package net.nyx.messenger.desktop

import com.goterl.lazysodium.interfaces.Sign
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.nyx.messenger.crypto.B64
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.crypto.Sodium
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identité locale desktop — portage de IdentityManager Android (ADR-003) :
 * paire ed25519 dont la clé publique EST l'adresse onion de l'utilisateur.
 *
 * SCELLEMENT (spec desktop-v1.md §3) : la clé privée (64 o libsodium
 * seed||pk) vit chiffrée AES-256-GCM, clé dérivée PBKDF2-HMAC-SHA256
 * 150 000 itérations — les MÊMES primitives JCA que PasswordVault /
 * PasswordVault Android (auditées, testées JVM). Sans mot de passe : sel
 * aléatoire local (l'identité reste confinée au profil utilisateur) ;
 * avec mot de passe : la clé ne quitte pas la machine sans lui.
 *
 * Format identity.json :
 *   { v, onion, onion_pub, alias?, alias_claimed, seal:{salt,nonce,box} }
 */
class DesktopIdentity(private val file: File) {

    @Serializable
    data class Seal(val salt: String, val nonce: String, val box: String)

    @Serializable
    data class Stored(
        val v: Int = 1,
        val onion: String,
        val onionPub: String,
        val alias: String? = null,
        val aliasClaimed: Boolean = false,
        val seal: Seal,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var cached: Stored? = null
    @Volatile private var cachedPriv: ByteArray? = null

    val exists: Boolean get() = file.isFile

    /** Adresse onion courante (null = pas d'identité). */
    val onionAddress: String? get() = cached?.onion ?: load()?.onion

    val publicKey: ByteArray? get() = cached?.let { B64.decode(it.onionPub) }
        ?: load()?.let { B64.decode(it.onionPub) }

    val alias: String? get() = cached?.alias ?: load()?.alias

    var aliasClaimed: Boolean
        get() = cached?.aliasClaimed ?: load()?.aliasClaimed ?: false
        set(value) {
            val cur = requireStored()
            cached = cur.copy(aliasClaimed = value)
            persist()
        }

    /** Clé privée déscellée (cache mémoire après le premier appel). */
    fun secretKey(password: CharArray? = null): ByteArray {
        cachedPriv?.let { return it }
        val st = requireStored()
        val priv = unseal(st.seal, password) ?: error(
            "clé d'identité indéchiffrable (mot de passe incorrect ou fichier altéré)"
        )
        cachedPriv = priv
        return priv
    }

    /** Crée l'identité si absente — retourne l'adresse onion. */
    @Synchronized
    fun createIfNeeded(password: CharArray? = null, alias: String? = null): String {
        load()?.let { return it.onion }
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        require((Sodium as Sign.Native).cryptoSignKeypair(pk, sk)) { "crypto_sign_keypair a échoué" }
        val onion = OnionCodec.pubKeyToOnion(pk)
        val stored = Stored(
            onion = onion,
            onionPub = B64.encode(pk),
            alias = alias,
            seal = seal(sk, password),
        )
        cached = stored
        cachedPriv = sk
        persist()
        return onion
    }

    /** Restaure une identité depuis une graine (RecoveryKey — même compte, même onion). */
    @Synchronized
    fun restoreFromSeed(key: String, password: CharArray? = null): String {
        val seed = RecoveryKey.decode(key) // checksum vérifié — faute de frappe rejetée
        val sk = seed + derivePublicFromSeed(seed)
        val onion = OnionCodec.pubKeyToOnion(sk.copyOfRange(32, 64))
        val stored = Stored(
            onion = onion,
            onionPub = B64.encode(sk.copyOfRange(32, 64)),
            alias = null,
            seal = seal(sk, password),
        )
        cached = stored
        cachedPriv = sk
        persist()
        return onion
    }

    fun setAlias(name: String?) {
        val cur = requireStored()
        cached = cur.copy(alias = name)
        persist()
    }

    /** EFFACE tout (Wipe / mot de passe destruction) — retour au nouvel onboarding. */
    @Synchronized
    fun wipe() {
        cached = null
        cachedPriv = null
        file.delete()
    }

    private fun requireStored(): Stored =
        cached ?: load() ?: error("identité absente")

    private fun load(): Stored? {
        if (!file.isFile) return null
        val st = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()
        cached = st
        return st
    }

    private fun persist() {
        cached?.let { JsonStore.write(file, Stored.serializer(), it) }
    }

    // ---------- scellement AES-GCM + PBKDF2 (miroir Android PasswordVault/PasswordCrypto) ----------

    private fun seal(plain: ByteArray, password: CharArray?): Seal {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password ?: CharArray(0), salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val box = cipher.doFinal(plain)
        return Seal(B64.encode(salt), B64.encode(nonce), B64.encode(box))
    }

    private fun unseal(seal: Seal, password: CharArray?): ByteArray? = runCatching {
        val key = deriveKey(password ?: CharArray(0), B64.decode(seal.salt))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, B64.decode(seal.nonce)))
        cipher.doFinal(B64.decode(seal.box))
    }.getOrNull()

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** ed25519 pk depuis la graine (libsodium : expand seed→pk). */
    private fun derivePublicFromSeed(seed: ByteArray): ByteArray {
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        require((Sodium as Sign.Native).cryptoSignSeedKeypair(pk, sk, seed)) { "crypto_sign_seed_keypair a échoué" }
        return pk
    }

    companion object {
        private const val ITERATIONS = 150_000

        /** Hachage de mot de passe (miroir PasswordVault.hashPassword — tests croisés). */
        fun hashPassword(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
            return try {
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
    }
}
