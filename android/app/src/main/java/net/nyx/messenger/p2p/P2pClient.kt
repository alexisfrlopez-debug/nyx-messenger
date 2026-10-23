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

    /**
     * v0.2.37 — délai entre HSFETCH et la 2ᵉ tentative : le fetch du
     * descripteur + son stockage + la sélection d'un point d'introduction
     * frais prennent ~10-15 s côté tor (source tor 0.4.9 : fetch_v3_desc →
     * cache_store_as_client → client_desc_has_arrived).
     */
    private const val DESCRIPTOR_SETTLE_MS = 12_000L

    /**
     * Connecte l'onion du pair sur le port média — socket TCP plein-duplex.
     *
     * v0.2.37 — RÉCUPÉRATION DESCRIPTEUR PÉRIMÉ (cause racine terrain
     * « impossible de décrocher » + « SOCKS server general failure », logs
     * 22/10 + source tor 0.4.9.14 lu) : après un redémarrage de l'app du
     * pair, son descripteur onion garde d'ANCIENS points d'introduction
     * (morts) dans le cache client (jusqu'à 24 h !) et chez certains des 8
     * HSDirs (30-60 s de propagation) → chaque connexion échoue en ~1 s par
     * « SOCKS server general failure » (échec d'INTRODUCE). Réaction : sur
     * échec, HSFETCH via le control port (force la re-demande du descripteur
     * en CONTOURNANT le cache — TorControlClient.hsfetch), on laisse le fetch
     * aboutir, puis UNE nouvelle tentative. Sans ça, l'appel meurt au bout
     * de sa fenêtre d'essais alors que le pair est en parfaite santé.
     *
     * [descriptorRecovery] = false → comportement v0.2.36 (une seule tentative,
     * pour les tests unitaires).
     */
    fun connect(peer: String, timeoutMs: Int = 40_000, descriptorRecovery: Boolean = true): Socket {
        val first = tryConnect(peer, timeoutMs)
        if (first != null) return first
        if (!descriptorRecovery) throw lastConnectError.get()
            ?: java.io.IOException("connexion impossible vers ${peer.take(16)}…")
        // Récupération : descripteur périmé ? → HSFETCH + nouvelle tentative.
        net.nyx.messenger.tor.TorManager.get(
            net.nyx.messenger.di.ServiceLocator.appContext()
        ).forceDescriptorRefresh(peer)
        try { Thread.sleep(DESCRIPTOR_SETTLE_MS) } catch (_: InterruptedException) { }
        val second = tryConnect(peer, timeoutMs)
        if (second != null) return second
        throw lastConnectError.get()
            ?: java.io.IOException("connexion impossible vers ${peer.take(16)}…")
    }

    /** Dernière erreur de connexion (rejouée par [connect] à la fin). */
    private val lastConnectError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

    /** Une tentative de connexion — Socket ou null (erreur dans [lastConnectError]). */
    private fun tryConnect(peer: String, timeoutMs: Int): Socket? {
        val socks = net.nyx.messenger.tor.TorManager.activeSocksPort
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

    /**
     * v0.2.41 — DÉPÔT DIRECT D'ÉVÉNEMENTS (ADR-017, transport de replan) :
     * les MÊMES événements signés que le chemin journal ([WireEvent] — JSON
     * filaire identique à POST /client/event) partent par le canal direct
     * onion→onion quand le réplica est injoignable ou dégradé. HELLO
     * « events » signé (selector = nombre d'événements), une trame F_EVENT
     * par événement, demi-fermeture, ACK récapitulatif.
     *
     * Timeout volontairement COURT (20 s, sans récupération HSFETCH) : ce
     * chemin est un REPLI dans un flux d'envoi — un pair hors ligne répond
     * « SOCKS general failure » en ~1-5 s ; traîner 90 s (connect + HSFETCH
     * + reconnect) gèlerait l'envoi pour rien. Renvoie true si le pair a
     * accepté les [events.size] événements.
     */
    fun pushEvents(
        peer: String,
        events: List<net.nyx.messenger.net.WireEvent>,
        alias: String,
        timeoutMs: Int = 20_000,
    ): Boolean {
        if (events.isEmpty()) return true
        val me = me ?: error("identité absente")
        val sig = signer?.invoke(
            P2pProtocol.helloSignedString("events", me, events.size.toString())
        ) ?: error("signature indisponible")
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val socket = connect(peer, timeoutMs, descriptorRecovery = false)
        try {
            socket.getOutputStream().let { out ->
                P2pProtocol.write(
                    out, P2pProtocol.F_HELLO,
                    P2pProtocol.encodeHello(
                        P2pProtocol.Hello("events", me, alias, "", "", events.size, ""), sig
                    )
                )
                for (ev in events) {
                    P2pProtocol.write(
                        out, P2pProtocol.F_EVENT,
                        json.encodeToString(
                            net.nyx.messenger.net.WireEvent.serializer(), ev
                        ).toByteArray(Charsets.UTF_8)
                    )
                }
            }
            runCatching { socket.shutdownOutput() }
            val frame = P2pProtocol.read(socket.getInputStream())
            val acked = frame?.takeIf { it.type == P2pProtocol.F_ACK && it.payload.size >= 4 }
                ?.let { f -> ((f.payload[0].toInt() and 0xFF) shl 24) or ((f.payload[1].toInt() and 0xFF) shl 16) or ((f.payload[2].toInt() and 0xFF) shl 8) or (f.payload[3].toInt() and 0xFF) }
                ?: -1
            android.util.Log.i("nyx-p2p", "dépôt direct événements : $acked/${events.size} ingérés par le pair")
            return acked == events.size
        } finally {
            runCatching { socket.close() }
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
