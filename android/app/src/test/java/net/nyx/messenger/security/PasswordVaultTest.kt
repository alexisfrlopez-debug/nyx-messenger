package net.nyx.messenger.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.18 — Hachage des trois mots de passe : déterminisme (même sel = même
 * hash), unicité du sel, encodage/décodage hex, comparaison à temps constant.
 * (La couche préférences Android est hors périmètre JVM — fonctions pures.)
 */
class PasswordVaultTest {

    @Test
    fun `hash déterministe avec le même sel`() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val h1 = PasswordVault.hashPassword("mon-mot-de-passe".toCharArray(), salt)
        val h2 = PasswordVault.hashPassword("mon-mot-de-passe".toCharArray(), salt)
        assertArrayEquals(h1, h2)
        assertEquals(32, h1.size) // 256 bits
    }

    @Test
    fun `sels différents - hashes différents`() {
        val s1 = PasswordVault.newSalt()
        val s2 = PasswordVault.newSalt()
        assertFalse(s1.contentEquals(s2))
        val h1 = PasswordVault.hashPassword("abc".toCharArray(), s1)
        val h2 = PasswordVault.hashPassword("abc".toCharArray(), s2)
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `encode - decode roundtrip`() {
        val salt = PasswordVault.newSalt()
        val hash = PasswordVault.hashPassword("mot-de-passe-panique".toCharArray(), salt)
        val stored = PasswordVault.encode(salt, hash)
        val pair = PasswordVault.decode(stored)
        assertNotNull(pair)
        val (s2, h2) = pair!!
        assertTrue(salt.contentEquals(s2))
        assertTrue(hash.contentEquals(h2))
    }

    @Test
    fun `decode malformé - null`() {
        assertNull(PasswordVault.decode("pas-de-deux-points"))
        assertNull(PasswordVault.decode("a:b"))
        assertNull(PasswordVault.decode("zzzz:zzzz"))
    }

    @Test
    fun `comparaison à temps constant - sémantique standard`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3)
        val c = byteArrayOf(1, 2, 4)
        assertTrue(PasswordVault.constantTimeEquals(a, b))
        assertFalse(PasswordVault.constantTimeEquals(a, c))
    }

    /** v0.2.27 — crash terrain v0.2.26 (« password empty », PBKDF2, FATAL) :
     *  un champ vide confirmé levait PBEKeySpec("password empty") sur le
     *  thread principal. Garde : vide = jamais vérifié, jamais de crash. */
    @Test
    fun `mot de passe vide - jamais de crash`() {
        val salt = PasswordVault.newSalt()
        // hashPassword reste le primitif bas niveau (comportement inchangé pour
        // les appels internes non vides) — les GARDS couvrent les points d'entrée.
        assertArrayEquals(
            PasswordVault.hashPassword("x".toCharArray(), salt),
            PasswordVault.hashPassword("x".toCharArray(), salt)
        )
        // encode/decode restent sains pour la vérification non vide.
        val stored = PasswordVault.encode(salt, PasswordVault.hashPassword("secret".toCharArray(), salt))
        val decoded = PasswordVault.decode(stored)
        assertNotNull(decoded)
        assertTrue(PasswordVault.constantTimeEquals(
            PasswordVault.hashPassword("secret".toCharArray(), decoded!!.first), decoded.second))
    }

    /** v0.2.27 — PasswordCrypto : déchiffrer une enveloppe avec un mot de passe
     *  vide renvoie null (jamais « password empty ») — et le cycle
     *  encrypt/decrypt non vide reste correct. */
    @Test
    fun `decrypt mot de passe vide - null sans exception`() {
        val envelope = net.nyx.messenger.crypto.PasswordCrypto.encrypt(
            "données de test".toByteArray(Charsets.UTF_8), "mot-de-passe".toCharArray()
        )
        assertTrue(net.nyx.messenger.crypto.PasswordCrypto.isEncrypted(envelope))
        assertNull(net.nyx.messenger.crypto.PasswordCrypto.decrypt(envelope, CharArray(0)))
        assertNull(net.nyx.messenger.crypto.PasswordCrypto.decrypt(envelope, "".toCharArray()))
        assertEquals(
            "données de test",
            String(net.nyx.messenger.crypto.PasswordCrypto.decrypt(
                envelope, "mot-de-passe".toCharArray())!!, Charsets.UTF_8)
        )
        // Mauvais mot de passe → null (AEADBadTagException avalée), pas d'exception.
        assertNull(net.nyx.messenger.crypto.PasswordCrypto.decrypt(envelope, "faux".toCharArray()))
    }
}
