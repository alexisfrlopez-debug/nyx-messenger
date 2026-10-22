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

    /**
     * Un test de validation de la release courante.
     * v0.2.26 : [requiresRedundancy] = le test exige PLUSIEURS réplicas
     * (couper le réplica courant, cycle sans serveur) — demande opérateur :
     * « ça nécessite de mettre le seul serveur/replica hors ligne et je
     * préfère éviter ce genre de prise de risque tant que je n'ai pas de
     * redondance autre que les réplicas temporaires » → l'item s'affiche
     * BLOQUÉ (badge « redondance requise ») tant que la flotte n'a pas
     * ≥ 2 réplicas, sans pouvoir être marqué fait.
     */
    data class Item(val id: String, val label: String, val requiresRedundancy: Boolean = false)

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

    /** v0.2.25 — items de validation (miroir de docs/releases/v0.2.25.md) :
     *  fixes tor 0.4.9 (appels/P2P), partage logs (grants), réplicas
     *  temporaires auto-partagés + failover, /admin/replicas console.
     *  v0.2.26 : les items destructeurs sont marqués requiresRedundancy. */
    val ITEMS_V0_2_25 = listOf(
        Item("tor-resolve", "Appels/visio : « décrocher » ÉTABLIT le média (tor 0.4.9.14 — plus de « No more HSDir » : les deux téléphones se joignent en direct)"),
        Item("fichier-p2p", "Fichier/média : envoi via P2P DIRECT quand les deux apps tournent (rapide) — le chemin journal reste le filet"),
        Item("logs-partage2", "Partage sans Drive : le fichier nyx-logs-*.txt arrive COMPLET dans la cible choisie (FB Messenger, e-mail…)"),
        Item("replica-auto", "Réplicas temporaires (test 2) : l'adresse du réplica d'un contact s'ajoute SEULE (log « annonce réplica ») et survit au redémarrage"),
        Item("replica-failover", "Réplicas : couper le réplica courant → l'app bascule sur le suivant sans redémarrer (log « bascule sur »)", requiresRedundancy = true),
        Item("restauration2", "Restauration fidèle (v0.2.21+) : ré-exporter la sauvegarde puis l'importer — alias + contacts reviennent"),
        Item("regressions-msg2", "Régressions : messages texte + accusés ✓✓ inchangés"),
        Item("regressions-medias2", "Régressions : onglet Fichiers & médias + pièces jointes (v0.2.19)"),
        Item("regressions-appels2", "Régressions : appel/visio v0.2.20 toujours fonctionnels"),
        Item("regressions-replica3", "Régressions (test 2) : réplica temporaire actif + cycle sans serveur", requiresRedundancy = true),
    )

    /** v0.2.26 — items de validation (miroir de docs/releases/v0.2.26.md) :
     *  anti-état collant appels, notifications de messages, carte Réplicas,
     *  sécurité mot de passe du compte, export conversations, éphémères. */
    val ITEMS_V0_2_26 = listOf(
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT l'appel — plus de « offre ignorée (déjà en appel) » ni de WAIT bloqué (watchdogs v0.2.26 ; l'appelant reçoit « refusé » si occupé)"),
        Item("notif-message", "Notification à la réception d'un message : canal « Messages », sonnerie, aperçu — PAS de notif si la conversation est ouverte"),
        Item("replicas-liste", "Paramètres → Réplicas : liste à jour (custom/découverts/amorce/embarqué) avec indicateur ACTIF et « UTILISÉ il y a… »"),
        Item("pwd-force", "Sécurité : à l'ouverture avec identité sans mot de passe → création FORCÉE du mot de passe NORMAL puis DESTRUCTION"),
        Item("sauvegarde-chiffree", "Sauvegarde de compte : fichier chiffré par le mot de passe — import sans mot de passe IMPOSSIBLE"),
        Item("import-destruction", "Import de clé : le mot de passe DESTRUCTION EFFACE le fichier importé (fichier disparu, rien restauré)"),
        Item("conv-export", "Export de conversations : sélection des conversations → fichier chiffré par mot de passe"),
        Item("conv-import", "Import de conversations : le fichier exporté réapparaît (textes), un ré-import ne duplique rien"),
        Item("ephemere", "Messages éphémères : minuteur 1 h/24 h/7 j dans le chat — les messages disparaissent des DEUX côtés (purge 30 s)"),
        Item("regressions-msg3", "Régressions : messages texte + accusés ✓✓ inchangés"),
        Item("regressions-replica4", "Régressions : réplica temporaire actif (cycle sans serveur : voir redondance — BLOQUÉ sans flotte ≥ 2)", requiresRedundancy = true),
    )

    /** v0.2.30 — items de validation (miroir de docs/releases/v0.2.30.md) :
     *  VALIDATION TERRAIN des correctifs v0.2.29 (aucun nouveau correctif —
     *  les mêmes APK que la v0.2.29-test2 143027 côté logique) : réplicas
     *  temporaires réparés (annonce conditionnée à la publication RÉELLE de
     *  l'onion, watchdog de republication, sondes périodiques nominatives),
     *  profil public re-publié (console /admin/replicas alimentée), blocage
     *  local + onglets Conversations/Contacts/Blocages. Cible opérateur : les
     *  appels/visio qui décrochent ENFIN via les réplicas temporaires. */
    val ITEMS_V0_2_30 = listOf(
        Item("replica-joignable", "Réplicas temporaires (test 2) : le réplica découvert d'un contact EST joignable — plus de « Host unreachable » (l'annonce ne part que si l'onion est réellement publiée)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT l'appel (média + audio/vidéo) — cause « host unreachable »/« Connect timed out » corrigée en v0.2.29"),
        Item("sonde-auto", "Logs : « auto-sonde média OK » (ou la cause nominative) après le démarrage — exportez les logs juste après un essai d'appel"),
        Item("auto-republication", "Logs : le watchdog est vivant — « republication » visible si l'onion n'était pas publiée (sinon aucune ligne, normal)"),
        Item("profil-republie", "Console admin (v0.8.1) : l'onglet réplicas temporaires se remplit SEUL — profil public re-publié dès que le réplica sert, puis toutes les 6 h"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état exact d'avant"),
        Item("onglets", "Onglets : Conversations (chronologique) / Contacts (alphabétique + raccourcis Message/Appel/Visio) / Blocages — « Ajouter un contact » vit dans Contacts"),
        Item("nouvelle-conversation", "Onglet Conversations : « Nouvelle conversation » ouvre le sélecteur de contacts acceptés"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée (v0.2.26) — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
        Item("regressions-replica5", "Régressions (test 2) : réplica temporaire actif et annonce de nouveau au redémarrage", requiresRedundancy = false),
    )

    /** v0.2.31 — items de validation (miroir de docs/releases/v0.2.31.md) :
     *  UI : défilement systématique (écran principal réparé — onglets confinés
     *  au poids restant, plan de tests replié, verrou défilant) + clavier
     *  (imePadding à la RACINE — le bas de page reste visible sur tous les
     *  écrans) + console v0.8.2 (journal de la boîte à outils en tête,
     *  auto-défilé). Les validations terrain v0.2.29 se poursuivent. */
    val ITEMS_V0_2_31 = listOf(
        Item("scroll-principal", "Écran principal (build test) : les onglets défilent dans l'espace restant — le bas de la liste est atteignable ; le plan de tests est replié (▶) et s'ouvre au clic"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible — correctif racine imePadding v0.2.31"),
        Item("verrou-defile", "Écran verrou : le contenu défile (petit écran / clavier ouvert) — déverrouillage toujours utilisable"),
        Item("console-toolbox", "Console v0.8.2 : boîte à outils 1-clic — le journal s'affiche EN TÊTE, auto-défilé à chaque ligne, « Copier la sortie » + « ← retour à la liste » (enchaînement des blocs sans ressaisie du mot de passe)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT l'appel via les réplicas temporaires (validation continue des fixs v0.2.29)"),
        Item("replica-joignable", "Réplicas temporaires : le réplica découvert d'un contact EST joignable — pas de « Host unreachable »"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état d'avant"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** v0.2.32 — items de validation (miroir de docs/releases/v0.2.32.md) :
     *  COMPLÉMENT au plan v0.2.31 (10 items — le retour terrain montre que la
     *  liste déployée du plan de tests restait non défilante) : l'item
     *  scroll-plan-tests couvre le nouveau correctif (liste CONFINÉE +
     *  verticalScroll INTERNE quand la carte est déployée) ; les 10 items
     *  v0.2.31 sont repris À L'IDENTIQUE (validation continue — aucun n'est
     *  encore coché en terrain). */
    private val ITEMS_V0_2_32 = listOf(
        Item("scroll-plan-tests", "Plan de tests : la liste DÉPLOYÉE défile À L'INTÉRIEUR de la carte — toutes les cases et « Tout réinitialiser » sont atteignables (l'entête reste visible)"),
        Item("scroll-principal", "Écran principal (build test) : les onglets défilent dans l'espace restant — le bas de la liste est atteignable ; le plan de tests est replié (▶) et s'ouvre au clic"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible — correctif racine imePadding v0.2.31"),
        Item("verrou-defile", "Écran verrou : le contenu défile (petit écran / clavier ouvert) — déverrouillage toujours utilisable"),
        Item("console-toolbox", "Console v0.8.2 : boîte à outils 1-clic — le journal s'affiche EN TÊTE, auto-défilé à chaque ligne, « Copier la sortie » + « ← retour à la liste » (enchaînement des blocs sans ressaisie du mot de passe)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT l'appel via les réplicas temporaires (validation continue des fixs v0.2.29)"),
        Item("replica-joignable", "Réplicas temporaires : le réplica découvert d'un contact EST joignable — pas de « Host unreachable »"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état d'avant"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** v0.2.33 — items de validation (miroir de docs/releases/v0.2.33.md) :
     *  PIPELINE LOGS RÉPARÉ (refresh token Drive expiré le 21/10 — cycle ~7 j,
     *  cause « les logs ne s'envoient plus ») : nouveau token intégré au build
     *  + ANNEAU DE DIAGNOSTIC PERSISTANT (DiagLog — les traces d'appels
     *  surviennent à la mort du process ; l'export v0.2.31 ne montrait qu'un
     *  process de 58 s, les tentatives d'appel de l'heure précédente étaient
     *  PERDUES). Les 11 items v0.2.32 sont repris À L'IDENTIQUE (validation
     *  continue — le retour terrain n'a pas encore coché le plan défilant). */
    val ITEMS_V0_2_33 = listOf(
        Item("logs-arrivent", "Bouton rouge (build test) : l'export de logs ARRIVE dans le dossier Drive (vérifiable dans les minutes qui suivent) — refresh token renouvelé le 22/10 ; l'export contient la section « DIAG PERSISTANT »"),
        Item("scroll-plan-tests", "Plan de tests : la liste DÉPLOYÉE défile À L'INTÉRIEUR de la carte — toutes les cases et « Tout réinitialiser » sont atteignables (l'entête reste visible)"),
        Item("scroll-principal", "Écran principal (build test) : les onglets défilent dans l'espace restant — le bas de la liste est atteignable ; le plan de tests est replié (▶) et s'ouvre au clic"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible — correctif racine imePadding v0.2.31"),
        Item("verrou-defile", "Écran verrou : le contenu défile (petit écran / clavier ouvert) — déverrouillage toujours utilisable"),
        Item("console-toolbox", "Console v0.8.3 : bouton « Rafraîchir » sur la page réplicas embarqués + ligne découverte (nœuds interrogés / annonces reçues) ; boîte à outils 1-clic inchangée"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT l'appel — exporter les logs JUSTE APRÈS l'essai (l'anneau persistant garde maintenant les traces même si l'app redémarre)"),
        Item("replica-joignable", "Réplicas temporaires : le réplica découvert d'un contact EST joignable — pas de « Host unreachable »"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état exact d'avant"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** v0.2.34 — items de validation (miroir de docs/releases/v0.2.34.md) :
     *  CAUSE RACINE HISTORIQUE CORRIGÉE — l'onion du téléphone était DÉTRUIT
     *  à la naissance (ADD_ONION sans flag Detach : tor tue le service
     *  éphémère dès la fermeture de la connexion contrôle ; preuve v0.2.33 —
     *  auto-sondes KO dès 15 s, « No more HSDir available to query » en
     *  boucle, appel entrant accepté mais média jamais établi). Correctif :
     *  Flags=Detach,DiscardPK + sérialisation des publications (collision
     *  tor 551 du 22/10 02:52:41). Les items critiques v0.2.33 sont repris. */
    val ITEMS_V0_2_34 = listOf(
        Item("sonde-ok", "DIAG PERSISTANT (export logs) : « auto-sonde média OK : …:47471 joignable (descripteur publié) » apparaît ~15 s après « onion publié » — PLUS AUCUN « SOCKS: Host unreachable » / « No more HSDir » sur notre propre onion (preuve directe du service vivant)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT le média (audio/visio fluide, les deux sens) — exporter les logs juste après l'essai ; côté appelant les « média essai N » ne débouchent plus sur « connexion directe impossible »"),
        Item("replica-console", "Console v0.8.4 (APRÈS mise à jour du nœud ≥ v0.2.25) : les réplicas embarqués apparaissent seuls (auto-ajout) et leur sonde est OK ; si une ligne « ÉCHEC : … 404 … METTRE LE NŒUD À JOUR » s'affiche, le serveur de prod reste à mettre à jour"),
        Item("logs-arrivent", "Bouton rouge (build test) : l'export de logs ARRIVE dans le dossier Drive — contient la section « DIAG PERSISTANT » avec les événements du process en cours"),
        Item("scroll-plan-tests", "Plan de tests : la liste DÉPLOYÉE défile À L'INTÉRIEUR de la carte — toutes les cases et « Tout réinitialiser » sont atteignables (l'entête reste visible)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible — correctif racine imePadding v0.2.31"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état exact d'avant"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** Items de la release courante (miroir de docs/releases/v0.2.34.md). */
    fun items(): List<Item> = ITEMS_V0_2_34

    /**
     * v0.2.26 — la redondance est-elle disponible ? (≥ 2 réplicas effectifs,
     * OU réplica temporaire embarqué servi — un deuxième nœud réel existe.)
     * Prédicat partagé par la carte plan de tests et la synthèse journal.
     */
    fun redundancyAvailable(): Boolean {
        val repo = runCatching { net.nyx.messenger.di.ServiceLocator.repository }.getOrNull() ?: return false
        return repo.replicas.size >= 2 ||
            (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                net.nyx.messenger.replica.EmbeddedReplica.serving &&
                net.nyx.messenger.replica.EmbeddedReplica.localPort > 0)
    }

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
        val redundancy = redundancyAvailable()
        // v0.2.26 : les items à redondance requise SANS flotte sont signalés
        // SKIPPED (pas « manquants ») — la synthèse reste exploitable.
        val blocked = items.filter { it.requiresRedundancy && !redundancy }.map { it.id }
        val missing = items.filter { doneAt(context, it.id) == 0L && it.id !in blocked }.map { it.id }
        android.util.Log.i(
            "nyx-test",
            "SYNTHÈSE : $done/${items.size} validés" +
                (if (blocked.isNotEmpty()) " — SKIPPED (redondance absente) : ${blocked.joinToString(", ")}" else "") +
                (if (missing.isEmpty()) " — TOUS PASS" else " — manquants : ${missing.joinToString(", ")}")
        )
    }
}
