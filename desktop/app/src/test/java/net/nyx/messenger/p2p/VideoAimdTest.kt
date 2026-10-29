package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.15 — tests de la QUALITÉ VIDÉO ADAPTATIVE AU DÉBIT ([VideoAimd],
 * miroir Android MjpegAimd v0.2.63 — demande 29/10 : « je veux que la
 * qualité du flux video s'adapte au débit de connexion entre les 2
 * messengers connectés »).
 *
 * Échelle q0.30→q0.55 pilotée par le write Tor : lent = descente immédiate,
 * 24 writes rapides soutenus = remontée, bornes strictes (jamais au-dessus
 * du sommet, jamais sous le plancher).
 */
class VideoAimdTest {

    @Test
    fun `état initial au sommet`() {
        val s = VideoAimd.State()
        assertTrue(s.atTop)
        assertFalse(s.atFloor)
        assertEquals(0.55f, s.quality, 1e-6f)
    }

    @Test
    fun `write lent - descente d'un cran`() {
        val s = VideoAimd.onWrite(VideoAimd.State(), 600)
        assertEquals(1, s.idx)
        assertEquals(0.40f, s.quality, 1e-6f)
        assertEquals(0, s.fastStreak)
    }

    @Test
    fun `writes rapides soutenus - remontée après 24 trames`() {
        var s = VideoAimd.State(idx = 0)
        repeat(VideoAimd.FAST_STREAK_TO_CLIMB - 1) {
            s = VideoAimd.onWrite(s, 100)
            assertEquals(0, s.idx)
        }
        s = VideoAimd.onWrite(s, 100)
        assertEquals(1, s.idx)
    }

    @Test
    fun `jamais au-dessus du sommet`() {
        var s = VideoAimd.State()
        repeat(60) { s = VideoAimd.onWrite(s, 100) }
        assertTrue(s.atTop)
        assertEquals(0.55f, s.quality, 1e-6f)
    }

    @Test
    fun `jamais en dessous du plancher`() {
        var s = VideoAimd.State(idx = 0)
        repeat(4) { s = VideoAimd.onWrite(s, 3_000) }
        assertTrue(s.atFloor)
        assertEquals(0.30f, s.quality, 1e-6f)
    }

    @Test
    fun `write mitoyen - streak remis à zéro sans descente`() {
        val s = VideoAimd.onWrite(VideoAimd.State(idx = 0, fastStreak = 15), 320)
        assertEquals(0, s.idx)
        assertEquals(0, s.fastStreak)
    }

    @Test
    fun `hystérésis - une lente casse la remontée en cours`() {
        var s = VideoAimd.State(idx = 1)
        repeat(8) { s = VideoAimd.onWrite(s, 90) }
        assertEquals(8, s.fastStreak)
        s = VideoAimd.onWrite(s, 700)
        assertEquals(0, s.idx)
    }

    @Test
    fun `escalier complet descente puis remontée`() {
        var s = VideoAimd.State()
        repeat(VideoAimd.LEVELS.size - 1) { s = VideoAimd.onWrite(s, 900) }
        assertTrue(s.atFloor)
        repeat(VideoAimd.LEVELS.size - 1) {
            repeat(VideoAimd.FAST_STREAK_TO_CLIMB) { s = VideoAimd.onWrite(s, 60) }
        }
        assertTrue(s.atTop)
    }
}
