package net.nyx.messenger.p2p

import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * v0.3.10 — MOTEUR VIDÉO D'APPEL DESKTOP — CAMÉRA PAR DÉFAUT (retour terrain
 * session 80, verbatim : « le pc fait un partage d'écran plutot que d'envoyer
 * le flux caméra. Je veux par défaut le flux caméra mais la possibilité de
 * partager l'écran »).
 *
 * DEUX SOURCES, bascule à CHAUD (bouton 📹 de l'overlay) :
 *  - [Source.CAMERA] (DÉFAUT) : webcam du PC capturée par l'exécutable
 *    ffmpeg EMBARQUÉ (dshow) qui sort directement du MJPEG à l'échelle ≤480
 *    sur son stdout — AUCUN JNI, AUCUN COM, aucune dépendance bibliothèque :
 *    un sous-processus possédé, détruit au raccroché/bascule. ffmpeg absent
 *    ou webcam introuvable → repli honnête sur l'écran (jamais un appel
 *    raté pour la visio — miroir Android).
 *  - [Source.SCREEN] : java.awt.Robot (partage d'écran historique v0.3.7).
 *
 * Le reste est le moteur v0.3.7 : trames [P2pProtocol.F_VIDEO] JPEG COMPLET
 * auto-contenu (~5 fps, budget Tor), réception [feedVideo] exposée à l'UI
 * ([latestJpeg] + [frameVersion]), rotation du pair = métadonnée de
 * présentation ([feedRotation]). v0.3.10 : le rendu utilise l'aspect DE LA
 * TRAME quand le pair n'annonce pas de rotation (JPEG auto-descriptif,
 * téléphones ≥ v0.2.58 qui pré-tournent à l'encodage).
 *
 * PRIVATÉ : rien ne part avant acceptation explicite ([transmit], comme
 * l'audio — pas de caméra à l'insu) ; le transport est l'onion direct (Tor
 * chiffre de bout en bout). AUCUNE image n'est stockée, aucune image ne
 * passe par un réplica.
 */
class DesktopCallVideo(
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
) {

    /** Source d'émission (CAMERA par défaut — demande opérateur session 80). */
    enum class Source { CAMERA, SCREEN }

    /** v0.3.10 — dimensions d'émission effectives + source active. */
    data class VideoSendInfo(val w: Int, val h: Int, val source: Source)

    /** v0.3.7 : aucune trame vidéo ne part avant acceptation explicite. */
    @Volatile var transmit = false

    /** Pause de l'ÉMISSION (bouton 📹 de l'overlay) : la réception continue. */
    @Volatile var paused = false

    @Volatile var stopped = false
        private set

    /** Source courante (bascule par [switchSource]). */
    @Volatile var source: Source = Source.CAMERA
        private set

    /** Dernier JPEG reçu du pair (null avant la 1ʳᵉ trame) — l'UI y poll. */
    @Volatile var latestJpeg: ByteArray? = null
        private set

    /** Compteur de trames reçues — l'UI ne redessine que s'il change. */
    @Volatile var frameVersion: Long = 0
        private set

    /** Rotation courante du pair (F_VIDEO_ROT — présentation de sa caméra). */
    @Volatile var peerRot: Int = 0

    /** Dimensions annoncées par le pair (aspect de la vue distante). */
    @Volatile var peerW: Int = 0
    @Volatile var peerH: Int = 0

    private var sendThread: Thread? = null

    /* --------------------------- source CAMÉRA --------------------------- */

    /** Processus ffmpeg possédé (null hors mode caméra). */
    @Volatile private var ffmpeg: Process? = null
    @Volatile private var ffmpegDevice: String = ""

    /** Dernière trame JPEG de la caméra (écrite par le lecteur, lue par l'émetteur). */
    @Volatile private var camFrame: ByteArray? = null

    /** Dimensions observées de la caméra (remplies à la 1ʳᵉ trame). */
    @Volatile var camW: Int = 0
        private set
    @Volatile var camH: Int = 0
        private set

    /** Robot (source écran) — créé paresseusement, thread de l'émetteur. */
    private var robot: Robot? = null
    private var screenBounds: java.awt.Rectangle? = null
    private var screenW = 0
    private var screenH = 0

    /**
     * Démarre l'émission — CAMÉRA PAR DÉFAUT, écran en repli. Renvoie les
     * dimensions d'émission, ou null si aucune source n'est disponible
     * (environnement sans écran ni webcam — l'appel continue en AUDIO-ONLY,
     * miroir Android : jamais un appel raté pour la visio).
     */
    fun startSending(): VideoSendInfo? {
        return try {
            val cam = startCamera()
            if (cam != null) {
                startSenderLoop()
                DesktopLog.i(
                    "nyx-video",
                    "CAMÉRA du PC active (${cam.w}x${cam.h} @ ~$FPS fps, MJPEG ffmpeg — « je veux par défaut le flux caméra »)"
                )
                return cam
            }
            val scr = startScreen()
            if (scr != null) {
                startSenderLoop()
                DesktopLog.w(
                    "nyx-video",
                    "caméra indisponible — repli PARTAGE D'ÉCRAN (${scr.w}x${scr.h}) ; bascule possible depuis l'overlay"
                )
                return scr
            }
            DesktopLog.w("nyx-video", "aucune source vidéo (caméra ni écran) — visio audio-only")
            null
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "source vidéo indisponible — visio audio-only : ${e.message?.take(80)}")
            null
        }
    }

    /** Ouvre la webcam (ffmpeg dshow) — null si indisponible. */
    private fun startCamera(): VideoSendInfo? {
        if (!DesktopPaths.isWindows) {
            DesktopLog.i("nyx-video", "caméra : capture dshow Windows seulement (ici ${System.getProperty("os.name")})")
            return null
        }
        val exe = DesktopPaths.resolveFfmpeg() ?: run {
            DesktopLog.w("nyx-video", "ffmpeg introuvable (installé à côté de l'app ?) — caméra indisponible")
            return null
        }
        val device = ffmpegDevice.ifEmpty { discoverCamera(exe) ?: return null }
        val p: Process
        try {
            val args = mutableListOf(
                exe.absolutePath,
                "-hide_banner", "-loglevel", "error",
                "-f", "dshow",
                "-framerate", CAM_FPS.toString(),
                "-i", "video=$device",
                "-vf", "scale=$CAP_W:$CAP_W:force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-q:v", "5",
                "-f", "mjpeg", "pipe:1"
            )
            p = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "ffmpeg non démarré : ${e.message?.take(60)}")
            return null
        }
        ffmpeg = p
        ffmpegDevice = device
        source = Source.CAMERA
        // Lecteur du flux MJPEG de ffmpeg (stdout) → [camFrame].
        val t = Thread({ readCameraStream(p.inputStream) }, "nyx-video-cam")
        t.isDaemon = true
        t.start()
        // Surveillant de mort : un ffmpeg mort (webcam débranchée) dégrade
        // l'émission en écran SEULEMENT si un écran existe — jamais de crash.
        val watch = Thread({
            try {
                p.waitFor()
            } catch (_: InterruptedException) {
            }
            if (!stopped && source == Source.CAMERA && ffmpeg === p) {
                DesktopLog.w("nyx-video", "ffmpeg caméra s'est arrêté (webcam perdue ?) — bascule vers l'écran")
                runCatching { switchSource() }
            }
        }, "nyx-video-camwatch")
        watch.isDaemon = true
        watch.start()
        // Dimensions honnêtes : attendre la 1ʳᵉ trame (≤ 4 s) pour annoncer
        // vw/vh réels au pair (le JPEG est auto-descriptif ensuite).
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline && camW == 0 && !stopped) {
            Thread.sleep(80)
        }
        val w = if (camW > 0) camW else CAP_W
        val h = if (camH > 0) camH else CAP_W * 3 / 4
        return VideoSendInfo(w, h, Source.CAMERA)
    }

    /** Énumère les webcams (premier périphérique vidéo dshow). */
    private fun discoverCamera(exe: File): String? {
        return try {
            val p = ProcessBuilder(
                exe.absolutePath, "-hide_banner",
                "-list_devices", "true", "-f", "dshow", "-i", "dummy"
            ).start()
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            var inVideo = false
            for (line in err.lineSequence()) {
                if (line.contains("DirectShow video devices")) { inVideo = true; continue }
                if (line.contains("DirectShow audio devices")) break
                if (!inVideo) continue
                if (line.contains("Alternative name")) continue
                val m = CAM_NAME_REGEX.find(line) ?: continue
                val name = m.groupValues[1].trim()
                if (name.isNotEmpty()) {
                    DesktopLog.i("nyx-video", "webcam détectée : « $name » (dshow)")
                    return name
                }
            }
            DesktopLog.w("nyx-video", "aucune webcam dshow détectée — repli écran")
            null
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "énumération webcam impossible : ${e.message?.take(60)}")
            null
        }
    }

    /** Lit le stdout MJPEG de ffmpeg, découpe les trames JPEG complètes. */
    private fun readCameraStream(input: InputStream) {
        try {
            val parser = MjpegStreamParser { frame ->
                if (frame.size in 64..MAX_FRAME_BYTES * 2) {
                    camFrame = frame
                    if (camW == 0 && frame.size > 100) {
                        runCatching {
                            val dims = jpegDims(frame)
                            if (dims != null) { camW = dims.first; camH = dims.second }
                        }
                    }
                }
            }
            val buf = ByteArray(32 * 1024)
            while (!stopped) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) parser.feed(buf, n)
            }
        } catch (_: Exception) {
            // ffmpeg tué (bascule/raccroché) — silencieux.
        }
    }

    /* --------------------------- source ÉCRAN --------------------------- */

    /** Prépare la capture d'écran (Robot) — null si indisponible. */
    private fun startScreen(): VideoSendInfo? {
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screen = ge.defaultScreenDevice.defaultConfiguration.bounds
            if (screen.width <= 0 || screen.height <= 0) return null
            val scale = if (screen.width > CAP_W) CAP_W.toFloat() / screen.width else 1f
            // JPEG de taille paire (conversions YUV propres côté Android).
            val tw = (((screen.width * scale).toInt()) / 2) * 2
            val th = (((screen.height * scale).toInt()) / 2) * 2
            if (tw <= 0 || th <= 0) return null
            screenBounds = screen
            screenW = tw; screenH = th
            source = Source.SCREEN
            stopFfmpeg()
            VideoSendInfo(tw, th, Source.SCREEN)
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "capture d'écran indisponible — visio audio-only : ${e.message?.take(80)}")
            null
        }
    }

    /* --------------------------- boucle d'émission --------------------------- */

    /**
     * Émetteur unique (~[FPS] fps) : pioche la trame de la SOURCE COURANTE
     * (caméra : dernier JPEG ffmpeg ; écran : capture Robot → JPEG), la
     * compresse au budget Tor et l'expédie. La bascule de source se fait à
     * CHAUD sans toucher au thread (miroir pause/mute : write bloqué par Tor
     * = trame sautée, jamais de bourrage).
     */
    private fun startSenderLoop() {
        val t = Thread({
            try {
                while (!stopped) {
                    Thread.sleep(FRAME_INTERVAL_MS)
                    if (paused || !transmit || stopped) continue
                    val src = source
                    var jpeg: ByteArray? = when (src) {
                        Source.CAMERA -> camFrame
                        Source.SCREEN -> captureScreenJpeg()
                    }
                    if (jpeg == null) continue
                    if (jpeg.size > MAX_FRAME_BYTES) {
                        jpeg = recompress(jpeg)
                        if (jpeg == null || jpeg.size > MAX_FRAME_BYTES) continue
                    }
                    write(P2pProtocol.F_VIDEO, jpeg)
                }
            } catch (e: InterruptedException) {
                // stop() — sortie propre.
            } catch (e: Exception) {
                if (!stopped) onFatal("vidéo (émission) : ${e.message}")
            }
        }, "nyx-video-enc")
        t.isDaemon = true
        t.start()
        sendThread = t
    }

    /** Capture d'écran → JPEG à l'échelle (source ÉCRAN). */
    private fun captureScreenJpeg(): ByteArray? {
        val screen = screenBounds ?: return null
        val r = robot ?: run {
            robot = try { Robot() } catch (e: Exception) { return null }
            robot!!
        }
        val shot = runCatching { r.createScreenCapture(screen) }.getOrNull() ?: return null
        val scaled = scaleTo(shot, screenW, screenH)
        return encodeJpeg(scaled, JPEG_QUALITY)
    }

    /** Recompression dure (circuit étroit) — null si le JPEG est illisible. */
    private fun recompress(jpeg: ByteArray): ByteArray? {
        val img = runCatching {
            ImageIO.read(java.io.ByteArrayInputStream(jpeg))
        }.getOrNull() ?: return null
        if (img.width <= 0 || img.height <= 0) return null
        return encodeJpeg(img, JPEG_QUALITY_LOW)
    }

    /**
     * v0.3.10 — BASCULE CAMÉRA ↔ ÉCRAN (demande opérateur : « la possibilité
     * de partager l'écran »). Renvoie la nouvelle source, ou null si la
     * bascule demandée est impossible (ex. caméra indisponible → on RESTE
     * sur l'écran : jamais d'appel dégradé pour une bascule).
     */
    fun switchSource(): Source? {
        if (stopped) return null
        return try {
            when (source) {
                Source.CAMERA -> {
                    // → ÉCRAN : tuer ffmpeg suffit (la boucle piochera Robot).
                    stopFfmpeg()
                    val scr = prepareScreenOnly()
                    if (scr == null) {
                        // Aucun écran (tête serveur) : essayer de garder/reprendre
                        // la caméra — la source ne change PAS.
                        if (camFrame == null) startCamera()
                        return if (source == Source.CAMERA) Source.CAMERA else null
                    }
                    source = Source.SCREEN
                    DesktopLog.i("nyx-video", "source vidéo basculée → PARTAGE D'ÉCRAN (${scr.w}x${scr.h})")
                    Source.SCREEN
                }
                Source.SCREEN -> {
                    // → CAMÉRA : démarrer ffmpeg (repli écran s'il échoue).
                    stopFfmpeg()
                    camFrame = null; camW = 0; camH = 0
                    val cam = startCamera()
                    if (cam == null) {
                        val scr = prepareScreenOnly()
                        if (scr == null) return null
                        source = Source.SCREEN
                        DesktopLog.w("nyx-video", "caméra indisponible à la bascule — écran maintenu")
                        return Source.SCREEN
                    }
                    DesktopLog.i("nyx-video", "source vidéo basculée → CAMÉRA (${cam.w}x${cam.h})")
                    Source.CAMERA
                }
            }
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "bascule de source échouée : ${e.message?.take(60)}")
            null
        }
    }

    /** Prépare la géométrie écran sans changer [source] (null si indisponible). */
    private fun prepareScreenOnly(): VideoSendInfo? {
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screen = ge.defaultScreenDevice.defaultConfiguration.bounds
            if (screen.width <= 0 || screen.height <= 0) return null
            val scale = if (screen.width > CAP_W) CAP_W.toFloat() / screen.width else 1f
            val tw = (((screen.width * scale).toInt()) / 2) * 2
            val th = (((screen.height * scale).toInt()) / 2) * 2
            if (tw <= 0 || th <= 0) return null
            screenBounds = screen
            screenW = tw; screenH = th
            VideoSendInfo(tw, th, Source.SCREEN)
        } catch (e: Exception) {
            null
        }
    }

    private fun stopFfmpeg() {
        val p = ffmpeg ?: return
        ffmpeg = null
        runCatching { p.destroy() }
    }

    /* ------------------------------ réception ------------------------------ */

    /** Trame F_VIDEO du pair (JPEG complet) — appelé par la boucle de lecture audio. */
    fun feedVideo(jpeg: ByteArray) {
        if (stopped || jpeg.size > MAX_FRAME_BYTES * 2) return
        latestJpeg = jpeg
        frameVersion++
    }

    /** Trame F_VIDEO_ROT du pair — rotation de son écran (présentation caméra). */
    fun feedRotation(payload: ByteArray) {
        val rot = VideoRotCodec.decodeFrame(payload) ?: return
        if (rot != peerRot) {
            peerRot = rot
            DesktopLog.i("nyx-video", "orientation du pair suivie : $rot°")
        }
    }

    /** Présentation du pair annoncée dans son HELLO/ACCEPT (aspect de la vue). */
    fun setPeerPresentation(w: Int, h: Int) {
        peerW = w
        peerH = h
    }

    /** Arrêt complet (raccroché, erreur, fin d'appel). */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { sendThread?.interrupt() }
        sendThread = null
        stopFfmpeg()
        latestJpeg = null
        camFrame = null
    }

    /* ----------------------------- encodage JPEG ----------------------------- */

    private fun scaleTo(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h) return src
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return dst
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream(20 * 1024)
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        try {
            val p = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(img, null, null), p)
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Dimensions d'un JPEG (marqueurs SOF0/2) sans le décoder entièrement. */
    private fun jpegDims(j: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < j.size) {
            if (j[i] != 0xFF.toByte()) { i++; continue }
            val marker = j[i + 1].toInt() and 0xFF
            when (marker) {
                0xC0, 0xC1, 0xC2 -> {
                    val h = ((j[i + 5].toInt() and 0xFF) shl 8) or (j[i + 6].toInt() and 0xFF)
                    val w = ((j[i + 7].toInt() and 0xFF) shl 8) or (j[i + 8].toInt() and 0xFF)
                    if (w > 0 && h > 0) return w to h
                    return null
                }
                0xD8, 0x01, 0xD9 -> { i += 2 }
                else -> {
                    if (i + 3 >= j.size) return null
                    val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
                    if (len <= 0) return null
                    i += 2 + len
                }
            }
        }
        return null
    }

    companion object {
        /** Largeur max d'émission (le téléphone affiche ≤ ~480 px — économie Tor). */
        const val CAP_W = 480

        /** Qualité JPEG courante (0-1). */
        const val JPEG_QUALITY = 0.55f

        /** Qualité de repli si la trame dépasse [MAX_FRAME_BYTES]. */
        const val JPEG_QUALITY_LOW = 0.35f

        /** Cadence cible (ms entre trames) — s'auto-ralentit si le write bloque. */
        const val FRAME_INTERVAL_MS = 200L

        const val FPS = 5

        /** Cadence de capture caméra côté ffmpeg (l'émetteur échantillonne à ~5). */
        const val CAM_FPS = 10

        /** Poids max conseillé d'une trame (recompression au-delà). */
        const val MAX_FRAME_BYTES = 32 * 1024

        /** Ligne dshow « "Nom" » après le préfixe [dshow @ …]. */
        private val CAM_NAME_REGEX = Regex("\\[dshow[^\\]]*\\]\\s+\"([^\"]+)\"")
    }
}

/**
 * v0.3.10 — découpage d'un flux MJPEG BRUT (stdout ffmpeg) en trames JPEG
 * complètes : SOI 0xFFD8 … EOI 0xFFD9. L'entropie JPEG STUFFE ses 0xFF
 * (suivis de 0x00) : une paire FFD9 ne peut apparaître QUE comme fin de
 * trame — le découpage est non ambigu. Garde mémoire : un en-tête sans
 * trame complète ne s'accumule jamais au-delà de 2× le budget.
 */
class MjpegStreamParser(private val onFrame: (ByteArray) -> Unit) {
    private var buf = ByteArrayOutputStream(64 * 1024)

    fun feed(chunk: ByteArray, len: Int) {
        buf.write(chunk, 0, len)
        val data = buf.toByteArray()
        var searchFrom = 0
        var keepFrom = 0
        while (true) {
            val s = indexOf(data, SOI, searchFrom)
            if (s < 0) break                    // plus de SOI : rien à extraire
            val e = indexOf(data, EOI, s + 2)
            if (e < 0) { keepFrom = s; break }   // trame incomplète → attendre
            val end = e + 2
            if (end - s >= 64 && end - s <= MAX_LEN) {
                onFrame(data.copyOfRange(s, end))
            }
            searchFrom = end
            keepFrom = end
        }
        if (keepFrom > 0) {
            val rest = data.copyOfRange(keepFrom, data.size)
            buf = ByteArrayOutputStream(maxOf(64 * 1024, rest.size * 2))
            buf.write(rest)
        } else if (data.size > MAX_LEN) {
            // Que des déchets sans SOI : tout jeter.
            buf.reset()
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        outer@ while (i <= hay.size - needle.size) {
            for (k in needle.indices) {
                if (hay[i + k] != needle[k]) { i++; continue@outer }
            }
            return i
        }
        return -1
    }

    companion object {
        private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        private const val MAX_LEN = DesktopCallVideo.MAX_FRAME_BYTES * 2
    }
}

/**
 * v0.3.7 — décodage du payload F_VIDEO_ROT (ASCII « 0 »/« 90 »/« 180 »/« 270 »)
 * — miroir exact de VideoRot.decodeFrame Android (le bureau n'émet JAMAIS
 * cette trame : la caméra/écran du PC ne tourne pas ; il ne fait que SUIVRE
 * la rotation de la caméra du téléphone).
 */
object VideoRotCodec {
    /** Décodage du payload F_VIDEO_ROT — null = illisible (ignoré sans erreur). */
    fun decodeFrame(payload: ByteArray): Int? {
        val s = runCatching { String(payload, Charsets.US_ASCII).trim() }.getOrNull() ?: return null
        return when (s) {
            "0", "90", "180", "270" -> s.toInt()
            else -> null
        }
    }
}
