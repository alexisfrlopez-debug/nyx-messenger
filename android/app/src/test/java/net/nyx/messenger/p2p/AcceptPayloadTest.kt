package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.20 — tests du payload F_CALL_ACCEPT : JSON {csd, vcsd} quand la visio
 * est négociée, b64 AAC brut (v0.2.18/19) sinon — compatibilité des deux formats.
 */
class AcceptPayloadTest {

    @Test
    fun `sans vidéo - format historique brut (compat v0219)`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "")
        assertEquals("AAEC", payload)
        val (audio, video) = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("AAEC", audio)
        assertEquals("", video)
    }

    @Test
    fun `avec vidéo - JSON csd + vcsd`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "AAAAMUcC")
        assertTrue(payload.startsWith("{"))
        val (audio, video) = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("AAEC", audio)
        assertEquals("AAAAMUcC", video)
    }

    @Test
    fun `parse tolérant - payload brut avec quotes ne casse pas`() {
        val (audio, video) = P2pProtocol.AcceptPayload.parse("AAEC".toByteArray())
        assertEquals("AAEC", audio)
        assertEquals("", video)
    }

    @Test
    fun `HELLO - vcsd présent seulement si visio`() {
        val with = P2pProtocol.encodeHello(
            P2pProtocol.Hello("call", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion", "al", "cid", "AA==", 0, "sig", "VVV="), "sig"
        )
        val s = String(with, Charsets.UTF_8)
        assertTrue(s.contains("\"vcsd\":\"VVV=\""))
        assertEquals("VVV=", P2pProtocol.parseHello(with)!!.vcsd)

        val without = P2pProtocol.encodeHello(
            P2pProtocol.Hello("call", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion", "al", "cid", "AA==", 0, "sig"), "sig"
        )
        val s2 = String(without, Charsets.UTF_8)
        assertTrue(!s2.contains("vcsd"))
        assertEquals("", P2pProtocol.parseHello(without)!!.vcsd)
    }

    @Test
    fun `F_VIDEO constant distinct des autres trames`() {
        assertTrue(P2pProtocol.F_VIDEO != P2pProtocol.F_AUDIO)
        assertTrue(P2pProtocol.F_VIDEO == 0x16)
    }
}
