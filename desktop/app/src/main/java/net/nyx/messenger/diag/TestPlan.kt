package net.nyx.messenger.diag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.nyx.messenger.desktop.DesktopLog
import java.io.File

/**
 * v0.3.4 (session 74 — « parité Android » : l'opérateur veut EXACTEMENT les
 * mêmes fonctionnalités/interfaces et boutons que la version Android) —
 * PLAN DE TESTS DE VALIDATION DESKTOP, portage miroir de `diag/TestPlan.kt`
 * Android (v0.2.20) :
 *
 *  - la liste est DÉFINIE PAR RELEASE (les items « À tester » des notes de
 *    version) — affichée sur la liste des conversations du build test
 *    (carte repliable `ui/TestPlanCard.kt`, miroir Android) ;
 *  - un clic marque le test FAIT (horodaté dans `testplan.json`, répertoire
 *    de données) et journalise « nyx-test PASS <id> : <label> » — la ligne
 *    part dans l'export de logs (le journal persistant alimente LogCollector)
 *    → l'agent/l'opérateur voit les résultats directement dans les journaux
 *    téléversés via le bouton rouge ;
 *  - « Tout réinitialiser » remet la liste à zéro (journalisé WARN) ;
 *  - la mise en production d'une version test = TOUS les items PASS dans les
 *    journaux + validation opérateur.
 *
 * Différence Android assumée : pas de champ `requiresRedundancy` bloquant —
 * aucun item desktop v0.3.4 n'est destructeur pour la flotte (les tests
 * « cycle sans serveur » resteront des validations Android).
 */
object TestPlan {

    /** Un test de validation de la release courante. */
    data class Item(val id: String, val label: String)

    /**
     * v0.3.6 — items de validation (miroir de docs/releases/v0.3.6-desktop.md) :
     * accusés de réception Corrigés à la racine (read-qui-ne-partait-jamais +
     * retry + guérison du backlog) et APPELS AUDIO bureau↔téléphone (ADPCM
     * négocié, téléphone ≥ v0.2.54 requis).
     */
    val ITEMS_V0_3_6 = listOf(
        Item(
            "accuses-read",
            "Accusés « lu » : ouvrir une conversation reçue → le TÉLÉPHONE affiche ✓✓ Lu sous ses bulles (l'accusé part enfin — filtre rcptDelivered impossible avant v0.3.6)"
        ),
        Item(
            "accuses-retry",
            "Accusés « reçu/lu » avec réseau instable : un échec de dépôt est DIFFÉRÉ (log « ✗ accusé … DIFFÉRÉ ») puis REPARTI au cycle suivant — plus jamais perdu"
        ),
        Item(
            "accuses-backlog",
            "Migration au démarrage : « migration accusés : N message(s) entrant(s) jamais acquitté(s) » au premier lancement → le téléphone voit passer ses ✓✓ d'avant-v0.3.6"
        ),
        Item(
            "appel-pc-vers-tel",
            "Appel PC→téléphone (téléphone ≥ v0.2.54) : bouton 📞 → sonnerie « appel sortant… » → décroché côté téléphone → les DEUX sens s'entendent (latence 0,5-3 s normale)"
        ),
        Item(
            "appel-tel-vers-pc",
            "Appel téléphone→PC : la fenêtre Nyx sonne (Accepter/Refuser) → Accepter → conversation audio établie — codec ADPCM négocié dans les journaux (« codec ADPCM négocié » côté téléphone)"
        ),
        Item(
            "appel-ancien-tel",
            "Appel avec un téléphone ≤ v0.2.53 : message explicite « l'appli du téléphone doit être mise à jour (v0.2.54+) » — jamais d'audio muet/déchets"
        ),
        Item(
            "appel-mute-raccroche",
            "En appel : bouton Micro (mute — le pair n'entend plus rien) + Raccrocher des deux côtés → fin propre (« terminé »)"
        ),
    )

    /**
     * v0.3.5 — items conservés (connexion, progression, parité Android,
     * rendezvous signé, circuit froid).
     */
    val ITEMS_V0_3_5 = listOf(
        Item(
            "rendezvous-signe",
            "Rendezvous signé : l'export de logs affiche « rendezvous signé VÉRIFIÉ : 1 entrée(s) embarquée(s) » — plus AUCUNE ligne « INVALIDE » (même après 10 min : vérifiée une seule fois)"
        ),
        Item(
            "circuit-froid",
            "Premier lancement : pendant ~1 min l'étape réplicas affiche « Premier circuit Tor en construction… c'est normal » (PAS d'erreur rouge), puis passe au vert et l'app s'ouvre"
        ),
        Item(
            "tor-premiere-connexion",
            "Connexion Tor : l'application se connecte AU PREMIER lancement (plus d'échec instantané « control port : écriture impossible »)"
        ),
        Item(
            "progression-demarrage",
            "Écran de connexion au démarrage : étape 1 Tor (pourcentage vivant) puis étape 2 réplicas — ouverture automatique quand tout est vert"
        ),
        Item(
            "reessayer",
            "Bouton « Réessayer » : après un échec simulé (réseau coupé pendant le lancement), le clic relance Tor et aboutit"
        ),
        Item(
            "vocaux",
            "Messages vocaux : bouton 🎤 enregistre, envoi, lecture côté Android ET Windows (les deux sens)"
        ),
        Item(
            "medias",
            "Pièces jointes : image/vidéo/fichier envoyés depuis Windows — visionneuse plein écran + « Enregistrer sous »"
        ),
        Item(
            "vue-limitee",
            "Média à affichage limité (vue unique/double) : destruction après consultation côté Windows, miroir Android"
        ),
        Item(
            "suppression-messages",
            "Suppression d'un message « pour moi » / « pour tous » (miroir Android v0.2.19)"
        ),
        Item(
            "mots-de-passe",
            "Mots de passe normal/panique/destruction : création au premier lancement, verrouillage, session LEURRE panique"
        ),
        Item(
            "plateau",
            "Réduction au plateau système : Tor actif + notifications vivantes ; clic sur l'icône → la fenêtre remonte"
        ),
        Item(
            "export-conversations",
            "Export/import des conversations chiffrées (Paramètres) — fichier relisible sur téléphone ou PC"
        ),
        Item(
            "regressions-msg",
            "Régressions : messages texte + accusés ✓/✓✓/Lu + invitations Accepter/Refuser inchangés"
        ),
        Item(
            "regressions-replica",
            "Régressions (test 2) : réplica temporaire embarqué EN LIGNE + purge manuelle depuis les Paramètres"
        ),
    )

    fun items(): List<Item> = ITEMS_V0_3_6 + ITEMS_V0_3_5

    /** Marques horodatées persistées (id → epoch ms ; fichier testplan.json). */
    @Serializable
    private data class Marks(val done: Map<String, Long> = emptyMap())

    @Volatile private var marks: Marks = Marks()
    @Volatile private var file: File? = null

    /** À appeler au démarrage (ServiceLocator.init) avec le répertoire de données. */
    fun init(dataDir: File) {
        file = File(dataDir, "testplan.json")
        marks = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(Marks.serializer(), file!!.readText())
        }.getOrDefault(Marks())
    }

    fun doneAt(id: String): Long = marks.done[id] ?: 0L

    fun doneCount(): Int = items().count { doneAt(it.id) > 0L }

    /** Marque/dé-marque un item + journalise (l'export de logs emporte la ligne). */
    fun setDone(id: String, done: Boolean) {
        marks = if (done) marks.copy(done = marks.done + (id to System.currentTimeMillis()))
        else marks.copy(done = marks.done - id)
        persist()
        val label = items().firstOrNull { it.id == id }?.label ?: id
        if (done) DesktopLog.i("nyx-test", "PASS $id : $label")
        else DesktopLog.w("nyx-test", "RESET $id : $label (remis « à faire »)")
    }

    fun reset() {
        marks = Marks()
        persist()
        DesktopLog.w("nyx-test", "plan de tests RÉINITIALISÉ (${items().size} items à refaire)")
    }

    /** Résumé journalisé quand TOUS les items sont faits (validation prod). */
    fun logSummary() {
        DesktopLog.i(
            "nyx-test",
            "PLAN COMPLET : ${items().size}/${items().size} tests faits — validation production possible"
        )
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(Json.encodeToString(Marks.serializer(), marks))
        }.onFailure { DesktopLog.w("nyx-test", "persistance du plan impossible (${it.message})") }
    }
}
