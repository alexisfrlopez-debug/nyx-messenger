package net.nyx.messenger.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.58 — tests de la rotation NV21 À L'ENCODE (pré-rotation MJPEG, session 80 :
 * « toujours un problème d'orientation de la video et de resolution qui devrait
 * s'adapter à l'orientation »). Un tampon 4x4 (Y en dégradé, chroma en paires
 * VU distinctes) est tourné de 90/180/270 : chaque octet Y et chaque paire VU
 * doit se retrouver À SA PLACE attendue, et les dimensions permutées (90/270).
 */
class Nv21RotTest {

    /** NV21 4x4 : Y = 0..15, chroma = paires (V,U) distinctes 100..107. */
    private fun buf4x4(): ByteArray {
        val ySize = 16
        val out = ByteArray(ySize + ySize / 2)
        for (i in 0 until ySize) out[i] = i.toByte()
        for (c in 0 until 8) out[ySize + c] = (100 + c).toByte()
        return out
    }

    @Test
    fun `rotation 0 - copie identique`() {
        val (out, dims) = Nv21Rot.rotate(buf4x4(), 4, 4, 0)
        assertEquals(4, dims[0]); assertEquals(4, dims[1])
        assertEquals(buf4x4().toList(), out.toList())
    }

    @Test
    fun `rotation 90 - dimensions permutées et Y déplacés`() {
        // Rotation HORAIRE 90 : dst[x*h + (h-1-y)] = src[y*w + x].
        val (out, dims) = Nv21Rot.rotate(buf4x4(), 4, 4, 90)
        assertEquals(4, dims[0]); assertEquals(4, dims[1]) // 4x4 reste 4x4 (carré de test)
        // src(0,0) → position (x=0, y=h-1=3) → index 0*4+3 = 3
        assertEquals(0.toByte(), out[3])
        // src(3,0) (index 3) → (x=3, y=3) → index 15
        assertEquals(3.toByte(), out[15])
        // src(0,3) (index 12) → (x=0, y=0) → index 0
        assertEquals(12.toByte(), out[0])
    }

    @Test
    fun `rotation 90 - rectangle 4x2 devient 2x4`() {
        // Y 4x2 (w=4, h=2) : src[y*4+x]
        val nv21 = ByteArray(8 + 4) // ySize=8, chroma 2x1 = 2 paires
        for (i in 0 until 8) nv21[i] = i.toByte()
        nv21[8] = 100; nv21[9] = 101; nv21[10] = 102; nv21[11] = 103
        val (out, dims) = Nv21Rot.rotate(nv21, 4, 2, 90)
        assertEquals(2, dims[0]); assertEquals(4, dims[1])
        // src(x,y) → dst(x'=h-1-y, y'=x) : src(0,0)=0 → dst(1,0) → index 0*2+1=1
        assertEquals(0.toByte(), out[1])
        // src(3,0)=3 → dst(1,3) → index 3*2+1=7
        assertEquals(3.toByte(), out[7])
        // src(0,1)=4 → dst(0,0) → index 0
        assertEquals(4.toByte(), out[0])
        // src(1,1)=5 → dst(0,1) → index 1*2+0=2
        assertEquals(5.toByte(), out[2])
        // chroma 2x1 → 1x2 : la paire (100,101) en (0,0) reste en tête (0,0),
        // la paire (102,103) en (1,0) passe en (0,1).
        assertEquals(100.toByte(), out[8]); assertEquals(101.toByte(), out[9])
        assertEquals(102.toByte(), out[10]); assertEquals(103.toByte(), out[11])
    }

    @Test
    fun `rotation 180 - Y et chroma retournés`() {
        val (out, dims) = Nv21Rot.rotate(buf4x4(), 4, 4, 180)
        assertEquals(4, dims[0]); assertEquals(4, dims[1])
        // dst[(h-1-y)*w + (w-1-x)] = src[y*w+x] → src(0,0) → index 15
        assertEquals(0.toByte(), out[15])
        assertEquals(15.toByte(), out[0])
        // chroma 2x2 : la paire (V,U) de (0,0) → (1,1) ; chroma src (100,101)
        // (index 16,17) atterrit en (cw-1, ch-1)=(1,1) → index 16 + (1*2+1)*2 = 22
        assertEquals(100.toByte(), out[22])
        assertEquals(101.toByte(), out[23])
    }

    @Test
    fun `rotation 270 - miroir de 90`() {
        val (out, dims) = Nv21Rot.rotate(buf4x4(), 4, 4, 270)
        assertEquals(4, dims[0]); assertEquals(4, dims[1])
        // dst[(w-1-x)*h + y] = src[y*w+x] → src(0,0) → index (4-1)*4+0 = 12
        assertEquals(0.toByte(), out[12])
        // src(3,3) (index 15) → (x'=y=3? non) : x=3,y=3 → (w-1-x)=0, y=3 → 0*4+3=3
        assertEquals(15.toByte(), out[3])
    }

    @Test
    fun `rotateIfNeeded - 0 degré économise la copie`() {
        val src = buf4x4()
        val (out, dims) = Nv21Rot.rotateIfNeeded(src, 4, 4, 0)
        assertTrue(out === src)   // même référence : aucune copie inutile
        assertEquals(4, dims[0]); assertEquals(4, dims[1])
    }

    @Test
    fun `rotation totale d'une image dressée 270 - le portrait sort droit`() {
        // Cas terrain : téléphone en portrait, rotation qui rend l'image
        // droite = 270 (capteur front + écran 0). Le tampon 4x2 paysage
        // devient 2x4 portrait — la RÉSOLUTION SUIT l'orientation.
        val nv21 = ByteArray(8 + 4)
        for (i in 0 until 8) nv21[i] = i.toByte()
        nv21[8] = 100; nv21[9] = 101; nv21[10] = 102; nv21[11] = 103
        val (out, dims) = Nv21Rot.rotate(nv21, 4, 2, 270)
        assertEquals(2, dims[0]); assertEquals(4, dims[1])
        // dst[(w-1-x)*h + y] = src[y*w+x] : src(0,0)=0 → (3)*2+0 = 6
        assertEquals(0.toByte(), out[6])
        // src(3,1)=7 → (0)*2+1 = 1
        assertEquals(7.toByte(), out[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dimensions impaires rejetées`() {
        Nv21Rot.rotate(ByteArray(9 + 4), 3, 3, 90)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rotation non multiple de 90 rejetée`() {
        Nv21Rot.rotate(buf4x4(), 4, 4, 45)
    }
}
