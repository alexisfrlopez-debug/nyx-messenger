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
 *    affichée sur la liste des conversations du build test UNIQUEMENT (le
 *    build standard est l'app publique, sans instrumentation) ;
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

    /** v0.2.35 — items de validation (miroir de docs/releases/v0.2.35.md) :
     *  (1) FUSION (demande opérateur) : le RÉPLICA EMBARQUÉ, validé en « test 2 »
     *  de la v0.2.19 à la v0.2.34, est INTÉGRÉ aux versions normale ET test —
     *  la version « test 2 » disparaît (2 APK par release) ; (2) REFONTE
     *  ESTHÉTIQUE « Nyx, déesse de la nuit » (moins technique, plus agréable) ;
     *  (3) serveur : constante de version ENFIN alignée sur les releases
     *  (/admin/status distinguera ancien/nouveau nœud — axe fiche corrigé) ;
     *  console v0.8.5 : MISE À JOUR DU NŒUD EN 1 CLIC. Les items terrain
     *  critiques v0.2.34 (sonde-ok, appel-decroche) restent en tête :
     *  non encore cochés au terrain. */
    val ITEMS_V0_2_35 = listOf(
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » apparaît dans les Paramètres du build NORMAL (jamais servi avant) — l'interrupteur arrête/reprend le service, l'annonce part aux contacts, le téléphone reste joignable :80 tant que l'app tourne"),
        Item("sonde-ok", "DIAG PERSISTANT (export logs) : « auto-sonde média OK : …:47471 joignable (descripteur publié) » ~15 s après « onion publié » — plus aucun « No more HSDir » sur notre propre onion (validation continue v0.2.34)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT le média (audio/visio fluide, les deux sens) — exporter les logs juste après l'essai (validation continue v0.2.34)"),
        Item("replica-console", "Console v0.8.5 APRÈS « Mettre à jour le nœud » (1 clic) : les réplicas embarqués apparaissent seuls, /admin/replicas ne répond plus 404 et /admin/status affiche « 0.2.35 » (constante ENFIN alignée — axe fiche corrigé)"),
        Item("maj-1clic", "Console v0.8.5 : « Mettre à jour le nœud » — journal en direct (SFTP + sha256 + stop/.bak/install/start + is-active), backup .bak visible, rollback < 1 min possible"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé de nuit sur tous les écrans, lune + constellation sur connexion/onboarding, icône croissant dans le lanceur, mode clair « aube » — aucun texte tronqué, l'app paraît moins technique"),
        Item("logs-arrivent", "Bouton rouge (build test) : l'export de logs ARRIVE dans le dossier Drive — contient « DIAG PERSISTANT » (validation continue)"),
        Item("scroll-plan-tests", "Plan de tests : la liste DÉPLOYÉE défile À L'INTÉRIEUR de la carte — toutes les cases et « Tout réinitialiser » sont atteignables (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état exact d'avant (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** v0.2.36 — items de validation (miroir de docs/releases/v0.2.36.md) :
     *  CORRECTIF LISIBILITÉ MODE SOMBRE (retour opérateur : « en mode
     *  "sombre" certains textes sont affichés en noir sur fond bleu foncé,
     *  ce qui est peu lisible ») — cause racine : Scaffold transparents +
     *  LocalContentColor par défaut NOIR ; fix racine NyxBackground +
     *  statuts verts/ambre thème-aware + tests de contraste WCAG. Les items
     *  terrain critiques v0.2.35 (fusion-replica, sonde-ok, appel-decroche,
     *  replica-console, maj-1clic) restent EN TÊTE : non cochés au terrain. */
    val ITEMS_V0_2_36 = listOf(
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » apparaît dans les Paramètres du build NORMAL (jamais servi avant) — l'interrupteur arrête/reprend le service, l'annonce part aux contacts, le téléphone reste joignable :80 tant que l'app tourne"),
        Item("sonde-ok", "DIAG PERSISTANT (export logs) : « auto-sonde média OK : …:47471 joignable (descripteur publié) » ~15 s après « onion publié » — plus aucun « No more HSDir » sur notre propre onion (validation continue v0.2.34/35)"),
        Item("appel-decroche", "Appels/visio : « décrocher » ÉTABLIT le média (audio/visio fluide, les deux sens) — exporter les logs juste après l'essai (validation continue v0.2.34/35)"),
        Item("replica-console", "Console v0.8.5 APRÈS « Mettre à jour le nœud » (1 clic) : les réplicas embarqués apparaissent seuls, /admin/replicas ne répond plus 404 et /admin/status affiche « 0.2.35 » (version du serveur embarqué — la 0.2.36 est la version de l'APP)"),
        Item("maj-1clic", "Console v0.8.5 : « Mettre à jour le nœud » — journal en direct (SFTP + sha256 + stop/.bak/install/start + is-active), backup .bak visible, rollback < 1 min possible"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » (correctif v0.2.36) : AUCUN texte noir/illisible sur le dégradé bleu nuit — parcourir TOUS les écrans (conversations, chat, réglages, connexions, profils, onion, verrou, plan de tests) ; les textes par défaut sont blanc lunaire, les statuts OK en jade lisible"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » (clair) : aucun texte pâle/illisible sur ivoire ; l'ambre « en attente » (réplica actif sans sonde OK) reste lisible — il était #F9A825 quasi invisible sur l'aube avant v0.2.36"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé de nuit sur tous les écrans, lune + constellation sur connexion/onboarding, icône croissant dans le lanceur, mode clair « aube » — aucun texte tronqué, l'app paraît moins technique"),
        Item("logs-arrivent", "Bouton rouge (build test) : l'export de logs ARRIVE dans le dossier Drive — contient « DIAG PERSISTANT » (validation continue)"),
        Item("scroll-plan-tests", "Plan de tests : la liste DÉPLOYÉE défile À L'INTÉRIEUR de la carte — toutes les cases et « Tout réinitialiser » sont atteignables (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; l'onglet Blocages le liste ; débloquer restaure l'état exact d'avant (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message (canal « Messages »), rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée (décompte, destruction à 0 vue)"),
    )

    /** v0.2.37 — items de validation (miroir de docs/releases/v0.2.37.md) :
     *  CORRECTIF « SOCKS server general failure » / APPELS + CONSOLE (retour
     *  opérateur 22/10 : « le replica temporaire apparait dans la console
     *  mais affiche "SOCKS injoignable" » + « toujours impossible de décrocher
     *  un appel ») — cause racine : descripteur onion PÉRIMÉ (points
     *  d'introduction morts après chaque redémarrage d'app) servi par le
     *  cache client (jusqu'à 24 h) et les HSDirs (30-60 s) → échec
     *  d'INTRODUCE immédiat. Fix : HSFETCH (re-demande forcée du descripteur)
     *  intégré aux connexions P2P, aux auto-sondes et à la console ;
     *  republication forcée désormais dernier recours (elle aggravait le mal) ;
     *  hygiène du DataDirectory corrompu (Xiaomi). */
    val ITEMS_V0_2_37 = listOf(
        Item("appel-decroche", "APPELS (correctif v0.2.37) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média — le log appelant montre « média essai N » puis « HSFETCH … — descripteur re-demandé » puis la connexion aboutit ; plus aucune salve de 14 « SOCKS server general failure »"),
        Item("appel-repos", "APPELS après >2 min de fonctionnement des deux apps : le média s'établit dès la 1re ou 2e tentative (descripteur propagé — fenêtre HSDir passée)"),
        Item("sonde-ok", "DIAG PERSISTANT (export logs) : après un redémarrage de l'app, l'auto-sonde peut échouer UNE fois (« descripteur pas encore propagé… HSFETCH déjà tenté ») mais la salve suivante (~10 min) est OK — plus de « RÉPUBLICATION FORCÉE » intempestive (désormais dernier recours après 4 échecs ~40 min)"),
        Item("replica-console", "Console v0.8.6 APRÈS mise à jour du nœud (v0.2.35+) : le réplica temporaire découvert (source=auto) passe EN LIGNE (sonde /health OK, version 0.2.37) quand le téléphone tourne depuis >2 min — l'erreur « SOCKS server general failure » déclenche désormais HSFETCH + nouvelle sonde automatique dans la console elle-même"),
        Item("lastseen", "Console v0.8.6 : le « vu » du réplica temporaire affiche une date RÉELLE (22/10/2026…) et non plus « 1970 » (le ts des annonces était en secondes, formaté comme des millisecondes)"),
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » apparaît dans les Paramètres du build NORMAL — l'interrupteur arrête/reprend le service, l'annonce part aux contacts (validation continue v0.2.35/36)"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » : aucun texte noir/illisible sur le dégradé bleu nuit — tous les écrans (validation continue v0.2.36)"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » : aucun texte pâle/illisible sur ivoire (validation continue v0.2.36)"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé/lune/icône/aube (validation continue v0.2.36)"),
        Item("logs-arrivent", "Bouton rouge (build test) : l'export de logs ARRIVE dans le dossier Drive — contient « DIAG PERSISTANT » sans caractères binaires (corruption NUL tor.log purgée au démarrage si présente)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; débloquer restaure l'état exact (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message, rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée"),
    )

    /** v0.2.38 — items de validation (miroir de docs/releases/v0.2.38.md) :
     *  DÉFILEMENT GARANTI PAR CONSTRUCTION (retour opérateur : « l'écran
     *  principal de la messagerie ne défile pas, il faudrait vérifier
     *  systématiquement le défilement de toutes les fenêtres » — RÉCIDIVE
     *  malgré le fix v0.2.31 : la zone fixe bannières + cartes test n'avait
     *  aucun plafond, elle pouvait dépasser l'écran et écraser la zone
     *  d'onglets à ~0 px). Fix : zone fixe plafonnée à 40 % du corps et
     *  défilante EN INTERNE (ScrollPolicy, invariant verrouillé par
     *  ScrollPolicyTest) + cartes de diagnostic REPLIÉES par défaut +
     *  audit systématique de toutes les fenêtres (chat/réglages/profils/
     *  verrou/connexions : colonnes défilantes confirmées). Les validations
     *  HSFETCH v0.2.37 restent EN ATTENTE terrain — items conservés. */
    val ITEMS_V0_2_38 = listOf(
        Item("scroll-principal-v2", "ÉCRAN PRINCIPAL (correctif v0.2.38) : la liste des conversations DÉFILE toujours — même build test (cartes repliées par défaut : « Envoi des journaux ▶ » + « Plan de tests ▶ »), même bannières d'erreur multi-lignes, même grande police d'accessibilité ; la zone d'onglets garde toujours ≥ 60 % de l'écran"),
        Item("scroll-zone-fixe", "Écran principal build test : DÉPLIER les deux cartes de diagnostic — la zone fixe (bannières + cartes) défile EN INTERNE si elle dépasse 40 % de l'écran (plan de tests complet atteignable), les onglets restent visibles dessous"),
        Item("scroll-toutes-fenetres", "TOUTES LES FENÊTRES : chat (messages + fichiers & médias), contacts/blocages, réglages, mon profil, fiche contact, mon adresse, verrou, connexions, onboarding — chaque écran défile quand son contenu dépasse l'écran, clavier ouvert aussi"),
        Item("appel-decroche", "APPELS (correctif v0.2.37 À VALIDER) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média — le log appelant montre « HSFETCH » puis la connexion ; plus aucune salve de « SOCKS server general failure »"),
        Item("appel-repos", "APPELS après >2 min de fonctionnement des deux apps : le média s'établit dès la 1re ou 2e tentative (validation v0.2.37)"),
        Item("sonde-ok", "DIAG PERSISTANT : après un redémarrage de l'app, l'auto-sonde peut échouer UNE fois mais la salve suivante est OK — plus de « RÉPUBLICATION FORCÉE » intempestive (validation v0.2.37)"),
        Item("replica-console", "Console v0.8.6 : le réplica temporaire découvert passe EN LIGNE quand le téléphone tourne depuis >2 min (HSFETCH intégré à la sonde console — validation v0.2.37)"),
        Item("lastseen", "Console v0.8.6 : le « vu » du réplica temporaire affiche une date RÉELLE et non plus « 1970 » (validation v0.2.37)"),
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » dans les Paramètres du build NORMAL (validation continue v0.2.35/36)"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » : aucun texte noir/illisible sur le dégradé bleu nuit (validation continue v0.2.36)"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » : aucun texte pâle/illisible sur ivoire (validation continue v0.2.36)"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé/lune/icône/aube (validation continue v0.2.36)"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état (collecte/envoi/déposé) reste visible dans la ligne de titre MÊME CARTE REPLIÉE"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; débloquer restaure l'état exact (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message, rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée"),
    )

    /** v0.2.39 — items de validation (miroir de docs/releases/v0.2.39.md) :
     *  CORRECTIF CRITIQUE HSFETCH (retour opérateur : « toujours le problème
     *  "SOCKS server failure" et décrochage impossible. la cause peut être
     *  la même, donc à régler en priorité » — CONFIRMÉ, cause IDENTIQUE des
     *  deux symptômes) : v0.2.37 envoyait « HSFETCH <56>.onion » alors que le
     *  control-spec exige HSAddress = 56*Base32Character SANS suffixe → tor
     *  rejetait CHAQUE HSFETCH par « 513 Invalid argument » (vérifié source
     *  tor 0.4.9.14 hs_parse_address_no_log) → la récupération de descripteur
     *  n'a JAMAIS opéré sur le terrain → cache client périmé → « SOCKS server
     *  general failure » en boucle → appels sans média + réplica « SOCKS
     *  injoignable » console. Fix : adresse NUE (56 base32) des DEUX côtés
     *  (messager + console v0.8.7), faux control-port des tests de contrat
     *  validant désormais la syntaxe comme le vrai tor (513 sinon). Les
     *  validations HSFETCH v0.2.37/38 (jamais réellement exécutables) et le
     *  défilement v0.2.38 restent inclus. ATTENTION TERRAIN : les DEUX
     *  téléphones doivent être en v0.2.39 (le Blackview était resté v0.2.36
     *  — sans HSFETCH du tout — lors du test du 22/10 soir). */
    val ITEMS_V0_2_39 = listOf(
        Item("hsfetch-ok", "DIAG PERSISTANT (correctif v0.2.39) : après un échec de connexion, le log montre « HSFETCH <10 premiers car.>… — descripteur re-demandé aux HSDirs » SANS aucun « erreur Tor 513 » — la récupération opère enfin (v0.2.37/v0.2.38 étaient rejetées par tor)"),
        Item("appel-decroche", "APPELS (correctif v0.2.39, LES 2 TÉLÉPHONES en v0.2.39) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média — le log appelant montre « HSFETCH » puis la connexion ; plus aucune salve de « SOCKS server general failure »"),
        Item("appel-repos", "APPELS après >2 min de fonctionnement des deux apps : le média s'établit dès la 1re ou 2e tentative"),
        Item("sonde-ok", "DIAG PERSISTANT : après un redémarrage de l'app, l'auto-sonde peut échouer UNE fois puis la récupération HSFETCH (sans 513) la rétablit — plus de « RÉPUBLICATION FORCÉE » intempestive"),
        Item("replica-console", "Console v0.8.7 : le réplica temporaire découvert passe EN LIGNE quand le téléphone tourne depuis >2 min (HSFETCH corrigé dans la sonde console — plus de « HSFETCH tenté sans succès » en boucle)"),
        Item("lastseen", "Console v0.8.7 : le « vu » du réplica temporaire affiche une date RÉELLE et non plus « 1970 »"),
        Item("scroll-principal-v2", "ÉCRAN PRINCIPAL (validation continue v0.2.38) : la liste des conversations DÉFILE toujours — cartes repliées par défaut, bannières d'erreur multi-lignes, grande police ; zone d'onglets ≥ 60 % de l'écran"),
        Item("scroll-toutes-fenetres", "TOUTES LES FENÊTRES (validation continue v0.2.38) : chat, contacts/blocages, réglages, profil, adresse, verrou, connexions, onboarding — chaque écran défile, clavier ouvert aussi"),
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » dans les Paramètres du build NORMAL (validation continue v0.2.35/36)"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » : aucun texte noir/illisible sur le dégradé bleu nuit (validation continue v0.2.36)"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » : aucun texte pâle/illisible sur ivoire (validation continue v0.2.36)"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé/lune/icône/aube (validation continue v0.2.36)"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible dans la ligne de titre MÊME CARTE REPLIÉE"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; débloquer restaure l'état exact (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message, rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée"),
    )

    /** v0.2.40 — items de validation (miroir de docs/releases/v0.2.40.md) :
     *  MISE À JOUR AUTOMATIQUE DU BUILD TEST (demande opérateur : « j'aimerais
     *  que la version test se mette à jour automatiquement dès qu'une nouvelle
     *  version est poussée sur github ») : canal test dédié — la version TEST
     *  suit désormais la DERNIÈRE release publiée (pré-releases incluses, APK
     *  -test) avec vérification toutes les 30 min, téléchargement auto via Tor,
     *  bannière « Nouvelle version X prête — installer » sur l'écran principal,
     *  notification + carte Paramètres dédiée. AUSSI : correctif manifest
     *  REQUEST_INSTALL_PACKAGES (absente depuis v0.2.20 — l'installation
     *  était bloquée silencieusement par Android 8+ sur les DEUX canaux).
     *  Les validations critiques v0.2.39 (HSFETCH, appels) restent en tête. */
    val ITEMS_V0_2_40 = listOf(
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (NOUVEAU v0.2.40) : à la publication de la prochaine release GitHub, la version TEST la détecte en ≤ 30 min (ou via Paramètres → « Vérifier maintenant »), la télécharge via Tor, affiche la bannière « Nouvelle version X prête — installer » + notification « Nyx TEST » → L'INSTALLATION ABOUTIT (correctif permission REQUEST_INSTALL_PACKAGES : Android 8+ la bloquait silencieusement depuis v0.2.20)"),
        Item("maj-auto-carte", "MAJ AUTO — carte Paramètres visible dans le build TEST avec le texte « Canal TEST : … pré-releases incluses » ; « Vérifier maintenant » rafraîchit l'état ; les versions PLUS ANCIENNES ne sont jamais proposées (anti-downgrade)"),
        Item("maj-auto-standard", "MAJ AUTO CANAL STANDARD (validation continue v0.2.20) : le build NORMAL continue d'ignorer les pré-releases — il ne propose QUE la dernière release validée (non-pré-release), APK -release.apk"),
        Item("hsfetch-ok", "DIAG PERSISTANT (validation continue v0.2.39) : après un échec de connexion, le log montre « HSFETCH <10 premiers car.>… — descripteur re-demandé aux HSDirs » SANS aucun « erreur Tor 513 »"),
        Item("appel-decroche", "APPELS (validation continue v0.2.39, LES 2 TÉLÉPHONES à jour) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média ; plus aucune salve de « SOCKS server general failure »"),
        Item("appel-repos", "APPELS après >2 min de fonctionnement des deux apps : le média s'établit dès la 1re ou 2e tentative"),
        Item("sonde-ok", "DIAG PERSISTANT : après un redémarrage de l'app, l'auto-sonde peut échouer UNE fois puis la récupération HSFETCH (sans 513) la rétablit"),
        Item("replica-console", "Console v0.8.7 : le réplica temporaire découvert passe EN LIGNE quand le téléphone tourne depuis >2 min"),
        Item("lastseen", "Console v0.8.7 : le « vu » du réplica temporaire affiche une date RÉELLE et non plus « 1970 »"),
        Item("scroll-principal-v2", "ÉCRAN PRINCIPAL (validation continue v0.2.38) : la liste des conversations DÉFILE toujours — la bannière de mise à jour ne perturbe ni le défilement ni le plafond de la zone fixe"),
        Item("scroll-toutes-fenetres", "TOUTES LES FENÊTRES (validation continue v0.2.38) : chat, contacts/blocages, réglages, profil, adresse, verrou, connexions, onboarding — chaque écran défile, clavier ouvert aussi"),
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » dans les Paramètres du build NORMAL (validation continue v0.2.35/36)"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » : aucun texte noir/illisible sur le dégradé bleu nuit (validation continue v0.2.36)"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » : aucun texte pâle/illisible sur ivoire (validation continue v0.2.36)"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé/lune/icône/aube (validation continue v0.2.36)"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible dans la ligne de titre MÊME CARTE REPLIÉE"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; débloquer restaure l'état exact (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message, rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée"),
    )

    /** v0.2.41 — items de validation (miroir de docs/releases/v0.2.41.md) :
     *  TRANSPORT DE REPLAN P2P (ADR-017, demande opérateur : « connexion aux
     *  replicas instables, il faudrait un 2e protocole de connexion qui
     *  assure une redondance/stabilité/rapidité […] peut-être s'inspirer de
     *  Briar pour une connexion P2P pur capable de prendre le relais quand
     *  les 2 téléphones sont en ligne ») : messages + signalisation d'appel
     *  livrables en DIRECT onion→onion (repli/anticipation selon la santé du
     *  réplica) + ANTI-CHURN tor (plus de redémarrage agressif du démon —
     *  cause racine de la cascade « SOCKS server general failure »). Les
     *  validations critiques antérieures restent incluses. ATTENTION TERRAIN :
     *  les 2 téléphones en v0.2.41 pour le chemin direct (un pair plus ancien
     *  refuse le HELLO events — l'expéditeur retombe alors sur le journal,
     *  comportement normal). */
    val ITEMS_V0_2_41 = listOf(
        Item("p2p-repli-message", "REPLAN P2P — MESSAGE (NOUVEAU v0.2.41, LES 2 TÉLÉPHONES en v0.2.41) : répliques COUPÉS (VM éteinte + répliques temporaires arrêtées) puis envoi d'un message texte pair-à-pair les 2 apps ouvertes → le log expéditeur montre « ✓ livré DIRECT P2P (réplica dégradé) » et le message ARRIVE chez le pair en ~5-20 s"),
        Item("p2p-repli-appel", "REPLAN P2P — APPEL (LES 2 TÉLÉPHONES en v0.2.41) : répliques coupés, appel vocal pair-à-pair les 2 apps ouvertes → la sonnerie part en direct (« signalisation livrée DIRECT P2P » dans le log) et le MÉDIA s'établit (chemin média déjà P2P)"),
        Item("p2p-sain", "COMPORTEMENT SAIN — répliques RÉTABLIS : les messages repassent par le journal (« ✓ déposé »), le poll réussit, plus de bascules en cascade ; les accusés ✓✓ reviennent"),
        Item("antichurn-tor", "ANTI-CHURN TOR : sur réseau lent (timeouts 25 s visibles), le log ne montre PLUS de « redémarrage du démon tor » dans les ~10 min suivant un bootstrap — plus de cascade « SOCKS server general failure »"),
        Item("dedup-direct", "DÉDOUBLONNAGE : un message livré en direct P2P puis re-livré par le journal (répliques rétablis) n'apparaît QU'UNE FOIS chez le pair (dédup eventId, porte commune)"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue v0.2.40) : à la publication de CETTE release, la version TEST en v0.2.40-test doit la détecter en ≤ 30 min, la télécharger via Tor, afficher la bannière « Nouvelle version 0.2.41 prête — installer » → L'INSTALLATION ABOUTIT (première validation réelle du correctif permission)"),
        Item("maj-auto-carte", "MAJ AUTO — carte Paramètres visible dans le build TEST avec le texte « Canal TEST : … pré-releases incluses » ; « Vérifier maintenant » rafraîchit l'état ; anti-downgrade respecté"),
        Item("maj-auto-standard", "MAJ AUTO CANAL STANDARD (validation continue v0.2.20) : le build NORMAL ignore les pré-releases — il ne propose QUE la dernière release validée (non-pré-release), APK -release.apk"),
        Item("hsfetch-ok", "DIAG PERSISTANT (validation continue v0.2.39) : après un échec de connexion, le log montre « HSFETCH … — descripteur re-demandé aux HSDirs » SANS aucun « erreur Tor 513 »"),
        Item("appel-decroche", "APPELS (validation continue v0.2.39, LES 2 TÉLÉPHONES à jour) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média ; plus aucune salve de « SOCKS server general failure »"),
        Item("appel-repos", "APPELS après >2 min de fonctionnement des deux apps : le média s'établit dès la 1re ou 2e tentative"),
        Item("replica-console", "Console v0.8.7 : le réplica temporaire découvert passe EN LIGNE quand le téléphone tourne depuis >2 min"),
        Item("lastseen", "Console v0.8.7 : le « vu » du réplica temporaire affiche une date RÉELLE et non plus « 1970 »"),
        Item("scroll-principal-v2", "ÉCRAN PRINCIPAL (validation continue v0.2.38) : la liste des conversations DÉFILE toujours — la bannière de mise à jour ne perturbe ni le défilement ni le plafond de la zone fixe"),
        Item("scroll-toutes-fenetres", "TOUTES LES FENÊTRES (validation continue v0.2.38) : chat, contacts/blocages, réglages, profil, adresse, verrou, connexions, onboarding — chaque écran défile, clavier ouvert aussi"),
        Item("fusion-replica", "Fusion : la carte « Réplica embarqué » dans les Paramètres du build NORMAL (validation continue v0.2.35/36)"),
        Item("lisibilite-nuit", "LISIBILITÉ MODE « NUIT » : aucun texte noir/illisible sur le dégradé bleu nuit (validation continue v0.2.36)"),
        Item("lisibilite-aube", "LISIBILITÉ MODE « AUBE » : aucun texte pâle/illisible sur ivoire (validation continue v0.2.36)"),
        Item("esthetique", "Esthétique « Nyx, déesse de la nuit » : dégradé/lune/icône/aube (validation continue v0.2.36)"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible dans la ligne de titre MÊME CARTE REPLIÉE"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("blocage", "Blocage/déblocage : bloquer (pression longue) coupe TOUT du contact ; débloquer restaure l'état exact (validation continue)"),
        Item("restauration3", "Régressions : restauration de sauvegarde chiffrée — alias + contacts reviennent, mot de passe demandé à l'import"),
        Item("notif-message", "Régressions : notification à la réception d'un message, rien si la conversation est ouverte"),
        Item("regressions-msg4", "Régressions : messages texte + accusés ✓✓ + média à vue limitée"),
    )

    val ITEMS_V0_2_44 = listOf(
        Item("appel-decroche-relais", "APPEL DÉCROCHÉ VIA RELAIS (NOUVEAU v0.2.44, LES 2 TÉLÉPHONES en v0.2.44 + SERVEUR FLOTTE 0.2.36 DÉPLOYÉ) : appels alors que l'onion du pair est injoignable (le cas terrain 23/10) → après ~60 s le log appelant montre « relais réplica ouvert en parallèle », à ~150 s « média VIA RELAIS réplica — direct impossible », côté appelé « média via RELAIS réplica — HELLO de … » → LA CONVERSATION S'ÉTABLIT ET L'AUDIO COULE (le décrochage aboutit enfin)"),
        Item("appel-direct-dabord", "APPEL DIRECT D'ABORD : onion du pair joignable → le média s'établit en direct AVANT 60 s et le log appelant montre la moitié relais libérée (« direct a gagné » : PAS de « média VIA RELAIS ») — le relais ne consomme jamais un appel sain"),
        Item("serveur-relais-deploye", "SERVEUR 0.2.36 : le réplica de flotte interrogé par la console (ou curl via tor) répond 400 (pas 404) à « GET /call/relay?cid=test&role=caller » SANS Upgrade — preuve que la route existe avant de tester les appels"),
        Item("conflux-zero", "TOR SANS CONFLUX : au bootstrap, le tor.log ne contient AUCUN « All current guards excluded by path restriction type 2 » (churn conflux supprimé — 29×/jour sur le Blackview en v0.2.43) ; « ConfluxEnabled 0 » accepté au démarrage (tor démarre)"),
        Item("onion-propage-info", "FORENSIQUE INFO : le tor.log (niveau info désormais) montre « Launching a circuit to intro point » et « stored successfully »/« 200 OK » pour NOTRE onion — à envoyer avec les logs si les appels échouent encore"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent toujours par le réplica de flotte (l'offre arrive en ≤ 15 s), la carte réplica reste ONLINE en console v0.8.9"),
        Item("raccroche-relais", "RACCROCHÉ RELAIS PROPRE : un appel établi VIA relais puis raccroché → le log serveur « relais appel … terminé » (si visible) et AUCUNE socket relais orpheline côté app (l'appel suivant s'ouvre normalement)"),
        Item("appel-decroche", "APPELS DIRECTS (validation continue v0.2.39) : un appel passé ~1 min après le DÉMARRAGE de l'app de l'appelé DOIT établir le média ; plus aucune salve de « SOCKS server general failure »"),
        Item("hsfetch-ok", "DIAG PERSISTANT (validation continue v0.2.39) : après un échec de connexion, le log montre « HSFETCH … — descripteur re-demandé aux HSDirs » SANS aucun « erreur Tor 513 » (les 513 restants dans les exports = ring des anciennes versions v0.2.38, à ignorer)"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue v0.2.40) : à la publication de CETTE release, la version TEST en v0.2.43-test la détecte en ≤ 30 min, la télécharge via Tor, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible dans la ligne de titre MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    /** Items de la release courante (miroir de docs/releases/v0.2.44.md). */
    fun items(): List<Item> = ITEMS_V0_2_44


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
