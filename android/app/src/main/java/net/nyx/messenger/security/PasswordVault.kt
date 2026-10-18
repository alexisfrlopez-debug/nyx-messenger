package net.nyx.messenger.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * v0.2.18 — LES TROIS MOTS DE PASSE (demande opérateur) :
 *
 *  - NORMAL  : comme la biométrie — ouvre l'application normalement ;
 *  - PANIQUE : ouvre l'application sur une base LEURRE vide (liste de contacts
 *    et historique apparents : aucun) — le vrai compte est intact et repris
 *    au prochain déverrouillage normal ;
 *  - DESTRUCTION : supprime IMMÉDIATEMENT contacts, historique, fichiers et
 *    identité (reset du compte → onboarding) — pas de confirmation, c'est son
 *    rôle en cas de contrainte.
 *
 * STOCKAGE : hachage PBKDF2-HMAC-SHA256 (150 000 itérations, sel 16 octets,
 * comparaison à temps constant) dans les préférences locales — l'application
 * ne conserve JAMAIS les mots de passe en clair. Objet testable (fonctions
 * pures sans Android pour le hachage).
 */
object PasswordVault {

    enum class Mode { NORMAL, PANIC, DESTRUCTION }

    private const val PREFS = "nyx_security"
    private const val KEY_PENDING = "pending_mode"
    private const val ITERATIONS = 150_000
    private const val KEYLEN_BITS = 256

    // ---------- hachage (pur, testé JVM) ----------

    fun hashPassword(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEYLEN_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun encode(salt: ByteArray, hash: ByteArray): String =
        salt.joinToString("") { "%02x".format(it) } + ":" +
            hash.joinToString("") { "%02x".format(it) }

    /** Sel + hash depuis « salt:hash » hex — null si malformé. */
    fun decode(stored: String): Pair<ByteArray, ByteArray>? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        fun hex(s: String): ByteArray {
            require(s.length % 2 == 0 && s.length >= 32)
            return ByteArray(s.length / 2) { i ->
                ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            }
        }
        return runCatching { hex(parts[0]) to hex(parts[1]) }.getOrNull()
    }

    // ---------- état (préférences locales) ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyOf(mode: Mode) = "pwd_${mode.name.lowercase()}"

    fun isSet(context: Context, mode: Mode): Boolean =
        prefs(context).getString(keyOf(mode), null) != null

    fun anySet(context: Context): Boolean =
        Mode.entries.any { isSet(context, it) }

    /** Pose (ou remplace) un mot de passe — vide = refusé. */
    fun set(context: Context, mode: Mode, password: String): Boolean {
        if (password.isBlank()) return false
        val salt = newSalt()
        val hash = hashPassword(password.toCharArray(), salt)
        // Garde : le mot de passe panique/destruction ne doit pas égaler le normal.
        for (other in Mode.entries) {
            if (other == mode) continue
            if (verifyAgainst(context, other, password)) return false
        }
        prefs(context).edit().putString(keyOf(mode), encode(salt, hash)).apply()
        return true
    }

    fun clear(context: Context, mode: Mode) {
        prefs(context).edit().remove(keyOf(mode)).apply()
    }

    private fun verifyAgainst(context: Context, mode: Mode, password: String): Boolean {
        // v0.2.27 — CRASH TERRAIN v0.2.26 (09:00:45, Blackview) : un champ de
        // mot de passe confirmé vide arrivait ici → PBEKeySpec(vide) levait
        // « IllegalArgumentException: password empty » (FATAL, app kill).
        // Garde unique : un mot de passe vide ne peut JAMAIS vérifier.
        if (password.isEmpty()) return false
        val stored = prefs(context).getString(keyOf(mode), null) ?: return false
        val (salt, hash) = decode(stored) ?: return false
        return constantTimeEquals(hashPassword(password.toCharArray(), salt), hash)
    }

    /**
     * Vérifie un mot de passe saisi sur l'écran de verrou — renvoie le MODE
     * correspondant, ou null (échec). La vérification couvre les trois rôles.
     */
    fun verify(context: Context, password: String): Mode? {
        if (password.isEmpty()) return null // v0.2.27 — cf. verifyAgainst (crash terrain)
        for (mode in Mode.entries) {
            if (verifyAgainst(context, mode, password)) return mode
        }
        return null
    }

    // ---------- mode en attente (passage panique ↔ normal) ----------

    /**
     * Le changement de mode (panique ↔ normal) exige un redémarrage du process
     * (la base Room est choisie à l'initialisation) : LockScreen pose le mode
     * ICI puis tue le process ; le prochain [consumePendingMode] — au boot —
     * décide de la base ouverte.
     */
    fun setPendingMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_PENDING, mode.name).apply()
    }

    /** Lu UNE FOIS par ServiceLocator.init — NORMAL si absent (consommé = effacé). */
    fun consumePendingMode(context: Context): Mode {
        val name = prefs(context).getString(KEY_PENDING, null)
        if (name != null) prefs(context).edit().remove(KEY_PENDING).apply()
        return Mode.entries.firstOrNull { it.name == name } ?: Mode.NORMAL
    }
}
