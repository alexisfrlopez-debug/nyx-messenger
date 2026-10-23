package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.18 — Signalisation d'appel : parse STRICT (un texte ordinaire ne doit
 * JAMAIS être pris pour une signalisation — il irait dans le chat), encode /
 * parse roundtrip, rejets des op inconnus et des cid malformés.
 */
class CallSignalTest {

    @Test
    fun `roundtrip encode - parse`() {
        val s = CallSignal("offer", "0123456789abcdef0123456789abcdef", "alice", 1_700_000_000)
        val back = CallSignal.parse(s.encode())
        assertNotNull(back)
        assertEquals(s, back)
    }

    @Test
    fun `texte ordinaire - jamais une signalisation`() {
        assertNull(CallSignal.parse("salut, on s'appelle ce soir ?"))
        assertNull(CallSignal.parse("{\"v\":1,\"kind\":\"file\",\"fid\":\"a\"}"))
        assertNull(CallSignal.parse(""))
        assertNull(CallSignal.parse("kind:call op:offer cid:xxx"))
    }

    @Test
    fun `op inconnu - rejeté`() {
        assertNull(
            CallSignal.parse("""{"v":1,"kind":"call","op":"zzz","cid":"0123456789abcdef0123456789abcdef","alias":"","ts":1}""")
        )
    }

    @Test
    fun `cid malformé - rejeté`() {
        assertNull(
            CallSignal.parse("""{"v":1,"kind":"call","op":"offer","cid":"court","alias":"","ts":1}""")
        )
    }

    @Test
    fun `formats op - acceptés`() {
        for (op in listOf("offer", "reject", "bye")) {
            val s = CallSignal.parse(
                """{"v":1,"kind":"call","op":"$op","cid":"0123456789abcdef0123456789abcdef","alias":"bob","ts":42}"""
            )
            assertNotNull(op, s)
            assertEquals(op, s!!.op)
            assertEquals("bob", s.alias)
            assertEquals(42L, s.ts)
        }
    }

    @Test
    fun `échappement alias - roundtrip`() {
        val s = CallSignal("offer", "0123456789abcdef0123456789abcdef", "al\"ice\\", 5)
        assertEquals(s, CallSignal.parse(s.encode()))
    }

    @Test
    fun `ttl offre - constante documentée`() {
        assertTrue(CallSignal.OFFER_TTL_S in 30..600)
        assertFalse(CallSignal.newCid().isBlank())
        assertEquals(32, CallSignal.newCid().length)
    }

    // v0.2.44 — champ relay (repli média par le réplica).

    @Test
    fun `roundtrip relay - champ emis et relu`() {
        val s = CallSignal(
            "offer", "0123456789abcdef0123456789abcdef", "alice", 1_700_000_000,
            relay = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80"
        )
        val back = CallSignal.parse(s.encode())
        assertNotNull(back)
        assertEquals("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80", back!!.relay)
    }

    @Test
    fun `sans relay - json inchangé champ absent`() {
        // Compatibilité arrière : v0.2.43 et avant ne doivent rien voir changer.
        val s = CallSignal("offer", "0123456789abcdef0123456789abcdef", "alice", 7)
        val json = s.encode()
        assertFalse(json.contains("relay"))
        assertEquals(s, CallSignal.parse(json))
    }

    @Test
    fun `payload v0 2 43 - relay absent accepte et vaut vide`() {
        // Un émetteur v0.2.43 n'envoie jamais le champ : la lecture doit
        // aboutir avec relay="" (le repli est simplement désactivé).
        val back = CallSignal.parse(
            """{"v":1,"kind":"call","op":"offer","cid":"0123456789abcdef0123456789abcdef","alias":"bob","ts":42}"""
        )
        assertNotNull(back)
        assertEquals("", back!!.relay)
    }

    @Test
    fun `relay non onion - accepte en passthrough`() {
        // La validation d'hôte se fait à l'USAGE (P2pClient.relayTarget),
        // pas au décodage : la signalisation ne doit jamais casser sur un
        // champ mal rempli par un futur émetteur.
        val back = CallSignal.parse(
            """{"v":1,"kind":"call","op":"offer","cid":"0123456789abcdef0123456789abcdef","alias":"bob","ts":42,"relay":"n-importe-quoi"}"""
        )
        assertNotNull(back)
        assertEquals("n-importe-quoi", back!!.relay)
    }
}
