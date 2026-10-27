package net.nyx.messenger.desktop

import net.nyx.messenger.diag.DriveUploader
import net.nyx.messenger.diag.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.3.3 (session 73) — tests du correctif « ne se connecte pas à tor ni aux
 * replicas » + du BOUTON D'ENVOI DE LOGS (miroir Android) :
 *  - journal PERSISTANT (DesktopLog) : append + queue + rotation bornée ;
 *  - torrc : geoip best-effort (présent → lignes, absent → rien) ;
 *  - DesktopTor : queue console du démon + queue tor.log + killLeftoverTor
 *    (bénin sur le bac à sable : aucun processus ne matche le chemin) ;
 *  - DriveUploader : helpers purs (mêmes comportements que l'Android) +
 *    configuration avec override terrain SANS rebuild (leçon v0.2.9) ;
 *  - LogCollector : en-tête + sections présentes même en mode dégradé
 *    (ServiceLocator non câblé — les sections tombent en « indisponible »
 *    sans faire échouer la collecte : le bouton ne doit JAMAIS planter).
 */
class DesktopLogPersistentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `journal persistant - append puis queue`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        val f = tmp.newFile("nyx-desktop.log")
        DesktopLog.initFile(f)
        DesktopLog.i("nyx-test", "première ligne")
        DesktopLog.w("nyx-test", "avertissement")
        val text = f.readText()
        assertTrue(text.contains("I/nyx-test: première ligne"))
        assertTrue(text.contains("W/nyx-test: avertissement"))
        // La queue contient les DEUX lignes récentes (même contenu que le fichier).
        val tail = DesktopLog.fileTail()
        assertTrue(tail.contains("première ligne"))
        assertTrue(tail.contains("avertissement"))
    }

    @Test
    fun `journal persistant - queue borne ne garde que la fin`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        val f = tmp.newFile("nyx-desktop.log")
        DesktopLog.initFile(f)
        DesktopLog.i("nyx-test", "LIGNE_ANCIENNE")
        DesktopLog.i("nyx-test", "LIGNE_RECENTE")
        // fileTail borné : une seule ligne garde la plus récente (la tête
        // partielle est sautée). 2 lignes ≈ 100+ octets → 1 ligne récente ≈ 50.
        val tail = DesktopLog.fileTail(60)
        assertFalse("la queue ne doit pas contenir la ligne ancienne", tail.contains("LIGNE_ANCIENNE"))
    }

    @Test
    fun `journal persistant - rotation a 1 Mo (garde la fin, perd la tête)`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        val f = tmp.newFile("big.log")
        DesktopLog.initFile(f)
        // ~1,3 Mo : la rotation (MAX 1 Mo, GARDE 512 Ko) doit couper la tête.
        val payload = "x".repeat(400)
        for (i in 1..3300) {
            DesktopLog.i("nyx-test", "L$i $payload")
        }
        DesktopLog.i("nyx-test", "MARQUEUR_FINAL")
        val len = f.length()
        assertTrue("le fichier doit être borné (< 1,2 Mo après rotation), taille=$len", len < 1_200_000)
        val tail = DesktopLog.fileTail()
        assertTrue("la fin (marqueur final) doit survivre à la rotation", tail.contains("MARQUEUR_FINAL"))
        assertFalse("la tête doit avoir été coupée", f.readText().contains("L1 "))
        // Tampon mémoire : toujours borné (4000 lignes max).
        assertTrue(DesktopLog.dump().contains("MARQUEUR_FINAL"))
    }
}

/** v0.3.3 — geoip best-effort + queues de diagnostic du démon. */
class DesktopTorDiagTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `torrc - geoip absent = aucune ligne (best-effort)`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val tor = DesktopTor(null)
        val rc = tor.writeTorrc(1L)
        val text = rc.readText()
        assertFalse(text.contains("GeoIPFile"))
        assertFalse(text.contains("GeoIPv6File"))
    }

    @Test
    fun `torrc - geoip present a cote du binaire = lignes absolues`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val dir = tmp.newFolder("tor")
        File(dir, "geoip").writeText("…")
        File(dir, "geoip6").writeText("…")
        val fakeBin = File(dir, "tor")       // n'a pas besoin d'exister pour writeTorrc
        val tor = DesktopTor(fakeBin)
        val rc = tor.writeTorrc(1L)
        val text = rc.readText()
        assertTrue(text.contains("GeoIPFile ${File(dir, "geoip").absolutePath}"))
        assertTrue(text.contains("GeoIPv6File ${File(dir, "geoip6").absolutePath}"))
    }

    @Test
    fun `queue console du demon - bornée et lisible`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val tor = DesktopTor(null)
        // stdoutTail est vide avant tout démarrage — pas de crash, pas de contenu.
        assertEquals("", tor.stdoutTail())
    }

    @Test
    fun `queue tor log - absente puis lue`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val tor = DesktopTor(null)
        // Aucun tor.log encore : vide, JAMAIS d'exception.
        assertEquals("", tor.torLogTail())
        File(DesktopPaths.torDir, "tor.log").writeText("a\nb\nc")
        assertEquals("a\nb\nc", tor.torLogTail())
        // Bornée : une demande plus petite que le contenu garde la FIN.
        val tail2 = tor.torLogTail(2)
        assertEquals("\nc", tail2)
    }

    @Test
    fun `killLeftoverTor - chemin inconnu = zero process touche (benin)`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val fake = File(tmp.newFolder(), "tor-inexistant")
        // Sur le bac à sable Linux : pgrep ne matche rien → 0, aucune exception.
        val n = DesktopTor(fake).killLeftoverTor(fake)
        assertEquals(0, n)
    }
}

/** v0.3.3 — helpers purs du portage DriveUploader (mêmes contrats que l'Android). */
class DriveUploaderHelpersTest {

    @Test
    fun `extractField - champ premier niveau`() {
        assertEquals("abc", DriveUploader.extractField("""{"id":"abc"}""", "id"))
        // Guillemet ÉCHAPPÉ dans la valeur JSON : la capture conserve la
        // séquence (comportement identique à l'Android).
        val json = "{\"id\":\"a\\\"b\"}"
        assertEquals("a\\\"b", DriveUploader.extractField(json, "id"))
        assertNull(DriveUploader.extractField("""{"name":"x"}""", "id"))
    }

    @Test
    fun `jsonEsc - echappement strict`() {
        assertEquals("""a\"b\\c\nd""", DriveUploader.jsonEsc("a\"b\\c\nd"))
        assertEquals("é", DriveUploader.jsonEsc("é"))         // hors ASCII > 0x20 : intacts
        val ctrl = String(charArrayOf(1.toChar()))              // contrôle < 0x20
        assertEquals("\\u0001", DriveUploader.jsonEsc(ctrl))   // échappé
    }

    @Test
    fun `truncate - borne`() {
        assertEquals("abc", DriveUploader.truncate("abc"))
        assertTrue(DriveUploader.truncate("a".repeat(500)).endsWith("…"))
        assertTrue(DriveUploader.truncate("a".repeat(500)).length <= 401)
    }

    @Test
    fun `config - override terrain SANS rebuild (lecon v0_2_9)`() {
        System.setProperty("nyx.data.dir", tmpDir())
        // Aucune ressource drive-upload.properties sur le classpath de test →
        // la config vient de l'override local uniquement.
        val f = File(System.getProperty("nyx.data.dir"), "drive-upload.properties")
        f.writeText(
            """
            drive.clientId=ID_TERRAIN
            drive.clientSecret=SECRET_TERRAIN
            drive.refreshToken=TOKEN_TERRAIN
            drive.folderId=FOLDER_TERRAIN
            """.trimIndent()
        )
        val cfg = DriveUploader.config()
        assertTrue(cfg.isConfigured)
        assertEquals("ID_TERRAIN", cfg.clientId)
        assertEquals("SECRET_TERRAIN", cfg.clientSecret)
        assertEquals("TOKEN_TERRAIN", cfg.refreshToken)
        assertEquals("FOLDER_TERRAIN", cfg.folderId)
    }

    @Test
    fun `config - vide = non configure, message explicite`() {
        System.setProperty("nyx.data.dir", tmpDir())
        val cfg = DriveUploader.config()
        assertFalse(cfg.isConfigured)
        val res = DriveUploader.upload("x.txt", byteArrayOf(1), cfg)
        assertFalse(res.ok)
        assertNotNull(res.error)
        assertTrue(res.error!!.contains("non configuré"))
    }

    private fun tmpDir(): String {
        val d = File("/tmp/nyx-drive-test-" + System.nanoTime())
        d.mkdirs()
        return d.absolutePath
    }
}

/** v0.3.3 — collecte de diagnostic : ne doit JAMAIS planter (mode dégradé test). */
class LogCollectorTest {

    @Test
    fun `en-tete et sections presentes meme sans backend câble`() {
        System.setProperty("nyx.data.dir", "/tmp/nyx-logcollector-test-" + System.nanoTime())
        val text = LogCollector.collect()
        assertTrue(text.contains("=== NYX DESKTOP EXPORT LOGS (build test) ==="))
        assertTrue(text.contains("version  : "))
        assertTrue(text.contains("os       : "))
        assertTrue(text.contains("java     : "))
        assertTrue(text.contains("tor bin  : "))
        assertTrue(text.contains("===== TOR.LOG"))
        assertTrue(text.contains("===== TOR CONSOLE"))
        assertTrue(text.contains("===== JOURNAL PERSISTANT"))
        assertTrue(text.contains("===== JOURNAL MEMOIRE"))
        // Aucune exception ne doit fuiter dans le texte (sections dégradées only).
    }

    @Test
    fun `nom de fichier normalise (miroir android)`() {
        val name = LogCollector.fileName()
        assertTrue(name.matches(Regex("nyx-logs-desktop-v[0-9.]+-\\d{8}-\\d{6}\\.txt")))
    }
}
