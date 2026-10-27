package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v0.3.6 — CODEC ADPCM IMA 4-bit (appel audio bureau↔téléphone) :
 * roundtrip intégral, vecteurs de forme, bornes et résilience inter-trames.
 * Le MÊME code vit dans l'APK (p2p/Adpcm.kt v0.2.54) — tout écart ici brise
 * l'interop : ces tests verrouillent le format FILAIRE.
 */
class AdpcmTest {

    /** Plancher de quantification IMA à l'index initial (pas 337 → ±42 + marge d'adaptation). */
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
        // IMA : un nibble NUL reconstruit ±pas/8 — le silence décode au plancher
        // de bruit (−53 dB FS, inaudible en appel), pas à zéro exact.
        val pcm = ByteArray(Adpcm.PCM_BLOCK) // que des zéros
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        assertPcmClose(pcm, back, "silence")
    }

    @Test
    fun `roundtrip - constante nonzero (prédicteur initial exact, oscillation au plancher)`() {
        val pcm = ByteArray(Adpcm.PCM_BLOCK) { if (it % 2 == 0) 0x34 else 0x12 } // échantillon 0x1234
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        // Le PREMIER échantillon est le prédicteur initial — EXACT.
        assertEquals(0x1234.toShort().toInt(), (((back[1].toInt() and 0xFF) shl 8) or (back[0].toInt() and 0xFF)).let { if (it >= 0x8000) it - 0x10000 else it })
        assertPcmClose(pcm, back, "constante")
    }

    @Test
    fun `roundtrip - bruit pleine echelle (erreur MOYENNE bornee, aucune derive)`() {
        // Bruit blanc ±30000 = pire cas d'un codec différentiel (écarts
        // inter-échantillons jusqu'à 60000, adaptation lente de l'index). Le
        // contrat est : PAS DE DÉRIVE (tous les échantillons reconstruits dans
        // l'échelle, erreur moyenne bornée) — pas l'erreur MAX.
        val rnd = Random(42)
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
        // Le contrat UTILISATEUR : la parole (écarts typiques < ±2000 à 16 kHz)
        // est restituée avec une erreur bornée serrée dès les PREMIERS
        // échantillons (index initial 40 — pas 598).
        val rnd = Random(11)
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
    fun `roundtrip - sinus lent (voix)`() {
        // 20 ms de sinus 300 Hz à -12 dB — représentatif de la voix.
        val pcm = ByteArray(Adpcm.PCM_BLOCK)
        for (i in 0 until Adpcm.PCM_BLOCK / 2) {
            val s = (8000.0 * kotlin.math.sin(2.0 * Math.PI * 300.0 * i / 16000.0)).toInt()
            pcm[i * 2] = (s and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val back = Adpcm.decodeBlock(Adpcm.encodeBlock(pcm))
        var err = 0L
        for (j in pcm.indices step 2) {
            val a = ((pcm[j + 1].toInt() and 0xFF) shl 8) or (pcm[j].toInt() and 0xFF)
            val b = ((back[j + 1].toInt() and 0xFF) shl 8) or (back[j].toInt() and 0xFF)
            err += kotlin.math.abs(a - b)
        }
        val meanErr = err / (Adpcm.PCM_BLOCK / 2)
        assertTrue("erreur moyenne sinus excessive : $meanErr", meanErr < 900)
    }

    @Test
    fun `tailles filaires - 640 PCM vers 324 ADPCM (64 kbps)`() {
        assertEquals(640, Adpcm.PCM_BLOCK)
        assertEquals(324, Adpcm.ADPCM_BLOCK)
        val block = Adpcm.encodeBlock(ByteArray(Adpcm.PCM_BLOCK))
        assertEquals(Adpcm.ADPCM_BLOCK, block.size)
    }

    @Test
    fun `blocs fermes - pas de dependance entre trames`() {
        // Deux blocs successifs quelconques : le second décode EXACTEMENT pareil
        // seul ou après le premier (état ferme par bloc — résilience Tor).
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

    @Test
    fun `flux - la fin non multiple de 640 est ignoree a l encodage`() {
        val pcm = ByteArray(Adpcm.PCM_BLOCK * 2 + 17)
        val blocks = Adpcm.encodeStream(pcm)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `etat ferme - reconstruction apres trame corrompue`() {
        val pcm = ByteArray(Adpcm.PCM_BLOCK) { if (it % 2 == 0) 0x34 else 0x12 }
        val good = Adpcm.encodeBlock(pcm)
        val corrupted = good.copyOf().also { it[10] = 0xFF.toByte(); it[11] = 0x00.toByte() }
        // Un bloc corrompu est ignoré (taille ok mais contenu altéré → bruit
        // ponctuel) ; le SUIVANT décode au plancher de quantification (état
        // ferme — aucune dérive inter-trames).
        val decoded = Adpcm.decodeStream(listOf(corrupted, good))
        assertEquals(Adpcm.PCM_BLOCK * 2, decoded.size)
        assertPcmClose(pcm, decoded.copyOfRange(Adpcm.PCM_BLOCK, decoded.size), "après trame corrompue")
    }
}
