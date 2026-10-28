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
     * v0.3.7 — items de validation (miroir de docs/releases/v0.3.7-desktop.md) :
     * VISIO MJPEG bureau↔téléphone (téléphone ≥ v0.2.55 — l'écran du PC est
     * partagé, la caméra du téléphone s'affiche) + durcissement des accusés
     * (dérive d'id au resync + double émission en course).
     */
    /**
     * v0.3.9 — items de validation (miroir de docs/releases/v0.3.9-desktop.md) :
     * CARTE DÉDIÉE Décrocher/Refuser + micro qui ne tue plus l'appel + visio
     * qui tient (crash caméra corrigé côté téléphone v0.2.57).
     */
    val ITEMS_V0_3_9 = listOf(
        Item(
            "carte-decrocher-refuser",
            "CARTE D'APPEL ENTRANTE AVEC ICÔNES DÉCROCHER/REFUSER — ITEM DÉCISIF n°1 (NOUVEAU v0.3.9, demande opérateur session 79 verbatim : « appels tel => pc : je veux une notification avec une icone pour décrocher et une pour refuser l'appel » — terrain 28/10 10:47 : appel tel→PC sonné 43 s sans décrochage, l'opérateur n'a pas trouvé l'écran d'acceptation et a RELANCÉ l'appli) : appel depuis le téléphone (fenêtre Nyx réduite, cachée, derrière d'autres fenêtres OU app verrouillée) → une CARTE DÉDIÉE sans barre de titre s'ouvre SEULE au CENTRE de l'écran, TOUJOURS AU-DESSUS, avec l'alias + DEUX icônes géantes ✓ Décrocher (vert) / ✕ Refuser (rouge) ; ✓ → l'appel s'établit et la fenêtre principale prend le relais ; ✕ (ou Alt+F4) → l'appel est refusé ; la carte se ferme seule dès que la sonnerie s'arrête ; l'info-bulle du plateau annonce « Décrocher (✓) ou Refuser (✕) »"
        ),
        Item(
            "micro-ne-tue-plus",
            "UN MICRO PERDU NE TUE PLUS L'APPEL (NOUVEAU v0.3.9, preuve export v0.3.8 28/10 11:46 : « micro : Une connexion établie a été abandonnée par un logiciel de votre ordinateur hôte » TERMINAIT un appel sain après 11 s) : pendant un appel, débrancher/changer le périphérique micro → le journal montre « micro perdu en cours d'appel : … — émission coupée, réception maintenue » et L'APPEL CONTINUE (le pair reste audible) jusqu'au raccroché ; idem haut-parleurs (« réception muette, appel maintenu »)"
        ),
        Item(
            "visio-une-minute",
            "VISIO QUI TIENT (v0.3.8 continue, preuve 28/10 11:49 : « visio MJPEG ACTIVE » des deux côtés PUIS crash téléphone 0,6 s après — corrigé v0.2.57 côté téléphone : caméra protégée + activité verrouillée) : appel visio depuis OU vers un téléphone ≥ v0.2.57 → l'appel RESTE ACTIF plus d'une minute, écran partagé + caméra du téléphone coulent ; si la caméra du téléphone est fermée par le système l'appel Y CROIT en audio (journal honnête), jamais de crash"
        ),
    )

    /**
     * v0.3.8 — items de validation (miroir de docs/releases/v0.3.8-desktop.md) :
     * SONNERIE bureau + décrochage verrouillé + visio qui tient au décroché +
     * latence audio réduite.
     */
    val ITEMS_V0_3_8 = listOf(
        Item(
            "sonnerie-bureau",
            "SONNERIE AUDIBLE AU BUREAU (NOUVEAU v0.3.8, session 78 : le bureau était MUET — l'appel visio entrant du 28/10 07:17 n'a jamais été remarqué, 0 acceptation en 54 s) : appel depuis le téléphone (fenêtre Nyx réduite ou cachée dans le plateau) → la SONNERIE bi-ton se fait ENTENDRE + la fenêtre se ramène au premier plan TOUJOURS AU-DESSUS ; journal « sonnerie bureau DÉMARRÉE » ; au décroché/refus « sonnerie bureau STOPPÉE »"
        ),
        Item(
            "decroche-app-verrouillee",
            "DÉCROCHER/REFUSER APP VERROUILLÉE (NOUVEAU v0.3.8, session 78 : « sans devoir tout déverrouiller (tel/ordi, application ...) ») : mot de passe app posé → appel entrant → l'OVERLAY d'appel (Accepter/Refuser) apparaît AU-DESSUS de l'écran de verrou ; décrocher → l'appel s'établit SANS saisir le mot de passe (alias seul visible, jamais de contenu de message) ; NB honnête : l'écran de verrouillage WINDOWS lui-même ne peut pas afficher d'UI tierce (restriction OS) — la sonnerie est le signal, la fenêtre est là au déverrouillage"
        ),
        Item(
            "visio-tient-decroche",
            "VISIO PC↔TÉLÉPHONE QUI TIENT AU DÉCROCHÉ (NOUVEAU v0.3.8, preuve 5ᵉ export : le téléphone acceptait mais la trame de rotation qui précédait l'ACCEPT était lue comme « pas de réponse » → BYE silencieux) : appel visio depuis OU vers un téléphone ≥ v0.2.56 → au décroché l'appel RESTE ACTIF (« appel actif avec … (codec adpcm, visio mjpeg) » — plus d'effondrement muet, toute fin est journalisée « appel terminé »)"
        ),
        Item(
            "latence-audio",
            "LATENCE AUDIO RÉDUITE (NOUVEAU v0.3.8, session 78 : « les appels audio tel-pc ont un delay trop long ») : appel audio avec le téléphone → réponse quasi immédiate (tampon 128 ms au lieu de 256 ms) ; après une rafale Tor le retard est RATTRAPÉ (« anti-arriéré : N bloc(s) sauté(s) pour rejoindre le direct »)"
        ),
    )

    val ITEMS_V0_3_7 = listOf(
        Item(
            "visio-pc-vers-tel",
            "Visio PC→téléphone (téléphone ≥ v0.2.55) : bouton 📹 → sonnerie → décroché → l'écran du PC est PARTAGÉ (bouton « 📹 Écran partagé ») et la caméra du téléphone s'affiche dans la fenêtre (~5 trames/s, latence normale)"
        ),
        Item(
            "visio-tel-vers-pc",
            "Visio téléphone→PC : bouton visio du téléphone → la fenêtre Nyx sonne « visio » → Accepter → écran partagé + caméra du téléphone dans les DEUX sens (journal « visio MJPEG ACTIVE — interop bureau » côté téléphone)"
        ),
        Item(
            "visio-pause",
            "En visio : bouton 📹 → « Partage en pause » (plus aucune trame ne part, la caméra du pair CONTINUE) → re-clic → reprise"
        ),
        Item(
            "visio-ancien-tel",
            "Visio avec un téléphone ≤ v0.2.54 : l'appel passe en AUDIO avec le message honnête dans les journaux (« vc absent — visio indisponible ») — jamais d'appel raté"
        ),
        Item(
            "accuses-unique",
            "Accusés UNIQUES : dans l'export de logs, « ✓ accusé « read » déposé » n'apparaît PLUS en double à 1 ms d'intervalle (verrou anti-course v0.3.7)"
        ),
        Item(
            "accuses-id-realigne",
            "Message ayant subi un resync (log « id local re-aligné sur le dépôt resync ») → le pair reçoit l'accusé pour le BON id → ✓✓ s'affiche"
        ),
    )

    /**
     * v0.3.6 — items (accusés corrigés + appels audio interop téléphone ≥ v0.2.54).
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

    fun items(): List<Item> = ITEMS_V0_3_9 + ITEMS_V0_3_8 + ITEMS_V0_3_7 + ITEMS_V0_3_6 + ITEMS_V0_3_5

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
