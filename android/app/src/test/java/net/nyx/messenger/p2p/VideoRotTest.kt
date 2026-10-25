package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * v0.2.53 — VideoRot : rotation capteur → écran (pur, JVM).
 * Terrain 25/10 : « l'image video a une orientation d'écran figée » —
 * l'image partait couchée (orientation capteur) sans aucune compensation.
 */
class VideoRotTest {

    @Test
    fun `index Surface vers degrés`() {
        assertEquals(0, VideoRot.displayRotationToDeg(VideoRot.SURFACE_ROTATION_0))
        assertEquals(90, VideoRot.displayRotationToDeg(VideoRot.SURFACE_ROTATION_90))
        assertEquals(180, VideoRot.displayRotationToDeg(VideoRot.SURFACE_ROTATION_180))
        assertEquals(270, VideoRot.displayRotationToDeg(VideoRot.SURFACE_ROTATION_270))
    }

    @Test
    fun `index Surface invalide rejeté`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoRot.displayRotationToDeg(4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoRot.displayRotationToDeg(-1)
        }
    }

    @Test
    fun `caméra arrière classique - capteur 90 portrait naturel`() {
        // Téléphone en portrait naturel (écran 0°), capteur arrière 90° :
        // l'image doit être tournée de 90° horaires pour être droite.
        assertEquals(90, VideoRot.uprightDegrees(90, 0, front = false))
    }

    @Test
    fun `caméra arrière - écran tourné 90 compensation`() {
        // Écran tourné de 90° : la rotation à appliquer suit (90 - 90 = 0).
        assertEquals(0, VideoRot.uprightDegrees(90, 90, front = false))
        assertEquals(180, VideoRot.uprightDegrees(90, 270, front = false))
    }

    @Test
    fun `caméra front classique - capteur 270`() {
        // Front : la compensation S'AJOUTE (capteur « inversé »).
        assertEquals(270, VideoRot.uprightDegrees(270, 0, front = true))
        assertEquals(0, VideoRot.uprightDegrees(270, 90, front = true))
        assertEquals(90, VideoRot.uprightDegrees(270, 180, front = true))
        assertEquals(180, VideoRot.uprightDegrees(270, 270, front = true))
    }

    @Test
    fun `front et arrière diffèrent sur le même écran`() {
        // Même capteur 90 : front ajoute, arrière soustrait.
        assertEquals(180, VideoRot.uprightDegrees(90, 90, front = true))
        assertEquals(0, VideoRot.uprightDegrees(90, 90, front = false))
    }

    @Test
    fun `valeurs non multiples de 90 rejetées`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoRot.uprightDegrees(45, 0, front = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoRot.uprightDegrees(90, 45, front = false)
        }
    }

    @Test
    fun `payload F_VIDEO_ROT aller-retour`() {
        for (deg in listOf(0, 90, 180, 270)) {
            assertEquals(deg, VideoRot.decodeFrame(VideoRot.encodeFrame(deg)))
        }
    }

    @Test
    fun `payload F_VIDEO_ROT illisible rend null`() {
        assertNull(VideoRot.decodeFrame("45".toByteArray()))
        assertNull(VideoRot.decodeFrame("abc".toByteArray()))
        assertNull(VideoRot.decodeFrame("".toByteArray()))
        assertNull(VideoRot.decodeFrame(ByteArray(0)))
    }
}
