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

    /* ---------------- v0.2.53 — présentation vidéo (vrot/vw/vh) ---------------- */

    @Test
    fun `avec vidéo - JSON porte vrot vw vh`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "AAAAMUcC", 90, 320, 240)
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("AAEC", info.audioCsd)
        assertEquals("AAAAMUcC", info.videoCsd)
        assertEquals(90, info.vrot)
        assertEquals(320, info.vw)
        assertEquals(240, info.vh)
    }

    @Test
    fun `sans vidéo - présentation absente du format historique`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "")
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("AAEC", info.audioCsd)
        assertEquals("", info.videoCsd)
        assertEquals(0, info.vrot)
    }

    @Test
    fun `JSON v0202 sans vrot toléré - pair v0252`() {
        // Un appelé v0.2.52 répond {csd, vcsd} SANS les clés nouvelles.
        val legacy = "{\"csd\":\"AAEC\",\"vcsd\":\"AAAAMUcC\"}"
        val info = P2pProtocol.AcceptPayload.parse(legacy.toByteArray())
        assertEquals("AAEC", info.audioCsd)
        assertEquals("AAAAMUcC", info.videoCsd)
        assertEquals(0, info.vrot)
        assertEquals(0, info.vw)
        assertEquals(0, info.vh)
    }

    @Test
    fun `F_VIDEO_ROT constant distinct et défini`() {
        assertTrue(P2pProtocol.F_VIDEO_ROT == 0x17)
        assertTrue(P2pProtocol.F_VIDEO_ROT != P2pProtocol.F_VIDEO)
        assertTrue(P2pProtocol.F_VIDEO_ROT != P2pProtocol.F_BYE)
    }

    @Test
    fun `HELLO v0253 - vrot vw vh présents si visio, absents sinon`() {
        val onion = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"
        val with = P2pProtocol.encodeHello(
            P2pProtocol.Hello("call", onion, "al", "cid", "AA==", 0, "sig", "VVV=", 270, 320, 240), "sig"
        )
        val hello = P2pProtocol.parseHello(with)!!
        assertEquals(270, hello.vrot)
        assertEquals(320, hello.vw)
        assertEquals(240, hello.vh)
        assertTrue(String(with, Charsets.UTF_8).contains("\"vrot\":270"))

        // HELLO v0.2.52 (sans les champs) : parsé avec présentation nulle.
        val legacy = P2pProtocol.encodeHello(
            P2pProtocol.Hello("call", onion, "al", "cid", "AA==", 0, "sig", "VVV="), "sig"
        )
        val h2 = P2pProtocol.parseHello(legacy)!!
        assertEquals("VVV=", h2.vcsd)
        assertEquals(0, h2.vrot)
        assertEquals(0, h2.vw)
        assertEquals(0, h2.vh)
    }
}
