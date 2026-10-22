package net.nyx.messenger.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM de la sauvegarde d'identité (v0.2.21 — format v2).
 *
 * Retour terrain (test 2) : « l'application me redemande de créer un alias et
 * je reçois l'invitation du contact que j'ai déjà accepté » — le format v2
 * embarque désormais alias/profil/contacts/curseurs et la lecture reste
 * compatible v1 (clé seule) et clé nue (papier).
 *
 * NB : org.json RÉEL en classpath de test (org.json:json — le stub android.jar
 * est inutilisable en JVM).
 */
class IdentityBackupTest {

    private val key = "nyx1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.0123abcd"
    private val okOnion = "nyx123456789012345678901234567890123456789012345678901234.onion"

    @Test
    fun `v2 round-trip — alias, profil, contacts et curseurs sont conservés`() {
        val profile = """{"fields":{"display_name":{"value":"Alice","public":true},"note":{"value":"privé","public":false}}}"""
        val contacts = """[{"onion":"$okOnion","alias":"Bob","status":"accepted","addedAt":111,
            "notifiedAt":222,"notifyPending":0,"ackPending":0}]"""
        val cursors = """{"$okOnion:80":4242}"""

        val json = IdentityBackup.toJson(
            okOnion, key, "alice", profile, contacts, cursors
        )
        val restored = IdentityBackup.extract(json)

        assertTrue(restored != null)
        assertEquals(key, restored!!.recoveryKey)
        assertEquals(okOnion, restored.onion)
        assertEquals("alice", restored.alias)
        assertTrue(restored.profileJson!!.contains("display_name"))
        assertTrue(restored.profileJson!!.contains("privé"))
        assertEquals(1, restored.contacts.size)
        val c = restored.contacts[0]
        assertEquals(okOnion, c.onion)
        assertEquals("Bob", c.alias)
        assertEquals("accepted", c.status)
        assertEquals(111L, c.addedAt)
        assertEquals(222L, c.notifiedAt)
        assertEquals(0, c.notifyPending)
        assertEquals(0, c.ackPending)
        assertEquals(mapOf("$okOnion:80" to 4242L), restored.cursors)
    }

    @Test
    fun `format v1 (clé seule) reste restaurable — identité sans état`() {
        val v1 = """
            {"format":"nyx-identity-backup","version":1,"onion":"$okOnion",
             "recovery_key":"$key","created_at":1760000000}
        """.trimIndent()
        val restored = IdentityBackup.extract(v1)
        assertTrue(restored != null)
        assertEquals(key, restored!!.recoveryKey)
        assertEquals(okOnion, restored.onion)
        assertNull(restored.alias)
        assertNull(restored.profileJson)
        assertTrue(restored.contacts.isEmpty())
        assertTrue(restored.cursors.isEmpty())
    }

    @Test
    fun `clé nue (papier) restaurable`() {
        val restored = IdentityBackup.extract("  $key\n")
        assertTrue(restored != null)
        assertEquals(key, restored!!.recoveryKey)
        assertNull(restored.onion)
        assertTrue(restored.contacts.isEmpty())
    }

    @Test
    fun `contacts au format invalide ignorés, curseurs nuls ignorés`() {
        val contacts = """[
            {"onion":"pas-une-adresse","alias":"X","status":"accepted","addedAt":1,"notifiedAt":1,"notifyPending":0,"ackPending":0},
            {"onion":"$okOnion","alias":"Bob","status":"accepted","addedAt":1,"notifiedAt":1,"notifyPending":0,"ackPending":0}
        ]"""
        val json = IdentityBackup.toJson(okOnion, key, null, null, contacts, """{"replica.onion:80":0}""")
        val restored = IdentityBackup.extract(json)
        assertTrue(restored != null)
        assertEquals(1, restored!!.contacts.size)
        assertTrue(restored.cursors.isEmpty()) // curseur 0 → ignoré
    }

    @Test
    fun `contenu méconnaissable rejeté`() {
        assertNull(IdentityBackup.extract(""))
        assertNull(IdentityBackup.extract("bonjour"))
        assertNull(IdentityBackup.extract("""{"format":"nyx-identity-backup","onion":"$okOnion"}"""))
        assertNull(IdentityBackup.extract("{json cassé"))
    }

    @Test
    fun `la sauvegarde v1 deux-arguments ne porte aucun état additionnel`() {
        val json = IdentityBackup.toJson(okOnion, key)
        val restored = IdentityBackup.extract(json)
        assertTrue(restored != null)
        assertNull(restored!!.alias)
        assertTrue(restored.contacts.isEmpty())
        assertTrue(restored.cursors.isEmpty())
    }

    @Test
    fun `clé avec checksum erroné passe extract mais est rejetée au décodage`() {
        val bad = "nyx1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.deadbeef"
        val restored = IdentityBackup.extract(bad)
        assertTrue(restored != null) // extract ne vérifie que la forme
        try {
            RecoveryKey.decode(restored!!.recoveryKey)
            throw AssertionError("le checksum erroné aurait dû être rejeté")
        } catch (_: IllegalArgumentException) {
            // attendu
        }
    }
}
