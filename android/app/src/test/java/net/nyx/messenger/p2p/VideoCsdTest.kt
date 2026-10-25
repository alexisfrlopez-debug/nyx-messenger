package net.nyx.messenger.p2p

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * v0.2.52 — tests JVM du csd H.264 ([VideoCsd]) : la panne terrain 25/10
 * (« visio annoncée dans le HELLO (csd 0 o) » → appelé en audio-only) est
 * exactement un csd VIDE accepté comme valide. Ces vecteurs verrouillent
 * l'extraction et la validation.
 */
class VideoCsdTest {

    /** SPS plausible (start code 00 00 00 01 + NAL 0x67 + charge). */
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42.toByte(), 0x00, 0x1E.toByte(), 0x0A)

    /** PPS plausible (start code 00 00 01 + NAL 0x68 + charge). */
    private val pps = byteArrayOf(0, 0, 1, 0x68.toByte(), 0xCE.toByte(), 0x03.toByte(), 0x84.toByte())

    @Test
    fun `fromFormat concatene sps puis pps`() {
        val csd = VideoCsd.fromFormat(sps, pps)
        assertEquals(sps.size + pps.size, csd.size)
        // le préfixe est le SPS
        for (i in sps.indices) assertEquals(sps[i], csd[i])
    }

    @Test
    fun `fromFormat sans pps reste le sps seul`() {
        val csd = VideoCsd.fromFormat(sps, null)
        assertEquals(sps.size, csd.size)
    }

    @Test
    fun `fromFormat vide des deux cotes rend vide`() {
        assertEquals(0, VideoCsd.fromFormat(null, null).size)
        assertEquals(0, VideoCsd.fromFormat(ByteArray(0), ByteArray(0)).size)
    }

    @Test
    fun `le csd 0 octet du terrain est INVALIDE`() {
        // « visio annoncée dans le HELLO (csd 0 o) » — la panne du 25/10.
        assertFalse(VideoCsd.looksValid(ByteArray(0)))
    }

    @Test
    fun `le csd factice 2 octets est INVALIDE`() {
        assertFalse(VideoCsd.looksValid(byteArrayOf(0, 0)))
    }

    @Test
    fun `sps+pps start code 4 octets est VALIDE`() {
        assertTrue(VideoCsd.looksValid(sps + pps))
    }

    @Test
    fun `start code 3 octets est VALIDE aussi`() {
        val s3 = byteArrayOf(0, 0, 1, 0x67, 0x42.toByte(), 0x00, 0x1E.toByte(), 0x0A)
        val p3 = byteArrayOf(0, 0, 1, 0x68.toByte(), 0xCE.toByte(), 0x03.toByte())
        assertTrue(VideoCsd.looksValid(s3 + p3))
    }

    @Test
    fun `charge sans start code est INVALIDE`() {
        // 8+ octets de bruit sans 00 00 01 : ce n'est pas un SPS.
        val noise = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9)
        assertFalse(VideoCsd.looksValid(noise))
    }

    @Test
    fun `isCodecConfig reflète le bit 2 des flags MediaCodec`() {
        // android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG = 2.
        assertTrue(VideoCsd.isCodecConfig(2))
        assertTrue(VideoCsd.isCodecConfig(2 or 1))
        assertFalse(VideoCsd.isCodecConfig(0))
        assertFalse(VideoCsd.isCodecConfig(1))
        assertFalse(VideoCsd.isCodecConfig(4))
    }

    @Test
    fun `un csd trop court mais avec start code reste invalide`() {
        // 3 octets = start code seul, pas de NAL : inexploitable.
        assertFalse(VideoCsd.looksValid(byteArrayOf(0, 0, 1)))
    }
}
