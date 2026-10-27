package net.nyx.messenger.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.3.2 — PORTAGE MIROIR de [net.nyx.messenger.crypto.PasswordCrypto]
 * Android (v0.2.26) : déchiffrement/chiffrement des FICHIERS DE SAUVEGARDE
 * DE COMPTE générés par l'application téléphone — retour terrain
 * « je n'arrive pas à restaurer un fichier de sauvegarde de compte que
 * j'ai généré avec l'appli téléphone ».
 *
 * FORMAT (texte, identique octet par octet à l'Android) :
 *
 *     nyx-enc-v1.<b64 sel 16 o>.<b64 iv 12 o>.<b64 AES-256-GCM>
 *
 * DÉRIVATION : PBKDF2-HMAC-SHA256 150 000 itérations (clé 256 bits) —
 * la MÊME primitive que [net.nyx.messenger.desktop.DesktopIdentity.hashPassword]
 * (miroir de PasswordVault.hashPassword Android, audité/testé JVM des deux
 * côtés) → une enveloppe scellée par le téléphone s'ouvre sur le PC et
 * réciproquement. Intégrité/confidentialité : AES-256-GCM (tag 128 bits) —
 * un mot de passe incorrect échoue avec AEADBadTagException → decrypt()
 * renvoie null (jamais de crash).
 */
object PasswordCrypto {

    const val MAGIC = "nyx-enc-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** Enveloppe chiffrée, ou null si mot de passe incorrect / format invalide. */
    fun decrypt(envelope: String, password: CharArray): ByteArray? {
        // Miroir v0.2.27 Android : mot de passe VIDE → null, jamais de crash
        // (« password empty » levait sur PBEKeySpec).
        if (password.isEmpty()) return null
        val t = envelope.trim()
        if (!t.startsWith("$MAGIC.")) return null
        val parts = t.split(".")
        if (parts.size != 4 || parts[0] != MAGIC) return null
        return runCatching {
            val b64 = java.util.Base64.getDecoder()
            val salt = b64.decode(parts[1])
            val iv = b64.decode(parts[2])
            val ct = b64.decode(parts[3])
            val key = SecretKeySpec(
                net.nyx.messenger.desktop.DesktopIdentity.hashPassword(password, salt), "AES"
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }

    fun encrypt(plain: ByteArray, password: CharArray): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(
            net.nyx.messenger.desktop.DesktopIdentity.hashPassword(password, salt), "AES"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        val b64 = java.util.Base64.getEncoder()
        // Base64 sans retours à la ligne (URL-safe non requis — texte simple).
        val b = fun(x: ByteArray): String = b64.encodeToString(x)
        return "$MAGIC.${b(salt)}.${b(iv)}.${b(ct)}"
    }

    /** Le texte est-il une enveloppe chiffrée [MAGIC] ? */
    fun isEncrypted(text: String): Boolean = text.trim().startsWith("$MAGIC.")
}
