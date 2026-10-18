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
 *
 * v0.2.26 — ANTI-ÉTAT COLLANT (terrain : « offre ignorée (déjà en appel) » en
 * boucle, appels qui sonnent sans jamais décrocher) : watchdog WAIT, garde-fou
 * global (aucune phase > 4 min), try/catch sur
 * tout le cycle (une exception tuait la coroutine SANS end() → phase figée),
 * OP_REJECT renvoyé à l'appelant quand une offre est ignorée (il cesse de
 * sonner), journalisation des transitions.
 *
 * v0.2.27 — FENÊTRES ALIGNÉES SUR LE TERRAIN (log v0.2.26 09:04 : essais média
 * « Connect timed out » en boucle + tor « No more HSDir available to query »
 * après un reboot — récupération d'annuaire 1-3 min) : fenêtre appelant 75 s →
 * 150 s, watchdog WAIT 60 s → 180 s (l'appelé ne raccroche PLUS avant la fin
 * de la fenêtre appelant — avant, il tuait l'appel en cours d'établissement).
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
        val videoEnabled: Boolean = false, // v0.2.20 : visio négociée (les DEUX côtés)
        // v0.2.26 : horodatage du DERNIER changement de phase (watchdogs).
        val phaseChangedAt: Long = 0L,
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
    /** v0.2.20 — moteur vidéo (visio) : null en appel audio seul. */
    private var video: CallVideo? = null
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
        watchdogGlobal()
    }

    /** v0.2.26 — filet de sécurité GLOBAL : aucune phase ne peut durer plus de
     *  [GLOBAL_CALL_CAP_MS] (terrain v0.2.25 : un état « en appel » collé rendait
     *  l'appareil sourd à toute offre entrante — « offre ignorée (déjà en appel) »). */
    private fun watchdogGlobal() {
        scope.launch {
            while (true) {
                delay(15_000)
                val st = _ui.value
                if (st.phase != Phase.IDLE && st.phase != Phase.ENDED &&
                    st.phaseChangedAt > 0 &&
                    nowMs() - st.phaseChangedAt > GLOBAL_CALL_CAP_MS
                ) {
                    android.util.Log.w(
                        "nyx-call",
                        "garde-fou global : ${st.phase} depuis > ${GLOBAL_CALL_CAP_MS / 1000} s — fin forcée"
                    )
                    end("appel interrompu (délai)")
                }
            }
        }
    }

    /* ----------------------------- APPEL SORTANT ----------------------------- */

    /** Appelle un contact accepté — signalisation journal puis média direct.
     *  v0.2.20 : [withVideo] = visio (H.264 sur le même canal direct). */
    fun call(peer: String, alias: String, withVideo: Boolean = false) {
        val r = repo ?: return
        if (_ui.value.phase != Phase.IDLE) return
        val cid = CallSignal.newCid()
        _ui.value = CallUi(Phase.OUTGOING, peer, alias, cid,
            message = "sonnerie…", videoEnabled = withVideo, phaseChangedAt = nowMs())
        scope.launch {
            // v0.2.26 : TOUTE exception dans le cycle sortant finit désormais
            // par end() — avant, une IOException tuait la coroutine SANS
            // nettoyage et la phase OUTGOING restait collée à jamais.
            try {
                runOutgoing(r, peer, alias, cid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("nyx-call", "appel sortant interrompu : ${e::class.simpleName} ${e.message}")
                end("erreur : ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Corps du cycle sortant (extrait de [call] v0.2.26 pour le try/catch). */
    private suspend fun runOutgoing(r: NyxRepository, peer: String, alias: String, cid: String) {
        run {
            // 1. Sonnerie chez le pair (journal chiffré — ≤ 15 s de poll).
            val offered = r.sendCallSignal(
                peer, CallSignal(CallSignal.OP_OFFER, cid, r.myAlias, nowS()).encode()
            )
            if (offered.isFailure) {
                end("réplica injoignable — appel impossible")
                return@run
            }
            // 2. Connexion média directe — FENÊTRE DE TENTATIVES (fix terrain
            //    v0.2.18 : un seul essai de 40 s partait IMMÉDIATEMENT après
            //    l'offre, alors que le pair ne la connaît qu'après son poll
            //    (~15 s) et qu'un circuit onion entrant peut prendre 10-30 s
            //    → « Connect timed out » = appel mort avant même la sonnerie).
            //    Désormais : essais de 12 s toutes les 3 s pendant 75 s —
            //    le pair sonne et accepte pendant ce temps.
            var socket: java.net.Socket? = null
            val deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS
            var attempt = 0
            while (socket == null && System.currentTimeMillis() < deadline) {
                if (_ui.value.phase != Phase.OUTGOING || _ui.value.cid != cid) return@run
                attempt++
                // v0.2.28 — la cible RÉELLE est loguée (préfixe 16 car — le
                // tor.log scrubbe les onions, le logcat app est la seule trace
                // exploitable pour diagnostiquer « impossible de décrocher »).
                android.util.Log.i(
                    "nyx-call",
                    "média essai $attempt → ${peer.take(16)}…:${P2pProtocol.MEDIA_ONION_PORT}"
                )
                socket = runCatching { P2pClient.connect(peer, CONNECT_ATTEMPT_MS) }.getOrElse { e ->
                    android.util.Log.i(
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
            val c = P2pCallConnection(socket, P2pProtocol.Hello("call", P2pClient.me ?: "", alias, cid, "", 0, ""))
            conn = c
            // csd-0 AVANT le HELLO (l'appelé en a besoin pour décoder).
            val audioEngine = CallAudio(c.input, { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
            audio = audioEngine
            myCsd = runCatching { audioEngine.startSending() }.getOrElse { e ->
                end("micro indisponible : ${e.message}"); return@run
            }
            // v0.2.20 — VISIO : si demandée et caméra dispo, le csd H.264 part
            // dans le HELLO (le pair prépare son décodeur et répond le sien).
            // Caméra indisponible → AUDIO-ONLY (jamais d'appel raté pour ça).
            var vCsdB64 = ""
            if (_ui.value.videoEnabled) {
                val ctx = appContext
                if (ctx == null) {
                    end("contexte indisponible"); return@run
                }
                val engine = CallVideo(ctx,
                    { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
                val vcsd = runCatching { engine.startSending() }.getOrNull()
                if (vcsd != null) {
                    video = engine
                    audioEngine.videoSink = { bytes -> engine.feedDecoder(bytes) }
                    vCsdB64 = java.util.Base64.getEncoder().encodeToString(vcsd)
                    android.util.Log.i("nyx-video", "visio annoncée dans le HELLO (csd ${vcsd.size} o)")
                } else {
                    engine.stop()
                    _ui.value = _ui.value.copy(videoEnabled = false)
                }
            }
            val me = P2pClient.me
            if (me == null) {
                end("identité indisponible"); return@run
            }
            val csdB64 = java.util.Base64.getEncoder().encodeToString(myCsd)
            val sig = P2pClient.signer?.invoke(
                P2pProtocol.helloSignedString("call", me, cid)
            ) ?: ""
            // v0.2.26 : le HELLO non remis (pair raccroché entre-temps) ne tue
            // plus la coroutine sans nettoyage — BYE + fin propre.
            runCatching {
                c.send(P2pProtocol.F_HELLO, P2pProtocol.encodeHello(
                    P2pProtocol.Hello("call", me, alias, cid, csdB64, 0, sig, vCsdB64), sig
                ))
            }.getOrElse { e ->
                android.util.Log.w("nyx-call", "HELLO non remis : ${e.message}")
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("connexion perdue"); return@run
            }
            // 3. Attendre la réponse (60 s — l'appelé sonne et accepte pendant
            //    ce temps ; la fenêtre totale reste sous le watchdog appelé).
            runCatching { socket.soTimeout = 60_000 }
            val answer = runCatching { P2pProtocol.read(c.input) }.getOrNull()
            when (answer?.type) {
                P2pProtocol.F_CALL_ACCEPT -> {
                    // v0.2.20 : payload JSON {csd, vcsd} (ou b64 brut v0.2.19).
                    val (peerAudioCsd, peerVideoCsd) = P2pProtocol.AcceptPayload.parse(answer.payload)
                    val peerCsd = runCatching {
                        java.util.Base64.getDecoder().decode(peerAudioCsd)
                    }.getOrElse { ByteArray(0) }
                    runCatching { audioEngine.startReceiving(peerCsd) }
                    audioEngine.transmit = true // acceptation explicite → l'audio part
                    if (_ui.value.videoEnabled && peerVideoCsd.isNotEmpty()) {
                        runCatching {
                            val vcsd = java.util.Base64.getDecoder().decode(peerVideoCsd)
                            video?.startReceiving(vcsd)
                            video?.transmit = true
                        }
                        android.util.Log.i("nyx-video", "visio ACTIVE (csd pair ${peerVideoCsd.length} o b64)")
                    } else {
                        // Le pair n'a pas de vidéo (v0.2.19 ou caméra refusée).
                        _ui.value = _ui.value.copy(videoEnabled = false)
                        runCatching { video?.stop() }
                        video = null
                    }
                    _ui.value = _ui.value.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "", phaseChangedAt = nowMs())
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
        // v0.2.29 — DÉFENSE EN PROFONDEUR (blocage local) : le poll détruit déjà
        // les signalisations des contacts bloqués (NyxRepository 3quinquies) —
        // ce second garde couvre toute ré-ingestion/rattrapage imprévue. Un
        // appel d'un contact bloqué est ignoré SANS OP_REJECT (silence total :
        // l'appelant ne doit rien apprendre, pas même notre présence).
        if (runCatching { r.isContactBlocked(author) }.getOrDefault(false)) {
            android.util.Log.i(
                "nyx-call",
                "signalisation de ${author.take(16)}… ignorée (contact bloqué localement, op=${signal.op})"
            )
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
                    // v0.2.26 : la phase fautive est JOURNALISÉE (diagnostic terrain)
                    // et l'appelant est LIBÉRÉ par OP_REJECT — avant, il sonnait
                    // jusqu'à son propre timeout sans jamais savoir pourquoi.
                    android.util.Log.i(
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
                android.util.Log.i("nyx-call", "appel entrant de ${author.take(16)}… (cid ${signal.cid.take(8)}…)")
                _ui.value = CallUi(Phase.INCOMING, author, label, signal.cid,
                    message = "appel entrant…", phaseChangedAt = nowMs())
                net.nyx.messenger.notify.ContactNotifier.incomingCall(author, label)
                // v0.2.20 : la CONNEXION média a pu arriver AVANT l'offre
                // (course journal/poll — terrain v0.2.18) : l'adopter si gardée.
                adoptWaitedConnection(signal.cid)
                // Garde-fou : média jamais arrivé (pair en échec après l'offre)
                // → on ne laisse PAS la sonnerie à l'infini.
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
        _ui.value = state.copy(phase = Phase.WAIT, message = "connexion…", phaseChangedAt = nowMs())
        // Si la connexion P2P est déjà là (HELLO reçu avant l'appui) —
        // [onIncomingConnection] l'a gardée en attente : finalise maintenant.
        tryFinalizeWaitedConnection()
        watchdogWait(state.cid)
    }

    /** v0.2.26 — watchdog WAIT : CAUSE RACINE du terrain v0.2.25 (« sonnent
     *  mais ne décrochent pas »). Après acceptation, la phase WAIT n'avait
     *  AUCUN timeout (le watchdog 120 s ne couvre que INCOMING) : si le média
     *  n'arrivait jamais, « connexion… » restait à l'écran pour toujours et
     *  toute offre suivante était ignorée (« déjà en appel »). Désormais :
     *  sous [WAIT_WATCHDOG_MS] sans finalisation → BYE au pair + fin propre. */
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
                android.util.Log.w(
                    "nyx-call",
                    "WAIT sans média sous ${WAIT_WATCHDOG_MS / 1000} s — fin de l'appel (cid ${cid.take(8)}…)"
                )
                end("connexion impossible")
            }
        }
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
     * déjà accepté → finalise immédiatement ; si l'offre n'est pas encore
     * traitée (course journal/poll — terrain v0.2.18 : l'appelant connecte
     * dès l'offre, l'appelé ne la voit qu'au prochain poll) → la connexion
     * est GARDÉE en attente au lieu d'un refus net ; sinon refus net.
     */
    fun onIncomingConnection(connection: P2pCallConnection) {
        scope.launch {
            synchronized(this@CallManager) {
                val state = _ui.value
                val wanted = state.phase == Phase.INCOMING && state.cid == connection.hello.cid
                val alreadyAccepted = state.phase == Phase.WAIT && acceptedCid == connection.hello.cid
                if (state.phase == Phase.IDLE) {
                    // L'offre n'est pas encore arrivée (poll ≤ 15 s) : garder.
                    val cid = connection.hello.cid
                    waited[cid] = connection
                    android.util.Log.i("nyx-call", "média AVANT l'offre — connexion gardée (cid ${cid.take(8)}…)")
                    scope.launch {
                        delay(WAITED_TTL_MS)
                        val orphan = synchronized(this@CallManager) { waited.remove(cid) }
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
                    android.util.Log.i("nyx-call", "média arrivé pendant la sonnerie — en attente d'acceptation")
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
        val held = synchronized(this@CallManager) { waited.remove(cid) } ?: return
        if (_ui.value.phase != Phase.INCOMING || _ui.value.cid != cid) {
            runCatching { held.send(P2pProtocol.F_CALL_REJECT) }
            runCatching { held.close() }
            return
        }
        android.util.Log.i("nyx-call", "offre reçue — connexion média déjà là, adoptée (cid ${cid.take(8)}…)")
        conn = held // la sonnerie continue — finalisation à l'acceptation
    }

    private fun tryFinalizeWaitedConnection() {
        val c = conn ?: return
        if (_ui.value.phase == Phase.WAIT && acceptedCid == c.hello.cid) {
            scope.launch { finalizeIncoming() }
        }
    }

    /** Côté appelé : démarre l'audio (et la visio si négociée) puis répond au pair.
     *  v0.2.26 : TOUTE exception → end() propre (avant : une IOException sur
     *  F_CALL_ACCEPT figeait WAIT à jamais — cf. [watchdogWait]). */
    private fun finalizeIncoming() {
        val c = conn ?: return
        try {
            finalizeIncomingBody(c)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("nyx-call", "finalisation entrante échouée : ${e::class.simpleName} ${e.message}")
            end("connexion impossible : ${e.message ?: e::class.simpleName}")
        }
    }

    private fun finalizeIncomingBody(c: P2pCallConnection) {
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
        // v0.2.20 — le pair a annoncé de la VIDÉO (HELLO vcsd) : on démarre la
        // nôtre et on répond en JSON {csd, vcsd} (pair forcément v0.2.20).
        var myVCsdB64 = ""
        val peerWantVideo = c.hello.vcsd.isNotEmpty()
        val ctx = appContext
        if (peerWantVideo && ctx != null) {
            val vEngine = CallVideo(ctx,
                { t, p -> c.send(t, p) }, { reason -> scope.launch { end(reason) } })
            val myVCsd = runCatching { vEngine.startSending() }.getOrNull()
            if (myVCsd != null) {
                video = vEngine
                engine.videoSink = { bytes -> vEngine.feedDecoder(bytes) }
                myVCsdB64 = java.util.Base64.getEncoder().encodeToString(myVCsd)
                runCatching {
                    vEngine.startReceiving(java.util.Base64.getDecoder().decode(c.hello.vcsd))
                    vEngine.transmit = true
                }
                android.util.Log.i("nyx-video", "visio ACTIVE côté appelé")
            } else {
                vEngine.stop()
            }
        }
        // v0.2.26 : la réponse d'acceptation non remise (pair disparu) ne fige
        // plus WAIT — fin propre immédiate.
        runCatching {
            c.send(P2pProtocol.F_CALL_ACCEPT, P2pProtocol.AcceptPayload.encode(myCsdB64, myVCsdB64)
                .toByteArray(Charsets.UTF_8))
        }.getOrElse { e ->
            android.util.Log.w("nyx-call", "CALL_ACCEPT non remis : ${e.message}")
            end("connexion perdue"); return
        }
        runCatching { engine.startReceiving(peerCsd) }
        engine.transmit = true // l'appelé a accepté explicitement
        _ui.value = _ui.value.copy(
            phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "",
            videoEnabled = myVCsdB64.isNotEmpty(), phaseChangedAt = nowMs()
        )
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

    /* ------------------------- visio (v0.2.20) ------------------------- */

    /** Surface de rendu DISTANT (SurfaceView de l'overlay) — null = détachée. */
    fun setRemoteSurface(s: android.view.Surface?) { video?.setRemoteSurface(s) }

    /** Surface d'APERÇU local (TextureView de l'overlay) — null = détachée. */
    fun setPreviewSurface(s: android.view.Surface?) { video?.setPreviewSurface(s) }

    /** Coupe/relance la CAMÉRA (émission) — la réception continue. */
    fun toggleCamera() {
        video?.toggleCamera()
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
        runCatching { video?.stop() }
        runCatching { conn?.close() }
        audio = null
        video = null
        conn = null
        pendingOffer = null
        acceptedCid = null
        synchronized(this@CallManager) {
            waited.values.forEach { c -> runCatching { c.send(P2pProtocol.F_CALL_REJECT) }; runCatching { c.close() } }
            waited.clear()
        }
        val peer = _ui.value.peer
        net.nyx.messenger.notify.ContactNotifier.cancelCall(peer)
        val wasActive = _ui.value.phase == Phase.ACTIVE
        _ui.value = _ui.value.copy(phase = Phase.ENDED, message = reason, muted = false, speaker = false,
            videoEnabled = false)
        scope.launch {
            delay(if (wasActive) 2500L else 3000L)
            if (_ui.value.phase == Phase.ENDED) _ui.value = CallUi()
        }
    }

    private fun nowS(): Long = System.currentTimeMillis() / 1000
    private fun nowMs(): Long = System.currentTimeMillis()

    /** v0.2.20 — fenêtres d'appel (terrain v0.2.18 : un seul essai 40 s tuait
     *  l'appel avant même le poll de l'appelé — « Connect timed out »).
     *  v0.2.27 — TERRAIN v0.2.26 (log 09:04, Blackview appelant) : les essais
     *  média timeout en boucle (« Connect timed out » ×3) pendant que tor
     *  n'arrive pas à résoudre l'onion de l'appelé (« No more HSDir available
     *  to query », 09:03:41 — juste après un reboot de l'app à 09:00:54 : les
     *  fetches HSDir peuvent prendre 1-3 min). Fenêtre 75 s → 150 s pour
     *  survivre à la récupération d'annuaire de tor. */
    private const val CONNECT_WINDOW_MS = 150_000L // essais jusqu'à 150 s (HSDir)
    private const val CONNECT_ATTEMPT_MS = 12_000  // essai individuel (tor : 10-30 s)
    private const val WAITED_TTL_MS = 90_000L      // connexion gardée avant l'offre
    // v0.2.26 — anti-état collant. v0.2.27 : WAIT 60 s → 180 s — l'appelé ne
    // doit PLUS raccrocher avant la fenêtre appelant (150 s) + HELLO/answer,
    // sinon il tue l'appel que l'appelant est en train d'établir (terrain :
    // « décrocher » puis rien — l'appelé finissait AVANT l'appelant).
    private const val WAIT_WATCHDOG_MS = 180_000L  // WAIT sans média → fin (BYE au pair)
    private const val GLOBAL_CALL_CAP_MS = 240_000L // aucune phase > 4 min (filet global)
}
