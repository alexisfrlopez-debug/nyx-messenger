package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.63 — tests de la QUALITÉ MJPEG ADAPTATIVE AU DÉBIT ([MjpegAimd],
 * demande 29/10 : « je veux que la qualité du flux video s'adapte au débit de
 * connexion entre les 2 messengers connectés »).
 *
 * L'échelle q30→q55 est pilotée par le write Tor : lent/rafales de trames
 * jetées = descente IMMÉDIATE d'un cran ; 24 writes rapides soutenus =
 * remontée ; JAMAIS au-dessus du sommet ni en dessous du plancher.
 */
class MjpegAimdTest {

    @Test
    fun `état initial au sommet`() {
        val s = MjpegAimd.State()
        assertTrue(s.atTop)
        assertFalse(s.atFloor)
        assertEquals(55, s.quality)
        assertEquals(MjpegAimd.LEVELS.size - 1, s.idx)
    }

    @Test
    fun `write lent - descente d'un cran`() {
        val s = MjpegAimd.onWrite(MjpegAimd.State(), 600, 0)
        assertEquals(MjpegAimd.LEVELS.size - 2, s.idx)
        assertEquals(46, s.quality)
        assertEquals(0, s.fastStreak) // la série de remontée est cassée
    }

    @Test
    fun `rafales de trames jetées - descente même à write rapide`() {
        // 3 trames jetées depuis le dernier write = circuit encombré.
        val s = MjpegAimd.onWrite(MjpegAimd.State(), 120, 3)
        assertEquals(MjpegAimd.LEVELS.size - 2, s.idx)
    }

    @Test
    fun `writes rapides soutenus - remontée après 24 trames`() {
        var s = MjpegAimd.State(idx = 0) // au plancher
        repeat(MjpegAimd.FAST_STREAK_TO_CLIMB - 1) {
            s = MjpegAimd.onWrite(s, 100, 0)
            assertEquals("pas de montée avant la 24ᵉ", 0, s.idx)
            assertEquals("la série compte", it + 1, s.fastStreak)
        }
        s = MjpegAimd.onWrite(s, 100, 0) // la 24ᵉ
        assertEquals(1, s.idx)
        assertEquals(0, s.fastStreak) // série consommée
    }

    @Test
    fun `jamais au-dessus du sommet`() {
        var s = MjpegAimd.State(idx = MjpegAimd.LEVELS.size - 1)
        repeat(60) { s = MjpegAimd.onWrite(s, 100, 0) }
        assertTrue(s.atTop)
        assertEquals(55, s.quality)
    }

    @Test
    fun `jamais en dessous du plancher`() {
        var s = MjpegAimd.State(idx = 0)
        repeat(5) { s = MjpegAimd.onWrite(s, 2_000, 4) }
        assertTrue(s.atFloor)
        assertEquals(30, s.quality)
    }

    @Test
    fun `write mitoyen - ni descente ni série, streak remis à zéro`() {
        val s = MjpegAimd.onWrite(MjpegAimd.State(idx = 0, fastStreak = 10), 300, 0)
        assertEquals(0, s.idx)
        assertEquals(0, s.fastStreak)
    }

    @Test
    fun `hystérésis - une lente casse la remontée en cours`() {
        var s = MjpegAimd.State(idx = 1)
        repeat(10) { s = MjpegAimd.onWrite(s, 100, 0) }
        assertEquals(10, s.fastStreak)
        s = MjpegAimd.onWrite(s, 600, 0) // une seule lente
        assertEquals(0, s.idx) // redescendu
        assertEquals(0, s.fastStreak)
    }

    @Test
    fun `descente puis remontée complètes - escalier symétrique`() {
        var s = MjpegAimd.State()
        // Le circuit se dégrade jusqu'au plancher :
        repeat(MjpegAimd.LEVELS.size - 1) { s = MjpegAimd.onWrite(s, 800, 0) }
        assertTrue(s.atFloor)
        // Le circuit respire : chaque cran exige 24 writes rapides.
        repeat(MjpegAimd.LEVELS.size - 1) {
            repeat(MjpegAimd.FAST_STREAK_TO_CLIMB) { s = MjpegAimd.onWrite(s, 80, 0) }
        }
        assertTrue(s.atTop)
        assertEquals(55, s.quality)
    }
}
