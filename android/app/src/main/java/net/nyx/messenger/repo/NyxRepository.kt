package net.nyx.messenger.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import net.nyx.messenger.data.db.SeqStateDao
import net.nyx.messenger.data.db.SeqStateEntity
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
    private val seqStateDao: SeqStateDao,
) {
    /** Réplicas de rattachement — v0.1 : le premier configuré ; TODO : rotation.
     *  @JvmField : évite le clash JVM du setter généré avec [setReplicas]. */
    @JvmField
    var replicas: List<String> = emptyList()

    // Bug v0.2.2 n°2 : setReplicas n'était JAMAIS appelé (aucune liste de
    // rendezvous embarquée — ADR-009) et le client figeait le port SOCKS de
    // tor À SA CRÉATION, alors que les ports tor sont DYNAMIQUES (NyxTorConfig
    // sonde un port libre à chaque démarrage). Le relais est donc reconstruit
    // dès que le port courant change.
    private var relayPort: Int = -1
    private var relayClient: RelayClient? = null

    fun setReplicas(onions: List<String>) {
        replicas = onions.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        relayPort = -1
        relayClient = null
    }

    /** Relais courant (reconstruit si le port SOCKS de tor a changé). */
    private fun relay(): RelayClient? {
        val port = net.nyx.messenger.tor.TorManager.activeSocksPort
        val c = relayClient
        if (c != null && port == relayPort) return c
        val fresh = replicas.firstOrNull()?.let {
            RelayClient.forReplica(it, socksPort = port).also { _ ->
                android.util.Log.i("nyx-repo", "relais réplica : ${it.take(16)}… (socks $port)")
            }
        }
        relayClient = fresh
        relayPort = port
        return fresh
    }

    /** Sonde de joignabilité du réplica rattaché (GET /health via Tor).
     *
     *  v0.2.5 : chaque sonde alimente [lastProbe] (durée + cause d'échec) —
     *  l'écran Connexions affiche le détail au lieu d'un « injoignable » muet,
     *  et l'export de logs contient les tentatives (tag nyx-conn). */
    data class ReplicaProbeReport(val ok: Boolean, val ms: Long, val detail: String?)

    private val _lastProbe = MutableStateFlow<ReplicaProbeReport?>(null)
    val lastProbe: StateFlow<ReplicaProbeReport?> = _lastProbe.asStateFlow()

    suspend fun replicaHealth(): Boolean {
        val rep = relay()?.healthDetailed()
        if (rep == null) {
            _lastProbe.value = ReplicaProbeReport(false, 0, "aucun réplica configuré")
            return false
        }
        _lastProbe.value = ReplicaProbeReport(rep.ok, rep.ms, rep.detail)
        return rep.ok
    }

    /* ---------------- envoi ---------------- */

    suspend fun send(peerOnion: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = relay() ?: error("aucun réplica configuré")
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

            // 3. Stockage local (en attente) puis dépôt (auto-réparation seq v0.2.10)
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(id, peerOnion, outgoing = true, text, ev.ts, ev.ts, STATE_PENDING)
            )
            postSigned(relay, me, signed, canonical, priv).fold(
                onSuccess = { messageDao.updateState(id, STATE_SENT) },
                onFailure = { e ->
                    messageDao.updateState(id, STATE_FAILED)
                    throw e
                }
            )
        }
    }

    /**
     * v0.2.10 — réservation de séquence PERSISTÉE (table seq_state, v2).
     * Le compteur en mémoire de v0.1 repartait à 1 à chaque relance alors que le
     * journal exige seq = dernier+1 : les premiers événements après un
     * redémarrage étaient rejetés et les messages ne partaient jamais.
     * Le mutex sérialise les réservations concurrentes (envoi + claim alias…).
     */
    private val seqMutex = Mutex()

    private suspend fun nextSeqFor(author: String): Long = seqMutex.withLock {
        val next = (seqStateDao.get(author)?.seq ?: 0L) + 1
        seqStateDao.upsert(SeqStateEntity(author, next))
        next
    }

    /**
     * Dépôt d'un événement signé avec auto-réparation de séquence :
     *  - 409 « déjà connu » = l'événement était déjà dans le journal → succès ;
     *  - rejet de seq (« en attente (attendu N) » / « déjà dépassée (dernière N) »)
     *    → la table seq_state est recadrée sur le réplica puis l'événement est
     *    re-signé et redéposé UNE fois (dérive après restauration de base…).
     */
    private suspend fun postSigned(
        relay: RelayClient,
        author: String,
        ev: WireEvent,
        canonical: String,
        priv: ByteArray,
    ): Result<Unit> {
        val first = relay.postEvent(ev)
        if (first.isSuccess) return first
        val msg = first.exceptionOrNull()?.message ?: return first
        if (msg.contains("HTTP 409")) return Result.success(Unit) // idempotent
        val expected = Regex("attendu (\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull()
            ?: (Regex("dernière (\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull()?.plus(1))
            ?: return first
        seqMutex.withLock { seqStateDao.upsert(SeqStateEntity(author, expected)) }
        val retried = ev.copy(seq = expected)
        val canonical2 = MessageCipher.canonical(
            retried.type, retried.author, retried.seq, retried.ts, retried.ttl, retried.to ?: "", retried.body
        )
        return relay.postEvent(retried.copy(sig = cipher.signEvent(canonical2, priv)))
    }

    /* ---------------- réception ---------------- */

    /** Poll de la boîte de réception sur le réplica rattaché. */
    suspend fun pollInbox(): Int = withContext(Dispatchers.IO) {
        val relay = relay() ?: return@withContext 0
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

            // 3. Demande de contact (v0.2.10) : l'auteur s'annonce ("alias" chiffré).
            if (ev.type == "contact") {
                val declared = Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(plain)
                    ?.groupValues?.get(1).orEmpty()
                val already = contactDao.get(ev.author)
                if (already == null) {
                    contactDao.upsert(
                        ContactEntity(
                            ev.author.lowercase(),
                            declared.ifBlank { ev.author.take(10) + "…" },
                            System.currentTimeMillis()
                        )
                    )
                    // Message d'information dans la conversation — la carte du
                    // contact apparaît dans la liste (contacts = conversations).
                    val label = declared.ifBlank { ev.author.take(10) + "…" }
                    messageDao.insert(
                        MessageEntity(
                            eventIdOf(canonical), ev.author, outgoing = false,
                            "$label vous a ajouté comme contact.",
                            ev.ts, System.currentTimeMillis(), STATE_RECEIVED
                        )
                    )
                    inserted++
                }
                continue
            }

            // 4. Stockage local
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(id, ev.author, outgoing = false, plain, ev.ts, System.currentTimeMillis(), STATE_RECEIVED)
            )
            if (contactDao.get(ev.author) == null) {
                contactDao.upsert(
                    ContactEntity(ev.author, ev.author.take(10) + "…", System.currentTimeMillis())
                )
            }
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
            val relay = relay() ?: error("aucun réplica configuré — vérification à la première connexion")
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
            val relay = relay() ?: error("aucun réplica configuré")
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
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
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

    /**
     * v0.2.10 — ANNONCE DE CONTACT : l'ajout d'un contact n'est plus purement
     * local, un événement signé type « contact » (type standard du journal,
     * body chiffré {"alias":…} pour le destinataire) est déposé sur le réplica :
     * le pair voit la demande apparaître chez lui (carte + message d'info).
     * Si le réplica est injoignable, notifyPending=1 mémorise le devoir —
     * [ensureContactNotified] le repartage à la prochaine entrée sur la liste.
     */
    private suspend fun sendContactNotification(onionLower: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = relay() ?: error("aucun réplica configuré")
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()
                val peerPub = OnionCodec.onionToPubKey(onionLower)
                val bodyJson = "{\"alias\":\"${identity.alias ?: ""}\"}"
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "contact", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = onionLower, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            }
        }

    /** Repartage des annonces de contact restées en attente (réplica injoignable). */
    suspend fun ensureContactNotified(): Unit = withContext(Dispatchers.IO) {
        if (replicas.isEmpty()) return@withContext
        for (c in contactDao.pendingNotify()) {
            if (sendContactNotification(c.onion).isSuccess) {
                contactDao.setNotifyPending(c.onion, 0)
            }
        }
    }

    suspend fun addContact(onion: String, alias: String): Result<Boolean> = runCatching {
        OnionCodec.onionToPubKey(onion) // validation de l'adresse
        val lower = onion.lowercase()
        contactDao.upsert(ContactEntity(lower, alias.ifBlank { onion.take(10) + "…" }, System.currentTimeMillis()))
        // Annonce au pair — l'échec n'annule PAS l'ajout local : il sera reparti.
        val transmise = sendContactNotification(lower).isSuccess
        contactDao.setNotifyPending(lower, if (transmise) 0 else 1)
        transmise
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
                val relay = relay() ?: error("aucun réplica configuré")
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
                postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            }
        }

    /**
     * Carte publique d'un contact (GET /client/profile — dernier seq gagne,
     * §7.3). null si le contact n'a pas de profil public ou réseau indisponible.
     */
    suspend fun contactProfile(onion: String): Result<net.nyx.messenger.net.ProfileResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = relay() ?: error("aucun réplica configuré")
                relay.profile(onion.lowercase())
            }
        }

    /* ---------------- contacts par alias (v0.2.0) ---------------- */

    /** Résultat d'un ajout de contact par pseudonyme unique. */
    sealed class AliasAddResult {
        /** Contact ajouté (alias résolu → [onion]). [notified] : annonce transmise au pair. */
        data class Added(val onion: String, val alias: String, val notified: Boolean) : AliasAddResult()
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
            val relay = relay() ?: error("aucun réplica configuré")
            val res = relay.resolveAlias(name)
            if (!res.taken) return@runCatching AliasAddResult.Free
            val onion = res.by ?: error("résolution incomplète (alias pris sans détenteur)")
            OnionCodec.onionToPubKey(onion) // cohérence (l'adresse est la clé publique)
            val lower = onion.lowercase()
            contactDao.upsert(ContactEntity(lower, name, System.currentTimeMillis()))
            // Annonce au pair (v0.2.10) — différée si le réplica ne répond pas.
            val notified = sendContactNotification(lower).isSuccess
            contactDao.setNotifyPending(lower, if (notified) 0 else 1)
            AliasAddResult.Added(lower, name, notified)
        }
    }

    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
    }
}
