package net.nyx.messenger.p2p

import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.net.Loopback
import java.net.ServerSocket
import java.net.Socket

/**
 * v0.3.6 — PORTAGE de P2pServer Android v0.2.18/0.2.45-0.2.48 : serveur du
 * canal direct P2P — écouteur local (loopback IPv4 explicite) recevant les
 * connexions tor entrantes du 2ᵉ port onion (47471, publié par
 * TorControlClient.publishOnionEd25519V3(mediaPort)).
 *
 * Chaque connexion est authentifiée au HELLO (signature ed25519 de la clé
 * d'identité + contact accepté) puis routée :
 *  - « call » → la connexion brute est remise à [callSink]
 *    ([DesktopCallManager] prend la main : sonnerie déjà en cours via la
 *    signalisation journal) ;
 *  - « file »/« events » → REFUS PROPRE (le bureau v0.3.6 n'implémente que le
 *    canal d'appel — les fragments directs restent au chemin journal, déjà
 *    fonctionnel et validé terrain ; un pair Android retombe dessus).
 *
 * L'écouteur démarre dès l'initialisation (identité présente) — AVANT la
 * publication onion, pour que le port média soit servi dès l'annonce.
 */
object DesktopP2pServer {

    @Volatile var localPort: Int = 0
        private set

    @Volatile private var started = false

    /** Injecté par ServiceLocator — le contact est-il accepté (verrou mutuel) ? */
    @Volatile var isAllowed: ((peer: String) -> Boolean)? = null

    /** Injecté par ServiceLocator — vérifie la signature ed25519 du HELLO (onion ↔ clé). */
    @Volatile var verifier: ((onion: String, canonical: String, sig: String) -> Boolean)? = null

    /** Injecté par ServiceLocator — remise d'une connexion d'appel authentifiée. */
    @Volatile var callSink: ((conn: P2pCallConnection) -> Unit)? = null

    private var serverSocket: ServerSocket? = null

    /**
     * v0.2.45 miroir — LE BACKEND EST-IL VRAIMENT VIVANT ? Une connexion TCP
     * locale est le seul verdict honnête (port publié côté tor mais socket
     * local mort = « general failure » chez tous les pairs).
     */
    fun alive(): Boolean {
        val p = localPort
        if (p <= 0 || !started) return false
        return try {
            Socket().use { s ->
                s.soTimeout = 1_500
                s.connect(java.net.InetSocketAddress("127.0.0.1", p), 1_500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Démarre l'écouteur (idempotent) — thread démon, aucune IOException fatale. */
    @Synchronized
    fun start() {
        if (started) return
        try {
            val server = Loopback.serverSocket(backlog = 16)
            serverSocket = server
            localPort = server.localPort
            started = true
            val t = Thread({
                DesktopLog.i(
                    "nyx-p2p",
                    "écouteur média prêt (local ${Loopback.bindAddress.hostAddress}:$localPort → port onion ${P2pProtocol.MEDIA_ONION_PORT})"
                )
                while (true) {
                    val sock: Socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (started) DesktopLog.w("nyx-p2p", "écouteur interrompu : ${e.message}")
                        selfHealIfStillOwner(server)
                        return@Thread
                    }
                    try {
                        Thread({ handle(sock) }, "nyx-p2p-conn").start()
                    } catch (e: Throwable) {
                        // v0.2.45 miroir — Thread.start() peut lever une Error (OOM)
                        // NON attrapée par « catch Exception » : l'écouteur mourait sans trace.
                        DesktopLog.w(
                            "nyx-p2p", "thread de connexion impossible (${e.javaClass.simpleName}) — " +
                                "connexion rejetée, écouteur maintenu"
                        )
                        runCatching { sock.close() }
                    }
                }
            }, "nyx-p2p-listen")
            t.isDaemon = true
            t.start()
        } catch (e: Exception) {
            DesktopLog.w("nyx-p2p", "démarrage écouteur média impossible : ${e.message}")
        }
    }

    /** v0.2.47 miroir — AUTO-RELANCE GARANTIE PROPRIÉTAIRE (pas de double écouteur). */
    @Synchronized
    private fun selfHealIfStillOwner(dead: ServerSocket) {
        if (!started) return                          // arrêt volontaire en cours
        if (serverSocket !== dead) return             // déjà remplacé — pas à nous
        DesktopLog.w("nyx-p2p", "auto-relance (socket fermé, propriétaire confirmé) — nouveau port")
        started = false
        start()
    }

    /** Arrêt (changement d'identité / fermeture). */
    @Synchronized
    fun stop() {
        started = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        localPort = 0
    }

    /** Traite une connexion entrante — HELLO obligatoire en première trame. */
    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        val first = try { P2pProtocol.readHello(sock.getInputStream()) }
        catch (_: Exception) { P2pProtocol.HelloFirst(false, true, null) }
        val hello = first.hello
        if (hello == null) {
            if (first.mute) {
                DesktopLog.i("nyx-p2p", "connexion muette fermée (sonde de joignabilité — aucun octet reçu)")
            } else {
                DesktopLog.w("nyx-p2p", "connexion sans HELLO valide — fermée")
            }
            runCatching { sock.close() }
            return
        }
        // Authentification : la signature du HELLO doit provenir de la CLÉ
        // dont dépend l'adresse onion revendiquée.
        val expected = P2pProtocol.helloSignedString(
            hello.kind, hello.onion,
            if (hello.kind == "file") hello.count.toString() else hello.cid
        )
        val authOk = verifier?.invoke(hello.onion, expected, hello.sig) == true
        if (!authOk) {
            DesktopLog.w(
                "nyx-p2p",
                "HELLO ${hello.kind} REFUSÉ de ${hello.onion.take(16)}… : signature invalide " +
                    "(clé ≠ onion revendiqué ou trame altérée)"
            )
            if (hello.kind == "call") {
                runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_CALL_REJECT) }
            }
            runCatching { sock.close() }
            return
        }
        if (isAllowed?.invoke(hello.onion) != true) {
            DesktopLog.w(
                "nyx-p2p",
                "HELLO ${hello.kind} REFUSÉ de ${hello.onion.take(16)}… : contact non établi " +
                    "côté destinataire (ni ACCEPTED ni PENDING)"
            )
            if (hello.kind == "call") {
                runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_CALL_REJECT) }
            }
            runCatching { sock.close() }
            return
        }
        if (hello.kind == "call") {
            val sink = callSink
            if (sink == null) {
                runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_CALL_REJECT) }
                runCatching { sock.close() }
                return
            }
            DesktopLog.i(
                "nyx-p2p",
                "appel entrant authentifié de ${hello.onion.take(16)}… (cid ${hello.cid.take(8)}…, codec ${hello.ac.ifEmpty { "aac" }})"
            )
            sink(P2pCallConnection(sock, hello))
        } else {
            // v0.3.6 — file/events : pas implémentés au bureau (chemin journal
            // fonctionnel) — refus propre, le pair Android retombe sur le journal.
            DesktopLog.i(
                "nyx-p2p",
                "HELLO ${hello.kind} de ${hello.onion.take(16)}… — canal non implémenté au bureau, " +
                    "fermé proprement (le pair retombe sur le journal)"
            )
            runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_ACK, intToBytes(-1)) }
            runCatching { sock.close() }
        }
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
        )
}
