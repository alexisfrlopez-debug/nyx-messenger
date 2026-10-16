package net.nyx.messenger.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Persistance de la carte de profil DE L'UTILISATEUR (champs publics ET privés
 * — préférences privées MODE_PRIVATE, l'appareil seul les lit).
 *
 * Invariant de vie privée (ADR-010, wire-format.md §7) : ce store contient
 * TOUT ; la publication réseau ([publicCard]) ne transporte jamais que le
 * sous-ensemble public, et l'avatar est recodé localement en 256×256 JPEG
 * avant toute diffusion (limite §7.2 respectée par construction).
 */
class ProfileStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("nyx_profile", Context.MODE_PRIVATE)

    // v0.2.14 : 1 = une carte publique attend d'être PUBLIÉE (le save initial
    // a échoué — réplica injoignable) ; reparti par la boucle 30 s via
    // [NyxRepository.ensureProfilePublished] puis remis à zéro.
    private val publishPending
        get() = prefs.getBoolean(KEY_PUBLISH_PENDING, false)

    /** Carte complète (privée + publique) — jamais transmise telle quelle. */
    fun load(): OwnProfile =
        prefs.getString(KEY_PROFILE, null)?.let { ProfileCodec.decode(it) }?.let {
            OwnProfile(it.fields)
        } ?: OwnProfile()

    @Synchronized
    fun save(profile: OwnProfile) {
        prefs.edit().putString(KEY_PROFILE, ProfileCodec.encode(ProfileBody(profile.fields))).apply()
    }

    /**
     * Mise à jour d'un champ (valeur + visibilité) — null/blank retire le champ.
     */
    @Synchronized
    fun setField(key: String, value: String, public: Boolean): OwnProfile {
        val cur = load().fields.toMutableMap()
        if (value.isBlank()) cur.remove(key) else cur[key] = ProfileField(value, public)
        val next = OwnProfile(cur)
        save(next)
        return next
    }

    /**
     * Carte PUBLIQUE — la seule chose qui peut partir sur le réseau. Retourne
     * null si rien n'est public (aucun événement à publier) et le motif de
     * refus si la carte publique viole la spec (jamais attendu via l'UI).
     */
    fun publicCard(): Pair<ProfileBody?, String?> {
        val pub = load().publicOnly().fields
        if (pub.isEmpty()) return null to null
        val err = ProfileValidator.validatePublicCard(pub)
        if (err != null) return null to err
        return ProfileBody(pub) to null
    }

    /** v0.2.14 : une publication de la carte publique est en attente. */
    fun isPublishPending(): Boolean = publishPending

    /** v0.2.14 : marque la publication comme à refaire (échec réseau au save). */
    @Synchronized
    fun markPublishPending() {
        prefs.edit().putBoolean(KEY_PUBLISH_PENDING, true).apply()
    }

    /** v0.2.14 : publication déposée avec succès. */
    @Synchronized
    fun clearPublishPending() {
        prefs.edit().putBoolean(KEY_PUBLISH_PENDING, false).apply()
    }

    companion object {
        private const val KEY_PROFILE = "profile_json"
        private const val KEY_PUBLISH_PENDING = "publish_pending"

        @Volatile private var instance: ProfileStore? = null
        fun get(context: Context): ProfileStore =
            instance ?: synchronized(this) {
                instance ?: ProfileStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Avatar : recodage local en 256×256 JPEG (carré, crop centré) avant toute
 * publication — limite §7.2 (base64 ≤ 65 536 caractères) respectée par
 * construction ; l'image pleine résolution ne quitte jamais l'appareil.
 */
object AvatarCodec {
    private const val TARGET = 256
    private const val JPEG_QUALITY = 80

    /** Décode + redimensionne + recode en JPEG, retourne le base64 (ou null). */
    fun downscaleToBase64(data: ByteArray): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= TARGET && opts.outHeight / (sample * 2) >= TARGET) sample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeByteArray(data, 0, data.size, opts2) ?: return null

        // Crop carré centré puis mise à l'échelle 256×256
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, x, y, side, side)
        val out = Bitmap.createScaledBitmap(square, TARGET, TARGET, true)

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        if (b64.length > ProfileKeys.LIMITS[ProfileKeys.AVATAR]!!) {
            // Filet de sécurité : réduction de qualité jusqu'à tenir dans la limite
            var q = JPEG_QUALITY - 15
            while (q >= 30) {
                bos.reset()
                out.compress(Bitmap.CompressFormat.JPEG, q, bos)
                val retry = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                if (retry.length <= ProfileKeys.LIMITS[ProfileKeys.AVATAR]!!) return retry
                q -= 15
            }
            return null
        }
        return b64
    }

    /** Décode un base64 d'avatar en Bitmap (affichage). */
    fun fromBase64(b64: String): Bitmap? =
        runCatching {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        }.getOrNull()
}
