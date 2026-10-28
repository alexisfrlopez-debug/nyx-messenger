package net.nyx.messenger.p2p

import net.nyx.messenger.desktop.DesktopLog
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * v0.3.7 — MOTEUR VIDÉO D'APPEL DESKTOP (interop visio bureau↔téléphone,
 * MJPEG logiciel — ADR-015 prolongé au média vidéo).
 *
 * Le bureau ne peut NI encoder NI décoder le H.264 MediaCodec d'Android en
 * pur Java → codec commun MJPEG : chaque trame [P2pProtocol.F_VIDEO] porte
 * un JPEG COMPLET, auto-contenu (une trame perdue ne corrompt rien — la
 * résilience Tor du format, miroir des blocs ADPCM fermes de l'audio).
 *
 * ÉMISSION : capture d'ÉCRAN (java.awt.Robot — l'écran du PC part au
 * téléphone ; le bureau n'a pas de caméra en pur Java), mise à l'échelle
 * ≤ [CAP_W] px de large, JPEG qualité [JPEG_QUALITY], rythme ~[FPS] fps.
 * La boucle s'auto-régule : un write() bloqué par le circuit Tor ralentit
 * naturellement le flux (aucune file d'attente, la trame périmée est
 * sautée).
 *
 * RÉCEPTION : les trames F_VIDEO du téléphone (caméra, YuvImage.compressToJpeg
 * côté Android ≥ v0.2.55) arrivent par [feedVideo] (routage
 * [DesktopCallAudio.videoSink] — l'audio reste le SEUL lecteur du socket) ;
 * le JPEG brut est exposé à l'UI ([latestJpeg] + [frameVersion] — l'overlay
 * Compose y poll et décode via skia). La rotation du pair ([feedRotation],
 * trames F_VIDEO_ROT) est une MÉTADONNÉE de présentation appliquée par la
 * VUE (miroir VideoFit Android — le flux ne change jamais).
 *
 * Budget Tor : ~10-25 Ko par trame à 5 fps ≈ 50-125 Ko/s par sens — l'audio
 * (8 Ko/s) reste prioritaire ; en cas de congestion la boucle ralentit
 * d'elle-même (write bloquant = trame sautée).
 *
 * PRIVATÉ : rien ne part avant acceptation explicite ([transmit], comme
 * l'audio — pas d'écran partagé à l'insu) ; le transport est l'onion direct
 * (Tor chiffre de bout en bout). AUCUNE image n'est stockée, aucune image
 * ne passe par un réplica.
 */
class DesktopCallVideo(
    private val write: (type: Int, payload: ByteArray) -> Unit,
    private val onFatal: (String) -> Unit,
) {

    /** v0.3.7 — dimensions d'émission effectives (annoncées au pair : vw/vh). */
    data class VideoSendInfo(val w: Int, val h: Int)

    /** v0.3.7 : aucune trame vidéo ne part avant acceptation explicite. */
    @Volatile var transmit = false

    /** v0.3.7 — pause du PARTAGE (bouton 📹 de l'overlay) : coupe l'ÉMISSION, la réception continue. */
    @Volatile var paused = false

    @Volatile var stopped = false
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

    /**
     * Démarre la capture d'écran (émission). Renvoie les dimensions
     * d'émission, ou null si la capture est indisponible (environnement
     * sans écran — l'appel continue en AUDIO-ONLY sans erreur, miroir
     * Android : jamais un appel raté pour la visio).
     */
    fun startSending(): VideoSendInfo? {
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screen = ge.defaultScreenDevice.defaultConfiguration.bounds
            if (screen.width <= 0 || screen.height <= 0) return null
            val scale = if (screen.width > CAP_W) CAP_W.toFloat() / screen.width else 1f
            // JPEG de taille paire (conversions YUV propres côté Android).
            val tw = (((screen.width * scale).toInt()) / 2) * 2
            val th = (((screen.height * scale).toInt()) / 2) * 2
            if (tw <= 0 || th <= 0) return null
            val t = Thread({
                try {
                    val robot = Robot()
                    while (!stopped) {
                        Thread.sleep(FRAME_INTERVAL_MS)
                        if (paused || !transmit || stopped) continue
                        val shot = runCatching { robot.createScreenCapture(screen) }.getOrNull() ?: continue
                        val scaled = scaleTo(shot, tw, th)
                        var jpeg = encodeJpeg(scaled, JPEG_QUALITY)
                        if (jpeg.size > MAX_FRAME_BYTES) {
                            // Circuit étroit : on recomprime plus dur plutôt que
                            // d'expédier un gros JPEG (la trame suivante est déjà
                            // en préparation — le gel est préférable au bourrage).
                            jpeg = encodeJpeg(scaled, JPEG_QUALITY_LOW)
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
            DesktopLog.i("nyx-video", "partage d'écran démarré (${tw}x${th} @ ~$FPS fps, JPEG q$JPEG_QUALITY)")
            VideoSendInfo(tw, th)
        } catch (e: Exception) {
            DesktopLog.w("nyx-video", "capture d'écran indisponible — visio audio-only : ${e.message?.take(80)}")
            null
        }
    }

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

    /** Présation du pair annoncée dans son HELLO/ACCEPT (aspect de la vue). */
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
        latestJpeg = null
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
                compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(img, null, null), p)
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    companion object {
        /** Largeur max d'émission (le téléphone affiche ≤ ~480 px — économie Tor). */
        const val CAP_W = 480

        /** Qualité JPEG courante (0-1). */
        const val JPEG_QUALITY = 0.55f

        /** Qualité de repli si la trame dépasse [MAX_FRAME_BYTES]. */
        const val JPEG_QUALITY_LOW = 0.35f

        /** Cadence cible (ms entre trames) — s'auto-rallenti si le write bloque. */
        const val FRAME_INTERVAL_MS = 200L

        const val FPS = 5

        /** Poids max conseillé d'une trame (recompression au-delà). */
        const val MAX_FRAME_BYTES = 32 * 1024
    }
}

/**
 * v0.3.7 — décodage du payload F_VIDEO_ROT (ASCII « 0 »/« 90 »/« 180 »/« 270 »)
 * — miroir exact de VideoRot.decodeFrame Android (le bureau n'émet JAMAIS
 * cette trame : un écran ne tourne pas ; il ne fait que SUIVRE la rotation
 * de la caméra du téléphone).
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
