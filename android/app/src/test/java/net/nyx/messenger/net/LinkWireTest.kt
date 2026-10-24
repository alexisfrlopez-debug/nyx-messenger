package net.nyx.messenger.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * v0.2.46 — CONTRAT DU FILAIRE NYX-LINK (ADR-018, spec protocol/specs/link.md).
 *
 * Les canons de signature sont vérifiés OCTET PAR OCTET contre le miroir Go
 * (server/internal/api/link.go — linkVerifySig) : une divergence d'un seul
 * caractère casserait TOUT le lien (401 systématique côté routeur).
 */
class LinkWireTest {

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `canon pull - octets exacts`() {
        assertEquals(
            "NYX-LINK-PULL\nkxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion\n1761234567",
            LinkWire.pullCanonical("kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion", 1761234567)
        )
    }

    @Test
    fun `canon reply - octets exacts avec sha256 du corps`() {
        val canon = LinkWire.replyCanonical(
            "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion",
            1761234567, "a1b2c3d4e5f60718293a4b5c6d7e8f90", 200,
            sha256Hex("eyJvayI6dHJ1ZX0=")
        )
        assertEquals(
            "NYX-LINK-REPLY\nkxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion\n" +
                "1761234567\na1b2c3d4e5f60718293a4b5c6d7e8f90\n200\n" +
                sha256Hex("eyJvayI6dHJ1ZX0="),
            canon
        )
    }

    @Test
    fun `sha256Hex - hexa minuscule du TEXTE transitant`() {
        // Vecteur de contrôle : sha256("") connu.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            LinkWire.sha256Hex("")
        )
        assertEquals(64, LinkWire.sha256Hex("abc").length)
        assertTrue(LinkWire.sha256Hex("abc").all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `splitPath - path nu, query encodée, valeurs vides`() {
        // Path nu.
        assertEquals("/health" to emptyMap<String, String>(), LinkWire.splitPath("/health"))
        // Query complète (l'auth contient ':' et du base64 avec '+' → encodé).
        val (p, q) = LinkWire.splitPath(
            "/client/inbox?to=kxcw%3Axx&after=12&limit=32&auth=1761234567%3AQmFzZTY0Kzg"
        )
        assertEquals("/client/inbox", p)
        assertEquals("kxcw:xx", q["to"])
        assertEquals("12", q["after"])
        assertEquals("32", q["limit"])
        assertEquals("1761234567:QmFzZTY0Kzg", q["auth"])
        // Clé sans valeur.
        val (_, q2) = LinkWire.splitPath("/x?flag&b=2")
        assertEquals("", q2["flag"])
        assertEquals("2", q2["b"])
    }

    @Test
    fun `corps send et reply - JSON exact`() {
        assertEquals(
            "{\"to\":\"a.onion\",\"id\":\"ab\",\"method\":\"GET\",\"path\":\"/health\",\"body_b64\":\"\"}",
            LinkWire.buildSendBody("a.onion", "ab", "GET", "/health", "")
        )
        assertEquals(
            "{\"id\":\"ab\",\"from\":\"a.onion\",\"status\":200,\"body_b64\":\"eA==\",\"auth\":\"1:eHg\"}",
            LinkWire.buildReplyBody("ab", "a.onion", 200, "eA==", "1:eHg")
        )
        // L'échappement JSON des valeurs (un path tordu ne doit pas casser le corps).
        val body = LinkWire.buildSendBody("a.onion", "ab", "POST", "/client/event?a=\"1\"", "x")
        assertTrue(body.contains("\"path\":\"/client/event?a=\\\"1\\\"\""))
    }

    @Test
    fun `parsePull - lot vide, lot multiple, objets incomplets ignorés`() {
        // Keepalive vide.
        assertTrue(LinkWire.parsePull("{\"reqs\":[]}").isEmpty())
        // Lot complet.
        val json = """
            {"reqs":[
              {"id":"a1b2c3d4e5f60718","method":"GET","path":"/health","body_b64":""},
              {"id":"b1b2c3d4e5f60718","method":"POST","path":"/client/event","body_b64":"eHg="}
            ]}
        """.trimIndent()
        val reqs = LinkWire.parsePull(json)
        assertEquals(2, reqs.size)
        assertEquals("a1b2c3d4e5f60718", reqs[0].id)
        assertEquals("GET", reqs[0].method)
        assertEquals("/health", reqs[0].path)
        assertEquals("POST", reqs[1].method)
        assertEquals("eHg=", reqs[1].bodyB64)
        // Objet sans champ requis → ignoré (le routeur ne livrera jamais ça,
        // mais un parseur tolérant ne doit pas crasher).
        assertTrue(LinkWire.parsePull("{\"reqs\":[{\"id\":\"x\"}]}").isEmpty())
        // Pas de champ reqs → vide.
        assertTrue(LinkWire.parsePull("{\"error\":\"x\"}").isEmpty())
    }

    @Test
    fun `parseWait - réponse, absent, erreur`() {
        val r = LinkWire.parseWait("{\"status\":200,\"body_b64\":\"eyJvayI6dHJ1ZX0=\"}")
        assertNotNull(r)
        assertEquals(200, r!!.status)
        assertEquals("eyJvayI6dHJ1ZX0=", r.bodyB64)
        assertNull(LinkWire.parseWait("{\"error\":\"délai dépassé\"}"))
        assertEquals("délai dépassé", LinkWire.parseError("{\"error\":\"délai dépassé\"}"))
    }

    @Test
    fun `newRequestId - hex unique de 32 caractères`() {
        val a = LinkWire.newRequestId()
        val b = LinkWire.newRequestId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }
}
