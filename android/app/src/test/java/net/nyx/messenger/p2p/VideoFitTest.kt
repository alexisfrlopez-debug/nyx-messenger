package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * v0.2.53 — VideoFit : matrice de présentation TextureView (pur, JVM) —
 * rotation horaire + fit centré + miroir, letterbox implicite.
 *
 * Convention android.graphics.Matrix : x' = m[0]·x + m[1]·y + m[2] ;
 * y' = m[3]·x + m[4]·y + m[5]. Les coins du buffer sont mappés et la
 * boîte englobante vérifiée contre la géométrie attendue.
 */
class VideoFitTest {

    /* ---------------------------- rotation 0 ---------------------------- */

    @Test
    fun `rotation 0 - buffer plus petit centré sans déformation`() {
        // Vue 200×200, buffer 320×240 → s = min(200/320, 200/240) = 0,625
        // contenu 200×150 centré : ty = 25
        val m = VideoFit.values(200, 200, 320, 240, 0)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 25f, 200f, 175f), b, 0.01f)
        // (0,0) → haut-gauche ; (320,0) → haut-droite (pas de retournement) ;
        // les deux bords du buffer restent sur la bande haute du letterbox (y=25).
        assertEquals(0f, VideoFit.map(m, 0f, 0f).first, 0.01f)
        assertEquals(25f, VideoFit.map(m, 0f, 0f).second, 0.01f)
        assertEquals(200f, VideoFit.map(m, 320f, 0f).first, 0.01f)
        assertEquals(25f, VideoFit.map(m, 320f, 0f).second, 0.01f)
    }

    @Test
    fun `rotation 0 - buffer aux mêmes proportions`() {
        val m = VideoFit.values(320, 240, 320, 240, 0)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 0f, 320f, 240f), b, 0.01f)
    }

    /* ---------------------------- rotation 90 --------------------------- */

    @Test
    fun `rotation 90 - boîte échangée et coin origine en haut-droite`() {
        // Vue 240×320 (portrait), buffer 320×240 (paysage capteur), rot 90 :
        // contenu 240×320 → remplit exactement (s = 1)
        val m = VideoFit.values(240, 320, 320, 240, 90)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 0f, 240f, 320f), b, 0.01f)
        // Rotation horaire : le coin (0,0) du buffer finit en HAUT-DROITE
        val c = VideoFit.map(m, 0f, 0f)
        assertEquals(240f, c.first, 0.01f)   // x max
        assertEquals(0f, c.second, 0.01f)    // y min
        // Le coin (0, 240) (bas-gauche du buffer) finit en HAUT-GAUCHE
        val c2 = VideoFit.map(m, 0f, 240f)
        assertEquals(0f, c2.first, 0.01f)
        assertEquals(0f, c2.second, 0.01f)
    }

    @Test
    fun `rotation 90 - letterbox symétrique`() {
        // Vue haute 240×640, contenu 240×320 → bandes noires 160 haut/bas
        val m = VideoFit.values(240, 640, 320, 240, 90)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 160f, 240f, 480f), b, 0.01f)
    }

    /* --------------------------- rotation 180 --------------------------- */

    @Test
    fun `rotation 180 - image retournée coin à coin`() {
        val m = VideoFit.values(320, 240, 320, 240, 180)
        // (0,0) → (320, 240) : bas-droite
        val c = VideoFit.map(m, 0f, 0f)
        assertEquals(320f, c.first, 0.01f)
        assertEquals(240f, c.second, 0.01f)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 0f, 320f, 240f), b, 0.01f)
    }

    /* --------------------------- rotation 270 --------------------------- */

    @Test
    fun `rotation 270 - coin origine en bas-gauche`() {
        // Rotation ANTI-horaire de 90 (= 270 horaires) : (0,0) → bas-gauche
        val m = VideoFit.values(240, 320, 320, 240, 270)
        val c = VideoFit.map(m, 0f, 0f)
        assertEquals(0f, c.first, 0.01f)
        assertEquals(320f, c.second, 0.01f)
        val b = VideoFit.bounds(m, 320, 240)
        assertArrayEquals(floatArrayOf(0f, 0f, 240f, 320f), b, 0.01f)
    }

    /* ------------------------------- miroir ----------------------------- */

    @Test
    fun `miroir - symétrie horizontale du résultat`() {
        val m = VideoFit.values(240, 320, 320, 240, 90, mirror = false)
        val mm = VideoFit.values(240, 320, 320, 240, 90, mirror = true)
        val p = VideoFit.map(m, 0f, 0f)
        val q = VideoFit.map(mm, 0f, 0f)
        // x'' = vue - x' ; y inchangé
        assertEquals(240f - p.first, q.first, 0.01f)
        assertEquals(p.second, q.second, 0.01f)
        // La boîte englobante est inchangée
        assertArrayEquals(VideoFit.bounds(m, 320, 240), VideoFit.bounds(mm, 320, 240), 0.01f)
    }

    @Test
    fun `miroir identité - buffer exactement la vue`() {
        val m = VideoFit.values(100, 100, 100, 100, 0, mirror = true)
        // (0,0) → (100, 0) ; (100, 0) → (0, 0)
        assertEquals(100f, VideoFit.map(m, 0f, 0f).first, 0.01f)
        assertEquals(0f, VideoFit.map(m, 100f, 0f).first, 0.01f)
    }

    /* ------------------------------ bornes ------------------------------ */

    @Test
    fun `dimensions invalides rejetées`() {
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(0, 200, 320, 240, 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(200, 0, 320, 240, 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(200, 200, 0, 240, 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(200, 200, 320, 0, 0) }
    }

    @Test
    fun `rotation non multiple de 90 rejetée`() {
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(200, 200, 320, 240, 45) }
        assertThrows(IllegalArgumentException::class.java) { VideoFit.values(200, 200, 320, 240, 360) }
    }
}
