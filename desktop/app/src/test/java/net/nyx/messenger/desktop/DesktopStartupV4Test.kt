package net.nyx.messenger.desktop

import kotlinx.coroutines.runBlocking
import net.nyx.messenger.repo.DepositRouting
import net.nyx.messenger.ui.showRawProbeError
import net.nyx.messenger.ui.startupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * v0.3.4 (session 74 — retour terrain « tor ne se connecte pas, ni les
 * replicas » + demande « barre de progression au lancement comme Android ») :
 *
 *  1. COURSE DU PORT DE CONTRÔLE (preuve : logs terrain 27/10 15:28 —
 *     ConnectException à +62/+17/+14/+16 ms du lancement, tor.log montrant
 *     un bootstrap PARFAITEMENT vivant ~14 s plus tard) :
 *     - purge du cookie périmé (le fichier persiste, tor ne l'efface jamais) ;
 *     - attente sur un connect TCP RÉELLEMENT accepté (isPortOpen) ;
 *  2. BOÎTES DES CONTACTS comme amorçage de réplicas (fait architectural :
 *     l'onion d'identité d'un appareil EST sa boîte, port 80) ;
 *  3. PROGRESSION DE DÉMARRAGE — miroir exact de la formule Android
 *     (ConnexionsScreen : Tor = moitié gauche, réplica = moitié droite) ;
 *  4. RENDEZVOUS SIGNÉ : la signature embarquée v0.2.23 ne vérifie PAS contre
 *     la pubkey de release compilée (constaté en CLI Python session 74 :
 *     RFC 8032 + 11 variantes de canonicalisation testées) — verrouillé par
 *     test pour éviter un retour en arrière silencieux. Si la liste est
 *     re-signée un jour, METTRE À JOUR ce test (et le diagnostic LogCollector).
 */
class DesktopStartupV4Test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 1. Course du port de contrôle ----

    @Test
    fun `isPortOpen - port refuse = faux, listener ouvert = vrai`() {
        // Port garanti fermé : on en réserve un puis on le libère.
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(DesktopTor.isPortOpen(closed))
        // Port ouvert : un listener local l'accepte.
        ServerSocket(0).use { s ->
            assertTrue(DesktopTor.isPortOpen(s.localPort))
        }
    }

    @Test
    fun `cookie perime - purge avant lancement (tor ne l efface jamais)`() {
        System.setProperty("nyx.data.dir", tmp.newFolder().absolutePath)
        DesktopPaths.ensure()
        val cookie = File(DesktopPaths.torDir, "data/control.authcookie")
        cookie.parentFile.mkdirs()
        cookie.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(cookie.isFile)
        DesktopTor(null).purgeStaleCookie()
        assertFalse("le cookie de la session précédente doit être supprimé avant de lancer tor",
            cookie.isFile)
        // Idempotent : re-purge sans fichier = silencieux.
        DesktopTor(null).purgeStaleCookie()
    }

    // ---- 2. Boîtes des contacts ----

    @Test
    fun `contactBox - trois formats terrain normalises en onion-80`() {
        val base = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd"
        // nu 56 caractères (carnet Android), .onion, .onion:80 → même boîte.
        assertEquals("$base.onion:80", ServiceLocator.contactBox(base))
        assertEquals("$base.onion:80", ServiceLocator.contactBox("$base.onion"))
        assertEquals("$base.onion:80", ServiceLocator.contactBox("$base.onion:80"))
        assertEquals("$base.onion:80", ServiceLocator.contactBox("$base.onion:443"))
    }

    @Test
    fun `contactBox - garbage rejete`() {
        assertNull(ServiceLocator.contactBox(""))
        assertNull(ServiceLocator.contactBox("pas-un-onion"))
        assertNull(ServiceLocator.contactBox("5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqdXX.onion"))
        assertNull(ServiceLocator.contactBox("commencont-par-la.onion"))
    }

    @Test
    fun `boite du contact - cible de depot valide, jamais source de lecture`() = runBlocking {
        // Intégration Fix v0.3.4 × invariants RepoRouting : l'onion d'un
        // contact entre dans la liste fusionnée → c'est une CIBLE de dépôt
        // (sa boîte, lue en procédé chez lui) et PAS une source de poll.
        val contact = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"
        val box = ServiceLocator.contactBox(contact)!!
        val replicas = listOf(box)
        val isPeerPhone: suspend (String) -> Boolean = { addr ->
            addr.substringBeforeLast(":") == contact
        }
        // envoi AU contact → sa boîte (dépôt direct)
        assertEquals(box, DepositRouting.depositTarget(contact, replicas, isPeerPhone))
        // poll → JAMAIS la boîte d'un pair (incident Android 24/10)
        assertTrue(DepositRouting.pollTargets(replicas, isPeerPhone).isEmpty())
    }

    // ---- 3. Progression de démarrage (miroir ConnexionsScreen Android) ----

    @Test
    fun `progression - tor seul au demarrage`() {
        // 0 % au tout début, 40 % à 80 % de bootstrap (percent/2 borné 49).
        assertEquals(0f, startupProgress(ServiceLocator.TorPhase.STARTING, 0, null, false))
        assertEquals(0.4f, startupProgress(ServiceLocator.TorPhase.STARTING, 80, null, true))
        assertEquals(0.49f, startupProgress(ServiceLocator.TorPhase.STARTING, 100, null, true))
    }

    @Test
    fun `progression - tor pret puis sonde reussie`() {
        // Tor prêt sans sonde : +10 ; sonde OK : complet.
        assertEquals(0.6f, startupProgress(ServiceLocator.TorPhase.READY, 100, null, true))
        assertEquals(1f, startupProgress(ServiceLocator.TorPhase.READY, 100, true, true))
    }

    @Test
    fun `progression - tor pret sans aucun replica connu`() {
        // Aucune sonde possible : la moitié droite reste à 0 (l'écran avance
        // de lui-même — contrairement à l'Android qui bloque sur le gate).
        assertEquals(0.5f, startupProgress(ServiceLocator.TorPhase.READY, 100, null, false))
        // Sonde ratée : reste à 10 sur la moitié droite, jamais vert.
        assertEquals(0.6f, startupProgress(ServiceLocator.TorPhase.READY, 100, false, true))
    }

    @Test
    fun `progression - echec tor = barre a zero`() {
        // Échec/arrêt : jamais de sonde possible (elle ne tourne que Tor prêt).
        assertEquals(0f, startupProgress(ServiceLocator.TorPhase.FAILED, null, null, true))
        assertEquals(0f, startupProgress(ServiceLocator.TorPhase.OFF, null, null, true))
        // Miroir Android : une sonde OK valide seule la moitié droite — la
        // formule ne la retire pas si l'état Tor régresse ensuite (impossible
        // en pratique : la sonde ne tourne que Tor prêt).
        assertEquals(0.5f, startupProgress(ServiceLocator.TorPhase.OFF, null, true, true))
    }

    // ---- 3bis. Grâce circuit froid (v0.3.5 — preuve export terrain v0.3.4) ----

    @Test
    fun `circuit froid - erreur brute masquee pendant la premiere minute et demie`() {
        // L'export terrain v0.3.4 : premier /health OK 74 s APRÈS le backend
        // PRÊT (circuit Tor froid vers l'onion : descripteur + circuit ≈ 1
        // min) — pendant ce délai l'écran EXPLIQUE l'échauffement au lieu de
        // cracher « SocketTimeoutException » en rouge.
        assertFalse(showRawProbeError(0L, false))
        assertFalse(showRawProbeError(30L, false))
        assertFalse(showRawProbeError(89L, false))
        assertTrue(showRawProbeError(90L, false))
        assertTrue(showRawProbeError(300L, false))
        // Sonde OK (ou inconnue) : jamais d'erreur brute à montrer.
        assertFalse(showRawProbeError(300L, true))
        assertFalse(showRawProbeError(0L, null))
        assertTrue(showRawProbeError(300L, null))
    }

    // ---- 4. Rendezvous signé (RÉSOLU session 75 : la base64 n'était jamais décodée) ----

    @Test
    fun `rendezvous embarque - signature actuelle VERIFIEE (resolution session 75)`() {
        val raw = javaClass.getResourceAsStream("/rendezvous.json")!!.readBytes()
        val sig = javaClass.getResourceAsStream("/rendezvous.json.sig")!!.readBytes()
        val verified = net.nyx.messenger.net.RendezvousList.load(
            raw, sig, ServiceLocator.RENDEZVOUS_PUBKEY
        )
        // v0.3.5 — LA CAUSE RACINE DU « rendezvous signé INVALIDE » (terrain
        // v0.3.3/v0.3.4 ET production Android depuis v0.2.23) : le fichier
        // .sig est du TEXTE base64 (88 caractères + fin de ligne = 89 octets)
        // et les clients le passaient OCTETS BRUTS à verify() qui exigeait
        // 64 octets → rejet INSTANTANÉ d'une signature PARFAITEMENT VALIDE
        // (preuve session 75 : OpenSSL 3.5.7 + libsodium/PyNaCl +
        // ed25519lib valident la signature embarquée sous la clé de release
        // M//9JU3… ; l'outil scripts/gen_rendezvous.py verify dit VALIDE).
        // Le verdict « clé inconnue » de la session 74 était un artefact de
        // la bibliothèque cryptography du bac à sable (cassée : elle échoue
        // son propre aller-retour sign→verify). Correction : RendezvousList
        // accepte désormais le .sig tel que lu (base64) OU décodé (64 o).
        assertNotNull("la signature embarquée DOIT être acceptée (base64 décodée)", verified)
        assertEquals(1, verified!!.entries.size)
        assertEquals(
            "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80",
            verified.entries[0].onion
        )
    }

    @Test
    fun `rendezvous embarque - fichier sig altere reste rejete`() {
        // La TOLÉRANCE base64 ne doit JAMAIS accepter une signature altérée :
        // 1 caractère changé dans le texte base64 → rejet en bloc.
        val raw = javaClass.getResourceAsStream("/rendezvous.json")!!.readBytes()
        val sig = javaClass.getResourceAsStream("/rendezvous.json.sig")!!.readBytes()
            .clone()
        for (i in sig.indices) {
            if (sig[i] == 'T'.code.toByte()) { sig[i] = 'U'.code.toByte(); break }
        }
        assertNull(net.nyx.messenger.net.RendezvousList.load(raw, sig, ServiceLocator.RENDEZVOUS_PUBKEY))
    }

    @Test
    fun `rendezvous embarque - le mecanisme de verification reste operationnel`() {
        // Preuve que CE N'EST PAS le vérificateur qui est cassé : une liste
        // correctement signée par la clé de test passe (vecteur du dépôt).
        val pub = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        val json = """{"format":"nyx-rendezvous","version":1,"generatedAt":1760000000,"replicas":[{"onion":"5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion:80","note":"test"}]}"""
        val sigB64 = "GhfxTjyefPSkGbY27uaVwS7xHnMu+n5tubTNhQuBDtq0Cb/AD0dp++Ne2hldUDjpuR2TncUxk2YWiOHcUy1eDg=="
        val verified = net.nyx.messenger.net.RendezvousList.load(
            json.toByteArray(), java.util.Base64.getDecoder().decode(sigB64), pub
        )
        assertEquals(1, verified!!.entries.size)
    }
}
