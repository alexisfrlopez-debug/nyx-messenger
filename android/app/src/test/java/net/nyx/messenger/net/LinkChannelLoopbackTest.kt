package net.nyx.messenger.net

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * v0.2.46 — INTÉGRATION RÉELLE DU CANAL INVERSÉ (ADR-018) : le VRAI
 * [LinkChannel] (boucle daemon, trames [LinkWire]) et le VRAI
 * [LinkTransport] (côté demandeur) contre un FAUX ROUTEUR loopback
 * (MockWebServer) qui vérifie les signatures EXACTEMENT comme le routeur
 * Go (linkVerifySig : canon + fenêtre ±300 s + base64). Même philosophie
 * que P2pEventsExchangeTest (socket loopback réelle) : le contrat
 * inter-processus est prouvé, pas juste les fonctions pures.
 */
class LinkChannelLoopbackTest {

    private lateinit var router: MockWebServer

    /** File des requêtes à livrer au prochain PULL du téléphone. */
    private val pendingReqs = ConcurrentLinkedQueue<LinkWire.LinkReq>()

    /** Réponses déposées par le téléphone (id → code + corps b64), vérifiées. */
    private val replies = ConcurrentHashMap<String, Pair<Int, String>>()

    /** La dernière auth PULL reçue (preuve du canon signé). */
    @Volatile private var lastPullAuth: String? = null

    // Identité ed25519 de test (BouncyCastle — miroir du sandbox Go). La clé
    // publique est DÉRIVÉE de la privée (le seed brut n'est PAS une clé
    // publique ed25519 valide : la validation BC la rejette selon les bits).
    private val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    private val pub = priv.generatePublicKey() as Ed25519PublicKeyParameters
    private val fakeOnion = "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion"

    private fun sign(canonical: String): String {
        val s = Ed25519Signer()
        s.init(true, priv)
        val msg = canonical.toByteArray(Charsets.UTF_8)
        s.update(msg, 0, msg.size)
        return Base64.getEncoder().encodeToString(s.generateSignature())
    }

    private fun verify(canonical: String, sigB64: String): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, pub)
        val msg = canonical.toByteArray(Charsets.UTF_8)
        s.update(msg, 0, msg.size)
        s.verifySignature(Base64.getDecoder().decode(sigB64))
    } catch (_: Exception) {
        false
    }

    /** Le routeur — mêmes règles que server/internal/api/link.go. */
    private inner class RouterDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath ?: ""
            return when {
                path.startsWith("/link/pull") -> handlePull(request)
                path.startsWith("/link/reply") -> handleReply(request)
                path.startsWith("/link/send") -> handleSend(request)
                path.startsWith("/link/wait") -> handleWait(request)
                else -> json(404, "{\"error\":\"route inconnue\"}")
            }
        }
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** GET /link/pull — auth par canon PULL, file livrée immédiatement. */
    private fun handlePull(request: RecordedRequest): MockResponse {
        val me = request.requestUrl?.queryParameter("me") ?: ""
        val auth = request.requestUrl?.queryParameter("auth") ?: ""
        lastPullAuth = auth
        val sep = auth.indexOf(':')
        val ts = if (sep > 0) auth.substring(0, sep).toLongOrNull() else null
        val sig = if (sep > 0) auth.substring(sep + 1) else ""
        val now = System.currentTimeMillis() / 1000
        if (ts == null || sig.isEmpty() || kotlin.math.abs(now - ts) > 300 ||
            !verify(LinkWire.pullCanonical(me, ts), sig)
        ) {
            return json(401, "{\"error\":\"authentification lien invalide (ts:sig)\"}")
        }
        val reqs = ArrayList<LinkWire.LinkReq>()
        while (reqs.size < 4) {
            val r = pendingReqs.poll() ?: break
            reqs.add(r)
        }
        if (reqs.isEmpty()) {
            Thread.sleep(400) // imite le long-poll 45 s, évite le spin du test
        }
        val body = reqs.joinToString(",", "{\"reqs\":[", "]}") { r ->
            "{\"id\":\"${r.id}\",\"method\":\"${r.method}\",\"path\":\"${r.path}\",\"body_b64\":\"${r.bodyB64}\"}"
        }
        return json(200, body)
    }

    /** POST /link/reply — signature sur le canon REPLY complet. */
    private fun handleReply(request: RecordedRequest): MockResponse {
        val raw = request.body.readUtf8()
        val id = net.nyx.messenger.p2p.MiniJson.str(raw, "id") ?: ""
        val from = net.nyx.messenger.p2p.MiniJson.str(raw, "from") ?: ""
        val status = net.nyx.messenger.p2p.MiniJson.num(raw, "status")?.toInt() ?: 0
        val bodyB64 = net.nyx.messenger.p2p.MiniJson.str(raw, "body_b64") ?: ""
        val auth = net.nyx.messenger.p2p.MiniJson.str(raw, "auth") ?: ""
        val sep = auth.indexOf(':')
        val ts = if (sep > 0) auth.substring(0, sep).toLongOrNull() else null
        val sig = if (sep > 0) auth.substring(sep + 1) else ""
        val now = System.currentTimeMillis() / 1000
        if (ts == null || sig.isEmpty() || kotlin.math.abs(now - ts) > 300 ||
            !verify(LinkWire.replyCanonical(from, ts, id, status, LinkWire.sha256Hex(bodyB64)), sig)
        ) {
            return json(401, "{\"error\":\"signature reply invalide\"}")
        }
        replies[id] = status to bodyB64
        return json(202, "{\"id\":\"$id\"}")
    }

    /** POST /link/send — dépôt d'un demandeur (whitelist testée côté Go). */
    private fun handleSend(request: RecordedRequest): MockResponse {
        val raw = request.body.readUtf8()
        val id = net.nyx.messenger.p2p.MiniJson.str(raw, "id") ?: ""
        val method = net.nyx.messenger.p2p.MiniJson.str(raw, "method") ?: "GET"
        val path = net.nyx.messenger.p2p.MiniJson.str(raw, "path") ?: ""
        val bodyB64 = net.nyx.messenger.p2p.MiniJson.str(raw, "body_b64") ?: ""
        pendingReqs.add(LinkWire.LinkReq(id, method, path, bodyB64))
        return json(202, "{\"id\":\"$id\",\"queued\":\"true\"}")
    }

    /** GET /link/wait — long-poll du demandeur (10 s max côté test). */
    private fun handleWait(request: RecordedRequest): MockResponse {
        val id = request.requestUrl?.queryParameter("id") ?: ""
        val deadline = System.currentTimeMillis() + 10_000
        var r = replies[id]
        while (r == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            r = replies[id]
        }
        return if (r == null) {
            json(408, "{\"error\":\"délai dépassé — téléphone sans canal ou injoignable\"}")
        } else {
            json(200, "{\"status\":${r.first},\"body_b64\":\"${r.second}\"}")
        }
    }

    @Before
    fun setUp() {
        router = MockWebServer()
        router.dispatcher = RouterDispatcher()
        router.start()
    }

    @After
    fun tearDown() {
        LinkChannel.resetForTest()
        router.shutdown()
    }

    private fun routerBase(): String = "localhost:${router.port}"

    private fun configureChannel(
        dispatcher: (String, String, Map<String, String>, ByteArray) -> Pair<Int, String>,
        signer: (String) -> String = { sign(it) },
    ) {
        LinkChannel.me = { fakeOnion }
        LinkChannel.signer = signer
        LinkChannel.replicaProvider = { routerBase() }
        LinkChannel.socksProvider = { 0 } // DIRECT (test loopback — jamais en prod)
        LinkChannel.dispatcher = dispatcher
        LinkChannel.onReplicaFailed = { }
    }

    @Test
    fun `aller-retour complet demandeur-canal - send, pull, exécution, reply, wait`() {
        // Le réplica embarqué local (stub du dispatcher) répond /health.
        configureChannel(dispatcher = { method, path, _, _ ->
            if (method == "GET" && path == "/health") {
                200 to "{\"ok\":true,\"version\":\"0.2.46-test\",\"onion\":\"$fakeOnion\",\"events\":7}"
            } else {
                404 to "{\"error\":\"route inconnue\"}"
            }
        })
        LinkChannel.start()

        // Le DEMANDEUR (console/pair en production) dépose SA requête et
        // attend la réponse — le VRAI [LinkTransport] du côté demandeur
        // contre le VRAI [LinkChannel] du côté téléphone, via le routeur.
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val resp = kotlinx.coroutines.runBlocking {
            LinkTransport.sendAndAwait(client, "http://${routerBase()}", fakeOnion, "GET", "/health", "")
        }
        LinkChannel.stop()

        assertEquals(200, resp.status)
        val decoded = String(Base64.getDecoder().decode(resp.bodyB64), Charsets.UTF_8)
        assertTrue("la réponse doit être le /health du réplica LOCAL du téléphone", decoded.contains("\"ok\":true"))
        assertTrue(decoded.contains("0.2.46-test"))
        assertTrue(LinkChannel.servedCount >= 1)
        assertNotNull("l'auth PULL a été reçue et validée par le routeur", lastPullAuth)
    }

    @Test
    fun `requête déposée avant le canal - livrée au premier pull`() {
        configureChannel(dispatcher = { _, _, _, _ -> 200 to "{\"ok\":true}" })
        // La requête attend AVANT que le canal ne démarre (le cas nominal :
        // la console sonde un téléphone dont le canal tourne déjà).
        val reqId = LinkWire.newRequestId()
        pendingReqs.add(LinkWire.LinkReq(reqId, "GET", "/health", ""))

        LinkChannel.start()
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (System.currentTimeMillis() < deadline && !replies.containsKey(reqId)) {
            Thread.sleep(100)
        }
        LinkChannel.stop()

        val reply = replies[reqId]
        assertNotNull("la réponse du téléphone n'est jamais arrivée (canal ou signature cassés)", reply)
        assertEquals(200, reply!!.first)
        val decoded = String(Base64.getDecoder().decode(reply.second), Charsets.UTF_8)
        assertTrue(decoded.contains("\"ok\":true"))
        assertTrue(LinkChannel.servedCount >= 1)
    }

    @Test
    fun `requête inconnue servie en 404 local - le lien n'est pas un proxy ouvert`() {
        configureChannel(dispatcher = { _, _, _, _ -> 404 to "{\"error\":\"route inconnue\"}" })
        val reqId = LinkWire.newRequestId()
        pendingReqs.add(
            LinkWire.LinkReq(reqId, "GET", "/admin/status", "")
        ) // interdit côté Go (whitelist), inconnu côté local

        LinkChannel.start()
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (System.currentTimeMillis() < deadline && !replies.containsKey(reqId)) {
            Thread.sleep(100)
        }
        LinkChannel.stop()

        val reply = replies[reqId]
        assertNotNull(reply)
        // Le routeur Go aurait refusé le dépôt (whitelist) — ici on vérifie
        // que MÊME livrée par erreur, la requête se heurte au 404 LOCAL.
        assertEquals(404, reply!!.first)
    }

    @Test
    fun `signature invalide - le routeur refuse le canal`() {
        configureChannel(
            dispatcher = { _, _, _, _ -> 200 to "{}" },
            signer = { _ -> Base64.getEncoder().encodeToString(ByteArray(64)) }, // signature poubelle
        )
        val reqId = LinkWire.newRequestId()
        pendingReqs.add(LinkWire.LinkReq(reqId, "GET", "/health", ""))

        LinkChannel.start()
        Thread.sleep(1500) // le canal tourne avec une signature invalide
        LinkChannel.stop()

        assertFalse(
            "aucune réponse ne doit être acceptée avec une signature invalide",
            replies.containsKey(reqId)
        )
    }
}
