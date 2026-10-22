package net.nyx.messenger.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation des alias — miroir exact de `journal.AliasRE` côté serveur Go
 * (wire-format.md §6.2). Les vecteurs invalides sont rejetés par le serveur
 * (HTTP 400) : le client doit les filtrer AVANT tout envoi.
 */
class AliasValidatorTest {

    @Test
    fun `format conforme au serveur - alias valides acceptés`() {
        assertTrue(AliasValidator.isValid("abc"))
        assertTrue(AliasValidator.isValid("pseudo_1"))
        assertTrue(AliasValidator.isValid("a1b2c3_x_yz"))
        assertTrue(AliasValidator.isValid("a".repeat(24))) // 24 = limite haute
    }

    @Test
    fun `format conforme au serveur - alias invalides refusés`() {
        assertFalse(AliasValidator.isValid("AB"))                    // 2 chars
        assertFalse(AliasValidator.isValid("a".repeat(25)))          // 25 chars
        assertFalse(AliasValidator.isValid("avec-espace"))           // tiret interdit
        assertFalse(AliasValidator.isValid("Avec_Majuscule"))        // majuscule
        assertFalse(AliasValidator.isValid("accentué"))              // accent
        assertFalse(AliasValidator.isValid(""))                      // vide
        assertFalse(AliasValidator.isValid("mon@alias"))             // @ interdit
        assertFalse(AliasValidator.isValid("mon.alias"))             // point interdit
    }

    @Test
    fun `normalisation vers le format serveur`() {
        assertEquals("mon_alias", AliasValidator.normalize(" Mon Alias "))
        assertEquals("pseudo_1", AliasValidator.normalize("Pseudo-1"))
        assertEquals(24, AliasValidator.normalize("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").length)
    }

    @Test
    fun `suggestions quand l alias est pris ou invalide`() {
        val s = AliasValidator.suggestions("meme_alias")
        assertEquals(5, s.size)
        assertTrue(s.none { it == "meme_alias" })   // jamais le nom d'origine
        assertTrue(s.all { AliasValidator.isValid(it) })
        // trop court → base prolongée
        assertTrue(AliasValidator.suggestions("ab").all { AliasValidator.isValid(it) })
    }
}
