package net.nyx.messenger.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import java.security.MessageDigest

/**
 * Chiffrement des corps de messages — v0.1 : sealed box libsodium
 * (crypto_box_seal vers la clé X25519 dérivée de la clé ed25519 du destinataire).
 *
 * On passe par les interfaces [Box.Native] / [Sign.Native] (byte[] bruts, miroir
 * exact de libsodium) : les variantes « Lazy » encodent via String et corrompraient
 * les corps binaires. Clé privée ed25519 au format libsodium (64 octets = seed||pk).
 *
 * v0.2 : remplacement par Signal Protocol (X3DH + Double Ratchet) — ADR-002.
 * L'interface [encryptFor] / [decryptFor] est conçue pour que la migration
 * ne touche que cette classe.
 */
class MessageCipher(sodiumInstance: LazySodiumAndroid) {

    private val sodium = sodiumInstance
    private val box = sodium as Box.Native
    private val sign = sodium as Sign.Native

    /**
     * Chiffre [plaintext] pour [recipientEd25519Pub] (clé publique ed25519 du contact).
     * Retourne le body base64 (opaque au serveur).
     */
    fun encryptFor(recipientEd25519Pub: ByteArray, plaintext: ByteArray): String {
        // conversion ed25519→curve25519 : (sortie curve, entrée ed)
        val curvePk = ByteArray(Box.PUBLICKEYBYTES)
        require(
            sodium.convertPublicKeyEd25519ToCurve25519(curvePk, recipientEd25519Pub)
        ) { "conversion ed25519→curve25519 impossible (mauvaise clé ?)" }

        // crypto_box_seal : paire éphémère interne + box au destinataire
        val sealed = ByteArray(plaintext.size + Box.SEALBYTES)
        require(box.cryptoBoxSeal(sealed, plaintext, plaintext.size.toLong(), curvePk)) {
            "crypto_box_seal a échoué"
        }
        return B64.encode(sealed)
    }

    /**
     * Déchiffre un body sealed box avec la clé privée ed25519 locale.
     * Retourne null si non déchiffrable (pas pour nous, altéré…).
     */
    fun decryptForSelf(selfEd25519Priv: ByteArray, bodyB64: String): ByteArray? {
        return try {
            // sk libsodium = seed(32) || pk(32) — la partie publique sert au seal open
            val edPub = selfEd25519Priv.copyOfRange(32, 64)
            val curveSk = ByteArray(Box.SECRETKEYBYTES)
            val curvePk = ByteArray(Box.PUBLICKEYBYTES)
            require(sodium.convertSecretKeyEd25519ToCurve25519(curveSk, selfEd25519Priv)) {
                "conversion sk ed25519→curve25519 impossible"
            }
            require(sodium.convertPublicKeyEd25519ToCurve25519(curvePk, edPub)) {
                "conversion pk ed25519→curve25519 impossible"
            }
            val sealed = B64.decode(bodyB64)
            val plain = ByteArray(sealed.size - Box.SEALBYTES)
            if (!box.cryptoBoxSealOpen(plain, sealed, sealed.size.toLong(), curvePk, curveSk)) {
                return null
            }
            plain
        } catch (_: Exception) {
            null
        }
    }

    /** Signe la représentation canonique d'un événement (cf. protocol/specs/wire-format.md). */
    fun signEvent(canonical: String, ed25519Priv: ByteArray): String {
        val msg = canonical.toByteArray(Charsets.UTF_8)
        val sig = ByteArray(Sign.BYTES)
        require(sign.cryptoSignDetached(sig, msg, msg.size.toLong(), ed25519Priv)) {
            "signature impossible"
        }
        return B64.encode(sig)
    }

    companion object {
        /** Représentation canonique NYX1 — DOIT rester identique au serveur Go. */
        fun canonical(type: String, author: String, seq: Long, ts: Long, ttl: Long, to: String, bodyB64: String): String {
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyB64.toByteArray(Charsets.UTF_8))
            val hex = bodyHash.joinToString("") { "%02x".format(it) }
            return "NYX1\n$type\n$author\n$seq\n$ts\n$ttl\n$to\n$hex"
        }
    }
}
