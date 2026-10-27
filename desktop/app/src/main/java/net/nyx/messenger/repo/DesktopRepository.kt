package net.nyx.messenger.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
                    onSuccess = {
                        store.updateMessage(id) { it.state = DesktopStore.STATE_SENT }
                        DesktopLog.i("nyx-send", "   ✓ déposé (seq=$seq)")
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
     */
    suspend fun sendFile(peerOnion: String, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val peer = peerOnion.trim().lowercase()
            val link = store.contact(peer)
                ?: error("ce destinataire n'est pas dans vos contacts")
            if (link.status != ACCEPTED) error("échange possible uniquement entre contacts acceptés")
            if (link.blockedAt > 0) error("contact bloqué")

            val bytes = file.readBytes()
            if (bytes.size > ChunkCodec.MAX_FILE_SIZE) {
                error("fichier trop volumineux (${bytes.size / (1024 * 1024)} Mo — max 64 Mo)")
            }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val fid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val safeName = ChunkCodec.sanitizeName(file.name)
            val mime = guessMime(safeName)
            val ts = System.currentTimeMillis() / 1000
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peer)

            // Bulle locale immédiate (miroir Android mediaPlaceholder).
            val firstCanonical = "local:$fid"
            store.insertMessage(
                DesktopStore.Msg(
                    id = firstCanonical, peer = peer, outgoing = true,
                    text = "[$safeName]", ts = ts, state = DesktopStore.STATE_PENDING,
                    kind = DesktopStore.KIND_FILE, fid = fid
                )
            )
            store.upsertAttachment(
                DesktopStore.AttachmentMeta(
                    fid = fid, name = safeName, mime = mime,
                    size = bytes.size.toLong(), sha256 = sha256,
                    state = DesktopStore.AT_READY, localPath = file.absolutePath
                )
            )

            var sent = 0
            var failed: Throwable? = null
            val parts = ChunkCodec.split(bytes)
            for ((index, chunk) in parts.withIndex()) {
                val payload = ChunkCodec.encode(
                    fid, safeName, mime, bytes.size.toLong(), sha256,
                    index, parts.size, Base64.getEncoder().encodeToString(chunk)
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
                if (r.isSuccess) sent++ else {
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

    // ---------------- dépôt (auto-réparation seq — miroir v0.2.10/52) ----------------

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
        val expected = SeqResync.expectedFrom(msg) ?: return first
        seqMutex.withLock { store.upsertSeq(author, expected) }
        val retried = ev.copy(seq = expected)
        val canonical2 = MessageCipher.canonical(
            retried.type, retried.author, retried.seq, retried.ts, retried.ttl, retried.to ?: "", retried.body
        )
        return relay.postEvent(retried.copy(sig = cipher.signEvent(canonical2, priv)))
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
                emitPendingReceipts()
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

        // Signalisation d'appel (phase 2 desktop) — ignorée proprement.
        if (Regex("\"kind\"\\s*:\\s*\"call\"").containsMatchIn(plain)) {
            return Ingest.APPLIED
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
        status == DECLINED || status == REMOVED

    /** Réception d'un fragment (miroir handleIncomingChunk). */
    private fun handleIncomingChunk(author: String, canonical: String, ts: Long, chunk: ChunkCodec.FileChunk) {
        var meta = store.attachment(chunk.fid)
        if (meta == null) {
            meta = DesktopStore.AttachmentMeta(
                fid = chunk.fid, name = chunk.name, mime = chunk.mime,
                size = chunk.size, sha256 = chunk.sha256, state = DesktopStore.AT_PENDING
            )
            store.upsertAttachment(meta)
            store.insertMessage(
                DesktopStore.Msg(
                    id = eventIdOf(canonical), peer = author, outgoing = false,
                    text = "[${chunk.name}]", ts = ts, state = DesktopStore.STATE_RECEIVED,
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

    /** Accusé « delivered » pour tout message reçu non encore acquitté. */
    private suspend fun emitPendingReceipts() {
        val me = identity.onionAddress ?: return
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return
        val byPeer = store.messages().filter { !it.outgoing && it.rcptPending == 1 }.groupBy { it.peer }
        for ((peer, msgs) in byPeer) {
            emitReceipt(me, priv, peer, ReceiptCodec.KIND_DELIVERED, msgs.map { it.id })
            msgs.forEach { store.updateMessage(it.id) { m -> m.rcptPending = 0 } }
        }
    }

    /** Accusé « read » — appelé à l'ouverture de la conversation. */
    suspend fun markConversationRead(peer: String) {
        store.markConversationRead(peer)
        val me = identity.onionAddress ?: return
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return
        val ids = store.messagesWith(peer)
            .filter { !it.outgoing && it.readLocal == 1 && it.rcptRead == 0L && it.rcptDelivered > 0L }
            .map { it.id }
        if (ids.isNotEmpty()) {
            emitReceipt(me, priv, peer, ReceiptCodec.KIND_READ, ids)
            ids.forEach { id -> store.updateMessage(id) { it.rcptRead = System.currentTimeMillis() } }
        }
    }

    private suspend fun emitReceipt(me: String, priv: ByteArray, peer: String, kind: String, ids: List<String>) {
        runCatching {
            if (ids.isEmpty()) return
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
            val depositAddr = depositTargetFor(peer) ?: return
            postSigned(relayFor(depositAddr), me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv)
            DesktopLog.i("nyx-send", "✓ accusé « $kind » déposé (${ids.size} msg → ${peer.take(16)}…)")
        }.onFailure {
            DesktopLog.w("nyx-send", "✗ accusé « $kind » échoué : ${it.message?.take(80)}")
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
    suspend fun removeContact(onion: String): Result<Unit> = changeContactStatus(onion, REMOVED)

    private suspend fun changeContactStatus(onion: String, status: ContactStatusAny): Result<Unit> = runCatching {
        store.updateContact(onion) { it.status = status }
        sendContactAck(onion, when (status) {
            DECLINED -> "declined"
            REMOVED -> "removed"
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
            identity.setAlias(norm)
        }
    }

    // ---------------- profils ----------------

    suspend fun publishProfile(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val p = store.myProfile()
            val fields = LinkedHashMap<String, String>()
            if (p.displayName.isNotBlank()) fields["display_name"] = p.displayName
            if (p.bio.isNotBlank()) fields["bio"] = p.bio
            if (p.website.isNotBlank()) fields["website"] = p.website
            if (p.location.isNotBlank()) fields["location"] = p.location
            if (p.avatarB64.isNotBlank()) fields["avatar"] = p.avatarB64
            val fieldsJson = fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
            val bodyPlain = "{\"fields\":$fieldsJson}"
            val body = Base64.getEncoder().encodeToString(bodyPlain.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "profile", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, to = "", body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val relay = relayFor(activeOrFirstReplica() ?: error("aucun réplica configuré"))
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
        }
    }

    suspend fun fetchProfile(peer: String) {
        val relay = relayFor(activeOrFirstReplica() ?: return) ?: return
        relay.profile(peer).getOrNull()?.let { resp ->
            val existing = store.peerProfile(peer)
            store.upsertPeerProfile(
                (existing ?: DesktopStore.PeerProfile(onion = peer)).apply {
                    resp.fields["display_name"]?.let { displayName = it.value }
                    resp.fields["bio"]?.let { bio = it.value }
                    fetchedAt = System.currentTimeMillis()
                }
            )
        }
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
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }
}

private typealias ContactStatusAny = net.nyx.messenger.desktop.DesktopStore.ContactStatus
