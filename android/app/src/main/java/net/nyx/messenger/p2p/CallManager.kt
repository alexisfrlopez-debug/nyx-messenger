package net.nyx.messenger.p2p

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        // v0.2.53 — PRÉSENTATION VIDÉO (matrices des TextureView de l'overlay) :
        // la rotation est une métadonnée (le flux H.264 ne change jamais),
        // locale pour l'aperçu, du pair pour la vue distante.
        val videoLocalRot: Int = 0,   // rotation locale (image droite ; aperçu front en miroir)
        val videoLocalW: Int = 0,     // taille d'encodage locale
        val videoLocalH: Int = 0,
        val videoLocalFront: Boolean = true, // caméra front → aperçu local en miroir
        val videoPeerRot: Int = 0,    // rotation du pair (HELLO/ACCEPT puis F_VIDEO_ROT)
        val videoPeerW: Int = 0,      // taille d'encodage du pair
        val videoPeerH: Int = 0,
        // v0.2.59 — ÉMISSION locale coupée (caméra perdue) mais RÉCEPTION
        // maintenue : l'overlay garde la vue distante et signale l'état.
        val videoSendLost: Boolean = false,
        // v0.2.60 — PARTAGE D'ÉCRAN VALIDÉ DES DEUX CÔTÉS (miroir bureau
        // v0.3.12 — trames F_SCREEN_REQ/ACCEPT/STOP, champ « sc ») :
        val screenSharePending: Boolean = false,  // notre REQ est en vol
        val screenShareRequest: Boolean = false,  // REQ du pair à valider (✓/✕)
        val screenSharing: Boolean = false,       // NOUS partageons notre écran
        val peerScreenShare: Boolean = false,     // le pair partage SON écran
        val screenNotice: String = "",            // message transitoire
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e ->
            net.nyx.messenger.diag.DiagLog.w("nyx-call", "coroutine appel terminée : ${e.message}")
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

    /** v0.2.44 — sockets relais réplica vivantes (appuyées par le repli média) —
     *  fermées par [end] pour ne jamais fuiter une moitié orpheline. */
    private val relaySockets = java.util.Collections.synchronizedList(ArrayList<java.net.Socket>())

    /** Offre entrante en attente (signalisation arrivée avant le média). */
    private var pendingOffer: CallSignal? = null
    private var acceptedCid: String? = null

    /**
     * v0.2.60 — cids récemment TERMINÉS (miroir bureau v0.3.12, terrain 28/10
     * 21:16 : l'offre du cid b2829fd6 re-livrée par la flotte a RE-SONNÉ après
     * la fin de l'appel) : un doublon de livraison ne re-sonne JAMAIS (TTL 10 min).
     */
    private val endedCids = HashMap<String, Long>()

    /** v0.2.61 — cids récemment REFUSÉS (miroir bureau v0.3.10 : l'offre ou le
     *  HELLO peut arriver APRÈS le refus — jamais de re-sonnerie). */
    private val rejectedCids = HashMap<String, Long>()

    /** v0.2.61 — RECONNEXION MÉDIA (miroir bureau v0.3.13 — terrain 29/10
     *  00:22→00:30 : les circuits Tor mouraient à 7-19 s d'un appel sain,
     *  « Connection reset » des deux côtés, la visio se coupait) : l'appel
     *  ne meurt plus à la première mort de socket — le CONNECTEUR re-compose
     *  le MÊME cid, l'autre bout maintient l'appel (grâce 60 s) et
     *  ré-adopte la nouvelle connexion. Budget : RECONNECT_BUDGET cycles. */
    @Volatile private var isCaller = false
    @Volatile private var reconnectsLeft = 0
    @Volatile private var reconnecting = false

    /** v0.2.61 — dernier HELLO émis / dernier ACCEPT envoyé (re-send tels
     *  quels à la reconnexion — même cid, même présentation). */
    @Volatile private var lastHelloPayload: ByteArray = ByteArray(0)
    @Volatile private var lastAcceptPayload: ByteArray = ByteArray(0)

    /** v0.2.61 — l'appel est en mode interop ADPCM (PC↔tel) : la reconnexion
     *  média ne s'applique QU'à ce chemin (le csd y est sans objet) ; un
     *  appel téléphone↔téléphone (AAC/H.264) garde la fin immédiate. */
    @Volatile private var interopAdpcm = false

    /** v0.2.61 — envoi média via la connexion COURANTE (indirection : les
     *  moteurs vidéo/audio survivent à un échange de socket). */
    private fun sendMedia(type: Int, payload: ByteArray) {
        val c = conn ?: return
        runCatching { c.send(type, payload) }
    }

    /** v0.2.61 — notification d'APPEL EN COURS (terrain 29/10 : « si l'app
     *  tel est réduite quand l'appel est reçu, obligé de réouvrir l'appli
     *  pour raccrocher ») : persistante pendant OUTGOING/ACTIVE avec action
     *  RACCROCHER — l'appel se termine sans rouvrir l'application. */
    private fun updateOngoingCallNotification() {
        val st = _ui.value
        if (st.phase == Phase.OUTGOING || st.phase == Phase.ACTIVE) {
            net.nyx.messenger.notify.ContactNotifier.ongoingCall(st.peer, st.alias, st.videoEnabled)
        } else {
            net.nyx.messenger.notify.ContactNotifier.cancelOngoingCall()
        }
    }

    /** v0.2.60 — le pair comprend le protocole partage d'écran (sc=1). */
    @Volatile private var peerScreenProtocol = false

    /**
     * v0.2.60 — LANCEMENT de la capture d'écran (MediaProjection) après
     * F_SCREEN_ACCEPT : câblé par l'UI (l'intention système exige une
     * ACTIVITÉ — jamais depuis le moteur d'appel).
     */
    @Volatile var screenShareStarter: ((onResult: (granted: Boolean) -> Unit) -> Unit)? = null

    /** v0.2.60 — suite du dialogue système MediaProjection (résultat d'activité). */
    @Volatile private var pendingProjectionResult: ((Boolean) -> Unit)? = null

    /** v0.2.60 — l'UI enregistre la suite AVANT de lancer le dialogue système. */
    fun setPendingProjectionResult(cb: (Boolean) -> Unit) { pendingProjectionResult = cb }

    /** v0.2.60 — le dialogue système a répondu (accordé/refusé). */
    fun onScreenProjectionGranted(granted: Boolean) {
        val cb = pendingProjectionResult ?: return
        pendingProjectionResult = null
        cb(granted)
    }

    fun init(repoRef: NyxRepository, context: android.content.Context) {
        repo = repoRef
        appContext = context.applicationContext
        repoRef.callSignalHandler = { author, signal ->
            runBlocking { onSignal(author, signal) }
        }
        watchdogGlobal()
    }

    /** v0.2.42 — filet de sécurité GLOBAL : cap PAR PHASE. Les phases
     *  transitoires (OUTGOING/INCOMING/WAIT…) restent bornées à 4 min (anti-
     *  état-collé v0.2.25 — un état « en appel » collé rendait l'appareil
     *  sourd à toute offre entrante) mais ACTIVE est bornée à 2 H : l'ancien
     *  cap global de 4 min RACCROCHAIT DE FORCE TOUT APPEL EN COURS (retour
     *  terrain session 63). Un appel actif légitime peut durer ; un ACTIVE
     *  collé sans média reste nettoyé après 2 h. */
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
                    android.util.Log.w(
                        "nyx-call",
                        "garde-fou global : ${st.phase} depuis > ${phaseCapMs(st.phase) / 1000} s — fin forcée"
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
        // v0.2.61 — rôle + budget de reconnexion + notification d'appel en
        // cours (l'appelant peut réduire l'appli pendant la sonnerie).
        isCaller = true
        reconnectsLeft = 0 // armé au passage ACTIVE (pas pendant la sonnerie)
        reconnecting = false
        lastHelloPayload = ByteArray(0)
        lastAcceptPayload = ByteArray(0)
        updateOngoingCallNotification()
        // v0.2.58 — RETOUR D'APPEL AUDIBLE chez l'appelant (terrain 28/10 :
        // « lors d'une tentative d'appel, je veux une sonnerie côté appelant
        // pour indiquer que le tel ou pc appelle ») : bi-ton doux pendant que
        // le pair sonne, stop déterministe au décroché/refus/timeout.
        net.nyx.messenger.notify.CallRingback.start()
        scope.launch {
            // v0.2.26 : TOUTE exception dans le cycle sortant finit désormais
            // par end() — avant, une IOException tuait la coroutine SANS
            // nettoyage et la phase OUTGOING restait collée à jamais.
            try {
                runOutgoing(r, peer, alias, cid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                net.nyx.messenger.diag.DiagLog.w("nyx-call", "appel sortant interrompu : ${e::class.simpleName} ${e.message}")
                end("erreur : ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Corps du cycle sortant (extrait de [call] v0.2.26 pour le try/catch). */
    private suspend fun runOutgoing(r: NyxRepository, peer: String, alias: String, cid: String) {
        run {
            // 1. Sonnerie chez le pair (journal chiffré — ≤ 15 s de poll).
            //    v0.2.44 — l'offre embarque le RÉPLICA RELAIS (repli média) :
            //    l'appelé saura où se brancher si le direct n'arrive pas.
            //    v0.2.61 — OFFRE EN PARALLÈLE de la connexion média (miroir
            //    bureau v0.3.13, terrain 29/10 00:31:59 : le dépôt d'offre
            //    prenait ~20 s AVANT le premier essai média — le pair sonnait
            //    tard ; désormais la connexion démarre IMMÉDIATEMENT, le HELLO
            //    fait sonner un pair ≥ v0.2.61/v0.3.13 dès son arrivée).
            val relayHint = r.relayHintFor(peer) ?: ""
            val offerJob = scope.async {
                r.sendCallSignal(
                    peer, CallSignal(CallSignal.OP_OFFER, cid, r.myAlias, nowS(), relayHint,
                    "aac," + P2pProtocol.AC_ADPCM,
                    if (_ui.value.videoEnabled) P2pProtocol.VC_H264 + "," + P2pProtocol.VC_MJPEG else "").encode()
                )
            }
            // 2. Connexion média directe — FENÊTRE DE TENTATIVES (fix terrain
            //    v0.2.18 : un seul essai de 40 s partait IMMÉDIATEMENT après
            //    l'offre, alors que le pair ne la connaît qu'après son poll
            //    (~15 s) et qu'un circuit onion entrant peut prendre 10-30 s
            //    → « Connect timed out » = appel mort avant même la sonnerie).
            //    Désormais : essais de 12 s toutes les 3 s pendant 150 s —
            //    le pair sonne et accepte pendant ce temps.
            //    v0.2.37 — CAUSE RACINE « impossible de décrocher » CORRIGÉE :
            //    chaque P2pClient.connect embarque la récupération HSFETCH
            //    (descripteur périmé après redémarrage du pair = « SOCKS
            //    server general failure » en boucle ; HSFETCH force la
            //    re-demande du descripteur frais puis retente — voir
            //    P2pClient.connect). La fenêtre de 150 s couvre la fenêtre
            //    de propagation HSDir (~30-60 s) du pair qui vient de démarrer.
            var socket: java.net.Socket? = null
            val deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS
            val t0 = System.currentTimeMillis()
            var attempt = 0
            // v0.2.44 — REPLI RELAIS RÉPLICA (session 65) : le direct onion→onion
            // est chroniquement mort quand l'onion du pair est injoignable
            // (points d'intro morts — terrain 23/10 : 35 min sans jamais devenir
            // joignable) alors que téléphone↔réplica fonctionne TOUJOURS
            // (journal + /health 375 ms). À mi-fenêtre, le canal relais est
            // ouvert EN PARALLÈLE (le réplica tient chaque moitié jusqu'à
            // l'appariement) ; si le direct échoue au terme des 150 s, le HELLO
            // part par le relais — le décrochage aboutit enfin.
            // relayHintFor : jamais l'onion du PAIR (chemin mort par définition).
            val relayReplica = r.relayHintFor(peer)
            var relaySocket: java.net.Socket? = null
            var relayOpened = false
            while (socket == null && System.currentTimeMillis() < deadline) {
                if (_ui.value.phase != Phase.OUTGOING || _ui.value.cid != cid) {
                    runCatching { relaySocket?.close() }
                    return@run
                }
                attempt++
                // v0.2.28 — la cible RÉELLE est loguée (préfixe 16 car — le
                // tor.log scrubbe les onions, le logcat app est la seule trace
                // exploitable pour diagnostiquer « impossible de décrocher »).
                // v0.2.48 — PREMIER ESSAI ÉLARGI À 45 s : terrain 24/10 08:48
                // (tor.log Blackview) : le rendezvous onion à froid prend
                // 15-35 s (« pending-join circ … with intro ack. Stalling. »
                // 12 s d'affilée → timeout) alors que CONNECT_ATTEMPT_MS =
                // 12 s tuait l'essai AVANT la complétion — chaque retry
                // relançait un NOUVEAU rendezvous, sans jamais converger. Le
                // 1er essai couvre désormais le rendezvous à froid ; les
                // suivants gardent 12 s (circuit client déjà chaud).
                val attemptMs = if (attempt == 1) FIRST_ATTEMPT_MS else CONNECT_ATTEMPT_MS
                android.util.Log.i(
                    "nyx-call",
                    "média essai $attempt (${attemptMs / 1000} s) → ${peer.take(16)}…:${P2pProtocol.MEDIA_ONION_PORT}"
                )
                socket = runCatching { P2pClient.connect(peer, attemptMs) }.getOrElse { e ->
                    android.util.Log.i(
                        "nyx-call",
                        "connexion directe impossible vers ${peer.take(16)}… (${e.message}) — " +
                            "nouvelle tentative (récupération HSFETCH déjà tentée par P2pClient)"
                    )
                    null
                }
                if (socket == null) {
                    if (!relayOpened && relayReplica != null &&
                        System.currentTimeMillis() - t0 >= RELAY_OPEN_AFTER_MS) {
                        relayOpened = true
                        val rs = runCatching {
                            P2pClient.connectViaRelay(relayReplica, cid, "caller")
                        }.getOrNull()
                        if (rs != null) {
                            rs.soTimeout = 90_000
                            relaySocket = rs
                            relaySockets.add(rs)
                            net.nyx.messenger.diag.DiagLog.i(
                                "nyx-call",
                                "relais réplica ouvert en parallèle — repli prêt (cid ${cid.take(8)}…)"
                            )
                        }
                    }
                    delay(3_000)
                }
            }
            if (socket == null) {
                // Direct épuisé : le RELAIS devient LE média.
                socket = relaySocket
                if (socket != null) {
                    socket.soTimeout = 90_000
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call",
                        "média VIA RELAIS réplica — direct impossible en ${CONNECT_WINDOW_MS / 1000} s (cid ${cid.take(8)}…)"
                    )
                }
            } else {
                // Le direct a gagné : la moitié relais est libérée immédiatement.
                runCatching { relaySocket?.close() }
                synchronized(relaySockets) { relaySockets.remove(relaySocket) }
            }
            if (socket == null) {
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("pas de réponse"); return@run
            }
            // v0.2.61 — garde POST-boucle (miroir bureau v0.3.13, terrain 29/10
            // 00:32: un appel annulé PENDANT un essai en vol voyait la
            // connexion aboutir APRÈS le cancel — l'acceptation tardive du
            // pair ressuscitait l'appel). Une connexion qui aboutit hors
            // OUTGOING est rejetée + BYE, sans jamais redevenir active.
            if (_ui.value.phase != Phase.OUTGOING || _ui.value.cid != cid) {
                net.nyx.messenger.diag.DiagLog.i("nyx-call", "connexion média aboutie après annulation (cid ${cid.take(8)}…) — rejetée + BYE")
                runCatching {
                    val ghost = P2pCallConnection(socket, P2pProtocol.Hello("call", "", "", cid, "", 0, ""))
                    ghost.send(P2pProtocol.F_BYE); ghost.close()
                }
                runCatching { socket.close() }
                offerJob.cancel()
                return@run
            }
            // v0.2.61 — le dépôt d'offre doit aboutir AUSSI (sans lui le pair
            // ≤ v0.2.60 ne sonne jamais) : il est presque toujours déjà fini.
            val offered = offerJob.await()
            if (offered.isFailure) {
                runCatching { socket.close() }
                end("réplica injoignable — appel impossible")
                return@run
            }
            val c = P2pCallConnection(socket, P2pProtocol.Hello("call", P2pClient.me ?: "", alias, cid, "", 0, ""))
            conn = c
            // csd-0 AVANT le HELLO (l'appelé en a besoin pour décoder).
            val audioEngine = CallAudio(c.input, { t, p -> sendMedia(t, p) }, { reason -> scope.launch { onMediaLostOrEnd(reason) } })
            audio = audioEngine
            myCsd = runCatching { audioEngine.startSending() }.getOrElse { e ->
                end("micro indisponible : ${e.message}"); return@run
            }
            // v0.2.20 — VISIO : si demandée et caméra dispo, le csd H.264 part
            // dans le HELLO (le pair prépare son décodeur et répond le sien).
            // Caméra indisponible → AUDIO-ONLY (jamais d'appel raté pour ça).
            // v0.2.55 — le HELLO annonce AUSSI le MJPEG ("h264,mjpeg") : un
            // appelé BUREAU (mjpeg seul) choisira le MJPEG dans son ACCEPT.
            var vCsdB64 = ""
            var vRot = 0; var vW = 0; var vH = 0
            var myVc = ""
            if (_ui.value.videoEnabled) {
                val ctx = appContext
                if (ctx == null) {
                    end("contexte indisponible"); return@run
                }
                // v0.2.53 — le callback de rotation locale alimente l'état UI
                // (l'aperçu suit le téléphone), la rotation part dans le HELLO.
                val engine = CallVideo(ctx,
                    { t, p -> sendMedia(t, p) }, { reason -> scope.launch { onMediaLostOrEnd(reason) } },
                    { rot -> _ui.value = _ui.value.copy(videoLocalRot = rot) },
                    onVideoLost = { reason -> scope.launch { onVideoDegraded(reason) } },
                    onPeerFrameSize = { w, h -> _ui.value = _ui.value.copy(videoPeerW = w, videoPeerH = h) })
                val vSend = runCatching { engine.startSending() }.getOrNull()
                if (vSend?.csd != null) {
                    video = engine
                    audioEngine.videoSink = { bytes -> engine.feedDecoder(bytes) }
                    audioEngine.rotSink = { p -> onPeerRotation(p) }
                    audioEngine.screenSink = { t, p -> onScreenFrame(t, p) }
                    vCsdB64 = java.util.Base64.getEncoder().encodeToString(vSend.csd)
                    vRot = vSend.rotation; vW = vSend.w; vH = vSend.h
                    myVc = P2pProtocol.VC_H264 + "," + P2pProtocol.VC_MJPEG
                    _ui.value = _ui.value.copy(
                        videoLocalRot = vRot, videoLocalW = vW, videoLocalH = vH,
                        videoLocalFront = vSend.front)
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-video",
                        "visio annoncée dans le HELLO (csd ${vSend.csd.size} o, rotation ${vRot}°, ${vW}x${vH}, vc=$myVc)"
                    )
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
            // v0.2.61 — payload MÉMORISÉ ([lastHelloPayload]) : la reconnexion
            // le re-send tel quel (même cid, même présentation vidéo).
            val helloPayload = P2pProtocol.encodeHello(
                // v0.2.53 — vrot/vw/vh : présentation vidéo (un pair v0.2.52 ignore ces clés).
                // v0.2.54 — ac : codecs audio SUPPORTÉS (le bureau ne sait pas
                // l'AAC → il choisira adpcm dans son ACCEPT ; un pair ≤ v0.2.53 ignore la clé).
                // v0.2.55 — vc : codecs vidéo SUPPORTÉS (un appelé bureau — mjpeg seul —
                // choisira mjpeg dans son ACCEPT ; un pair ≤ v0.2.54 ignore la clé).
                P2pProtocol.Hello("call", me, alias, cid, csdB64, 0, sig, vCsdB64, vRot, vW, vH,
                    "aac," + P2pProtocol.AC_ADPCM, myVc,
                    // v0.2.60 — protocole partage d'écran validé (l'appelé
                    // bureau ≥ v0.3.12 l'écho-t-il dans son ACCEPT).
                    P2pProtocol.SC_PROTOCOL), sig
            )
            lastHelloPayload = helloPayload
            runCatching { c.send(P2pProtocol.F_HELLO, helloPayload) }
                .getOrElse { e ->
                net.nyx.messenger.diag.DiagLog.w("nyx-call", "HELLO non remis : ${e.message}")
                runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                end("connexion perdue"); return@run
            }
            // 3. Attendre la réponse (60 s — l'appelé sonne et accepte pendant
            //    ce temps ; la fenêtre totale reste sous le watchdog appelé).
            //    v0.2.56 — LECTURE EN BOUCLE : un appelé ≤ v0.2.55 expédie sa
            //    trame de rotation AVANT son F_CALL_ACCEPT (cause racine terrain
            //    28/10 : l'appel s'effondrait à l'instant du décroché) — la boucle
            //    route ces trames précoces au lieu de les prendre pour un échec.
            runCatching { socket.soTimeout = 60_000 }
            val answer = P2pProtocol.readCallAnswer(c.input, 60_000) { p -> onPeerRotation(p) }
            when (answer?.type) {
                P2pProtocol.F_CALL_ACCEPT -> {
                    // v0.2.61 — GARDE D'ÉTAT (miroir bureau v0.3.13, terrain
                    // 29/10 00:32: le F_CALL_ACCEPT tardif d'un appel ANNULÉ
                    // ressuscitait l'appel côté PC) : un appel qui n'est plus
                    // OUTGOING ne redevient JAMAIS actif — BYE immédiat.
                    if (_ui.value.phase != Phase.OUTGOING || _ui.value.cid != cid) {
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-call", "F_CALL_ACCEPT tardif IGNORÉ (cid ${cid.take(8)}… — appel ${_ui.value.phase}) : BYE au pair"
                        )
                        runCatching { c.send(P2pProtocol.F_BYE) }
                        runCatching { c.close() }
                        runCatching { r.sendCallSignal(peer, CallSignal(CallSignal.OP_BYE, cid, "", nowS()).encode()) }
                        return@run
                    }
                    // v0.2.20 : payload JSON {csd, vcsd} (ou b64 brut v0.2.19) ;
                    // v0.2.53 : + présentation vidéo de l'appelé (vrot/vw/vh).
                    val accept = P2pProtocol.AcceptPayload.parse(answer.payload)
                    // v0.2.60 — le pair écho-t-il le protocole partage d'écran ?
                    peerScreenProtocol = P2pProtocol.supportsScreenShare(accept.sc)
                    val peerCsd = runCatching {
                        java.util.Base64.getDecoder().decode(accept.audioCsd)
                    }.getOrElse { ByteArray(0) }
                    // v0.2.54 — CODEC CHOISI PAR L'APPELÉ : le bureau ne fait
                    // QUE l'ADPCM → il répond ac=adpcm : on bascule émission ET
                    // réception sur le codec logiciel (miroir bureau). Un pair
                    // téléphone répond sans ac (ou aac) → AAC matériel inchangé.
                    if (accept.ac == P2pProtocol.AC_ADPCM) {
                        runCatching { audioEngine.switchSendingToAdpcm() }
                        runCatching { audioEngine.startReceiving(peerCsd, P2pProtocol.AC_ADPCM) }
                        // v0.2.61 — mode interop : la reconnexion média s'applique.
                        interopAdpcm = true
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-call", "codec ADPCM négocié — interop bureau (cid ${cid.take(8)}…)"
                        )
                    } else {
                        runCatching { audioEngine.startReceiving(peerCsd) }
                    }
                    audioEngine.transmit = true // acceptation explicite → l'audio part
                    // v0.2.55 — VISIO : l'appelé a répondu vc="mjpeg" (c'est un
                    // bureau) → l'émission bascule en MJPEG (l'encodeur H.264
                    // n'a rien émis), la réception décode les JPEG de son écran.
                    // Sinon (vc absent, pair ≤ v0.2.54) : dégradation honnête en
                    // AUDIO — l'appel TIENT (miroir bureau).
                    if (_ui.value.videoEnabled && accept.vc == P2pProtocol.VC_MJPEG) {
                        runCatching {
                            // v0.2.58 — pré-rotation accordée par un bureau
                            // ≥ v0.3.10 (ACCEPT vpr) : les trames partent
                            // droites et suivent l'orientation.
                            video?.switchSendingToMjpeg(accept.vpr == P2pProtocol.VPR_PREROTATED)
                            video?.startReceiving(ByteArray(0))
                            video?.transmit = true
                            video?.sendCurrentRotation()
                        }
                        _ui.value = _ui.value.copy(
                            videoPeerRot = accept.vrot, videoPeerW = accept.vw, videoPeerH = accept.vh)
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-video",
                            "visio MJPEG ACTIVE — interop bureau (écran du pair ${accept.vw}x${accept.vh}" +
                                (if (accept.vpr == P2pProtocol.VPR_PREROTATED) ", pré-rotation vpr — la résolution suit l'orientation" else "") + ")"
                        )
                    } else if (_ui.value.videoEnabled && accept.videoCsd.isNotEmpty()) {
                        runCatching {
                            val vcsd = java.util.Base64.getDecoder().decode(accept.videoCsd)
                            video?.startReceiving(vcsd)
                            video?.transmit = true
                            // v0.2.53 — resynchronise la rotation si le téléphone
                            // a tourné pendant la sonnerie/la négociation.
                            video?.sendCurrentRotation()
                        }
                        _ui.value = _ui.value.copy(
                            videoPeerRot = accept.vrot, videoPeerW = accept.vw, videoPeerH = accept.vh)
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-video",
                            "visio ACTIVE (csd pair ${accept.videoCsd.length} o b64, rotation ${accept.vrot}°, ${accept.vw}x${accept.vh})"
                        )
                    } else {
                        // Le pair n'a pas de vidéo (v0.2.19, bureau ≤ v0.3.6 ou caméra refusée).
                        _ui.value = _ui.value.copy(videoEnabled = false)
                        runCatching { video?.stop() }
                        video = null
                        if (accept.vc.isEmpty() && accept.videoCsd.isEmpty() && _ui.value.phase == Phase.OUTGOING) {
                            // informe l'opérateur sans casser l'appel (audio)
                            net.nyx.messenger.diag.DiagLog.w(
                                "nyx-video",
                                "vc absent du pair — visio indisponible (bureau < v0.3.7 ?) : appel AUDIO"
                            )
                        }
                    }
                    _ui.value = _ui.value.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "", phaseChangedAt = nowMs())
                    // v0.2.58 — le retour d'appel s'arrête AVANT l'audio (le
                    // décroché est effectif — plus de tonalité par-dessus la voix).
                    net.nyx.messenger.notify.CallRingback.stop()
                    // v0.2.61 — budget de reconnexion armé + notification en cours.
                    reconnectsLeft = RECONNECT_BUDGET
                    updateOngoingCallNotification()
                    net.nyx.messenger.diag.DiagLog.i("nyx-call", "appel actif avec ${peer.take(16)}… (cid ${cid.take(8)}…)")
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
                // v0.2.60 — offre d'un appel TERMINÉ il y a < 10 min (miroir
                // bureau v0.3.12, terrain 28/10 : re-livraison flotte d'une
                // offre déjà consommée) : doublon, JAMAIS de re-sonnerie.
                val endedAt = synchronized(endedCids) { endedCids[signal.cid] }
                if (endedAt != null && nowMs() - endedAt < 600_000L) {
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call",
                        "offre d'un appel déjà terminé (cid ${signal.cid.take(8)}…) — ignorée (doublon de livraison)"
                    )
                    return
                }
                // v0.2.61 — offre d'un appel REFUSÉ il y a < 120 s (miroir
                // bureau v0.3.10 : le refus de la carte a précédé le poll) :
                // silence, pas de re-sonnerie.
                val rejAt = synchronized(rejectedCids) { rejectedCids[signal.cid] }
                if (rejAt != null && nowMs() - rejAt < 120_000L) {
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call", "offre d'un appel déjà refusé (cid ${signal.cid.take(8)}…) — ignorée"
                    )
                    return
                }
                // v0.2.61 — OFFRE D'UN APPEL DÉJÀ CONNU (miroir bureau v0.3.10 :
                // la connexion média + HELLO authentifié sont arrivés AVANT
                // le poll — la sonnerie a déjà démarré DÈS LE HELLO, ou
                // l'appel est déjà décroché/actif) → l'offre ne fait que
                // CONFIRMER, jamais re-sonner ni renvoyer OP_REJECT au pair.
                if (_ui.value.phase != Phase.IDLE && _ui.value.cid == signal.cid) {
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call",
                        "offre journal en confirmation (déjà en ${_ui.value.phase} — cid ${signal.cid.take(8)}…)"
                    )
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
                net.nyx.messenger.diag.DiagLog.i("nyx-call", "appel entrant de ${author.take(16)}… (cid ${signal.cid.take(8)}…)")
                _ui.value = CallUi(Phase.INCOMING, author, label, signal.cid,
                    message = "appel entrant…", phaseChangedAt = nowMs())
                net.nyx.messenger.notify.ContactNotifier.incomingCall(author, label,
                    // v0.2.57 — VISIO ENTRANTE : le bouton Décrocher de la
                    // notification ouvre l'ACTIVITÉ VERROUILLÉE (la caméra
                    // exige l'appli au premier plan — Android la ferme en
                    // arrière-plan ; terrain 28/10 : FATAL « CameraDevice
                    // was already closed » 0,6 s après le décroché). L'offre
                    // porte l'intention visio (vc) depuis v0.2.55.
                    video = signal.vc.isNotEmpty())
                // v0.2.52 — SONNERIE POSSÉDÉE (boucle MediaPlayer) : terrain
                // 25/10 « Xiaomi continue de sonner alors que l'appel est
                // décroché » — le son de CANAL jouait pendant tout l'appel
                // (annulation seulement à end()). Elle est stoppée au
                // décroché [acceptIncoming], au refus et à toute fin.
                net.nyx.messenger.notify.CallRinger.start()
                // v0.2.44 — REPLI RELAIS : l'appelant a annoncé un réplica
                // relais dans l'offre → s'y brancher dès maintenant (la
                // moitié patiente côté réplica jusqu'à l'appariement). Si le
                // média direct n'arrive jamais, le HELLO de l'appelant sera
                // lu ICI et l'appel se finalise par le relais — c'est le
                // correctif terrain du « impossible de décrocher ».
                if (signal.relay.isNotEmpty()) {
                    relayIncomingWait(signal.relay, signal.cid, author)
                }
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
                // v0.2.61 — TOUT BYE mémorise le cid (même hors appel : le BYE
                // d'un appel annulé peut être traité pendant IDLE — l'offre ou
                // le HELLO re-livré ensuite ne re-sonnera JAMAIS, miroir
                // bureau v0.3.13).
                synchronized(endedCids) {
                    endedCids[signal.cid] = nowMs()
                    endedCids.entries.removeIf { nowMs() - it.value > 600_000L }
                }
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

    /** v0.2.44 — MOITIÉ RELAIS côté appelé : connexion au réplica indiqué par
     *  l'offre (rôle « callee »), puis attente du HELLO de l'appelant (il ne
     *  l'envoie là-bas QUE si son direct échoue). HELLO authentifié comme le
     *  fait P2pServer (signature + onion de l'offre) puis remise à la machine
     *  à états existante ([onIncomingConnection]) — le média relais se
     *  finalise donc EXACTEMENT comme un média direct. */
    private fun relayIncomingWait(relayOnion: String, cid: String, expectedAuthor: String) {
        scope.launch {
            val sock = try {
                P2pClient.connectViaRelay(relayOnion, cid, "callee")
            } catch (e: Exception) {
                net.nyx.messenger.diag.DiagLog.w(
                    "nyx-call",
                    "relais réplica indisponible (cid ${cid.take(8)}…) : ${e.message?.take(60)}"
                )
                return@launch
            }
            relaySockets.add(sock)
            var handedOver = false
            try {
                // L'appelant tente le direct pendant CONNECT_WINDOW_MS (150 s)
                // avant d'émettre son HELLO relais : cette lecture couvre sa
                // fenêtre + la latence d'acceptation de l'appelé.
                sock.soTimeout = (CONNECT_WINDOW_MS + 45_000L).toInt()
                val frame = P2pProtocol.read(sock.getInputStream()) ?: return@launch
                val hello = frame.takeIf { it.type == P2pProtocol.F_HELLO }
                    ?.let { P2pProtocol.parseHello(it.payload) } ?: return@launch
                if (hello.kind != "call" || hello.cid != cid ||
                    !hello.onion.equals(expectedAuthor, ignoreCase = true)) {
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-call", "HELLO relais incohérent (cid ${cid.take(8)}…) — ignoré"
                    )
                    return@launch
                }
                val verifier = P2pVerifier.get
                val expected = P2pProtocol.helloSignedString("call", hello.onion, hello.cid)
                if (verifier == null || !verifier(hello.onion, expected, hello.sig)) {
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-call", "HELLO relais REFUSÉ (signature) de ${hello.onion.take(16)}…"
                    )
                    return@launch
                }
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-call",
                    "média via RELAIS réplica — HELLO de ${hello.onion.take(16)}… (cid ${cid.take(8)}…)"
                )
                // Propriété transférée à la machine à états ([onIncomingConnection]
                // rejette/finalise/garde la connexion elle-même) — NE PAS fermer.
                handedOver = true
                synchronized(relaySockets) { relaySockets.remove(sock) }
                onIncomingConnection(P2pCallConnection(sock, hello))
            } catch (_: Exception) {
                // TTL réplica, pair raccroché, ou le direct a gagné la course :
                // fin silencieuse — la socket est fermée ci-dessous si ouverte.
            } finally {
                if (!handedOver) {
                    runCatching { if (!sock.isClosed) sock.close() }
                    synchronized(relaySockets) { relaySockets.remove(sock) }
                }
            }
        }
    }

    /** Accepte un appel entrant — la connexion média peut encore arriver. */
    fun acceptIncoming() {
        val state = _ui.value
        if (state.phase != Phase.INCOMING) return
        acceptedCid = state.cid
        // v0.2.62 — le SURVEILLANT DE SONNERIE doit être mort AVANT que le
        // moteur audio ne lise la socket (jamais deux lecteurs).
        stopRingByeWatch(join = true)
        // v0.2.33 — trace persistante de l'INSTANT d'acceptation (l'export
        // post-redémarrage ne voyait jamais QUAND l'utilisateur avait appuyé).
        net.nyx.messenger.diag.DiagLog.i(
            "nyx-call",
            "acceptation utilisateur (cid ${state.cid.take(8)}… — média " +
                "${if (conn != null) "déjà connecté" else "pas encore arrivé"})"
        )
        // v0.2.52 — LA SONNERIE S'ARRÊTE ICI : terrain 25/10, la sonnerie
        // du Xiaomi continuait pendant TOUT l'appel (19 s mesurées) car la
        // notification (au son de canal) n'était annulée qu'à end().
        net.nyx.messenger.notify.CallRinger.stop()
        net.nyx.messenger.notify.ContactNotifier.cancelCall(state.peer)
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

    /** Refuse un appel entrant — trace chez l'appelant (journal) + socket.
     *  v0.2.50 — même correctif que hangup() ci-dessous : le F_CALL_REJECT part dans la
     *  coroutine IO, JAMAIS sur le thread UI (NetworkOnMainThreadException —
     *  pile jumelle de celle du crash terrain 24/10 11:48 ; le bouton Refuser
     *  de l'overlay appelle cette fonction en direct depuis le thread UI). */
    fun rejectIncoming() {
        val state = _ui.value
        if (state.phase != Phase.INCOMING && state.phase != Phase.WAIT) return
        val r = repo
        val rejConn = conn
        pendingOffer = null
        acceptedCid = null
        // v0.2.61 — memo du refus (garde anti re-sonnerie : l'offre journal
        // ou le HELLO peuvent arriver APRÈS le refus — miroir bureau v0.3.10).
        synchronized(rejectedCids) {
            rejectedCids[state.cid] = nowMs()
            val it2 = rejectedCids.entries.iterator()
            while (it2.hasNext()) {
                val e = it2.next()
                if (nowMs() - e.value > 300_000L) it2.remove()
            }
        }
        // v0.2.52 — LA FIN EST IMMÉDIATE : terrain 25/10 05:34-05:35, avec
        // tor mort le dépôt OP_REJECT traînait 25-50 s (SOCKS refusé,
        // connectTimeout 25 s) et FIGEAIT le refus « sur place ». Désormais :
        // rejet socket 1 octet (tampon TCP local, instantané) → end() → le
        // rejet journal part best-effort APRÈS (le pair a sinon ses propres
        // watchdogs — il n'attend que la lecture de sa socket).
        scope.launch {
            runCatching { rejConn?.send(P2pProtocol.F_CALL_REJECT) }
            end("refusé")
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_REJECT, state.cid, "", nowS()).encode())
            }
        }
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
            // v0.2.61 — GARDE CONTACT BLOQUÉ (avant la section critique — la
            // vérification est suspend) : silence total pour un bloqué, quel
            // que soit l'état local (offre déjà traitée ou pas).
            val fromOnionEarly = connection.hello.onion
            if (repo?.isContactBlocked(fromOnionEarly) == true) {
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-call", "HELLO de ${fromOnionEarly.take(16)}… ignoré (contact bloqué localement)"
                )
                runCatching { connection.send(P2pProtocol.F_CALL_REJECT) }
                runCatching { connection.close() }
                return@launch
            }
            synchronized(this@CallManager) {
                val state = _ui.value
                val wanted = state.phase == Phase.INCOMING && state.cid == connection.hello.cid
                val alreadyAccepted = state.phase == Phase.WAIT && acceptedCid == connection.hello.cid
                // v0.2.61 — RECONNEXION : le connecteur re-compose le MÊME cid
                // pendant un appel ACTIF en reconnexion → ré-adoption + ACCEPT
                // ré-émis (le moteur vidéo SURVIT — envoi par [sendMedia]).
                val resumeActive = reconnecting && state.phase == Phase.ACTIVE && state.cid == connection.hello.cid
                if (resumeActive) {
                    conn = connection
                    resumeCallOnConnection(connection)
                    return@launch
                }
                if (state.phase == Phase.IDLE) {
                    val cid = connection.hello.cid
                    val fromOnion = connection.hello.onion
                    // v0.2.61 — SONNERIE DÈS LE HELLO AUTHENTIFIÉ (miroir
                    // bureau v0.3.10, terrain 29/10 00:31:59 : l'appel PC→tel
                    // ne sonnait qu'à l'OFFRE du poll, ~25-45 s après le
                    // lancement — l'opérateur annulait avant ; le HELLO média
                    // arrive AVANT et porte DÉJÀ tout : identité vérifiée par
                    // le serveur P2P, cid, alias, codecs, intention visio).
                    // Gardes : contact bloqué (vérifié AVANT la section
                    // critique), appel terminé < 10 min, appel refusé < 120 s
                    // (jamais de re-sonnerie fantôme).
                    val endedAt2 = synchronized(endedCids) { endedCids[cid] }
                    if (endedAt2 != null && nowMs() - endedAt2 < 600_000L) {
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-call", "HELLO d'un appel déjà terminé (cid ${cid.take(8)}…) — refusé"
                        )
                        runCatching { connection.send(P2pProtocol.F_CALL_REJECT) }
                        runCatching { connection.close() }
                        return@launch
                    }
                    val rejAt2 = synchronized(rejectedCids) { rejectedCids[cid] }
                    if (rejAt2 != null && nowMs() - rejAt2 < 120_000L) {
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-call", "HELLO d'un appel déjà refusé (cid ${cid.take(8)}…) — refusé"
                        )
                        runCatching { connection.send(P2pProtocol.F_CALL_REJECT) }
                        runCatching { connection.close() }
                        return@launch
                    }
                    val label = connection.hello.alias.ifBlank { fromOnion.take(10) + "…" }
                    val wantsVideo = P2pProtocol.supportsMjpeg(connection.hello.vc) ||
                        connection.hello.vcsd.isNotEmpty()
                    conn = connection
                    _ui.value = CallUi(Phase.INCOMING, fromOnion, label, cid,
                        videoEnabled = wantsVideo,
                        message = "appel entrant…", phaseChangedAt = nowMs())
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call",
                        "appel entrant DÈS LE HELLO (sonnerie immédiate — offre journal en confirmation, cid ${cid.take(8)}…, codec ${connection.hello.ac.ifEmpty { "aac" }}, visio ${if (wantsVideo) "oui" else "non"})"
                    )
                    net.nyx.messenger.notify.ContactNotifier.incomingCall(fromOnion, label, wantsVideo)
                    net.nyx.messenger.notify.CallRinger.start()
                    startRingByeWatch(connection, cid) // v0.2.62 — l'annulation du pair tue la sonnerie
                    // Garde-fou : média sans offre suivie (pair disparu) → pas
                    // de sonnerie à l'infini (miroir du chemin offre).
                    scope.launch {
                        delay(120_000)
                        if (_ui.value.phase == Phase.INCOMING && _ui.value.cid == cid) {
                            repo?.addCallInfo(fromOnion, false, "Appel manqué")
                            end("appel manqué")
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
                    net.nyx.messenger.diag.DiagLog.i("nyx-call", "média arrivé pendant la sonnerie — en attente d'acceptation")
                    startRingByeWatch(connection, state.cid) // v0.2.62 — l'annulation du pair tue la sonnerie
                    return@launch
                }
            }
            finalizeIncoming()
        }
    }

    /** Connexions média gardées avant l'offre (course journal/poll). */
    private val waited = HashMap<String, P2pCallConnection>()

    /* ---------------- v0.2.62 — SURVEILLANT DE SONNERIE ----------------
     * Preuve 29/10 cid ffe6105b : le PC annule 05:17:56, le tel a CONTINUÉ
     * de sonner jusqu'au « appel manqué » 05:18:02 — le BYE arrivé sur le
     * média GARDE pendant la sonnerie n'était JAMAIS lu (personne ne lisait
     * la socket avant le décroché). Un lecteur léger consomme les trames
     * pendant INCOMING : F_BYE/F_CALL_REJECT tuent la sonnerie À L'INSTANT
     * (« appel terminé (INCOMING) — annulé par le pair (bye pendant la
     * sonnerie) ») ; EOF (pair fermé) = annulation aussi ; toute autre trame
     * est consommée silencieusement (flux gardé parsé). Arrêt DÉTERMINISTE
     * avant la finalisation (génération + join + soTimeout) — jamais deux
     * lecteurs sur la même socket. */
    @Volatile private var ringWatchGen = 0
    @Volatile private var ringWatchThread: Thread? = null

    private fun startRingByeWatch(c: P2pCallConnection, cid: String) {
        stopRingByeWatch(join = true)
        val gen = ++ringWatchGen
        val t = Thread({
            try { c.socket.soTimeout = 250 } catch (_: Exception) {}
            try {
                while (!c.closed.get() && ringWatchGen == gen &&
                    _ui.value.phase == Phase.INCOMING && _ui.value.cid == cid) {
                    val frame = try {
                        P2pProtocol.read(c.input)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        // v0.2.63 — SOCKET MORTE pendant la sonnerie (reset
                        // réseau / circuit Tor effondré) : terrain 29/10 cid
                        // 441c689b — Windows a tué la socket du PC 10:33:38,
                        // l'EOF/RST est arrivé sur le média du tel… et le
                        // break SILENCIEUX d'avant laissait la sonnerie
                        // courir : l'opérateur a décroché dans le vide
                        // (« CALL_ACCEPT non remis : Broken pipe » 10:33:42).
                        // Un pair qui disparaît pendant la sonnerie = annulation.
                        if (ringWatchGen == gen && _ui.value.phase == Phase.INCOMING &&
                            _ui.value.cid == cid) {
                            net.nyx.messenger.diag.DiagLog.i(
                                "nyx-call",
                                "média du pair MORT pendant la sonnerie (${e.javaClass.simpleName}) — sonnerie arrêtée (cid ${cid.take(8)}…)"
                            )
                            end("annulé par le pair (média perdu pendant la sonnerie)")
                        }
                        return@Thread
                    }
                    if (frame == null) {
                        // v0.2.63 — EOF (le pair a FERMÉ pendant la sonnerie) :
                        // le commentaire v0.2.62 prétendait « EOF = annulation
                        // aussi » mais le code SE TAISAIT (break sans end —
                        // terrain 29/10 cid 441c689b : sonnerie poursuivie
                        // 4,3 s après la fermeture). Désormais : fin réelle.
                        if (ringWatchGen == gen && _ui.value.phase == Phase.INCOMING &&
                            _ui.value.cid == cid) {
                            net.nyx.messenger.diag.DiagLog.i(
                                "nyx-call",
                                "fermeture du pair pendant la sonnerie (EOF) — sonnerie arrêtée (cid ${cid.take(8)}…)"
                            )
                            end("annulé par le pair (bye pendant la sonnerie)")
                        }
                        return@Thread
                    }
                    when (frame.type) {
                        P2pProtocol.F_BYE, P2pProtocol.F_CALL_REJECT -> {
                            if (ringWatchGen == gen && _ui.value.phase == Phase.INCOMING &&
                                _ui.value.cid == cid) {
                                net.nyx.messenger.diag.DiagLog.i(
                                    "nyx-call",
                                    "BYE du pair pendant la sonnerie (cid ${cid.take(8)}…) — sonnerie arrêtée à l'instant"
                                )
                                end("annulé par le pair (bye pendant la sonnerie)")
                            }
                            return@Thread
                        }
                        else -> Unit // trames précoces consommées (flux parsé)
                    }
                }
            } finally {
                try { c.socket.soTimeout = 0 } catch (_: Exception) {}
            }
        }, "nyx-ring-watch")
        t.isDaemon = true
        ringWatchThread = t
        t.start()
    }

    private fun stopRingByeWatch(join: Boolean) {
        ringWatchGen++
        val t = ringWatchThread ?: return
        ringWatchThread = null
        if (join) runCatching { t.join(1_500) }
    }

    /** Offre traitée : adopte la connexion média gardée, s'il y en a une. */
    private fun adoptWaitedConnection(cid: String) {
        val held = synchronized(this@CallManager) { waited.remove(cid) } ?: return
        if (_ui.value.phase != Phase.INCOMING || _ui.value.cid != cid) {
            runCatching { held.send(P2pProtocol.F_CALL_REJECT) }
            runCatching { held.close() }
            return
        }
        net.nyx.messenger.diag.DiagLog.i("nyx-call", "offre reçue — connexion média déjà là, adoptée (cid ${cid.take(8)}…)")
        conn = held // la sonnerie continue — finalisation à l'acceptation
        startRingByeWatch(held, cid) // v0.2.62 — un BYE d'annulation tue la sonnerie à l'instant
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
            net.nyx.messenger.diag.DiagLog.w("nyx-call", "finalisation entrante échouée : ${e::class.simpleName} ${e.message}")
            end("connexion impossible : ${e.message ?: e::class.simpleName}")
        }
    }

    private fun finalizeIncomingBody(c: P2pCallConnection) {
        // v0.2.60 — protocole partage d'écran validé : capacité de L'APPELANT
        // (le bureau ≥ v0.3.12 annonce sc="1" dans son HELLO).
        peerScreenProtocol = P2pProtocol.supportsScreenShare(c.hello.sc)
        val peerCsd = runCatching {
            java.util.Base64.getDecoder().decode(c.hello.csd)
        }.getOrElse { ByteArray(0) }
        // v0.2.54 — CHOIX DU CODEC : l'appelant annonce ses codecs supportés
        // (HELLO.ac — liste « aac,adpcm » téléphone ≥ v0.2.54 ; « adpcm » seul
        // bureau ; ABSENT = téléphone ≤ v0.2.53 qui ne sait PAS décoder
        // l'ADPCM). Règle : ADPCM seulement si l'appelant ne SAIT PAS l'AAC
        // (c'est le bureau) — téléphone↔téléphone garde l'AAC matériel.
        val callerCodecs = c.hello.ac.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val chosenAdpcm = callerCodecs.contains(P2pProtocol.AC_ADPCM) && !callerCodecs.contains(P2pProtocol.AC_AAC)
        if (chosenAdpcm) {
            // v0.2.61 — mode interop : la reconnexion média s'applique.
            interopAdpcm = true
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-call", "appelant ${c.hello.onion.take(16)}… ADPCM seul (bureau) — codec logiciel interop"
            )
        }
        val engine = CallAudio(c.input, { t, p -> sendMedia(t, p) }, { reason -> scope.launch { onMediaLostOrEnd(reason) } })
        if (chosenAdpcm) engine.useAdpcm = true   // AVANT startSending — boucle ADPCM d'emblée
        audio = engine
        val myCsdB64 = runCatching {
            java.util.Base64.getEncoder().encodeToString(engine.startSending())
        }.getOrElse {
            c.send(P2pProtocol.F_CALL_REJECT)
            end("micro indisponible"); return
        }
        // v0.2.20 — le pair a annoncé de la VIDÉO (HELLO vcsd) : on démarre la
        // nôtre et on répond en JSON {csd, vcsd} (pair forcément v0.2.20).
        // v0.2.55 — un appelant BUREAU n'a pas de vcsd (pas de H.264) mais
        // annonce vc="mjpeg" : la visio se négocie AUSSI par là. MJPEG choisi
        // quand l'appelant ne SAIT PAS le H.264 (c'est un bureau) ; téléphone↔
        // téléphone (vc="h264,mjpeg") garde le H.264 matériel OCTET POUR OCTET.
        var myVCsdB64 = ""
        var myVRot = 0; var myVW = 0; var myVH = 0
        val callerCodecsV = c.hello.vc.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val chosenMjpeg = callerCodecsV.contains(P2pProtocol.VC_MJPEG) && !callerCodecsV.contains(P2pProtocol.VC_H264)
        val peerWantVideo = c.hello.vcsd.isNotEmpty() || P2pProtocol.supportsMjpeg(c.hello.vc)
        val ctx = appContext
        if (peerWantVideo && ctx != null) {
            // v0.2.53 — même moteur que l'appelant : rotation suivie + callback UI.
            // v0.2.55 — useMjpeg quand l'appelant est un bureau (mjpeg seul).
            val vEngine = CallVideo(ctx,
                { t, p -> sendMedia(t, p) }, { reason -> scope.launch { onMediaLostOrEnd(reason) } },
                { rot -> _ui.value = _ui.value.copy(videoLocalRot = rot) },
                useMjpeg = chosenMjpeg,
                onVideoLost = { reason -> scope.launch { onVideoDegraded(reason) } },
                onPeerFrameSize = { w, h -> _ui.value = _ui.value.copy(videoPeerW = w, videoPeerH = h) })
            val vSend = runCatching { vEngine.startSending() }.getOrNull()
            if (vSend?.csd != null) {
                video = vEngine
                engine.videoSink = { bytes -> vEngine.feedDecoder(bytes) }
                engine.rotSink = { p -> onPeerRotation(p) }
                engine.screenSink = { t, p -> onScreenFrame(t, p) }
                // csd VIDE en MJPEG (aucun paramètre de décodeur) — le HELLO du
                // bureau n'en attend aucun, l'ACCEPT répond vc="mjpeg".
                myVCsdB64 = if (chosenMjpeg) "" else java.util.Base64.getEncoder().encodeToString(vSend.csd)
                myVRot = vSend.rotation; myVW = vSend.w; myVH = vSend.h
                _ui.value = _ui.value.copy(
                    videoLocalRot = myVRot, videoLocalW = myVW, videoLocalH = myVH,
                    videoLocalFront = vSend.front,
                    // présentation de L'APPELANT lue de son HELLO (0/0/0 si v0.2.52 ;
                    // vw/vh d'écran s'il s'agit du bureau)
                    videoPeerRot = c.hello.vrot, videoPeerW = c.hello.vw, videoPeerH = c.hello.vh)
                runCatching {
                    vEngine.startReceiving(
                        if (chosenMjpeg) ByteArray(0)
                        else java.util.Base64.getDecoder().decode(c.hello.vcsd)
                    )
                    // v0.2.56 — NI transmit NI rotation ICI : le F_CALL_ACCEPT
                    // part AVANT TOUTE trame média (la rotation qui précédait
                    // l'ACCEPT en v0.2.53-0.2.55 faisait traiter l'appel comme
                    // « pas de réponse » par tout appelant à lecture unique).
                }
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-video",
                    if (chosenMjpeg)
                        "visio MJPEG ACTIVE côté appelé — interop bureau (PRÉ-TOURNÉE ${myVW}x${myVH} ${if (myVW < myVH) "portrait" else "paysage"} — la résolution suit l'orientation, écran pair ${c.hello.vw}x${c.hello.vh})"
                    else
                        "visio ACTIVE côté appelé (rotation locale ${myVRot}°, pair ${c.hello.vrot}°)"
                )
            } else {
                vEngine.stop()
            }
        }
        // v0.2.26 : la réponse d'acceptation non remise (pair disparu) ne fige
        // plus WAIT — fin propre immédiate.
        // v0.2.61 — payload ACCEPT MÉMORISÉ ([lastAcceptPayload]) : ré-émis tel
        // quel quand le connecteur re-compose le même cid après une reconnexion.
        val acceptPayload = P2pProtocol.AcceptPayload.encode(
            myCsdB64, myVCsdB64, myVRot, myVW, myVH,
            if (chosenAdpcm) P2pProtocol.AC_ADPCM else "",
            if (chosenMjpeg) P2pProtocol.VC_MJPEG else "",
            // v0.2.60 — ÉCHO du protocole partage d'écran (l'appelant
            // bureau ≥ v0.3.12 y lit notre capacité sc).
            if (peerScreenProtocol) P2pProtocol.SC_PROTOCOL else ""
        ).toByteArray(Charsets.UTF_8)
        lastAcceptPayload = acceptPayload
        runCatching {
            // v0.2.53 — la présentation de l'appelé accompagne son csd
            // (un appelant v0.2.52 ignore ces clés du JSON).
            // v0.2.54 — ac : le codec CHOISI (adpcm = interop bureau ; ABSENT
            // sinon → format/téléphone↔téléphone inchangés).
            // v0.2.55 — vc : codec vidéo CHOISI (« mjpeg » seulement quand le
            // MJPEG est choisi — téléphone↔téléphone reste le format historique).
            c.send(P2pProtocol.F_CALL_ACCEPT, acceptPayload)
        }.getOrElse { e ->
            net.nyx.messenger.diag.DiagLog.w("nyx-call", "CALL_ACCEPT non remis : ${e.message}")
            end("connexion perdue"); return
        }
        // v0.2.54 — réception : le codec de L'APPELANT (bureau = ADPCM).
        runCatching { engine.startReceiving(peerCsd, if (chosenAdpcm) P2pProtocol.AC_ADPCM else "") }
        engine.transmit = true // l'appelé a accepté explicitement
        // v0.2.56 — l'ÉMISSION (et la resynchronisation de rotation) ne part
        // qu'APRÈS le F_CALL_ACCEPT : le pair à lecture unique voit TOUJOURS
        // l'ACCEPT en premier (interop avec v0.2.53-0.3.7).
        if (video != null) {
            video?.transmit = true
            video?.sendCurrentRotation()
        }
        _ui.value = _ui.value.copy(
            phase = Phase.ACTIVE, startedAt = System.currentTimeMillis(), message = "",
            videoEnabled = myVCsdB64.isNotEmpty() || chosenMjpeg, phaseChangedAt = nowMs()
        )
        // v0.2.61 — rôle APPELÉ + budget de reconnexion armé (miroir appelant)
        // + notification d'appel en cours (action Raccrocher).
        isCaller = false
        reconnectsLeft = RECONNECT_BUDGET
        updateOngoingCallNotification()
        net.nyx.messenger.diag.DiagLog.i("nyx-call", "appel actif avec ${_ui.value.peer.take(16)}… (côté appelé)")
    }

    /* ------------- v0.2.61 — RECONNEXION MÉDIA EN COURS D'APPEL ------------- */

    /**
     * Mort du média EN COURS D'APPEL (terrain 29/10 00:22→00:30 : circuits
     * Tor morts à 7-19 s — « Connection reset » des deux côtés, la visio se
     * coupait) : au lieu d'une fin immédiate, l'appel passe en
     * « reconnexion… » — le CONNECTEUR (l'appelant d'origine) re-compose le
     * MÊME cid, l'appelé maintient l'appel (grâce RECONNECT_GRACE_MS) et
     * ré-adopte la connexion. Budget RECONNECT_BUDGET cycles par appel ;
     * épuisé, hors ACTIVE ou hors interop ADPCM → fin classique [end].
     */
    private fun onMediaLostOrEnd(reason: String) {
        val st = _ui.value
        // v0.2.62 — UN BYE EST UN RACCROCHÉ, PAS UNE PERTE DE CIRCUIT
        // (preuve 29/10 05:22:24 : le PC raccroche, le tel journalise
        // « média perdu en cours d'appel (bye) — reconnexion » puis ré-essaie
        // 4 s — la reconnexion n'est QUE pour les vraies morts de réseau) :
        // fin immédiate, aucun essai.
        if (reason.contains("bye", ignoreCase = true)) {
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-call", "pair raccroché (BYE) — fin immédiate (cid ${st.cid.take(8)}…, pas de reconnexion)"
            )
            end("terminé")
            return
        }
        if (st.phase != Phase.ACTIVE || reconnectsLeft <= 0 || reconnecting || !interopAdpcm) {
            end(reason)
            return
        }
        reconnectsLeft--
        reconnecting = true
        _ui.value = st.copy(message = "reconnexion… ($reconnectsLeft essai${if (reconnectsLeft > 1) "s" else ""} restant${if (reconnectsLeft > 1) "s" else ""})", phaseChangedAt = nowMs())
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-call",
            "média perdu en cours d'appel ($reason) — reconnexion (cid ${st.cid.take(8)}…, ${if (reconnectsLeft > 0) "$reconnectsLeft cycle(s) restant(s)" else "dernier cycle"})"
        )
        // Le lecteur de l'ANCIEN socket est mort ; l'émission n'écrirait que
        // dans le vide → moteur audio arrêté (recréé à la reprise). Le moteur
        // vidéo SURVIT (émission via [sendMedia] — la nouvelle connexion y
        // route automatiquement les trames).
        runCatching { audio?.stop() }
        audio = null
        if (isCaller) attemptMediaReconnect(st.peer, st.cid) else reconnectHoldWatchdog(st.cid)
    }

    /** Côté CONNECTEUR : re-compose le même cid jusqu'à RECONNECT_TRIES
     * essais ; ACCEPT → reprise ; épuisement → fin honnête. */
    private fun attemptMediaReconnect(peer: String, cid: String) {
        scope.launch {
            try {
                for (round in 1..RECONNECT_TRIES) {
                    if (_ui.value.phase != Phase.ACTIVE || _ui.value.cid != cid || !reconnecting) return@launch
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-call", "reconnexion média essai $round/$RECONNECT_TRIES → ${peer.take(16)}…:${P2pProtocol.MEDIA_ONION_PORT}"
                    )
                    val sock = runCatching { P2pClient.connect(peer, RECONNECT_ATTEMPT_MS) }.getOrNull()
                    if (sock != null) {
                        val c = P2pCallConnection(sock, P2pProtocol.Hello("call", P2pClient.me ?: "", "", cid, "", 0, ""))
                        val ok = runCatching {
                            // HELLO de reprise : MÊME cid, même présentation
                            // ([lastHelloPayload]) — l'appelé ré-adopte.
                            if (lastHelloPayload.isEmpty()) error("HELLO d'origine inconnu")
                            c.send(P2pProtocol.F_HELLO, lastHelloPayload)
                            sock.soTimeout = 15_000
                            val answer = P2pProtocol.readCallAnswer(c.input, 15_000) { p -> onPeerRotation(p) }
                            answer?.type == P2pProtocol.F_CALL_ACCEPT
                        }.getOrDefault(false)
                        if (ok) {
                            conn = c
                            resumeCallOnConnection(c)
                            return@launch
                        }
                        runCatching { c.close() }
                    }
                    if (round < RECONNECT_TRIES) delay(3_000)
                }
                if (_ui.value.phase == Phase.ACTIVE && _ui.value.cid == cid && reconnecting) {
                    net.nyx.messenger.diag.DiagLog.w("nyx-call", "reconnexion impossible — fin de l'appel (cid ${cid.take(8)}…)")
                    end("connexion perdue")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (_ui.value.phase == Phase.ACTIVE && _ui.value.cid == cid) end("connexion perdue : ${e.message?.take(80)}")
            }
        }
    }

    /** Reprise sur une connexion ré-établée (les DEUX rôles y passent) :
     *  audio recréé sur le nouveau flux, la visio continue (émission
     *  [sendMedia], réception re-câblée au nouveau lecteur). */
    private fun resumeCallOnConnection(c: P2pCallConnection) {
        scope.launch {
            try {
                // Côté appelé : ré-pOND l'ACCEPT d'origine (le connecteur l'attend).
                if (!isCaller && lastAcceptPayload.isNotEmpty()) {
                    runCatching { c.send(P2pProtocol.F_CALL_ACCEPT, lastAcceptPayload) }
                }
                runCatching { audio?.stop() }
                val engine = CallAudio(c.input, { t, p -> sendMedia(t, p) }, { reason -> scope.launch { onMediaLostOrEnd(reason) } })
                engine.useAdpcm = interopAdpcm // AVANT startSending — mode interop d'emblée
                audio = engine
                engine.videoSink = video?.let { v -> { bytes -> v.feedDecoder(bytes) } }
                engine.rotSink = { p -> onPeerRotation(p) }
                engine.screenSink = { t, p -> onScreenFrame(t, p) }
                runCatching { engine.startSending() } // csd frais (ignoré en ADPCM)
                runCatching { engine.startReceiving(ByteArray(0), if (interopAdpcm) P2pProtocol.AC_ADPCM else "") }
                engine.muted = _ui.value.muted
                engine.transmit = true
                reconnecting = false
                _ui.value = _ui.value.copy(message = "", phaseChangedAt = nowMs())
                net.nyx.messenger.diag.DiagLog.i("nyx-call", "média RÉTABLI — appel repris (cid ${_ui.value.cid.take(8)}…)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                net.nyx.messenger.diag.DiagLog.w("nyx-call", "reprise média impossible : ${e.message?.take(80)}")
                // L'appel tient encore un cycle si le budget le permet.
                if (reconnectsLeft > 0) onMediaLostOrEnd("reprise échouée") else end("connexion perdue")
            }
        }
    }

    /** Côté appelé : grâce sans retour du connecteur → fin honnête. */
    private fun reconnectHoldWatchdog(cid: String) {
        scope.launch {
            delay(RECONNECT_GRACE_MS)
            if (_ui.value.phase == Phase.ACTIVE && _ui.value.cid == cid && reconnecting) {
                net.nyx.messenger.diag.DiagLog.w(
                    "nyx-call", "reconnexion sans retour du pair sous ${RECONNECT_GRACE_MS / 1000} s — fin (cid ${cid.take(8)}…)"
                )
                end("connexion perdue")
            }
        }
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

    /**
     * v0.2.60 — PARTAGE D'ÉCRAN VALIDÉ DES DEUX CÔTÉS (miroir bureau v0.3.12).
     * Demande au pair la permission de remplacer NOTRE caméra par NOTRE écran
     * (MediaProjection) ; la capture ne démarre qu'à son F_SCREEN_ACCEPT.
     */
    fun requestScreenShare() {
        val state = _ui.value
        if (!state.videoEnabled || state.phase != Phase.ACTIVE) return
        val c = conn ?: return
        if (state.screenSharing) {
            // On partage déjà : arrêt + information du pair.
            runCatching { c.send(P2pProtocol.F_SCREEN_STOP) }
            net.nyx.messenger.p2p.screenshare.ScreenShare.stop()
            video?.switchSendingBackToCamera()
            _ui.value = state.copy(screenSharing = false, screenNotice = "Partage d'écran arrêté — caméra.")
            return
        }
        if (state.peerScreenShare) {
            // v0.2.62 — le PAIR partage : le même bouton RÉVOQUE son partage
            // (demande 29/10 : « besoin aussi d'un bouton pour annuler le
            // partage d'écran lorsqu'il est proposé ou activé » — les DEUX
            // états, les DEUX sens). Il voit « révoqué par le pair » et
            // revient à SA caméra.
            runCatching { c.send(P2pProtocol.F_SCREEN_STOP) }
            _ui.value = state.copy(peerScreenShare = false, screenNotice = "Partage du PC révoqué — sa caméra revient.")
            net.nyx.messenger.diag.DiagLog.i("nyx-video", "partage du PC RÉVOQUÉ par l'utilisateur (F_SCREEN_STOP)")
            return
        }
        if (!peerScreenProtocol) {
            _ui.value = state.copy(
                screenNotice = "Partage d'écran : le PC ne le valide pas (≥ v0.3.12 requis)."
            )
            return
        }
        if (state.screenSharePending) return
        // v0.2.62 — ÉMISSION HONNÊTE (défaut détecté 29/10 06:22:06 : le tel
        // journalisait « F_SCREEN_REQ émis » même si l'écriture échouait
        // silencieusement — le PC n'a rien vu, le diagnostic était impossible).
        val sent = runCatching {
            c.send(P2pProtocol.F_SCREEN_REQ, P2pProtocol.SC_PROTOCOL.toByteArray(Charsets.US_ASCII)); true
        }.getOrDefault(false)
        if (!sent) {
            net.nyx.messenger.diag.DiagLog.w(
                "nyx-video", "envoi trame 0x19 impossible — liaison perdue : demande de partage NON émise"
            )
            _ui.value = state.copy(screenNotice = "Partage d'écran : envoi impossible (liaison perdue).")
            return
        }
        _ui.value = state.copy(screenSharePending = true, screenNotice = "Demande envoyée — en attente du PC…")
        net.nyx.messenger.diag.DiagLog.i("nyx-video", "F_SCREEN_REQ émis — attente de la validation du PC")
        val reqAt = nowMs()
        scope.launch {
            delay(30_000)
            if (_ui.value.screenSharePending && _ui.value.phase == Phase.ACTIVE && nowMs() - reqAt >= 29_000) {
                _ui.value = _ui.value.copy(
                    screenSharePending = false,
                    screenNotice = "Partage d'écran : aucune réponse du PC — caméra maintenue."
                )
            }
        }
    }

    /** v0.2.62 — ANNULER NOTRE demande de partage en attente (demande
     *  opérateur 29/10 : « besoin aussi d'un bouton pour annuler le partage
     *  d'écran lorsqu'il est proposé ou activé ») : F_SCREEN_STOP au pair,
     *  attente levée, caméra maintenue — le pair voit « partage refusé ». */
    fun cancelScreenShareRequest() {
        val state = _ui.value
        if (!state.screenSharePending) return
        val c = conn
        val sent = if (c != null) {
            runCatching { c.send(P2pProtocol.F_SCREEN_STOP); true }.getOrDefault(false)
        } else false
        _ui.value = state.copy(
            screenSharePending = false,
            screenNotice = if (sent) "Demande de partage annulée — caméra." else ""
        )
        net.nyx.messenger.diag.DiagLog.i(
            "nyx-video",
            "demande de partage ANNULEE par l'utilisateur (F_SCREEN_STOP" +
                (if (sent) ", émis" else " — liaison perdue") + ")"
        )
    }

    /** v0.2.60 — RÉPONSE à la demande DU PC (boutons ✓/✕ de l'écran d'appel). */
    fun answerScreenShare(accept: Boolean) {
        val state = _ui.value
        if (!state.screenShareRequest) return
        val c = conn ?: return
        runCatching {
            c.send(
                if (accept) P2pProtocol.F_SCREEN_ACCEPT else P2pProtocol.F_SCREEN_STOP,
                P2pProtocol.SC_PROTOCOL.toByteArray(Charsets.US_ASCII)
            )
        }
        _ui.value = state.copy(
            screenShareRequest = false,
            peerScreenShare = accept,
            screenNotice = if (accept) "Le PC partage son écran." else "Demande refusée."
        )
        net.nyx.messenger.diag.DiagLog.i("nyx-video", "REQ partage d'écran du PC → ${if (accept) "ACCEPTÉ" else "REFUSÉ"}")
    }

    /**
     * v0.2.60 — trames de partage d'écran du PAIR (routées par CallAudio).
     * F_SCREEN_ACCEPT déclenche l'intention système MediaProjection (via
     * [screenShareStarter] câblé par l'UI) : la capture ne part QU'après le
     * consentement opérateur ET la validation du pair — les DEUX côtés.
     */
    private fun onScreenFrame(type: Int, @Suppress("UNUSED_PARAMETER") payload: ByteArray) {
        when (type) {
            P2pProtocol.F_SCREEN_REQ -> {
                if (_ui.value.phase != Phase.ACTIVE || !peerScreenProtocol) {
                    runCatching { conn?.send(P2pProtocol.F_SCREEN_STOP) }
                    return
                }
                _ui.value = _ui.value.copy(screenShareRequest = true)
                net.nyx.messenger.diag.DiagLog.i("nyx-video", "F_SCREEN_REQ du PC — dialogue ✓/✕ affiché")
            }
            P2pProtocol.F_SCREEN_ACCEPT -> {
                if (!_ui.value.screenSharePending) return
                _ui.value = _ui.value.copy(screenSharePending = false)
                val starter = screenShareStarter
                if (starter == null) {
                    runCatching { conn?.send(P2pProtocol.F_SCREEN_STOP) }
                    _ui.value = _ui.value.copy(screenNotice = "Partage d'écran indisponible ici.")
                    return
                }
                net.nyx.messenger.diag.DiagLog.i("nyx-video", "partage d'écran VALIDÉ par le PC — demande MediaProjection (dialogue système)")
                starter { granted ->
                    scope.launch {
                        if (!granted) {
                            runCatching { conn?.send(P2pProtocol.F_SCREEN_STOP) }
                            _ui.value = _ui.value.copy(screenNotice = "Capture d'écran refusée — caméra maintenue.")
                            return@launch
                        }
                        val ctx = appContext ?: return@launch
                        val ok = net.nyx.messenger.p2p.screenshare.ScreenShare.start(ctx) { jpeg ->
                            video?.feedScreenFrame(jpeg)
                        }
                        if (ok && video != null) {
                            video?.switchSendingToScreen()
                            _ui.value = _ui.value.copy(
                                screenSharing = true,
                                screenNotice = "Votre écran est partagé (validé par le PC)."
                            )
                        } else {
                            net.nyx.messenger.p2p.screenshare.ScreenShare.stop()
                            runCatching { conn?.send(P2pProtocol.F_SCREEN_STOP) }
                            _ui.value = _ui.value.copy(screenNotice = "Capture d'écran impossible — caméra maintenue.")
                        }
                    }
                }
            }
            P2pProtocol.F_SCREEN_STOP -> {
                val st = _ui.value
                when {
                    st.screenSharePending -> _ui.value = st.copy(
                        screenSharePending = false,
                        screenNotice = "Partage REFUSÉ par le PC — caméra maintenue."
                    )
                    st.screenSharing -> {
                        net.nyx.messenger.p2p.screenshare.ScreenShare.stop()
                        video?.switchSendingBackToCamera()
                        _ui.value = st.copy(
                            screenSharing = false,
                            screenNotice = "Partage révoqué par le PC — caméra."
                        )
                    }
                    st.peerScreenShare -> _ui.value = st.copy(
                        peerScreenShare = false,
                        screenNotice = "Le PC a arrêté son partage d'écran."
                    )
                }
                net.nyx.messenger.diag.DiagLog.i("nyx-video", "F_SCREEN_STOP du PC")
            }
        }
    }

    /** Surface de rendu DISTANT (SurfaceView de l'overlay) — null = détachée. */
    fun setRemoteSurface(s: android.view.Surface?) { video?.setRemoteSurface(s) }

    /** Surface d'APERÇU local (TextureView de l'overlay) — null = détachée. */
    fun setPreviewSurface(s: android.view.Surface?) { video?.setPreviewSurface(s) }

    /**
     * v0.2.53 — le pair a tourné SON écran (trame F_VIDEO_ROT) : la vue
     * distante de l'overlay change de matrice de présentation — le flux
     * H.264 et le décodeur ne bougent PAS (aucune coupure, aucune
     * resynchronisation IDR). Payload illisible ou pair ≤ v0.2.52 : ignoré.
     */
    private fun onPeerRotation(payload: ByteArray) {
        val rot = VideoRot.decodeFrame(payload) ?: return
        if (_ui.value.videoPeerRot != rot) {
            _ui.value = _ui.value.copy(videoPeerRot = rot)
            net.nyx.messenger.diag.DiagLog.i("nyx-video", "orientation du pair suivie : ${rot}°")
        }
    }

    /** Coupe/relance la CAMÉRA (émission) — la réception continue. */
    fun toggleCamera() {
        video?.toggleCamera()
    }

    /** Raccroche (ou annule une sonnerie sortante).
     *  v0.2.50 — PLUS JAMAIS D'ÉCRITURE SOCKET DEPUIS LE THREAD UI : terrain
     *  24/10 11:48 puis 11:49 (v0.2.49, Blackview appelante) — la sonnerie ne
     *  sonnait jamais (routage de l'offre, cf. NyxRepository v0.2.50) et
     *  l'opérateur, en tapant Raccrocher ~40-50 s plus tard, déclenchait
     *  conn.send(F_BYE) SUR LE THREAD PRINCIPAL → NetworkOnMainThreadException
     *  → CRASH FATAL de l'app ENTIÈRE (tor + réplica compris — le pair voyait
     *  ensuite son poll expirer 29 s : « réplica … en échec »). DEUX fois en
     *  80 s, pile la pile terrain « V2.J.b / V2.B.g / writeByte ». Le BYE
     *  socket + le BYE journal + le nettoyage partent désormais dans la
     *  coroutine IO ; l'UI suit _ui (ENDED) quelques millisecondes plus tard
     *  — aucun appel tactile ne peut donc plus tuer l'app. */
    fun hangup(reason: String = "terminé") {
        val state = _ui.value
        // v0.2.52 — GARDE PHASES VIVANTES : terrain 25/10, l'écran ENDED
        // martelé (16 dépôts OP_BYE d'affilée en 14 s, connectTimeout 25 s
        // chacun via le SOCKS mort) spammait des coroutines de dépôt. Les
        // taps sur un appel déjà terminé ne font PLUS rien.
        if (state.phase != Phase.OUTGOING && state.phase != Phase.INCOMING &&
            state.phase != Phase.WAIT && state.phase != Phase.ACTIVE
        ) return
        val r = repo
        val byeConn = conn
        // v0.2.52 — LA FIN EST IMMÉDIATE (le dépôt journal ne la retient
        // plus EN OTAGE) : BYE socket 1 octet (tampon TCP local — instantané,
        // même socket mourante) → end() → BYE journal best-effort ensuite.
        // Terrain 25/10 : « impossible d'arrêter la tentative d'appel sans
        // tout fermer » — end() attendait le dépôt sur le SOCKS refusé.
        scope.launch {
            runCatching { byeConn?.send(P2pProtocol.F_BYE) }
            end(reason)
            runCatching {
                r?.sendCallSignal(state.peer, CallSignal(CallSignal.OP_BYE, state.cid, "", nowS()).encode())
            }
        }
    }

    /**
     * v0.2.57 — DÉGRADATION HONNÊTE de la visio : la caméra/le flux vidéo est
     * perdu en COURS d'appel → l'appel CONTINUE EN AUDIO. Miroir exact du
     * micro HS (v0.2.48/v0.2.56 : « micro HS = émission coupée, l'appel
     * continue en réception »). Terrain 28/10 11:49:59 (export v0.2.56) :
     * visio décrochée DEPUIS LA NOTIFICATION, téléphone verrouillé → le
     * système fermait la caméra PENDANT la configuration de session →
     * IllegalStateException non attrapée sur « nyx-cam » → FATAL EXCEPTION,
     * process tué 0,6 s après le début de la visio. La visio ne TUERA plus
     * JAMAIS un appel sain.
     */
    private fun onVideoDegraded(reason: String) {
        val st = _ui.value
        if (st.phase != Phase.WAIT && st.phase != Phase.ACTIVE) return
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-video",
            "émission visio coupée — la RÉCEPTION CONTINUE : $reason " +
                "(cid ${st.cid.take(8)}…, pair ${st.peer.take(10)}…)"
        )
        // v0.2.59 — SEULE L'ÉMISSION meurt : la caméra locale est coupée mais
        // le flux du pair CONTINUE d'être décodé et affiché (terrain 28/10 :
        // « visio tel => pc : je n'ai pas eu de video, idem dans l'autre
        // sens » — la caméra perdue tuait AUSSI la vue distante).
        runCatching { video?.stopSendingOnly() }
        _ui.value = _ui.value.copy(videoSendLost = true)
    }

    /** Termine et nettoie ; l'écran affiche [reason] 3 s puis revient à IDLE. */
    private fun end(reason: String) {
        // v0.2.62 — le SURVEILLANT DE SONNERIE meurt avec l'appel (fin,
        // refus, garde-fou 120 s — jamais de lecteur fantôme sur la socket).
        stopRingByeWatch(join = false)
        // v0.2.59 — la fin d'appel est JOURNALISÉE (miroir bureau « appel
        // terminé (phase) — motif ») : les exports terrain ne montraient JAMAIS
        // pourquoi un appel s'arrêtait côté téléphone.
        net.nyx.messenger.diag.DiagLog.i(
            "nyx-call",
            "appel terminé (${_ui.value.phase}) — $reason (cid ${_ui.value.cid.take(8)}…, " +
                "pair ${_ui.value.peer.take(10)}…)"
        )
        // v0.2.60 — mémorise le cid terminé (un doublon de livraison flotte de
        // son offre ne re-sonnera PAS — miroir bureau) + arrêt du partage
        // d'écran éventuel + reset de la capacité pair.
        val endedCid = _ui.value.cid
        if (endedCid.isNotEmpty()) {
            synchronized(endedCids) {
                endedCids[endedCid] = nowMs()
                endedCids.entries.removeIf { nowMs() - it.value > 600_000L }
            }
        }
        runCatching { net.nyx.messenger.p2p.screenshare.ScreenShare.stop() }
        peerScreenProtocol = false
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
        // v0.2.44 — moitiés relais orphelines (l'autre bout n'est jamais
        // venu ou l'appel s'achève avant l'appariement).
        synchronized(relaySockets) {
            relaySockets.forEach { s -> runCatching { s.close() } }
            relaySockets.clear()
        }
        val peer = _ui.value.peer
        net.nyx.messenger.notify.CallRinger.stop()
        // v0.2.58 — toute fin d'appel coupe AUSSI le retour d'appel (refus,
        // timeout, erreur, raccroché — filet identique à la sonnerie).
        net.nyx.messenger.notify.CallRingback.stop()
        net.nyx.messenger.notify.ContactNotifier.cancelCall(peer)
        // v0.2.61 — reset de la reconnexion + notification d'appel en cours
        // retirée (plus de cycle fantôme au prochain appel).
        reconnecting = false
        reconnectsLeft = 0
        interopAdpcm = false
        net.nyx.messenger.notify.ContactNotifier.cancelOngoingCall()
        val wasActive = _ui.value.phase == Phase.ACTIVE
        _ui.value = _ui.value.copy(phase = Phase.ENDED, message = reason, muted = false, speaker = false,
            videoEnabled = false,
            // v0.2.53 — la présentation ne survit pas à l'appel.
            videoLocalRot = 0, videoLocalW = 0, videoLocalH = 0, videoLocalFront = true,
            videoPeerRot = 0, videoPeerW = 0, videoPeerH = 0,
            // v0.2.59 — l'état d'émission ne survit pas non plus à l'appel.
            videoSendLost = false)
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

    /**
     * v0.2.48 — 1ᵉʳ essai média : couvre le rendezvous onion À FROID (2
     * circuits complets, terrain 24/10 : 15-35 s) — les essais suivants
     * gardent [CONNECT_ATTEMPT_MS] (circuit client déjà chaud). 45 s < au
     * délai d'ouverture du relais (60 s) : le direct garde la priorité.
     */
    private const val FIRST_ATTEMPT_MS = 45_000
    private const val WAITED_TTL_MS = 90_000L      // connexion gardée avant l'offre
    // v0.2.44 — repli relais : ouverture du canal relais après 60 s de direct
    // infructueux (assez tôt pour que l'appelé s'y branche dès son acceptation,
    // assez tard pour laisser le direct une vraie chance en début de fenêtre).
    private const val RELAY_OPEN_AFTER_MS = 60_000L
    // v0.2.26 — anti-état collant. v0.2.27 : WAIT 60 s → 180 s — l'appelé ne
    // doit PLUS raccrocher avant la fenêtre appelant (150 s) + HELLO/answer,
    // sinon il tue l'appel que l'appelant est en train d'établir (terrain :
    // « décrocher » puis rien — l'appelé finissait AVANT l'appelant).
    private const val WAIT_WATCHDOG_MS = 180_000L  // WAIT sans média → fin (BYE au pair)
    private const val GLOBAL_CALL_CAP_MS = 240_000L // phases transitoires > 4 min (filet global)
    private const val ACTIVE_CALL_CAP_MS = 7_200_000L // v0.2.42 : ACTIVE > 2 h (anti-collé uniquement)
    // v0.2.61 — reconnexion média (terrain 29/10 : circuits morts à 7-19 s)
    private const val RECONNECT_BUDGET = 2          // cycles de reconnexion par appel
    private const val RECONNECT_TRIES = 2           // essais de connexion par cycle
    private const val RECONNECT_ATTEMPT_MS = 12_000  // essai individuel (circuit tiède)
    private const val RECONNECT_GRACE_MS = 60_000L   // grâce de l'appelé sans retour du connecteur
}
