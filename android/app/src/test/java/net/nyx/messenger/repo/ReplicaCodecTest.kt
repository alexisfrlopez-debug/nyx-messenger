package net.nyx.messenger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.25 — annonce de réplica temporaire (corps du type « contact » chiffré,
 * opaque serveur). Roundtrip encode→parse, garde-fous d'identité
 * (isSelfAnnounced) et rejets des corps NON annonce (invitation, acks,
 * accusés — compat v0.2.14) + portes du format d'adresse.
 */
class ReplicaCodecTest {

    private val onion = "a".repeat(56) + ".onion"

    @Test
    fun `roundtrip encode puis parse`() {
        val body = ReplicaCodec.encodeBody("alek", "$onion:80", "0.2.25")
        val ann = ReplicaCodec.parse(body)!!
        assertEquals("$onion:80", ann.replica)
        assertEquals("alek", ann.alias)
        assertEquals("0.2.25", ann.ver)
    }

    @Test
    fun `sans port et sans ver — parse accepté`() {
        val ann = ReplicaCodec.parse("{\"replica\":\"$onion\"}")!!
        assertEquals(onion, ann.replica)
        assertEquals("", ann.alias)
        assertEquals("", ann.ver)
    }

    @Test
    fun `garde-fou identité — auteur == onion annoncée`() {
        val ann = ReplicaCodec.parse("{\"replica\":\"$onion:80\"}")!!
        assertTrue(ReplicaCodec.isSelfAnnounced(onion, ann))
        assertFalse(ReplicaCodec.isSelfAnnounced("b".repeat(56) + ".onion", ann))
    }

    @Test
    fun `corps autres — parse null (compat ancienne)`() {
        // invitation
        assertNull(ReplicaCodec.parse("{\"alias\":\"alek\"}"))
        // ack
        assertNull(ReplicaCodec.parse("{\"alias\":\"alek\",\"ack\":\"accepted\"}"))
        // accusé (ReceiptCodec)
        assertNull(ReplicaCodec.parse(ReceiptCodec.encode("alek", ReceiptCodec.KIND_READ, listOf("x1"))))
        // JSON invalide / sans clé
        assertNull(ReplicaCodec.parse(""))
        assertNull(ReplicaCodec.parse("not json at all"))
    }

    @Test
    fun `adresse invalide — parse null`() {
        // pas du base32 56 chars
        assertNull(ReplicaCodec.parse("{\"replica\":\"xxx.onion:80\"}"))
        // port non numérique
        assertNull(ReplicaCodec.parse("{\"replica\":\"$onion:abc\"}"))
        // port hors bornes
        assertNull(ReplicaCodec.parse("{\"replica\":\"$onion:0\"}"))
        assertNull(ReplicaCodec.parse("{\"replica\":\"$onion:70000\"}"))
        // injection guillemets
        assertNull(ReplicaCodec.parse("{\"replica\":\"$onion:80\\\"}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode adresse invalide — exception`() {
        ReplicaCodec.encodeBody("alek", "garbage", "0.2.25")
    }

    @Test
    fun `encode échappe alias`() {
        val body = ReplicaCodec.encodeBody("al\"ek\\", "$onion:80", "")
        // le JSON reste parseable par notre regex même avec échappement
        val ann = ReplicaCodec.parse(body)
        assertTrue(ann != null && ann.replica == "$onion:80")
    }
}
