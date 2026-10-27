package net.nyx.messenger.replica

import kotlinx.serialization.json.Json
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.net.WireEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Réplique embarquée desktop — intégration loopback RÉELLE (miroir
 * ReplicaHttpTest/EmbeddedReplica Android) : dépôt signé → inbox authentifiée
 * → curseur, sur le routeur in-process [EmbeddedReplica.dispatch].
 */
class EmbeddedReplicaDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cipher = MessageCipher.INSTANCE

    private fun freshDir(): File {
        val dir = tmp.newFolder()
        System.setProperty("nyx.data.dir", dir.absolutePath)
        return dir
    }

    private fun resetWith(onion: String, allowed: Set<String>) {
        // NB : le répertoire de données (nyx.data.dir) est déjà posé par freshDir()
        // AVANT la création des identités — ici on ne RESET que l'état du réplica.
        DesktopPaths.ensure()
        EmbeddedReplica.resetForTest()
        EmbeddedReplica.start(onion, "test")
        EmbeddedReplica.verify = { author, canonical, sig ->
            val pub = runCatching { net.nyx.messenger.crypto.OnionCodec.onionToPubKey(author) }.getOrNull()
            pub != null && cipher.verifyEvent(canonical, sig, pub)
        }
        EmbeddedReplica.isAllowed = { it in allowed || it == onion }
    }

    private fun signedEvent(authorKeys: Pair<ByteArray, ByteArray>, author: String, seq: Long, to: String): WireEvent {
        val body = Base64.getEncoder().encodeToString("payload-$seq".toByteArray())
        val ev = WireEvent(
            type = "msg", author = author, seq = seq,
            ts = System.currentTimeMillis() / 1000, to = to, body = body, sig = ""
        )
        val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, to, ev.body)
        return ev.copy(sig = cipher.signEvent(canonical, authorKeys.second))
    }

    @Test
    fun `dépôt signé puis lecture inbox authentifiée`() {
        // Deux identités : alice (dépôt) et bob (propriétaire du réplica).
        freshDir()
        val aliceOnion = newIdentity("alice")
        val bobOnion = newIdentity("bob")
        val aliceKeys = keysOf("alice")
        val bobKeys = keysOf("bob")

        resetWith(bobOnion, allowed = setOf(aliceOnion))

        // 1. Alice dépose un message pour bob.
        val ev = signedEvent(aliceKeys, aliceOnion, 1, bobOnion)
        val (code, resp) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(),
            json.encodeToString(WireEvent.serializer(), ev).toByteArray()
        )
        assertEquals(200, code)
        assertTrue(resp.contains("\"accepted\":true"))

        // 2. Bob lit SON inbox (auth ts:sig).
        val ts = System.currentTimeMillis() / 1000
        val authCanonical = ReplicaValidate.inboxCanonical(bobOnion, ts)
        val auth = "$ts:" + cipher.signEvent(authCanonical, bobKeys.second)
        val (code2, resp2) = EmbeddedReplica.dispatch(
            "GET", "/client/inbox",
            mapOf("to" to bobOnion, "after" to "0", "auth" to auth),
            ByteArray(0)
        )
        assertEquals(200, code2)
        val inbox = json.decodeFromString(net.nyx.messenger.net.InboxResponse.serializer(), resp2)
        assertEquals(1, inbox.events.size)
        assertEquals(aliceOnion, inbox.events[0].author)
        assertTrue(inbox.cursor >= 1)

        // 3. Auth INVALIDE → 401.
        val (code3, _) = EmbeddedReplica.dispatch(
            "GET", "/client/inbox",
            mapOf("to" to bobOnion, "after" to "0", "auth" to "123:AAAA"),
            ByteArray(0)
        )
        assertEquals(401, code3)
    }

    @Test
    fun `dépôt hors séquence - en attente puis admission`() {
        freshDir()
        val aliceOnion = newIdentity("alice")
        val bobOnion = newIdentity("bob")
        val aliceKeys = keysOf("alice")
        resetWith(bobOnion, allowed = setOf(aliceOnion))

        // seq 2 d'abord → 400 « en attente (attendu 1) ».
        val ev2 = signedEvent(aliceKeys, aliceOnion, 2, bobOnion)
        val (code, resp) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(),
            json.encodeToString(WireEvent.serializer(), ev2).toByteArray()
        )
        assertEquals(400, code)
        assertTrue(resp.contains("attendu 1"))

        // seq 1 → accepté ET l'attente 2 est admise en cascade.
        val ev1 = signedEvent(aliceKeys, aliceOnion, 1, bobOnion)
        val (code2, _) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(),
            json.encodeToString(WireEvent.serializer(), ev1).toByteArray()
        )
        assertEquals(200, code2)

        val bobKeys = keysOf("bob")
        val ts = System.currentTimeMillis() / 1000
        val auth = "$ts:" + cipher.signEvent(ReplicaValidate.inboxCanonical(bobOnion, ts), bobKeys.second)
        val (_, resp2) = EmbeddedReplica.dispatch(
            "GET", "/client/inbox",
            mapOf("to" to bobOnion, "after" to "0", "auth" to auth),
            ByteArray(0)
        )
        val inbox = json.decodeFromString(net.nyx.messenger.net.InboxResponse.serializer(), resp2)
        assertEquals(2, inbox.events.size)
    }

    @Test
    fun `dépôt d'un inconnu - refusé`() {
        freshDir()
        val malloryOnion = newIdentity("mallory")
        val bobOnion = newIdentity("bob")
        val malloryKeys = keysOf("mallory")
        resetWith(bobOnion, allowed = emptySet())

        val ev = signedEvent(malloryKeys, malloryOnion, 1, bobOnion)
        val (code, resp) = EmbeddedReplica.dispatch(
            "POST", "/client/event", emptyMap(),
            json.encodeToString(WireEvent.serializer(), ev).toByteArray()
        )
        assertEquals(403, code)
        assertTrue(resp.contains("auteur inconnu"))
    }

    // ---------- helpers identités de test ----------

    private val keyDir get() = File(System.getProperty("nyx.data.dir"))

    private fun newIdentity(name: String): String {
        val f = File(keyDir, "id_$name.json")
        val id = net.nyx.messenger.desktop.DesktopIdentity(f)
        return id.createIfNeeded(null, name)
    }

    private fun keysOf(name: String): Pair<ByteArray, ByteArray> {
        val id = net.nyx.messenger.desktop.DesktopIdentity(File(keyDir, "id_$name.json"))
        val sk = id.secretKey()
        return sk.copyOfRange(32, 64) to sk
    }
}
