package net.nyx.messenger.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.nyx.messenger.crypto.AliasValidator
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.desktop.DesktopIdentity
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.ACCEPTED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.DECLINED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.INCOMING
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.PENDING
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.REMOVED
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.REMOVED_BY_ME
import net.nyx.messenger.media.MediaIngest
import net.nyx.messenger.net.BootstrapReplicas
import net.nyx.messenger.net.RelayClient
import net.nyx.messenger.net.WireEvent
import net.nyx.messenger.replica.EmbeddedReplica
import net.nyx.messenger.replica.ReplicaValidate
import net.nyx.messenger.repo.ReplicaCodec
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Logique messagerie desktop — portage de NyxRepository Android (v1 :
 * journal seul — pas d'appels, pas de P2P direct, pas de NYX-LINK : ADR-019).
 *
 * Mêmes invariants que l'Android :
 *  - dépôt = LA BOÎTE DU PAIR sinon LA FLOTTE (DepositRouting) ;
 *  - lecture = réplica PROPRE en procédé + flotte en round-robin ;
 *  - seq persistée par auteur + auto-réparation (SeqResync) ;
 *  - messages réservés aux contacts MUTUELS (verrou d'échange) ;
 *  - accusés ✓✓ par le canal « contact » chiffré ;
 *  - fichiers par fragments d'événements « msg » (ChunkCodec).
 *
 * v0.3.4 « parité Android » : politique de vue (once/twice) au envoi ET à la
 * consommation, suppression « pour tous » différée (del_pending + flush 30 s),
 * suppression locale / de conversation, profils complets (cartes
 * {value,public} + champ auto replica + limites fermées — ADR-010), TTL
 * éphémère exposé, export/import des conversations (format nyx-conversations
 * v1 — interopérable avec l'Android), compression d'images ≤ 1600 px,
 * rafraîchissement des fiches pair (TTL 1 h).
 */
class DesktopRepository(
    private val identity: DesktopIdentity,
    private val store: DesktopStore,
    private val socksPortProvider: () -> Int,
    private val descriptorRefresher: ((onion: String) -> Result<Unit>)? = null,
) {
    private val cipher = MessageCipher.INSTANCE
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @JvmField
    var replicas: List<String> = emptyList()

    private val seqMutex = Mutex()
    private val pollMutex = Mutex()

    /** v0.3.4 — événements sortants pour les notifications plateau (miroir
     * MessageNotifier/ContactNotifier Android). Jamais d'exception attendue. */
    var onIncomingMessage: ((peer: String, text: String) -> Unit)? = null
    var onIncomingInvite: ((peer: String, alias: String) -> Unit)? = null
    var onContactAck: ((peer: String, ack: String, alias: String) -> Unit)? = null

    /** v0.3.6 — signalisation d'appel reçue (kind=call — offert au DesktopCallManager,
     * JAMAIS stockée comme message ; miroir callSignalHandler Android). */
    var callSignalHandler: ((author: String, signal: net.nyx.messenger.p2p.CallSignal) -> Unit)? = null

    // ---------------- envoi ----------------

    suspend fun send(peerOnion: String, text: String, ttlSeconds: Long = 0L): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                DesktopLog.i("nyx-send", "→ msg peer=${peerOnion.trim().take(16)}… len=${text.length}")
                val peer = peerOnion.trim().lowercase()
                val link = store.contact(peer)
                if (link?.status != ACCEPTED) {
                    error(
                        if (link == null) "ce destinataire n'est pas dans vos contacts"
                        else "échange possible uniquement une fois l'invitation acceptée des deux côtés"
                    )
                }
                if (link.blockedAt > 0) error("contact bloqué — débloquez-le pour lui écrire")

                val depositAddr = depositTargetFor(peer) ?: error("aucun réplica configuré")
                val relay = relayFor(depositAddr)
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()
                val peerPub = OnionCodec.onionToPubKey(peer)

                val body = cipher.encryptFor(peerPub, text.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "msg", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = ttlSeconds,
                    to = peer, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(
                    ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body
                )
                val signed = ev.copy(sig = cipher.signEvent(canonical, priv))

                val id = eventIdOf(canonical)
                store.insertMessage(
                    DesktopStore.Msg(
                        id = id, peer = peer, outgoing = true, text = text, ts = ev.ts,
                        state = DesktopStore.STATE_PENDING,
                        expireAt = if (ttlSeconds > 0) System.currentTimeMillis() + ttlSeconds * 1000 else 0L
                    )
                )
                postSigned(relay, me, signed, canonical, priv).fold(
                    onSuccess = { final ->
                        // v0.3.7 — re-alignment d'id : au resync de séquence le
                        // dépôt part avec un seq CORRIGÉ (nouveau canonical →
                        // nouvel id) ; la ligne locale doit suivre SINON l'accusé
                        // du pair pointe dans le vide (bulle « ✓ » à vie —
                        // cause racine « toujours problème d'accusés »).
                        val finalId = if (final.seq == seq) id else {
                            val finalCanonical = MessageCipher.canonical(
                                final.type, final.author, final.seq, final.ts, final.ttl, final.to ?: "", final.body
                            )
                            val fid = eventIdOf(finalCanonical)
                            store.rekeyMessage(id, fid)
                            fid
                        }
                        store.updateMessage(finalId) { it.state = DesktopStore.STATE_SENT }
                        DesktopLog.i("nyx-send", "   ✓ déposé (seq=${final.seq})")
                    },
                    onFailure = { e ->
                        store.updateMessage(id) { it.state = DesktopStore.STATE_FAILED }
                        DesktopLog.w("nyx-send", "   ✗ dépôt ÉCHOUÉ (seq=$seq) : ${e.message}")
                        throw e
                    }
                )
            }
        }

    /**
     * v0.2.17 (portage) — envoi de FICHIER : fragments d'événements « msg »
     * chiffrés E2E (≤ 96 Ko binaires par fragment), bulle locale immédiate.
     * v0.3.4 : [view] = politique d'affichage chez le destinataire
     * ("forever"/"once"/"twice" — miroir v0.2.28) + compression d'image
     * ≤ 1600 px JPEG q82 (miroir MediaIngest) + progression répercutée dans
     * AttachmentMeta.bytesReceived (barre de transfert côté UI).
     */
    suspend fun sendFile(
        peerOnion: String,
        file: File,
        view: String = "forever",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val peer = peerOnion.trim().lowercase()
            val link = store.contact(peer)
                ?: error("ce destinataire n'est pas dans vos contacts")
            if (link.status != ACCEPTED) error("échange possible uniquement entre contacts acceptés")
            if (link.blockedAt > 0) error("contact bloqué")

            // Compression d'image (miroir Android MediaIngest : ≤ 1600 px,
            // JPEG q82, sauf GIF) — best-effort : échec = octets d'origine.
            var safeName = ChunkCodec.sanitizeName(file.name)
            var mime = guessMime(safeName)
            var bytes = file.readBytes()
            if (mime.startsWith("image/") && mime != "image/gif") {
                val compressed = MediaIngest.compressImage(bytes)
                if (compressed != null && compressed.size < bytes.size) {
                    bytes = compressed
                    mime = "image/jpeg"
                    if (!safeName.endsWith(".jpg", true) && !safeName.endsWith(".jpeg", true)) {
                        safeName = safeName.substringBeforeLast('.') + ".jpg"
                    }
                }
            }
            if (bytes.size > ChunkCodec.MAX_FILE_SIZE) {
                error("fichier trop volumineux (${bytes.size / (1024 * 1024)} Mo — max 64 Mo)")
            }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val fid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val ts = System.currentTimeMillis() / 1000
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peer)

            // Bulle locale immédiate (miroir Android mediaPlaceholder — v0.3.4 :
            // préfixe emoji par type, miroir "🖼/🎬/🎤/📄").
            val firstCanonical = "local:$fid"
            store.insertMessage(
                DesktopStore.Msg(
                    id = firstCanonical, peer = peer, outgoing = true,
                    text = placeholderFor(mime, safeName), ts = ts, state = DesktopStore.STATE_PENDING,
                    kind = DesktopStore.KIND_FILE, fid = fid
                )
            )
            store.upsertAttachment(
                DesktopStore.AttachmentMeta(
                    fid = fid, name = safeName, mime = mime,
                    size = bytes.size.toLong(), sha256 = sha256,
                    state = DesktopStore.AT_READY, localPath = file.absolutePath,
                    viewPolicy = view, viewsLeft = -1
                )
            )

            var sent = 0
            var failed: Throwable? = null
            val parts = ChunkCodec.split(bytes)
            for ((index, chunk) in parts.withIndex()) {
                val payload = ChunkCodec.encode(
                    fid, safeName, mime, bytes.size.toLong(), sha256,
                    index, parts.size, Base64.getEncoder().encodeToString(chunk), view
                )
                val body = cipher.encryptFor(peerPub, payload.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "msg", author = me, seq = seq, ts = System.currentTimeMillis() / 1000,
                    to = peer, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(
                    ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body
                )
                val signed = ev.copy(sig = cipher.signEvent(canonical, priv))
                val depositAddr = depositTargetFor(peer) ?: error("aucun réplica configuré")
                val relay = relayFor(depositAddr)
                val r = postSigned(relay, me, signed, canonical, priv)
                if (r.isSuccess) {
                    sent++
                    // v0.3.4 — barre de progression d'ENVOI (octets déposés).
                    store.updateAttachment(fid) { it.bytesReceived = (sent * ChunkCodec.CHUNK_PAYLOAD).toLong().coerceAtMost(it.size) }
                } else {
                    failed = r.exceptionOrNull() ?: error("dépôt de fragment échoué")
                    break
                }
            }
            if (failed != null) {
                store.updateMessage(firstCanonical) { it.state = DesktopStore.STATE_FAILED }
                throw failed
            }
            store.updateMessage(firstCanonical) { it.state = DesktopStore.STATE_SENT }
            DesktopLog.i("nyx-send", "✓ fichier $safeName déposé en ${parts.size} fragment(s)")
        }
    }

    // ---------------- appels (v0.3.6 — portage miroir Android) ----------------

    /** Alias local déclaré (sonnerie du pair — miroir myAlias Android). */
    fun myAlias(): String = identity.alias ?: ""

    /** Contact bloqué localement ? (défense en profondeur CallManager.) */
    fun isContactBlocked(peer: String): Boolean =
        (store.contact(peer)?.blockedAt ?: 0L) > 0L

    /**
     * Signalisation d'appel sur le journal (ADR-015) : corps « msg » chiffré
     * E2E, JSON kind=call — type DÉJÀ whitelisté par le réplica (zéro
     * changement serveur), INVISIBLE pour lui. CIBLE DÉTERMINISTE (v0.2.50
     * Android miroir) : LA BOÎTE DU PAIR sinon la flotte.
     */
    suspend fun sendCallSignal(peerLower: String, json: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()
                val peerPub = OnionCodec.onionToPubKey(peerLower)
                val body = cipher.encryptFor(peerPub, json.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "msg", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = peerLower, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                val signed = ev.copy(sig = cipher.signEvent(canonical, priv))
                val depositAddr = depositTargetFor(peerLower) ?: error("aucun réplica configuré")
                postSigned(relayFor(depositAddr), me, signed, canonical, priv).getOrThrow()
            Unit
            }
        }

    /**
     * Trace d'appel dans la conversation (miroir addCallInfo Android v0.2.18 —
     * « Appel manqué » etc. ; jamais comptée comme non lue).
     */
    suspend fun addCallInfo(peer: String, outgoing: Boolean, text: String) {
        store.insertMessage(
            DesktopStore.Msg(
                id = "call-${System.nanoTime()}", peer = peer, outgoing = outgoing, text = text,
                ts = System.currentTimeMillis() / 1000, state = DesktopStore.STATE_RECEIVED, readLocal = 1
            )
        )
    }

    /** v0.3.4 — intitulé de la bulle locale (miroir Android "🖼/🎬/🎤/📄 <nom>"). */
    internal fun placeholderFor(mime: String, name: String): String = when {
        mime.startsWith("image/") -> "🖼 $name"
        mime.startsWith("video/") -> "🎬 $name"
        mime.startsWith("audio/") -> "🎤 $name"
        else -> "📄 $name"
    }

    // ---------------- dépôt (auto-réparation seq — miroir v0.2.10/52) ----------------

    private suspend fun postSigned(
        relay: RelayClient,
        author: String,
        ev: WireEvent,
        canonical: String,
        priv: ByteArray,
    ): Result<WireEvent> {
        val first = relay.postEvent(ev)
        if (first.isSuccess) return Result.success(ev)
        val msg = first.exceptionOrNull()?.message ?: return Result.failure(first.exceptionOrNull()!!)
        if (msg.contains("HTTP 409")) return Result.success(ev) // idempotent
        val expected = SeqResync.expectedFrom(msg) ?: return Result.failure(first.exceptionOrNull()!!)
        seqMutex.withLock { store.upsertSeq(author, expected) }
        val retried = ev.copy(seq = expected)
        val canonical2 = MessageCipher.canonical(
            retried.type, retried.author, retried.seq, retried.ts, retried.ttl, retried.to ?: "", retried.body
        )
        // v0.3.7 — on rend l'événement DÉFINITIF (seq corrigé) : l'appelant
        // re-aligne l'id local (la dérive d'id tuait l'accusé du pair — le
        // canonical contient le seq, donc l'id CHANGE au resync).
        val retry = relay.postEvent(retried.copy(sig = cipher.signEvent(canonical2, priv)))
        return if (retry.isSuccess) Result.success(retried) else Result.failure(retry.exceptionOrNull()!!)
    }

    private suspend fun nextSeqFor(author: String): Long = seqMutex.withLock {
        val next = store.seqOf(author) + 1
        store.upsertSeq(author, next)
        next
    }

    // ---------------- réception ----------------

    suspend fun pollInbox(): Int {
        if (!pollMutex.tryLock()) return 0
        try {
            return withContext(Dispatchers.IO) {
                val me = identity.onionAddress ?: return@withContext 0
                val priv = identity.secretKey()
                var inserted = 0
                inserted += pollOwnReplica(me, priv)
                inserted += pollFleetReplicas(me, priv)
                // v0.3.6 — accusés en attente (miroir Android : tentative
                // immédiate en fin de poll, DIFFÉRÉ au cycle suivant en cas
                // d'échec — l'état n'est plus jamais perdu).
                runCatching { ensureReceiptsSent() }
                // v0.3.4 — repartie des suppressions différées à chaque poll (miroir Android).
                runCatching { ensureDeletionsSent() }
                inserted
            }
        } finally {
            pollMutex.unlock()
        }
    }

    private suspend fun pollOwnReplica(me: String, priv: ByteArray): Int {
        val hadCursor = store.hasCursor("own:$me")
        val cursor = store.cursor("own:$me")
        val authTs = System.currentTimeMillis() / 1000
        val auth = "$authTs:" + cipher.signEvent(ReplicaValidate.inboxCanonical(me, authTs), priv)
        val (code, body) = EmbeddedReplica.dispatch(
            "GET", "/client/inbox",
            mapOf("to" to me, "after" to cursor.toString(), "limit" to "32", "auth" to auth),
            ByteArray(0)
        )
        if (code != 200) {
            if (code != 503) DesktopLog.w("nyx-poll", "inbox PROPRE (réplica embarqué) → $code ${body.take(80)}")
            return 0
        }
        val resp = runCatching { json.decodeFromString(net.nyx.messenger.net.InboxResponse.serializer(), body) }
            .getOrNull() ?: return 0
        val floor = if (hadCursor) 0L else System.currentTimeMillis() / 1000 - 120
        var inserted = 0
        for (ev in resp.events) {
            if (ev.ts < floor) continue
            if (ingestInboxEvent(ev, priv) == Ingest.NEW) inserted++
        }
        if (resp.cursor > cursor) store.setCursor("own:$me", resp.cursor)
        if (inserted > 0) DesktopLog.i("nyx-poll", "← inbox PROPRE $inserted évts")
        return inserted
    }

    private suspend fun pollFleetReplicas(me: String, priv: ByteArray): Int {
        val targets = DepositRouting.pollTargets(replicas) { addr -> isPeerPhone(addr) }
        var inserted = 0
        for (addr in targets) {
            val key = "fleet:$addr"
            val cursor = store.cursor(key)
            val relay = relayFor(addr)
            val res = relay.inbox(me, cursor, limit = 100)
            res.fold(
                onSuccess = { resp ->
                    for (ev in resp.events) {
                        if (ingestInboxEvent(ev, priv) == Ingest.NEW) inserted++
                    }
                    if (resp.cursor > cursor) store.setCursor(key, resp.cursor)
                },
                onFailure = { e ->
                    DesktopLog.w("nyx-poll", "réplica ${addr.take(16)}… en échec (poll inbox) : ${e.message?.take(80)}")
                }
            )
        }
        return inserted
    }

    private enum class Ingest { NEW, APPLIED, REJECTED }

    private suspend fun ingestInboxEvent(ev: WireEvent, priv: ByteArray): Ingest {
        val author = ev.author.trim().lowercase()
        val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
        val authorPub = runCatching { OnionCodec.onionToPubKey(author) }.getOrNull()
        if (authorPub == null || !cipher.verifyEvent(canonical, ev.sig, authorPub)) {
            DesktopLog.w("nyx-recv", "   signature invalide type=${ev.type} author=${author.take(16)}…")
            return Ingest.REJECTED
        }
        // HSFETCH de guérison sur échec (portage v0.2.45 — injecté par le ServiceLocator).
        val plain = cipher.decryptForSelf(priv, ev.body)?.toString(Charsets.UTF_8)
        if (plain == null) {
            DesktopLog.w("nyx-recv", "   déchiffrement impossible type=${ev.type} author=${author.take(16)}…")
            return Ingest.REJECTED
        }

        if (ev.type == "contact") return ingestContactBody(author, canonical, ev, plain)

        // Messages réservés aux contacts MUTUELS (verrou d'échange — miroir v0.2.14).
        val link = store.contact(author)
        if (ev.type == "msg" && link?.status != ACCEPTED && link?.status != PENDING) {
            DesktopLog.w("nyx-recv", "   ✗ msg de ${author.take(16)}… ignoré (pas un contact mutuel)")
            return Ingest.REJECTED
        }
        if (ev.type == "msg" && (link?.blockedAt ?: 0L) > 0) return Ingest.REJECTED

        // Fragment de fichier ?
        val chunk = ChunkCodec.parse(plain)
        if (chunk != null) {
            handleIncomingChunk(author, canonical, ev.ts, chunk)
            return if (chunk.index == 0) Ingest.NEW else Ingest.APPLIED
        }

        // Signalisation d'appel (v0.3.6 — portage miroir Android) : parse AVANT
        // le stockage — une offre/rejet/raccroché n'est JAMAIS un message.
        // Garde anti-sonnerie fantôme (v0.2.21 miroir) : une signalisation
        // VIEILLE (re-livrée après restauration ou très en retard) ne doit pas
        // déclencher d'appel — 120 s de marge sur un poll de 30 s.
        val callSignal = net.nyx.messenger.p2p.CallSignal.parse(plain)
        if (callSignal != null) {
            if (ev.ts >= System.currentTimeMillis() / 1000 - 120) {
                callSignalHandler?.invoke(author, callSignal)
                return Ingest.APPLIED
            } else {
                DesktopLog.i("nyx-recv", "   · signalisation d'appel périmée de ${author.take(16)}… — ignorée")
                return Ingest.REJECTED
            }
        }

        // Éphémère expiré ?
        if (ev.ttl > 0 && System.currentTimeMillis() / 1000 >= ev.ts + ev.ttl) return Ingest.REJECTED

        val id = eventIdOf(canonical)
        store.insertMessage(
            DesktopStore.Msg(
                id = id, peer = author, outgoing = false, text = plain, ts = ev.ts,
                state = DesktopStore.STATE_RECEIVED, rcptPending = 1,
                expireAt = if (ev.ttl > 0) System.currentTimeMillis() + ev.ttl * 1000 else 0L
            )
        )
        if (store.contact(author) == null) {
            store.upsertContact(
                DesktopStore.Contact(
                    onion = author, alias = author.take(10) + "…", addedAt = System.currentTimeMillis()
                )
            )
        }
        DesktopLog.i("nyx-recv", "   ✓ ${ev.type} de ${author.take(16)}… stocké")
        if (ev.type == "msg") onIncomingMessage?.invoke(author, plain)
        return Ingest.NEW
    }

    private suspend fun ingestContactBody(author: String, canonical: String, ev: WireEvent, plain: String): Ingest {
        // 1. Accusé ✓/✓✓ (traité en premier — miroir v0.2.15).
        val receipt = ReceiptCodec.parse(plain)
        if (receipt != null) {
            val (kind, ids) = receipt
            if (ids.isNotEmpty()) {
                val now = System.currentTimeMillis()
                for (id in ids) {
                    val m = store.message(id)
                    if (m != null && m.peer == author && m.outgoing) {
                        store.updateMessage(id) {
                            if (kind == ReceiptCodec.KIND_DELIVERED) {
                                if (it.rcptDelivered == 0L) it.rcptDelivered = now
                            } else {
                                if (it.rcptDelivered == 0L) it.rcptDelivered = now
                                it.rcptRead = now
                            }
                        }
                    }
                }
                DesktopLog.i("nyx-recv", "   ✓ accusé « $kind » de ${author.take(16)}… (${ids.size} msg)")
            }
            return Ingest.APPLIED
        }
        // 2. Suppression distante (asymétrie : uniquement nos messages reçus de lui).
        val delIds = DeleteCodec.parse(plain)
        if (delIds != null) {
            if (delIds.isNotEmpty()) {
                val rows = store.messagesByIds(delIds).filter { it.peer == author && !it.outgoing }
                store.deleteMessages(delIds)
                for (m in rows) if (m.kind == DesktopStore.KIND_FILE && m.fid.isNotBlank()) {
                    store.deleteAttachmentFile(m.fid)
                }
                DesktopLog.i("nyx-recv", "   ✓ suppression distante de ${author.take(16)}…")
            }
            return Ingest.APPLIED
        }
        // 3. Annonce de réplica (garde-fous : adresse = auteur + contact accepté).
        val replicaAnn = ReplicaCodec.parse(plain)
        if (replicaAnn != null) {
            val linkOk = store.contact(author)?.status == ACCEPTED
            if (ReplicaCodec.isSelfAnnounced(author, replicaAnn) && linkOk) {
                val changed = net.nyx.messenger.net.ReplicaRegistry.ingest(
                    replicaAnn.replica, System.currentTimeMillis()
                )
                DesktopLog.i(
                    "nyx-recv",
                    "   ✓ annonce réplica de ${author.take(16)}… → ${replicaAnn.replica.take(24)}…${if (changed) " (persistée)" else " (connue)"}"
                )
                onChangedReplicas()
            }
            return Ingest.APPLIED
        }
        // 4. Invitation / ACK (cycle de vie du lien).
        val declared = Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(plain)?.groupValues?.get(1).orEmpty()
        val ack = Regex("\"ack\"\\s*:\\s*\"([^\"]*)\"").find(plain)?.groupValues?.get(1).orEmpty()
        val label = declared.ifBlank { author.take(10) + "…" }
        val already = store.contact(author)
        when {
            ack == "accepted" || ack == "declined" || ack == "removed" -> {
                if (already == null && ack != "accepted") return Ingest.REJECTED
                if (already != null && archived(already.status)) return Ingest.REJECTED
                val newStatus = when (ack) {
                    "accepted" -> ACCEPTED
                    "declined" -> DECLINED
                    else -> REMOVED
                }
                if (already != null) {
                    store.updateContact(author) {
                        it.status = newStatus; it.notifyPending = 0
                    }
                }
                val phrase = when (ack) {
                    "accepted" -> "$label a accepté votre invitation."
                    "declined" -> "$label a décliné votre invitation."
                    else -> "$label vous a supprimé de sa liste de contacts."
                }
                store.insertMessage(
                    DesktopStore.Msg(
                        id = eventIdOf(canonical), peer = author, outgoing = false,
                        text = phrase, ts = ev.ts, state = DesktopStore.STATE_RECEIVED, readLocal = 1
                    )
                )
                DesktopLog.i("nyx-recv", "   ✓ ack « $ack » de ${author.take(16)}… ($label)")
                onContactAck?.invoke(author, ack, label)
                return Ingest.NEW
            }
            already == null -> {
                store.upsertContact(
                    DesktopStore.Contact(
                        onion = author, alias = label, addedAt = System.currentTimeMillis(),
                        notifiedAt = System.currentTimeMillis(), status = INCOMING
                    )
                )
                store.insertMessage(
                    DesktopStore.Msg(
                        id = eventIdOf(canonical), peer = author, outgoing = false,
                        text = "$label vous a ajouté comme contact.",
                        ts = ev.ts, state = DesktopStore.STATE_RECEIVED, readLocal = 1
                    )
                )
                DesktopLog.i("nyx-recv", "   ✓ demande de contact de ${author.take(16)}… ($label)")
                onIncomingInvite?.invoke(author, label)
                return Ingest.NEW
            }
            already.status == PENDING -> {
                // Ajout mutuel : équivalent accept + ACK explicite reparti.
                store.updateContact(author) { it.status = ACCEPTED; it.notifyPending = 1 }
                store.insertMessage(
                    DesktopStore.Msg(
                        id = eventIdOf(canonical), peer = author, outgoing = false,
                        text = "$label vous a ajouté en retour : invitation acceptée.",
                        ts = ev.ts, state = DesktopStore.STATE_RECEIVED, readLocal = 1
                    )
                )
                DesktopLog.i("nyx-recv", "   ✓ ajout mutuel de ${author.take(16)}… ($label) → ack reparti")
                return Ingest.NEW
            }
            else -> {
                if (archived(already.status)) {
                    store.updateContact(author) { it.status = INCOMING }
                    return Ingest.NEW
                }
                return Ingest.REJECTED
            }
        }
    }

    private fun archived(status: ContactStatusAny): Boolean =
        status == DECLINED || status == REMOVED || status == REMOVED_BY_ME

    // ---------------- suppressions (v0.3.4 — miroir v0.2.19 « pour tous »/« pour moi ») ----------------

    /**
     * « Supprimer pour tous » : la copie LOCALE disparaît immédiatement, la
     * suppression chez le destinataire part par le canal contact chiffré
     * (différée si le réplica est injoignable — miroir del_pending Android).
     * Garde-fous miroir : contact ACCEPTé uniquement, propres messages sortis.
     */
    suspend fun deleteForEveryone(peerOnion: String, ids: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val peer = peerOnion.trim().lowercase()
                val link = store.contact(peer)
                if (link?.status != ACCEPTED) {
                    error("suppression « pour tous » réservée aux contacts acceptés")
                }
                val mine = store.messagesByIds(ids).filter { it.outgoing && it.peer == peer }.map { it.id }
                if (mine.isEmpty()) {
                    error("seuls vos propres messages peuvent être supprimés chez le destinataire")
                }
                // Copie locale d'abord (+ fichiers joints) — l'utilisateur voit
                // immédiatement l'effet (miroir Android).
                deleteLocallyInternal(mine)
                val pending = store.delPendingList().firstOrNull { it.peer == peer }?.ids.orEmpty()
                store.addDelPending(peer, (pending + mine).distinct())
                ensureDeletionsSent()
            }
        }

    /** « Supprimer pour moi » : copie LOCALE de CET appareil uniquement. */
    suspend fun deleteLocally(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { deleteLocallyInternal(ids) }
    }

    private fun deleteLocallyInternal(ids: List<String>) {
        val rows = store.messagesByIds(ids)
        store.deleteMessages(ids)
        for (m in rows) if (m.kind == DesktopStore.KIND_FILE && m.fid.isNotBlank()) {
            store.deleteAttachmentFile(m.fid)
        }
    }

    /** Suppression LOCALE de toute la conversation (miroir Android : le contact reste). */
    suspend fun deleteConversationLocal(peerOnion: String): Int = withContext(Dispatchers.IO) {
        store.deleteConversation(peerOnion.trim().lowercase())
    }

    /** Suppression d'un média (onglet Fichiers & médias — miroir Android). */
    suspend fun deleteAttachment(peerOnion: String, fid: String, forEveryone: Boolean): Result<Unit> {
        val peer = peerOnion.trim().lowercase()
        val carrier = store.messagesWith(peer).firstOrNull { it.kind == DesktopStore.KIND_FILE && it.fid == fid }
            ?: return Result.failure(IllegalStateException("média introuvable"))
        return if (forEveryone && carrier.outgoing) {
            deleteForEveryone(peer, listOf(carrier.id))
        } else {
            deleteLocally(listOf(carrier.id))
        }
    }

    /** Repartie des suppressions différées (miroir ensureDeletionsSent — boucle 30 s + post-suppression). */
    suspend fun ensureDeletionsSent() {
        if (replicas.isEmpty()) return
        val me = identity.onionAddress ?: return
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return
        for (p in store.delPendingList()) {
            if (p.ids.isEmpty()) { store.removeDelPending(p.peer); continue }
            val link = store.contact(p.peer)
            // Lien plus accepté → abandon (miroir Android : suppression muette).
            if (link?.status != ACCEPTED) { store.removeDelPending(p.peer); continue }
            runCatching {
                val peerPub = OnionCodec.onionToPubKey(p.peer)
                val bodyJson = DeleteCodec.encode(identity.alias ?: "", p.ids)
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "contact", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, to = p.peer, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                val depositAddr = depositTargetFor(p.peer) ?: error("aucun réplica configuré")
                postSigned(relayFor(depositAddr), me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
                store.removeDelPending(p.peer)
                DesktopLog.i("nyx-send", "✓ suppression « pour tous » déposée (${p.ids.size} msg → ${p.peer.take(16)}…)")
            }
        }
    }

    /**
     * v0.3.4 — consommation d'une vue sur un média à affichage limité (miroir
     * consumeMediaView v0.2.28) : décrémente, DÉTRUIT le fichier local quand
     * il ne reste plus de vue. Retourne les vues restantes.
     */
    fun consumeMediaView(fid: String): Int {
        val left = store.consumeView(fid)
        if (left <= 0) {
            store.updateAttachment(fid) { it.localPath = "" }
            store.deleteAttachmentFile(fid)
            DesktopLog.i("nyx-media", "média à vue limitée détruit ($fid)")
        }
        return left
    }

    /** Réception d'un fragment (miroir handleIncomingChunk — v0.3.4 : politique de vue). */
    private fun handleIncomingChunk(author: String, canonical: String, ts: Long, chunk: ChunkCodec.FileChunk) {
        var meta = store.attachment(chunk.fid)
        if (meta == null) {
            meta = DesktopStore.AttachmentMeta(
                fid = chunk.fid, name = chunk.name, mime = chunk.mime,
                size = chunk.size, sha256 = chunk.sha256, state = DesktopStore.AT_PENDING,
                viewPolicy = chunk.view,
                viewsLeft = when (chunk.view) {
                    DesktopStore.VIEW_ONCE -> 1
                    DesktopStore.VIEW_TWICE -> 2
                    else -> -1
                }
            )
            store.upsertAttachment(meta)
            store.insertMessage(
                DesktopStore.Msg(
                    id = eventIdOf(canonical), peer = author, outgoing = false,
                    text = placeholderFor(chunk.mime, chunk.name), ts = ts, state = DesktopStore.STATE_RECEIVED,
                    kind = DesktopStore.KIND_FILE, fid = chunk.fid, rcptPending = 1
                )
            )
        }
        store.putChunk(chunk.fid, chunk.index, Base64.getDecoder().decode(chunk.data))
        val have = store.chunkCount(chunk.fid)
        if (have >= chunk.total) {
            val f = store.assembleAttachment(chunk.fid, chunk.total, chunk.sha256)
            store.upsertAttachment(meta.apply {
                state = if (f != null) DesktopStore.AT_READY else DesktopStore.AT_FAILED
                localPath = f?.absolutePath ?: ""
            })
        } else {
            store.upsertAttachment(meta.apply { bytesReceived = have.toLong() })
        }
    }

    // ---------------- accusés ----------------

    /**
     * v0.3.6 — DÉPÔT DES ACCUSÉS EN ATTENTE (miroir EXACT de ensureReceiptsSent
     * Android v0.2.15, retour terrain « les accusés de réception ne fonctionnent
     * pas entre l'app tel et l'app windows ») :
     *  - « read » D'ABORD (il couvre le « delivered » — impliqué côté
     *    expéditeur) puis « delivered » seul ;
     *  - lots de [RECEIPT_BATCH] ids (body raisonnable) ;
     *  - rcptPending n'est vidé QUE SI LE DÉPÔT RÉUSSIT — avant, l'échec
     *    (SOCKS/Tor passager) était ignoré ET l'état effacé : l'accusé était
     *    PERDU À JAMAIS sans retry (l'Android retente chaque cycle —
     *    « DIFFÉRÉ ») ;
     *  - seuls les liens ACCEPTÉS reçoivent des accusés (pas d'accusé vers un
     *    pair archivé/supprimé — même verrou que l'envoi de messages).
     * Appelé en fin de pollInbox (tentative immédiate) — le cycle suivant
     * recommence tant qu'un accusé reste en attente.
     */
    suspend fun ensureReceiptsSent() {
        // v0.3.7 — VERROU ANTI-DOUBLE-ÉMISSION (retour terrain session 77, 4ᵉ
        // export : « ✓ accusé « read » déposé … (4 msg) » DEUX FOIS à 1 ms
        // d'intervalle) : le poll de fin de cycle ET le markConversationRead
        // « flush immédiat » lisaient les MÊMES ids en attente en parallèle et
        // déposaient chacun (deux événements, deux seq brûlés — le pair voit
        // double). Le verrou SÉRIALISE : le second attend, relit l'état — les
        // ids déjà déposés sont partis, il ne reste que le VRAI nouveau.
        rcptMutex.withLock {
            ensureReceiptsSentLocked()
        }
    }

    /** v0.3.7 — verrou d'émission des accusés (un seul cycle à la fois). */
    private val rcptMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun ensureReceiptsSentLocked() {
        if (replicas.isEmpty()) return
        val me = identity.onionAddress ?: return
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return
        for (peer in store.peersWithPendingReceipts()) {
            if (store.contact(peer)?.status != ACCEPTED) continue
            // « read » d'abord — il couvre le « delivered » (impliqué côté
            // expéditeur) et libère le plus d'état d'un coup.
            val readIds = store.readPendingIds(peer)
            for (batch in readIds.chunked(RECEIPT_BATCH)) {
                emitReceipt(me, priv, peer, ReceiptCodec.KIND_READ, batch).fold(
                    onSuccess = {
                        store.markReceiptsRead(batch)
                        store.clearRcptPending(batch)
                        DesktopLog.i("nyx-send", "✓ accusé « read » déposé pour ${peer.take(16)}… (${batch.size} msg)")
                    },
                    onFailure = { e ->
                        DesktopLog.w("nyx-send", "✗ accusé « read » DIFFÉRÉ pour ${peer.take(16)}… : ${e.message?.take(80)}")
                    }
                )
            }
            val deliveredIds = store.deliveredPendingIds(peer)
            for (batch in deliveredIds.chunked(RECEIPT_BATCH)) {
                emitReceipt(me, priv, peer, ReceiptCodec.KIND_DELIVERED, batch).fold(
                    onSuccess = {
                        store.clearRcptPending(batch)
                        DesktopLog.i("nyx-send", "✓ accusé « delivered » déposé pour ${peer.take(16)}… (${batch.size} msg)")
                    },
                    onFailure = { e ->
                        DesktopLog.w("nyx-send", "✗ accusé « delivered » DIFFÉRÉ pour ${peer.take(16)}… : ${e.message?.take(80)}")
                    }
                )
            }
        }
    }

    /** v0.3.6 — lot maximum d'ids par accusé (miroir RECEIPT_BATCH Android). */
    private val RECEIPT_BATCH = 50

    /**
     * v0.3.6 — LA CONVERSATION EST OUVERTE (écran chat visible) : tout ce qui
     * vient d'être reçu devient « lu » (indicateur destinataire de la liste),
     * les « delivered » en attente sont PROMUS « read » (un seul état à vider,
     * qui couvre l'autre) et les accusés partent immédiatement (différé si le
     * réplica ne répond pas — le poll suivant retente, miroir Android).
     */
    suspend fun markConversationRead(peer: String) {
        store.markConversationRead(peer)
        store.promoteDeliveredToRead(peer)
        runCatching { ensureReceiptsSent() }
    }

    /**
     * v0.3.6 — émission d'UN accusé signé chiffré (miroir sendReceipt Android).
     * Renvoie le résultat du DÉPÔT (plus jamais avalé — avant, « ✓ déposé »
     * était journalisé même en échec et l'état effacé sans retry).
     */
    private suspend fun emitReceipt(me: String, priv: ByteArray, peer: String, kind: String, ids: List<String>): Result<Unit> {
        return runCatching {
            if (ids.isEmpty()) return@runCatching
            val peerPub = OnionCodec.onionToPubKey(peer)
            val alias = identity.alias ?: ""
            val bodyJson = ReceiptCodec.encode(alias, kind, ids)
            val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "contact", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = peer, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val depositAddr = depositTargetFor(peer) ?: error("aucun réplica configuré")
            postSigned(relayFor(depositAddr), me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
        }
    }

    // ---------------- contacts ----------------

    suspend fun addContact(onionRaw: String, alias: String): Result<Boolean> = runCatching {
        val onion = onionRaw.trim().lowercase().removeSuffix(".onion") + ".onion"
        OnionCodec.onionToPubKey(onion) // validation checksum
        val me = identity.onionAddress ?: error("identité absente")
        if (onion == me) error("vous ne pouvez pas vous ajouter vous-même")
        val existing = store.contact(onion)
        if (existing != null && existing.status == ACCEPTED) return@runCatching false
        val label = alias.ifBlank { onion.take(10) + "…" }
        store.upsertContact(
            DesktopStore.Contact(
                onion = onion, alias = label, addedAt = System.currentTimeMillis(),
                status = PENDING, notifyPending = 1
            )
        )
        sendContactNotification(onion).getOrThrow()
        true
    }

    /** Ajout par ALIAS (résolution /client/alias — wire-format §6). */
    suspend fun addContactByAlias(alias: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = AliasValidator.normalize(alias)
            if (!AliasValidator.isValid(name)) error("alias invalide (3-24 : a-z 0-9 _)")
            val relay = relayFor(activeOrFirstReplica() ?: error("aucun réplica configuré"))
            val resolved = relay.resolveAlias(name).getOrNull() ?: error("résolution impossible")
            if (!resolved.taken || resolved.by == null) error("alias « $name » inconnu du réseau")
            val onion = resolved.by!!
            addContact(onion, name).getOrThrow()
            onion
        }
    }

    suspend fun acceptContact(onion: String): Result<Unit> = changeContactStatus(onion, ACCEPTED)
    suspend fun declineContact(onion: String): Result<Unit> = changeContactStatus(onion, DECLINED)

    /** v0.3.4 — miroir Android deleteContact : NOTRE suppression → REMOVED_BY_ME
     * (« contact supprimé »), l'ack « removed » reçu le laisse en REMOVED
     * (« supprimé par ce contact ») — distinction affichée dans les listes. */
    suspend fun removeContact(onion: String): Result<Unit> = changeContactStatus(onion, REMOVED_BY_ME)

    private suspend fun changeContactStatus(onion: String, status: ContactStatusAny): Result<Unit> = runCatching {
        store.updateContact(onion) { it.status = status }
        sendContactAck(onion, when (status) {
            DECLINED -> "declined"
            REMOVED, REMOVED_BY_ME -> "removed"
            else -> "accepted"
        }).getOrThrow()
    }

    suspend fun setBlocked(onion: String, blocked: Boolean) {
        store.updateContact(onion) { it.blockedAt = if (blocked) System.currentTimeMillis() else 0L }
    }

    /** Annonce « je t'ajoute » (body chiffré {"alias":…} — miroir Android). */
    private suspend fun sendContactNotification(onionLower: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val depositAddr = depositTargetFor(onionLower) ?: error("aucun réplica configuré")
            val relay = relayFor(depositAddr)
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(onionLower)
            val bodyJson = "{\"alias\":\"${identity.alias ?: ""}\"}"
            val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "contact", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = onionLower, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
        }
    }

    private suspend fun sendContactAck(onionLower: String, ack: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val depositAddr = depositTargetFor(onionLower) ?: error("aucun réplica configuré")
            val relay = relayFor(depositAddr)
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(onionLower)
            val bodyJson = "{\"alias\":\"${identity.alias ?: ""}\",\"ack\":\"$ack\"}"
            val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "contact", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = onionLower, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
        }
    }

    /** Repartie des annonces/acks en attente (miroir ensureContactNotified). */
    suspend fun ensureContactNotified() {
        if (replicas.isEmpty()) return
        for (c in store.contacts().filter { it.notifyPending == 1 }) {
            sendContactNotification(c.onion).fold(
                onSuccess = {
                    store.updateContact(c.onion) { it.notifyPending = 0; it.notifiedAt = System.currentTimeMillis() }
                    DesktopLog.i("nyx-send", "✓ annonce contact déposée pour ${c.onion.take(16)}…")
                },
                onFailure = { e ->
                    DesktopLog.w("nyx-send", "✗ annonce contact ÉCHOUÉE pour ${c.onion.take(16)}… : ${e.message}")
                }
            )
        }
    }

    /** Annonce de notre réplica embarqué aux contacts acceptés (miroir v0.2.25). */
    suspend fun announceReplica(replicaAddr: String): Int {
        if (replicas.isEmpty()) return 0
        val me = identity.onionAddress ?: return 0
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return 0
        val bodyJson = ReplicaCodec.encodeBody(identity.alias ?: "", replicaAddr, "0.3.1")
        var sent = 0
        for (c in store.contacts().filter { it.status == ACCEPTED }) {
            runCatching {
                val peerPub = OnionCodec.onionToPubKey(c.onion)
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "contact", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, to = c.onion, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                val depositAddr = depositTargetFor(c.onion) ?: error("aucun réplica")
                postSigned(relayFor(depositAddr), me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv)
                    .getOrThrow()
                sent++
            }.onFailure {
                DesktopLog.w("nyx-send", "✗ annonce réplica ÉCHOUÉE pour ${c.onion.take(16)}… : ${it.message}")
            }
        }
        if (sent > 0) DesktopLog.i("nyx-send", "✓ annonce réplica déposée pour $sent contact(s)")
        return sent
    }

    // ---------------- alias ----------------

    suspend fun claimAlias(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val norm = AliasValidator.normalize(name)
            if (!AliasValidator.isValid(norm)) error("alias invalide (3-24 : a-z 0-9 _)")
            val relay = relayFor(activeOrFirstReplica() ?: error("aucun réplica configuré"))
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val check = relay.resolveAlias(norm).getOrNull()
            if (check?.taken == true && check.by != me) {
                error("cet alias est déjà pris (par ${check.by?.take(12)}…)")
            }
            val bodyJson = "{\"alias\":\"$norm\",\"action\":\"claim\"}"
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "alias", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = "", body = Base64.getEncoder().encodeToString(bodyJson.toByteArray()), sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
            identity.setAlias(norm)
        }
    }

    // ---------------- profils ----------------

    /** v0.3.4 — limites fermées miroir ProfileKeys Android (appliquées via .take() à la publication). */
    private val profileLimits = linkedMapOf(
        "display_name" to 64, "bio" to 280, "website" to 128,
        "location" to 64, "avatar" to 65536, "replica" to 128
    )

    @Serializable
    internal data class ProfileFieldJson(val value: String, val public: Boolean = true)

    @Serializable
    internal data class ProfileBodyJson(val fields: Map<String, ProfileFieldJson> = emptyMap())

    /**
     * Carte DIFFUSÉE = champs PUBLICS seuls (ADR-010 : les champs privés ne
     * quittent JAMAIS l'appareil) + champ auto « replica » quand la réplique
     * embarquée sert (miroir ProfileStore.publishPending/publicOnly).
     * Sérialisation kotlinx (échappement JSON correct — l'ancienne
     * concaténation naïve cassait les bios contenant des guillemets).
     */
    internal fun publishedProfileCard(): ProfileBodyJson {
        val p = store.myProfile()
        val fields = LinkedHashMap<String, ProfileFieldJson>()
        if (p.displayNamePublic && p.displayName.isNotBlank())
            fields["display_name"] = ProfileFieldJson(p.displayName.take(64))
        if (p.bioPublic && p.bio.isNotBlank()) fields["bio"] = ProfileFieldJson(p.bio.take(280))
        if (p.websitePublic && p.website.isNotBlank()) fields["website"] = ProfileFieldJson(p.website.take(128))
        if (p.locationPublic && p.location.isNotBlank()) fields["location"] = ProfileFieldJson(p.location.take(64))
        if (p.avatarPublic && p.avatarB64.isNotBlank()) fields["avatar"] = ProfileFieldJson(p.avatarB64.take(65536))
        if (EmbeddedReplica.serving) {
            identity.onionAddress?.let { fields["replica"] = ProfileFieldJson("$it:80") }
        }
        return ProfileBodyJson(fields)
    }

    suspend fun publishProfile(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val bodyPlain = json.encodeToString(ProfileBodyJson.serializer(), publishedProfileCard())
            val body = Base64.getEncoder().encodeToString(bodyPlain.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "profile", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = "", body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val relay = relayFor(activeOrFirstReplica() ?: error("aucun réplica configuré"))
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            Unit
        }
    }

    suspend fun fetchProfile(peer: String) {
        val relay = runCatching { relayFor(activeOrFirstReplica() ?: return) }.getOrNull() ?: return
        relay.profile(peer).getOrNull()?.let { resp ->
            val existing = store.peerProfile(peer)
            store.upsertPeerProfile(
                (existing ?: DesktopStore.PeerProfile(onion = peer)).apply {
                    resp.fields["display_name"]?.let { displayName = it.value.take(64) }
                    resp.fields["bio"]?.let { bio = it.value.take(280) }
                    resp.fields["website"]?.let { website = it.value.take(128) }
                    resp.fields["location"]?.let { location = it.value.take(64) }
                    resp.fields["avatar"]?.let { avatarB64 = it.value.take(65536) }
                    fetchedAt = System.currentTimeMillis()
                }
            )
        }
    }

    /**
     * v0.3.4 — rafraîchissement des fiches pair (miroir refreshPeerProfiles
     * Android : TTL 1 h, échec silencieux — icône générique en attendant).
     */
    suspend fun refreshPeerProfiles(ttlMs: Long = 3_600_000L) {
        val now = System.currentTimeMillis()
        val targets = store.contacts()
            .filter { it.status == ACCEPTED && it.blockedAt == 0L }
            .map { it.onion }
            .filter { onion ->
                val p = store.peerProfile(onion)
                p == null || now - p.fetchedAt > ttlMs
            }
        for (onion in targets) runCatching { fetchProfile(onion) }
    }

    // ---------------- export / import des conversations (v0.3.4 — miroir v0.2.26) ----------------

    /**
     * Export JSON « nyx-conversations » v1 — MÊME schéma que l'Android (les
     * fichiers ne partent pas en binaire : libellé + fid seulement).
     */
    fun exportConversationsJson(peers: List<String>): String {
        val root = org.json.JSONObject()
        root.put("format", "nyx-conversations")
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis() / 1000)
        val arr = org.json.JSONArray()
        for (peerRaw in peers) {
            val peer = peerRaw.trim().lowercase()
            val contact = store.contact(peer)
            val conv = org.json.JSONObject()
            conv.put("peer", peer)
            conv.put("alias", contact?.alias?.ifBlank { peer.take(10) + "…" } ?: (peer.take(10) + "…"))
            conv.put("status", (contact?.status ?: ACCEPTED).name.lowercase())
            val msgs = org.json.JSONArray()
            for (m in store.messagesWith(peer)) {
                val o = org.json.JSONObject()
                o.put("id", m.id)
                o.put("out", m.outgoing)
                o.put("text", m.text)
                o.put("sent_at", m.ts * 1000)
                o.put("received_at", m.receivedAt)
                o.put("state", m.state)
                o.put("kind", m.kind)
                o.put("fid", m.fid)
                o.put("expire_at", m.expireAt)
                msgs.put(o)
            }
            conv.put("messages", msgs)
            arr.put(conv)
        }
        root.put("conversations", arr)
        return root.toString(2)
    }

    /**
     * Import (fusion idempotente — miroir Android : INSERT IGNORE par eventId,
     * contact créé si absent, statut du fichier sinon accepté). Retourne le
     * nombre de messages réellement ajoutés.
     */
    fun importConversationsJson(text: String): Int {
        val root = org.json.JSONObject(text)
        check(root.optString("format") == "nyx-conversations") { "fichier non reconnu" }
        val convs = root.optJSONArray("conversations") ?: throw IllegalArgumentException("aucune conversation")
        var added = 0
        for (i in 0 until convs.length()) {
            val conv = convs.getJSONObject(i)
            val peer = conv.optString("peer").trim().lowercase()
            if (!peer.endsWith(".onion")) continue
            val existing = store.contact(peer)
            if (existing == null) {
                val status = runCatching {
                    ContactStatusAny.valueOf(conv.optString("status", "accepted").uppercase())
                }.getOrDefault(ACCEPTED)
                store.upsertContact(
                    DesktopStore.Contact(
                        onion = peer,
                        alias = conv.optString("alias").ifBlank { peer.take(10) + "…" },
                        addedAt = System.currentTimeMillis(),
                        status = status
                    )
                )
            }
            val msgs = conv.optJSONArray("messages") ?: continue
            for (j in 0 until msgs.length()) {
                val o = msgs.getJSONObject(j)
                val id = o.optString("id")
                if (id.isBlank() || store.message(id) != null) continue
                store.insertMessage(
                    DesktopStore.Msg(
                        id = id,
                        peer = peer,
                        outgoing = o.optBoolean("out", false),
                        text = o.optString("text"),
                        ts = o.optLong("sent_at", System.currentTimeMillis()) / 1000,
                        state = o.optInt("state", DesktopStore.STATE_RECEIVED),
                        readLocal = 1,
                        kind = o.optInt("kind", DesktopStore.KIND_TEXT),
                        fid = o.optString("fid", ""),
                        expireAt = o.optLong("expire_at", 0L)
                    )
                )
                added++
            }
        }
        return added
    }

    // ---------------- routage ----------------

    fun setReplicas(onions: List<String>) {
        replicas = onions.map { it.trim().lowercase() }.filter { BootstrapReplicas.ENTRY.matches(it) }.distinct()
    }

    private suspend fun depositTargetFor(peer: String): String? =
        DepositRouting.depositTarget(peer, replicas) { addr -> isPeerPhone(addr) }

    private fun isPeerPhone(addr: String): Boolean {
        val key = LinkFallback.hostKey(addr)
        return store.contacts().any { it.onion == key || it.onion == "$key.onion" || it.onion == "$key.onion:80" }
    }

    private fun activeOrFirstReplica(): String? = replicas.firstOrNull()

    private fun relayFor(addr: String): RelayClient {
        val port = socksPortProvider()
        require(port > 0) { "Tor n'est pas démarré (port SOCKS inconnu)" }
        return RelayClient.of(addr, port)
    }

    private fun onChangedReplicas() { onChangeCallback?.invoke() }

    var onChangeCallback: (() -> Unit)? = null

    private fun eventIdOf(canonical: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".bmp", true) -> "image/bmp"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".wav", true) -> "audio/wav"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".mp4", true) || name.endsWith(".m4v", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        else -> "application/octet-stream"
    }
}

private typealias ContactStatusAny = net.nyx.messenger.desktop.DesktopStore.ContactStatus
