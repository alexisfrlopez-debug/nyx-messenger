package net.nyx.messenger.crypto

import org.bouncycastle.crypto.digests.SHA3Digest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM du codage onion v3 (tournent sur le host — pas d'émulateur requis).
 *
 * Les vecteurs attendus ont été produits par des implémentations INDÉPENDANTES :
 *  - SHA3-256 : vecteurs NIST FIPS 202 + hashlib.sha3_256 (Python)
 *  - checksum onion : hashlib.sha3_256(b".onion checksum" || pubkey || 0x03)[:2]
 */
class OnionCodecTest {

    private fun sha3x256(input: ByteArray): ByteArray {
        val h = SHA3Digest(256)
        h.update(input, 0, input.size)
        val out = ByteArray(h.digestSize)
        h.doFinal(out, 0)
        return out
    }

    @Test
    fun `sha3-256 conforme aux vecteurs NIST`() {
        // SHA3-256("") et SHA3-256("abc") — NIST FIPS 202 / exemples officiels
        assertArrayEquals(
            hexToBytes("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"),
            sha3x256(ByteArray(0)),
        )
        assertArrayEquals(
            hexToBytes("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"),
            sha3x256("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `checksum onion identique a hashlib python`() {
        // Vecteur généré indépendamment (Python 3.12, hashlib.sha3_256) :
        // pub = bytes(range(32)) → checksum b510,
        // onion aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion
        val pub = ByteArray(32) { it.toByte() }
        val onion = OnionCodec.pubKeyToOnion(pub)
        assertEquals("aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion", onion)
    }

    @Test
    fun `aller-retour onion vers pubkey`() {
        val pub = ByteArray(32) { (it * 7 + 3).toByte() }
        val onion = OnionCodec.pubKeyToOnion(pub)
        assertEquals(56 + ".onion".length, onion.length)
        assertArrayEquals(pub, OnionCodec.onionToPubKey(onion))
    }

    @Test
    fun `checksum invalide refuse`() {
        val pub = ByteArray(32) { it.toByte() }
        val onion = OnionCodec.pubKeyToOnion(pub)
        // Corrompt un caractère du checksum (position 32 de l'adresse)
        val broken = onion.substring(0, 32) + (if (onion[32] != 'a') 'a' else 'b') + onion.substring(33)
        val result = runCatching { OnionCodec.onionToPubKey(broken) }
        assertTrue(result.isFailure)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }
}
