package net.nyx.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * v0.3.2 — INTEROPÉRABILITÉ RÉELLE téléphone → PC.
 *
 * Le vecteur [interop-vector-android-backup.json] est une enveloppe
 * nyx-enc-v1 scellée par une implémentation INDEPENDANTE (Python
 * cryptography : PBKDF2-HMAC-SHA256 150 000 it. + AES-256-GCM), aux
 * paramètres EXACTS de PasswordCrypto Android — c'est-à-dire le même
 * format que les fichiers de sauvegarde générés par l'application
 * téléphone. Si ce test est vert, le PC ouvre les sauvegardes du
 * téléphone : le trajet exact du retour terrain
 * (« je n'arrive pas à restaurer un fichier de sauvegarde de compte
 * que j'ai généré avec l'appli téléphone »).
 */
class PasswordCryptoInteropTest {

    private fun vector(): Triple<String, String, String> {
        val raw = javaClass.getResourceAsStream("/interop-vector-android-backup.json")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("vecteur d'interopérabilité absent du classpath de test")
        val o = org.json.JSONObject(raw)
        return Triple(
            o.getString("envelope"),
            o.getString("password"),
            o.getString("plain_utf8"),
        )
    }

    @Test
    fun `enveloppe scellée par une implémentation tierce - déchiffrée intacte`() {
        val (envelope, password, plain) = vector()
        assertTrue(PasswordCrypto.isEncrypted(envelope))

        val bytes = PasswordCrypto.decrypt(envelope, password.toCharArray())
        assertNotNull(bytes)

        // Contenu octet pour octet + charge utile JSON Nyx valide.
        assertArrayEquals(plain.toByteArray(Charsets.UTF_8), bytes)
        val backup = org.json.JSONObject(String(bytes!!, Charsets.UTF_8))
        assertEquals("nyx-identity-backup", backup.getString("format"))
        assertEquals(2, backup.getInt("version"))
        assertEquals("alek", backup.getString("alias"))
        assertTrue(backup.getString("recovery_key").startsWith("nyx1."))

        // Et cette charge est reconnaissable par IdentityBackup (v2 complet).
        val found = IdentityBackup.extract(String(bytes, Charsets.UTF_8))
        assertNotNull(found)
        assertEquals("alek", found!!.alias)
    }

    @Test
    fun `mauvais mot de passe sur l'enveloppe tierce - refus silencieux`() {
        val (envelope, password, _) = vector()
        assertNull(PasswordCrypto.decrypt(envelope, "faux".toCharArray()))
        assertNull(PasswordCrypto.decrypt(envelope, password.uppercase().toCharArray()))
        assertNull(PasswordCrypto.decrypt(envelope, CharArray(0)))
    }

    @Test
    fun `empreinte du vecteur stable - garde-fou de régression du format`() {
        val (envelope, _, plain) = vector()
        // SHA-256 de l'enveloppe : tout changement de format Android/desktop
        // (magic, séparateurs, base64) casse ce test → regeneration assumée.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(envelope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertTrue(
            "empreinte de l'enveloppe divergente : $digest",
            digest == "70345cfccd8c46fa4be1ace22cb74931ce6f8476e68c3155cad9d30179fd222f"
        )
        assertEquals(244, plain.length) // sanity : charge témoin non mutée
    }
}
