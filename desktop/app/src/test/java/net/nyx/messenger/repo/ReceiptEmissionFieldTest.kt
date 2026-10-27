package net.nyx.messenger.repo

import kotlinx.coroutines.runBlocking
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.desktop.DesktopIdentity
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.DesktopStore.ContactStatus.ACCEPTED
import net.nyx.messenger.net.WireEvent
import net.nyx.messenger.replica.EmbeddedReplica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * session 76 — REPRODUCTION TERRAIN (« accusés de réception ne fonctionnent
 * pas » entre l'app téléphone et l'app Windows, export v0.3.5 20:15) :
 * le journal terrain montre des messages du téléphone stockés
 * (rcptPending=1) SANS AUCUNE trace d'émission d'accusé — ni « ✓ accusé …
 * déposé » ni « ✗ accusé … échoué ». Ce test rejoue LE scénario exact :
 * téléphone (identité B) dépose un message frais dans le réplica embarqué du
 * PC (identité A) → pollInbox() → l'accusé « delivered » DOIT partir.
 *
 * Le port SOCKS pointe vers un serveur mort local : l'émission tente un vrai
 * POST (OkHttp/SOCKS → refus immédiat) — on doit retrouver la trace en
 * journal, QUELLE QUE SOIT l'issue (dépôt ✓ ou échec ✗).
 */
class ReceiptEmissionFieldTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = MessageCipher.INSTANCE

    /** Port SOCKS volontairement MORT (rien n'écoute) — refus TCP immédiat. */
    private val deadSocksPort: Int = ServerSocket(0).use { it.localPort }

    private fun freshDir(): File {
        val dir = tmp.newFolder()
        System.setProperty("nyx.data.dir", dir.absolutePath)
        DesktopPaths.ensure()
        return dir
    }

    private fun newIdentity(name: String): Pair<DesktopIdentity, String> {
        val id = DesktopIdentity(File(tmp.newFolder(), "$name.json"))
        val onion = id.createIfNeeded(alias = name)
        return id to onion
    }

    /** Le téléphone B signe + chiffre pour le PC A, comme le vrai client Android. */
    private fun phoneMessageEvent(
        phoneKeys: DesktopIdentity, phoneOnion: String, pcOnion: String, pcPub: ByteArray, seq: Long, text: String,
    ): WireEvent {
        val body = cipher.encryptFor(pcPub, text.toByteArray(Charsets.UTF_8))
        val ev = WireEvent(
            type = "msg", author = phoneOnion, seq = seq,
            ts = System.currentTimeMillis() / 1000, to = pcOnion, body = body, sig = ""
        )
        val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, pcOnion, ev.body)
        return ev.copy(sig = cipher.signEvent(canonical, phoneKeys.secretKey()))
    }

    private fun startReplicaFor(pcOnion: String, allowed: Set<String>) {
        EmbeddedReplica.resetForTest()
        EmbeddedReplica.start(pcOnion, "test")
        EmbeddedReplica.verify = { author, canonical, sig ->
            val pub = runCatching { OnionCodec.onionToPubKey(author) }.getOrNull()
            pub != null && cipher.verifyEvent(canonical, sig, pub)
        }
        EmbeddedReplica.isAllowed = { it == pcOnion || it in allowed }
    }

    /**
     * SCÉNARIO TERRAIN EXACT — message frais du téléphone dans le réplica du
     * PC, réplicas = [téléphone, flotte], poll complet.
     *
     * Attendu (miroir Android) : un accusé « delivered » part vers le
     * téléphone — trace journal obligatoire (« ✓ accusé … déposé » ou
     * « ✗ accusé … échoué »).
     */
    @Test
    fun `message frais du telephone - l accuse delivered doit partir`() = runBlocking {
        freshDir()
        val (pcId, pcOnion) = newIdentity("pc")
        val (phoneId, phoneOnion) = newIdentity("tel")
        val store = DesktopStore()
        // Contact mutuel ACCEPTÉ (le verrou d'échange l'exige).
        store.upsertContact(
            DesktopStore.Contact(
                onion = phoneOnion, alias = "tel", addedAt = System.currentTimeMillis(),
                status = ACCEPTED
            )
        )
        val repo = DesktopRepository(pcId, store, socksPortProvider = { deadSocksPort })
        // Réplicas miroir terrain : boîte du pair (téléphone) + flotte.
        repo.setReplicas(listOf("$phoneOnion:80", "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80"))

        startReplicaFor(pcOnion, allowed = setOf(phoneOnion))

        // Le téléphone dépose un message FRESH dans le réplica embarqué du PC
        // (POST /client/event en procédé — miroir du POST Tor du terrain).
        val ev = phoneMessageEvent(phoneId, phoneOnion, pcOnion, pcId.publicKey!!, 1, "salut du tel")
        val (code, resp) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(), kotlinx.serialization.json.Json
                .encodeToString(WireEvent.serializer(), ev).toByteArray()
        )
        assertEquals("dépôt du téléphone refusé : $resp", 200, code)

        val baseline = DesktopLog.dump()
        val baselineHasAccuse = baseline.contains("accusé « delivered »")

        // LE poll du terrain.
        repo.pollInbox()

        // 1. Le message est bien stocké avec accusé en attente.
        val stored = store.messagesWith(phoneOnion).filter { !it.outgoing }
        assertTrue("message du téléphone non stocké", stored.isNotEmpty())
        assertEquals("rcptPending doit valoir 1 (accusé delivered en attente)", 1, stored[0].rcptPending)

        // 2. LA trace d'émission doit exister — c'est ce qui MANQUE dans
        //    l'export terrain v0.3.5 (aucune ligne accusé côté nyx-send).
        val dump = DesktopLog.dump()
        val newLines = if (baselineHasAccuse) dump.substringAfter(baseline.substringAfterLast("accusé « delivered »")) else dump
        val emission = newLines.lineSequence().filter { it.contains("accusé « delivered »") && it.contains("nyx-send") }.toList()
        assertTrue(
            "AUCUNE trace d'émission d'accusé « delivered » côté nyx-send (bug terrain v0.3.5) — journal:\n" +
                newLines.lineSequence().filter { it.contains("nyx-send") }.joinToString("\n"),
            emission.isNotEmpty()
        )
    }

    /**
     * SCÉNARIO LECTURE — conversation ouverte juste après réception (le cas
     * ChatPanel/markConversationRead du terrain) : l'accusé « read » DOIT
     * partir (miroir Android markConversationRead → ensureReceiptsSent).
     */
    @Test
    fun `conversation ouverte - l accuse read doit partir`() = runBlocking {
        freshDir()
        val (pcId, pcOnion) = newIdentity("pc2")
        val (phoneId, phoneOnion) = newIdentity("tel2")
        val store = DesktopStore()
        store.upsertContact(
            DesktopStore.Contact(
                onion = phoneOnion, alias = "tel2", addedAt = System.currentTimeMillis(),
                status = ACCEPTED
            )
        )
        val repo = DesktopRepository(pcId, store, socksPortProvider = { deadSocksPort })
        repo.setReplicas(listOf("$phoneOnion:80"))

        startReplicaFor(pcOnion, allowed = setOf(phoneOnion))
        val ev = phoneMessageEvent(phoneId, phoneOnion, pcOnion, pcId.publicKey!!, 1, "salut read")
        val (code, resp) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(), kotlinx.serialization.json.Json
                .encodeToString(WireEvent.serializer(), ev).toByteArray()
        )
        assertEquals("dépôt refusé : $resp", 200, code)
        repo.pollInbox()
        val stored = store.messagesWith(phoneOnion).filter { !it.outgoing }
        assertTrue(stored.isNotEmpty())

        // La conversation s'ouvre (miroir ChatPanel onEnter).
        repo.markConversationRead(phoneOnion)

        val dump = DesktopLog.dump()
        val readLines = dump.lineSequence().filter { it.contains("accusé « read »") && it.contains("nyx-send") }.toList()
        assertTrue(
            "AUCUNE trace d'émission d'accusé « read » côté nyx-send (bug terrain v0.3.5 — filtre rcptDelivered>0 sur messages ENTRANTS jamais atteint) — journal nyx-send:\n" +
                dump.lineSequence().filter { it.contains("nyx-send") }.toList().takeLast(12).joinToString("\n"),
            readLines.isNotEmpty()
        )
        assertNotNull(store)
    }
}
