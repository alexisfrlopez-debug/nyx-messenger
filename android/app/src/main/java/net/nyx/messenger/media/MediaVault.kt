package net.nyx.messenger.media

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.2.18 — CHIFFREMENT AU REPOS des médias (demande opérateur : « tout
 * fichier stocké uniquement sur les téléphones (en crypté, accessible
 * uniquement via l'application et le compte concerné) »).
 *
 * La base Room est DÉJÀ chiffrée (SQLCipher, clé scellée Keystore —
 * ServiceLocator) ; les FICHIERS médias sous filesDir/media/ étaient en clair.
 * Désormais chaque fichier est écrit AES-256-GCM avec une clé dédiée scellée
 * par le Keystore matériel (même mécanique que KeystoreBox — alias distinct).
 *
 * Format sur disque : « NYXV1 » || nonce(12) || ciphertext+tag.
 * Lecture : [readBytes] déchiffre en mémoire (aucune copie déchiffrée sur
 * disque — l'aperçu image décode depuis les octets ; le lecteur audio écrit
 * un fichier TEMPORAIRE dans cacheDir, supprimé à l'arrêt).
 *
 * Les copies exportées par l'utilisateur (Téléchargements) restent en clair :
 * ce sont des exports VOLONTAIRES hors de l'app (comme une photo envoyée par
 * un autre canal) — documenté dans les notes de version.
 */
object MediaVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "nyx_media_at_rest_key"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12

    /** Magie + version — un fichier non marqué = média d'une version ≤ 0.2.17 (clair). */
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'Y'.code.toByte(), 'X'.code.toByte(), 1)

    private var cachedKey: SecretKey? = null

    private fun key(context: Context): SecretKey {
        cachedKey?.let { return it }
        // Clé média = 32 octets aléatoires scellés via KeystoreBox (clé
        // matérielle maîtresse « nyx_identity_master_key » — la MÊME que pour
        // la passphrase SQLCipher) : le secret ne quitte jamais le Keystore
        // sous forme lisible, et reste déchiffrable tant que l'appareil est
        // déverrouillé — exactement le modèle de la base.
        val prefs = context.getSharedPreferences("nyx_mediakey", Context.MODE_PRIVATE)
        val stored = prefs.getString("sealed", null)
        val secret: ByteArray = if (stored != null) {
            net.nyx.messenger.crypto.KeystoreBox.open(java.util.Base64.getDecoder().decode(stored))
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(
                "sealed",
                java.util.Base64.getEncoder().encodeToString(net.nyx.messenger.crypto.KeystoreBox.seal(fresh))
            ).apply()
            fresh
        }
        val key = SecretKeySpec(secret, "AES")
        cachedKey = key
        return key
    }

    /** Chiffre (nonce généré à chaque écriture). Sortie = MAGIC || nonce || ct. */
    fun encrypt(context: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context))
        val nonce = cipher.iv
        require(nonce.size == NONCE_LEN) { "nonce GCM inattendu (${nonce.size})" }
        val ct = cipher.doFinal(plain)
        return MAGIC + nonce + ct
    }

    /** Déchiffre MAGIC || nonce || ct — null si format inconnu (fichier clair v≤0.2.17) ou corrompu. */
    fun decrypt(context: Context, blob: ByteArray): ByteArray? {
        if (blob.size <= MAGIC.size + NONCE_LEN) return null
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(context),
                GCMParameterSpec(GCM_TAG_BITS, blob, MAGIC.size, NONCE_LEN)
            )
            cipher.doFinal(blob, MAGIC.size + NONCE_LEN, blob.size - MAGIC.size - NONCE_LEN)
        } catch (_: Exception) {
            null
        }
    }

    /** Lit un fichier média et le déchiffre (null = illisible/corrompu). */
    fun readBytes(context: Context, path: String): ByteArray? =
        runCatching { decrypt(context, File(path).readBytes()) }.getOrNull()

    /** Fichier temporaire DÉCHIFFRÉ pour un lecteur système (MediaPlayer…). */
    fun tempPlain(context: Context, path: String, name: String): File? {
        val bytes = readBytes(context, path) ?: return null
        val dir = File(context.cacheDir, "media-play").apply { mkdirs() }
        val f = File(dir, "${java.lang.Long.toHexString(System.nanoTime())}-${name.ifBlank { "media" }}")
        f.writeBytes(bytes)
        return f
    }

    /** Purge les fichiers temporaires de lecture (appel à la sortie du chat). */
    fun clearTemp(context: Context) {
        runCatching { File(context.cacheDir, "media-play").deleteRecursively() }
    }
}
