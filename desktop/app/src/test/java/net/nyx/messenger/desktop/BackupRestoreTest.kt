package net.nyx.messenger.desktop

import net.nyx.messenger.crypto.IdentityBackup
import net.nyx.messenger.crypto.PasswordCrypto
import net.nyx.messenger.crypto.RecoveryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.3.2 — Chaîne SAUVEGARDE DE COMPTE desktop de bout en bout :
 *
 *   PC/Android source → exportBackupJson (v2) → enveloppe chiffrée
 *   nyx-enc-v1 (le FORMAT EXACT des fichiers générés par l'appli
 *   téléphone) → déchiffrement → applyRestore sur une « autre machine »
 *   (répertoire temporaire vierge).
 *
 * Sémantiques miroir Android vérifiées (retour terrain v0.2.21) :
 *  - même onion restauré, alias/profil/contacts/curseurs repris ;
 *  - un contact « incoming » local est MIS À NIVEAU vers accepted quand la
 *    sauvegarde le dit (répare l'invitation recréée par la re-livraison
 *    du réplica) ;
 *  - un contact archivé local (declined/removed) n'est JAMAIS ressuscité ;
 *  - un curseur local existant n'est JAMAIS reculé ;
 *  - mauvais mot de passe → erreur explicite, rien n'est écrit.
 */
class BackupRestoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val peerA = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnop.onion"
    private val peerB = "zyxwvutsrqponmlkjihgfedcba234567zyxwvutsrqponmlkjihgfedcba234567zy.onion"
    private val replica = "qwertyuiopasdfghjklzxcvbnm234567qwertyuiopasdfghjklzxcvbnm234.onion"

    private fun machine(): Pair<DesktopIdentity, DesktopStore> {
        val dir = tmp.newFolder()
        System.setProperty("nyx.data.dir", dir.absolutePath)
        // La création des répertoires du magasin attend un dataDir existant.
        net.nyx.messenger.desktop.DesktopPaths.ensure()
        return DesktopIdentity(File(dir, "identity.json")) to
            DesktopStore().apply { ensureLoaded() }
    }

    @Test
    fun `aller-retour complet - enveloppe chiffrée téléphone vers PC vierge`() {
        val (idA, storeA) = machine()
        val onion = idA.createIfNeeded(null, "alice")
        storeA.saveMyProfile(
            DesktopStore.MyProfile(displayName = "Alice", bio = "nuit", location = "Paris")
        )
        storeA.upsertContact(
            DesktopStore.Contact(peerA, "bob", 111L, DesktopStore.ContactStatus.ACCEPTED)
        )
        storeA.setCursor("$replica:80", 4242L)

        // Export → enveloppe chiffrée (trajet du fichier téléphone).
        val json = BackupRestore.exportBackupJson(idA, storeA)
        assertTrue(json.contains("nyx-identity-backup"))
        assertTrue(json.contains("recovery_key"))
        assertTrue(json.contains("contacts"))
        val envelope = PasswordCrypto.encrypt(json.toByteArray(Charsets.UTF_8), "s3cret!".toCharArray())

        // Autre machine (vierge) : déchiffrement + application.
        val (idB, storeB) = machine()
        val plain = PasswordCrypto.decrypt(envelope, "s3cret!".toCharArray())
        assertNotNull(plain)
        val found = IdentityBackup.extract(plain!!.toString(Charsets.UTF_8))
        assertNotNull(found)
        val restoredOnion = BackupRestore.applyRestore(idB, storeB, found!!, null)

        assertEquals(onion, restoredOnion)
        assertEquals("alice", idB.alias)
        assertEquals("Alice", storeB.myProfile().displayName)
        assertEquals("nuit", storeB.myProfile().bio)
        assertEquals("Paris", storeB.myProfile().location)
        assertEquals(1, storeB.contacts().size)
        assertEquals(DesktopStore.ContactStatus.ACCEPTED, storeB.contact(peerA)?.status)
        assertEquals("bob", storeB.contact(peerA)?.alias)
        assertEquals(4242L, storeB.cursor("$replica:80"))
        // La clé de l'export correspond à la graine locale : re-export possible.
        assertEquals(RecoveryKey.encode(idA.secretKey().copyOfRange(0, 32)), found.recoveryKey)
    }

    @Test
    fun `contact incoming local mis à niveau vers accepted par la sauvegarde`() {
        val (idA, storeA) = machine()
        val onion = idA.createIfNeeded(null, null)
        storeA.upsertContact(DesktopStore.Contact(peerA, "bob", 1L, DesktopStore.ContactStatus.ACCEPTED))
        val json = BackupRestore.exportBackupJson(idA, storeA)

        // Cible : le même contact existe déjà en « incoming » (invitation
        // recréée par la re-livraison du réplica — le cas du terrain).
        val (idB, storeB) = machine()
        storeB.upsertContact(DesktopStore.Contact(peerA, "bob…", 2L, DesktopStore.ContactStatus.INCOMING))
        BackupRestore.applyRestore(idB, storeB, IdentityBackup.extract(json)!!)
        assertEquals(DesktopStore.ContactStatus.ACCEPTED, storeB.contact(peerA)?.status)
        assertEquals(onion, idB.onionAddress)
    }

    @Test
    fun `contact archivé local jamais ressuscité - curseur local jamais reculé`() {
        val (idA, storeA) = machine()
        idA.createIfNeeded(null, null)
        storeA.upsertContact(DesktopStore.Contact(peerB, "zoe", 1L, DesktopStore.ContactStatus.ACCEPTED))
        storeA.setCursor("$replica:80", 10L)
        val json = BackupRestore.exportBackupJson(idA, storeA)

        val (idB, storeB) = machine()
        // Décisions LOCALES post-export : refus + curseur plus avancé.
        storeB.upsertContact(DesktopStore.Contact(peerB, "zoe", 2L, DesktopStore.ContactStatus.DECLINED))
        storeB.setCursor("$replica:80", 999L)
        BackupRestore.applyRestore(idB, storeB, IdentityBackup.extract(json)!!)
        assertEquals(DesktopStore.ContactStatus.DECLINED, storeB.contact(peerB)?.status)
        assertEquals(999L, storeB.cursor("$replica:80"))
    }

    @Test
    fun `restauration par enveloppe avec mauvais mot de passe - rien n'est écrit`() {
        val (idA, storeA) = machine()
        idA.createIfNeeded(null, null)
        val envelope = PasswordCrypto.encrypt(
            BackupRestore.exportBackupJson(idA, storeA).toByteArray(Charsets.UTF_8),
            "bon".toCharArray()
        )

        val (idB, storeB) = machine()
        val plain = PasswordCrypto.decrypt(envelope, "mauvais".toCharArray())
        assertNull(plain) // jamais de crash — miroir Android v0.2.27
        assertTrue(!idB.exists)
        assertTrue(storeB.contacts().isEmpty())
    }

    @Test
    fun `clé nue (papier) restaure l'identité seule`() {
        val (idA, _) = machine()
        val onion = idA.createIfNeeded(null, "alice")
        val key = RecoveryKey.encode(idA.secretKey().copyOfRange(0, 32))

        val (idB, storeB) = machine()
        val found = IdentityBackup.extract(key)
        assertNotNull(found)
        BackupRestore.applyRestore(idB, storeB, found!!)
        assertEquals(onion, idB.onionAddress)
        assertNull(idB.alias) // pas d'état additionnel dans une clé nue
        assertTrue(storeB.contacts().isEmpty())
    }

    @Test
    fun `statuts android mappés - removed_by_me et inconnu vers desktop`() {
        // Sauvegarde générée par un TÉLÉPHONE : statuts Android (chaînes) que
        // le desktop doit mapper (removed_by_me → REMOVED, inconnu → PENDING).
        val (idA, storeA) = machine()
        val onion = idA.createIfNeeded(null, null)
        val key = RecoveryKey.encode(idA.secretKey().copyOfRange(0, 32))
        val contacts = """[
            {"onion":"$peerA","alias":"a","status":"removed_by_me","addedAt":1,"notifiedAt":0,"notifyPending":0,"ackPending":0},
            {"onion":"$peerB","alias":"b","status":"statut-inconnu","addedAt":1,"notifiedAt":0,"notifyPending":0,"ackPending":0}
        ]"""
        val json = IdentityBackup.toJson(onion, key, null, null, contacts, "{}")

        val (idB, storeB) = machine()
        BackupRestore.applyRestore(idB, storeB, IdentityBackup.extract(json)!!)
        assertEquals(DesktopStore.ContactStatus.REMOVED, storeB.contact(peerA)?.status)
        assertEquals(DesktopStore.ContactStatus.PENDING, storeB.contact(peerB)?.status)
    }
}
