package net.nyx.messenger.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.LibraryLoader
import java.security.MessageDigest

/**
 * Chiffrement des corps de messages — portage desktop de MessageCipher Android
 * (v0.1 : sealed box libsodium, miroir exact ; v0.2 : Signal Protocol — ADR-002,
 * seule cette classe changera).
 *
 * LazySodium JAVA (et non Android) : mêmes interfaces Box.Native / Sign.Native
 * en byte[] bruts — les vecteurs de test JVM Android restent valides.
 * Chargement des natifs : PREFER_BUNDLED — l'artefact lazysodium-java embarque
 * libsodium pour windows64/windows/linux64/linux/mac ; repli système si absent.
 */
class MessageCipher private constructor() {

    private val sodium: LazySodiumJava = Sodium
    private val box = sodium as Box.Native
    private val sign = sodium as Sign.Native

    fun encryptFor(recipientEd25519Pub: ByteArray, plaintext: ByteArray): String {
        val curvePk = ByteArray(Box.PUBLICKEYBYTES)
        require(
            sodium.convertPublicKeyEd25519ToCurve25519(curvePk, recipientEd25519Pub)
        ) { "conversion ed25519→curve25519 impossible (mauvaise clé ?)" }

        val sealed = ByteArray(plaintext.size + Box.SEALBYTES)
        require(box.cryptoBoxSeal(sealed, plaintext, plaintext.size.toLong(), curvePk)) {
            "crypto_box_seal a échoué"
        }
        return B64.encode(sealed)
    }

    fun decryptForSelf(selfEd25519Priv: ByteArray, bodyB64: String): ByteArray? {
        return try {
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

    fun signEvent(canonical: String, ed25519Priv: ByteArray): String {
        val msg = canonical.toByteArray(Charsets.UTF_8)
        val sig = ByteArray(Sign.BYTES)
        require(sign.cryptoSignDetached(sig, msg, msg.size.toLong(), ed25519Priv)) {
            "signature impossible"
        }
        return B64.encode(sig)
    }

    fun verifyEvent(canonical: String, sigB64: String, ed25519Pub: ByteArray): Boolean {
        return try {
            val msg = canonical.toByteArray(Charsets.UTF_8)
            val sig = B64.decode(sigB64)
            if (sig.size != Sign.BYTES || ed25519Pub.size != Sign.ED25519_PUBLICKEYBYTES) return false
            sign.cryptoSignVerifyDetached(sig, msg, msg.size, ed25519Pub)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Instance unique — libsodium n'est chargée qu'une fois par processus. */
        val INSTANCE: MessageCipher = MessageCipher()

        /** Représentation canonique NYX1 — DOIT rester identique au serveur Go. */
        fun canonical(type: String, author: String, seq: Long, ts: Long, ttl: Long, to: String, bodyB64: String): String {
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyB64.toByteArray(Charsets.UTF_8))
            val hex = bodyHash.joinToString("") { "%02x".format(it) }
            return "NYX1\n$type\n$author\n$seq\n$ts\n$ttl\n$to\n$hex"
        }
    }
}

/**
 * Instance libsodium du processus desktop — chargement paresseux unique.
 * PREFER_BUNDLED : la dll/so embarquée dans le jar est extraite vers le
 * répertoire temporaire et chargée par JNA (repli : libsodium système).
 */
object Sodium : LazySodiumJava(SodiumJava(LibraryLoader.Mode.PREFER_BUNDLED))
