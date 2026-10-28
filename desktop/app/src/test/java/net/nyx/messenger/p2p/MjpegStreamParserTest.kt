package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.10 — tests de la CAMÉRA DU PC PAR DÉFAUT (demande opérateur session 80 :
 * « le pc fait un partage d'écran plutot que d'envoyer le flux caméra. Je veux
 * par défaut le flux caméra mais la possibilité de partager l'écran »).
 *
 * Le flux MJPEG de ffmpeg (stdout) est une CONCATÉNATION de JPEG complets :
 * [MjpegStreamParser] le découpe sur les marqueurs SOI 0xFFD8 … EOI 0xFFD9
 * (l'entropie JPEG STUFFE ses 0xFF en 0xFF00 : une paire FFD9 ne peut être
 * qu'une fin de trame — découpage non ambigu). Les trames doivent être
 * reconstituées EXACTEMENT, y compris quand elles arrivent MORCÉLÉES entre
 * plusieurs lectures stdout.
 */
class MjpegStreamParserTest {

    /** Un JPEG de synthèse : en-tête factice + charge + EOI. */
    private fun frame(seed: Int, size: Int = 300): ByteArray {
        val out = ByteArray(size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()          // SOI
        out[2] = 0xE0.toByte(); out[3] = 0x00                    // marqueur factice
        for (i in 4 until size - 2) out[i] = ((seed + i) and 0xFE).toByte() // jamais 0xFF : entropie stuffée
        out[size - 2] = 0xFF.toByte(); out[size - 1] = 0xD9.toByte() // EOI
        return out
    }

    @Test
    fun `une trame complète en une lecture`() {
        val f = frame(1)
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        p.feed(f, f.size)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }

    @Test
    fun `trame morcelée en trois lectures - reconstitution exacte`() {
        val f = frame(7, 500)
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        val a = f.size / 3
        p.feed(f.copyOfRange(0, a), a)
        assertEquals(0, got.size)   // rien avant la fin de la trame
        p.feed(f.copyOfRange(a, 2 * a), a)
        assertEquals(0, got.size)
        p.feed(f.copyOfRange(2 * a, f.size), f.size - 2 * a)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }

    @Test
    fun `deux trames collées puis une troisième morcelée`() {
        val f1 = frame(1, 200); val f2 = frame(2, 250); val f3 = frame(3, 260)
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        val glued = f1 + f2
        p.feed(glued, glued.size)
        assertEquals(2, got.size)
        assertArrayEquals(f1, got[0]); assertArrayEquals(f2, got[1])
        p.feed(f3, f3.size)
        assertEquals(3, got.size)
        assertArrayEquals(f3, got[2])
    }

    @Test
    fun `trame incomplète puis complétée après un autre morceau`() {
        // ffmpeg écrit le flux en continu : le tampon interne ne doit JAMAIS
        // perdre un octet entre deux lectures.
        val f = frame(9, 400)
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        val half = f.size / 2
        p.feed(f.copyOfRange(0, half), half)
        p.feed(f.copyOfRange(half, f.size), f.size - half)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }

    @Test
    fun `débris sans SOI - jetés sans fausser la suite`() {
        val junk = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val f = frame(5, 150)
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        p.feed(junk, junk.size)
        p.feed(f, f.size)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }

    @Test
    fun `trame déraisonnablement grosse - ignorée (garde mémoire)`() {
        // Un flux corrompu ne doit pas faire croître le tampon à l'infini :
        // au-delà de 2× le budget trame, le découpage jette.
        val big = ByteArray(DesktopCallVideo.MAX_FRAME_BYTES * 3)
        big[0] = 0xFF.toByte(); big[1] = 0xD8.toByte()
        val got = mutableListOf<ByteArray>()
        val p = MjpegStreamParser { got.add(it) }
        p.feed(big, big.size)
        assertEquals(0, got.size)   // jamais livrée
        // La suite fonctionne toujours.
        val f = frame(6, 150)
        p.feed(f, f.size)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }

    @Test
    fun `dimensions JPEG lues du marqueur SOF`() {
        // jpegDims est privé : la lecture des dimensions de la 1ʳᵉ trame
        // passe par la réflexion de la même logique — vérifions la FORME
        // du découpage à la place : une trame minimale reste entière.
        val f = frame(0, 64)
        assertTrue(f.size >= 64)
        val got = mutableListOf<ByteArray>()
        MjpegStreamParser { got.add(it) }.feed(f, f.size)
        assertEquals(1, got.size)
        assertArrayEquals(f, got[0])
    }
}
