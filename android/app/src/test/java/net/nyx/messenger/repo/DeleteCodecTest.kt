package net.nyx.messenger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.19 — tests JVM du codec de suppression (DeleteCodec) — le canal
 * « contact » chiffré transporte {"alias":…,"del":[eventId,…]} ; les corps
 * voisins (invitation, ack, accusé) ne doivent JAMAIS parser comme une
 * suppression, et inversement.
 */
class DeleteCodecTest {

    private val ids = listOf(
        "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
        "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    )

    @Test
    fun `encode roundtrip`() {
        val body = DeleteCodec.encode("alek", ids)
        assertEquals(ids, DeleteCodec.parse(body))
        assertTrue(body.contains("\"alias\":\"alek\""))
        assertTrue(body.contains("\"del\":"))
    }

    @Test
    fun `encode liste vide reste valide`() {
        val body = DeleteCodec.encode("alek", emptyList())
        val parsed = DeleteCodec.parse(body)
        assertNotNull(parsed)
        assertTrue(parsed!!.isEmpty())
    }

    @Test
    fun `un accusé rcpt ne parse PAS comme suppression`() {
        val receipt = ReceiptCodec.encode("alek", "read", ids)
        assertNull(DeleteCodec.parse(receipt))
        // Et inversement : une suppression n'est pas un accusé.
        assertNull(ReceiptCodec.parse(DeleteCodec.encode("alek", ids)))
        // Les deux codecs restent utilisables sur LEUR propre corps.
        assertEquals("read" to ids, ReceiptCodec.parse(receipt))
    }

    @Test
    fun `invitation et ack ne parse PAS comme suppression`() {
        assertNull(DeleteCodec.parse("{\"alias\":\"alek\"}"))
        assertNull(DeleteCodec.parse("{\"alias\":\"alek\",\"ack\":\"accepted\"}"))
    }

    @Test
    fun `corps sans del ne parse pas`() {
        assertNull(DeleteCodec.parse(""))
        assertNull(DeleteCodec.parse("{\"rcpt\":\"read\",\"ids\":[\"x\"]}"))
        assertNull(DeleteCodec.parse("texte libre du message"))
    }

    @Test
    fun `garde - un corps accusé portant un champ del est rejeté`() {
        // Jamais émis ainsi, mais le plus spécifique (accusé) doit gagner.
        val hybrid = "{\"alias\":\"a\",\"rcpt\":\"read\",\"ids\":[\"x\"],\"del\":[\"y\"]}"
        assertNull(DeleteCodec.parse(hybrid))
        assertNotNull(ReceiptCodec.parse(hybrid))
    }

    @Test
    fun `batch limite documentée`() {
        assertTrue(DeleteCodec.BATCH == 50)
    }

    @Test
    fun `ids non hex restent littéraux (le codec ne juge pas le contenu)`() {
        val body = DeleteCodec.encode("a", listOf("callinfo-x"))
        assertEquals(listOf("callinfo-x"), DeleteCodec.parse(body))
    }

    @Test
    fun `espaces et quotes gérés comme les autres codecs`() {
        val body = "{\"alias\":\"a\", \"del\" : [ \"abc\" , \"def\" ]}"
        assertEquals(listOf("abc", "def"), DeleteCodec.parse(body))
        assertFalse(DeleteCodec.parse(body)!!.isEmpty())
    }
}
