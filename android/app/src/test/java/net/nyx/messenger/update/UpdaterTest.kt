package net.nyx.messenger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.20 — tests JVM de la mise à jour automatique (partie PURE) : parsing
 * de la réponse GitHub /releases/latest (pré-release rejetée, APK standard
 * uniquement) et comparaison sémantique des versions.
 */
class UpdaterTest {

    private val jsonValid = """
        {
          "url": "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/1",
          "tag_name": "v0.2.20",
          "name": "Nyx v0.2.20",
          "prerelease": false,
          "assets": [
            {"name": "nyx-v0.2.20-20261017-120000-test.apk",
             "browser_download_url": "https://github.com/x/nyx-v0.2.20-test.apk"},
            {"name": "SHA256SUMS-v0.2.20.txt",
             "browser_download_url": "https://github.com/x/sums.txt"},
            {"name": "nyx-v0.2.20-20261017-120000-release.apk",
             "browser_download_url": "https://github.com/x/nyx-v0.2.20-release.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parse release valide - APK standard choisi (pas le test)`() {
        val r = Updater.parseLatest(jsonValid)
        assertEquals("v0.2.20", r!!.tag)
        assertEquals("0.2.20", r.version)
        assertEquals("https://github.com/x/nyx-v0.2.20-release.apk", r.apkUrl)
    }

    @Test
    fun `prerelease rejetée (validation de production)`() {
        val json = jsonValid.replace("\"prerelease\": false", "\"prerelease\": true")
        assertNull(Updater.parseLatest(json))
    }

    @Test
    fun `réponse sans APK standard rejetée`() {
        val json = jsonValid.replace("-release.apk\"", "- Standard.apk\"")
        assertNull(Updater.parseLatest(json))
    }

    @Test
    fun `json sans tag rejeté`() {
        assertNull(Updater.parseLatest("{\"prerelease\":false}"))
        assertNull(Updater.parseLatest("pas du json"))
    }

    @Test
    fun `comparaison sémantique des versions`() {
        assertTrue(Updater.isNewer("v0.2.20", "0.2.19"))
        assertTrue(Updater.isNewer("v0.3.0", "0.2.99"))
        assertTrue(Updater.isNewer("v1.0.0", "0.9.9"))
        assertFalse(Updater.isNewer("v0.2.19", "0.2.19"))
        assertFalse(Updater.isNewer("v0.2.18", "0.2.19"))
        assertFalse(Updater.isNewer("v0.2.20", "0.2.20"))
    }

    @Test
    fun `versions avec suffixe test gérées`() {
        assertTrue(Updater.isNewer("v0.2.21", "0.2.20-test2"))
        assertFalse(Updater.isNewer("v0.2.20", "0.2.20-test2"))
    }
}
