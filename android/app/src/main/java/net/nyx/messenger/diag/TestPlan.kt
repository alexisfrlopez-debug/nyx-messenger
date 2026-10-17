package net.nyx.messenger.diag

/**
 * v0.2.20 — PLAN DE TESTS DE VALIDATION (demande opérateur : « pour les
 * versions test, je veux qu'elle affiche un listing des tests à effectuer,
 * listing qui se met à jour quand le test en question est fait. les résultats
 * des tests doivent figurer dans les logs pour que tu puisses valider le
 * passage en prod »).
 *
 * FONCTIONNEMENT :
 *  - la liste est DÉFINIE PAR RELEASE (les items de « À tester » des notes) —
 *    affichée sur la liste des conversations des builds test/test2 UNIQUEMENT ;
 *  - un appui marque le test FAIT (horodaté dans les préférences locales) et
 *    journalise « nyx-test PASS <id> : <label> » — la ligne part dans l'export
 *    de logs (LogCollector lit le tampon logcat) → l'agent/l'opérateur voit
 *    les résultats directement dans les logs téléversés ;
 *  - un appui long DÉ-MARQUE (retour « à faire », journalisé WARN) ;
 *  - la mise en production d'une version test = TOUS les items PASS dans les
 *    logs + validation opérateur (le build standard, lui, ne prend que les
 *    releases « latest » non-pré-release — cf. Updater).
 */
object TestPlan {

    /** Un test de validation de la release courante. */
    data class Item(val id: String, val label: String)

    /** v0.2.20 — items de validation (miroir de docs/releases/v0.2.20.md). */
    val ITEMS_V0_2_20 = listOf(
        Item("appel-sonnerie", "Appel vocal : le destinataire ENTEND la sonnerie (même app en arrière-plan)"),
        Item("appel-actif", "Appel vocal : conversation audio après acceptation des deux côtés"),
        Item("appel-fin", "Appel : raccrocher proprement des deux côtés (pas d'appel fantôme)"),
        Item("visio-active", "Visio : vidéo des deux côtés après acceptation (aperçu local + distant)"),
        Item("visio-camera", "Visio : le bouton caméra coupe/relance l'émission vidéo"),
        Item("visio-repli", "Visio : refuser la caméra = appel AUDIO-ONLY (pas d'erreur)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ inchangés"),
        Item("regressions-medias", "Régressions : onglet Fichiers & médias + suppression (v0.2.19)"),
        Item("regressions-replica", "Régressions (test 2) : réplica temporaire actif + cycle sans serveur"),
    )

    /** v0.2.24 — items de validation (miroir de docs/releases/v0.2.24.md) :
     *  reprise des validations en attente du terrain (0.2.21-0.2.23) + les
     *  correctifs de CETTE release (défilement, envoi de logs). */
    val ITEMS_V0_2_24 = listOf(
        Item("replica-amorcage", "Connexion : plus de message « pas de réplica d'amorçage » — la création d'identité passe (rendezvous signé v0.2.23)"),
        Item("logs-drive", "Gros bouton rouge : envoi Drive RÉUSSI — le fichier nyx-logs-*.txt apparaît dans le dossier de travail"),
        Item("logs-partage", "Partage sans Drive : le fichier nyx-logs-*.txt arrive COMPLET à destination (pièce jointe, plus coupée)"),
        Item("scroll-tests", "Liste des conversations : l'écran DÉFILE — plan de tests et contacts tous accessibles"),
        Item("restauration", "Restauration fidèle (v0.2.21+) : ré-exporter la sauvegarde puis l'importer — alias + contacts reviennent, PAS de re-création d'alias"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ inchangés"),
        Item("regressions-medias", "Régressions : onglet Fichiers & médias + pièces jointes (v0.2.19)"),
        Item("regressions-appels", "Régressions (si utilisé) : appel/visio v0.2.20 toujours fonctionnels"),
        Item("regressions-replica2", "Régressions (test 2) : réplica temporaire actif + cycle sans serveur"),
    )

    /** Items de la release courante (miroir de docs/releases/v0.2.24.md). */
    fun items(): List<Item> = ITEMS_V0_2_24

    /** L'horodatage de validation d'un test (0 = à faire). */
    fun doneAt(context: android.content.Context, id: String): Long =
        context.getSharedPreferences("nyx_testplan", android.content.Context.MODE_PRIVATE)
            .getLong("done_$id", 0L)

    /** Marque (ou dé-marque) un test et JOURNALISE le résultat. */
    fun setDone(context: android.content.Context, id: String, done: Boolean) {
        val prefs = context.getSharedPreferences("nyx_testplan", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("done_$id", if (done) System.currentTimeMillis() else 0L).apply()
        val label = items().firstOrNull { it.id == id }?.label ?: id
        if (done) {
            android.util.Log.i("nyx-test", "PASS $id : $label")
        } else {
            android.util.Log.w("nyx-test", "RESET $id : $label (repassé « à faire »)")
        }
    }

    /** Nombre de tests faits (progression affichée). */
    fun doneCount(context: android.content.Context): Int =
        items().count { doneAt(context, it.id) > 0L }

    /** Tout réinitialiser (nouveau cycle de test). */
    fun reset(context: android.content.Context) {
        val prefs = context.getSharedPreferences("nyx_testplan", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        android.util.Log.w("nyx-test", "RESET : plan de tests remis à zéro")
    }

    /** Ligne de synthèse journalisée (export de logs — résumé lisible). */
    fun logSummary(context: android.content.Context) {
        val items = items()
        val done = doneCount(context)
        val missing = items.filter { doneAt(context, it.id) == 0L }.map { it.id }
        android.util.Log.i(
            "nyx-test",
            "SYNTHÈSE : $done/${items.size} validés" + if (missing.isEmpty()) " — TOUS PASS" else " — manquants : ${missing.joinToString(", ")}"
        )
    }
}
