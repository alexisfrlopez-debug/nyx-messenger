package net.nyx.messenger.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nyx.messenger.diag.DiagLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * v0.2.46 — CANAL INVERSÉ NYX-LINK (ADR-018) : le téléphone ne dépend plus
 * de sa joignabilité ENTRANTE Tor pour servir son réplica embarqué.
 *
 * Boucle daemon : un long-poll /link/pull (45 s serveur) vers le réplica
 * ACTIF de flotte — chemin sortant prouvé à 100 % sur le terrain (journal,
 * /health 375 ms, XMPP) — reçoit les requêtes des demandeurs, les exécute
 * LOCALEMENT ([dispatcher] = EmbeddedReplica.dispatch : mêmes règles que le
 * direct — signature, quotas, whitelist, pause Paramètres) et redépose les
 * réponses signées (/link/reply).
 *
 * Conséquence terrain : la sonde de la console et les dépôts/polls des
 * pairs aboutissent MÊME quand l'onion du téléphone est injoignable
 * (« oignon entrant injoignable » affiché console — sessions 41 → 67) :
 * l'affichage passe de INJOIGNABLE à EN LIGNE (via lien inversé).
 *
 * Robustesse : backoff exponentiel 5 s → 30 s sur échec réseau ; chaque
 * échec remonte à [onReplicaFailed] (failover + DescriptorHeal v0.2.45
 * réutilisés) ; le canal suit le réplica actif à CHAQUE itération (une
 * bascule est prise en compte au tour suivant).
 *
 * Injecté par ServiceLocator (testable JVM : socksProvider → null = direct,
 * faux routeur loopback).
 */
object LinkChannel {

    /** Notre adresse complète « xxx.onion » (avec suffixe — forme du canon). */
    @Volatile var me: () -> String? = { null }

    /** Signe un canon (MessageCipher.signEvent — ed25519 libsodium, b64). */
    @Volatile var signer: ((canonical: String) -> String)? = null

    /** Le réplica routeur à interroger (« xxx.onion » — le ACTIF de NyxRepository). */
    @Volatile var replicaProvider: () -> String? = { null }

    /** Port SOCKS Tor actif ; < 0 = pas prêt (attendre) ; 0 = DIRECT (test JVM
     *  loopback uniquement — jamais en production, TorManager fournit un port réel). */
    @Volatile var socksProvider: () -> Int = { -1 }

    /** Exécution locale d'une requête (EmbeddedReplica.dispatch). */
    @Volatile var dispatcher:
        ((method: String, path: String, query: Map<String, String>, body: ByteArray) -> Pair<Int, String>)? = null

    /** Échec réseau du canal (failover/descriptorRefresher — NyxRepository). */
    @Volatile var onReplicaFailed: ((reason: String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Requêtes servies depuis le démarrage (témoin d'activité, export diag). */
    @Volatile var servedCount: Long = 0
        private set

    /** Relances successives après échec (backoff exponentiel, plafonné). */
    @Volatile var consecutiveErrors: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "nyx-link-channel")
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        runCatching { thread?.interrupt() }
        thread = null
    }

    /** v0.2.46 — remise à zéro entre deux tests JVM ([LinkChannelLoopbackTest]). */
    fun resetForTest() {
        stop()
        servedCount = 0
        consecutiveErrors = 0
    }

    private fun loop() {
        DiagLog.i("nyx-link", "canal inversé démarré — le réplica embarqué reçoit désormais via la flotte")
        while (running) {
            val replica = try {
                replicaProvider()
            } catch (_: Exception) {
                null
            }
            val socks = try {
                socksProvider()
            } catch (_: Exception) {
                -1
            }
            val meAddr = try {
                me()
            } catch (_: Exception) {
                null
            }
            if (replica == null || socks < 0 || meAddr.isNullOrBlank() || signer == null || dispatcher == null) {
                sleepQuiet(10_000)
                continue
            }
            try {
                val n = pullRound(replica, socks, meAddr)
                consecutiveErrors = 0
                if (n > 0) DiagLog.i("nyx-link", "lot servi : $n requête(s) (total $servedCount)")
                // Succès (même keepalive vide) : boucle immédiate — le
                // long-poll passé (45 s serveur) cadence naturellement le canal.
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                consecutiveErrors++
                val detail = "${e.javaClass.simpleName}: ${e.message ?: "erreur réseau"}"
                DiagLog.w(
                    "nyx-link",
                    "canal en échec ($consecutiveErrors de suite) vers ${replica.take(16)}… : $detail — backoff ${backoffMs()} ms"
                )
                onReplicaFailed?.invoke("lien inversé : $detail")
                sleepQuiet(backoffMs())
            }
        }
        DiagLog.i("nyx-link", "canal inversé arrêté")
    }

    private fun backoffMs(): Long {
        val shift = consecutiveErrors.coerceAtMost(3) // 5 s, 10 s, 20 s, 30 s… plafonné
        return (5_000L shl shift).coerceAtMost(30_000L)
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            running = false
        }
    }

    /** Un cycle PULL → exécution locale de chaque requête → REPLY. */
    private fun pullRound(replica: String, socks: Int, meAddr: String): Int {
        val client = clientFor(socks)
        val base = "http://$replica"
        val ts = System.currentTimeMillis() / 1000
        val auth = ts.toString() + ":" + signer!!.invoke(LinkWire.pullCanonical(meAddr, ts))
        val pullUrl = "$base/link/pull?me=" + java.net.URLEncoder.encode(meAddr, "UTF-8") +
            "&auth=" + java.net.URLEncoder.encode(auth, "UTF-8")
        val req = Request.Builder().url(pullUrl)
            .header("User-Agent", "Nyx/1")
            .get().build()
        val reqs = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("GET /link/pull → HTTP ${resp.code}" +
                    " ${resp.body?.string()?.take(160).orEmpty()}")
            }
            LinkWire.parsePull(resp.body?.string() ?: "")
        }
        var served = 0
        for (r in reqs) {
            serveOne(client, base, meAddr, r)
            served++
            servedCount++
        }
        return served
    }

    /** Exécute UNE requête localement puis dépose la réponse signée. */
    private fun serveOne(client: OkHttpClient, base: String, meAddr: String, r: LinkWire.LinkReq) {
        val disp = dispatcher ?: return
        val t0 = System.currentTimeMillis()
        val (path, query) = LinkWire.splitPath(r.path)
        val body = try {
            if (r.bodyB64.isEmpty()) ByteArray(0)
            else java.util.Base64.getDecoder().decode(r.bodyB64)
        } catch (_: IllegalArgumentException) {
            DiagLog.w("nyx-link", "requête ${r.id.take(8)}… : corps non base64 — ignorée")
            return
        }
        val (code, jsonResp) = try {
            disp(r.method, path, query, body)
        } catch (e: Exception) {
            DiagLog.w("nyx-link", "requête ${r.id.take(8)}… : exécution locale échouée (${e.message})")
            500 to "{\"error\":\"exécution locale impossible\"}"
        }
        val bodyB64 = java.util.Base64.getEncoder().encodeToString(jsonResp.toByteArray(Charsets.UTF_8))
        val ts = System.currentTimeMillis() / 1000
        val auth = ts.toString() + ":" + signer!!.invoke(
            LinkWire.replyCanonical(meAddr, ts, r.id, code, LinkWire.sha256Hex(bodyB64))
        )
        val replyBody = LinkWire.buildReplyBody(r.id, meAddr, code, bodyB64, auth)
            .toRequestBody("application/json".toMediaType())
        val replyReq = Request.Builder().url("$base/link/reply")
            .header("User-Agent", "Nyx/1")
            .post(replyBody).build()
        client.newCall(replyReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                DiagLog.w(
                    "nyx-link",
                    "réponse ${r.id.take(8)}… refusée (HTTP ${resp.code}" +
                        " ${resp.body?.string()?.take(160).orEmpty()}) — at-least-once : le routeur relivra"
                )
                return
            }
        }
        DiagLog.i(
            "nyx-link",
            "${r.method} ${r.path.take(40)} → $code servi LOCALEMENT puis déposé " +
                "(${System.currentTimeMillis() - t0} ms)"
        )
    }

    /** Client réutilisé (le long-poll 45 s cadence la boucle — un client par tour serait du gaspillage). */
    private var cachedClient: OkHttpClient? = null
    private var cachedSocks = -2

    @Synchronized
    private fun clientFor(socks: Int): OkHttpClient {
        val c = cachedClient
        if (c != null && cachedSocks == socks) return c
        val fresh = buildClient(socks)
        cachedClient = fresh
        cachedSocks = socks
        return fresh
    }

    private fun buildClient(socks: Int): OkHttpClient {
        val b = OkHttpClient.Builder()
        if (socks > 0) {
            b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))
        }
        b.connectTimeout(25, TimeUnit.SECONDS)
        b.readTimeout(50, TimeUnit.SECONDS) // long-poll serveur 45 s + marge
        b.writeTimeout(30, TimeUnit.SECONDS)
        return b.build()
    }
}

/**
 * v0.2.46 — ALLER-RETOUR LIEN côté DEMANDEUR (repli réseau) : dépose une
 * requête pour un pair sur le réplica routeur, puis attend la réponse du
 * téléphone. Paramétrable par (client, base) → testable JVM en loopback.
 */
object LinkTransport {

    /** Renvoie le code HTTP du réplica du pair + son corps, ou exception. */
    suspend fun sendAndAwait(
        client: OkHttpClient,
        base: String,
        to: String,
        method: String,
        path: String,
        bodyB64: String,
        requestId: String = LinkWire.newRequestId(),
    ): LinkWire.LinkResp = withContext(Dispatchers.IO) {
        // 1. Dépôt.
        val sendBody = LinkWire.buildSendBody(to, requestId, method, path, bodyB64)
            .toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder().url("$base/link/send")
                .header("User-Agent", "Nyx/1")
                .post(sendBody).build()
        ).execute().use { resp ->
            if (resp.code != 202) {
                throw IllegalStateException(
                    "POST /link/send → HTTP ${resp.code} : ${resp.body?.string()?.take(200).orEmpty()}"
                )
            }
        }
        // 2. Attente (le serveur long-polle 75 s ; le readTimeout du client
        //    doit couvrir — le client du réplica de flotte est à 120 s).
        client.newCall(
            Request.Builder().url("$base/link/wait?id=$requestId")
                .header("User-Agent", "Nyx/1")
                .get().build()
        ).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 200) {
                return@use LinkWire.parseWait(text)
                    ?: throw IllegalStateException("réponse lien illisible : ${text.take(200)}")
            }
            throw IllegalStateException(
                "GET /link/wait → HTTP ${resp.code} : ${LinkWire.parseError(text) ?: text.take(200)}"
            )
        }
    }
}
