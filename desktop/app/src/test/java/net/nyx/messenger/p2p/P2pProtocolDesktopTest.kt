package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * v0.3.6 — PROTOCOLE D'APPEL DESKTOP (portage P2pProtocol/CallSignal Android) :
 * verrou du format FILAIRE interopérable + extension codec « ac ».
 * Tout écart avec l'Android (encodeurs identiques octet à octet) brise
 * l'interop téléphone↔PC.
 */
class P2pProtocolDesktopTest {

    private val onion = "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion"

    // ---------- CallSignal ----------

    @Test
    fun `signalisation - roundtrip offer avec codec adpcm`() {
        val sig = CallSignal(CallSignal.OP_OFFER, "0123456789abcdef0123456789abcdef", "alek", 1770000000L, "", "adpcm")
        val parsed = CallSignal.parse(sig.encode())
        assertNotNull(parsed)
        assertEquals("offer", parsed!!.op)
        assertEquals("0123456789abcdef0123456789abcdef", parsed.cid)
        assertEquals("alek", parsed.alias)
        assertEquals(1770000000L, parsed.ts)
        assertEquals("adpcm", parsed.ac)
        assertEquals("", parsed.relay)
    }

    @Test
    fun `signalisation - un corps Android v0253 (sans ac) se parse, ac vide`() {
        val androidJson = "{\"v\":1,\"kind\":\"call\",\"op\":\"offer\"," +
            "\"cid\":\"0123456789abcdef\",\"alias\":\"alek\",\"ts\":1770000000," +
            "\"relay\":\"5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80\"}"
        val parsed = CallSignal.parse(androidJson)
        assertNotNull(parsed)
        assertEquals("", parsed!!.ac) // ancien téléphone : AAC implicite
        assertTrue(parsed.relay.endsWith(":80"))
    }

    @Test
    fun `signalisation - texte ordinaire rejete`() {
        assertNull(CallSignal.parse("coucou, ça va ?"))
        assertNull(CallSignal.parse("{\"kind\":\"msg\"}"))
        assertNull(CallSignal.parse("{\"kind\":\"call\",\"op\":\"grotx\",\"cid\":\"0123456789abcdef\",\"ts\":1}"))
    }

    // ---------- HELLO ----------

    @Test
    fun `hello - roundtrip bureau avec ac adpcm`() {
        val hello = P2pProtocol.Hello("call", onion, "alek", "cid1234567890abcd", "AAQI", 0, "sig==", ac = "adpcm")
        val bytes = P2pProtocol.encodeHello(hello, "sig==")
        val parsed = P2pProtocol.parseHello(bytes)
        assertNotNull(parsed)
        assertEquals("call", parsed!!.kind)
        assertEquals(onion, parsed.onion)
        assertEquals("adpcm", parsed.ac)
        assertEquals("AAQI", parsed.csd)
        assertEquals("", parsed.vcsd) // audio seul
    }

    @Test
    fun `hello - un Android v0254 (aac,adpcm) se parse cote bureau`() {
        // Le téléphone annonce SES codecs supportés — le bureau y cherche adpcm.
        val androidHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"$onion\",\"alias\":\"alek\"," +
            "\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0,\"ac\":\"aac,adpcm\",\"sig\":\"sig==\"}"
        val parsed = P2pProtocol.parseHello(androidHello.toByteArray())
        assertNotNull(parsed)
        assertEquals("aac,adpcm", parsed!!.ac)
        assertTrue("adpcm" in parsed.ac.split(',').map { it.trim() })
    }

    @Test
    fun `hello - un Android v0253 (sans ac) se parse, ac vide`() {
        val oldHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"$onion\",\"alias\":\"alek\"," +
            "\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0,\"sig\":\"sig==\"}"
        val parsed = P2pProtocol.parseHello(oldHello.toByteArray())
        assertNotNull(parsed)
        assertEquals("", parsed!!.ac) // le bureau refusera (AAC indécodable)
    }

    @Test
    fun `hello - signature sur la chaîne nyx-p2p (miroir exact Android)`() {
        assertEquals("nyx-p2p|call|$onion|cid12345", P2pProtocol.helloSignedString("call", onion, "cid12345"))
    }

    // ---------- ACCEPT ----------

    @Test
    fun `accept - JSON avec codec choisi adpcm`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAQI", "", 0, 0, 0, "adpcm")
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("AAQI", info.audioCsd)
        assertEquals("adpcm", info.ac)
        assertEquals("", info.videoCsd)
    }

    @Test
    fun `accept - un Android v0253 (JSON sans ac ou brut) se parse, ac vide`() {
        // JSON sans ac (téléphone↔téléphone — le bureau refusera l'appel).
        val jsonNoAc = "{\"csd\":\"AAQI\",\"vcsd\":\"\",\"vrot\":0,\"vw\":0,\"vh\":0}"
        assertEquals("", P2pProtocol.AcceptPayload.parse(jsonNoAc.toByteArray()).ac)
        // Format historique BRUT (v0.2.19) : b64 direct.
        assertEquals("AAQI", P2pProtocol.AcceptPayload.parse("AAQI".toByteArray()).audioCsd)
        assertEquals("", P2pProtocol.AcceptPayload.parse("AAQI".toByteArray()).ac)
    }

    // ---------- trames filaires ----------

    @Test
    fun `trames - format type u8 + longueur i32 BE + payload (fige v0218)`() {
        val out = ByteArrayOutputStream()
        P2pProtocol.write(out, P2pProtocol.F_AUDIO, byteArrayOf(1, 2, 3))
        val raw = out.toByteArray()
        assertEquals(8, raw.size)
        assertEquals(P2pProtocol.F_AUDIO, raw[0].toInt() and 0xFF)
        assertEquals(0, raw[1].toInt())
        assertEquals(0, raw[2].toInt())
        assertEquals(0, raw[3].toInt())
        assertEquals(3, raw[4].toInt())
        assertEquals(1, raw[5].toInt())
        // Lecture miroir.
        val frame = P2pProtocol.read(ByteArrayInputStream(raw))
        assertEquals(P2pProtocol.F_AUDIO, frame!!.type)
        assertEquals(3, frame.payload.size)
    }

    @Test
    fun `trame aberrante - plus de 4 Mo rejetee`() {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeByte(P2pProtocol.F_AUDIO)
        d.writeInt(5 * 1024 * 1024)
        try {
            P2pProtocol.read(ByteArrayInputStream(out.toByteArray()))
            throw AssertionError("trame aberrante acceptée")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `port media - 47471 (miroir Android)`() {
        assertEquals(47471, P2pProtocol.MEDIA_ONION_PORT)
    }
}
