package net.nyx.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enveloppe chiffrée nyx-enc-v1 desktop — MIROIR de PasswordCryptoTest
 * Android : les paramètres (PBKDF2-HMAC-SHA256 150 000 itérations, clé
 * 256 bits, AES-GCM tag 128, IV 12 o, sel 16 o) et le FORMAT TEXTE sont
 * identiques → les enveloppes scellées par le téléphone s'ouvrent ici et
 * réciproquement (retour terrain v0.3.2 : restauration des sauvegardes
 * générées par l'appli téléphone).
 */
class PasswordCryptoTest {

    @Test
    fun `aller-retour - contenu et mot de passe exacts`() {
        val plain = "clé nyx1 + contacts + curseurs — la nuit tombe".toByteArray(Charsets.UTF_8)
        val env = PasswordCrypto.encrypt(plain, "motdepasse".toCharArray())
        assertTrue(PasswordCrypto.isEncrypted(env))
        val back = PasswordCrypto.decrypt(env, "motdepasse".toCharArray())
        assertNotNull(back)
        assertArrayEquals(plain, back)
    }

    @Test
    fun `format - magic + quatre segments base64`() {
        val env = PasswordCrypto.encrypt(byteArrayOf(1, 2, 3), "abcd".toCharArray())
        val parts = env.trim().split(".")
        assertEquals(4, parts.size)
        assertEquals("nyx-enc-v1", parts[0])
        // Sel 16 o et IV 12 o — base64 STANDARD (padding inclus, miroir
        // Android Base64.getEncoder()) : 16 o → 24 caractères, 12 o → 16.
        assertEquals(24, parts[1].length)
        assertEquals(16, parts[2].length)
        assertFalse(env.contains("\n"))
    }

    @Test
    fun `mot de passe incorrect - null jamais de crash`() {
        val env = PasswordCrypto.encrypt("secret".toByteArray(), "bon".toCharArray())
        assertNull(PasswordCrypto.decrypt(env, "mauvais".toCharArray()))
        // Miroir v0.2.27 Android : mot de passe VIDE → null (pas d'exception).
        assertNull(PasswordCrypto.decrypt(env, CharArray(0)))
    }

    @Test
    fun `contours - entrées non enveloppes et tronquées`() {
        assertNull(PasswordCrypto.decrypt("nyx1.AAAA.xxxx", "p".toCharArray()))
        assertNull(PasswordCrypto.decrypt("pas-une-enveloppe", "p".toCharArray()))
        val env = PasswordCrypto.encrypt("secret".toByteArray(), "bon".toCharArray())
        // Troncature du chiffré → checksum GCM invalide → null.
        assertNull(PasswordCrypto.decrypt(env.dropLast(4), "bon".toCharArray()))
        assertFalse(PasswordCrypto.isEncrypted("du texte ordinaire"))
    }

    @Test
    fun `sels distincts - deux exports ne partagent rien`() {
        val env1 = PasswordCrypto.encrypt("x".toByteArray(), "p".toCharArray())
        val env2 = PasswordCrypto.encrypt("x".toByteArray(), "p".toCharArray())
        // Sel OU IV aléatoire → enveloppes toujours différentes.
        assertTrue(env1 != env2)
    }
}
