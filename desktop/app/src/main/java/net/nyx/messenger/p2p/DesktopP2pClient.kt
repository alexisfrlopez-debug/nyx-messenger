package net.nyx.messenger.p2p

import net.nyx.messenger.desktop.DesktopLog
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.3.6 — PORTAGE de P2pClient Android v0.2.18/0.2.37 : client du canal
 * direct P2P — connexion SORTANTE via le SOCKS du tor embarqué vers l'onion
 * du pair (port média 47471). Le nom d'hôte est passé NON résolu au proxy
 * (SOCKS5 avec résolution distante) — c'est tor qui résout l'adresse onion ;
 * aucun trafic hors tor (ADR-007).
 *
 * Récupération descripteur périmé (v0.2.37 Android, miroir) : sur échec,
 * HSFETCH via le control port (force la re-demande du descripteur en
 * contournant le cache client — un descripteur aux points d'intro morts
 * échoue en « SOCKS server general failure »), pause de ~12 s, puis UNE
 * nouvelle tentative.
 */
object DesktopP2pClient {

    /** Injecté par ServiceLocator — signature ed25519 (b64) avec la clé d'identité. */
    @Volatile var signer: ((canonical: String) -> String)? = null

    /** Injecté par ServiceLocator — l'adresse onion locale (authentification). */
    @Volatile var me: String? = null

    /** Injecté par ServiceLocator — port SOCKS tor courant (0 = pas démarré). */
    @Volatile var socksPort: () -> Int = { 0 }

    /** Injecté par ServiceLocator — HSFETCH forcé du descripteur d'un pair (contrôle tor). */
    @Volatile var descriptorRefresher: ((onion: String) -> Unit)? = null

    /** v0.2.37 miroir — délai entre HSFETCH et la 2ᵉ tentative (fetch+stockage+intro ≈ 12 s). */
    private const val DESCRIPTOR_SETTLE_MS = 12_000L

    /** Dernière erreur de connexion (rejouée par [connect] à la fin). */
    private val lastConnectError = AtomicReference<Throwable?>(null)

    /**
     * Connecte l'onion du pair sur le port média — socket TCP plein-duplex.
     * [descriptorRecovery] = false → une seule tentative (tests unitaires).
     */
    fun connect(peer: String, timeoutMs: Int = 40_000, descriptorRecovery: Boolean = true): Socket {
        val first = tryConnect(peer, timeoutMs)
        if (first != null) return first
        if (!descriptorRecovery) throw lastConnectError.get()
            ?: java.io.IOException("connexion impossible vers ${peer.take(16)}…")
        val socks = socksPort()
        if (socks > 0) {
            runCatching { descriptorRefresher?.invoke(peer) }
            DesktopLog.i("nyx-call", "descripteur pair périmé ? HSFETCH ${peer.take(16)}… puis nouvelle tentative")
        }
        try { Thread.sleep(DESCRIPTOR_SETTLE_MS) } catch (_: InterruptedException) { }
        val second = tryConnect(peer, timeoutMs)
        if (second != null) return second
        throw lastConnectError.get()
            ?: java.io.IOException("connexion impossible vers ${peer.take(16)}…")
    }

    /** Une tentative de connexion — Socket ou null (erreur dans [lastConnectError]). */
    private fun tryConnect(peer: String, timeoutMs: Int): Socket? {
        val socks = socksPort()
        if (socks <= 0) {
            lastConnectError.set(java.io.IOException("Tor n'est pas démarré (port SOCKS inconnu)"))
            return null
        }
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))
        return try {
            socket.connect(
                InetSocketAddress.createUnresolved(peer, P2pProtocol.MEDIA_ONION_PORT),
                timeoutMs
            )
            socket.soTimeout = 60_000
            socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            lastConnectError.set(e)
            null
        }
    }
}

/**
 * v0.3.6 — PORTAGE de P2pCallConnection Android : connexion d'appel remise
 * par [DesktopP2pServer] à [DesktopCallManager] — le HELLO « call » a déjà
 * été authentifié ; le gestionnaire d'appels pilote ensuite les trames
 * (accept/reject/audio/bye) sur le socket brut.
 */
class P2pCallConnection(
    val socket: Socket,
    val hello: P2pProtocol.Hello,
) {
    val input: java.io.InputStream get() = socket.getInputStream()
    val output: java.io.OutputStream get() = socket.getOutputStream()
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)

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
