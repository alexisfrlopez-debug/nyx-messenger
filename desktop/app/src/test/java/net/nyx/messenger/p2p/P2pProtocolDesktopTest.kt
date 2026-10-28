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

    // ---------- v0.3.7 — codecs VIDÉO (interop visio bureau↔téléphone) ----------

    @Test
    fun `supportsMjpeg - listes annoncees par les pairs`() {
        // Téléphone ≥ v0.2.55 : les DEUX codecs.
        assertTrue(P2pProtocol.supportsMjpeg("h264,mjpeg"))
        // Bureau v0.3.7 : MJPEG seul.
        assertTrue(P2pProtocol.supportsMjpeg("mjpeg"))
        // Anciens : pas de MJPEG.
        assertEquals(false, P2pProtocol.supportsMjpeg(""))
        assertEquals(false, P2pProtocol.supportsMjpeg("h264"))
        // Espaces et casse tolérés, clé absente = vide.
        assertTrue(P2pProtocol.supportsMjpeg("h264, mjpeg"))
    }

    @Test
    fun `hello - roundtrip bureau visio mjpeg (vcsd vide, vw-vh porteurs)`() {
        val hello = P2pProtocol.Hello(
            "call", onion, "alek", "cid1234567890abcd", "AAQI", 0, "sig==",
            vcsd = "", vrot = 0, vw = 480, vh = 270,
            ac = "adpcm", vc = "mjpeg")
        val parsed = P2pProtocol.parseHello(P2pProtocol.encodeHello(hello, "sig=="))
        assertNotNull(parsed)
        assertEquals("mjpeg", parsed!!.vc)
        assertEquals("adpcm", parsed.ac)
        assertEquals("", parsed.vcsd)      // MJPEG : pas de csd H.264
        assertEquals(480, parsed.vw)        // aspect d'écran du bureau
        assertEquals(270, parsed.vh)
        assertEquals(0, parsed.vrot)
    }

    @Test
    fun `hello - un Android v0255 (h264,mjpeg) se parse cote bureau`() {
        val androidHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"$onion\",\"alias\":\"alek\"," +
            "\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0," +
            "\"vcsd\":\"aGVsbG8=\",\"vrot\":90,\"vw\":320,\"vh\":240," +
            "\"ac\":\"aac,adpcm\",\"vc\":\"h264,mjpeg\",\"sig\":\"sig==\"}"
        val parsed = P2pProtocol.parseHello(androidHello.toByteArray())
        assertNotNull(parsed)
        assertTrue(P2pProtocol.supportsMjpeg(parsed!!.vc))
        assertEquals("h264,mjpeg", parsed.vc)
        assertEquals(320, parsed.vw)
        assertEquals(240, parsed.vh)
    }

    @Test
    fun `hello - un pair v0254 (sans vc) se parse, vc vide`() {
        val oldHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"$onion\",\"alias\":\"alek\"," +
            "\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0," +
            "\"vcsd\":\"aGVsbG8=\",\"vrot\":90,\"vw\":320,\"vh\":240," +
            "\"ac\":\"aac,adpcm\",\"sig\":\"sig==\"}"
        val parsed = P2pProtocol.parseHello(oldHello.toByteArray())
        assertNotNull(parsed)
        assertEquals("", parsed!!.vc) // pas de visio PC↔téléphone avec un v0.2.54
        assertEquals("aGVsbG8=", parsed.vcsd)
    }

    @Test
    fun `accept - JSON avec codec video choisi mjpeg`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAQI", "", 90, 320, 240, "adpcm", "mjpeg")
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("mjpeg", info.vc)
        assertEquals("adpcm", info.ac)
        assertEquals("", info.videoCsd)   // MJPEG : aucun csd à transporter
        assertEquals(320, info.vw)
        assertEquals(240, info.vh)
        assertEquals(90, info.vrot)
    }

    @Test
    fun `accept - un pair v0254 (JSON sans vc) se parse, vc vide`() {
        val jsonNoVc = "{\"csd\":\"AAQI\",\"vcsd\":\"aGVsbG8=\",\"vrot\":90,\"vw\":320,\"vh\":240,\"ac\":\"adpcm\"}"
        assertEquals("", P2pProtocol.AcceptPayload.parse(jsonNoVc.toByteArray()).vc)
    }

    @Test
    fun `accept - format historique reste brut quand ni ac ni vc`() {
        // miroir Android : le b64 brut v0.2.19 n'est JAMAIS réintroduit par le vc.
        assertEquals("AAQI", P2pProtocol.AcceptPayload.encode("AAQI", "", 0, 0, 0, "", ""))
        val json = P2pProtocol.AcceptPayload.encode("AAQI", "", 0, 0, 0, "", "mjpeg")
        assertTrue(json.startsWith("{")) // le vc force le JSON
        assertTrue("\"vc\":\"mjpeg\"" in json)
    }

    @Test
    fun `signalisation - roundtrip offer avec intention visio mjpeg`() {
        val sig = CallSignal(
            CallSignal.OP_OFFER, "0123456789abcdef0123456789abcdef", "alek", 1770000000L,
            "", "adpcm", "mjpeg")
        val parsed = CallSignal.parse(sig.encode())
        assertNotNull(parsed)
        assertEquals("mjpeg", parsed!!.vc)
        assertEquals("adpcm", parsed.ac)
    }

    @Test
    fun `signalisation - un Android v0255 appelant video se lit cote bureau`() {
        val androidOffer = "{\"v\":1,\"kind\":\"call\",\"op\":\"offer\"," +
            "\"cid\":\"0123456789abcdef0123456789abcdef\",\"alias\":\"alek\",\"ts\":1770000000," +
            "\"relay\":\"\",\"ac\":\"aac,adpcm\",\"vc\":\"h264,mjpeg\"}"
        val parsed = CallSignal.parse(androidOffer)
        assertNotNull(parsed)
        assertTrue(P2pProtocol.supportsMjpeg(parsed!!.vc)) // l'écran d'appel affiche « visio »
    }

    // ---------- v0.3.7 — rotation du pair (F_VIDEO_ROT, miroir VideoRot Android) ----------

    @Test
    fun `videoRotCodec - payload ASCII 0 90 180 270 (format Android exact)`() {
        assertEquals(0, VideoRotCodec.decodeFrame("0".toByteArray(Charsets.US_ASCII)))
        assertEquals(90, VideoRotCodec.decodeFrame("90".toByteArray(Charsets.US_ASCII)))
        assertEquals(180, VideoRotCodec.decodeFrame("180".toByteArray(Charsets.US_ASCII)))
        assertEquals(270, VideoRotCodec.decodeFrame("270".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `videoRotCodec - payload illisible ignore sans erreur`() {
        assertNull(VideoRotCodec.decodeFrame("45".toByteArray(Charsets.US_ASCII)))
        assertNull(VideoRotCodec.decodeFrame(ByteArray(0)))
        assertNull(VideoRotCodec.decodeFrame(byteArrayOf(-1, -2)))
    }
}
