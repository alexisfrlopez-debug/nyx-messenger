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
) {

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

    /** v0.2.48 — Handler des callbacks caméra (HandlerThread « nyx-cam ») :
     *  le thread média n'a PAS de Looper (« No handler given… » terrain 24/10). */
    private var cameraHandler: android.os.Handler? = null
    private var cameraThread: android.os.HandlerThread? = null

    private var preview: Surface? = null
    private var decoder: MediaCodec? = null
    private var remoteSurface: Surface? = null
    private var peerCsd: ByteArray = ByteArray(0)
    private val threads = mutableListOf<Thread>()

    /**
     * Démarre la caméra + l'encodeur. Renvoie le csd H.264 (SPS+PPS concaténés)
     * ou null si la caméra est indisponible (permission refusée, pas de
     * caméra, occupation) — l'appel continue en AUDIO-ONLY sans erreur.
     */
    fun startSending(): ByteArray? {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.i("nyx-video", "permission caméra absente — visio audio-only")
            return null
        }
        return try {
            val (csd, surface, size) = createEncoder()
            encSurface = surface
            android.util.Log.i(
                "nyx-video",
                "encodeur H.264 prêt (${size.first}x${size.second}) — ouverture caméra"
            )
            openCamera(surface)
            csd
        } catch (e: Exception) {
            android.util.Log.w("nyx-video", "caméra indisponible — visio audio-only : ${e.message}")
            stopCamera()
            null
        }
    }

    /** CsD (SPS+PPS) + surface d'entrée + taille effective de l'encodeur. */
    private fun createEncoder(): Triple<ByteArray, Surface, Pair<Int, Int>> {
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
        var csd = ByteArray(0)
        val info = MediaCodec.BufferInfo()
        // Le csd sort sur INFO_OUTPUT_FORMAT_CHANGED (avant toute trame).
        for (i in 0 until 100) {
            val outIdx = enc.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = enc.outputFormat
                val sps = of.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                val pps = of.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                csd = (sps ?: ByteArray(0)) + (pps ?: ByteArray(0))
                break
            } else if (outIdx >= 0) {
                enc.releaseOutputBuffer(outIdx, false)
            }
        }
        // Thread de drain : chaque access unit codée part en F_VIDEO.
        val t = Thread({
            val bufInfo = MediaCodec.BufferInfo()
            try {
                while (!stopped) {
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, 100_000)
                    if (outIdx >= 0 && bufInfo.size > 0) {
                        val ob = enc.getOutputBuffer(outIdx)
                        if (ob != null && transmit && !stopped) {
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
        return Triple(csd, surface, w to h)
    }

    /** Taille caméra/encodeur : la plus petite supportée ≥ 320×240 (bord 640). */
    private fun pickCaptureSize(): Pair<Int, Int> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCameraId(mgr) ?: throw IllegalStateException("aucune caméra")
        val map = mgr.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width in 240..640 && it.height in 180..480 }
            ?.sortedBy { it.width * it.height }
            ?: emptyList()
        val best = sizes.firstOrNull() ?: android.util.Size(320, 240)
        return best.width to best.height
    }

    /** Caméra FRONT en priorité (visio naturelle), sinon la première. */
    private fun pickCameraId(mgr: CameraManager): String? {
        val ids = mgr.cameraIdList
        return ids.firstOrNull {
            runCatching {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        } ?: ids.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(encoderSurface: Surface) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCameraId(mgr) ?: throw IllegalStateException("aucune caméra")
        // v0.2.48 — HANDLER EXPLICITE (terrain 24/10 08:49 : « caméra
        // indisponible — visio audio-only : No handler given, and current
        // thread has no looper! ») : openCamera exige un thread POURVU d'un
        // Looper pour livrer ses callbacks ; le thread média n'en a pas.
        // Un HandlerThread dédié (créé une fois, libéré dans stopCamera)
        // remplace le main thread — AUCUN callback caméra ne touche l'UI.
        if (cameraHandler == null) {
            val ht = android.os.HandlerThread("nyx-cam")
            ht.start()
            cameraThread = ht
            cameraHandler = android.os.Handler(ht.looper)
        }
        val handler = requireNotNull(cameraHandler) { "handler caméra indisponible" }
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
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
     */
    fun startReceiving(peerCsd: ByteArray) {
        synchronized(lock) { peerCsdBuf = peerCsd }
        reconfigureDecoder()
    }

    @Volatile private var peerCsdBuf: ByteArray = ByteArray(0)

    /** Surface distante (SurfaceView de l'overlay) — reconfigure le décodeur. */
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
    }
}
