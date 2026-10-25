package net.nyx.messenger.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.51 — vecteurs purs de l'incident « ça décroche mais l'appel coupe
 * immédiatement » (terrain 25/10 04:58, 6/6) : le csd-0 AAC annoncé au pair
 * était un « csd factice » {0,0} (lecture d'outputFormat AVANT que le codec
 * C2 ne l'expose) qui tuait le décodeur du pair au démarrage. Le csd est
 * désormais CALCULÉ (AudioSpecificConfig = fonction pure du format) et le
 * csd reçu ASSAINI (interop v0.2.50).
 */
class AacCsdTest {

    @Test
    fun `notre appel - AAC-LC 16 kHz mono = 14 08`() {
        // Le format configuré par CallAudio (SAMPLE_RATE=16000, mono, LC).
        assertArrayEquals(byteArrayOf(0x14, 0x08), AacCsd.aacLc(16_000, 1))
    }

    @Test
    fun `vecteur de référence - AAC-LC 44100 stéréo = 12 10`() {
        // Le csd-0 le plus cité de la littérature (AAC-LC 44,1 kHz stéréo).
        assertArrayEquals(byteArrayOf(0x12, 0x10), AacCsd.aacLc(44_100, 2))
    }

    @Test
    fun `autre vecteur connu - AAC-LC 48000 stéréo = 11 90`() {
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacCsd.aacLc(48_000, 2))
    }

    @Test
    fun `audioObjectType vaut 2 (LC) dans les 5 premiers bits`() {
        // 0x14 = 0001 0100 : bits[0..4] = 00010 = 2 = AAC-LC.
        assertEquals(2, (AacCsd.aacLc(16_000, 1)[0].toInt() shr 3) and 0x1F)
    }

    @Test
    fun `le csd factice v0 2 50 est détecté cassé - deux octets nuls`() {
        // L'exact « ByteArray(2) » que le terrain a reçu 6 fois : {0,0}.
        assertTrue(AacCsd.looksBroken(ByteArray(2)))
    }

    @Test
    fun `tout-nul de n'importe quelle longueur est cassé - pas seulement 2`() {
        assertTrue(AacCsd.looksBroken(ByteArray(0)))
        assertTrue(AacCsd.looksBroken(byteArrayOf(0, 0, 0)))
        assertTrue(AacCsd.looksBroken(byteArrayOf(1)))       // trop court
    }

    @Test
    fun `un csd valide n'est pas cassé`() {
        assertFalse(AacCsd.looksBroken(byteArrayOf(0x14, 0x08)))
        assertFalse(AacCsd.looksBroken(byteArrayOf(0x12, 0x10)))
    }

    @Test
    fun `sanitize remplace le csd factice par le calcul local - interop v0250`() {
        // Un pair v0.2.50 envoie {0,0} : on décode quand même (config FIXE).
        assertArrayEquals(
            byteArrayOf(0x14, 0x08),
            AacCsd.sanitize(ByteArray(2), 16_000, 1)
        )
        assertArrayEquals(
            byteArrayOf(0x14, 0x08),
            AacCsd.sanitize(ByteArray(0), 16_000, 1)
        )
    }

    @Test
    fun `sanitize respecte un csd pair valide - même différent du nôtre`() {
        // Un pair avec un autre format VALIDE : c'est son affaire, on décode
        // avec SON csd (le décodeur s'adapte, l'AudioTrack reste 16 kHz).
        assertArrayEquals(
            byteArrayOf(0x12, 0x10),
            AacCsd.sanitize(byteArrayOf(0x12, 0x10), 16_000, 1)
        )
    }

    @Test
    fun `fréquence ou canaux hors normes - refus explicite (jamais de csd imaginaire)`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AacCsd.aacLc(44_056, 1)   // pas dans la table ISO
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AacCsd.aacLc(16_000, 0)   // PCE — indécodable sans l'élément
        }
    }
}
