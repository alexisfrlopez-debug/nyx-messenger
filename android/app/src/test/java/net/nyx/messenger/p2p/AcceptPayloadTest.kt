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

    // ---------- v0.2.55 — codecs VIDÉO (interop visio PC↔téléphone) ----------

    @Test
    fun `v0255 - supportsMjpeg - listes annoncees par les pairs`() {
        assertTrue(P2pProtocol.supportsMjpeg("h264,mjpeg"))
        assertTrue(P2pProtocol.supportsMjpeg("mjpeg"))          // le bureau
        assertTrue(!P2pProtocol.supportsMjpeg(""))               // ≤ v0.2.54
        assertTrue(!P2pProtocol.supportsMjpeg("h264"))
        assertTrue(P2pProtocol.supportsMjpeg("h264, mjpeg"))     // espace toléré
    }

    @Test
    fun `v0255 - HELLO du bureau (mjpeg seul, vcsd vide) se parse`() {
        // C'est L'APPELANT bureau : vw/vh portent l'aspect de son écran.
        val hello = P2pProtocol.Hello("call",
            "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion",
            "pc", "cid1234567890abcd", "AAQI", 0, "sig==",
            vcsd = "", vrot = 0, vw = 480, vh = 270,
            ac = "adpcm", vc = "mjpeg")
        val parsed = P2pProtocol.parseHello(P2pProtocol.encodeHello(hello, "sig=="))
        assertEquals("mjpeg", parsed!!.vc)
        assertEquals("", parsed.vcsd)
        assertEquals(480, parsed.vw)
        assertEquals(270, parsed.vh)
        // Choix CALLE : MJPEG seulement si l'appelant ne SAIT PAS le H.264.
        val codecs = parsed.vc.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(codecs.contains(P2pProtocol.VC_MJPEG))
        assertTrue(!codecs.contains(P2pProtocol.VC_H264))
    }

    @Test
    fun `v0255 - HELLO du téléphone (h264,mjpeg) se parse — l'appelé bureau choisit le MJPEG`() {
        val hello = P2pProtocol.Hello("call",
            "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion",
            "tel", "cid1234567890abcd", "AAQI", 0, "sig==",
            vcsd = "aGVsbG8=", vrot = 90, vw = 320, vh = 240,
            ac = "aac,adpcm", vc = "h264,mjpeg")
        val parsed = P2pProtocol.parseHello(P2pProtocol.encodeHello(hello, "sig=="))
        assertEquals("h264,mjpeg", parsed!!.vc)
        assertTrue(P2pProtocol.supportsMjpeg(parsed.vc))
        assertEquals("aGVsbG8=", parsed.vcsd) // téléphone↔téléphone : H.264 intact
    }

    @Test
    fun `v0255 - HELLO d'un pair ≤ v0254 (sans vc) se parse, vc vide`() {
        val oldHello = "{\"v\":1,\"t\":\"call\",\"onion\":\"kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion\"," +
            "\"alias\":\"tel\",\"cid\":\"cid1234567890abcd\",\"csd\":\"AAQI\",\"count\":0," +
            "\"vcsd\":\"aGVsbG8=\",\"vrot\":90,\"vw\":320,\"vh\":240,\"ac\":\"aac,adpcm\",\"sig\":\"sig==\"}"
        assertEquals("", P2pProtocol.parseHello(oldHello.toByteArray())!!.vc)
    }

    @Test
    fun `v0255 - ACCEPT avec codec video mjpeg (choix du bureau appelé)`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAQI", "", 0, 480, 270, "adpcm", "mjpeg")
        assertTrue(payload.startsWith("{"))
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("mjpeg", info.vc)
        assertEquals("adpcm", info.ac)
        assertEquals("", info.videoCsd)   // MJPEG : aucun csd à transporter
        assertEquals(480, info.vw)        // aspect d'écran du bureau
        assertEquals(270, info.vh)
    }

    @Test
    fun `v0255 - ACCEPT sans codec video (téléphone↔téléphone) reste inchangé`() {
        // Le champ vc est ABSENT du JSON téléphone↔téléphone — format v0.2.20
        // OCTET POUR OCTET (un appelant v0.2.54 lit le JSON sans le voir).
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "AAAAMUcC", 90, 320, 240)
        assertTrue(payload.startsWith("{"))
        assertTrue(!payload.contains("\"vc\""))
        assertEquals("", P2pProtocol.AcceptPayload.parse(payload.toByteArray()).vc)
    }

    @Test
    fun `v0255 - offre de signalisation avec intention visio se parse`() {
        val sig = CallSignal(CallSignal.OP_OFFER, "0123456789abcdef0123456789abcdef", "tel", 1770000000L,
            "", "aac,adpcm", "h264,mjpeg")
        val parsed = CallSignal.parse(sig.encode())
        assertEquals("h264,mjpeg", parsed!!.vc)
        // Un ancien format (sans vc) reste lisible — clé ignorée.
        val old = CallSignal.parse("{\"v\":1,\"kind\":\"call\",\"op\":\"offer\"," +
            "\"cid\":\"0123456789abcdef\",\"alias\":\"tel\",\"ts\":1770000000,\"ac\":\"aac,adpcm\"}")
        assertEquals("", old!!.vc)
    }

    @Test
    fun `v0258 - ACCEPT vpr=1 du bureau - pré-rotation MJPEG accordée`() {
        // Terrain 28/10 : « toujours un problème d'orientation de la video et
        // de resolution qui devrait s'adapter à l'orientation » — le bureau
        // ≥ v0.3.10 répond vpr="1" : le téléphone appelant pré-tourne ses
        // trames à l'encodage (portrait 240x320 ↔ paysage 320x240).
        // SEUL LE BUREAU émet vpr : le JSON est forgé tel qu'un bureau v0.3.10
        // l'écrit (miroir de son AcceptPayload.encode à 8 paramètres).
        val payload = "{\"csd\":\"AAEC\",\"vcsd\":\"\",\"vrot\":0,\"vw\":480,\"vh\":270," +
            "\"ac\":\"adpcm\",\"vc\":\"mjpeg\",\"vpr\":\"1\"}"
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("1", info.vpr)
        assertEquals("mjpeg", info.vc)
        assertEquals("adpcm", info.ac)
    }

    @Test
    fun `v0258 - ACCEPT sans vpr (bureau ancien) - comportement historique`() {
        // Un bureau ≤ v0.3.9 n'accorde PAS la pré-rotation : champ absent →
        // le téléphone garde la métadonnée de rotation (F_VIDEO_ROT).
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "", 0, 480, 270, "adpcm", "mjpeg")
        assertTrue(!payload.contains("vpr"))
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertEquals("", info.vpr)
    }

    @Test
    fun `v0258 - le téléphone n'émet JAMAIS vpr - octet pour octet inchangé`() {
        // L'ENCODE Android n'a pas de paramètre vpr : le JSON produit par un
        // téléphone ≥ v0.2.58 est IDENTIQUE à celui d'un v0.2.55 (interop).
        val legacy = P2pProtocol.AcceptPayload.encode("AAEC", "AAAAMUcC", 90, 320, 240, "aac", "")
        assertTrue(!legacy.contains("vpr"))
        val mjpeg = P2pProtocol.AcceptPayload.encode("AAEC", "", 0, 240, 320, "adpcm", "mjpeg")
        assertTrue(!mjpeg.contains("vpr"))
    }

    // ---------- v0.2.60 — PROTOCOLE PARTAGE D'ÉCRAN VALIDÉ (champ sc) ----------

    @Test
    fun `v0260 - ACCEPT du téléphone écho sc=1 quand l'appelant l'annonce`() {
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "", 0, 320, 240, "adpcm", "mjpeg", "1")
        assertTrue(payload.contains("\"sc\":\"1\""))
        val info = P2pProtocol.AcceptPayload.parse(payload.toByteArray())
        assertTrue(P2pProtocol.supportsScreenShare(info.sc))
    }

    @Test
    fun `v0260 - ACCEPT SANS sc - téléphone↔téléphone inchangé`() {
        // Un appelé téléphone (pas bureau) ne met pas sc : champ absent.
        val payload = P2pProtocol.AcceptPayload.encode("AAEC", "AAAAMUcC", 90, 320, 240, "", "")
        assertTrue(!payload.contains("\"sc\""))
        assertTrue(!P2pProtocol.supportsScreenShare(P2pProtocol.AcceptPayload.parse(payload.toByteArray()).sc))
    }

    @Test
    fun `v0260 - HELLO du téléphone porte sc - le bureau peut l'écho-t-il`() {
        val sig = "SIGB64"
        val bytes = P2pProtocol.encodeHello(
            P2pProtocol.Hello("call", "a".repeat(56) + ".onion", "alias", "cid1", "AAEC", 0, sig,
                vcsd = "AAAAMUcC", vrot = 270, vw = 320, vh = 240,
                ac = "aac,adpcm", vc = "h264,mjpeg", sc = P2pProtocol.SC_PROTOCOL), sig
        )
        val hello = P2pProtocol.parseHello(bytes)!!
        assertTrue(P2pProtocol.supportsScreenShare(hello.sc))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"sc\":\"1\""))
    }

    @Test
    fun `v0260 - ACCEPT du bureau v0312 avec sc lu par le téléphone`() {
        // Le bureau ≥ v0.3.12 répond sc="1" : le téléphone sait qu'il peut
        // demander le partage de SON écran (F_SCREEN_REQ → ACCEPT → capture).
        // (L'ENCODE Android n'a pas de vpr : le sc du bureau est SIMULÉ par un
        // JSON littéral ci-dessous, byte pour byte ce que le bureau émet.)
        val bureau = "{\"csd\":\"AAEC\",\"vcsd\":\"\",\"vrot\":0,\"vw\":480,\"vh\":270," +
            "\"ac\":\"adpcm\",\"vc\":\"mjpeg\",\"vpr\":\"1\",\"sc\":\"1\"}"
        val info = P2pProtocol.AcceptPayload.parse(bureau.toByteArray())
        assertTrue(P2pProtocol.supportsScreenShare(info.sc))
        assertEquals("1", info.vpr)
    }
}
