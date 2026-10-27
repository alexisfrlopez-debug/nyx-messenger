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
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.repo.DesktopRepository

/**
 * v0.3.6 — GESTIONNAIRE D'APPELS DESKTOP (portage de CallManager Android
 * v0.2.18-0.2.52, ADR-015 : audio direct onion-to-onion — AUDIO SEUL au
 * bureau : la visio H.264 MediaCodec Android n'a pas d'équivalent JVM, les
 * appels PC↔téléphone sont audio, téléphone↔téléphone gardent la visio).
 *
 * Machine à états (StateFlow — l'overlay d'appel s'y abonne où qu'il soit) :
 *
 *   IDLE ──call()──▶ OUTGOING ──accept du pair──▶ ACTIVE ──hangup()──▶ ENDED→IDLE
 *                      │  ▲
 *   IDLE ◀──reject──┘  └──timeout (« pas de réponse »)
 *   IDLE ──offer reçu──▶ INCOMING ──accept()──▶ WAIT ──ACCEPT envoyé──▶ ACTIVE
 *                          │reject() / bye reçu / offre périmée
 *                          ▼
 *                        ENDED (« refusé » / « appel manqué »)
 *
 * SIGNALISATION (journal, ≤ 30 s de latence poll) : offer/reject/bye — type
 * « msg » kind=call chiffré E2E, zéro changement serveur. MÉDIA (temps réel) :
 * connexion directe onion-to-onion port 47471 — l'appelant ouvre (HELLO signé
 * + csd), l'appelé répond F_CALL_ACCEPT (son csd + codec choisi) ou
 * F_CALL_REJECT. Verrou contact mutuel identique aux messages ; un seul appel
 * à la fois.
 *
 * CODEC v0.3.6 (négociation) : le bureau ne fait que l'ADPCM (pur Java —
 * l'AAC MediaCodec Android n'existe pas sur JVM). HELLO.ac = codecs SUPPORTÉS
 * par l'appelant (liste « aac,adpcm » côté téléphone ≥ v0.2.54 ; « adpcm »
 * côté bureau ; absent = AAC seul ≤ v0.2.53) ; ACCEPT.ac = codec CHOISI par
 * l'appelé. Téléphone↔téléphone : AAC (inchangé, l'appelé préfère) ;
 * PC↔téléphone ≥ v0.2.54 : ADPCM dans les DEUX sens ; ancien téléphone :
 * refus propre avec message de mise à jour.
 *
 * Anti-état collant (v0.2.26 miroir) : watchdog global par phase + garde-fou
 * WAIT + try/catch sur tout le cycle (une exception tuait la coroutine SANS
 * end() → phase figée).
 */
object DesktopCallManager {

    enum class Phase { IDLE, OUTGOING, INCOMING, WAIT, ACTIVE, ENDED }

    data class CallUi(
        val phase: Phase = Phase.IDLE,
        val peer: String = "",
        val alias: String = "",
        val cid: String = "",
        val startedAt: Long = 0L,
        val muted: Boolean = false,
        val message: String = "",
        val phaseChangedAt: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e ->
            DesktopLog.w("nyx-call", "coroutine appel terminée : ${e.message}")
        })

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    /** Injecté par ServiceLocator. */
    @Volatile private var repo: DesktopRepository? = null

    /** Connexion média en cours (appelant OU appelé après remise par P2pServer). */
    private var conn: P2pCallConnection? = null
    private var audio: DesktopCallAudio? = null
    private var myCsd: ByteArray = ByteArray(0)

    /** Offre entrante en attente (signalisation arrivée avant le média). */
    private var pendingOffer: CallSignal? = null
    private var acceptedCid: String? = null

    fun init(repoRef: DesktopRepository) {
        repo = repoRef
        repoRef.callSignalHandler = { author, signal ->
            runBlocking { onSignal(author, signal) }
        }
        watchdogGlobal()
    }

    /** v0.2.42 miroir — filet de sécurité GLOBAL : cap PAR PHASE (transitoires 4 min, ACTIVE 2 h). */
    private fun phaseCapMs(phase: Phase): Long =
        if (phase == Phase.ACTIVE) ACTIVE_CALL_CAP_MS else GLOBAL_CALL_CAP_MS

    private fun watchdogGlobal() {
        scope.launch {
            while (true) {
                delay(15_000)
                val st = _ui.value
                if (st.phase != Phase.IDLE && st.phase != Phase.ENDED &&
                    st.phaseChangedAt > 0 &&
                    nowMs() - st.phaseChangedAt > phaseCapMs(st.phase)
                ) {
                    DesktopLog.w(
                        "nyx-call",
                        "garde-fou global : ${st.phase} depuis > ${phaseCapMs(st.phase) / 1000} s — fin forcée"
                    )
                    end("appel interrompu (délai)")
                }
            }
        }
    }

    /* ----------------------------- APPEL SORTANT ----------------------------- */

    /** Appelle un contact accepté — signalisation journal puis média direct. */
    fun call(peer: String, alias: String) {
        val r = repo ?: return
        if (_ui.value.phase != Phase.IDLE) return
        val cid = CallSignal.newCid()
        _ui.value = CallUi(Phase.OUTGOING, peer, alias, cid,
            message = "sonnerie…", phaseChangedAt = nowMs())
        scope.launch {
            // v0.2.26 miroir : TOUTE exception dans le cycle sortant finit par
            // end() — une IOException qui tuait la coroutine sans nettoyage
            // laissait la phase OUTGOING collée à jamais.
            try {
                runOutgoing(r, peer, alias, cid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                DesktopLog.w("nyx-call", "appel sortant interrompu : ${e::class.simpleName} ${e.message}")
                end("erreur : ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Corps du cycle sortant. */
    private suspend fun runOutgoing(r: DesktopRepository, peer: String, alias: String, cid: String) {
        run {
            // 1. Sonnerie chez le pair (journal chiffré — ≤ 30 s de poll).
            //    L'offre porte le codec du bureau (informationnel — le choix
            //    réel se fait au HELLO/ACCEPT).
            val offered = r.sendCallSignal(
                peer, CallSignal(CallSignal.OP_OFFER, cid, r.myAlias(), nowS(), "", P2pProtocol.AC_ADPCM).encode()
            )
            if (offered.isFailure) {
                end("réplica injoignable — appel impossible")
                return@run
            }
            // 2. Connexion média directe — FENÊTRE DE TENTATIVES (v0.2.18/0.2.27
            //    miroir : le pair ne connaît l'offre qu'après son poll (~30 s)
            //    et un circuit onion entrant peut prendre 10-30 s — un seul
            //    essai de 40 s partait AVANT la sonnerie). Essais de 12 s toutes
            //    les 3 s pendant 150 s ; chaque connect embarque la récupération
            //    HSFETCH (descripteur périmé du pair — v0.2.37 miroir).
            var socket: java.net.Socket? = null
            val deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS
            var attempt = 0
            while (socket == null && System.currentTimeMillis() < deadline) {
                if (_ui.value.phase != Phase.OUTGOING || _ui.value.cid != cid) return@run
                attempt++
                // v0.2.48 miroir — PREMIER essai élargi (rendezvous onion À FROID 15-35 s).
                val attemptMs = if (attempt == 1) FIRST_ATTEMPT_MS else CONNECT_ATTEMPT_MS
                DesktopLog.i(
                    "nyx-call",
                    "média essai $attempt (${attemptMs / 1000} s) → ${peer.take(16)}…:${P2pProtocol.MEDIA_ONION_PORT}"
                )
                socket = runCatching { DesktopP2pClient.connect(peer, attemptMs) }.getOrElse { e ->
                    DesktopLog.i(
                        "nyx-call",
                        "connexion directe impossible vers ${peer.take(16)}… (${e.message}) — nouvelle tentative"
                    )
                    null
                }
                if (socket == null) delay(3_000)
            }
            if (socket == null) {
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("pas de réponse"); return@run
            }
            val c = P2pCallConnection(socket, P2pProtocol.Hello("call", DesktopP2pClient.me ?: "", alias, cid, "", 0, ""))
            conn = c
            // csd-0 AVANT le HELLO (l'appelé en a besoin pour son décodeur —
            // calculé : AAC-LC 16 kHz mono, VALIDE même pour un pair ancien).
            val audioEngine = DesktopCallAudio(c.input, { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
            audio = audioEngine
            myCsd = runCatching { audioEngine.startSending() }.getOrElse { e ->
                end("micro indisponible : ${e.message}"); return@run
            }
            val me = DesktopP2pClient.me
            if (me == null) {
                end("identité indisponible"); return@run
            }
            val csdB64 = java.util.Base64.getEncoder().encodeToString(myCsd)
            val sig = DesktopP2pClient.signer?.invoke(
                P2pProtocol.helloSignedString("call", me, cid)
            ) ?: ""
            // v0.2.26 miroir : le HELLO non remis (pair raccroché entre-temps)
            // ne tue plus la coroutine sans nettoyage — BYE + fin propre.
            runCatching {
                c.send(P2pProtocol.F_HELLO, P2pProtocol.encodeHello(
                    // ac="adpcm" : le bureau ne propose QUE l'ADPCM — un appelé
                    // Android ≥ v0.2.54 y branche son codec logiciel miroir.
                    P2pProtocol.Hello("call", me, alias, cid, csdB64, 0, sig, ac = P2pProtocol.AC_ADPCM), sig
                ))
            }.getOrElse { e ->
                DesktopLog.w("nyx-call", "HELLO non remis : ${e.message}")
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("connexion perdue"); return@run
            }
            // 3. Attendre la réponse (60 s — l'appelé sonne et accepte pendant
            //    ce temps ; la fenêtre totale reste sous le watchdog global).
            runCatching { socket.soTimeout = 60_000 }
            val answer = runCatching { P2pProtocol.read(c.input) }.getOrNull()
            when (answer?.type) {
                P2pProtocol.F_CALL_ACCEPT -> {
                    val accept = P2pProtocol.AcceptPayload.parse(answer.payload)
                    if (accept.ac != P2pProtocol.AC_ADPCM) {
                        // L'appelé a choisi l'AAC (téléphone ≤ v0.2.53 ou
                        // téléphone↔téléphone) — le bureau ne décode PAS l'AAC :
                        // refus propre + indice de mise à jour (auto-update canal test).
                        DesktopLog.w(
                            "nyx-call",
                            "ACCEPT codec « ${accept.ac.ifEmpty { "aac" }} } » — le bureau ne fait que l'ADPCM " +
                                "(téléphone ≤ v0.2.53 ?) : appel terminé, mise à jour de l'appli téléphone requise"
                        )
                        runCatching { c.send(P2pProtocol.F_BYE) }
                        runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                        end("l'appli du téléphone doit être mise à jour (v0.2.54+) pour recevoir les appels du PC")
                        return@run
                    }
                    audioEngine.startReceiving(P2pProtocol.AC_ADPCM)
                    audioEngine.transmit = true // acceptation explicite → l'audio part
                    _ui.value = _ui.value.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "", phaseChangedAt = nowMs())
                    DesktopLog.i("nyx-call", "appel actif avec ${peer.take(16)}… (cid ${cid.take(8)}…, codec adpcm)")
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
        // v0.2.29 miroir — DÉFENSE EN PROFONDEUR (blocage local) : silence total
        // pour un contact bloqué (l'appelant ne doit rien apprendre).
        if (r.isContactBlocked(author)) {
            DesktopLog.i("nyx-call", "signalisation de ${author.take(16)}… ignorée (contact bloqué, op=${signal.op})")
            return
        }
        when (signal.op) {
            CallSignal.OP_OFFER -> {
                // Anti-rejeu souple : une offre trop vieille est un appel manqué.
                if (signal.ts in 1 until now - CallSignal.OFFER_TTL_S) {
                    r.addCallInfo(author, false, "Appel manqué")
                    return
                }
                if (_ui.value.phase != Phase.IDLE) {
                    // v0.2.26 miroir : l'appelant est LIBÉRÉ par OP_REJECT —
                    // sinon il sonne jusqu'à son timeout sans savoir pourquoi.
                    DesktopLog.i(
                        "nyx-call",
                        "offre ignorée (déjà en appel : ${_ui.value.phase}) de ${author.take(16)}… — OP_REJECT renvoyé"
                    )
                    scope.launch {
                        runCatching {
                            r.sendCallSignal(author, CallSignal(CallSignal.OP_REJECT, signal.cid, "", nowS()).encode())
                        }
                    }
                    return
                }
                pendingOffer = signal
                val label = signal.alias.ifBlank { author.take(10) + "…" }
                DesktopLog.i("nyx-call", "appel entrant de ${author.take(16)}… (cid ${signal.cid.take(8)}…, codec annoncé ${signal.ac.ifEmpty { "aac" }})")
                _ui.value = CallUi(Phase.INCOMING, author, label, signal.cid,
                    message = "appel entrant…", phaseChangedAt = nowMs())
                net.nyx.messenger.notify.DesktopNotifier.incomingCall(author, label)
                // v0.2.20 miroir : la CONNEXION média a pu arriver AVANT l'offre
                // (course journal/poll) : l'adopter si gardée.
                adoptWaitedConnection(signal.cid)
                // Garde-fou : média jamais arrivé → pas de sonnerie à l'infini.
                val offeredCid = signal.cid
                scope.launch {
                    delay(120_000)
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
        DesktopLog.i(
            "nyx-call",
            "acceptation utilisateur (cid ${state.cid.take(8)}… — média " +
                "${if (conn != null) "déjà connecté" else "pas encore arrivé"})"
        )
        net.nyx.messenger.notify.DesktopNotifier.cancelCall(state.peer)
        _ui.value = state.copy(phase = Phase.WAIT, message = "connexion…", phaseChangedAt = nowMs())
        // Si la connexion P2P est déjà là (HELLO reçu avant l'appui) —
        // [onIncomingConnection] l'a gardée en attente : finalise maintenant.
        tryFinalizeWaitedConnection()
        watchdogWait(state.cid)
    }

    /** v0.2.26 miroir — watchdog WAIT : sous 180 s sans média → BYE + fin propre. */
    private fun watchdogWait(cid: String) {
        scope.launch {
            delay(WAIT_WATCHDOG_MS)
            val st = _ui.value
            if (st.phase == Phase.WAIT && st.cid == cid) {
                val r = repo
                scope.launch {
                    runCatching {
                        r?.sendCallSignal(st.peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode())
                    }
                }
                DesktopLog.w("nyx-call", "WAIT sans média sous ${WAIT_WATCHDOG_MS / 1000} s — fin de l'appel (cid ${cid.take(8)}…)")
                end("connexion impossible")
            }
        }
    }

    /** Refuse un appel entrant — trace chez l'appelant (journal) + socket. */
    fun rejectIncoming() {
        val state = _ui.value
        if (state.phase != Phase.INCOMING && state.phase != Phase.WAIT) return
        val r = repo
        val rejConn = conn
        pendingOffer = null
        acceptedCid = null
        // v0.2.52 miroir — LA FIN EST IMMÉDIATE : rejet socket (tampon TCP
        // local, instantané) → end() → le rejet journal best-effort APRÈS.
        scope.launch {
            runCatching { rejConn?.send(P2pProtocol.F_CALL_REJECT) }
            end("refusé")
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_REJECT, state.cid, "", nowS()).encode())
            }
        }
    }

    /**
     * Remise de connexion par [DesktopP2pServer] (côté appelé) : si
     * l'utilisateur a déjà accepté → finalise immédiatement ; si l'offre
     * n'est pas encore traitée (course journal/poll) → la connexion est
     * GARDÉE en attente au lieu d'un refus net ; sinon refus net.
     */
    fun onIncomingConnection(connection: P2pCallConnection) {
        scope.launch {
            synchronized(this@DesktopCallManager) {
                val state = _ui.value
                val wanted = state.phase == Phase.INCOMING && state.cid == connection.hello.cid
                val alreadyAccepted = state.phase == Phase.WAIT && acceptedCid == connection.hello.cid
                if (state.phase == Phase.IDLE) {
                    // L'offre n'est pas encore arrivée (poll ≤ 30 s) : garder.
                    val cid = connection.hello.cid
                    waited[cid] = connection
                    DesktopLog.i("nyx-call", "média AVANT l'offre — connexion gardée (cid ${cid.take(8)}…)")
                    scope.launch {
                        delay(WAITED_TTL_MS)
                        val orphan = synchronized(this@DesktopCallManager) { waited.remove(cid) }
                        if (orphan != null) {
                            runCatching { orphan.send(P2pProtocol.F_CALL_REJECT) }
                            runCatching { orphan.close() }
                        }
                    }
                    return@launch
                }
                if (!wanted && !alreadyAccepted) {
                    // Personne n'attend cet appel (offre expirée, occupé…) : refus net.
                    connection.send(P2pProtocol.F_CALL_REJECT)
                    connection.close()
                    return@launch
                }
                conn = connection
                if (state.phase == Phase.INCOMING) {
                    // L'appui « accepter » viendra : la connexion patiente.
                    DesktopLog.i("nyx-call", "média arrivé pendant la sonnerie — en attente d'acceptation")
                    return@launch
                }
            }
            finalizeIncoming()
        }
    }

    /** Connexions média gardées avant l'offre (course journal/poll). */
    private val waited = HashMap<String, P2pCallConnection>()

    /** Offre traitée : adopte la connexion média gardée, s'il y en a une. */
    private fun adoptWaitedConnection(cid: String) {
        val held = synchronized(this@DesktopCallManager) { waited.remove(cid) } ?: return
        if (_ui.value.phase != Phase.INCOMING || _ui.value.cid != cid) {
            runCatching { held.send(P2pProtocol.F_CALL_REJECT) }
            runCatching { held.close() }
            return
        }
        DesktopLog.i("nyx-call", "offre reçue — connexion média déjà là, adoptée (cid ${cid.take(8)}…)")
        conn = held // la sonnerie continue — finalisation à l'acceptation
    }

    private fun tryFinalizeWaitedConnection() {
        val c = conn ?: return
        if (_ui.value.phase == Phase.WAIT && acceptedCid == c.hello.cid) {
            scope.launch { finalizeIncoming() }
        }
    }

    /** Côté appelé : démarre l'audio puis répond au pair. v0.2.26 miroir : TOUTE exception → end() propre. */
    private fun finalizeIncoming() {
        val c = conn ?: return
        try {
            finalizeIncomingBody(c)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DesktopLog.w("nyx-call", "finalisation entrante échouée : ${e::class.simpleName} ${e.message}")
            end("connexion impossible : ${e.message ?: e::class.simpleName}")
        }
    }

    private fun finalizeIncomingBody(c: P2pCallConnection) {
        // CHOIX DU CODEC (v0.3.6) : le bureau ne fait que l'ADPCM. L'appelant
        // l'annonce dans HELLO.ac (« adpcm » bureau ; « aac,adpcm » téléphone
        // ≥ v0.2.54 ; ABSENT = téléphone ≤ v0.2.53 qui ne SAIT PAS décoder
        // l'ADPCM) → refus propre + indice de mise à jour, jamais de déchets
        // audio muets.
        val callerSupportsAdpcm = c.hello.ac.split(',').map { it.trim() }.contains(P2pProtocol.AC_ADPCM)
        if (!callerSupportsAdpcm) {
            DesktopLog.w(
                "nyx-call",
                "appelant ${c.hello.onion.take(16)}… codec « ${c.hello.ac.ifEmpty { "aac" }} } » seulement — " +
                    "le bureau exige l'ADPCM (téléphone ≤ v0.2.53 ?) : refus + mise à jour requise"
            )
            runCatching { c.send(P2pProtocol.F_CALL_REJECT) }
            runCatching { c.close() }
            conn = null
            end("mettez à jour l'appli téléphone (v0.2.54+) pour appeler le PC")
            return
        }
        val engine = DesktopCallAudio(c.input, { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
        audio = engine
        val myCsdB64 = runCatching {
            java.util.Base64.getEncoder().encodeToString(engine.startSending())
        }.getOrElse {
            c.send(P2pProtocol.F_CALL_REJECT)
            end("micro indisponible"); return
        }
        // v0.2.26 miroir : la réponse d'acceptation non remise (pair disparu)
        // ne fige plus WAIT — fin propre immédiate.
        runCatching {
            c.send(P2pProtocol.F_CALL_ACCEPT, P2pProtocol.AcceptPayload.encode(
                myCsdB64, "", 0, 0, 0, P2pProtocol.AC_ADPCM
            ).toByteArray(Charsets.UTF_8))
        }.getOrElse { e ->
            DesktopLog.w("nyx-call", "CALL_ACCEPT non remis : ${e.message}")
            end("connexion perdue"); return
        }
        engine.startReceiving(P2pProtocol.AC_ADPCM)
        engine.transmit = true // l'appelé a accepté explicitement
        _ui.value = _ui.value.copy(
            phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "",
            phaseChangedAt = nowMs()
        )
        DesktopLog.i("nyx-call", "appel actif avec ${_ui.value.peer.take(16)}… (côté appelé, codec adpcm)")
    }

    /* ------------------------------- COMMUN ------------------------------- */

    fun toggleMute() {
        val state = _ui.value
        val m = !state.muted
        audio?.muted = m
        _ui.value = state.copy(muted = m)
    }

    /**
     * Raccroche (ou annule une sonnerie sortante). v0.2.52 miroir — la fin
     * est IMMÉDIATE : BYE socket (tampon TCP local — instantané) → end() →
     * BYE journal best-effort ensuite.
     */
    fun hangup(reason: String = "terminé") {
        val state = _ui.value
        // v0.2.52 miroir — GARDE PHASES VIVANTES : les taps sur un appel déjà
        // terminé ne font PLUS rien (pas de spam de dépôts OP_BYE).
        if (state.phase != Phase.OUTGOING && state.phase != Phase.INCOMING &&
            state.phase != Phase.WAIT && state.phase != Phase.ACTIVE
        ) return
        val r = repo
        val byeConn = conn
        scope.launch {
            runCatching { byeConn?.send(P2pProtocol.F_BYE) }
            end(reason)
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_BYE, state.cid, "", nowS()).encode())
            }
        }
    }

    /** Termine et nettoie ; l'overlay affiche [reason] 3 s puis revient à IDLE. */
    private fun end(reason: String) {
        runCatching { audio?.stop() }
        runCatching { conn?.close() }
        audio = null
        conn = null
        pendingOffer = null
        acceptedCid = null
        synchronized(this@DesktopCallManager) {
            waited.values.forEach { c -> runCatching { c.send(P2pProtocol.F_CALL_REJECT) }; runCatching { c.close() } }
            waited.clear()
        }
        val peer = _ui.value.peer
        net.nyx.messenger.notify.DesktopNotifier.cancelCall(peer)
        val wasActive = _ui.value.phase == Phase.ACTIVE
        _ui.value = _ui.value.copy(phase = Phase.ENDED, message = reason, muted = false,
            phaseChangedAt = nowMs())
        scope.launch {
            delay(if (wasActive) 2500L else 3000L)
            if (_ui.value.phase == Phase.ENDED) _ui.value = CallUi()
        }
    }

    private fun nowS(): Long = System.currentTimeMillis() / 1000
    private fun nowMs(): Long = System.currentTimeMillis()

    // v0.2.18/0.2.27 miroir — fenêtres d'appel (le pair ne connaît l'offre
    // qu'après son poll ~30 s ; un circuit onion entrant prend 10-30 s).
    private const val CONNECT_WINDOW_MS = 150_000L // essais jusqu'à 150 s (HSDir)
    private const val CONNECT_ATTEMPT_MS = 12_000  // essai individuel (tor : 10-30 s)
    private const val FIRST_ATTEMPT_MS = 45_000    // 1ᵉʳ essai : rendezvous À FROID (15-35 s)
    private const val WAITED_TTL_MS = 90_000L      // connexion gardée avant l'offre
    private const val WAIT_WATCHDOG_MS = 180_000L  // WAIT sans média → fin (BYE au pair)
    private const val GLOBAL_CALL_CAP_MS = 240_000L // phases transitoires > 4 min (filet global)
    private const val ACTIVE_CALL_CAP_MS = 7_200_000L // v0.2.42 : ACTIVE > 2 h (anti-collé)
}
