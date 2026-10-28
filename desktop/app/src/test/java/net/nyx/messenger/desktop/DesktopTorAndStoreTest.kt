package net.nyx.messenger.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** torrc généré (DesktopTor) — miroir des invariants NyxTorConfig Android. */
class DesktopTorrcTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `torrc - options critiques présentes`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val tor = DesktopTor(null)
        val rc = tor.writeTorrc(4242)

        val text = rc.readText()
        val p = tor.ports!!
        assertTrue(text.contains("SocksPort 127.0.0.1:${p.socks} IsolateDestAddr"))
        assertTrue(text.contains("ControlPort 127.0.0.1:${p.control}"))
        assertTrue(text.contains("CookieAuthentication 1"))
        assertTrue(text.contains("ConfluxEnabled 0"))            // miroir v0.2.44
        assertTrue(text.contains("SafeLogging 1"))
        assertTrue(text.contains("__OwningControllerProcess 4242"))
        assertTrue(text.contains("Log info file"))
        // Le DataDirectory vit sous le répertoire de données applicatif.
        assertTrue(text.contains(File(System.getProperty("nyx.data.dir"), "tor").absolutePath))
    }
}

/** Magasin JSON desktop — écriture atomique + round-trip. */
class DesktopStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `messages - insert update roundtrip`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val s = DesktopStore()
        s.insertMessage(DesktopStore.Msg("id1", "peer.onion", true, "salut", 1L))
        s.updateMessage("id1") { it.state = DesktopStore.STATE_SENT }
        // Relecture d'une NOUVELLE instance (relance process).
        val s2 = DesktopStore()
        assertEquals(1, s2.messages().size)
        assertEquals(DesktopStore.STATE_SENT, s2.messages()[0].state)
        // Insert idempotent (même eventId).
        s2.insertMessage(DesktopStore.Msg("id1", "peer.onion", true, "salut", 1L))
        assertEquals(1, s2.messages().size)
    }

    @Test
    fun `v0310 - insertMessage rend true SEULEMENT au premier insert`() {
        // Terrain 28/10 : « sur pc, notifications incessantes de messages déjà
        // traités » — le même événement livré par plusieurs sources de poll
        // (réplica propre + flotte) repassait par la bulle plateau alors que
        // le magasin dédupliquait déjà : le chemin notification doit pouvoir
        // distinguer NOUVEAU de DÉJÀ PRÉSENT.
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val s = DesktopStore(); s.ensureLoaded()
        val m = DesktopStore.Msg("dedup1", "peer.onion", false, "re-notifié ?", 1L)
        assertTrue(s.insertMessage(m))    // 1ʳᵉ fois : NOUVEAU → notifier
        assertTrue(!s.insertMessage(m))   // 2ᵉ fois : DÉJÀ LÀ → NE PAS notifier
        assertTrue(!s.insertMessage(m))   // 3ᵉ fois (flotte) : idem
        assertEquals(1, s.messagesWith("peer.onion").size)  // jamais doublé dans le magasin
    }

    @Test
    fun `seq_state - persistance par auteur`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        DesktopStore().apply { upsertSeq("a.onion", 5) }
        assertEquals(5L, DesktopStore().seqOf("a.onion"))
        assertEquals(0L, DesktopStore().seqOf("b.onion"))
    }

    @Test
    fun `attachment - fragments et assemblage sha256`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val s = DesktopStore()
        val data = ByteArray(200) { it.toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        s.upsertAttachment(DesktopStore.AttachmentMeta("fid1", "f.bin", "application/octet-stream", 200, sha))
        s.putChunk("fid1", 0, data.copyOfRange(0, 100))
        assertEquals(1, s.chunkCount("fid1"))
        // Assemblage impossible tant qu'il manque un fragment.
        assertEquals(null, s.assembleAttachment("fid1", 2, sha))
        s.putChunk("fid1", 1, data.copyOfRange(100, 200))
        val f = s.assembleAttachment("fid1", 2, sha)
        assertNotNull(f)
        assertEquals(200, f!!.length())
        // sha invalide → null (fichier altéré).
        s.putChunk("fid2", 0, data.copyOfRange(0, 100))
        s.putChunk("fid2", 1, data.copyOfRange(100, 200))
        assertEquals(null, s.assembleAttachment("fid2", 2, "deadbeef"))
    }
}
