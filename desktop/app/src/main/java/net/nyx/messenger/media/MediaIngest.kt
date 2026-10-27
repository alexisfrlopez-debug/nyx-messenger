package net.nyx.messenger.media

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Portage desktop de media/MediaIngest.kt (Android) — v0.3.4 « parité Android » :
 *  - [compressImage] : réduction ≤ 1600 px + JPEG qualité 82 avant envoi
 *    (miroir exact des constantes Android, sauf GIF) — best-effort : tout
 *    échec de décodage retourne null et l'appelant envoie les octets d'origine ;
 *  - [downscaleToBase64] : avatar → recadrage carré CENTRÉ → 256×256 JPEG,
 *    qualité dégressive 80→30 jusqu'à tenir en 65 536 caractères base64
 *    (miroir AvatarCodec Android — même format filaire, interopérable) ;
 *  - [decodeBase64] : décodage pour l'affichage.
 */
object MediaIngest {

    const val SEND_MAX_DIM = 1600
    const val SEND_JPEG_QUALITY = 82
    const val AVATAR_DIM = 256
    const val AVATAR_B64_LIMIT = 65_536

    /** Compression d'envoi (≤ [SEND_MAX_DIM], JPEG q[SEND_JPEG_QUALITY]). null = garder l'original. */
    fun compressImage(bytes: ByteArray): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val w = src.width
        val h = src.height
        val scale = minOf(1f, SEND_MAX_DIM.toFloat() / maxOf(w, h))
        if (scale >= 1f && isAlreadyJpeg(bytes)) return bytes
        val tw = maxOf(1, (w * scale).toInt())
        val th = maxOf(1, (h * scale).toInt())
        val scaled = scaleTo(src, tw, th)
        encodeJpeg(scaled, SEND_JPEG_QUALITY)
    }.getOrNull()

    /** Avatar : recadrage carré centré → 256×256 JPEG base64 (miroir AvatarCodec). null = illisible. */
    fun downscaleToBase64(bytes: ByteArray): String? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val side = minOf(src.width, src.height)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        val square = src.getSubimage(sx, sy, side, side)
        val scaled = scaleTo(square, AVATAR_DIM, AVATAR_DIM)
        var quality = 80
        while (quality >= 30) {
            val jpg = encodeJpeg(scaled, quality)
            val b64 = Base64.getEncoder().encodeToString(jpg)
            if (b64.length <= AVATAR_B64_LIMIT) return b64
            quality -= 15
        }
        null
    }.getOrNull()

    /** Décodage d'un avatar base64 (échec silencieux → initiale). */
    fun decodeBase64(b64: String): BufferedImage? = runCatching {
        if (b64.isBlank()) return null
        ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(b64)))
    }.getOrNull()

    private fun scaleTo(src: BufferedImage, tw: Int, th: Int): BufferedImage {
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, tw, th, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encodeJpeg(img: BufferedImage, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam
        params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT)
        params.setCompressionQuality(quality / 100f)
        val stream = javax.imageio.stream.MemoryCacheImageOutputStream(out)
        writer.output = stream
        writer.write(null, javax.imageio.IIOImage(img, null, null), params)
        writer.dispose()
        stream.close()
        return out.toByteArray()
    }

    private fun isAlreadyJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8
}
