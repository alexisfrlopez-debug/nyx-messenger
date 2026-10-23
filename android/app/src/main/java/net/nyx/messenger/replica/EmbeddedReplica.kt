package net.nyx.messenger.replica

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.data.db.NyxDatabase
import net.nyx.messenger.data.db.ReplicaEventEntity
import net.nyx.messenger.data.db.ReplicaStateEntity
import net.nyx.messenger.net.WireEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * v0.2.19 — RÉPLICA TEMPORAIRE EMBARQUÉ (demande opérateur : « lorsque
 * l'application est lancée, le téléphone devienne lui-même un replica temporaire
 * mais il ne faut pas que ça bouffe trop de ressource/bande passante/forfait de
 * données » — validé en version « test 2 » de la v0.2.19 à la v0.2.34,
 * INTÉGRÉ aux versions normale et test depuis la v0.2.35).
 *
 * PRINCIPE : pendant que l'application tourne, le téléphone sert l'API réplica
 * — LES MÊMES 5 ROUTES que le serveur Go (RelayClient n'en connaît pas
 * d'autres) — sur le PORT 80 de son onion d'identité (3ᵉ mappage ADD_ONION).
 * Un contact ajoute donc « <notre onion>:80 » dans ses réplicas : le réseau
 * fonctionne sans le serveur central.
 *
 * CHIFFREMENT/PRIVATÉ : le réplica ne voit que des corps OPAQUES (chiffrés E2E,
 * comme le serveur Go) et les stocke dans la base SQLCipher. Lecture d'inbox
 * réservée au détenteur de l'adresse (signature ed25519 — [ReplicaValidate.inboxCanonical])
 * — le serveur Go n'a pas cette exigence mais ignore les paramètres inconnus :
 * le MÊME client parle aux deux, zéro changement serveur.
 *
 * RESSOURCES (ReplicaQuota) : dépôts limités par auteur (60/min), plafonds de
 * stockage (3 000 événements / 48 MiB / 500 par boîte), purge TTL 48 h, corps
 * bornés 256 Ko, écouteur loopback (tout transite par Tor — forfait non exposé).
 * Les dépôts ne sont acceptés que de nos CONTACTS (ACCEPTED ou PENDING) et
 * n'adressent que nos contacts (ou nous-mêmes) — surface d'abus quasi nulle.
 *
 * TEMPORAIRE : la table est purgée après 48 h et vidée par le mot de passe
 * destruction (Wipe efface la base entière). Interrupteur Paramètres fermé
 * = table vide (le serveur n'est pas démarré).
 */
object EmbeddedReplica {

    /** Port local (loopback) mappé sur le port 80 de l'onion — 0 = non démarré. */
    @Volatile var localPort: Int = 0
        private set

    /** Interrupteur utilisateur (Paramètres) — pris en compte PAR CONNEXION. */
    @Volatile var serving: Boolean = true

    /** Injecté par ServiceLocator — vérification ed25519 (canonical → sig b64). */
    @Volatile var verify: ((author: String, canonical: String, sigB64: String) -> Boolean)? = null

    /** Injecté par ServiceLocator — le pair est-il un contact utilisable (ACCEPTED/PENDING) ? */
    @Volatile var isAllowed: ((onion: String) -> Boolean)? = null

    @Volatile private var started = false
    private var server: ServerSocket? = null
    private var db: NyxDatabase? = null
    private var ownOnion: String = ""
    private var versionName: String = "0.2.39"

    /**
     * v0.2.45 — LE BACKEND EST-IL VRAIMENT VIVANT ? (cause racine terrain
     * 23/10, PROUVÉE par rejeu tor sandbox — scripts/tor_deadbackend.py : un
     * socket fermé donne EXACTEMENT la signature « Connection refused » +
     * « errno 115 misc » + END misc + « SOCKS server general failure » vue
     * sur les 2 téléphones alors que leur service Tor était sain : INTRODUCE2
     * reçus, circuits rendezvous ouverts). L'écouteur peut mourir
     * silencieusement (thread tué par une Error non-Exception — Thread.start()
     * sous pression mémoire — rien n'est journalisé) : une vraie connexion
     * TCP locale est le seul verdict qui ne ment pas.
     */
    fun alive(): Boolean {
        val p = localPort
        if (p <= 0 || !started) return false
        return try {
            java.net.Socket().use { s ->
                s.soTimeout = 1_500
                s.connect(java.net.InetSocketAddress("127.0.0.1", p), 1_500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v0.2.45 — RELANCE SI MORT : ferme l'éventuel socket zombie, rebinde un
     * port NEUF et repart. Renvoie le nouveau port local (0 si échec).
     * [restarted] signale une relance (l'appelant — TorManager — doit
     * republier l'onion : le mapping port 80 → port local a CHANGÉ).
     */
    @Synchronized
    fun restartIfDead(): Int {
        if (alive()) return localPort
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-replica",
            "réplica temporaire MORT (port $localPort refuse les connexions locales) — relance de l'écouteur"
        )
        runCatching { server?.close() }
        server = null
        started = false
        localPort = 0
        val d = db ?: return 0
        start(d, ownOnion, versionName)
        return localPort
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rate = ReplicaQuota.RateLimiter()
    private val appendMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e ->
            net.nyx.messenger.diag.DiagLog.w("nyx-replica", "coroutine réplica terminée : ${e.message}")
        })
    /** Dépôts hors séquence en attente (miroir journal.pending côté Go — mémoire). */
    private val pending = HashMap<String, ArrayDeque<WireEvent>>()

    /**
     * Démarre l'écouteur (idempotent) + la purge périodique. Appelé par
     * ServiceLocator AVANT la publication onion (le port 80 doit être servi
     * dès l'annonce) — même ordre que P2pServer.
     */
    @Synchronized
    fun start(database: NyxDatabase, ownOnionAddress: String, appVersion: String) {
        if (started) return
        db = database
        ownOnion = ownOnionAddress
        versionName = appVersion
        try {
            val s = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
            localPort = s.localPort
            server = s
            started = true
            val t = Thread({
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-replica", "réplica temporaire prêt (local $localPort, port 80 onion)"
                )
                while (true) {
                    val sock: Socket = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (started) net.nyx.messenger.diag.DiagLog.w(
                            "nyx-replica", "écouteur interrompu : ${e.message}"
                        )
                        return@Thread
                    }
                    try {
                        Thread({ handle(sock) }, "nyx-replica-conn").start()
                    } catch (e: Throwable) {
                        // v0.2.45 — Thread.start() peut lever OutOfMemoryError
                        // (Error, NON attrapée par « catch Exception ») : le
                        // thread d'écoute mourait SANS trace et le port restait
                        // « publié » côté tor alors que plus rien n'écoutait —
                        // signature terrain « general failure » prouvée au
                        // sandbox. On journalise (DiagLog = SURVIT à l'export)
                        // et on continue d'accepter.
                        net.nyx.messenger.diag.DiagLog.w(
                            "nyx-replica", "thread de connexion impossible (${e.javaClass.simpleName}: " +
                                "${e.message}) — connexion rejetée, écouteur maintenu"
                        )
                        runCatching { sock.close() }
                    }
                }
            }, "nyx-replica-listen")
            t.isDaemon = true
            t.start()
            startPurgeLoop()
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w("nyx-replica", "démarrage réplica impossible : ${e.message}")
        }
    }

    /** Purge périodique : TTL 48 h + rappel des plafonds (toutes les 10 min). */
    private fun startPurgeLoop() {
        scope.launch {
            while (isActive) {
                delay(10 * 60_000L)
                runCatching { purge() }
                    .onFailure { net.nyx.messenger.diag.DiagLog.w("nyx-replica", "purge échouée : ${it.message}") }
            }
        }
    }

    private suspend fun purge() {
        val d = db ?: return
        val now = System.currentTimeMillis()
        val dao = d.replicaDao()
        val purged = dao.purgeOlderThan(now - ReplicaQuota.TTL_MS)
        var removed = purged
        // Rappel des plafonds (un dépôt pendant un arrêt de purge a pu déborder).
        val count = dao.count()
        if (count > ReplicaQuota.MAX_EVENTS_TOTAL) {
            removed += dao.deleteOldest(count - ReplicaQuota.MAX_EVENTS_TOTAL)
        }
        var bytes = dao.totalBytes()
        var guard = 0
        while (bytes > ReplicaQuota.MAX_BYTES_TOTAL && guard++ < 100) {
            val before = dao.count()
            if (before <= 0) break
            dao.deleteOldest(minOf(50, before))
            bytes = dao.totalBytes()
        }
        dao.upsertState(ReplicaStateEntity(1, now))
        if (removed > 0) {
            net.nyx.messenger.diag.DiagLog.i("nyx-replica", "purge : $removed événements expirés (TTL ${ReplicaQuota.TTL_MS / 3600_000} h)")
        }
    }

    /** Une connexion Tor entrante → une requête HTTP → une réponse. */
    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val req = try {
                ReplicaHttp.parse(sock.getInputStream())
            } catch (e: ReplicaHttp.Bad) {
                respond(sock, 400, ReplicaHttp.errorJson(e.reason)); return
            } catch (_: Exception) {
                return // connexion morte avant requête — rien à dire
            } ?: return
            val t0 = android.os.SystemClock.elapsedRealtime()
            val (code, jsonResp) = route(req)
            respond(sock, code, jsonResp)
            if (req.path != "/health") {
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-replica",
                    "${req.method} ${req.path} → $code (${android.os.SystemClock.elapsedRealtime() - t0} ms)"
                )
            }
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w("nyx-replica", "requête interrompue : ${e.message}")
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

    /** Routage — miroir des handlers Go (api/handlers.go). */
    private fun route(req: ReplicaHttp.Request): Pair<Int, String> {
        if (!serving) {
            return 503 to ReplicaHttp.errorJson("réplica temporaire en pause (Paramètres)")
        }
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
        val d = db
        val events = if (d != null) runBlocking { d.replicaDao().count() } else 0
        return 200 to buildJsonObject {
            put("ok", true)
            put("version", versionName)
            put("onion", ownOnion)
            put("events", events)
        }.toString()
    }

    /** POST /client/event — miroir de journal.Store.Append (seq stricte + pending). */
    private fun handleDeposit(req: ReplicaHttp.Request): Pair<Int, String> {
        val d = db ?: return 503 to ReplicaHttp.errorJson("réplica non initialisé")
        val ev = try {
            json.decodeFromString(WireEvent.serializer(), String(req.body, Charsets.UTF_8))
        } catch (e: Exception) {
            return 400 to ReplicaHttp.errorJson("JSON invalide: ${e.message?.take(120)}")
        }
        val author = ev.author.trim().lowercase()
        val to = (ev.to ?: "").trim().lowercase()
        val nowS = System.currentTimeMillis() / 1000

        // 1. Validation structurelle (miroir journal.Validate).
        ReplicaValidate.basicError(ev.type, ev.body, ev.ts, nowS)?.let { return 400 to ReplicaHttp.errorJson(it) }

        // 2. Limiteur de débit (forfait/bande passante).
        if (!rate.allow(author, System.currentTimeMillis())) {
            return 429 to ReplicaHttp.errorJson("trop de dépôts — réessayez plus tard")
        }

        // 3. Portes d'entrée : contact connu, destinataire connu ou diffusion.
        val allowedAuthor = isAllowed?.invoke(author) == true
        if (allowedAuthor == false || isAllowed == null) {
            return 403 to ReplicaHttp.errorJson("auteur inconnu de ce réplica temporaire")
        }
        if (to.isEmpty()) {
            if (ev.type != "alias" && ev.type != "profile") {
                return 400 to ReplicaHttp.errorJson("destinataire requis pour le type \"${ev.type}\"")
            }
        } else if (to != ownOnion && isAllowed?.invoke(to) != true) {
            return 403 to ReplicaHttp.errorJson("destinataire inconnu de ce réplica temporaire")
        }

        // 4. Signature ed25519 (miroir journal.Validate — publiée APRÈS la
        //    limite de débit : les faux dépôts ne coûtent pas de crypto).
        val canonical = net.nyx.messenger.crypto.MessageCipher.canonical(
            ev.type, author, ev.seq, ev.ts, ev.ttl, to, ev.body
        )
        if (verify?.invoke(author, canonical, ev.sig) != true) {
            return 400 to ReplicaHttp.errorJson("signature invalide pour $author")
        }

        // 5. Journal — seq stricte + admission des en attente (miroir Store.Append).
        val id = ReplicaValidate.eventId(canonical)
        val result = runBlocking {
            appendMutex.withLock {
                val dao = d.replicaDao()
                if (dao.event(id) != null) return@withLock "dup"
                val last = dao.lastSeq(author)
                when {
                    ev.seq > last + 1 -> {
                        // Hors séquence (futur) : mis en attente (miroir journal.pending).
                        val q = pending.getOrPut(author) { ArrayDeque() }
                        q.addLast(ev.copy(author = author, to = to.takeIf { it.isNotEmpty() }))
                        "pending:${last + 1}"
                    }
                    ev.seq != last + 1 -> "late:$last"
                    else -> {
                        insertAndAdmit(d, ev.copy(author = author, to = to.takeIf { it.isNotEmpty() }), id)
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

    /** Insertion + admission en cascade des dépôts redevenus consécutifs. */
    private suspend fun insertAndAdmit(
        d: NyxDatabase,
        ev: WireEvent,
        id: String,
    ) {
        val dao = d.replicaDao()
        insertOne(dao, ev, id)
        // Admet les événements en attente devenus consécutifs (miroir Go).
        val q = pending[ev.author]
        while (q != null && q.isNotEmpty()) {
            val next = q.first()
            val nextCanonical = net.nyx.messenger.crypto.MessageCipher.canonical(
                next.type, next.author, next.seq, next.ts, next.ttl, next.to ?: "", next.body
            )
            val last = dao.lastSeq(ev.author)
            if (next.seq != last + 1) break
            q.removeFirst()
            insertOne(dao, next, ReplicaValidate.eventId(nextCanonical))
        }
        if (q != null && q.isEmpty()) pending.remove(ev.author)
    }

    private suspend fun insertOne(
        dao: net.nyx.messenger.data.db.ReplicaDao,
        ev: WireEvent,
        id: String,
    ) {
        val to = (ev.to ?: "")
        val gid = dao.maxGid() + 1
        dao.insertEvent(
            ReplicaEventEntity(
                eventId = id, gid = gid, type = ev.type, author = ev.author,
                seq = ev.seq, ts = ev.ts, ttl = ev.ttl, to = to,
                body = ev.body, sig = ev.sig, bytes = ev.body.length.toLong(),
                insertedAt = System.currentTimeMillis()
            )
        )
        // Plafonds (demande opérateur : ressources bornées).
        val count = dao.count()
        if (count > ReplicaQuota.MAX_EVENTS_TOTAL) dao.deleteOldest(count - ReplicaQuota.MAX_EVENTS_TOTAL)
        if (to.isNotEmpty() && dao.countForInbox(to) > ReplicaQuota.MAX_EVENTS_PER_INBOX) {
            dao.deleteOldestForInbox(to, dao.countForInbox(to) - ReplicaQuota.MAX_EVENTS_PER_INBOX)
        }
        var bytes = dao.totalBytes()
        var guard = 0
        while (bytes > ReplicaQuota.MAX_BYTES_TOTAL && guard++ < 50) {
            dao.deleteOldest(25)
            bytes = dao.totalBytes()
        }
    }

    /** GET /client/inbox?to=&after=&limit= — authentifié (signature du détenteur). */
    private fun handleInbox(req: ReplicaHttp.Request): Pair<Int, String> {
        val d = db ?: return 503 to ReplicaHttp.errorJson("réplica non initialisé")
        val to = (req.query["to"] ?: "").trim().lowercase()
        if (to.isEmpty()) return 400 to ReplicaHttp.errorJson("paramètre to requis")
        // AUTH (spécifique au réplica embarqué — le serveur Go ignore ce paramètre) :
        // la boîte n'est lisible que par le détenteur de l'adresse.
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
        val (events, cursor) = runBlocking {
            val dao = d.replicaDao()
            val list = dao.inbox(to, after, limit)
            val maxGid = dao.maxGid()
            val cur = if (list.size == limit && list.isNotEmpty()) list.last().gid else maxGid
            list to cur
        }
        val arr = events.joinToString(",", "[", "]") { encodeEvent(it) }
        return 200 to "{\"events\":$arr,\"cursor\":$cursor}"
    }

    /** GET /client/profile?author= — carte publique courante (dernier seq gagne). */
    private fun handleProfile(req: ReplicaHttp.Request): Pair<Int, String> {
        val d = db ?: return 503 to ReplicaHttp.errorJson("réplica non initialisé")
        val author = (req.query["author"] ?: "").trim().lowercase()
        if (author.isEmpty()) return 400 to ReplicaHttp.errorJson("paramètre author requis")
        runCatching { OnionCodec.onionToPubKey(author) }
            .getOrElse { return 400 to ReplicaHttp.errorJson("adresse onion invalide") }
        val ev = runBlocking { d.replicaDao().profileOf(author) }
            ?: return 404 to ReplicaHttp.errorJson("aucun profil public pour cet auteur")
        // Le corps (b64) est la carte {"fields":{…}} déjà validée à l'émission —
        // renvoyée telle quelle dans le champ fields (miroir du handler Go).
        val inner = runCatching {
            val raw = String(java.util.Base64.getDecoder().decode(ev.body), Charsets.UTF_8)
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["fields"] ?: JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
        return 200 to buildJsonObject {
            put("author", ev.author)
            put("seq", ev.seq)
            put("ts", ev.ts)
            put("fields", inner)
        }.toString()
    }

    /** GET /client/alias?name= — résolution (règle du plus petit id, §6.3). */
    private fun handleAlias(req: ReplicaHttp.Request): Pair<Int, String> {
        val d = db ?: return 503 to ReplicaHttp.errorJson("réplica non initialisé")
        val name = req.query["name"] ?: ""
        if (!Regex("^[a-z0-9_]{3,24}$").matches(name)) {
            return 400 to ReplicaHttp.errorJson("paramètre name requis (format ^[a-z0-9_]{3,24}\$)")
        }
        val claims = runBlocking { d.replicaDao().aliasEvents() }
        var holder: ReplicaEventEntity? = null
        for (c in claims) {
            val alias = runCatching {
                val raw = String(java.util.Base64.getDecoder().decode(c.body), Charsets.UTF_8)
                Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
            }.getOrNull() ?: continue
            if (alias != name) continue
            val cur = holder
            if (cur == null || c.eventId < cur.eventId) holder = c
        }
        val h = holder
        return if (h == null) {
            200 to "{\"alias\":\"$name\",\"taken\":false}"
        } else {
            200 to "{\"alias\":\"$name\",\"taken\":true,\"by\":\"${h.author}\"}"
        }
    }

    private fun encodeEvent(e: ReplicaEventEntity): String {
        val obj = json.encodeToString(
            WireEvent.serializer(),
            WireEvent(
                type = e.type, author = e.author, seq = e.seq, ts = e.ts,
                ttl = e.ttl, to = e.to.ifEmpty { null }, body = e.body, sig = e.sig
            )
        )
        return obj
    }

    /** Arrêt (process en cours de mort — Wipe/ExitController ; jamais critique). */
    @Synchronized
    fun stop() {
        started = false
        runCatching { server?.close() }
        server = null
        localPort = 0
        scope.cancel()
    }

    /** v0.2.45 — hook de test JVM ([BackendLivenessTest]) : substitue le socket. */
    fun rebindForTest(s: ServerSocket) {
        server = s
        localPort = s.localPort
        started = true
    }

    /** v0.2.45 — hook de test JVM : remet l'état à zéro (sans toucher au scope). */
    fun resetForTest() {
        runCatching { server?.close() }
        server = null
        started = false
        localPort = 0
    }
}
