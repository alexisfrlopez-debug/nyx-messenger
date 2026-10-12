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

class RelayClient private constructor(
    replicaOnion: String,
    socksHost: String = "127.0.0.1",
    socksPort: Int = 9050
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "http://$replicaOnion"

    /** GET /health */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$base/health").build()).execute().use {
                it.isSuccessful
            }
        }.getOrDefault(false)
    }

    /** POST /client/event — dépôt d'un événement signé. */
    suspend fun postEvent(event: WireEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(WireEvent.serializer(), event)
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/client/event").post(body).build()
            client.newCall(req).execute().use { resp ->
                check(resp.code == 200) { "POST /client/event → HTTP ${resp.code}" }
            }
        }
    }

    /** GET /client/inbox?to=…&after=… */
    suspend fun inbox(to: String, after: Long): InboxResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/client/inbox?to=$to&after=$after").build()
        client.newCall(req).execute().use { resp ->
            check(resp.code == 200) { "GET /client/inbox → HTTP ${resp.code}" }
            json.decodeFromString(InboxResponse.serializer(), resp.body!!.string())
        }
    }

    companion object {
        /** Fabrique explicite : un réplica + le proxy Tor. */
        fun forReplica(onion: String, socksPort: Int = 9050): RelayClient =
            RelayClient(onion, socksPort = socksPort)
    }
}
