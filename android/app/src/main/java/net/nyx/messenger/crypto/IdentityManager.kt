package net.nyx.messenger.crypto

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.util.Base64

/**
 * Identité locale : paire ed25519 dont la clé publique DEVIENT le service onion
 * de l'utilisateur (ADR-003). Clé privée scellée via KeystoreBox.
 *
 * Format stocké (préférences privées de l'app, mode MODE_PRIVATE) :
 *   onion         : adresse complète "xxx….onion"
 *   onion_pub     : base64 (32 octets)
 *   onion_priv    : base64 du scellé KeystoreBox(64 octets)
 *   alias         : pseudonyme unique optionnel (wire-format.md §6 — v0.1.2)
 *   alias_claimed : true une fois le claim accepté par un réplica
 */
class IdentityManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("nyx_identity", Context.MODE_PRIVATE)
    private val sodium = LazySodiumAndroid(SodiumAndroid())

    /* v0.2.13 — CORRECTIF TERRAIN CRITIQUE : ces deux champs n'étaient remplis
     * qu'en mémoire (createIfNeeded / restoreFromSeed), appelés UNIQUEMENT depuis
     * l'onboarding. Après CHAQUE relance du processus, onionAddress restait null
     * → « identité absente » sur TOUS les envois/annonces/acks + pollInbox muet
     * (logs v0.2.12 : ✗ annonce contact ÉCHOUÉE … identité absente, les 2
     * téléphones, toutes les 30 s) — c'est LA cause du blocage invitations/messages
     * constaté depuis la v0.2.9. Désormais les getters retombent sur les
     * préférences (même pattern que alias ci-dessous) : l'identité survit à toute
     * relance sans dépendre de l'onboarding. secretKey() lisait déjà les prefs —
     * c'est pourquoi Tor publiait l'onion pendant que le dépôt échouait. */
    var onionAddress: String? = null
        get() = field ?: prefs.getString(KEY_ONION, null)?.also { field = it }
        private set

    var publicKey: ByteArray? = null
        get() = field ?: prefs.getString(KEY_PUB, null)?.let {
            Base64.getDecoder().decode(it).also { pk -> field = pk }
        }
        private set

    val exists: Boolean get() = prefs.getString(KEY_PRIV, null) != null

    /** Pseudonyme unique optionnel (à l'onboarding — v0.1.2). */
    var alias: String? = null
        get() = field ?: prefs.getString(KEY_ALIAS, null)
        private set

    /** Le claim d'alias a été accepté par au moins un réplica. */
    var aliasClaimed: Boolean
        get() = prefs.getBoolean(KEY_ALIAS_CLAIMED, false)
        private set(value) = prefs.edit().putBoolean(KEY_ALIAS_CLAIMED, value).apply()

    /** Crée l'identité si absente. Retourne l'adresse onion. */
    @Synchronized
    fun createIfNeeded(): String {
        existsOrNull()?.let { return it }

        // 1. Paire ed25519 (libsodium — même primitive côté serveur Go)
        //    sk libsodium = 64 octets (seed||pk), pk = 32 octets.
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
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

    /**
     * Graine ed25519 (32 octets — [0..32) du format libsodium stocké) :
     * la clé de récupération du compte (v0.2.3). JAMAIS journalisée.
     */
    fun seed(): ByteArray = secretKey().copyOfRange(0, 32)

    /**
     * Restaure l'identité depuis une graine de récupération (v0.2.3) :
     * re-dérive la paire (même adresse onion garantie), scelle et écrase les
     * préférences locales. Retourne l'adresse restaurée.
     */
    @Synchronized
    fun restoreFromSeed(seed: ByteArray): String {
        require(seed.size == 32) { "graine ed25519 : ${seed.size} octets ≠ 32" }
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        sodium.cryptoSignSeedKeypair(pk, sk, seed)

        val onion = OnionCodec.pubKeyToOnion(pk)
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

    /** Enregistre l'alias choisi (vérifié avant appel — AliasValidator + réplica). */
    @Synchronized
    fun setAlias(name: String) {
        prefs.edit().putString(KEY_ALIAS, name).apply()
    }

    /** Marque le claim comme accepté (POST /client/event réussi). */
    @Synchronized
    fun markAliasClaimed() {
        prefs.edit().putBoolean(KEY_ALIAS_CLAIMED, true).apply()
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
        private const val KEY_ALIAS = "alias"
        private const val KEY_ALIAS_CLAIMED = "alias_claimed"

        @Volatile private var instance: IdentityManager? = null
        fun get(context: Context): IdentityManager =
            instance ?: synchronized(this) {
                instance ?: IdentityManager(context.applicationContext).also { instance = it }
            }
    }
}
