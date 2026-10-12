package net.nyx.messenger.crypto

import android.content.Context
import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Native
import java.util.Base64

/**
 * Identité locale : paire ed25519 dont la clé publique DEVIENT le service onion
 * de l'utilisateur (ADR-003). Clé privée scellée via KeystoreBox.
 *
 * Format stocké (préférences privées de l'app, mode MODE_PRIVATE) :
 *   onion         : adresse complète "xxx….onion"
 *   onion_pub     : base64 (32 octets)
 *   onion_priv    : base64 du scellé KeystoreBox(64 octets)
 */
class IdentityManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("nyx_identity", Context.MODE_PRIVATE)
    private val sodium = LazySodiumAndroid(Native.load("sodium", com.sun.jna.Pointer::class.java))

    var onionAddress: String? = null; private set
    var publicKey: ByteArray? = null; private set

    val exists: Boolean get() = prefs.getString(KEY_PRIV, null) != null

    /** Crée l'identité si absente. Retourne l'adresse onion. */
    @Synchronized
    fun createIfNeeded(): String {
        existsOrNull()?.let { return it }

        // 1. Paire ed25519 (libsodium — même primitive côté serveur Go)
        val pk = ByteArray(Sign.BYTES)
        val sk = ByteArray(Sign.SECRET_KEY_BYTES)
        sodium.cryptoSignKeypair(pk, sk)

        // 2. Adresse onion v3 = base32(pubkey || checksum || 0x03)
        val onion = OnionCodec.pubKeyToOnion(pk)

        // 3. Scellement de la clé privée via Keystore matériel
        val sealed = KeystoreBox.seal(sk)
        val b64 = Base64.getEncoder()
        prefs.edit()
            .putString(KEY_ONION, onion)
            .putString(KEY_PUB, b64.encodeToString(pk))
            .putString(KEY_PRIV, b64.encodeToString(sealed))
            .apply()

        onionAddress = onion
        publicKey = pk
        return onion
    }

    /** Charge l'identité existante (mémoire + champs publics). */
    private fun existsOrNull(): String? {
        onionAddress = prefs.getString(KEY_ONION, null)
        publicKey = prefs.getString(KEY_PUB, null)?.let { Base64.getDecoder().decode(it) }
        return onionAddress
    }

    /** Clé privée déscellée (usage mémoire uniquement — jamais journalisée). */
    fun secretKey(): ByteArray {
        val sealed = prefs.getString(KEY_PRIV, null)
            ?: error("identité absente — appeler createIfNeeded()")
        return KeystoreBox.open(Base64.getDecoder().decode(sealed))
    }

    /** Suppression totale (fonction « panique »). */
    fun destroy() {
        prefs.edit().clear().apply()
        onionAddress = null
        publicKey = null
    }

    companion object {
        private const val KEY_ONION = "onion"
        private const val KEY_PUB = "onion_pub"
        private const val KEY_PRIV = "onion_priv"

        @Volatile private var instance: IdentityManager? = null
        fun get(context: Context): IdentityManager =
            instance ?: synchronized(this) {
                instance ?: IdentityManager(context.applicationContext).also { instance = it }
            }
    }
}
