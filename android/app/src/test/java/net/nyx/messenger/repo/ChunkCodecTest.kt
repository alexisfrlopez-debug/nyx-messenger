package net.nyx.messenger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.17 — tests JVM du codec des fragments de fichier (même discipline que
 * ReceiptCodec v0.2.15 : objet pur, compatibilité wire garantie).
 */
class ChunkCodecTest {

    @Test
    fun `roundtrip encode-parse`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val b64 = java.util.Base64.getEncoder().encodeToString(data)
        val json = ChunkCodec.encode(
            "aabbccdd00112233445566778899eeff", "photo.jpg", "image/jpeg",
            96_000, "ff".repeat(32), 0, 40, b64
        )
        val chunk = ChunkCodec.parse(json)
        assertNotNull(chunk)
        chunk!!
        assertEquals("aabbccdd00112233445566778899eeff", chunk.fid)
        assertEquals("photo.jpg", chunk.name)
        assertEquals("image/jpeg", chunk.mime)
        assertEquals(96_000L, chunk.size)
        assertEquals("ff".repeat(32), chunk.sha256)
        assertEquals(0, chunk.index)
        assertEquals(40, chunk.total)
        assertTrue(data.contentEquals(chunk.data))
    }

    @Test
    fun `nom avec séparateurs neutralisé`() {
        val json = ChunkCodec.encode(
            "a".repeat(32), "../../etc/passwd", "text/plain",
            10, "b".repeat(64), 0, 1,
            java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        )
        val chunk = ChunkCodec.parse(json)
        assertNotNull(chunk)
        assertFalse(chunk!!.name.contains('/'))
        assertFalse(chunk.name.contains("..\\"))
    }

    @Test
    fun `nom avec guillemets survit au JSON`() {
        val json = ChunkCodec.encode(
            "a".repeat(32), "l'été « photo » \"v2\".jpg", "image/jpeg",
            10, "b".repeat(64), 0, 1,
            java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        )
        val chunk = ChunkCodec.parse(json)
        assertNotNull(chunk)
        assertEquals("l'été « photo » \"v2\".jpg", chunk!!.name)
    }

    @Test
    fun `corps étrangers ne parsent pas`() {
        // Message texte ordinaire
        assertNull(ChunkCodec.parse("bonjour"))
        assertNull(ChunkCodec.parse("{\"texte\":\"salut\"}"))
        // Invitation v0.2.10
        assertNull(ChunkCodec.parse("{\"alias\":\"alice\"}"))
        // Accusé v0.2.15
        assertNull(ChunkCodec.parse("{\"alias\":\"alice\",\"rcpt\":\"read\",\"ids\":[\"x\"]}"))
    }

    @Test
    fun `fragments invalides rejetés`() {
        val data = java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        fun body(v: Int = 1, kind: String = "file", fid: String = "a".repeat(32),
                 name: String = "f.bin", mime: String = "application/octet-stream",
                 size: Long = 10, sha: String = "b".repeat(64), i: Int = 0, n: Int = 1,
                 d: String = data) =
            "{\"v\":$v,\"kind\":\"$kind\",\"fid\":\"$fid\",\"name\":\"$name\"," +
                "\"mime\":\"$mime\",\"size\":$size,\"sha256\":\"$sha\",\"i\":$i,\"n\":$n,\"data\":\"$d\"}"

        assertNull(ChunkCodec.parse(body(v = 2)))                       // version inconnue
        assertNull(ChunkCodec.parse(body(kind = "audio")))              // kind inconnu
        assertNull(ChunkCodec.parse(body(fid = "zz")))                  // fid mal formé
        assertNull(ChunkCodec.parse(body(sha = "c".repeat(63))))        // sha court
        assertNull(ChunkCodec.parse(body(i = 1, n = 1)))                // index hors bornes
        assertNull(ChunkCodec.parse(body(n = 0)))                       // total nul
        assertNull(ChunkCodec.parse(body(n = ChunkCodec.MAX_CHUNKS + 1)))
        assertNull(ChunkCodec.parse(body(size = 0)))                    // taille nulle
        assertNull(ChunkCodec.parse(body(size = ChunkCodec.MAX_FILE_SIZE + 1)))
        assertNull(ChunkCodec.parse(body(name = "  ")))                 // nom vide
        assertNull(ChunkCodec.parse(body(d = "!!!!pas du base64!!!!"))) // base64 cassé
        assertNull(ChunkCodec.parse(body(d = java.util.Base64.getEncoder().encodeToString(
            ByteArray(ChunkCodec.CHUNK_PAYLOAD + 1)))))                 // fragment trop gros
        assertNull(ChunkCodec.parse("{v:1"))                            // JSON tronqué
    }

    @Test
    fun `split — bornes exactes`() {
        // Fichier vide → aucun fragment (l'envoi refuse un fichier vide).
        assertTrue(ChunkCodec.split(ByteArray(0)).isEmpty())
        // Exactement un fragment.
        assertEquals(1, ChunkCodec.split(ByteArray(10)).size)
        // Exactement la taille d'un fragment.
        assertEquals(1, ChunkCodec.split(ByteArray(ChunkCodec.CHUNK_PAYLOAD)).size)
        // Un octet de plus → 2 fragments, second d'un octet.
        val two = ChunkCodec.split(ByteArray(ChunkCodec.CHUNK_PAYLOAD + 1))
        assertEquals(2, two.size)
        assertEquals(1, two[1].size)
        // Réassemblage fidèle sur un fichier pseudo-aléatoire de 300 Ko.
        val file = ByteArray(300 * 1024) { (it * 7 % 253).toByte() }
        val round = ChunkCodec.split(file).reduce { a, b -> a + b }
        assertTrue(file.contentEquals(round))
    }

    @Test
    fun `taille humaine`() {
        assertEquals("512 o", ChunkCodec.humanSize(512))
        assertEquals("96 Ko", ChunkCodec.humanSize(96 * 1024))
        assertEquals("1,5 Mo", ChunkCodec.humanSize((1.5 * 1024 * 1024).toLong()))
    }
}
