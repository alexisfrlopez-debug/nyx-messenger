package net.nyx.messenger.crypto

import com.goterl.lazysodium.interfaces.Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests desktop du chiffrement — miroir des invariants Android :
 * sealed box aller-retour, signature/vérification, canonical NYX1,
 * vecteur onion (OnionCodec) + scellement d'identité desktop.
 */
class MessageCipherTest {

    private val cipher = MessageCipher.INSTANCE

    private fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(Sign.ED25519_PUBLICKEYBYTES)
        val sk = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        assertTrue((Sodium as Sign.Native).cryptoSignKeypair(pk, sk))
        return pk to sk
    }

    @Test
    fun `sealed box aller-retour`() {
        val (pk, sk) = newKeyPair()
        val plain = "Salut la nuit ☾ — é à ü".toByteArray(Charsets.UTF_8)
        val body = cipher.encryptFor(pk, plain)
        // body = base64, opaque, plus long que le clair (overhead seal 48 o).
        assertTrue(Base64.getDecoder().decode(body).size == plain.size + 48)
        val back = cipher.decryptForSelf(sk, body)
        assertNotNull(back)
        assertArrayEquals(plain, back)
    }

    @Test
    fun `sealed box non déchiffrable par un tiers`() {
        val (pk, _) = newKeyPair()
        val (_, otherSk) = newKeyPair()
        val body = cipher.encryptFor(pk, "secret".toByteArray())
        assertNull(cipher.decryptForSelf(otherSk, body))
    }

    @Test
    fun `signature détachée et vérification`() {
        val (pk, sk) = newKeyPair()
        val canonical = MessageCipher.canonical("msg", "abc.onion", 1, 2, 0, "to.onion", "AAAA")
        val sig = cipher.signEvent(canonical, sk)
        assertTrue(cipher.verifyEvent(canonical, sig, pk))
        assertTrue(!cipher.verifyEvent(canonical + "x", sig, pk))
        assertTrue(!cipher.verifyEvent(canonical, sig, ByteArray(32)))
    }

    @Test
    fun `canonical NYX1 - format exact`() {
        val c = MessageCipher.canonical("msg", "author.onion", 7, 1234, 0, "to.onion", "QUJD")
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("QUJD".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("NYX1\nmsg\nauthor.onion\n7\n1234\n0\nto.onion\n$expectedHash", c)
    }

    @Test
    fun `onion v3 - aller-retour et checksum`() {
        val (pk, _) = newKeyPair()
        val onion = OnionCodec.pubKeyToOnion(pk)
        assertEquals(62, onion.length) // 56 + ".onion"
        assertEquals(onion.substring(56), ".onion")
        assertArrayEquals(pk, OnionCodec.onionToPubKey(onion))
        // checksum invalide rejeté
        val bad = onion.toCharArray().also { it[0] = if (it[0] == 'a') 'b' else 'a' }
        assertTrue(runCatching { OnionCodec.onionToPubKey(String(bad)) }.isFailure)
    }
}
