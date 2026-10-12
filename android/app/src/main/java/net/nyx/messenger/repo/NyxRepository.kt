package net.nyx.messenger.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.nyx.messenger.crypto.B64
import net.nyx.messenger.crypto.IdentityManager
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.data.db.ContactDao
import net.nyx.messenger.data.db.ContactEntity
import net.nyx.messenger.data.db.MessageDao
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.data.db.PollStateDao
import net.nyx.messenger.data.db.PollStateEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.net.RelayClient
import net.nyx.messenger.net.WireEvent

/**
 * Logique messagerie : envoi (chiffrement → signature → dépôt sur un réplica)
 * et réception (poll inbox → vérification → déchiffrement → stockage local).
 *
 * v0.1 : chiffrement sealed box ; v0.2 : Signal Protocol — seul ce fichier change.
 */
class NyxRepository(
    private val identity: IdentityManager,
    private val cipher: MessageCipher,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val pollStateDao: PollStateDao,
) {
    /** Réplicas de rattachement — v0.1 : le premier configuré ; TODO : rotation.
     *  @JvmField : évite le clash JVM du setter généré avec [setReplicas]. */
    @JvmField
    var replicas: List<String> = emptyList()
    private var client: RelayClient? = null

    fun setReplicas(onions: List<String>) {
        replicas = onions
        // SOCKS : port du Tor EMBARQUÉ (dynamique — ADR-007), plus Orbot requis.
        client = replicas.firstOrNull()?.let {
            RelayClient.forReplica(it, socksPort = net.nyx.messenger.tor.TorManager.activeSocksPort)
        }
    }

    /* ---------------- envoi ---------------- */

    suspend fun send(peerOnion: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = client ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peerOnion)

            // 1. Chiffrement E2E (sealed box, v0.1)
            val body = cipher.encryptFor(peerPub, text.toByteArray(Charsets.UTF_8))

            // 2. Événement signé
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "msg", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, ttl = 0,
                to = peerOnion, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val signed = ev.copy(sig = cipher.signEvent(canonical, priv))

            // 3. Stockage local (en attente) puis dépôt
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(id, peerOnion, outgoing = true, text, ev.ts, ev.ts, STATE_PENDING)
            )
            relay.postEvent(signed).fold(
                onSuccess = { messageDao.updateState(id, STATE_SENT) },
                onFailure = { e ->
                    messageDao.updateState(id, STATE_FAILED)
                    throw e
                }
            )
        }
    }

    private suspend fun nextSeqFor(author: String): Long {
        // v0.1 : compteur local en mémoire (persisté via les événements eux-mêmes) ;
        // TODO : persister (fichier scellé) pour survivre aux redémarrages
        return seqCounter++
    }

    private var seqCounter: Long = 1

    /* ---------------- réception ---------------- */

    /** Poll de la boîte de réception sur le réplica rattaché. */
    suspend fun pollInbox(): Int = withContext(Dispatchers.IO) {
        val relay = client ?: return@withContext 0
        val me = identity.onionAddress ?: return@withContext 0
        val priv = identity.secretKey()

        val cursor = pollStateDao.get(replicas.first())?.cursor ?: 0L
        val resp = relay.inbox(me, cursor)
        var inserted = 0
        for (ev in resp.events) {
            // 1. Vérification de la signature de l'auteur
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val authorPub = runCatching { OnionCodec.onionToPubKey(ev.author) }.getOrNull()
            val okSig = authorPub != null && verifySignature(canonical, ev.sig, authorPub)
            if (!okSig) continue

            // 2. Déchiffrement (sealed box v0.1)
            val plain = cipher.decryptForSelf(priv, ev.body)
                ?.toString(Charsets.UTF_8) ?: continue

            // 3. Stockage local
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(id, ev.author, outgoing = false, plain, ev.ts, System.currentTimeMillis(), STATE_RECEIVED)
            )
            contactDao.upsert(
                ContactEntity(ev.author, ev.author.take(10) + "…", System.currentTimeMillis())
            )
            inserted++
        }
        pollStateDao.upsert(PollStateEntity(replicas.first(), resp.cursor))
        inserted
    }

    private fun verifySignature(canonical: String, sigB64: String, pub: ByteArray): Boolean {
        // Vérification ed25519 réelle via libsodium — protège contre un réplica
        // mensonger (miroir de journal.Validate côté serveur Go).
        return ServiceLocator.cipher.verifyEvent(canonical, sigB64, pub)
    }

    private fun eventIdOf(canonical: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /* ---------------- contacts ---------------- */

    fun contacts(): Flow<List<ContactEntity>> = contactDao.all()
    fun conversation(peer: String): Flow<List<MessageEntity>> = messageDao.conversation(peer)

    /* ---------------- alias (v0.1.2 — wire-format.md §6) ---------------- */

    /** État de disponibilité d'un pseudonyme sur le réplica rattaché. */
    data class AliasInfo(val name: String, val taken: Boolean, val by: String? = null)

    /**
     * Vérifie la disponibilité d'un alias sur le réplica rattaché.
     * Échoue proprement si aucun réplica n'est configuré/joignable — l'UI
     * affiche alors que la confirmation se fera à la première connexion.
     */
    suspend fun checkAlias(name: String): Result<AliasInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = client ?: error("aucun réplica configuré — vérification à la première connexion")
            val res = relay.resolveAlias(name)
            AliasInfo(name, res.taken, res.by)
        }
    }

    /**
     * Réserve le pseudonyme : événement signé type "alias" (claim) déposé sur le
     * réplica rattaché — la convergence inter-réplicas suit la règle du plus petit
     * id (déterministe, wire-format.md §6.3).
     */
    suspend fun claimAlias(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = client ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val bodyJson = "{\"alias\":\"$name\",\"action\":\"claim\"}"
            val body = B64.encode(bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "alias", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, ttl = 0,
                to = null, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            relay.postEvent(ev.copy(sig = cipher.signEvent(canonical, priv))).getOrThrow()
        }
    }

    /**
     * Garantit que l'alias choisi (s'il existe) est réservé : appelé à l'entrée
     * sur la liste des conversations — si le claim avait différé (aucun réplica
     * joignable à l'onboarding), il part dès qu'un réplica répond.
     */
    suspend fun ensureAliasClaimed(): Unit = withContext(Dispatchers.IO) {
        val name = identity.alias ?: return@withContext
        if (identity.aliasClaimed || replicas.isEmpty()) return@withContext
        claimAlias(name).onSuccess { identity.markAliasClaimed() }
    }

    suspend fun addContact(onion: String, alias: String): Result<Unit> = runCatching {
        OnionCodec.onionToPubKey(onion) // validation de l'adresse
        contactDao.upsert(ContactEntity(onion.lowercase(), alias.ifBlank { onion.take(10) + "…" }, System.currentTimeMillis()))
    }

    /* ---------------- profil (v0.2.0 — wire-format.md §7) ---------------- */

    /**
     * Publie la carte PUBLIQUE du profil (seule la partie publique part —
     * invariant §7.2 : un champ privé ne quitte jamais l'appareil). Dépose un
     * événement `profile` en diffusion (to vide) signé, type standard du journal.
     */
    suspend fun publishProfile(store: net.nyx.messenger.profile.ProfileStore): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (bodyCard, err) = store.publicCard()
                err?.let { error(it) }
                val card = bodyCard ?: return@runCatching // rien de public : no-op
                val relay = client ?: error("aucun réplica configuré")
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()

                val body = B64.encode(
                    net.nyx.messenger.profile.ProfileCodec.encode(card).toByteArray(Charsets.UTF_8)
                )
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "profile", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = null, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                relay.postEvent(ev.copy(sig = cipher.signEvent(canonical, priv))).getOrThrow()
            }
        }

    /**
     * Carte publique d'un contact (GET /client/profile — dernier seq gagne,
     * §7.3). null si le contact n'a pas de profil public ou réseau indisponible.
     */
    suspend fun contactProfile(onion: String): Result<net.nyx.messenger.net.ProfileResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = client ?: error("aucun réplica configuré")
                relay.profile(onion.lowercase())
            }
        }

    /* ---------------- contacts par alias (v0.2.0) ---------------- */

    /** Résultat d'un ajout de contact par pseudonyme unique. */
    sealed class AliasAddResult {
        /** Contact ajouté (alias résolu → [onion]). */
        data class Added(val onion: String, val alias: String) : AliasAddResult()
        /** Alias libre sur le réplica : personne ne le détenteur. */
        data object Free : AliasAddResult()
    }

    /**
     * Ajoute un contact à partir de son ALIAS UNIQUE (invitations « @pseudo ») :
     * résolution via GET /client/alias (wire-format.md §6.4) puis enregistrement
     * local de l'adresse onion résolue. Aucun listing global : résolution
     * point à point uniquement.
     */
    suspend fun addContactByAlias(alias: String): Result<AliasAddResult> = withContext(Dispatchers.IO) {
        runCatching {
            val name = net.nyx.messenger.crypto.AliasValidator.normalize(alias)
            check(net.nyx.messenger.crypto.AliasValidator.isValid(name)) { "format d'alias invalide" }
            val relay = client ?: error("aucun réplica configuré")
            val res = relay.resolveAlias(name)
            if (!res.taken) return@runCatching AliasAddResult.Free
            val onion = res.by ?: error("résolution incomplète (alias pris sans détenteur)")
            OnionCodec.onionToPubKey(onion) // cohérence (l'adresse est la clé publique)
            contactDao.upsert(ContactEntity(onion.lowercase(), name, System.currentTimeMillis()))
            AliasAddResult.Added(onion, name)
        }
    }

    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
    }
}
