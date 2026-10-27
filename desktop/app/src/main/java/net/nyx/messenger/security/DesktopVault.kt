package net.nyx.messenger.security

import kotlinx.serialization.Serializable
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.desktop.JsonStore
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Portage desktop de security/PasswordVault.kt (Android v0.2.18) — v0.3.4
 * « parité Android » : les TROIS mots de passe du compte.
 *
 *  - NORMAL      : déverrouille l'application (écran de verrouillage) ;
 *  - PANIC       : leurre — ouvre une SESSION LEURRE (données vides, Tor
 *                  coupé : miroir SessionMode.PANIC Android) ;
 *  - DESTRUCTION : efface TOUT sur-le-champ (protection en cas de contrainte).
 *
 * Stockage : `<dataDir>/security.json` — par mode « salt:hash » HEX (sel 16 o,
 * hash 32 o), PBKDF2-HMAC-SHA256 **150 000 itérations / 256 bits** — MÊMES
 * paramètres que l'Android (hashPassword de PasswordVault). Comparaison en
 * temps constant (MessageDigest.isEqual). L'Android garde ces valeurs en
 * SharedPreferences : le fichier JSON local 0600 (JsonStore) est l'équivalent
 * strict (rien n'entre en clair, le hash ne part JAMAIS sur le réseau).
 *
 * Garde-fous miroir : refus des mots de passe vides et des collisions entre
 * rôles (« déjà utilisé par un autre rôle »).
 */
object DesktopVault {

    enum class Mode { NORMAL, PANIC, DESTRUCTION }

    @Serializable
    private data class VaultData(
        val normal: String = "",
        val panic: String = "",
        val destruction: String = "",
        val pendingMode: String = "",
    )

    private val file: File
        get() = File(DesktopPaths.dataDir, "security.json")

    private fun load(): VaultData =
        JsonStore.read(file, VaultData.serializer()) ?: VaultData()

    private fun save(v: VaultData) = JsonStore.write(file, VaultData.serializer(), v)

    // ---------------- hachage (paramètres Android exacts) ----------------

    internal fun hashPassword(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 150_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hash
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

    private fun encode(salt: ByteArray, hash: ByteArray): String = "${toHex(salt)}:${toHex(hash)}"

    private fun verify(stored: String, password: CharArray): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2 || parts[0].length != 32 || parts[1].length != 64) return false
        val salt = fromHex(parts[0])
        val expected = fromHex(parts[1])
        val actual = hashPassword(password, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    // ---------------- API ----------------

    /** Le mot de passe [mode] est-il défini ? */
    fun isSet(mode: Mode): Boolean = load().field(mode).isNotBlank()

    /** true si AU MOINS un rôle est défini (écran de verrouillage requis). */
    fun anySet(): Boolean = load().let { it.normal.isNotBlank() || it.panic.isNotBlank() || it.destruction.isNotBlank() }

    /**
     * Définit le mot de passe d'un rôle. Refus (exception) : vide OU déjà
     * utilisé par un AUTRE rôle (miroir PasswordVault.set).
     */
    fun set(mode: Mode, password: CharArray) {
        if (password.isEmpty() || String(password).isBlank()) error("mot de passe vide")
        val v = load()
        for (other in Mode.entries) {
            if (other != mode && v.field(other).isNotBlank() && verify(v.field(other), password)) {
                error("ce mot de passe est déjà utilisé par un autre rôle — choisissez-en un autre")
            }
        }
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val updated = when (mode) {
            Mode.NORMAL -> v.copy(normal = encode(salt, hashPassword(password, salt)))
            Mode.PANIC -> v.copy(panic = encode(salt, hashPassword(password, salt)))
            Mode.DESTRUCTION -> v.copy(destruction = encode(salt, hashPassword(password, salt)))
        }
        save(updated)
        DesktopLog.i("nyx-vault", "mot de passe ${mode.name.lowercase()} défini")
    }

    /** Retire un rôle (miroir « remove » Android). */
    fun remove(mode: Mode) {
        val v = load()
        save(when (mode) {
            Mode.NORMAL -> v.copy(normal = "")
            Mode.PANIC -> v.copy(panic = "")
            Mode.DESTRUCTION -> v.copy(destruction = "")
        })
    }

    /** Modifie le mot de passe d'un rôle (après vérification de l'ancien). */
    fun change(mode: Mode, oldPassword: CharArray, newPassword: CharArray) {
        val v = load()
        if (v.field(mode).isBlank()) error("aucun mot de passe ${mode.name.lowercase()} défini")
        if (!verify(v.field(mode), oldPassword)) error("ancien mot de passe incorrect")
        set(mode, newPassword)
    }

    /** Vérifie un mot de passe contre les TROIS rôles (miroir verify). */
    fun verify(password: CharArray): Mode? {
        val v = load()
        for (mode in Mode.entries) {
            val stored = v.field(mode)
            if (stored.isNotBlank() && verify(stored, password)) return mode
        }
        return null
    }

    // ---------------- session leurre (miroir pending_mode + SessionMode) ----------------

    /** Mode de session en attente (consommé UNE fois au démarrage suivant). */
    fun setPendingMode(mode: Mode) {
        val v = load()
        save(v.copy(pendingMode = mode.name))
    }

    fun consumePendingMode(): Mode {
        val v = load()
        val mode = runCatching { Mode.valueOf(v.pendingMode) }.getOrNull() ?: Mode.NORMAL
        if (v.pendingMode.isNotBlank()) save(v.copy(pendingMode = ""))
        return mode
    }

    /** Wipe du coffre (appelé par la destruction / la déconnexion de compte). */
    fun wipe() {
        runCatching { file.delete() }
    }

    private fun VaultData.field(mode: Mode): String = when (mode) {
        Mode.NORMAL -> normal
        Mode.PANIC -> panic
        Mode.DESTRUCTION -> destruction
    }
}
