package net.nyx.messenger.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
 */
class CallVideo(
    private val context: Context,
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
    /** v0.2.53 — la rotation LOCALE a changé (l'aperçu et l'état UI suivent). */
    private val onLocalRotation: (Int) -> Unit = {},
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
     */
    fun sendCurrentRotation() {
        if (!transmit || stopped) return
        runCatching { write(P2pProtocol.F_VIDEO_ROT, VideoRot.encodeFrame(currentRot)) }
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
                    (if (transmit) " — F_VIDEO_ROT au pair, matrice de présentation changée" else "")
            )
            onLocalRotation(newRot)
            if (transmit && !stopped) {
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
                if (!stopped) onFatal("vidéo (émission) : ${e.message}")
            }
        }, "nyx-video-enc")
        t.isDaemon = true
        t.start()
        threads.add(t)
    }

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
        // v0.2.48 — HANDLER EXPLICITE (terrain 24/10 08:49 : « caméra
        // indisponible — visio audio-only : No handler given, and current
        // thread has no looper! ») : openCamera exige un thread POURVU d'un
        // Looper pour livrer ses callbacks ; le thread média n'en a pas.
        // Un HandlerThread dédié (créé une fois, libéré dans stopCamera)
        // remplace le main thread — AUCUN callback caméra ne touche l'UI.
        val handler = ensureCameraHandler()
        mgr.openCamera(pick.id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped) { runCatching { device.close() }; return }
                camera = device
                try {
                    val targets = mutableListOf<Surface>(encoderSurface)
                    preview?.let { targets.add(it) }
                    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (stopped) { runCatching { s.close() }; return }
                            session = s
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(encoderSurface)
                                preview?.let { addTarget(it) }
                                set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            s.setRepeatingRequest(req.build(), null, handler)
                            cameraOn = true
                            android.util.Log.i("nyx-video", "caméra active (visio en émission)")
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            onFatal("vidéo : session caméra refusée")
                        }
                    }, handler)
                } catch (e: Exception) {
                    if (!stopped) onFatal("vidéo : ${e.message}")
                }
            }

            override fun onDisconnected(device: CameraDevice) { runCatching { device.close() } }
            override fun onError(device: CameraDevice, error: Int) {
                if (!stopped) onFatal("vidéo : caméra en erreur ($error)")
            }
        }, handler)
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

    /** Attache/détache la surface d'APERÇU local (TextureView de l'overlay). */
    fun setPreviewSurface(s: Surface?) {
        synchronized(lock) {
            if (preview == s) return
            preview = s
            if (camera != null && !stopped) {
                // Re-crée la session avec/sans aperçu.
                stopCamera()
                encSurface?.let { runCatching { openCamera(it) } }
            }
        }
    }

    /**
     * Démarre le DÉCODEUR H.264 avec le csd du pair (SPS+PPS concaténés) —
     * les trames arrivent via [feedDecoder] (routage [CallAudio.videoSink]).
     * v0.2.53 — SANS rotation : la présentation (rotation du pair) est portée
     * par la VUE de l'UI (matrice TextureView) : le support décodeur de
     * KEY_ROTATION est inégal selon les appareils, et changer de rotation en
     * cours d'appel n'exige ainsi AUCUN redémarrage de décodeur.
     */
    fun startReceiving(peerCsd: ByteArray) {
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

    /** Appelé par la boucle de lecture ([CallAudio.videoSink]) pour chaque F_VIDEO. */
    fun feedDecoder(h264: ByteArray) {
        val dec = synchronized(lock) { decoder } ?: return
        try {
            var inIdx = dec.dequeueInputBuffer(10_000)
            if (inIdx < 0) return
            val ib = dec.getInputBuffer(inIdx) ?: return
            ib.clear(); ib.put(h264)
            dec.queueInputBuffer(inIdx, 0, h264.size, System.nanoTime() / 1000, 0)
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

    /** Coupe/démarre l'ÉMISSION caméra (bouton visio de l'overlay). */
    fun toggleCamera() {
        if (cameraOn) {
            stopCamera()
            cameraOn = false
        } else {
            val s = encSurface ?: return
            runCatching { openCamera(s) }
        }
    }

    private fun stopCamera() {
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
    }
}
