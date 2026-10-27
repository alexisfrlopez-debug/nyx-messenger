package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v0.2.54 — CODEC ADPCM IMA 4-bit (interop appels PC↔téléphone) : le MÊME
 * code vit dans le bureau Nyx v0.3.6 (desktop/…/p2p/Adpcm.kt). Ces tests
 * verrouillent le FORMAT FILAIRE : tout écart entre les deux côtés brise
 * l'interop — les mêmes bornes que le bureau (AdpcmTest desktop) doivent
 * tenir ICI, sur les MÊMES vecteurs.
 */
class AdpcmTest {

    /** Plancher de quantification IMA à l'index initial (pas 337 → ±42 + marge). */
    private val noiseFloor = 60

    private fun assertPcmClose(expected: ByteArray, actual: ByteArray, what: String) {
        assertEquals(expected.size, actual.size)
        for (j in expected.indices step 2) {
            val a = ((expected[j + 1].toInt() and 0xFF) shl 8) or (expected[j].toInt() and 0xFF)
            val b = ((actual[j + 1].toInt() and 0xFF) shl 8) or (actual[j].toInt() and 0xFF)
            val sa = if (a >= 0x8000) a - 0x10000 else a
            val sb = if (b >= 0x8000) b - 0x10000 else b
            assertTrue("$what — échantillon $j/2 : écart ${kotlin.math.abs(sa - sb)} > $noiseFloor", kotlin.math.abs(sa - sb) <= noiseFloor)
        }
    }

    @Test
    fun `roundtrip - silence pur (au plancher de quantification)`() {
        val pcm = ByteArray(Adpcm.PCM_BLOCK)
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        assertPcmClose(pcm, back, "silence")
    }

    @Test
    fun `roundtrip - constante nonzero (prédicteur initial exact, oscillation au plancher)`() {
        val pcm = ByteArray(Adpcm.PCM_BLOCK) { if (it % 2 == 0) 0x34 else 0x12 }
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        assertEquals(0x1234.toShort().toInt(), (((back[1].toInt() and 0xFF) shl 8) or (back[0].toInt() and 0xFF)).let { if (it >= 0x8000) it - 0x10000 else it })
        assertPcmClose(pcm, back, "constante")
    }

    @Test
    fun `roundtrip - bruit pleine echelle (erreur MOYENNE bornee, aucune derive)`() {
        val rnd = Random(42)   // MÊME graine que le bureau — mêmes vecteurs
        val pcm = ByteArray(Adpcm.PCM_BLOCK)
        var i = 0
        while (i < pcm.size) {
            val s = rnd.nextInt(-30000, 30000)
            pcm[i] = (s and 0xFF).toByte(); pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        var totalErr = 0L
        for (j in pcm.indices step 2) {
            val a = ((pcm[j + 1].toInt() and 0xFF) shl 8) or (pcm[j].toInt() and 0xFF)
            val b = ((back[j + 1].toInt() and 0xFF) shl 8) or (back[j].toInt() and 0xFF)
            val sa = if (a >= 0x8000) a - 0x10000 else a
            val sb = if (b >= 0x8000) b - 0x10000 else b
            totalErr += kotlin.math.abs(sa - sb)
            assertTrue("échantillon reconstruit hors échelle : $sb", sb in -32768..32767)
        }
        val meanErr = totalErr / (Adpcm.PCM_BLOCK / 2)
        assertTrue("erreur moyenne sur bruit pleine échelle excessive : $meanErr", meanErr < 6000)
    }

    @Test
    fun `voix - ecarts inter-echantillons moderes (contrat qualite reel)`() {
        val rnd = Random(11)   // MÊME graine que le bureau
        val pcm = ByteArray(Adpcm.PCM_BLOCK)
        var s = 500
        var i = 0
        while (i < pcm.size) {
            s = (s + rnd.nextInt(-1500, 1500)).coerceIn(-8000, 8000)
            pcm[i] = (s and 0xFF).toByte(); pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        var totalErr = 0L
        var maxErr = 0
        for (j in pcm.indices step 2) {
            val a = ((pcm[j + 1].toInt() and 0xFF) shl 8) or (pcm[j].toInt() and 0xFF)
            val b = ((back[j + 1].toInt() and 0xFF) shl 8) or (back[j].toInt() and 0xFF)
            val sa = if (a >= 0x8000) a - 0x10000 else a
            val sb = if (b >= 0x8000) b - 0x10000 else b
            val e = kotlin.math.abs(sa - sb)
            totalErr += e
            maxErr = maxOf(maxErr, e)
        }
        val meanErr = totalErr / (Adpcm.PCM_BLOCK / 2)
        assertTrue("erreur moyenne voix excessive : $meanErr", meanErr < 250)
        assertTrue("erreur max voix excessive : $maxErr", maxErr < 2500)
    }

    @Test
    fun `tailles filaires - 640 PCM vers 324 ADPCM, index initial 40`() {
        assertEquals(640, Adpcm.PCM_BLOCK)
        assertEquals(324, Adpcm.ADPCM_BLOCK)
        assertEquals(40, Adpcm.INITIAL_INDEX)
        assertEquals(Adpcm.ADPCM_BLOCK, Adpcm.encodeBlock(ByteArray(Adpcm.PCM_BLOCK)).size)
    }

    @Test
    fun `blocs fermes - pas de dependance entre trames`() {
        val rnd = Random(7)
        fun block(): ByteArray {
            val pcm = ByteArray(Adpcm.PCM_BLOCK)
            var i = 0
            while (i < pcm.size) {
                val s = rnd.nextInt(-20000, 20000)
                pcm[i] = (s and 0xFF).toByte(); pcm[i + 1] = ((s shr 8) and 0xFF).toByte()
                i += 2
            }
            return Adpcm.encodeBlock(pcm)
        }
        val b1 = block(); val b2 = block()
        val joined = Adpcm.decodeStream(listOf(b1, b2))
        val alone = Adpcm.decodeBlock(b2)
        assertArrayEquals(alone, joined.copyOfRange(Adpcm.PCM_BLOCK, joined.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `taille inattendue rejetee - encode`() {
        Adpcm.encodeBlock(ByteArray(639))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `taille inattendue rejetee - decode`() {
        Adpcm.decodeBlock(ByteArray(100))
    }
}
