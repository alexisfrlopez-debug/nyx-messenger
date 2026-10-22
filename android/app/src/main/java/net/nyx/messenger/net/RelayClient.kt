package net.nyx.messenger.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Client du nœud serveur Nyx — TOUS les appels passent par le SOCKS5 de Tor.
 * Constructeur avec proxy explicite : impossible de créer un client hors Tor
 * (garantie d'architecture, cf. docs/01-modele-securite.md §2.4).
 */
@Serializable
data class WireEvent(
    val type: String,
    val author: String,
    val seq: Long,
    val ts: Long,
    val ttl: Long = 0,
    val to: String? = null,
    val body: String,
    val sig: String
)

@Serializable
data class InboxResponse(val events: List<WireEvent> = emptyList(), val cursor: Long = 0)

/** Réponse de résolution d'un pseudonyme — GET /client/alias (wire-format.md §6). */
@Serializable
data class AliasResolve(val alias: String, val taken: Boolean, val by: String? = null)

/** Un champ public d'une carte de profil (wire-format.md §7). */
@Serializable
data class ProfileFieldDto(val value: String = "", val public: Boolean = true)

/** Réponse GET /client/profile — carte publique d'un auteur (v0.2.0). */
@Serializable
data class ProfileResponse(
    val author: String,
    val seq: Long = 0,
    val ts: Long = 0,
    val fields: Map<String, ProfileFieldDto> = emptyMap()
)

class RelayClient private constructor(
    replicaOnion: String,
    socksHost: String = "127.0.0.1",
    socksPort: Int = 9050
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
        // v0.2.16 : 60 s → 25 s — le connect via SOCKS inclut la construction du
        // circuit onion ; un circuit réussissant typiquement en 5-15 s, une
        // tentative qui n'a pas abouti en 25 s ne passera pas en 60 s : mieux
        // vaut échouer tôt et RETENTER (terrain v0.2.15 : la sonde restait
        // bloquée 60 s par tentative, l'écran ne se rafraîchissait qu'une
        // fois par minute et tout paraissait figé).
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "http://$replicaOnion"

    /** Rapport d'une sonde /health (v0.2.5 — affiché sur l'écran Connexions). */
    data class HealthReport(val ok: Boolean, val ms: Long, val detail: String?)

    /**
     * GET /health avec rapport complet (v0.2.5) : durée + cause d'échec.
     * AVANT, les exceptions étaient avalées (getOrDefault(false)) — les logs
     * device ne disaient RIEN de l'échec de la sonde réplica (« réplica
     * injoignable » sans aucune trace). Chaque tentative est loguée nyx-conn.
     */
    suspend fun healthDetailed(): HealthReport = withContext(Dispatchers.IO) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            client.newCall(Request.Builder().url("$base/health").build()).execute().use { resp ->
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                val ok = resp.isSuccessful
                if (ok) {
                    android.util.Log.i("nyx-conn", "GET /health OK (${ms} ms)")
                } else {
                    android.util.Log.w("nyx-conn", "GET /health → HTTP ${resp.code} (${ms} ms)")
                }
                HealthReport(ok, ms, if (ok) null else "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "erreur réseau"}"
            android.util.Log.w("nyx-conn", "GET /health ÉCHEC (${ms} ms) : $detail")
            HealthReport(false, ms, detail)
        }
    }

    /** GET /health */
    suspend fun health(): Boolean = healthDetailed().ok

    /** POST /client/event — dépôt d'un événement signé. */
    suspend fun postEvent(event: WireEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(WireEvent.serializer(), event)
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/client/event").post(body).build()
            client.newCall(req).execute().use { resp ->
                // v0.2.10 : le corps d'erreur du réplica (ex. « seq 3 en attente
                // (attendu 2) ») est remonté dans le message — il alimente
                // l'auto-réparation de séquence côté [NyxRepository.postSigned].
                check(resp.code == 200) {
                    "POST /client/event → HTTP ${resp.code} : " +
                        resp.body?.string()?.take(300).orEmpty()
                }
            }
        }
    }

    /**
     * GET /client/inbox?to=…&after=…
     * v0.2.17 : paramètre limit — le serveur répond par lots bornés (défaut
     * 500 côté réplica). Avec des fragments de fichier en vol (≈171 Ko sur
     * le fil chacun), une réponse non bornée peut dépasser la minute Tor ;
     * 32 lots × 171 Ko ≈ 5,5 Mo max par poll, dans le readTimeout 120 s.
     * Le curseur ne s'arrête que lorsque TOUS les événements au-delà sont
     * livrés (le serveur recadre le curseur — rien n'est perdu).
     *
     * v0.2.19 : [auth] = preuve « ts:sig » que le demandeur détient bien
     * l'adresse `to` (signature ed25519 de [net.nyx.messenger.replica.ReplicaValidate.inboxCanonical]).
     * Le réplica EMBARQUÉ (v0.2.19 ; toutes versions depuis v0.2.35) l'exige ;
 * le serveur Go
     * IGNORE ce paramètre inconnu — le même client parle aux deux, zéro
     * changement serveur.
     */
    suspend fun inbox(to: String, after: Long, limit: Int = 32, auth: String? = null): InboxResponse = withContext(Dispatchers.IO) {
        val authPart = auth?.let { "&auth=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val req = Request.Builder().url("$base/client/inbox?to=$to&after=$after&limit=$limit$authPart").build()
        client.newCall(req).execute().use { resp ->
            check(resp.code == 200) { "GET /client/inbox → HTTP ${resp.code}" }
            json.decodeFromString(InboxResponse.serializer(), resp.body!!.string())
        }
    }

    /** GET /client/alias?name=… — disponibilité d'un pseudonyme (v0.1.2). */
    suspend fun resolveAlias(name: String): AliasResolve = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/client/alias?name=$name").build()
        client.newCall(req).execute().use { resp ->
            check(resp.code == 200) { "GET /client/alias → HTTP ${resp.code}" }
            json.decodeFromString(AliasResolve.serializer(), resp.body!!.string())
        }
    }

    /**
     * GET /client/profile?author=… — carte publique d'un auteur (v0.2.0).
     * null si l'auteur n'a pas de profil public (404 — cas normal).
     */
    suspend fun profile(author: String): ProfileResponse? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/client/profile?author=$author").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@use null
            check(resp.code == 200) { "GET /client/profile → HTTP ${resp.code}" }
            json.decodeFromString(ProfileResponse.serializer(), resp.body!!.string())
        }
    }

    companion object {
        /** Fabrique explicite : un réplica + le proxy Tor. */
        fun forReplica(onion: String, socksPort: Int = 9050): RelayClient =
            RelayClient(onion, socksPort = socksPort)
    }
}
