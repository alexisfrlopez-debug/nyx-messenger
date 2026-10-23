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
 * v0.2.40 — CANAL TEST : parsing de la liste /releases (première release
 * avec asset -test.apk, pré-releases ADMISES, drafts rejetés, anti-downgrade).
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

    /** Liste /releases (canal test) : la plus récente d'abord. */
    private val jsonList = """
        [
          {
            "url": "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/43",
            "tag_name": "v0.2.40",
            "name": "Nyx v0.2.40",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "nyx-v0.2.40-20261023-210000-test.apk",
               "browser_download_url": "https://github.com/x/nyx-v0.2.40-test.apk"},
              {"name": "nyx-v0.2.40-20261023-210000-release.apk",
               "browser_download_url": "https://github.com/x/nyx-v0.2.40-release.apk"},
              {"name": "SHA256SUMS-v0.2.40.txt",
               "browser_download_url": "https://github.com/x/sums40.txt"}
            ]
          },
          {
            "url": "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/42",
            "tag_name": "v0.2.39",
            "name": "Nyx v0.2.39",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "nyx-v0.2.39-20261023-180000-test.apk",
               "browser_download_url": "https://github.com/x/nyx-v0.2.39-test.apk"}
            ]
          }
        ]
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

    // ── v0.2.40 — CANAL TEST : parseLatestTest (liste /releases) ──

    @Test
    fun `canal test - première release (pré-release ADMISE) avec APK test choisie`() {
        val r = Updater.parseLatestTest(jsonList)
        assertEquals("v0.2.40", r!!.tag)
        assertEquals("0.2.40", r.version)
        // l'ASSET TEST est choisi, jamais l'asset -release.apk de la même release
        assertEquals("https://github.com/x/nyx-v0.2.40-test.apk", r.apkUrl)
    }

    @Test
    fun `canal test - release sans asset test est SAUTÉE (la suivante est prise)`() {
        // v0.2.40 sans asset -test.apk → on retombe sur v0.2.39
        val json = jsonList.replace(
            "nyx-v0.2.40-20261023-210000-test.apk", "nyx-v0.2.40-20261023-210000-STD.apk"
        )
        val r = Updater.parseLatestTest(json)
        assertEquals("v0.2.39", r!!.tag)
        assertEquals("https://github.com/x/nyx-v0.2.39-test.apk", r.apkUrl)
    }

    @Test
    fun `canal test - DRAFT rejeté (sécurité, l'API publique n'en liste pas)`() {
        // JSON dédié (trimIndent rend fragiles les replace multi-lignes) :
        // v0.2.40 en DRAFT avec asset test → SAUTÉE, v0.2.39 retenue.
        val json = """
            [
              {
                "url": "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/43",
                "tag_name": "v0.2.40",
                "name": "Nyx v0.2.40",
                "draft": true,
                "prerelease": true,
                "assets": [
                  {"name": "nyx-v0.2.40-20261023-210000-test.apk",
                   "browser_download_url": "https://github.com/x/nyx-v0.2.40-test.apk"}
                ]
              },
              {
                "url": "https://api.github.com/repos/alexisfrlopez-debug/nyx-messenger/releases/42",
                "tag_name": "v0.2.39",
                "name": "Nyx v0.2.39",
                "draft": false,
                "prerelease": true,
                "assets": [
                  {"name": "nyx-v0.2.39-20261023-180000-test.apk",
                   "browser_download_url": "https://github.com/x/nyx-v0.2.39-test.apk"}
                ]
              }
            ]
        """.trimIndent()
        val r = Updater.parseLatestTest(json)
        assertEquals("v0.2.39", r!!.tag)
    }

    @Test
    fun `canal test - anti-downgrade, version installée test plus récente que la liste`() {
        // installée « 0.2.40-test » (versionName du flavor diag) vs release v0.2.40
        // → isNewer FAUX (suffixe ignoré par la comparaison)
        assertFalse(Updater.isNewer("0.2.40", "0.2.40-test"))
        // et une liste plus ancienne ne déclenche jamais de mise à jour
        assertFalse(Updater.isNewer("0.2.39", "0.2.40-test"))
        // livraison suivante → OUI
        assertTrue(Updater.isNewer("0.2.41", "0.2.40-test"))
    }

    @Test
    fun `canal test - liste vide ou sans tag rejetée`() {
        assertNull(Updater.parseLatestTest("[]"))
        assertNull(Updater.parseLatestTest("pas du json"))
        assertNull(Updater.parseLatestTest("{\"message\":\"Not Found\"}"))
    }

    // ── v0.2.42 — PROTOCOLE DE SECOURS : parseurs des endpoints WEB ──

    /** Extrait réel du flux releases.atom (vérifié en production 2026-10-23). */
    private val atomFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
          <id>tag:github.com,2008:https://github.com/x/nyx-messenger/releases</id>
          <title>Release notes from nyx-messenger</title>
          <entry>
            <id>tag:github.com,2008:Repository/1415030332/v0.2.41</id>
            <updated>2026-10-23T00:26:36Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/x/nyx-messenger/releases/tag/v0.2.41"/>
            <title>Nyx v0.2.41</title>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1415030332/v0.2.40</id>
            <updated>2026-10-22T22:55:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/x/nyx-messenger/releases/tag/v0.2.40"/>
            <title>Nyx v0.2.40</title>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `atom - tags extraits ordre chronologique inverse`() {
        assertEquals(listOf("v0.2.41", "v0.2.40"), Updater.parseAtomTags(atomFeed))
    }

    @Test
    fun `atom - id du flux lui-même ignoré, flux vide ou invalide rejeté`() {
        // L'id global (sans /vX.Y.Z) ne doit pas produire de tag fantôme.
        assertTrue(Updater.parseAtomTags("<feed></feed>").isEmpty())
        assertTrue(Updater.parseAtomTags("pas du xml").isEmpty())
    }

    @Test
    fun `redirection - tag extrait de l'URL finale`() {
        assertEquals(
            "v0.2.41",
            Updater.tagFromRedirect(
                "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/tag/v0.2.41"
            )
        )
        assertNull(Updater.tagFromRedirect("https://github.com/x/nyx-messenger/releases"))
    }

    /** Extrait réel du fragment expanded_assets (vérifié en production 2026-10-23). */
    private val assetsHtml = """
        <include-fragment>
          <div class="Box Box--condensed">
            <a href="/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/nyx-v0.2.41-20261023-001655-release.apk" rel="nofollow">…</a>
            <a href="/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/nyx-v0.2.41-20261023-001655-test.apk" rel="nofollow">…</a>
            <a href="/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/SHA256SUMS-v0.2.41.txt" rel="nofollow">…</a>
          </div>
        </include-fragment>
    """.trimIndent()

    @Test
    fun `assets - URL absolue du canal test extraite`() {
        assertEquals(
            "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/nyx-v0.2.41-20261023-001655-test.apk",
            Updater.assetUrlFromExpanded(assetsHtml, "-test.apk")
        )
    }

    @Test
    fun `assets - URL absolue du canal standard et SHA256SUMS extraits`() {
        assertEquals(
            "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/nyx-v0.2.41-20261023-001655-release.apk",
            Updater.assetUrlFromExpanded(assetsHtml, "-release.apk")
        )
        assertEquals(
            "https://github.com/alexisfrlopez-debug/nyx-messenger/releases/download/v0.2.41/SHA256SUMS-v0.2.41.txt",
            Updater.assetUrlFromExpanded(assetsHtml, "SHA256SUMS")
        )
    }

    @Test
    fun `assets - suffixes disjoints, pas de croisement`() {
        assertNull(Updater.assetUrlFromExpanded(assetsHtml, "-debug.apk"))
        assertNull(Updater.assetUrlFromExpanded("<div></div>", "-test.apk"))
    }

    @Test
    fun `sha256sums - entrée correspondante trouvée, formats GNU acceptés`() {
        val sums = """
            1111111111111111111111111111111111111111111111111111111111111111  nyx-v0.2.41-20261023-001655-test.apk
            2222222222222222222222222222222222222222222222222222222222222222 *nyx-v0.2.41-20261023-001655-release.apk
        """.trimIndent()
        assertEquals(
            "1111111111111111111111111111111111111111111111111111111111111111",
            Updater.parseSha256Sums(sums, "nyx-v0.2.41-20261023-001655-test.apk")
        )
        // l'astérisque binaire (format GNU) est toléré
        assertEquals(
            "2222222222222222222222222222222222222222222222222222222222222222",
            Updater.parseSha256Sums(sums, "nyx-v0.2.41-20261023-001655-release.apk")
        )
        assertNull(Updater.parseSha256Sums(sums, "inconnu.apk"))
    }
}
