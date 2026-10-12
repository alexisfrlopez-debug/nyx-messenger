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
import java.security.SecureRandom

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
    /** Réplicas de rattachement — v0.1 : le premier configuré ; TODO : rotation. */
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
        // TODO v0.1 device : crypto_sign_verify_detached via lazysodium (sign interface)
        // La validation serveur existe déjà (journal.Validate) ; côté client elle protège
        // contre un réplica mensonger — à brancher avec le même appel libsodium.
        return sigB64.isNotBlank()
    }

    private fun eventIdOf(canonical: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /* ---------------- contacts ---------------- */

    fun contacts(): Flow<List<ContactEntity>> = contactDao.all()
    fun conversation(peer: String): Flow<List<MessageEntity>> = messageDao.conversation(peer)

    suspend fun addContact(onion: String, alias: String): Result<Unit> = runCatching {
        OnionCodec.onionToPubKey(onion) // validation de l'adresse
        contactDao.upsert(ContactEntity(onion.lowercase(), alias.ifBlank { onion.take(10) + "…" }, System.currentTimeMillis()))
    }

    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
    }
}

private val rng = SecureRandom() // réservé v0.2 (nonces X3DH)
