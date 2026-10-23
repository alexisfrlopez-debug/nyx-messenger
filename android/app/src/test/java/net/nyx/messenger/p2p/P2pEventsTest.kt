package net.nyx.messenger.p2p

import net.nyx.messenger.net.WireEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v0.2.41 — Extension « events » du protocole P2P (ADR-017 — transport de
 * replan) : HELLO kind=events (encode/parse/rejets), trame F_EVENT portant
 * un WireEvent JSON IDENTIQUE au filaire journal, miroir de la chaîne signée.
 */
class P2pEventsTest {

    private val onion = "a".repeat(56) + ".onion"

    @Test
    fun `hello events - roundtrip`() {
        val sig = "SIGb64".repeat(12)
        val h = P2pProtocol.Hello("events", onion, "alias", "", "", 3, sig)
        val back = P2pProtocol.parseHello(P2pProtocol.encodeHello(h, sig))
        assertNotNull(back)
        assertEquals("events", back!!.kind)
        assertEquals(onion, back.onion)
        assertEquals(3, back.count)
        assertEquals(sig, back.sig)
    }

    @Test
    fun `hello kind inconnu - toujours rejeté (compat ascendante)`() {
        val sig = "SIGb64".repeat(12)
        val h = P2pProtocol.Hello("evil", onion, "", "", "", 1, sig)
        assertNull(P2pProtocol.parseHello(P2pProtocol.encodeHello(h, sig)))
    }

    @Test
    fun `chaîne signée events - miroir exact du vérificateur`() {
        // L'expéditeur signe nyx-p2p|events|<onion>|<count> — le serveur
        // reconstruit la MÊME chaîne (helloSignedString partagé).
        val signed = P2pProtocol.helloSignedString("events", onion, "2")
        assertEquals("nyx-p2p|events|$onion|2", signed)
        // Et le sélecteur d'un appel reste le cid (inchangé, non-régression).
        assertEquals("nyx-p2p|call|$onion|cid42", P2pProtocol.helloSignedString("call", onion, "cid42"))
    }

    @Test
    fun `trame F_EVENT - roundtrip avec WireEvent JSON filaire`() {
        // Le payload de F_EVENT est le JSON EXACT déposé sur POST /client/event.
        val ev = WireEvent(
            type = "msg", author = onion, seq = 7, ts = 1_728_000_000L, ttl = 0,
            to = "b".repeat(56) + ".onion", body = "Q09SUFM=", sig = "c2ln"
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val wire = json.encodeToString(WireEvent.serializer(), ev)
        val bos = ByteArrayOutputStream()
        P2pProtocol.write(bos, P2pProtocol.F_EVENT, wire.toByteArray(Charsets.UTF_8))
        val frame = P2pProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull(frame)
        assertEquals(P2pProtocol.F_EVENT, frame!!.type)
        val back = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(WireEvent.serializer(), String(frame.payload, Charsets.UTF_8))
        assertEquals(ev, back)
        assertTrue(wire.contains("\"type\":\"msg\""))
    }

    @Test
    fun `trame F_EVENT - limite 4 Mo respectée (garde read inchangée)`() {
        // Un événement texte/signalisation ≪ 4 Ko ; la limite de trame
        // existante (4 Mo) couvre le cas le plus large sans nouveau plafond.
        val petit = ByteArray(4096) { 'x'.code.toByte() }
        val bos = ByteArrayOutputStream()
        P2pProtocol.write(bos, P2pProtocol.F_EVENT, petit)
        val frame = P2pProtocol.read(ByteArrayInputStream(bos.toByteArray()))
        assertNotNull(frame)
        assertEquals(4096, frame!!.payload.size)
    }
}
