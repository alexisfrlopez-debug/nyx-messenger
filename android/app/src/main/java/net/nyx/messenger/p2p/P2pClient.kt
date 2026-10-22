package net.nyx.messenger.p2p

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.2.18 — CLIENT du canal direct P2P : connexion SORTANTE via le SOCKS du
 * tor embarqué vers l'onion du pair (port média 47471). Le nom d'hôte est
 * passé NON résolu au proxy (SOCKS5 avec résolution distante) — c'est tor
 * qui résout l'adresse onion ; aucun trafic hors tor (ADR-007).
 */
object P2pClient {

    /** Injecté par ServiceLocator — signature ed25519 (b64) avec la clé d'identité. */
    @Volatile var signer: ((canonical: String) -> String)? = null

    /** Injecté par ServiceLocator — l'adresse onion locale (authentification). */
    @Volatile var me: String? = null

    /** Connecte l'onion du pair sur le port média — socket TCP plein-duplex. */
    fun connect(peer: String, timeoutMs: Int = 40_000): Socket {
        val socks = net.nyx.messenger.tor.TorManager.activeSocksPort
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))
        try {
            socket.connect(
                InetSocketAddress.createUnresolved(peer, P2pProtocol.MEDIA_ONION_PORT),
                timeoutMs
            )
            socket.soTimeout = 60_000
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Dépôt DIRECT d'un fichier : HELLO(file) signé, puis un F_FILE par
     * fragment (corps chiffré E2E identique au chemin journal), puis attente
     * de l'ACK récapitulatif. Renvoie true si le pair a bien reçu ET accepté
     * les [count] fragments — dans ce cas le réplica n'est JAMAIS sollicité.
     * [onProgress] = fragments transmis / total (UI).
     */
    fun pushFile(
        peer: String,
        encryptedBodies: List<String>,
        alias: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        val me = me ?: error("identité absente")
        val sig = signer?.invoke(
            P2pProtocol.helloSignedString("file", me, encryptedBodies.size.toString())
        ) ?: error("signature indisponible")
        val count = encryptedBodies.size
        val socket = connect(peer)
        try {
            socket.getOutputStream().let { out ->
                P2pProtocol.write(
                    out, P2pProtocol.F_HELLO,
                    P2pProtocol.encodeHello(
                        P2pProtocol.Hello("file", me, alias, "", "", count, ""), sig
                    )
                )
                for ((idx, body) in encryptedBodies.withIndex()) {
                    P2pProtocol.write(out, P2pProtocol.F_FILE, body.toByteArray(Charsets.UTF_8))
                    onProgress(idx + 1, count)
                }
            }
            // Demi-fermeture : signal au pair que le dépôt est complet (sinon
            // il attendrait un fragment de plus sans jamais lire l'ACK).
            runCatching { socket.shutdownOutput() }
            val frame = P2pProtocol.read(socket.getInputStream())
            val acked = frame?.takeIf { it.type == P2pProtocol.F_ACK && it.payload.size >= 4 }
                ?.let { f -> ((f.payload[0].toInt() and 0xFF) shl 24) or ((f.payload[1].toInt() and 0xFF) shl 16) or ((f.payload[2].toInt() and 0xFF) shl 8) or (f.payload[3].toInt() and 0xFF) }
                ?: -1
            android.util.Log.i("nyx-p2p", "dépôt direct : $acked/$count acceptés par le pair")
            return acked == count
        } finally {
            runCatching { socket.close() }
        }
    }
}

/**
 * v0.2.18 — Connexion d'appel remise par [P2pServer] à [CallManager] : le
 * HELLO « call » a déjà été authentifié ; le gestionnaire d'appels pilote
 * ensuite les trames (accept/reject/audio/bye) sur le socket brut.
 */
class P2pCallConnection(
    val socket: Socket,
    val hello: P2pProtocol.Hello,
) {
    val input: InputStream get() = socket.getInputStream()
    val output: OutputStream get() = socket.getOutputStream()
    val closed = AtomicBoolean(false)

    /** Envoi de trame thread-safe (l'encodeur audio et le contrôle coexistent). */
    private val writeLock = Any()
    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        if (closed.get()) return
        synchronized(writeLock) {
            P2pProtocol.write(output, type, payload)
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}
