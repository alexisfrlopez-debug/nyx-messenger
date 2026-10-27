package net.nyx.messenger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.15 — codec des accusés de réception/lecture (corps du type « contact »
 * chiffré, opaque serveur). Roundtrip encode→parse + rejets des corps NON
 * accusé (invitation, acks accepted/declined/removed — compat v0.2.14).
 */
class ReceiptCodecTest {

    @Test
    fun `roundtrip encode puis parse`() {
        val body = ReceiptCodec.encode("alek", ReceiptCodec.KIND_DELIVERED,
            listOf("abc123", "def456"))
        val (kind, ids) = ReceiptCodec.parse(body)!!
        assertEquals(ReceiptCodec.KIND_DELIVERED, kind)
        assertEquals(listOf("abc123", "def456"), ids)
    }

    @Test
    fun `kind read accepté`() {
        val body = ReceiptCodec.encode("", ReceiptCodec.KIND_READ, listOf("x1"))
        val (kind, ids) = ReceiptCodec.parse(body)!!
        assertEquals(ReceiptCodec.KIND_READ, kind)
        assertEquals(listOf("x1"), ids)
    }

    @Test
    fun `ids vides — accusé valide sans cible`() {
        val body = ReceiptCodec.encode("alek", ReceiptCodec.KIND_READ, emptyList())
        val (kind, ids) = ReceiptCodec.parse(body)!!
        assertEquals(ReceiptCodec.KIND_READ, kind)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `corps invitation v0210 — PAS un accusé`() {
        assertNull(ReceiptCodec.parse("{\"alias\":\"alek\"}"))
    }

    @Test
    fun `corps ack v0212-v0214 — PAS un accusé`() {
        assertNull(ReceiptCodec.parse("{\"alias\":\"alek\",\"ack\":\"accepted\"}"))
        assertNull(ReceiptCodec.parse("{\"alias\":\"alek\",\"ack\":\"declined\"}"))
        assertNull(ReceiptCodec.parse("{\"alias\":\"alek\",\"ack\":\"removed\"}"))
    }

    @Test
    fun `rcpt inconnu — rejeté (évolution future sécurisée)`() {
        assertNull(ReceiptCodec.parse("{\"alias\":\"x\",\"rcpt\":\"seen\",\"ids\":[]}"))
    }

    @Test
    fun `corps vide ou non JSON — rejeté`() {
        assertNull(ReceiptCodec.parse(""))
        assertNull(ReceiptCodec.parse("pas du json"))
    }

    @Test
    fun `kinds valides`() {
        assertTrue(ReceiptCodec.isValidKind(ReceiptCodec.KIND_DELIVERED))
        assertTrue(ReceiptCodec.isValidKind(ReceiptCodec.KIND_READ))
        assertFalse(ReceiptCodec.isValidKind(""))
        assertFalse(ReceiptCodec.isValidKind("seen"))
    }

    @Test
    fun `batch 50 ids — roundtrip complet`() {
        val ids = (1..50).map { "event-%03d".format(it) }
        val body = ReceiptCodec.encode("alek", ReceiptCodec.KIND_DELIVERED, ids)
        val (kind, parsed) = ReceiptCodec.parse(body)!!
        assertEquals(ReceiptCodec.KIND_DELIVERED, kind)
        assertEquals(ids, parsed)
    }
}
