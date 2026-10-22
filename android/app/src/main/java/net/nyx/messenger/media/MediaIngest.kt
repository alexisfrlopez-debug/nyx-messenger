package net.nyx.messenger.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v0.2.17 — INGEST MÉDIA local (photo/vidéo/fichier/audio) pour le canal
 * pièces jointes. Toutes les opérations restent LOCALES : le transport E2E
 * fragmenté vit dans [net.nyx.messenger.repo.ChunkCodec] / NyxRepository.
 *
 *  - [readBytes]     : lecture bornée d'un Uri (picker galerie / documents) ;
 *  - [compressImage] : rééchantillonnage ≤ 1600 px + JPEG q82 — une photo
 *    d'appareil (2-8 Mo) tombe à 150-400 Ko, soit 2-5 fragments au lieu de
 *    20-90 (le transport onion tourne à ~100 Ko/s, chaque Ko compte) ;
 *  - [persistMedia]  : écriture du fichier sous filesDir/media/<fid>/<nom>
 *    (côté expéditeur pour revoir ses propres médias, côté destinataire après
 *    assemblage + vérification sha256) ;
 *  - [saveToDownloads] : copie vers MediaStore/Downloads (API 29+, les deux
 *    téléphones du terrain sont Android 15/16).
 */
object MediaIngest {

    /** Fichiers refusés au-delà de cette taille (garde-fou transport). */
    const val MAX_BYTES = net.nyx.messenger.repo.ChunkCodec.MAX_FILE_SIZE

    /** Dimension max (côté long) d'une image recompressée. */
    const val IMAGE_MAX_PX = 1600
    private const val JPEG_QUALITY = 82

    fun mediaDir(context: Context, fid: String): File =
        File(File(context.filesDir, "media"), fid).apply { mkdirs() }

    /** Lecture bornée d'un Uri. Lève si le fichier dépasse [MAX_BYTES]. */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) {
                    throw IllegalArgumentException(
                        "fichier trop volumineux (max ${net.nyx.messenger.repo.ChunkCodec.humanSize(MAX_BYTES)})"
                    )
                }
                out.write(buf, 0, n)
            }
        } ?: throw IllegalArgumentException("fichier illisible")
        return out.toByteArray()
    }

    data class Compressed(val bytes: ByteArray, val mime: String, val name: String)

    /** Est-ce un MIME image compressible (jpeg/png/webp) ? */
    fun isCompressibleImage(mime: String): Boolean =
        mime.startsWith("image/") && mime != "image/gif"

    /**
     * Recompression d'une image : décodage borné (inSampleSize), rescale à
     * [IMAGE_MAX_PX] max, réencodage JPEG [JPEG_QUALITY]. Échec (null) =
     * on enverra l'original tel quel.
     */
    fun compressImage(context: Context, uri: Uri, originalName: String): Compressed? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > IMAGE_MAX_PX) {
            val ratio = IMAGE_MAX_PX.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        if (scaled !== bitmap) bitmap.recycle()
        val base = originalName.substringBeforeLast('.').ifBlank { "photo" }
        return Compressed(bos.toByteArray(), "image/jpeg", "$base.jpg")
    }

    /**
     * Écrit le fichier média sous filesDir/media/<fid>/<name> — retourne le
     * chemin. v0.2.18 : le fichier est CHIFFRÉ AU REPOS (AES-GCM, clé scellée
     * Keystore — [MediaVault]) ; lecture uniquement via [MediaVault.readBytes].
     */
    fun persistMedia(context: Context, fid: String, name: String, bytes: ByteArray): String {
        val dir = mediaDir(context, fid)
        val safeName = net.nyx.messenger.repo.ChunkCodec.sanitizeName(name).ifBlank { "fichier" }
        val f = File(dir, safeName)
        f.writeBytes(MediaVault.encrypt(context, bytes))
        return f.absolutePath
    }

    /**
     * Copie d'un média local vers Téléchargements (MediaStore, API 29+) —
     * export VOLONTAIRE : le fichier est déchiffré pour l'occasion (l'export
     * quitte le périmètre protégé de l'app, à la demande de l'utilisateur).
     */
    fun saveToDownloads(context: Context, localPath: String, mime: String): String? {
        val src = File(localPath)
        if (!src.exists()) return null
        val plain = MediaVault.readBytes(context, localPath)
            ?: runCatching { src.readBytes() }.getOrNull() // média clair (v0.2.17)
            ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(plain) }
            ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return src.name
    }
}
