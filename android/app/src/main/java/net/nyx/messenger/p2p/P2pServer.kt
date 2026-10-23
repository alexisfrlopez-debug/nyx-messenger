package net.nyx.messenger.p2p

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * v0.2.18 — SERVEUR du canal direct P2P : écouteur local (loopback) recevant
 * les connexions tor entrantes du 2ᵉ port onion (47471). Chaque connexion est
 * authentifiée au HELLO (signature ed25519 de la clé d'identité + contact
 * accepté) puis routée :
 *  - « file » → les fragments passent un à un dans [fileSink] (MÊME porte que
 *    le chemin journal — [net.nyx.messenger.repo.NyxRepository.receiveDirectFragment]),
 *    ACK récapitulatif renvoyé à la fin ;
 *  - « call » → la connexion brute est remise à [callSink] ([CallManager]
 *    prend la main : sonnerie déjà en cours via la signalisation journal).
 *
 * L'écouteur démarre dès l'initialisation (identité présente) — AVANT la
 * publication onion, pour que le port média soit servi dès l'annonce.
 */
object P2pServer {

    @Volatile var localPort: Int = 0
        private set

    @Volatile private var started = false

    /** Injecté par ServiceLocator — le contact est-il accepté (verrou mutuel) ? */
    @Volatile var isAllowed: ((peer: String) -> Boolean)? = null

    /** Injecté par ServiceLocator — ingestion d'un fragment direct (renvoie true = accepté). */
    @Volatile var fileSink: (suspend (author: String, bodyB64: String) -> Boolean)? = null

    /**
     * v0.2.41 — Injecté par ServiceLocator — ingestion d'un LOT d'événements
     * directs (ADR-017). Renvoie le nombre d'événements ACCEPTÉS (0 = tout
     * rejeté — l'expéditeur retombera sur le journal).
     */
    @Volatile var eventSink: (suspend (author: String, events: List<net.nyx.messenger.net.WireEvent>) -> Int)? = null

    /** Injecté par ServiceLocator — remise d'une connexion d'appel authentifiée. */
    @Volatile var callSink: ((conn: P2pCallConnection) -> Unit)? = null

    /** Démarre l'écouteur (idempotent) — thread démon, aucune IOException fatale. */
    @Synchronized
    fun start() {
        if (started) return
        try {
            val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
            localPort = server.localPort
            started = true
            val t = Thread({
                net.nyx.messenger.diag.DiagLog.i("nyx-p2p", "écouteur direct prêt (local $localPort)")
                while (true) {
                    val sock: Socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (started) {
                            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "écouteur interrompu : ${e.message}")
                            // Recrée l'écouteur (le port peut avoir changé) —
                            // la republication onion suivra au prochain Ready.
                            started = false
                            start()
                        }
                        return@Thread
                    }
                    Thread({ handle(sock) }, "nyx-p2p-conn").start()
                }
            }, "nyx-p2p-listen")
            t.isDaemon = true
            t.start()
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "démarrage écouteur impossible : ${e.message}")
        }
    }

    /** Traite une connexion entrante — HELLO obligatoire en première trame. */
    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        val helloFrame = try { P2pProtocol.read(sock.getInputStream()) } catch (_: Exception) { null }
        val hello = helloFrame?.takeIf { it.type == P2pProtocol.F_HELLO }
            ?.let { P2pProtocol.parseHello(it.payload) }
        if (hello == null) {
            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "connexion sans HELLO valide — fermée")
            runCatching { sock.close() }
            return
        }
        // Authentification : la signature du HELLO doit provenir de la CLÉ
        // dont dépend l'adresse onion revendiquée.
        val verifier = P2pVerifier.get
        val expected = P2pProtocol.helloSignedString(
            hello.kind, hello.onion,
            if (hello.kind == "file") hello.count.toString() else hello.cid
        )
        val authOk = verifier != null && verifier(hello.onion, expected, hello.sig)
        if (!authOk || isAllowed?.invoke(hello.onion) != true) {
            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "HELLO ${hello.kind} REFUSÉ de ${hello.onion.take(16)}…")
            if (hello.kind == "call") {
                runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_CALL_REJECT) }
            }
            runCatching { sock.close() }
            return
        }
        if (hello.kind == "file") {
            Thread({ serveFile(sock, hello) }, "nyx-p2p-file").start()
        } else if (hello.kind == "events") {
            // v0.2.41 — dépôt direct d'événements (transport de replan ADR-017).
            Thread({ serveEvents(sock, hello) }, "nyx-p2p-events").start()
        } else {
            val sink = callSink
            if (sink == null) {
                runCatching { P2pProtocol.write(sock.getOutputStream(), P2pProtocol.F_CALL_REJECT) }
                runCatching { sock.close() }
                return
            }
            net.nyx.messenger.diag.DiagLog.i("nyx-p2p", "appel entrant authentifié de ${hello.onion.take(16)}… (cid ${hello.cid.take(8)}…)")
            sink(P2pCallConnection(sock, hello))
        }
    }

    /** Fragments → fileSink (séquentiel, ordre du dépôt), puis ACK final. */
    private fun serveFile(sock: Socket, hello: P2pProtocol.Hello) {
        var received = 0
        val input = sock.getInputStream()
        val out = sock.getOutputStream()
        try {
            val sink = fileSink
                ?: run { P2pProtocol.write(out, P2pProtocol.F_ACK, intToBytes(-1)); return }
            while (received < hello.count) {
                val frame = P2pProtocol.read(input) ?: break
                if (frame.type != P2pProtocol.F_FILE) break
                val body = String(frame.payload, Charsets.UTF_8)
                val ok = kotlinx.coroutines.runBlocking { sink(hello.onion, body) }
                if (!ok) {
                    net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "fragment direct ${received + 1}/${hello.count} refusé — arrêt")
                    break
                }
                received++
            }
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "dépôt direct interrompu à $received/${hello.count} : ${e.message}")
        } finally {
            runCatching { P2pProtocol.write(out, P2pProtocol.F_ACK, intToBytes(received)) }
            runCatching { sock.close() }
            net.nyx.messenger.diag.DiagLog.i("nyx-p2p", "dépôt direct terminé : $received/${hello.count} fragments ingérés")
        }
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
        )

    /**
     * v0.2.41 — LOT d'événements signés (ADR-017) : chaque trame F_EVENT est
     * un JSON WireEvent IDENTIQUE au filaire journal. Tout le lot part au
     * [eventSink] (la porte commune re-vérifie CHAQUE signature, le
     * destinataire, les verrous de contact et la dédup) puis ACK du nombre
     * accepté. Un pair v0.2.18-0.2.40 n'émet jamais ce HELLO ; un serveur
     * v0.2.18-0.2.40 le REFUSE (parseHello inconnu) — compat totale.
     */
    private fun serveEvents(sock: Socket, hello: P2pProtocol.Hello) {
        var accepted = 0
        val input = sock.getInputStream()
        val out = sock.getOutputStream()
        try {
            val sink = eventSink
                ?: run { P2pProtocol.write(out, P2pProtocol.F_ACK, intToBytes(0)); return }
            val events = ArrayList<net.nyx.messenger.net.WireEvent>(hello.count.coerceAtMost(256))
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            while (events.size < hello.count) {
                val frame = P2pProtocol.read(input) ?: break
                if (frame.type != P2pProtocol.F_EVENT) break
                val ev = runCatching {
                    json.decodeFromString(
                        net.nyx.messenger.net.WireEvent.serializer(),
                        String(frame.payload, Charsets.UTF_8)
                    )
                }.getOrNull() ?: break
                events.add(ev)
            }
            accepted = kotlinx.coroutines.runBlocking { sink(hello.onion, events) }
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-p2p", "dépôt direct événements : $accepted/${hello.count} ingérés"
            )
        } catch (e: Exception) {
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-p2p", "dépôt direct d'événements interrompu ($accepted/${hello.count}) : ${e.message}"
            )
        } finally {
            runCatching { P2pProtocol.write(out, P2pProtocol.F_ACK, intToBytes(accepted)) }
            runCatching { sock.close() }
        }
    }
}

/**
 * Pont d'authentification (injecté par ServiceLocator) : vérifie la signature
 * ed25519 du HELLO avec la clé publique dérivée de l'onion revendiqué.
 * [canonical] = chaîne signée reconstruite ; [sig] = signature b64 du HELLO.
 */
object P2pVerifier {
    @Volatile var get: ((onion: String, canonical: String, sig: String) -> Boolean)? = null
}
