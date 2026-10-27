package net.nyx.messenger.replica

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.net.Loopback
import net.nyx.messenger.net.WireEvent
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * RÉPLICA TEMPORAIRE EMBARQUÉE DESKTOP — portage de EmbeddedReplica Android
 * (v0.2.19+) : pendant que l'app tourne, le PC sert L'API réplica (les 5
 * routes que RelayClient connaît) sur le PORT 80 de son onion d'identité.
 *
 * Mêmes règles que l'Android (miroir du serveur Go) : validation structurelle,
 * signature ed25519, seq stricte + admission des en attente, quotas (60
 * dépôts/min/auteur, 3 000 événements/48 MiB/500 par boîte), purge TTL 48 h,
 * dépôts acceptés UNIQUEMENT de nos contacts et adressés à nous/nos contacts.
 *
 * STOCKAGE DESKTOP : journal `replica/events.jsonl` (append-only, une ligne
 * par événement, gid = position) + relecture au démarrage — remplace
 * Room/SQLCipher. Écriture append avec flush par ligne (crash-safe : une
 * ligne partielle est ignorée à la relecture).
 */
object EmbeddedReplica {

    @Serializable
    data class EventRow(
        val gid: Long,
        val eventId: String,
        val type: String,
        val author: String,
        val seq: Long,
        val ts: Long,
        val ttl: Long,
        val to: String,
        val body: String,
        val sig: String,
    )

    @Volatile var localPort: Int = 0
        private set

    @Volatile var serving: Boolean = true

    @Volatile var verify: ((author: String, canonical: String, sigB64: String) -> Boolean)? = null
    @Volatile var isAllowed: ((onion: String) -> Boolean)? = null

    @Volatile private var started = false
    private var server: ServerSocket? = null
    private var journalFile: File? = null
    private val rows = ArrayList<EventRow>()        // ordre gid croissant
    private var ownOnion: String = ""
    private var versionName: String = "0.3.0"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rate = ReplicaQuota.RateLimiter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, ArrayDeque<WireEvent>>()

    // TOUTES les mutations de `rows` passent par synchronized(rows) — un seul
    // verrou applicatif (le journal disque est écrit sous ce même verrou).

    fun alive(): Boolean {
        val p = localPort
        if (p <= 0 || !started) return false
        return try {
            Socket().use { s ->
                s.soTimeout = 1_500
                s.connect(java.net.InetSocketAddress("127.0.0.1", p), 1_500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun start(ownOnionAddress: String, appVersion: String) {
        if (started) return
        ownOnion = ownOnionAddress
        versionName = appVersion
        journalFile = File(DesktopPaths.replicaDir, "events.jsonl")
        loadJournal()
        try {
            val s = Loopback.serverSocket(backlog = 16)
            localPort = s.localPort
            server = s
            started = true
            val t = Thread({
                DesktopLog.i(
                    "nyx-replica",
                    "réplica temporaire prêt (local ${Loopback.bindAddress.hostAddress}:$localPort, port 80 onion)"
                )
                while (true) {
                    val sock: Socket = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (started) DesktopLog.w("nyx-replica", "écouteur interrompu : ${e.message}")
                        return@Thread
                    }
                    try {
                        Thread({ handle(sock) }, "nyx-replica-conn").start()
                    } catch (e: Throwable) {
                        // (miroir v0.2.45) Thread.start() peut lever OutOfMemoryError :
                        // journaliser et CONTINUER d'accepter.
                        DesktopLog.w(
                            "nyx-replica",
                            "thread de connexion impossible (${e.javaClass.simpleName}) — connexion rejetée, écouteur maintenu"
                        )
                        runCatching { sock.close() }
                    }
                }
            }, "nyx-replica-listen")
            t.isDaemon = true
            t.start()
            startPurgeLoop()
        } catch (e: Exception) {
            DesktopLog.w("nyx-replica", "démarrage réplica impossible : ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        started = false
        runCatching { server?.close() }
        server = null
        localPort = 0
        scope.cancel()
    }

    /** v0.2.45 (portage) — hook de test JVM : remet l'état à zéro sans tuer les threads de purge. */
    @Synchronized
    fun resetForTest() {
        runCatching { server?.close() }
        server = null
        started = false
        localPort = 0
        pending.clear()
        rows.clear()
        journalFile = null
    }

    private fun startPurgeLoop() {
        scope.launch {
            while (isActive) {
                delay(10 * 60_000L)
                runCatching { purge() }
                    .onFailure { DesktopLog.w("nyx-replica", "purge échouée : ${it.message}") }
            }
        }
    }

    /** Exécution locale d'une requête (poll en procédé de NOTRE boîte). */
    fun dispatch(method: String, path: String, query: Map<String, String>, body: ByteArray): Pair<Int, String> {
        if (!started) return 503 to ReplicaHttp.errorJson("réplica embarqué non démarré")
        return route(ReplicaHttp.Request(method.uppercase(), path, query, emptyMap(), body))
    }

    private fun route(req: ReplicaHttp.Request): Pair<Int, String> {
        if (!serving) return 503 to ReplicaHttp.errorJson("réplica temporaire en pause (Paramètres)")
        return when {
            req.path == "/health" -> handleHealth()
            req.path == "/client/event" && req.method == "POST" -> handleDeposit(req)
            req.path == "/client/inbox" -> handleInbox(req)
            req.path == "/client/profile" -> handleProfile(req)
            req.path == "/client/alias" -> handleAlias(req)
            else -> 404 to ReplicaHttp.errorJson("route inconnue")
        }
    }

    private fun handleHealth(): Pair<Int, String> {
        val events = synchronized(rows) { rows.size }
        return 200 to "{\"ok\":true,\"version\":\"$versionName\",\"onion\":\"$ownOnion\",\"events\":$events}"
    }

    private fun handleDeposit(req: ReplicaHttp.Request): Pair<Int, String> {
        val ev = try {
            json.decodeFromString(WireEvent.serializer(), String(req.body, Charsets.UTF_8))
        } catch (e: Exception) {
            return 400 to ReplicaHttp.errorJson("JSON invalide: ${e.message?.take(120)}")
        }
        val author = ev.author.trim().lowercase()
        val to = (ev.to ?: "").trim().lowercase()
        val nowS = System.currentTimeMillis() / 1000

        ReplicaValidate.basicError(ev.type, ev.body, ev.ts, nowS)?.let { return 400 to ReplicaHttp.errorJson(it) }

        if (!rate.allow(author, System.currentTimeMillis())) {
            return 429 to ReplicaHttp.errorJson("trop de dépôts — réessayez plus tard")
        }

        val allowedAuthor = isAllowed?.invoke(author) == true
        if (!allowedAuthor) {
            return 403 to ReplicaHttp.errorJson("auteur inconnu de ce réplica temporaire")
        }
        if (to.isEmpty()) {
            if (ev.type != "alias" && ev.type != "profile") {
                return 400 to ReplicaHttp.errorJson("destinataire requis pour le type \"${ev.type}\"")
            }
        } else if (to != ownOnion && isAllowed?.invoke(to) != true) {
            return 403 to ReplicaHttp.errorJson("destinataire inconnu de ce réplica temporaire")
        }

        val canonical = MessageCipher.canonical(ev.type, author, ev.seq, ev.ts, ev.ttl, to, ev.body)
        if (verify?.invoke(author, canonical, ev.sig) != true) {
            return 400 to ReplicaHttp.errorJson("signature invalide pour $author")
        }

        val id = ReplicaValidate.eventId(canonical)
        val result = synchronized(rows) {
            if (byIdLocked(id) != null) "dup"
            else {
                val last = lastSeqLocked(author)
                when {
                    ev.seq > last + 1 -> {
                        val q = pending.getOrPut(author) { ArrayDeque() }
                        q.addLast(ev.copy(author = author, to = to.takeIf { it.isNotEmpty() }))
                        "pending:${last + 1}"
                    }
                    ev.seq != last + 1 -> "late:$last"
                    else -> {
                        insertAndAdmitLocked(ev.copy(author = author, to = to.takeIf { it.isNotEmpty() }), id)
                        "ok"
                    }
                }
            }
        }
        return when {
            result == "dup" -> 409 to "{\"error\":\"déjà connu\",\"id\":\"$id\"}"
            result.startsWith("pending") -> {
                val expected = result.substringAfter(':')
                400 to ReplicaHttp.errorJson("seq ${ev.seq} en attente (attendu $expected)")
            }
            result.startsWith("late") -> {
                val last = result.substringAfter(':')
                400 to ReplicaHttp.errorJson("seq ${ev.seq} déjà dépassée (dernière $last)")
            }
            else -> 200 to "{\"accepted\":true,\"id\":\"$id\"}"
        }
    }

    private fun insertAndAdmitLocked(ev: WireEvent, id: String) {
        insertOneLocked(ev, id)
        val q = pending[ev.author]
        while (q != null && q.isNotEmpty()) {
            val next = q.first()
            val nextCanonical = MessageCipher.canonical(
                next.type, next.author, next.seq, next.ts, next.ttl, next.to ?: "", next.body
            )
            val last = lastSeqLocked(ev.author)
            if (next.seq != last + 1) break
            q.removeFirst()
            insertOneLocked(next, ReplicaValidate.eventId(nextCanonical))
        }
        if (q != null && q.isEmpty()) pending.remove(ev.author)
    }

    private fun insertOneLocked(ev: WireEvent, id: String) {
        val to = (ev.to ?: "")
        val gid = (rows.lastOrNull()?.gid ?: 0L) + 1L
        appendToDisk(EventRow(gid, id, ev.type, ev.author, ev.seq, ev.ts, ev.ttl, to, ev.body, ev.sig))
        rows.add(EventRow(gid, id, ev.type, ev.author, ev.seq, ev.ts, ev.ttl, to, ev.body, ev.sig))

        // Plafonds (même politique Android/Go).
        trimLocked()
    }

    private fun trimLocked() {
        var over = rows.size - ReplicaQuota.MAX_EVENTS_TOTAL
        if (over > 0) dropOldestLocked(over)
        // Par boîte
        val byInbox = rows.groupBy { it.to }
        for ((inbox, list) in byInbox) {
            if (inbox.isNotEmpty() && list.size > ReplicaQuota.MAX_EVENTS_PER_INBOX) {
                val ids = list.take(list.size - ReplicaQuota.MAX_EVENTS_PER_INBOX).map { it.eventId }.toHashSet()
                removeAllLocked(ids)
            }
        }
        var bytes = rows.sumOf { it.body.length.toLong() }
        var guard = 0
        while (bytes > ReplicaQuota.MAX_BYTES_TOTAL && guard++ < 50 && rows.isNotEmpty()) {
            dropOldestLocked(minOf(25, rows.size))
            bytes = rows.sumOf { it.body.length.toLong() }
        }
    }

    private fun dropOldestLocked(n: Int) = removeAllLocked(rows.take(n).map { it.eventId }.toHashSet())

    private fun removeAllLocked(ids: HashSet<String>) {
        if (ids.isEmpty()) return
        rows.removeAll { it.eventId in ids }
        rewriteDiskLocked()
    }

    private fun purge() {
        val cutoff = System.currentTimeMillis() - ReplicaQuota.TTL_MS
        val ids = HashSet<String>()
        synchronized(rows) {
            for (r in rows) {
                val expireAt = r.ts * 1000 + if (r.ttl > 0) r.ttl * 1000 else ReplicaQuota.TTL_MS
                if (expireAt < cutoff) ids.add(r.eventId)
            }
            if (ids.isNotEmpty()) removeAllLocked(ids)
        }
        if (ids.isEmpty()) return
        DesktopLog.i("nyx-replica", "purge : ${ids.size} événements expirés (TTL 48 h)")
    }

    private fun handleInbox(req: ReplicaHttp.Request): Pair<Int, String> {
        val to = (req.query["to"] ?: "").trim().lowercase()
        if (to.isEmpty()) return 400 to ReplicaHttp.errorJson("paramètre to requis")
        val auth = req.query["auth"] ?: ""
        val sep = auth.indexOf(':')
        val ts = if (sep > 0) auth.substring(0, sep).toLongOrNull() else null
        val sig = if (sep > 0) auth.substring(sep + 1) else ""
        val nowS = System.currentTimeMillis() / 1000
        if (ts == null || sig.isEmpty() || kotlin.math.abs(nowS - ts) > ReplicaValidate.AUTH_WINDOW_S) {
            return 401 to ReplicaHttp.errorJson("authentification inbox requise (ts:sig)")
        }
        val canonical = ReplicaValidate.inboxCanonical(to, ts)
        if (verify?.invoke(to, canonical, sig) != true) {
            return 401 to ReplicaHttp.errorJson("authentification inbox invalide")
        }
        val after = req.query["after"]?.toLongOrNull() ?: 0L
        val limit = (req.query["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 500)
        val list: List<EventRow>
        val cursor: Long
        synchronized(rows) {
            list = rows.filter { it.to == to && it.gid > after }.take(limit)
            val maxGid = rows.lastOrNull()?.gid ?: 0L
            cursor = if (list.size == limit && list.isNotEmpty()) list.last().gid else maxGid
        }
        val arr = list.joinToString(",", "[", "]") { encodeEvent(it) }
        return 200 to "{\"events\":$arr,\"cursor\":$cursor}"
    }

    private fun handleProfile(req: ReplicaHttp.Request): Pair<Int, String> {
        val author = (req.query["author"] ?: "").trim().lowercase()
        if (author.isEmpty()) return 400 to ReplicaHttp.errorJson("paramètre author requis")
        runCatching { OnionCodec.onionToPubKey(author) }
            .getOrElse { return 400 to ReplicaHttp.errorJson("adresse onion invalide") }
        val ev = synchronized(rows) {
            rows.filter { it.author == author && it.type == "profile" && it.to.isEmpty() }
                .maxByOrNull { it.seq }
        } ?: return 404 to ReplicaHttp.errorJson("aucun profil public pour cet auteur")
        // Le corps est la carte {"fields":{…}} — extraire le bloc fields tel quel
        // (le parseur d'accolades évite une dépendance à org.json ici).
        val fields = extractFields(ev.body)
        return 200 to "{\"author\":\"${ev.author}\",\"seq\":${ev.seq},\"ts\":${ev.ts},\"fields\":$fields}"
    }

    private fun extractFields(bodyB64: String): String {
        return runCatching {
            val raw = String(Base64.getDecoder().decode(bodyB64), Charsets.UTF_8)
            val start = raw.indexOf("\"fields\"")
            if (start < 0) return "{}"
            var depth = 0
            var i = raw.indexOf('{', start)
            if (i < 0) return "{}"
            val from = i
            while (i < raw.length) {
                when (raw[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return raw.substring(from, i + 1) }
                }
                i++
            }
            "{}"
        }.getOrDefault("{}")
    }

    private fun handleAlias(req: ReplicaHttp.Request): Pair<Int, String> {
        val name = req.query["name"] ?: ""
        if (!Regex("^[a-z0-9_]{3,24}$").matches(name)) {
            return 400 to ReplicaHttp.errorJson("paramètre name requis (format ^[a-z0-9_]{3,24}\$)")
        }
        var holder: EventRow? = null
        synchronized(rows) {
            for (c in rows.filter { it.type == "alias" }) {
                val alias = runCatching {
                    val raw = String(Base64.getDecoder().decode(c.body), Charsets.UTF_8)
                    Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
                }.getOrNull() ?: continue
                if (alias != name) continue
                val cur = holder
                if (cur == null || c.eventId < cur.eventId) holder = c
            }
        }
        val h = holder
        return if (h == null) {
            200 to "{\"alias\":\"$name\",\"taken\":false}"
        } else {
            200 to "{\"alias\":\"$name\",\"taken\":true,\"by\":\"${h.author}\"}"
        }
    }

    private fun encodeEvent(e: EventRow): String = json.encodeToString(
        WireEvent.serializer(),
        WireEvent(
            type = e.type, author = e.author, seq = e.seq, ts = e.ts,
            ttl = e.ttl, to = e.to.ifEmpty { null }, body = e.body, sig = e.sig
        )
    )

    private fun byIdLocked(id: String): EventRow? = rows.firstOrNull { it.eventId == id }
    private fun lastSeqLocked(author: String): Long =
        rows.filter { it.author == author }.maxOfOrNull { it.seq } ?: 0L

    // ---------- stockage disque ----------

    private fun loadJournal() {
        val f = journalFile ?: return
        rows.clear()
        if (!f.isFile) return
        var gid = 0L
        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val row = runCatching { json.decodeFromString(EventRow.serializer(), line) }.getOrNull() ?: continue
            gid = maxOf(gid, row.gid)
            rows.add(row)
        }
        DesktopLog.i("nyx-replica", "journal embarqué rechargé : ${rows.size} événements (gid max $gid)")
    }

    private fun appendToDisk(row: EventRow) {
        val f = journalFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(json.encodeToString(EventRow.serializer(), row) + "\n")
        }.onFailure { DesktopLog.w("nyx-replica", "append journal impossible : ${it.message}") }
    }

    private fun rewriteDiskLocked() {
        val f = journalFile ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(rows.joinToString("") { json.encodeToString(EventRow.serializer(), it) + "\n" })
            tmp.renameTo(f)
        }.onFailure { DesktopLog.w("nyx-replica", "réécriture journal impossible : ${it.message}") }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val req = try {
                ReplicaHttp.parse(sock.getInputStream())
            } catch (e: ReplicaHttp.Bad) {
                respond(sock, 400, ReplicaHttp.errorJson(e.reason)); return
            } catch (_: Exception) {
                return
            } ?: return
            val t0 = System.currentTimeMillis()
            val (code, jsonResp) = route(req)
            respond(sock, code, jsonResp)
            if (req.path != "/health") {
                DesktopLog.i("nyx-replica", "${req.method} ${req.path} → $code (${System.currentTimeMillis() - t0} ms)")
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun respond(sock: Socket, code: Int, jsonResp: String) {
        runCatching {
            sock.getOutputStream().write(ReplicaHttp.response(code, jsonResp))
            sock.getOutputStream().flush()
        }
    }
}
