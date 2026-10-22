// Tests JVM du modèle de profil (wire-format.md §7 — miroir des tests serveur).
package net.nyx.messenger.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private fun f(value: String, public: Boolean) = ProfileField(value, public)

    @Test
    fun `carte publique valide acceptée`() {
        val fields = mapOf(
            ProfileKeys.DISPLAY_NAME to f("Anon", true),
            ProfileKeys.BIO to f("salut", true),
        )
        assertNull(ProfileValidator.validatePublicCard(fields))
    }

    @Test
    fun `champ privé dans une carte diffusée rejeté (invariant §7_2)`() {
        val fields = mapOf(
            ProfileKeys.LOCATION to f("Paris", false),
        )
        assertNotNull(ProfileValidator.validatePublicCard(fields))
    }

    @Test
    fun `clé inconnue rejetée (clés fermées §7_2)`() {
        val fields = mapOf(
            "real_name" to f("X", true),
        )
        assertNotNull(ProfileValidator.validatePublicCard(fields))
    }

    @Test
    fun `dépassement de limite rejeté`() {
        val longBio = "a".repeat(281)
        val fields = mapOf(
            ProfileKeys.BIO to f(longBio, true),
        )
        assertNotNull(ProfileValidator.validatePublicCard(fields))
        // et la limite exacte passe
        val ok = mapOf(ProfileKeys.BIO to f("a".repeat(280), true))
        assertNull(ProfileValidator.validatePublicCard(ok))
    }

    @Test
    fun `publicOnly ne conserve que les champs publics`() {
        val p = OwnProfile(
            mapOf(
                ProfileKeys.DISPLAY_NAME to f("Anon", true),
                ProfileKeys.LOCATION to f("ici", false),
            )
        )
        val pub = p.publicOnly()
        assertTrue(pub.fields.containsKey(ProfileKeys.DISPLAY_NAME))
        assertTrue(!pub.fields.containsKey(ProfileKeys.LOCATION))
        assertNull(ProfileValidator.validatePublicCard(pub.fields))
    }

    @Test
    fun `aller-retour codec profil (base64 JSON §7_2)`() {
        val body = ProfileBody(
            mapOf(ProfileKeys.DISPLAY_NAME to ProfileField("Anon", true))
        )
        val encoded = ProfileCodec.encode(body)
        val decoded = ProfileCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("Anon", decoded!!.fields[ProfileKeys.DISPLAY_NAME]?.value)
        assertEquals(true, decoded.fields[ProfileKeys.DISPLAY_NAME]?.public)
    }

    @Test
    fun `avatar tient dans la limite §7_2`() {
        assertTrue(65536 <= ProfileKeys.LIMITS[ProfileKeys.AVATAR]!!)
    }
}
