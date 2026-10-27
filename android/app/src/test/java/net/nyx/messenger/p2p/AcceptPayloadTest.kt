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

    // ---------------- v0.2.54 — codec ADPCM (interop appels PC↔téléphone) ----------------

    @Test
    fun `v0254 - ACCEPT avec codec adpcm (choix du bureau)`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAQI", "", 0, 0, 0, "adpcm")
        assertTrue(payload.startsWith("{"))
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("adpcm", info.ac)
        assertEquals("AAQI", info.audioCsd)
    }

    @Test
    fun `v0254 - ACCEPT sans codec (téléphone↔téléphone) reste historique`() {
        // Pas de vidéo + pas de codec → format BRUT (un appelant v0.2.19 le lit).
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "")
        assertEquals("AAEC", payload)
        assertEquals("", P2pProtocol.AcceptPayload.parse(payload.toByteArray()).ac)
    }

    @Test
    fun `v0254 - HELLO du bureau (adpcm seul) se parse`() {
        val hello = P2pProtocol.Hello("call", "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion",
            "pc", "cid1234567890abcd", "AAQI", 0, "sig==", ac = "adpcm")
        val parsed = P2pProtocol.parseHello(P2pProtocol.encodeHello(hello, "sig=="))
        assertEquals("adpcm", parsed!!.ac)
        // Le choix CALLE : ADPCM seulement si l'appelant ne SAIT PAS l'AAC.
        val codecs = parsed.ac.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(codecs.contains(P2pProtocol.AC_ADPCM))
        assertTrue(!codecs.contains(P2pProtocol.AC_AAC))
    }

    @Test
    fun `v0254 - HELLO du téléphone (aac,adpcm) se parse — l'appelé garde l'AAC`() {
        val hello = P2pProtocol.Hello("call", "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion",
            "tel", "cid1234567890abcd", "AAQI", 0, "sig==", ac = "aac,adpcm")
        val parsed = P2pProtocol.parseHello(P2pProtocol.encodeHello(hello, "sig=="))
        assertEquals("aac,adpcm", parsed!!.ac)
        val codecs = parsed.ac.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(codecs.contains(P2pProtocol.AC_AAC)) // → l'appelé téléphone choisit AAC
    }

    @Test
    fun `v0254 - HELLO d'un téléphone ≤ v0253 (sans ac) se parse, ac vide`() {
        val oldHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion\"," +
            "\"alias\":\"tel\",\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0,\"sig\":\"sig==\"}"
        assertEquals("", P2pProtocol.parseHello(oldHello.toByteArray())!!.ac)
    }

    @Test
    fun `v0254 - offre de signalisation avec codecs se parse (champ informationnel)`() {
        val sig = CallSignal(CallSignal.OP_OFFER, "0123456789abcdef0123456789abcdef", "tel", 1770000000L, "", "aac,adpcm")
        val parsed = CallSignal.parse(sig.encode())
        assertEquals("aac,adpcm", parsed!!.ac)
        // Et un ancien format (sans ac) reste lisible.
        val old = CallSignal.parse("{\"v\":1,\"kind\":\"call\",\"op\":\"offer\"," +
            "\"cid\":\"0123456789abcdef\",\"alias\":\"tel\",\"ts\":1770000000}")
        assertEquals("", old!!.ac)
    }
}
