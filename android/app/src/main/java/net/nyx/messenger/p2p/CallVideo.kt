package net.nyx.messenger.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * v0.2.20 — MOTEUR VIDÉO D'APPEL (demande opérateur : « l'appel visio » —
 * ADR-015 prolongé au média vidéo).
 *
 * ÉMISSION : Camera2 (caméra FRONT si présente) → surface d'entrée d'un
 * encodeur MediaCodec H.264 (≈320×240 @ 15 fps, 150 kbps — format choisi
 * PARMI LES TAILLES supportées par la caméra pour éviter tout refus de
 * session) → trames [P2pProtocol.F_VIDEO] sur le canal direct P2P (le MÊME
 * socket que l'audio — l'entrelacement est borné par le verrou d'écriture de
 * [P2pCallConnection]). Le csd (SPS+PPS concaténés) part dans le HELLO /
 * F_CALL_ACCEPT — le décodeur du pair en a besoin AVANT la 1ʳᵉ trame.
 *
 * RÉCEPTION : les trames F_VIDEO sont routées par la boucle de lecture
 * ([CallAudio.videoSink]) vers [feedDecoder] ; le décodeur H.264 rend dans la
 * surface distante fournie par l'UI ([setRemoteSurface] — configuré au premier
 * appel, recréé si la surface change).
 *
 * v0.2.53 — ORIENTATION SUIVIE (terrain 25/10 08:41 : « l'image video a une
 * orientation d'écran figée ») : l'image partait en orientation CAPTEUR (couchée
 * à 90° sur un téléphone en portrait) et ne suivait jamais le téléphone.
 * La rotation est une MÉTADONNÉE DE PRÉSENTATION : le flux H.264 ne change
 * PAS (aucun redémarrage d'encodeur, aucune coupure), l'émetteur calcule
 * [VideoRot.uprightDegrees] (orientation capteur + rotation courante de
 * l'écran), la porte dans le HELLO ([VideoSendInfo]) puis la SUIT
 * (DisplayListener anti-rebond 600 ms) et envoie une trame
 * [P2pProtocol.F_VIDEO_ROT] à chaque changement — le pair tourne sa VUE
 * (matrice TextureView, cf. [VideoFit]) sans toucher au décodeur. Un pair
 * ≤ v0.2.52 ignore silencieusement ces trames (interop garantie).
 *
 * PRIVATÉ : rien ne part avant acceptation explicite ([transmit], comme
 * l'audio — pas de caméra à l'insu) ; le transport est l'onion direct (Tor
 * chiffre de bout en bout). AUCUNE image n'est stockée, aucune image ne passe
 * par un réplica.
 *
 * Budget Tor : ≈19 Ko/s par sens — l'audio (3 Ko/s) reste prioritaire ; la
 * vidéo se dégrade en gel (trames perdues) plutôt qu'en coupure.
 *
 * v0.2.55 — MODE MJPEG (interop visio PC↔téléphone) : le bureau ne sait NI
 * encoder NI décoder le H.264 en pur Java → codec commun MJPEG (JPEG complet
 * par trame, auto-contenu — une trame perdue ne corrompt rien, miroir des
 * blocs ADPCM fermes). ÉMISSION : caméra → ImageReader YUV_420_888 → NV21 →
 * YuvImage.compressToJpeg (q~55, ~10-20 Ko à 320×240) → trame F_VIDEO ; un
 * exécuteur mono-thread saute la trame si la précédente n'est pas partie
 * (write bloqué par Tor = gel, jamais de bourrage). RÉCEPTION : JPEG →
 * BitmapFactory → Canvas sur la surface distante (le lockCanvas marche sur
 * toute Surface). Le mode est choisi à la CONSTRUCTION ([useMjpeg] — appelé
 * d'un bureau) ou APRÈS l'ACCEPT ([switchSendingToMjpeg] — le bureau appelé
 * répond vc="mjpeg" : l'encodeur H.264 n'a encore rien émis, la caméra est
 * ré-utilisée). Téléphone↔téléphone : H.264 OCTET POUR OCTET inchangé.
 *
 * v0.2.57 — CAMÉA PERDUE = VISIO PERDUE, PAS APPEL PERDU : terrain 28/10
 * 11:49:59 (export v0.2.56) — visio MJPEG décrochée DEPUIS LA NOTIFICATION,
 * téléphone verrouillé, appli en arrière-plan : le système ferme la caméra
 * PENDANT la configuration de session → « CameraDevice was already closed »
 * jeté par onConfigured → setRepeatingRequest, NON ATTRAPÉ sur le thread
 * « nyx-cam » → FATAL EXCEPTION, process tué 0,6 s après le début de la
 * visio. Correction miroir du micro HS (v0.2.48/v0.2.56) : toute erreur de
 * caméra/encodeur DÉGRADE l'appel en audio-only via [onVideoLost] — AUCUNE
 * exception ne s'échappe plus des callbacks caméra, JAMAIS onFatal.
 */
class CallVideo(
    private val context: Context,
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
    /** v0.2.53 — la rotation LOCALE a changé (l'aperçu et l'état UI suivent). */
    private val onLocalRotation: (Int) -> Unit = {},
    /** v0.2.55 — mode MJPEG dès le départ (appelant = bureau : il ne fait QUE
     *  le MJPEG — pas d'encodeur H.264 à démarrer). */
    private val useMjpeg: Boolean = false,
    /** v0.2.57 — visio perdue en cours d'appel (caméra fermée/erreur) :
     *  L'APPEL CONTINUE EN AUDIO. Miroir du micro HS. */
    private val onVideoLost: (String) -> Unit = {},
) {

    /** v0.2.53 — résultat de [startSending] : csd + présentation locale. */
    data class VideoSendInfo(
        val csd: ByteArray?,   // null = pas de visio (permission/caméra/csd absent)
        val rotation: Int,     // rotation horaire qui rend l'image droite (0/90/180/270)
        val w: Int,            // largeur d'encodage
        val h: Int,            // hauteur d'encodage
        val front: Boolean = true, // v0.2.53 : caméra front (aperçu local en miroir)
    )

    /** v0.2.20 : aucune trame vidéo ne part avant acceptation explicite. */
    @Volatile var transmit = false

    /** v0.2.55 — mode MJPEG courant (émission ET réception). */
    @Volatile var mjpeg = useMjpeg
        private set

    /**
     * v0.2.58 — MJPEG PRÉ-TOURNÉ (bureau ≥ v0.3.10 : ACCEPT « vpr=1 », ou
     * appelant bureau — toute version — côté appelé) : la trame est tournée
     * À L'ENCODE ([Nv21Rot]) — portrait 240x320 ↔ paysage 320x240, la
     * RÉSOLUTION SUIT l'orientation (terrain 28/10 : « toujours un problème
     * d'orientation de la video et de resolution qui devrait s'adapter à
     * l'orientation »). Plus de F_VIDEO_ROT : l'aspect vit dans le JPEG.
     */
    @Volatile var mjpegPreRotated = false
        private set

    /** v0.2.59 — GARDE DE GÉNÉRATION CAMÉRA : chaque openCamera/stopCamera
     *  incrémente [camGen] ; les callbacks asynchrones (onOpened/onConfigured/
     *  onDisconnected/onError) d'une ouverture PÉRIMÉE sont IGNORÉS. Terrain
     *  28/10 17:24 : la bascule H.264→MJPEG (stopCamera+openCamera) et le
     *  rattachement de l'aperçu UI (setPreviewSurface → stopCamera+openCamera)
     *  se COURSAIENT — le onConfigured de la session morte exécutait
     *  setRepeatingRequest sur une surface libérée → « CaptureRequest contains
     *  unconfigured Input/Output Surface! » → visio coupée d'office. */
    private val camGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** v0.2.59 — retries caméra (erreurs TRANSITOIRES uniquement) : la 1ʳᵉ
     *  configuration qui échoue sur une erreur de course est REJOUÉE une fois
     *  400 ms plus tard AVANT de dégrader la visio (terrain 28/10 : « CameraDevice
     *  was already closed » 0,4 s après le début de la visio). */
    @Volatile private var camRetries = 0

    /** Caméra active (émission en cours). */
    @Volatile var cameraOn = false
        private set

    @Volatile var stopped = false
        private set

    private val lock = Any()
    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** Caméra choisie : identifiant + orientation capteur + face (v0.2.53). */
    private class CamPick(val id: String, val sensorDeg: Int, val front: Boolean)

    private var camPick: CamPick? = null

    /** v0.2.53 — rotation de présentation courante (calculée au démarrage puis suivie). */
    @Volatile private var currentRot = 0

    /** v0.2.48 — Handler des callbacks caméra (HandlerThread « nyx-cam ») :
     *  le thread média n'a PAS de Looper (« No handler given… » terrain 24/10). */
    private var cameraHandler: android.os.Handler? = null
    private var cameraThread: android.os.HandlerThread? = null

    /** v0.2.53 — écoute de la rotation de l'écran (même looper que la caméra). */
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    private var preview: Surface? = null
    private var decoder: MediaCodec? = null
    private var remoteSurface: Surface? = null
    private var peerCsd: ByteArray = ByteArray(0)
    private val threads = mutableListOf<Thread>()

    // ------------------------- v0.2.55 — mode MJPEG -------------------------

    /** Lecteur YUV de la caméra (mode MJPEG — remplace la surface encodeur). */
    private var mjpegReader: ImageReader? = null

    /** Exécuteur mono-thread compression+write (gel si le circuit est lent). */
    private var mjpegExecutor: java.util.concurrent.ExecutorService? = null
    private val mjpegBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Démarre la caméra + l'encodeur. Renvoie le csd H.264 (SPS+PPS
     * concaténés) et la présentation (rotation/taille), ou un csd null si la
     * caméra est indisponible (permission refusée, pas de caméra, occupation)
     * — l'appel continue en AUDIO-ONLY sans erreur.
     */
    fun startSending(): VideoSendInfo {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.i("nyx-video", "permission caméra absente — visio audio-only")
            return VideoSendInfo(null, 0, 0, 0)
        }
        return try {
            // v0.2.55 — MODE MJPEG (appelant = bureau) : pas d'encodeur H.264,
            // ImageReader YUV → JPEG. Le csd est VIDE (pas de paramètres de
            // décodeur en MJPEG) mais NON-NULL (la visio est ACTIVE).
            if (useMjpeg) return startSendingMjpeg()
            // v0.2.52 — L'ORDRE EST INVERSÉ : la caméra démarre AVANT la lecture
            // du csd. Terrain 25/10 : « visio annoncée dans le HELLO (csd 0 o) » —
            // l'encodeur à surface d'entrée n'expose le csd (outputFormat ou
            // buffer CODEC_CONFIG) qu'APRÈS la première trame caméra ; le lire
            // AVANT rendait un csd VIDE et l'appelé dégradait l'appel en
            // audio-only (même classe de bug que le csd-0 AAC v0.2.51).
            val enc = createEncoder()
            encSurface = enc.surface
            android.util.Log.i(
                "nyx-video",
                "encodeur H.264 démarré (${enc.w}x${enc.h}) — ouverture caméra puis lecture du csd"
            )
            openCamera(enc.surface)
            val csd = awaitRealCsd(enc.codec)
            if (!VideoCsd.looksValid(csd)) {
                android.util.Log.w(
                    "nyx-video",
                    "csd H.264 TOUJOURS VIDE après ${CSD_WAIT_MS / 1000} s " +
                        "(caméra sans trame ?) — visio AUDIO-ONLY explicite"
                )
                stopCamera()
                stopEncoder(enc.codec)
                VideoSendInfo(null, 0, 0, 0)
            } else {
                android.util.Log.i(
                    "nyx-video",
                    "csd H.264 LU APRÈS LA 1ʳᵉ TRAME (${csd.size} o) — encodeur prêt, caméra active"
                )
                // v0.2.53 — présentation initiale : rotation qui rend l'image
                // DROITE pour la rotation D'ÉCRAN courante (terrain : l'image
                // partait couchée à 90° et ne bougeait plus jamais).
                currentRot = computeUprightRotation()
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-video",
                    "orientation initiale ${currentRot}° (capteur ${camPick?.sensorDeg ?: "?"}°, " +
                        "caméra ${if (camPick?.front == true) "front" else "arrière"}, " +
                        "écran ${currentDisplayRotationDeg()}°) — suit désormais le téléphone"
                )
                onLocalRotation(currentRot)
                startRotationTracking()
                startEncDrain(enc.codec)
                VideoSendInfo(csd, currentRot, enc.w, enc.h, camPick?.front ?: true)
            }
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "caméra indisponible — visio audio-only : ${e.message}")
            stopCamera()
            VideoSendInfo(null, 0, 0, 0)
        }
    }

    /**
     * v0.2.53 — Envoie la rotation COURANTE au pair (F_VIDEO_ROT). Appelé par
     * CallManager juste après `transmit = true` : garantit la synchronisation
     * même si le téléphone a tourné pendant la sonnerie/la négociation.
     * v0.2.58 — NO-OP en MJPEG pré-tourné : la trame EST droite (l'aspect vit
     * dans le JPEG — une métadonnée ferait double-rotation chez le pair).
     */
    fun sendCurrentRotation() {
        if (!transmit || stopped) return
        if (mjpeg && mjpegPreRotated) return
        runCatching { write(P2pProtocol.F_VIDEO_ROT, VideoRot.encodeFrame(currentRot)) }
    }

    /* ------------------------- v0.2.55 — émission MJPEG ------------------------- */

    /**
     * ÉMISSION MJPEG : ImageReader YUV_420_888 → NV21 → YuvImage.compressToJpeg
     * → trame F_VIDEO. L'exécuteur mono-thread saute la trame si la précédente
     * n'est pas encore partie (write bloqué par Tor = gel naturel, jamais de
     * bourrage du circuit). Renvoie un csd VIDE non-null (visio active, aucun
     * paramètre de décodeur à transporter — miroir bureau).
     */
    private fun startSendingMjpeg(): VideoSendInfo {
        return try {
            val (w, h) = pickCaptureSize()
            val handler = ensureCameraHandler()
            val reader = ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                if (stopped || sendingStopped) return@setOnImageAvailableListener
                if (!mjpegBusy.compareAndSet(false, true)) {
                    // La trame précédente est encore en compression/émission :
                    // on JETTE les périmées (acquireLatestImage vide la file).
                    runCatching { r.acquireLatestImage()?.close() }
                    return@setOnImageAvailableListener
                }
                try {
                    mjpegExecutor?.execute {
                        try {
                            val img = r.acquireLatestImage() ?: return@execute
                            try {
                                if (transmit && !stopped) {
                                    val jpeg = compressJpeg(img)
                                    if (jpeg != null) write(P2pProtocol.F_VIDEO, jpeg)
                                }
                            } finally {
                                img.close()
                            }
                        } catch (e: Exception) {
                            if (!stopped) android.util.Log.w("nyx-video", "trame MJPEG sautée : ${e.message}")
                        } finally {
                            mjpegBusy.set(false)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // v0.2.59 — exécuteur arrêté (stopSendingOnly) : trame jetée,
                    // jamais de crash sur le thread caméra.
                    mjpegBusy.set(false)
                }
            }, handler)
            mjpegReader = reader
            mjpegExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "nyx-video-mj").apply { isDaemon = true }
            }
            openCamera(reader.surface)
            currentRot = computeUprightRotation()
            // v0.2.58 — PRÉ-ROTATION À L'ENCODE ([Nv21Rot]) : la trame part
            // DROITE et sa RÉSOLUTION SUIT l'orientation (portrait 240x320 ↔
            // paysage 320x240 — terrain 28/10 : « toujours un problème
            // d'orientation de la video et de resolution qui devrait s'adapter
            // à l'orientation »). L'annonce est permutée, vrot=0 : un bureau
            // ≥ v0.3.10 (vpr) rend par aspect de trame ; un bureau ≤ v0.3.9
            // lit vrot=0 + vw/vh permutés — DROIT dans les DEUX cas.
            mjpegPreRotated = true
            val outW = if (currentRot == 90 || currentRot == 270) h else w
            val outH = if (currentRot == 90 || currentRot == 270) w else h
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-video",
                "visio MJPEG démarrée PRÉ-TOURNÉE (${outW}x${outH} " +
                    "${if (outW < outH) "portrait" else "paysage"}, YUV→JPEG q$MJPEG_QUALITY — " +
                    "la résolution suit l'orientation) — interop bureau"
            )
            onLocalRotation(currentRot)
            startRotationTracking()
            VideoSendInfo(ByteArray(0), 0, outW, outH, camPick?.front ?: true)
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "caméra indisponible (MJPEG) — visio audio-only : ${e.message}")
            stopCamera()
            VideoSendInfo(null, 0, 0, 0)
        }
    }

    /**
     * v0.2.55 — L'APPELÉ est un bureau (ACCEPT.vc="mjpeg") : l'encodeur H.264
     * n'a encore RIEN émis (transmit était faux) — on l'arrête, on ré-utilise
     * la caméra vers l'ImageReader YUV, la réception passe en JPEG. Miroir du
     * switchSendingToAdpcm audio (v0.2.54).
     *
     * v0.2.58 — [preRotated] = le bureau a accordé la pré-rotation dans son
     * ACCEPT (vpr="1", bureau ≥ v0.3.10) : les trames partent DROITES et
     * suivent l'orientation SANS F_VIDEO_ROT. Un bureau ancien ne l'accorde
     * pas : comportement historique (métadonnée de rotation) inchangé.
     */
    fun switchSendingToMjpeg(preRotated: Boolean = false) {
        if (mjpeg || stopped) return
        mjpeg = true
        mjpegPreRotated = preRotated
        // L'encodeur H.264 et son drain meurent (aucune trame n'est partie —
        // transmit n'a jamais été vrai à ce stade).
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encSurface?.release() }
        encSurface = null
        runCatching { decoder?.stop(); decoder?.release() }
        decoder = null
        // Caméra → ImageReader YUV (même session ré-créée, miroir setPreviewSurface).
        stopCamera()
        try {
            val (w, h) = pickCaptureSize()
            val handler = ensureCameraHandler()
            val reader = ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                if (stopped || sendingStopped) return@setOnImageAvailableListener
                if (!mjpegBusy.compareAndSet(false, true)) {
                    runCatching { r.acquireLatestImage()?.close() }
                    return@setOnImageAvailableListener
                }
                try {
                    mjpegExecutor?.execute {
                        try {
                            val img = r.acquireLatestImage() ?: return@execute
                            try {
                                if (transmit && !stopped) {
                                    val jpeg = compressJpeg(img)
                                    if (jpeg != null) write(P2pProtocol.F_VIDEO, jpeg)
                                }
                            } finally {
                                img.close()
                            }
                        } catch (e: Exception) {
                            if (!stopped) android.util.Log.w("nyx-video", "trame MJPEG sautée : ${e.message}")
                        } finally {
                            mjpegBusy.set(false)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // v0.2.59 — exécuteur arrêté : trame jetée, jamais de crash.
                    mjpegBusy.set(false)
                }
            }, handler)
            mjpegReader = reader
            if (mjpegExecutor == null) {
                mjpegExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                    Thread(r, "nyx-video-mj").apply { isDaemon = true }
                }
            }
            openCamera(reader.surface)
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-video",
                "codec vidéo MJPEG négocié — émission basculée (caméra ré-utilisée, ${w}x${h}" +
                    (if (preRotated) ", PRÉ-TOURNÉE vpr — la résolution suivra l'orientation" else "") + ")"
            )
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "bascule MJPEG impossible : ${e.message} — visio coupée côté émission")
        }
    }

    /** Image YUV_420_888 → NV21 (VU entrelacé, gère rowStride/pixelStride). */
    private fun yuv420ToNv21(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val ySize = w * h
        val nv21 = ByteArray(ySize + ySize / 2)
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]
        val yBuf = yPlane.buffer.duplicate()
        if (yPlane.rowStride == w && yPlane.pixelStride == 1) {
            yBuf.position(0)
            yBuf.get(nv21, 0, ySize)
        } else {
            var off = 0
            for (row in 0 until h) {
                for (col in 0 until w) {
                    nv21[off++] = yBuf.get(row * yPlane.rowStride + col * yPlane.pixelStride)
                }
            }
        }
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        var off = ySize
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[off++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[off++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        return nv21
    }

    /** NV21 → JPEG (null si compression impossible). v0.2.58 — PRÉ-ROTATION
     *  quand le mode MJPEG pré-tourné est actif ([mjpegPreRotated]) : la
     *  trame part droite, sa résolution suit l'orientation (portrait ↔ paysage). */
    private fun compressJpeg(img: Image): ByteArray? {
        var nv21 = yuv420ToNv21(img)
        var w = img.width
        var h = img.height
        if (mjpegPreRotated) {
            val (rotated, dims) = Nv21Rot.rotateIfNeeded(nv21, w, h, currentRot)
            nv21 = rotated
            w = dims[0]
            h = dims[1]
        }
        // YuvImage(strides=null : calcule depuis les dimensions).
        val yuv = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, w, h, null
        )
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        if (!yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), MJPEG_QUALITY, out)) {
            return null
        }
        return out.toByteArray()
    }

    /* ------------------- v0.2.53 — rotation de présentation ------------------- */

    /** Rotation de l'écran principal en degrés (0/90/180/270 — 0 si indisponible). */
    private fun currentDisplayRotationDeg(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return 0
        val disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 0
        return runCatching { VideoRot.displayRotationToDeg(disp.rotation) }.getOrDefault(0)
    }

    /** Rotation qui rend l'image droite, pour la rotation d'écran COURANTE. */
    private fun computeUprightRotation(): Int {
        val pick = camPick ?: return 0
        return VideoRot.uprightDegrees(pick.sensorDeg, currentDisplayRotationDeg(), pick.front)
    }

    /** Applique la rotation d'écran courante (appelé avec anti-rebond). */
    private val applyRotation = Runnable {
        if (stopped) return@Runnable
        val newRot = computeUprightRotation()
        if (newRot != currentRot) {
            val old = currentRot
            currentRot = newRot
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-video",
                "orientation suivie : ${old}° → ${newRot}° (écran tourné)" +
                    (if (mjpeg && mjpegPreRotated) " — PRÉ-ROTATION : la prochaine trame suivra (JPEG auto-descriptif, pas de F_VIDEO_ROT)"
                     else if (transmit) " — F_VIDEO_ROT au pair, matrice de présentation changée" else "")
            )
            onLocalRotation(newRot)
            // v0.2.58 — en MJPEG PRÉ-TOURNÉ, la rotation est DANS la trame
            // suivante (compressJpeg lit [currentRot]) : aucune métadonnée
            // à émettre (elle ferait double-rotation chez le pair).
            if (!(mjpeg && mjpegPreRotated) && transmit && !stopped) {
                runCatching { write(P2pProtocol.F_VIDEO_ROT, VideoRot.encodeFrame(newRot)) }
            }
        }
    }

    /** Enregistre le suivi de rotation (looper caméra, anti-rebond 600 ms). */
    private fun startRotationTracking() {
        if (displayListener != null) return
        val handler = ensureCameraHandler()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            // La rotation de l'écran arrive par onDisplayChanged (le Display
            // principal) — pas de callback dédié sur DisplayListener.
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                // Anti-rebond : le passage portrait→paysage passe par des états
                // intermédiaires ; on ne retient que la position STABLE.
                handler.removeCallbacks(applyRotation)
                handler.postDelayed(applyRotation, ROT_DEBOUNCE_MS)
            }

            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        runCatching { dm.registerDisplayListener(listener, handler) }
        displayListener = listener
    }

    private fun stopRotationTracking() {
        val listener = displayListener ?: return
        displayListener = null
        cameraHandler?.removeCallbacks(applyRotation)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return
        runCatching { dm.unregisterDisplayListener(listener) }
    }

    /**
     * v0.2.52 — Attente BORNÉE du VRAI csd : INFO_OUTPUT_FORMAT_CHANGED avec
     * csd-0/csd-1 non vides, OU premier buffer CODEC_CONFIG (certains
     * encodeurs ne passent que par là). Les trames ordinaires rencontrées en
     * chemin sont relâchées. Vide = timeout (≈ caméra muette).
     */
    private fun awaitRealCsd(enc: MediaCodec): ByteArray {
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + CSD_WAIT_MS
        while (System.currentTimeMillis() < deadline && !stopped) {
            val outIdx = enc.dequeueOutputBuffer(info, 50_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = enc.outputFormat
                val csd = VideoCsd.fromFormat(
                    of.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } },
                    of.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                )
                if (VideoCsd.looksValid(csd)) return csd
                // format exposé SANS csd (C2 précoce) : la suite viendra par
                // le buffer CODEC_CONFIG — on continue d'attendre.
            } else if (outIdx >= 0) {
                val ob = enc.getOutputBuffer(outIdx)
                if (ob != null && VideoCsd.isCodecConfig(info.flags) && info.size > 0) {
                    val csd = ByteArray(info.size).also { ob.position(info.offset); ob.get(it) }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (VideoCsd.looksValid(csd)) return csd
                } else {
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        }
        return ByteArray(0)
    }

    private fun stopEncoder(enc: MediaCodec) {
        runCatching { enc.stop() }
        runCatching { enc.release() }
    }

    /** Encodeur configuré : codec + surface d'entrée + taille effective. */
    private class EncReady(val codec: MediaCodec, val surface: Surface, val w: Int, val h: Int)

    private fun createEncoder(): EncReady {
        val (w, h) = pickCaptureSize()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_INTERVAL_S)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        // v0.2.52 — NI attente de csd NI drain ici : l'encodeur à surface
        // d'entrée ne produira rien avant la première trame caméra (le csd
        // est lu par [awaitRealCsd] APRÈS openCamera, le drain par
        // [startEncDrain] une fois le csd acquis).
        return EncReady(enc, surface, w, h)
    }

    /** Thread de drain (démarré quand le csd est acquis) : chaque access
     *  unit codée part en F_VIDEO — les buffers CODEC_CONFIG ne partent
     *  JAMAIS en trame (le csd voyage déjà dans HELLO/F_CALL_ACCEPT). */
    private fun startEncDrain(enc: MediaCodec) {
        val t = Thread({
            val bufInfo = MediaCodec.BufferInfo()
            try {
                while (!stopped) {
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, 100_000)
                    if (outIdx >= 0 && bufInfo.size > 0) {
                        val ob = enc.getOutputBuffer(outIdx)
                        if (ob != null && transmit && !stopped &&
                            !VideoCsd.isCodecConfig(bufInfo.flags)
                        ) {
                            val payload = ByteArray(bufInfo.size)
                                .also { ob.position(bufInfo.offset); ob.get(it) }
                            write(P2pProtocol.F_VIDEO, payload)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    }
                }
            } catch (e: Exception) {
                // v0.2.57 — émission perdue ≠ appel perdu (miroir micro HS).
                // v0.2.59 — sauf si l'émission a été coupée VOLONTAIREMENT
                // ([stopSendingOnly]) : la réception continue, pas de dégradation.
                if (!stopped && !sendingStopped) onVideoLost("encodeur : ${e.message ?: e::class.simpleName}")
            }
        }, "nyx-video-enc")
        t.isDaemon = true
        t.start()
        threads.add(t)
    }

    /**
     * v0.2.59 — COUPE L'ÉMISSION SEULE (caméra + encodeur + lecteur MJPEG) :
     * la RÉCEPTION du flux du pair CONTINUE (miroir exact du micro HS —
     * « émission coupée, réception maintenue »). Terrain 28/10 17:24-17:25 :
     * la caméra perdue tuait AUSSI la vue du pair — l'appelé ne voyait PLUS
     * la visio entrante alors que le bureau continuait d'envoyer son écran
     * (« je n'ai pas eu de video, idem dans l'autre sens »). Le décodeur, la
     * surface distante et [feedDecoder] restent EN PLACE : seules les trames
     * LOCALES cessent de partir.
     */
    fun stopSendingOnly() {
        if (stopped) return
        // Les erreurs tardives du drain/encodeur ne doivent PLUS dégrader la
        // réception : l'émission est déjà déclarée morte volontairement.
        sendingStopped = true
        stopRotationTracking()
        stopCamera()
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encSurface?.release() }
        encoder = null; encSurface = null
        runCatching { mjpegExecutor?.shutdownNow() }
        mjpegExecutor = null
        runCatching { mjpegReader?.close() }
        mjpegReader = null
    }

    /** v0.2.59 — émission volontairement arrêtée : les erreurs d'encodeur
     *  tardives n'invoquent plus [onVideoLost] (la réception continue). */
    @Volatile private var sendingStopped = false

    /** Taille caméra/encodeur : la plus petite supportée ≥ 320×240 (bord 640). */
    private fun pickCaptureSize(): Pair<Int, Int> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val pick = pickCamera(mgr) ?: throw IllegalStateException("aucune caméra")
        val map = mgr.getCameraCharacteristics(pick.id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width in 240..640 && it.height in 180..480 }
            ?.sortedBy { it.width * it.height }
            ?: emptyList()
        val best = sizes.firstOrNull() ?: android.util.Size(320, 240)
        return best.width to best.height
    }

    /** Caméra FRONT en priorité (visio naturelle), sinon la première — avec son
     *  orientation capteur et sa face (v0.2.53 : calcul de la présentation). */
    private fun pickCamera(mgr: CameraManager): CamPick? {
        val ids = mgr.cameraIdList
        val chosen = ids.firstOrNull {
            runCatching {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        } ?: ids.firstOrNull() ?: return null
        val ch = runCatching { mgr.getCameraCharacteristics(chosen) }.getOrNull() ?: return null
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return CamPick(chosen, sensor, front)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(encoderSurface: Surface) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val pick = pickCamera(mgr) ?: throw IllegalStateException("aucune caméra")
        camPick = pick
        camDesired = true
        // v0.2.59 — cette ouverture invalide toutes les précédentes : leurs
        // callbacks (session en cours de configuration, fermeture en vol) seront
        // ignorés par la comparaison de génération.
        val gen = camGen.incrementAndGet()
        // v0.2.48 — HANDLER EXPLICITE (terrain 24/10 08:49 : « caméra
        // indisponible — visio audio-only : No handler given, and current
        // thread has no looper! ») : openCamera exige un thread POURVU d'un
        // Looper pour livrer ses callbacks ; le thread média n'en a pas.
        // Un HandlerThread dédié (créé une fois, libéré dans stopCamera)
        // remplace le main thread — AUCUN callback caméra ne touche l'UI.
        val handler = ensureCameraHandler()
        mgr.openCamera(pick.id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped || gen != camGen.get()) { runCatching { device.close() }; return }
                camera = device
                try {
                    val targets = mutableListOf<Surface>(encoderSurface)
                    preview?.let { targets.add(it) }
                    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            // v0.2.57 — TOUT le corps est protégé : terrain
                            // 28/10 11:49:59, la caméra était fermée par le
                            // système (appli en arrière-plan) PENDANT la
                            // configuration → IllegalStateException NON
                            // ATTRAPÉE sur « nyx-cam » → FATAL EXCEPTION,
                            // process tué. Désormais : session refermée
                            // proprement, visio dégradée, l'appel vit.
                            try {
                                if (stopped || gen != camGen.get()) { runCatching { s.close() }; return }
                                session = s
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(encoderSurface)
                                    preview?.let { addTarget(it) }
                                    set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                }
                                s.setRepeatingRequest(req.build(), null, handler)
                                cameraOn = true
                                camRetries = 0
                                net.nyx.messenger.diag.DiagLog.i(
                                    "nyx-video", "caméra active (visio en émission)"
                                )
                            } catch (e: Exception) {
                                cameraOn = false
                                runCatching { s.close() }
                                // v0.2.59 — ERREUR TRANSITOIRE ? UNE relance avant
                                // d'abandonner (courses stopCamera/openCamera,
                                // périphérique juste refermé).
                                if (!stopped && gen == camGen.get() && isTransientCamError(e) && camRetries < 2) {
                                    camRetries++
                                    net.nyx.messenger.diag.DiagLog.w(
                                        "nyx-video",
                                        "configuration caméra échouée (${"" + e.message?.take(70)}) — relance ${camRetries}/2 dans 400 ms"
                                    )
                                    scheduleCameraRetry(encoderSurface)
                                } else {
                                    if (!stopped) onVideoLost(
                                        "caméra fermée pendant la configuration : " +
                                            "${e.message ?: e::class.simpleName}"
                                    )
                                }
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            // v0.2.57 — session refusée : visio coupée, appel maintenu.
                            cameraOn = false
                            runCatching { s.close() }
                            if (stopped || gen != camGen.get()) return
                            // v0.2.59 — une session refusée peut être une course
                            // (surface d'aperçu en cours de remplacement) : une
                            // RELANCE honnête avant de dégrader.
                            if (camRetries < 2) {
                                camRetries++
                                net.nyx.messenger.diag.DiagLog.w(
                                    "nyx-video", "session caméra refusée — relance ${camRetries}/2"
                                )
                                scheduleCameraRetry(encoderSurface)
                            } else if (!stopped) onVideoLost("session caméra refusée")
                        }
                    }, handler)
                } catch (e: Exception) {
                    // v0.2.57 — ouverture de session impossible : visio coupée,
                    // appel maintenu (miroir du micro HS v0.2.48).
                    cameraOn = false
                    runCatching { device.close() }
                    camera = null
                    if (!stopped) onVideoLost("${e.message ?: e::class.simpleName}")
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                // v0.2.57 — le système ferme la caméra (appli en arrière-plan,
                // autre appli caméra) : visio coupée, appel maintenu.
                runCatching { device.close() }
                // v0.2.59 — PÉRIMÉ ? une fermeture volontaire (stopCamera/
                // bascule) déclenche ce callback APRÈS qu'une NOUVELLE
                // ouverture a pris le relais : ne PAS tuer la nouvelle session.
                if (gen != camGen.get() || stopped) return
                camera = null
                cameraOn = false
                if (!stopped) onVideoLost("caméra déconnectée par le système")
            }

            override fun onError(device: CameraDevice, error: Int) {
                // v0.2.57 — caméra en erreur : visio coupée, appel maintenu.
                runCatching { device.close() }
                if (gen != camGen.get() || stopped) return
                camera = null
                cameraOn = false
                if (!stopped) onVideoLost("caméra en erreur ($error)")
            }
        }, handler)
    }

    /** v0.2.59 — erreurs caméra TRANSITOIRES (courses de reconfiguration) :
     *  une relance à 400 ms est légitime, la dégradation n'est pas encore
     *  méritée (terrain 28/10 : « unconfigured Input/Output Surface »,
     *  « CameraDevice was already closed », « Camera in use »). */
    private fun isTransientCamError(e: Exception): Boolean {
        val m = e.message ?: e::class.simpleName ?: return false
        return m.contains("unconfigured", ignoreCase = true) ||
            m.contains("already closed", ignoreCase = true) ||
            m.contains("in use", ignoreCase = true) ||
            m.contains("CameraDevice", ignoreCase = true)
    }

    /** v0.2.59 — relance différée de l'ouverture caméra (sur le handler caméra,
     *  après invalidation des ouvertures en vol). */
    private fun scheduleCameraRetry(surface: Surface) {
        val h = runCatching { ensureCameraHandler() }.getOrNull() ?: return
        camGen.incrementAndGet() // invalide les callbacks en vol AVANT la relance
        h.postDelayed({
            if (!stopped) {
                runCatching { stopCamera() }
                runCatching { openCamera(surface) }
            }
        }, 400L)
    }

    /** Handler unique du looper caméra (callbacks caméra + rotation d'écran). */
    private fun ensureCameraHandler(): android.os.Handler {
        if (cameraHandler == null) {
            val ht = android.os.HandlerThread("nyx-cam")
            ht.start()
            cameraThread = ht
            cameraHandler = android.os.Handler(ht.looper)
        }
        return requireNotNull(cameraHandler) { "handler caméra indisponible" }
    }

    /** Attache/détache la surface d'APERÇU local (TextureView de l'overlay).
     *  v0.2.59 — RE-CRÉATION DE SESSION DÉBOUNCÉE : le rattachement de l'aperçu
     *  arrive PRÉCISÉMENT pendant la configuration de la bascule H.264→MJPEG
     *  (terrain 28/10 : « CaptureRequest contains unconfigured Input/Output
     *  Surface! » 0,2 s après la bascule) — deux openCamera se coursaient.
     *  Désormais la reconfiguration est COALESCÉE sur le handler caméra avec
     *  250 ms de débounce : les attach/détach en rafale ne produisent QU'UNE
     *  reconfiguration, avec l'état FINAL des surfaces. */
    fun setPreviewSurface(s: Surface?) {
        synchronized(lock) {
            if (preview == s) return
            preview = s
            if (!stopped && (camera != null || camDesired)) {
                val h = runCatching { ensureCameraHandler() }.getOrNull()
                if (h != null) {
                    val pending = Runnable {
                        synchronized(lock) {
                            // camDesired = une ouverture est live OU en vol : la
                            // garde de génération rend la reconfiguration SÛRE
                            // même si elle concurrence une ouverture en cours.
                            if (!stopped && (camera != null || camDesired)) {
                                stopCamera()
                                captureSurface()?.let { runCatching { openCamera(it) } }
                            }
                        }
                    }
                    previewPending?.let { h.removeCallbacks(it) }
                    previewPending = pending
                    h.postDelayed(pending, 250L)
                } else {
                    stopCamera()
                    captureSurface()?.let { runCatching { openCamera(it) } }
                }
            }
        }
    }

    /** v0.2.59 — reconfiguration d'aperçu en attente (débounce 250 ms). */
    private var previewPending: Runnable? = null

    /** v0.2.59 — une ouverture caméra est DÉSIRÉE (live ou en vol) : guide le
     *  débounce d'aperçu — rattacher une surface pendant une ouverture en vol
     *  est SÛR grâce à la garde de génération. */
    @Volatile private var camDesired = false

    /**
     * Démarre le DÉCODEUR H.264 avec le csd du pair (SPS+PPS concaténés) —
     * les trames arrivent via [feedDecoder] (routage [CallAudio.videoSink]).
     * v0.2.53 — SANS rotation : la présentation (rotation du pair) est portée
     * par la VUE de l'UI (matrice TextureView) : le support décodeur de
     * KEY_ROTATION est inégal selon les appareils, et changer de rotation en
     * cours d'appel n'exige ainsi AUCUN redémarrage de décodeur.
     * v0.2.55 — MJPEG : csd VIDE (aucun paramètre de décodeur), pas de
     * MediaCodec — [feedDecoder] décode les JPEG et rend sur la surface.
     */
    fun startReceiving(peerCsd: ByteArray) {
        if (mjpeg) {
            synchronized(lock) { peerCsdBuf = ByteArray(0) }
            net.nyx.messenger.diag.DiagLog.i("nyx-video", "réception MJPEG prête (JPEG → Canvas)")
            return
        }
        synchronized(lock) { peerCsdBuf = peerCsd }
        reconfigureDecoder()
    }

    @Volatile private var peerCsdBuf: ByteArray = ByteArray(0)

    /** Surface distante (TextureView de l'overlay) — reconfigure le décodeur. */
    fun setRemoteSurface(s: Surface?) {
        synchronized(lock) {
            if (remoteSurface == s) return
            remoteSurface = s
        }
        reconfigureDecoder()
    }

    private fun reconfigureDecoder() {
        val surface: Surface?
        val csd: ByteArray
        synchronized(lock) {
            surface = remoteSurface ?: return
            csd = peerCsdBuf
            runCatching { decoder?.stop(); decoder?.release() }
            decoder = null
        }
        if (csd.isEmpty()) return // le csd du pair n'est pas encore connu
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                if (csd.isNotEmpty()) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            }
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, surface, null, 0)
            dec.start()
            synchronized(lock) { decoder = dec }
            android.util.Log.i("nyx-video", "décodeur H.264 prêt (rendu surface distante)")
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "décodeur impossible : ${e.message}")
        }
    }

    /** Appelé par la boucle de lecture ([CallAudio.videoSink]) pour chaque F_VIDEO.
     *  v0.2.55 — en MJPEG le payload est un JPEG COMPLET : décodage BitmapFactory
     *  puis rendu Canvas sur la surface distante (fit centré — la rotation du
     *  pair est portée par la MATRICE de la vue, comme en H.264). */
    fun feedDecoder(data: ByteArray) {
        if (mjpeg) { renderMjpeg(data); return }
        val dec = synchronized(lock) { decoder } ?: return
        try {
            var inIdx = dec.dequeueInputBuffer(10_000)
            if (inIdx < 0) return
            val ib = dec.getInputBuffer(inIdx) ?: return
            ib.clear(); ib.put(data)
            dec.queueInputBuffer(inIdx, 0, data.size, System.nanoTime() / 1000, 0)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = dec.dequeueOutputBuffer(info, 0)
                if (outIdx >= 0) dec.releaseOutputBuffer(outIdx, true) // rendu à la surface
                else break
            }
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "trame vidéo rejetée : ${e.message}")
        }
    }

    /** v0.2.55 — JPEG du bureau → Bitmap → Canvas (fit centré sur la surface). */
    private fun renderMjpeg(jpeg: ByteArray) {
        if (jpeg.size > MJPEG_MAX_FRAME_BYTES) return
        val bmp = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }.getOrNull() ?: return
        val s = synchronized(lock) { remoteSurface }
        if (s == null) { bmp.recycle(); return }
        runCatching {
            val canvas = s.lockCanvas(null)
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
                val scale = minOf(
                    canvas.width.toFloat() / bmp.width,
                    canvas.height.toFloat() / bmp.height
                )
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (canvas.width - dw) / 2f
                val top = (canvas.height - dh) / 2f
                canvas.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(left, top, left + dw, top + dh),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                )
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
        bmp.recycle()
    }

    /** Coupe/démarre l'ÉMISSION caméra (bouton visio de l'overlay). */
    fun toggleCamera() {
        if (cameraOn) {
            stopCamera()
            cameraOn = false
        } else {
            val s = captureSurface() ?: return
            runCatching { openCamera(s) }
        }
    }

    /** v0.2.55 — surface de CAPTURE courante : encodeur H.264 ou ImageReader MJPEG. */
    private fun captureSurface(): Surface? =
        synchronized(lock) { encSurface ?: mjpegReader?.surface }

    private fun stopCamera() {
        // v0.2.59 — invalide les ouvertures en vol : leurs callbacks seront
        // ignorés (garde de génération [camGen]).
        camGen.incrementAndGet()
        camDesired = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        // v0.2.48 — le HandlerThread caméra survit aux re-créations de session
        // (setPreviewSurface) ; il est arrêté uniquement au stop complet.
    }

    /** Arrêt complet (raccroché, erreur, écran détruit). */
    fun stop() {
        if (stopped) return
        stopped = true
        stopRotationTracking()
        stopCamera()
        // v0.2.48 — libère le thread des callbacks caméra (HandlerThread
        // « nyx-cam » créé dans openCamera — jamais sur le main).
        runCatching { cameraThread?.quitSafely() }
        cameraThread = null
        cameraHandler = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { encSurface?.release() }
        // v0.2.55 — mode MJPEG : lecteur YUV + exécuteur de compression.
        runCatching { mjpegExecutor?.shutdownNow() }
        mjpegExecutor = null
        runCatching { mjpegReader?.close() }
        mjpegReader = null
        encoder = null; decoder = null; encSurface = null
        preview = null; remoteSurface = null
    }

    companion object {
        const val BIT_RATE = 150_000
        const val FRAME_RATE = 15
        const val I_INTERVAL_S = 2

        /** v0.2.52 — attente max du csd réel (caméra → 1ʳᵉ trame → format). */
        const val CSD_WAIT_MS = 6_000

        /** v0.2.53 — anti-rebond du suivi d'orientation (ms). */
        const val ROT_DEBOUNCE_MS = 600L

        /** v0.2.55 — qualité JPEG de l'émission MJPEG (miroir bureau ~0.55). */
        const val MJPEG_QUALITY = 55

        /** v0.2.55 — poids max accepté d'une trame JPEG reçue (défense). */
        const val MJPEG_MAX_FRAME_BYTES = 512 * 1024
    }
}
