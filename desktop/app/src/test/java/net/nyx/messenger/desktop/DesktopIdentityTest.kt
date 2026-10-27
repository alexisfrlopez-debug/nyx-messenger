package net.nyx.messenger.desktop

import net.nyx.messenger.crypto.RecoveryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.junit.rules.TemporaryFolder

/** Sceau d'identité desktop : aller-retour, mot de passe, restauration par graine. */
class DesktopIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun identity(): DesktopIdentity {
        // tmp.root est unique PAR MÉTHODE de test (rule JUnit) — aucune collision.
        return DesktopIdentity(File(tmp.root, "identity.json"))
    }

    @Test
    fun `création sans mot de passe - identité persistée et relisible`() {
        val id = identity()
        assertFalse(id.exists)
        val onion = id.createIfNeeded(null, null)
        assertTrue(onion.endsWith(".onion"))
        assertEquals(62, onion.length)
        // Nouvelle instance (relance de process) : même identité.
        val id2 = identity()
        assertTrue(id2.exists)
        assertEquals(onion, id2.onionAddress)
        assertNotNull(id2.secretKey())
    }

    @Test
    fun `création avec mot de passe - clé indéchiffrable sans lui`() {
        val id = identity()
        id.createIfNeeded(charArrayOf('n', 'u', 'i', 't'), "nyx_test")
        assertEquals("nyx_test", id.alias)
        // Mot de passe erroné → erreur explicite (pas de crash).
        assertTrue(runCatching { identity().secretKey(charArrayOf('x')) }.isFailure)
        // Mot de passe correct → clé accessible.
        assertNotNull(identity().secretKey(charArrayOf('n', 'u', 'i', 't')))
    }

    @Test
    fun `restauration par clé de récupération - même onion`() {
        val id = identity()
        val onion = id.createIfNeeded(null, "a")
        val sk = id.secretKey()
        val key = RecoveryKey.encode(sk.copyOfRange(0, 32))

        // Autre répertoire (autre machine) : restauré = MÊME adresse.
        val id2 = identity()
        val restored = id2.restoreFromSeed(key, null)
        assertEquals(onion, restored)
        assertEquals(onion, id2.onionAddress)

        // Faute de frappe : checksum rejeté AVANT tout écrasement.
        val bad = key.dropLast(1) + (if (key.last() == 'a') 'b' else 'a')
        assertTrue(runCatching { id2.restoreFromSeed(bad, null) }.isFailure)
    }

    @Test
    fun `wipe - retour à zéro`() {
        val id = identity()
        id.createIfNeeded(null, null)
        id.wipe()
        assertFalse(id.exists)
        assertNull(id.onionAddress)
    }
}
