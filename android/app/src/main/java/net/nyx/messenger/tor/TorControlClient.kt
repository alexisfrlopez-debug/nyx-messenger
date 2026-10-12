package net.nyx.messenger.tor

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale

/**
 * Client minimal du control port Tor — miroir Kotlin de
 * `server/internal/tor/control.go` (commandes AUTHENTICATE, GETINFO, ADD_ONION,
 * DEL_ONION). Volontairement sans dépendance : le protocole est simple et stable.
 *
 * Authentification par cookie (CookieAuthentication 1 dans le torrc généré).
 */
class TorControlClient(private val controlPort: Int) : AutoCloseable {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private fun connect() {
        if (socket != null && socket!!.isConnected && !socket!!.isClosed) return
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", controlPort), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII))
    }

    /** Envoie une commande et lit le bloc de réponse multi-lignes. */
    private fun cmd(line: String): ControlResponse {
        connect()
        writer!!.write(line)
        writer!!.write("\r\n")
        writer!!.flush()
        val lines = ArrayList<String>()
        val values = HashMap<String, String>()
        var code = 0
        while (true) {
            val raw = reader!!.readLine() ?: error("control port : connexion fermée (réponse à « $line »)")
            if (raw.length < 4) error("control port : ligne invalide « $raw »")
            code = raw.substring(0, 3).toIntOrNull() ?: error("control port : code invalide « $raw »")
            val rest = raw.substring(4)
            lines.add(rest)
            val eq = rest.indexOf('=')
            if (eq > 0) values[rest.substring(0, eq)] = rest.substring(eq + 1)
            if (raw[3] == ' ') break // ' ' = fin de bloc, '-' = continuation
        }
        if (code >= 400) error("control port : erreur Tor $code — ${lines.joinToString(" / ")}")
        return ControlResponse(code, lines, values)
    }

    /** AUTHENTICATE avec le cookie généré par tor. */
    fun authenticate(cookieFile: File) {
        val hex = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
        cmd("AUTHENTICATE $hex")
    }

    /** GETINFO status/bootstrap-phase → « Bootstrapped 65% (…) ». */
    fun bootstrapPhase(): String = cmd("GETINFO status/bootstrap-phase")
        .values["status/bootstrap-phase"].orEmpty()

    /**
     * Publie le service onion v3 du client à partir de sa clé privée ed25519
     * (64 octets : graine + clé publique — même encodage que le serveur Go).
     * Port 80 côté onion → 127.0.0.1:[targetPort]. Retourne le ServiceID.
     */
    fun publishOnionEd25519V3(privateKey64: ByteArray, targetPort: Int): String {
        require(privateKey64.size == 64) { "clé ed25519 : ${privateKey64.size} octets ≠ 64" }
        val keyB64 = Base64.getEncoder().encodeToString(privateKey64)
        val resp = cmd("ADD_ONION ED25519-V3:$keyB64 Flags=DiscardPK Port=80,127.0.0.1:$targetPort")
        return resp.values["ServiceID"] ?: error("ADD_ONION : ServiceID absent (${resp.lines})")
    }

    /** Retire un service onion publié (arrêt propre). */
    fun unpublish(serviceId: String) {
        runCatching { cmd("DEL_ONION $serviceId") }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    data class ControlResponse(val code: Int, val lines: List<String>, val values: Map<String, String>)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000

        /** « Bootstrapped 65% (Loading relay descriptors…) » → 65, ou null si non parsable. */
        fun parsePercent(bootstrapPhase: String): Int? =
            Regex("Bootstrapped (\\d+)%").find(bootstrapPhase)?.groupValues?.get(1)?.toIntOrNull()

        /** True quand le bootstrap est terminé. */
        fun isDone(bootstrapPhase: String): Boolean =
            bootstrapPhase.lowercase(Locale.ROOT).contains("done")
    }
}
