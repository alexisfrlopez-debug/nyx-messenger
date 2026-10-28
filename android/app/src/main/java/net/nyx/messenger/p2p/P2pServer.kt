package net.nyx.messenger.p2p

import net.nyx.messenger.net.Loopback
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

    /** Socket serveur courant (v0.2.45 — sonde de vie + relance). */
    private var serverSocket: ServerSocket? = null

    /**
     * v0.2.45 — LE BACKEND EST-IL VRAIMENT VIVANT ? Même cause racine que
     * [net.nyx.messenger.replica.EmbeddedReplica.alive] (terrain 23/10 +
     * rejeu sandbox) : le port média 47471 restait publié côté tor alors que
     * le socket local refusait les connexions (« errno 115 misc », appels et
     * auto-sondes en « general failure »). Une connexion TCP locale est le
     * seul verdict honnête — l'auto-réparation sur accept() en exception ne
     * couvre PAS la mort silencieuse du socket.
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

    /**
     * v0.2.45 — RELANCE SI MORT : ferme le zombie, rebinde un port NEUF.
     * Renvoie le nouveau port local (0 si échec) — l'appelant (TorManager)
     * republie l'onion car le mapping 47471 → port local a CHANGÉ.
     */
    @Synchronized
    fun restartIfDead(): Int {
        if (alive()) return localPort
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-p2p",
            "écouteur direct MORT (port $localPort refuse les connexions locales) — relance"
        )
        runCatching { serverSocket?.close() }
        serverSocket = null
        started = false
        localPort = 0
        start()
        return localPort
    }

    /** Démarre l'écouteur (idempotent) — thread démon, aucune IOException fatale. */
    @Synchronized
    fun start() {
        if (started) return
        try {
            // v0.2.47 — BIND IPv4 EXPLICITE (cause racine sessions 41→68, preuve
            // AOSP : voir net/Loopback.kt) : getLoopbackAddress() = ::1 sur Android
            // alors que l'ADD_ONION cible 127.0.0.1 → média P2P DIRECT n'a JAMAIS
            // été joignable (« décrochage impossible » — le média exigeait l'onion
            // entrant du pair, dont le backend refusait toute connexion).
            val server = Loopback.serverSocket(backlog = 16)
            serverSocket = server
            localPort = server.localPort
            started = true
            val t = Thread({
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-p2p",
                    "écouteur direct prêt (local ${Loopback.bindAddress.hostAddress}:$localPort)"
                )
                while (true) {
                    val sock: Socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        net.nyx.messenger.diag.DiagLog.w(
                            "nyx-p2p", "écouteur interrompu : ${e.message}"
                        )
                        // v0.2.47 — AUTO-RELANCE SEULEMENT SI ON EST ENCORE
                        // PROPRIÉTAIRE du socket publié : le close() du chien de
                        // garde (restartIfDead) réveille AUSSI ce thread — relancer
                        // alors créait un DOUBLE écouteur (terrain 24/10 05:22:38 :
                        // deux « écouteur direct prêt » à 1 ms d'écart, ports
                        // 42899/44763, socket orphelin + churn de descripteur).
                        selfHealIfStillOwner(server)
                        return@Thread
                    }
                    try {
                        Thread({ handle(sock) }, "nyx-p2p-conn").start()
                    } catch (e: Throwable) {
                        // v0.2.45 — Thread.start() peut lever une Error (OOM)
                        // NON attrapée par « catch Exception » : l'écouteur
                        // mourait sans trace, port publié mais mort côté tor.
                        net.nyx.messenger.diag.DiagLog.w(
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
            net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "démarrage écouteur impossible : ${e.message}")
        }
    }

    /**
     * v0.2.47 — AUTO-RELANCE GARANTIE PROPRIÉTAIRE : appelée par le thread
     * d'écoute dont l'accept() vient de lever. Ne relance QUE si le socket mort
     * est TOUJOURS celui publié dans [serverSocket] (sinon le chien de garde —
     * ou un arrêt — a déjà remplacé l'écouteur : ne rien toucher). Sérialisée
     * avec start/restartIfDead (@Synchronized) pour lever toute course.
     */
    @Synchronized
    private fun selfHealIfStillOwner(dead: ServerSocket) {
        if (!started) return                          // arrêt volontaire en cours
        if (serverSocket !== dead) return             // déjà remplacé — pas à nous
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-p2p", "auto-relance (socket fermé, propriétaire confirmé) — nouveau port"
        )
        started = false
        start()
    }

    /** Traite une connexion entrante — HELLO obligatoire en première trame. */
    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        // v0.2.48 — lecture enrichie : distinguons la connexion MUETTE (sonde
        // de joignabilité : l'auto-sonde média locale et les sondes console
        // ouvrent TCP sans envoyer RIEN — terrain 24/10 : une par 20-40 s par
        // export) de la vraie trame invalide, pour ne plus noyer les logs.
        val first = try { P2pProtocol.readHello(sock.getInputStream()) }
        catch (_: Exception) { P2pProtocol.HelloFirst(false, true, null) }
        val hello = first.hello
        if (hello == null) {
            if (first.mute) {
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-p2p", "connexion muette fermée (sonde de joignabilité — aucun octet reçu)"
                )
            } else {
                net.nyx.messenger.diag.DiagLog.w("nyx-p2p", "connexion sans HELLO valide — fermée")
            }
            runCatching { sock.close() }
            return
        }
        // Authentification : la signature du HELLO doit provenir de la CLÉ
        // dont dépend l'adresse onion revendiquée.
        // v0.2.59 — SÉLECTEUR CORRIGÉ pour le kind « events » : l'émetteur
        // [P2pClient.pushEvents] signe le COUNT (nombre d'événements), mais le
        // vérificateur utilisait le CID (vide) → « signature invalide » pour
        // TOUT dépôt direct P2P d'événements (terrain 28/10 17:24:03 :
        // « HELLO events REFUSÉ de kxcwah… : signature invalide » — le repli
        // ADR-017 était structurellement mort). cid pour un appel, count
        // pour file ET events.
        val verifier = P2pVerifier.get
        val expected = P2pProtocol.helloVerifierString(hello)
        // v0.2.48 — LA CAUSE DU REFUS EST JOURNALISÉE (terrain 24/10 08:49 : « HELLO
        // events REFUSÉ de kxcwah… » sans raison — signature ou contact ? le
        // terrain ne pouvait pas trancher) : deux logs distincts, le prochain
        // export est auto-diagnostiquant.
        val authOk = verifier != null && verifier(hello.onion, expected, hello.sig)
        if (!authOk) {
            net.nyx.messenger.diag.DiagLog.w(
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
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-p2p",
                "HELLO ${hello.kind} REFUSÉ de ${hello.onion.take(16)}… : contact non établi " +
                    "côté destinataire (ni ACCEPTED ni PENDING — voir EmbeddedReplica)"
            )
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
