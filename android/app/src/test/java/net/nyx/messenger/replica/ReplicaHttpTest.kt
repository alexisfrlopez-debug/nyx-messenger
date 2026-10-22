package net.nyx.messenger.replica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * v0.2.19 — tests JVM des briques PURES du réplica temporaire embarqué :
 * parsing HTTP minimal (ReplicaHttp), validation d'événements (miroir du
 * serveur Go — ReplicaValidate), limiteur de débit (ReplicaQuota).
 */
class ReplicaHttpTest {

    @Test
    fun `parse requête POST client-event`() {
        val body = """{"type":"msg","author":"a.onion","seq":1,"ts":10,"ttl":0,"to":"b.onion","body":"QQ==","sig":"sig"}"""
        val raw = "POST /client/event HTTP/1.1\r\nHost: x.onion\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val req = ReplicaHttp.parse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))
        assertNotNull(req)
        req!!
        assertEquals("POST", req.method)
        assertEquals("/client/event", req.path)
        assertTrue(req.query.isEmpty())
        assertEquals(body, String(req.body, Charsets.UTF_8))
        assertEquals("application/json", req.headers["content-type"])
    }

    @Test
    fun `parse requête GET inbox avec query décodée`() {
        val raw = "GET /client/inbox?to=abc%2Bdef.onion&after=12&limit=32 HTTP/1.1\r\n\r\n"
        val req = ReplicaHttp.parse(ByteArrayInputStream(raw.toByteArray()))
        assertNotNull(req)
        req!!
        assertEquals("GET", req.method)
        assertEquals("/client/inbox", req.path)
        assertEquals("abc+def.onion", req.query["to"])
        assertEquals("12", req.query["after"])
        assertEquals("32", req.query["limit"])
        assertTrue(req.body.isEmpty())
    }

    @Test
    fun `flux vide renvoie null (sonde fermée sans requête)`() {
        assertNull(ReplicaHttp.parse(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corps dépassant la max size est rejeté`() {
        val raw = "POST /client/event HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"
        try {
            ReplicaHttp.parse(ByteArrayInputStream(raw.toByteArray()))
            throw AssertionError("Bad attendu")
        } catch (e: ReplicaHttp.Bad) {
            assertTrue(e.reason.contains("trop volumineux"))
        }
    }

    @Test
    fun `ligne de requête invalide rejetée`() {
        try {
            ReplicaHttp.parse(ByteArrayInputStream("N'IMPORTE QUOI\r\n\r\n".toByteArray()))
            throw AssertionError("Bad attendu")
        } catch (e: ReplicaHttp.Bad) {
            assertTrue(e.reason.contains("invalide"))
        }
    }

    @Test
    fun `réponse HTTP complète - en-têtes cohérents`() {
        val resp = String(ReplicaHttp.response(200, "{\"ok\":true}"), Charsets.ISO_8859_1)
        assertTrue(resp.startsWith("HTTP/1.1 200 OK"))
        assertTrue(resp.contains("Content-Type: application/json"))
        assertTrue(resp.contains("Content-Length: 11"))
        assertTrue(resp.endsWith("{\"ok\":true}"))
        assertTrue(resp.contains("Connection: close"))
    }

    @Test
    fun `errorJson échappe les quotes`() {
        assertEquals("{\"error\":\"déjà \\\"connu\\\"\"}", ReplicaHttp.errorJson("déjà \"connu\""))
    }

    @Test
    fun `parseQuery sans = traite la clé seule`() {
        val q = ReplicaHttp.parseQuery("flag&x=1")
        assertEquals("", q["flag"])
        assertEquals("1", q["x"])
    }
}

class ReplicaValidateTest {

    @Test
    fun `type inconnu rejeté avec message miroir du Go`() {
        val err = ReplicaValidate.basicError("inconnu", "QQ==", 100, 200)
        assertEquals("type inconnu \"inconnu\"", err)
    }

    @Test
    fun `body vide ou trop gros rejeté`() {
        assertTrue(ReplicaValidate.basicError("msg", "", 100, 200)?.contains("taille") == true)
        val big = "A".repeat(ReplicaValidate.MAX_BODY_SIZE + 1)
        assertTrue(ReplicaValidate.basicError("msg", big, 100, 200)?.contains("taille") == true)
    }

    @Test
    fun `body non base64 rejeté`() {
        assertTrue(ReplicaValidate.basicError("msg", "%%%pas-b64", 100, 200)?.contains("base64") == true)
    }

    @Test
    fun `dérive horloge au-delà de 24 h rejetée`() {
        val drift = ReplicaValidate.MAX_TS_DRIFT_S + 1
        assertTrue(ReplicaValidate.basicError("msg", "QQ==", 100, 100 + drift)?.contains("horodatage") == true)
        assertNull(ReplicaValidate.basicError("msg", "QQ==", 100, 100 + ReplicaValidate.MAX_TS_DRIFT_S))
    }

    @Test
    fun `types connus acceptés`() {
        for (t in listOf("msg", "contact", "prekey", "meta", "alias", "profile")) {
            assertNull(ReplicaValidate.basicError(t, "QQ==", 100, 200))
        }
    }

    @Test
    fun `eventId = sha256 hex de la représentation canonique (miroir Go)`() {
        val canonical = "NYX1\nmsg\nabc.onion\n1\n10\n0\ndef.onion\nc0ffee"
        val id = ReplicaValidate.eventId(canonical)
        assertEquals(64, id.length)
        // Stable et déterministe (même entrée → même id).
        assertEquals(id, ReplicaValidate.eventId(canonical))
        // Différent pour un autre corps.
        assertFalse(id == ReplicaValidate.eventId(canonical.replace("c0ffee", "ffffff")))
    }

    @Test
    fun `canonical inbox auth - format stable`() {
        assertEquals("NYX-INBOX\nabc.onion\n1234", ReplicaValidate.inboxCanonical("abc.onion", 1234))
    }
}

class ReplicaQuotaTest {

    @Test
    fun `limiteur autorise N puis refuse`() {
        val lim = ReplicaQuota.RateLimiter(maxPerWindow = 3, windowMs = 60_000)
        assertTrue(lim.allow("a", 0))
        assertTrue(lim.allow("a", 1))
        assertTrue(lim.allow("a", 2))
        assertFalse(lim.allow("a", 3))      // 4e dépôt dans la fenêtre → refusé
        assertTrue(lim.allow("b", 4))       // autre auteur, compteur indépendant
        assertTrue(lim.allow("a", 60_001))  // fenêtre expirée → repart
    }

    @Test
    fun `plafonds cohérents avec la promesse de sobriété`() {
        // La promesse opérateur : « ne pas bouffer trop de ressources/bande
        // passante/forfait » — bornes raisonnables documentées.
        assertTrue(ReplicaQuota.MAX_BYTES_TOTAL <= 64L * 1024 * 1024)
        assertTrue(ReplicaQuota.MAX_EVENTS_TOTAL <= 5_000)
        assertTrue(ReplicaQuota.MAX_EVENTS_PER_INBOX <= 500)
        assertEquals(48L * 3600 * 1000, ReplicaQuota.TTL_MS)
        assertTrue(ReplicaQuota.MAX_DEPOSITS_PER_MIN <= 120)
    }
}
