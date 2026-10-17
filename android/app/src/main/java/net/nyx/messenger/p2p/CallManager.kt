package net.nyx.messenger.p2p

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.nyx.messenger.repo.NyxRepository

/**
 * v0.2.18 — GESTIONNAIRE D'APPELS (ADR-015 : audio direct onion-to-onion).
 *
 * Machine à états (StateFlow — l'écran d'appel s'y abonne où qu'il soit) :
 *
 *   IDLE ──call()──▶ OUTGOING ──accept du pair──▶ ACTIVE ──hangup()──▶ ENDED→IDLE
 *                      │  ▲
 *   IDLE ◀──reject──┘  └──timeout 45 s (« pas de réponse »)
 *   IDLE ──offer reçu──▶ INCOMING ──accept()──▶ WAIT ──HELLO P2P──▶ ACTIVE
 *                          │reject() / bye reçu / offre périmée
 *                          ▼
 *                        ENDED (« refusé » / « appel manqué »)
 *
 * SIGNALISATION (journal, ≤ 15 s de latence) : offer/reject/bye — type « msg »
 * kind=call chiffré E2E, zéro changement serveur. MÉDIA (temps réel) :
 * connexion directe onion-to-onion port 47471 — l'appelant ouvre (HELLO signé
 * + csd-0 AAC), l'appelé répond F_CALL_ACCEPT (son csd-0) ou F_CALL_REJECT.
 * Verrou contact mutuel identique aux messages ; un seul appel à la fois.
 */
object CallManager {

    enum class Phase { IDLE, OUTGOING, INCOMING, WAIT, ACTIVE, ENDED }

    data class CallUi(
        val phase: Phase = Phase.IDLE,
        val peer: String = "",
        val alias: String = "",
        val cid: String = "",
        val startedAt: Long = 0L,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val message: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e ->
            android.util.Log.w("nyx-call", "coroutine appel terminée : ${e.message}")
        })

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    /** Injecté par ServiceLocator. */
    @Volatile private var repo: NyxRepository? = null
    @Volatile private var appContext: android.content.Context? = null

    /** Connexion média en cours (appelant OU appelé après remise par P2pServer). */
    private var conn: P2pCallConnection? = null
    private var audio: CallAudio? = null
    private var myCsd: ByteArray = ByteArray(0)

    /** Offre entrante en attente (signalisation arrivée avant le média). */
    private var pendingOffer: CallSignal? = null
    private var acceptedCid: String? = null

    fun init(repoRef: NyxRepository, context: android.content.Context) {
        repo = repoRef
        appContext = context.applicationContext
        repoRef.callSignalHandler = { author, signal ->
            runBlocking { onSignal(author, signal) }
        }
    }

    /* ----------------------------- APPEL SORTANT ----------------------------- */

    /** Appelle un contact accepté — signalisation journal puis média direct. */
    fun call(peer: String, alias: String) {
        val r = repo ?: return
        if (_ui.value.phase != Phase.IDLE) return
        val cid = CallSignal.newCid()
        _ui.value = CallUi(Phase.OUTGOING, peer, alias, cid,
            message = "sonnerie…")
        scope.launch {
            // 1. Sonnerie chez le pair (journal chiffré — ≤ 15 s de poll).
            val offered = r.sendCallSignal(
                peer, CallSignal(CallSignal.OP_OFFER, cid, r.myAlias, nowS()).encode()
            )
            if (offered.isFailure) {
                end("réplica injoignable — appel impossible")
                return@launch
            }
            // 2. Connexion média directe (tor peut mettre 10-30 s).
            val socket = runCatching { P2pClient.connect(peer) }.getOrElse { e ->
                android.util.Log.i("nyx-call", "connexion directe impossible : ${e.message}")
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("pas de réponse"); return@launch
            }
            val c = P2pCallConnection(socket, P2pProtocol.Hello("call", P2pClient.me ?: "", alias, cid, "", 0, ""))
            conn = c
            // csd-0 AVANT le HELLO (l'appelé en a besoin pour décoder).
            val audioEngine = CallAudio(c.input, { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
            audio = audioEngine
            myCsd = runCatching { audioEngine.startSending() }.getOrElse { e ->
                end("micro indisponible : ${e.message}"); return@launch
            }
            val me = P2pClient.me ?: ""
            val csdB64 = java.util.Base64.getEncoder().encodeToString(myCsd)
            val sig = P2pClient.signer?.invoke(
                P2pProtocol.helloSignedString("call", me, cid)
            ) ?: ""
            c.send(P2pProtocol.F_HELLO, P2pProtocol.encodeHello(
                P2pProtocol.Hello("call", me, alias, cid, csdB64, 0, sig), sig
            ))
            // 3. Attendre la réponse (45 s — l'appelé sonne pendant ce temps).
            runCatching { socket.soTimeout = 45_000 }
            val answer = runCatching { P2pProtocol.read(c.input) }.getOrNull()
            when (answer?.type) {
                P2pProtocol.F_CALL_ACCEPT -> {
                    val peerCsd = runCatching {
                        java.util.Base64.getDecoder().decode(String(answer.payload, Charsets.UTF_8))
                    }.getOrElse { ByteArray(0) }
                    runCatching { audioEngine.startReceiving(peerCsd) }
                    audioEngine.transmit = true // acceptation explicite → l'audio part
                    _ui.value = _ui.value.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "")
                    android.util.Log.i("nyx-call", "appel actif avec ${peer.take(16)}… (cid ${cid.take(8)}…)")
                }
                P2pProtocol.F_CALL_REJECT -> end("refusé")
                else -> {
                    // EOF / timeout — le pair n'a pas répondu.
                    runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                    end(if (_ui.value.phase == Phase.OUTGOING) "pas de réponse" else _ui.value.message)
                }
            }
        }
    }

    /* ----------------------------- APPEL ENTRANT ----------------------------- */

    /** Signalisation journal (offer/reject/bye) — appelé par le poll inbox. */
    suspend fun onSignal(author: String, signal: CallSignal) {
        val r = repo ?: return
        val now = nowS()
        when (signal.op) {
            CallSignal.OP_OFFER -> {
                // Anti-rejeu souple : une offre trop vieille est un appel manqué.
                if (signal.ts in 1 until now - CallSignal.OFFER_TTL_S) {
                    r.addCallInfo(author, false, "Appel manqué")
                    return
                }
                if (_ui.value.phase != Phase.IDLE) {
                    android.util.Log.i("nyx-call", "offre ignorée (déjà en appel) de ${author.take(16)}…")
                    return
                }
                pendingOffer = signal
                val label = signal.alias.ifBlank { author.take(10) + "…" }
                _ui.value = CallUi(Phase.INCOMING, author, label, signal.cid, message = "appel entrant…")
                net.nyx.messenger.notify.ContactNotifier.incomingCall(author, label)
                // Garde-fou : média jamais arrivé (pair en échec après l'offre)
                // → on ne laisse PAS la sonnerie à l'infini.
                val offeredCid = signal.cid
                scope.launch {
                    delay(90_000)
                    if (_ui.value.phase == Phase.INCOMING && _ui.value.cid == offeredCid) {
                        r.addCallInfo(author, false, "Appel manqué")
                        end("appel manqué")
                    }
                }
            }
            CallSignal.OP_REJECT -> {
                if (_ui.value.phase == Phase.OUTGOING && _ui.value.peer == author) {
                    end("refusé")
                }
            }
            CallSignal.OP_BYE -> {
                when (_ui.value.phase) {
                    Phase.INCOMING -> if (_ui.value.peer == author) {
                        pendingOffer = null
                        r.addCallInfo(author, false, "Appel manqué")
                        end("appel manqué")
                    }
                    Phase.ACTIVE, Phase.WAIT, Phase.OUTGOING -> if (_ui.value.peer == author) end("terminé")
                    else -> {}
                }
            }
        }
    }

    /** Accepte un appel entrant — la connexion média peut encore arriver. */
    fun acceptIncoming() {
        val state = _ui.value
        if (state.phase != Phase.INCOMING) return
        acceptedCid = state.cid
        _ui.value = state.copy(phase = Phase.WAIT, message = "connexion…")
        // Si la connexion P2P est déjà là (HELLO reçu avant l'appui) —
        // [onIncomingConnection] l'a gardée en attente : finalise maintenant.
        tryFinalizeWaitedConnection()
    }

    /** Refuse un appel entrant — trace chez l'appelant (journal) + socket. */
    fun rejectIncoming() {
        val state = _ui.value
        if (state.phase != Phase.INCOMING && state.phase != Phase.WAIT) return
        val r = repo
        scope.launch {
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_REJECT, state.cid, "", nowS()).encode())
            }
        }
        conn?.send(P2pProtocol.F_CALL_REJECT)
        pendingOffer = null
        acceptedCid = null
        end("refusé")
    }

    /**
     * Remise de connexion par [P2pServer] (côté appelé) : si l'utilisateur a
     * déjà accepté → finalise immédiatement ; sinon garde la connexion en
     * attente (sonnerie en cours) et répondra à l'acceptation.
     */
    fun onIncomingConnection(connection: P2pCallConnection) {
        scope.launch {
            synchronized(this@CallManager) {
                val state = _ui.value
                val wanted = state.phase == Phase.INCOMING && state.cid == connection.hello.cid
                val alreadyAccepted = state.phase == Phase.WAIT && acceptedCid == connection.hello.cid
                if (!wanted && !alreadyAccepted) {
                    // Personne n'attend cet appel (offre expirée, occupé…) : refus net.
                    connection.send(P2pProtocol.F_CALL_REJECT)
                    connection.close()
                    return@launch
                }
                conn = connection
                if (state.phase == Phase.INCOMING) {
                    // L'appui « accepter » viendra : la connexion patiente.
                    android.util.Log.i("nyx-call", "média arrivé pendant la sonnerie — en attente d'acceptation")
                    return@launch
                }
            }
            finalizeIncoming()
        }
    }

    private fun tryFinalizeWaitedConnection() {
        val c = conn ?: return
        if (_ui.value.phase == Phase.WAIT && acceptedCid == c.hello.cid) {
            scope.launch { finalizeIncoming() }
        }
    }

    /** Côté appelé : démarre l'audio et répond F_CALL_ACCEPT au pair. */
    private fun finalizeIncoming() {
        val c = conn ?: return
        val peerCsd = runCatching {
            java.util.Base64.getDecoder().decode(c.hello.csd)
        }.getOrElse { ByteArray(0) }
        val engine = CallAudio(c.input, { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
        audio = engine
        val myCsdB64 = runCatching {
            java.util.Base64.getEncoder().encodeToString(engine.startSending())
        }.getOrElse {
            c.send(P2pProtocol.F_CALL_REJECT)
            end("micro indisponible"); return
        }
        c.send(P2pProtocol.F_CALL_ACCEPT, myCsdB64.toByteArray(Charsets.UTF_8))
        runCatching { engine.startReceiving(peerCsd) }
        engine.transmit = true // l'appelé a accepté explicitement
        _ui.value = _ui.value.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "")
        android.util.Log.i("nyx-call", "appel actif avec ${_ui.value.peer.take(16)}… (côté appelé)")
    }

    /* ------------------------------- COMMUN ------------------------------- */

    fun toggleMute() {
        val state = _ui.value
        val m = !state.muted
        audio?.muted = m
        _ui.value = state.copy(muted = m)
    }

    fun toggleSpeaker() {
        val state = _ui.value
        val s = !state.speaker
        audio?.setSpeaker(appContext ?: return, s)
        _ui.value = state.copy(speaker = s)
    }

    /** Raccroche (ou annule une sonnerie sortante). */
    fun hangup(reason: String = "terminé") {
        val state = _ui.value
        if (state.phase == Phase.IDLE) return
        val r = repo
        scope.launch {
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_BYE, state.cid, "", nowS()).encode())
            }
        }
        if (state.phase == Phase.INCOMING) {
            // Annulation d'une sonnerie entrante côté appelé : bye déjà envoyé.
        }
        conn?.send(P2pProtocol.F_BYE)
        end(reason)
    }

    /** Termine et nettoie ; l'écran affiche [reason] 3 s puis revient à IDLE. */
    private fun end(reason: String) {
        runCatching { audio?.stop() }
        runCatching { conn?.close() }
        audio = null
        conn = null
        pendingOffer = null
        acceptedCid = null
        val wasActive = _ui.value.phase == Phase.ACTIVE
        _ui.value = _ui.value.copy(phase = Phase.ENDED, message = reason, muted = false, speaker = false)
        scope.launch {
            delay(if (wasActive) 2500L else 3000L)
            if (_ui.value.phase == Phase.ENDED) _ui.value = CallUi()
        }
    }

    private fun nowS(): Long = System.currentTimeMillis() / 1000
}
