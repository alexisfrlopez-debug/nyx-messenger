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
 * Client du nœud serveur Nyx — portage desktop de RelayClient Android.
 * TOUS les appels passent par le SOCKS5 de Tor (garantie d'architecture —
 * constructeur privé : impossible de créer un client hors Tor).
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
        // v0.2.16 (miroir Android) : 25 s — un circuit onion réussit typiquement
        // en 5-15 s ; mieux vaut échouer tôt et RETENTER.
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "http://$replicaOnion"

    /** En-tête UA neutre posé sur CHAQUE requête (miroir Android v0.2.42). */
    private fun request(url: String) =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)

    data class HealthReport(val ok: Boolean, val ms: Long, val detail: String?)

    suspend fun healthDetailed(): HealthReport = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(request("$base/health").build()).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val ok = resp.isSuccessful
                net.nyx.messenger.desktop.DesktopLog.i(
                    "nyx-conn", "GET /health ${if (ok) "OK" else "→ HTTP ${resp.code}"} (${ms} ms)"
                )
                HealthReport(ok, ms, if (ok) null else "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "erreur réseau"}"
            net.nyx.messenger.desktop.DesktopLog.w("nyx-conn", "GET /health ÉCHEC (${ms} ms) : $detail")
            HealthReport(false, ms, detail)
        }
    }

    suspend fun health(): Boolean = healthDetailed().ok

    /** POST /client/event — dépôt d'un événement signé. */
    suspend fun postEvent(event: WireEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(WireEvent.serializer(), event)
                .toRequestBody("application/json".toMediaType())
            val req = request("$base/client/event").post(body).build()
            client.newCall(req).execute().use { resp ->
                // (miroir v0.2.10) le corps d'erreur du réplica alimente
                // l'auto-réparation de séquence côté repository (SeqResync).
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Unit
                } else {
                    val reason = text.take(200).ifBlank { "HTTP ${resp.code}" }
                    error("HTTP ${resp.code} $reason")
                }
            }
        }
    }

    /** GET /client/inbox?to=&after=&limit — boîte de réception. */
    suspend fun inbox(to: String, after: Long, limit: Int = 100): Result<InboxResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = request("$base/client/inbox?to=$to&after=$after&limit=$limit").get().build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code} ${text.take(160)}")
                    json.decodeFromString(InboxResponse.serializer(), text)
                }
            }
        }

    /** GET /client/alias?name= — résolution de pseudonyme (v1.1). */
    suspend fun resolveAlias(name: String): Result<AliasResolve?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = request("$base/client/alias?name=$name").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                json.decodeFromString(AliasResolve.serializer(), resp.body?.string().orEmpty())
            }
        }
    }

    /** GET /client/profile?author= — carte publique (v1.2). */
    suspend fun profile(author: String): Result<ProfileResponse?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = request("$base/client/profile?author=$author").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@runCatching null
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString(ProfileResponse.serializer(), resp.body?.string().orEmpty())
            }
        }
    }

    companion object {
        private const val USER_AGENT = "Nyx/1"

        /** Fabrique : cache par (onion, port SOCKS) — le port tor est dynamique. */
        fun of(replicaOnion: String, socksPort: Int): RelayClient =
            RelayClient(replicaOnion, "127.0.0.1", socksPort)
    }
}
