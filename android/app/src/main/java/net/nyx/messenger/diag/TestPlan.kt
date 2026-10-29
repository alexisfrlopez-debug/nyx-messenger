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

    val ITEMS_V0_2_45 = listOf(
        Item("guerison-cache", "GUÉRISON CACHE DESCRIPTEUR (NOUVEAU v0.2.45, L'ITEM DÉCISIF — le cas Blackview/5G du 23/10) : après installation de v0.2.45 sur un téléphone qui bouclait en « Connect timed out 25 s » vers le réplica de flotte, le DIAG PERSISTANT montre « réplica … : N échecs réseau consécutifs → HSFETCH de guérison » PUIS un « GET /health OK » / poll OK en ~2-3 min (au lieu de ~24 h de cache client empoisonné) — la carte réplica repasse ONLINE"),
        Item("serveur-detach", "SERVEUR 0.2.37 DÉPLOYÉ SANS FENÊTRE ORPHELINE (NOUVEAU v0.2.45) : « Mettre à jour le nœud » (console, binaire 0.2.37) → pendant et après le déploiement, les téléphones NE perdent PAS le réplica (aucune tempête de timeouts de plusieurs minutes) ; le log serveur (journalctl) montre « ✔ ONION CLIENT adopté (déjà vivant — Detach) » ; la carte flotte repasse EN LIGNE en ≤ 1 min"),
        Item("backend-watchdog", "CHIEN DE GARDE BACKENDS (NOUVEAU v0.2.45) : après un échec d'auto-sonde, le diag journalise le verdict « backends locaux (réplica=vivant, p2p=vivant) » ; si un backend meurt (cas rare), « backend local relancé … — republication onion pour recâbler les mappings » apparaît en ≤ 60 s et les pairs se reconnectent"),
        Item("appel-decroche-relais", "APPEL DÉCROCHÉ VIA RELAIS (v0.2.44, TOUJOURS EN ATTENTE — les 2 téléphones en v0.2.44+ + serveur 0.2.36+ déployé) : appels alors que l'onion du pair est injoignable → ~60 s « relais réplica ouvert en parallèle », ~150 s « média VIA RELAIS réplica » → LA CONVERSATION S'ÉTABLIT ET L'AUDIO COULE"),
        Item("appel-direct-dabord", "APPEL DIRECT D'ABORD : onion du pair joignable → média direct AVANT 60 s, PAS de « média VIA RELAIS » — le relais ne consomme jamais un appel sain"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue v0.2.44) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, la télécharge via Tor, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    /** Items de la release courante (miroir de docs/releases/v0.2.46.md). */
    val ITEMS_V0_2_46 = listOf(
        Item("lien-inverse-console", "LIEN INVERSÉ — LA CARTE CONSOLE (NOUVEAU v0.2.46, ITEM DÉCISIF — la demande « régler l'oignon entrant injoignable dans la console ») : téléphone v0.2.46 allumé + serveur 0.2.38 déployé (console v0.8.12 → « Mettre à jour le nœud ») → même quand la sonde DIRECTE de son réplica embarqué échoue (onion entrant mort), la carte passe à « EN LIGNE (VIA LIEN INVERSÉ) — v0.2.46 · X évènement(s) · Y ms » (bleu) avec la version RÉELLE du téléphone — plus JAMAIS « INJOIGNABLE » tant que l'app vit"),
        Item("lien-inverse-diag", "CANAL nyx-link DANS L'EXPORT (NOUVEAU v0.2.46) : l'export de logs montre « nyx-link : canal inversé démarré — le réplica embarqué reçoit désormais via la flotte » puis, à chaque requête tirée, « GET /health → 200 servi LOCALEMENT puis déposé (… ms) » — le téléphone répond par son canal SORTANT"),
        Item("lien-inverse-depot", "REPLI DÉPÔT AU PAIR INJOIGNABLE (NOUVEAU v0.2.46) : les 2 téléphones en v0.2.46, onion du pair injoignable (sonde console KO) → l'envoi d'un message aboutit quand même → log émetteur « ✓ dépôt au pair … servi par LIEN INVERSÉ » ; le pair le reçoit à son prochain poll"),
        Item("guerison-cache", "GUÉRISON CACHE DESCRIPTEUR (v0.2.45, continue) : après 2 échecs réseau consécutifs du même réplica, le DIAG montre « → HSFETCH de guérison » puis réplica ONLINE en ~2-3 min"),
        Item("serveur-detach", "SERVEUR 0.2.38 DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : « Mettre à jour le nœud » (console v0.8.12, binaire 0.2.38 — a00d63d4…) → les téléphones NE perdent PAS le réplica pendant le déploiement ; les routes /link deviennent vivantes (404 → 202)"),
        Item("appel-decroche-relais", "APPEL DÉCROCHÉ VIA RELAIS (v0.2.44, TOUJOURS EN ATTENTE — les 2 téléphones en v0.2.46 + serveur 0.2.38) : appel alors que l'onion du pair est injoignable → ~60 s « relais réplica ouvert en parallèle », ~150 s « média VIA RELAIS réplica » → L'AUDIO COULE (la cause racine commune oignon↔décrochage est documentée v0.2.46 — la signalisation survit de plus maintenant au repli lien dans postSigned)"),
        Item("appel-direct-dabord", "APPEL DIRECT D'ABORD : onion du pair joignable → média direct AVANT 60 s, PAS de relais — le lien/relais ne consomment jamais un chemin sain"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_47 = listOf(
        Item("bind-ipv4-preuve", "BIND IPv4 — LA PREUVE DANS L'EXPORT (NOUVEAU v0.2.47, ITEM DÉCISIF — cause racine des sessions 41→68 : getLoopbackAddress() = ::1 sur Android alors que tor cible 127.0.0.1) : l'export de logs montre « réplica temporaire prêt (local 127.0.0.1:PORT, port 80 onion) » et « écouteur direct prêt (local 127.0.0.1:PORT) » — ET PLUS JAMAIS la boucle « réplica temporaire MORT → relance → onion publié » toutes les 60 s (45 occurrences dans le seul export du 24/10)"),
        Item("onion-entrant-direct", "OIGNON ENTRANT VIVANT POUR LA PREMIÈRE FOIS (NOUVEAU v0.2.47) : la sonde console DIRECTE du réplica embarqué passe — carte « EN LIGNE » directe (pas seulement VIA LIEN INVERSÉ) avec version/événements/latence RÉELS ; l'auto-sonde média « auto-sonde média OK : …:47471 joignable » apparaît dans le DIAG (elle échouait depuis toujours — « SOCKS server general failure »)"),
        Item("appel-direct-media", "APPEL DIRECT AVEC MÉDIA (NOUVEAU v0.2.47 — le test décisif du décrochage) : appel entre les 2 téléphones v0.2.47, l'appelé décroche → le média s'établit EN DIRECT (onion du pair enfin joignable) AVANT tout repli — l'audio coule immédiatement, PAS de bascule relais à 60 s ; si l'onion du pair est injoignable (réseau hostile), le repli relais v0.2.44+ prend le relais comme avant"),
        Item("double-ecouteur", "PLUS DE DOUBLE ÉCOUTEUR P2P (v0.2.47) : à une relance de chien de garde, UN SEUL « écouteur direct prêt » suit le « MORT » (le terrain 24/10 montrait deux « prêt » à 1 ms d'écart — socket orphelin + churn de descripteur) ; « auto-relance (socket fermé, propriétaire confirmé) » n'apparaît que si la fermeture vient d'ailleurs"),
        Item("lien-inverse-console", "LIEN INVERSÉ — LA CARTE CONSOLE (v0.2.46, continue) : téléphone allumé + serveur 0.2.38 déployé → même onion entrant mort, la carte passe à « EN LIGNE (VIA LIEN INVERSÉ) » — le repli doit rester silencieux tant que le direct marche (v0.2.47 : le direct doit maintenant marcher)"),
        Item("guerison-cache", "GUÉRISON CACHE DESCRIPTEUR (v0.2.45, continue) : après 2 échecs réseau consécutifs du même réplica, le DIAG montre « → HSFETCH de guérison » puis réplica ONLINE en ~2-3 min"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : « Mettre à jour le nœud » (console v0.8.12, binaire 0.2.38) → les téléphones NE perdent PAS le réplica pendant le déploiement"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_48 = listOf(
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ — ITEM DÉCISIF (NOUVEAU v0.2.48) : appel entre les 2 téléphones v0.2.48, l'appelé décroche → l'export de L'APPELÉ montre « appel entrant authentifié de <pair>… (cid …) » (le HELLO call accepté — JAMAIS vu depuis la v0.2.18 : le verrou contact du média était plus strict que celui de la sonnerie, un pair PENDING faisait sonner mais son média était refusé = « décrochage impossible » structurel ; la cause du refus est désormais journalisée : signature ou contact)"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (NOUVEAU v0.2.48 — le 1er essai couvre le rendezvous à froid) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio COULE EN DIRECT sans bascule relais — le terrain 24/10 montrait « pending-join … Stalling » 12 s d'affilée : le rendezvous onion à froid prend 15-35 s, l'ancienne fenêtre tuait l'essai AVANT la complétion"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (NOUVEAU v0.2.48 — la permission RECORD_AUDIO n'était JAMAIS demandée : AudioRecord échouait en silence, terrain 24/10 « AudioFlinger could not create record track, status: -1 ») : le premier clic Appeler/Décrocher affiche la demande système → ACCORDEZ-LA ; l'appelé ET l'appelant s'entendent ; si refusée/occupée, le DIAG dit exactement quoi (« micro REFUSÉ : permission… » / « micro INUTILISABLE : AudioRecord non initialisé ») et l'appel reste audible dans le sens qui marche"),
        Item("visio-camera", "VISIO SANS « no looper » (NOUVEAU v0.2.48) : appel visio → « caméra active (visio en émission) » dans le DIAG (l'ouverture caméra a désormais un HandlerThread dédié « nyx-cam » — avant : « caméra indisponible — visio audio-only : No handler given, and current thread has no looper! »)"),
        Item("depot-direct-pair", "DÉPÔT DIRECT AU PAIR PLUS JAMAIS REFUSÉ (NOUVEAU v0.2.48) : « dépôt direct événements : 1/1 ingérés par le pair » dans le DIAG de l'expéditeur (terrain 24/10 : -1/1 refusé — même cause que le décrochage) ; plus de « HELLO events REFUSÉ de <pair>… : contact non établi » pour un pair PENDING"),
        Item("hsfetch-port", "HSFETCH DE GUÉRISON OPÉRATIONNEL (NOUVEAU v0.2.48) : plus JAMAIS « HSFETCH … échoué : adresse onion invalide (65 ≠ 56) » (la guérison recevait l'adresse de réplica AVEC le port « .onion:80 » — strip du port désormais, le descripteur est vraiment re-demandé) ; après un déploiement nœud, réplica ONLINE en ~2-3 min"),
        Item("tirage-flotte", "TIRAGE NYX-LINK SANS 404 (NOUVEAU v0.2.48) : plus de « canal en échec vers <pair>… : GET /link/pull → HTTP 404 route inconnue » — le canal tire désormais UNIQUEMENT vers un réplica de FLOTTE (jamais l'embarqué d'un pair, qui n'implémente pas les routes /link)"),
        Item("sonde-muette", "SONDES SANS BRUIT (NOUVEAU v0.2.48) : les sondes de joignabilité (auto-sonde média, console) apparaissent comme « connexion muette fermée (sonde de joignabilité — aucun octet reçu) » en INFO — le « connexion sans HELLO valide » WARN ne sort PLUS que pour une vraie trame invalide"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS terrain 24/10 matin — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) » + AUCUN MORT en boucle + « auto-sonde média OK »"),
        Item("lien-inverse-console", "LIEN INVERSÉ — LA CARTE CONSOLE (v0.2.46, continue) : le direct doit maintenant marcher (v0.2.47+) — la carte « VIA LIEN INVERSÉ » n'est plus qu'un repli"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : déploiement nœud 0.2.38 confirmé terrain 24/10 06:42 — « ONION CLIENT adopté (déjà vivant — Detach) »"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_49 = listOf(
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR — ITEM DÉCISIF n°1 (NOUVEAU v0.2.49) : l'export de CHAQUE téléphone contient « câblage v0.2.49 vérifié : tirage NYX-LINK flotte seule, verrou média ACCEPTED||PENDING, HSFETCH normalisé, relance service jamais servi » (preuve que le dex contient bien les correctifs — l'APK v0.2.48 du 08:12 était un build MIXTE : la « reprise » sur mtimes ambiguës avait packagé des artefacts périmés, 3 correctifs sur 5 ABSENTS du dex, l'audit 26/26 ne voyait rien) ; SYMÉTRIQUEMENT, plus AUCUN symptôme périmé : plus de « HSFETCH … échoué : adresse onion invalide (65 ≠ 56) » et le canal inversé ne tire JAMAIS vers l'onion d'un pair"),
        Item("onion-confirme", "ONION VRAIMENT SERVI APRÈS CHAQUE DÉMARRAGE (NOUVEAU v0.2.49) : après « onion publié : <addr> », l'export montre « onion CONFIRMÉ servi par les HSDirs : <addr> (auto-sonde OK…) » en ≤ ~3 min ; si un démarrage a perdu la course aux microdescripteurs (circuits d'intro refusés au bootstrap), l'export montre « onion JAMAIS SERVI … — RELANCE DU SERVICE (DEL+ADD) » PUIS « onion CONFIRMÉ » (auto-guérison au lieu d'attendre le retry passif tor de 5 min — terrain 24/10 11:07 : Xiaomi 0/3 intro pendant TOUTE la fenêtre de test = cause profonde des appels impossibles)"),
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ — ITEM DÉCISIF n°2 (v0.2.48, reporté) : appel entre les 2 téléphones v0.2.49, l'appelé décroche → l'export de L'APPELÉ montre « appel entrant authentifié de <pair>… (cid …) » (le HELLO call accepté — JAMAIS vu depuis la v0.2.18 : le verrou contact du média était plus strict que celui de la sonnerie) ; la cause de TOUT refus est journalisée (signature ou contact)"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (v0.2.48, reporté) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio COULE EN DIRECT sans bascule relais"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (v0.2.48, reporté) : le premier clic Appeler/Décrocher affiche la demande système → ACCORDEZ-LA ; l'appelé ET l'appelant s'entendent ; si refusée/occupée, le DIAG dit exactement quoi et l'appel reste audible dans le sens qui marche"),
        Item("visio-camera", "VISIO SANS « no looper » (v0.2.48, reporté) : appel visio → « caméra active (visio en émission) » dans le DIAG (HandlerThread dédié « nyx-cam »)"),
        Item("depot-direct-pair", "DÉPÔT DIRECT AU PAIR PLUS JAMAIS REFUSÉ (v0.2.48, reporté) : « dépôt direct événements : 1/1 ingérés par le pair » ; plus de « HELLO events REFUSÉ … : contact non établi » pour un pair PENDING"),
        Item("hsfetch-port", "HSFETCH DE GUÉRISON OPÉRATIONNEL (v0.2.48, reporté) : plus JAMAIS « HSFETCH … échoué : adresse onion invalide (65 ≠ 56) »"),
        Item("tirage-flotte", "TIRAGE NYX-LINK SANS 404 NI VERS-LE-PAIR (v0.2.48, reporté) : le canal tire UNIQUEMENT vers un réplica de FLOTTE — plus de « canal en échec vers <pair>… » du tout : c'est le correctif de la « connexion aux réplicas un peu longue » du terrain 24/10 11:0x"),
        Item("sonde-muette", "SONDES SANS BRUIT (v0.2.48, reporté) : « connexion muette fermée (sonde de joignabilité…) » en INFO"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) » + AUCUN MORT en boucle"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : nœud 0.2.38 confirmé terrain 24/10 06:42"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    /** v0.2.61 — items de validation (miroir de docs/releases/v0.2.61.md —
     *  retour terrain 29/10 00:34 : les 6 points de l'opérateur). */
    val ITEMS_V0_2_61 = listOf(
        Item(
            "raccrocher-notification",
            "RACCROCHER DEPUIS LA NOTIFICATION — ITEM DÉCISIF n°1 (NOUVEAU v0.2.61, retour terrain 29/10 : « si l'app tel est réduite quand l'appel est reçu, obligé de réouvrir l'appli pour raccrocher ») : appel PC→tel, décrocher, puis RÉDUIRE l'appli (bouton accueil) → une notification « <alias> — Appel en cours » persiste avec un bouton RACCROCHER → l'appuyer TERMINE l'appel sans rouvrir l'appli ; l'export contient « raccroché DEPUIS LA NOTIFICATION (appel en cours) » et la notification disparaît"
        ),
        Item(
            "annulation-propagee",
            "APPEL ANNULÉ NE RESSUSCITE PAS — ITEM DÉCISIF n°2 (NOUVEAU v0.2.61, preuve 29/10 00:32 : appel PC annulé puis re-sonné + « actif » 7 s après le cancel) : appel visio PC→tel, tel appli réduite, ANNULER côté PC pendant la sonnerie ; au bout de ~1 min, réouvrir le tel : l'appel annulé NE RE-SONNE PAS (ou sonnerie brève immédiatement tuée par le BYE — JAMAIS de décrochage dans le vide) ; côté PC : JAMAIS « appel actif » après le cancel (le journal peut montrer « F_CALL_ACCEPT tardif IGNORÉ … : BYE au pair »)"
        ),
        Item(
            "sonnerie-des-le-hello",
            "SONNERIE DÈS LE HELLO (NOUVEAU v0.2.61, miroir bureau v0.3.10 — retour 29/10 : « l'application tel était réduite, pas de notification d'appel ») : appel PC→tel → le téléphone sonne (notification + sonnerie) NETTEMENT plus tôt qu'avant — à l'arrivée de la connexion média, sans attendre le poll de l'offre ; l'export contient « appel entrant DÈS LE HELLO (sonnerie immédiate — offre journal en confirmation » puis « offre journal en confirmation (déjà en INCOMING »"
        ),
        Item(
            "accuse-lu-notif",
            "ACQUISÉ « LU » PAR TAP SUR LA NOTIFICATION (NOUVEAU v0.2.61, retour 29/10 : « accusé reçu mais pas lu ») : envoyer un message PC→tel → taper la NOTIFICATION sur le téléphone : la CONVERSATION s'ouvre directement (plus jamais la liste) → le PC affiche ✓✓ lu sous le message en ≤ 30 s ; l'export tel contient « ✓ accusé « read » déposé »"
        ),
        Item(
            "reconnexion-media",
            "RECONNEXION MÉDIA EN COURS D'APPEL (NOUVEAU v0.2.61, preuve 29/10 00:22→00:30 : circuits Tor morts à 7-19 s — « la video s'est coupée ») : appel PC↔tel de PLUSIEURS minutes ; si le circuit tombe, l'écran d'appel affiche « reconnexion… » et l'export contient « média perdu en cours d'appel … — reconnexion » puis « média RÉTABLI — appel repris » (budget 2 cycles) ; sans retour du pair sous 60 s : fin honnête « connexion perdue »"
        ),
        Item(
            "build-propre-v61",
            "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.61 vérifié : SONNERIE DÈS LE HELLO authentifié » (chaîne complète du marqueur — cf. notes de release)"
        ),
    )

    /** v0.2.60 — items de validation (miroir de docs/releases/v0.2.60.md). */
    val ITEMS_V0_2_60 = listOf(
        Item(
            "ecran-valide-pc-vers-tel",
            "PARTAGE D'ÉCRAN VALIDÉ — ITEM DÉCISIF n°1 (NOUVEAU v0.2.60, demande opérateur verbatim session 78 : « partage d'écran dans les 2 sens UNIQUEMENT si validé des 2 côtés ») : appel visio AVEC le PC v0.3.12 → le PC appuie 🖥️ → SUR LE TÉLÉPHONE s'affiche « Le PC demande à partager son écran » avec ✓ Autoriser / ✕ Refuser ; ✓ → l'écran du PC remplace sa caméra ; ✕ → le PC garde sa caméra (message chez lui) ; pendant le partage le téléphone appuie son propre bouton d'arrêt → le PC REVIENT à sa caméra immédiatement"
        ),
        Item(
            "ecran-valide-tel-vers-pc",
            "PARTAGE D'ÉCRAN TÉLÉPHONE→PC — ITEM DÉCISIF n°2 (NOUVEAU v0.2.60) : pendant la visio, bouton 🖥️ de l'écran d'appel → « Demande envoyée — en attente du PC… » ; le PC valide → le DIALOGUE SYSTÈME Android « Capturer l'écran » apparaît (deuxième consentement) ; Autoriser → une notification « Nyx — partage d'écran actif » et l'écran du téléphone part en JPEG (~5 fps) vers le PC ; Refuser au dialogue système OU raccrocher → la caméra reprend, la notification disparaît ; sans validation PC : « Partage d'écran : le PC ne le valide pas (≥ v0.3.12 requis) »"
        ),
        Item(
            "camera-recuperation",
            "RÉCUPÉRATION CAMÉRA (NOUVEAU v0.2.60, preuve 5ᵉ export 28/10 20:16:55 : « caméra en erreur (3) » à 19 s d'une visio saine) : pendant une visio > 1 min, si la caméra meurt (erreur fatale 3/10 du HAL), l'export montre « TENTATIVE DE RÉCUPÉRATION 1/1 » puis « récupération caméra : réouverture effectuée » et l'émission REPREND ; en cas de second échec : dégradation honnête « émission visio coupée — la RÉCEPTION CONTINUE », l'appel tient"
        ),
        Item(
            "dedup-flotte",
            "DOUBLONS DE LIVRAISON ÉCRÊTÉS (NOUVEAU v0.2.60, miroir bureau — preuve 6ᵉ export 21:12→21:28 : la flotte re-livrait le MÊME journal à chaque poll) : dans l'export, AU PLUS UNE ligne « N doublon(s) de livraison ignoré(s) (eventId connu) » par cycle de poll, PLUS JAMAIS de dizaines de « ✓ annonce réplica (persistée) » identiques ; une offre d'appel terminé ne re-sonne pas (une seule sonnerie par appel réel)"
        ),
        Item(
            "build-propre-v60",
            "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.60 vérifié : PARTAGE D'ÉCRAN VALIDÉ DES DEUX CÔTÉS » (chaîne complète du marqueur — cf. notes de release)"
        ),
    )

    val ITEMS_V0_2_59 = listOf(
        Item("visio-bidirectionnelle", "LA VISIO TÉLÉPHONE↔PC PRODUIT UNE IMAGE DES DEUX CÔTÉS — ITEM DÉCISIF n°1 (NOUVEAU v0.2.59, retour terrain 28/10 17:24-17:25 : « visio tel => pc : je n'ai pas eu de video, idem dans l'autre sens » — preuve exports : « CaptureRequest contains unconfigured Input/Output Surface! » puis « CameraDevice was already closed » : la bascule H.264→MJPEG et le rattachement de l'aperçu UI SE COURSAIENT, la caméra perdue tuait AUSSI la vue du pair) : appel visio depuis OU vers le PC (bureau v0.3.11) → au décroché, le PC voit VOTRE caméra (portrait/paysage droit) ET vous voyez la webcam/écran du PC, PLUS D'UNE MINUTE sans crash ni bande noire ; l'aperçu LOCAL s'affiche aussi ; si votre caméra est reprise par le système, la VUE DU PAIR RESTE (message « Votre caméra est coupée — vous recevez toujours la vidéo », journal « émission visio coupée — la RÉCEPTION CONTINUE »)"),
        Item("messages-tel-vers-pc", "MESSAGES TÉLÉPHONE→PC : NOTIFIÉS CHEZ LE PAIR + ACCUSÉS CHEZ VOUS — ITEM DÉCISIF n°2 (NOUVEAU v0.2.59, retour terrain 28/10 17:26-17:30 : « envoi de message tel => pc : pas de notification coté pc, pas d'accusé de réception/lecture coté tel » — CAUSE RACINE PROUVÉE export 17:24:03 « HELLO events REFUSÉ : signature invalide » : le vérificateur recalculait la chaîne « events » avec le CID VIDE au lieu du COUNT signé → TOUT dépôt direct P2P était refusé) : envoyer un message au PC (bureau v0.3.11) → la bulle plateau apparaît CHEZ LE PC (même fenêtre réduite/arrière-plan) et l'accusé ✓ (réception) puis ✓✓ (lecture à l'ouverture) reviennent SUR VOTRE bulle en quelques secondes (journaux « accusés en attente : … » côté bureau)"),
        Item("media-vue-manuelle", "MÉDIA À VUE LIMITÉE : AFFICHAGE JUSQU'À FERMETURE MANUELLE — ITEM DÉCISIF n°3 (NOUVEAU v0.2.59, retour terrain 28/10 17:32 : « le média s'affiche et se referme de suite, il a été détruit sans que je ne puisse le voir. je veux pouvoir terminer l'affichage moi-même ») : recevoir un média à vue limitée DEPUIS le PC (bureau v0.3.11) → taper la bulle verrouillée → le média S'AFFICHE ET RESTE affiché (la bulle montre l'image depuis la mémoire, le fichier est détruit à la révélation) ; fermez la visionneuse VOUS-MÊME quand vous voulez (retour) ; l'image reste dans la conversation tant que vous n'en sortez pas ; en y revenant plus tard : carte « média expiré (vue(s) consommée(s)) »"),
        Item("regressions-appels-59", "Régressions appels : audio tel↔PC et tel↔tel, visio tel↔tel (H.264 inchangé), tonalité de retour d'appel (v0.2.58 conservée), décrochage verrouillé (v0.2.56/57 conservé), fin d'appel journalisée (« appel terminé (…) — motif »)"),
        Item("build-propre-v59", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.59 vérifié : CAMÉRA GARDE DE GÉNÉRATION » (chaîne complète du marqueur — cf. notes de release)"),
    )

    val ITEMS_V0_2_58 = listOf(
        Item("retour-appel-audible", "TONALITÉ DE RETOUR D'APPEL CHEZ L'APPELANT — ITEM DÉCISIF n°1 (NOUVEAU v0.2.58, demande opérateur verbatim 28/10 : « lors d'une tentative d'appel, je veux une sonnerie côté appelant pour indiquer que le tel ou pc appelle ») : appeler le PC (audio ou visio) → TONALITÉ bi-ton douce (1,5 s son / 3,5 s pause) pendant TOUTE la sonnerie du PC, COUPÉE À L'INSTANT où l'on décroche (journal « retour d'appel DÉMARRÉ » à l'appel et « retour d'appel STOPPÉ » au décroché) ; idem quand le PC appelle le téléphone (miroir bureau v0.3.10) ; refus/timeout/raccroché coupent aussi la tonalité"),
        Item("visio-orientation-resolution", "VISIO : L'ORIENTATION ET LA RÉSOLUTION SUIVENT LE TÉLÉPHONE — ITEM DÉCISIF n°2 (NOUVEAU v0.2.58, demande verbatim : « toujours un problème d'orientation de la video et de resolution qui devrait s'adapter à l'orientation ») : visio vers un PC en v0.3.10 → tenir le téléphone en PORTRAIT : l'image sur le PC est DROITE en 240x320 (journal « visio MJPEG démarrée PRÉ-TOURNÉE (240x320 portrait) ») ; TOURNER le téléphone en paysage : l'image reste DROITE et passe en 320x240 SANS coupure (journal « orientation suivie » puis trames à l'aspect paysage) ; AUCUN étirement, AUCUNE bande noire"),
        Item("notifications-propres", "PAS DE RE-NOTIFICATION : (miroir bureau v0.3.10, demande « sur pc, notifications incessantes de messages déjà traités » — côté téléphone : aucun symptôme attendu) : envoyer/recevoir des messages → UNE notification par message nouveau, AUCUNE bulle répétée pour un message déjà lu"),
        Item("regressions-appels", "Régressions appels : audio tel↔PC et tel↔tel, visio tel↔tel (H.264 inchangé), décrochage verrouillé (session 78/79 conservés), sonnerie entrante stoppée au décroché"),
        Item("build-propre-v58", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.58 vérifié : RETOUR D'APPEL audible chez l'appelant » (chaîne complète du marqueur — cf. notes de release)"),
    )

    val ITEMS_V0_2_57 = listOf(
        Item("visio-verrouille-tient", "LA VISIO TIENT QUAND ON DÉCROCHE VERROUILLÉ — ITEM DÉCISIF n°1 (NOUVEAU v0.2.57, preuve 3ᵉ export téléphone 28/10 11:49:59 : décroché visio DEPUIS LA NOTIFICATION, téléphone verrouillé → « CameraDevice was already closed » jeté par onConfigured sur le thread nyx-cam → FATAL EXCEPTION, process tué 0,6 s après le début de la visio) : appel visio ENTRANT → décrocher DEPUIS LA NOTIFICATION écran verrouillé → l'activité d'appel s'ouvre SUR le verrou (alias + durée) et l'appel RESTE ACTIF (audio + visio), AUCUN « FATAL EXCEPTION » dans l'export ; si la caméra est fermée par le système l'appel CONTINUE EN AUDIO (journal « visio coupée — l'appel CONTINUE en audio : … »), JAMAIS de crash"),
        Item("visio-decroche-notification", "LE BOUTON VISIO DE LA NOTIFICATION OUVRE L'ACTIVITÉ VERROUILLÉE (NOUVEAU v0.2.57, cause racine : le receiver silencieux décrochait SANS premier plan — Android ferme la caméra d'une appli en arrière-plan) : appel visio entrant, téléphone verrouillé → bouton Décrocher de la notification → l'ACTIVITÉ d'appel s'affiche immédiatement sur le verrou (journal « décroché DEPUIS LA NOTIFICATION (visio — activité verrouillée au premier plan, caméra autorisée) ») ; le bouton « Continuer dans l'application » y ramène après déverrouillage ; les appels AUDIO gardent le décroché SANS OUVRIR l'app (session 78 inchangée)"),
        Item("visio-tient-decroche", "LA VISIO PC↔TÉLÉPHONE TIENT APRÈS LE DÉCROCHÉ (v0.2.56, continue — preuve 28/10 : « visio MJPEG ACTIVE » des DEUX côtés à 11:49:58 puis FATAL 0,6 s après : cause racine caméra, pas de négociation) : appel visio depuis OU vers le PC → au décroché l'appel RESTE ACTIF des deux côtés, la caméra et l'écran partagé coulent PLUS D'UNE MINUTE sans crash"),
        Item("build-propre-v57", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.57 vérifié : caméra PERDUE = visio perdue PAS appel perdu » (chaîne complète du marqueur — cf. notes de release)"),
    )

    val ITEMS_V0_2_56 = listOf(
        Item("decroche-verrouille", "DÉCROCHER/REFUSER ÉCRAN VERROUILLÉ — ITEM DÉCISIF n°1 (NOUVEAU v0.2.56, demande opérateur 28/10 session 78 : « pouvoir décrocher ou rejeter un appel quand le téléphone est verrouillé sans devoir tout déverrouiller (tel/ordi, application ...) ») : téléphone ÉCRIN VERROUILLÉ + app verrouillée → appel entrant → l'écran S'ALLUME sur l'écran d'appel Nyx (alias + Décrocher/Refuser — intent plein écran) ; les boutons de la NOTIFICATION fonctionnent AUSSI sans déverrouiller ; décrocher → l'appel s'établit (audio) sans RIEN déverrouiller ; refuser → l'appel se termine ; l'overlay d'appel est visible AU-DESSUS du verrou de l'app (jamais de contenu de message — alias seul)"),
        Item("visio-tient-decroche", "LA VISIO PC↔TÉLÉPHONE TIENT APRÈS LE DÉCROCHÉ — ITEM DÉCISIF n°2 (NOUVEAU v0.2.56, preuve 5ᵉ export bureau 28/10 07:19 : le téléphone acceptait à 07:19:13 mais l'appel s'effondrait — trame de rotation AVANT le F_CALL_ACCEPT lue comme « pas de réponse » + process tué par l'ANR AudioRecord 4 s plus tard) : appel visio depuis OU vers le PC → au décroché l'appel RESTE ACTIF des deux côtés (« appel actif » chez le bureau, journal « appel actif avec … (côté appelé) »), l'émission ADPCM démarre (« émission ADPCM active (micro démarré, blocs 20 ms) » — plus AUCUN « Error -38 » dans le logcat), la caméra et l'écran partagé coulent"),
        Item("latence-audio-pc", "LATENCE AUDIO TÉLÉPHONE↔PC RÉDUITE (NOUVEAU v0.2.56, demande 28/10 : « les appels audio tel-pc ont un delay trop long ») : appel audio avec le PC → la conversation est FLUIDE (réponse quasi immédiate, plus de retard perceptible de une demi-seconde) ; après une rafale Tor le retard est RATTRAPÉ (« anti-arriéré : N bloc(s) sauté(s) pour rejoindre le direct ») au lieu de s'accumuler"),
        Item("build-propre-v56", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.2.56 vérifié : permissions caméra+micro+notification demandées ensemble au premier démarrage, APPEL DÉCROCHABLE/REFUSABLE ÉCRAN ET APP VERROUILLÉS » (chaîne complète du marqueur — cf. notes de release)"),
    )

    val ITEMS_V0_2_55 = listOf(
        Item("visio-pc-mjpeg", "VISIO AVEC LE PC — ITEM DÉCISIF n°1 (NOUVEAU v0.2.55, demande opérateur 28/10 : « appels visio ne fonctionnent pas entre pc et tel » — le bureau ne peut NI encoder NI décoder le H.264 MediaCodec en pur Java) : appel visio depuis OU vers le PC (bureau ≥ v0.3.7) → la CAMÉRA du téléphone s'affiche sur l'écran du PC ET l'ÉCRAN du PC s'affiche sur le téléphone, ~5 trames/s (JPEG logiciel négocié « mjpeg » — journal « visio MJPEG ACTIVE — interop bureau » / « codec vidéo MJPEG négocié — émission basculée ») ; l'appel AUDIO continue de fonctionner ; un téléphone ≤ v0.2.54 en face → le PC refuse proprement la visio, l'appel reste AUDIO"),
        Item("visio-tel-tel-inchange", "TÉLÉPHONE↔TÉLÉPHONE H.264 INCHANGÉ — ITEM DÉCISIF n°2 (interop : le champ « vc » du HELLO/ACCEPT est ABSENT entre téléphones — format v0.2.20 octet pour octet) : visio entre DEUX v0.2.55 → image H.264 comme avant, rotation suivie, AUCUNE régression"),
        Item("accuses-unique", "ACCUSÉS SANS DOUBLE ÉMISSION (NOUVEAU v0.2.55, preuve 4ᵉ export : l'accusé « read » partait DEUX FOIS à 1 ms d'intervalle — le pair voyait double) : dans l'export, chaque « ✓ accusé « read » déposé » apparaît UNE SEULE fois même en ouvrant la conversation pendant un cycle de poll"),
        Item("accuses-id-realigne", "ID RE-ALIGNÉ AU RESYNC (NOUVEAU v0.2.55, miroir desktop v0.3.7 : après un « 400 → re-signé, rejoué », l'événement déposé porte un NOUVEL id — la ligne locale garde l'ancien et l'accusé du PC pointait dans le vide) : un message parti avec resync (log « id local re-aligné sur le dépôt resync ») → le ✓✓ du PC ARRIVE sous la bulle"),
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export de CHAQUE téléphone contient « câblage v0.2.55 vérifié : permissions caméra+micro+notification demandées ensemble au premier démarrage, orientation vidéo suivie (rotation capteur→écran » (chaîne complète du marqueur — cf. notes de release)"),
    )

    val ITEMS_V0_2_53 = listOf(
        Item("permission-precoce", "LES PERMISSIONS SONT DEMANDÉES AU PREMIER DÉMARRAGE — ITEM DÉCISIF n°1 (NOUVEAU v0.2.53, demande opérateur 25/10 08:41 : « l'appel video n'a fonctionné qu'à partir du moment où je l'ai lancé du Xiaomi et que j'ai eu la demande d'autorisation d'utiliser la caméra. il faudrait que les demandes d'autorisation soient faites dès l'installation de l'application » — cause racine : Xiaomi « permission caméra absente — visio audio-only » à 08:39:59 pendant l'appel visio ENTRANT du Blackview, la permission caméra n'était demandée qu'au clic visio côté APPELANT, JAMAIS au démarrage ni côté appelé) : au PREMIER lancement (ou après mise à jour si pas encore accordées), UNE SEULE boîte système demande caméra + micro (+ notification) — l'export montre « permissions demandées au premier démarrage : … » puis au démarrage suivant « permissions de démarrage déjà toutes accordées (notif, caméra, micro) » ; une visio reçue ou émise MARCHE DÈS LA PREMIÈRE utilisation, sans dégradation audio-only"),
        Item("orientation-suivie", "LA VIDÉO EST DROITE ET SUIT LE TÉLÉPHONE — ITEM DÉCISIF n°2 (NOUVEAU v0.2.53, demande opérateur 25/10 : « l'image video a une orientation d'écran figée, il faudrait qu'elle suive l'orientation du téléphone (et que la résolution s'adapte en conséquence) » — l'image partait en orientation CAPTEUR, couchée à 90° sur un téléphone en portrait, et ne suivait jamais l'écran : aucune lecture de SENSOR_ORIENTATION, décodeur à rotation-degrees = 0) : appel visio entre les DEUX v0.2.53 → l'image distante est DROITE (plus couchée) dans TOUTES les orientations ; tourner le téléphone en cours d'appel → l'image QUE VOIT LE PAIR suit (portrait ↔ paysage, résolution affichée adaptée 240×320 ↔ 320×240) SANS AUCUNE COUPURE (matrice de présentation, le flux H.264 ne change pas) ; l'aperçu local est droit et en MIROIR naturel (caméra front) ; les exports montrent « orientation initiale X° (capteur Y° …) — suit désormais le téléphone » puis à chaque rotation « orientation suivie : X° → Y° (écran tourné) — F_VIDEO_ROT au pair » chez celui qui tourne et « orientation du pair suivie : Y° » chez l'autre"),
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export de CHAQUE téléphone contient « câblage v0.2.54 vérifié : permissions caméra+micro+notification demandées ensemble au premier démarrage, orientation vidéo suivie (rotation capteur→écran » (chaîne complète du marqueur — cf. notes de release)"),
        Item("sonnerie-stop-decroche", "LA SONNERIE S'ARRÊTE AU DÉCROCHÉ (v0.2.52, continue — acquis terrain 25/10 08:39-08:41 : « sonnerie STOPPÉE » ×5 dans les 2 exports) : appel → décrocher → la sonnerie S'ARRÊTE À L'INSTANT, silence pendant la conversation"),
        Item("visio-image", "LA VISIO PORTE UNE IMAGE (v0.2.52, continue — acquis terrain 25/10 08:41 : « csd H.264 LU APRÈS LA 1ʳᵉ TRAME (30 o) » ×2, « visio ACTIVE » des deux côtés) : appel visio → les DEUX écrans montrent l'image distante"),
        Item("raccroche-libre", "RACCROCHER LIBÈRE TOUJOURS, MÊME TOR MORT (v0.2.52, continue) : pendant un appel sortant qui sonne dans le vide, Raccrocher referme l'écran d'appel EN MOINS D'UNE SECONDE"),
        Item("tor-relance", "TOR RELANCÉ AUTOMATIQUEMENT APRÈS MORT DU DÉMON (v0.2.52, continue) : si le démon meurt, « démon tor MORT … — RELANCE AUTOMATIQUE n/3 » puis l'onion se RE-PUBLIE"),
        Item("appel-tient", "L'APPEL TIENT APRÈS LE DÉCROCHÉ (v0.2.51, continue) : décrocher → l'appel RESTE ACTIF, l'audio coule dans les DEUX SENS, « csd-0 AAC CALCULÉ (1408) », AUCUN « work failed to complete: 14 »"),
        Item("lien-resync", "DÉPÔT AU PAIR PAR LIEN RESYNCHRONISÉ (v0.2.52, continue) : si « seq N refusé (attendu M) — re-signé, rejoué » apparaît, le dépôt aboutit"),
        Item("csd-calcule", "CSD-0 CALCULÉ VISIBLE (v0.2.51, continue) : « csd-0 AAC CALCULÉ (1408) » juste après la mise en marche de l'encodeur"),
        Item("sonnerie-retablie", "LA SONNERIE SONNE (v0.2.50, continue) : « appel entrant de <pair>… (cid …) » chez l'appelé + « sonnerie DÉMARRÉE »"),
        Item("onion-confirme", "ONION VRAIMENT SERVI APRÈS CHAQUE DÉMARRAGE (v0.2.49, reporté) : « onion CONFIRMÉ servi par les HSDirs » ≤ ~3 min après « onion publié »"),
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ (v0.2.48, reporté) : « appel entrant authentifié de <pair>… (cid …) »"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (v0.2.48, reporté) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio coule en direct"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (v0.2.48, continue — désormais AU DÉMARRAGE en plus du clic) : les deux sens s'entendent"),
        Item("visio-camera", "VISIO SANS « no looper » (v0.2.48, reporté) : « caméra active (visio en émission) »"),
        Item("poll-local", "POLL PROPRE + FLOTTE SANS JOURNAL DE PAIR (v0.2.50, continue) : plus aucun « réplica <pair>… en échec (poll inbox…) » quand le pair s'éteint"),
        Item("raccroche-sans-crash", "RACCROCHER NE TUE PLUS L'APP (v0.2.50, continue) : aucun « FATAL EXCEPTION » dans l'export"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) »"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : nœud 0.2.38 confirmé terrain"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_52 = listOf(
        Item("sonnerie-stop-decroche", "LA SONNERIE S'ARRÊTE AU DÉCROCHÉ — ITEM DÉCISIF n°1 (NOUVEAU v0.2.52, cause racine du terrain 25/10 06:38 « Xiaomi continue de sonner alors que l'appel est décroché » : la sonnerie était le SON DU CANAL de notification, annulé seulement au RACCROCHÉ — 19 s de sonnerie PAR-DESSUS l'appel actif mesurées sur l'appel n°1) : appel audio → décrocher → la sonnerie S'ARRÊTE À L'INSTANT du décroché, l'audio coule proprement ; dans l'export de l'appelé : « sonnerie DÉMARRÉE (boucle possédée…) » à l'offre PUIS « sonnerie STOPPÉE » juste après « acceptation utilisateur » — et PLUS JAMAIS de sonnerie pendant la conversation"),
        Item("visio-image", "LA VISIO PORTE UNE IMAGE — ITEM DÉCISIF n°2 (NOUVEAU v0.2.52, cause racine « l'appel visio ne fonctionne pas (audio seulement) » : « visio annoncée dans le HELLO (csd 0 o) » — le csd H.264 était lu AVANT la 1ʳᵉ trame caméra, l'encodeur à surface d'entrée n'en expose AUCUN avant : csd VIDE → l'appelé voyait vcsd vide et dégradait l'appel en audio-only) : appel visio → les DEUX écrans montrent l'image distante (aperçu local + rendu distant) ; dans l'export de l'appelant : « csd H.264 LU APRÈS LA 1ʳᵉ TRAME (N o) » (plus JAMAIS « csd 0 o ») et « visio ACTIVE », chez l'appelé « visio ACTIVE côté appelé » — si la caméra ne livre rien : « csd H.264 TOUJOURS VIDE … visio AUDIO-ONLY explicite » (repli honnête, jamais une visio morte annoncée)"),
        Item("raccroche-libre", "RACCROCHER LIBÈRE TOUJOURS, MÊME TOR MORT — ITEM DÉCISIF n°3 (NOUVEAU v0.2.52, cause racine du 1ᵉʳ appel 25/10 « Blackview semblait bloqué, impossible d'arrêter la tentative d'appel sans tout fermer » : end() attendait le dépôt OP_BYE qui traînait 25-50 s sur le SOCKS refusé — 16 dépôts martelés en 14 s, puis fermeture forcée) : pendant un appel sortant qui sonne dans le vide, Raccrocher referme l'écran d'appel EN MOINS D'UNE SECONDE, re-taper dessus ne fait rien (garde phases vivantes) ; l'app reste utilisable et un NOUVEL appel passe dès que tor est remis (cf. tor-relance)"),
        Item("tor-relance", "TOR RELANCÉ AUTOMATIQUEMENT APRÈS MORT DU DÉMON (NOUVEAU v0.2.52, terrain 25/10 05:34 : démon mort, ECONNREFUSED 127.0.0.1:9050 pendant 25 s+, app vivante mais sourde jusqu'à « tout fermer ») : si le démon meurt, l'export montre « démon tor MORT (code X, vécu N s) — RELANCE AUTOMATIQUE n/3 » puis l'onion se RE-PUBLIE dans la foulée ; les arrêts volontaires (Quitter complètement, écran Connexions) ne déclenchent AUCUNE relance"),
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR : l'export de CHAQUE téléphone contient « câblage v0.2.52 vérifié : sonnerie possédée (boucle stoppée au décroché, canal silencieux), csd H.264 lu après la 1ʳᵉ trame caméra » (chaîne complète du marqueur — cf. notes de release)"),
        Item("appel-tient", "L'APPEL TIENT APRÈS LE DÉCROCHÉ (v0.2.51, continue) : décrocher → l'appel RESTE ACTIF, l'audio coule dans les DEUX SENS, « csd-0 AAC CALCULÉ (1408) », AUCUN « work failed to complete: 14 »"),
        Item("lien-resync", "DÉPÔT AU PAIR PAR LIEN RESYNCHRONISÉ (NOUVEAU v0.2.52, terrain 05:35:02 « POST /client/event → 400 servi LOCALEMENT » : le repli lien abandonnait sur un refus de séquence, l'événement était PERDU) : si l'export montre « dépôt au pair par LIEN : seq N refusé (attendu M) — re-signé, rejoué » puis « ✓ dépôt au pair … [après resynchronisation de séquence] », l'offre/BYE est bien arrivé"),
        Item("csd-calcule", "CSD-0 CALCULÉ VISIBLE (v0.2.51, continue) : « csd-0 AAC CALCULÉ (1408) » juste après la mise en marche de l'encodeur"),
        Item("sonnerie-retablie", "LA SONNERIE SONNE (v0.2.50, continue) : « appel entrant de <pair>… (cid …) » chez l'appelé + « sonnerie DÉMARRÉE »"),
        Item("onion-confirme", "ONION VRAIMENT SERVI APRÈS CHAQUE DÉMARRAGE (v0.2.49, reporté) : « onion CONFIRMÉ servi par les HSDirs » ≤ ~3 min après « onion publié »"),
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ (v0.2.48, reporté) : « appel entrant authentifié de <pair>… (cid …) »"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (v0.2.48, reporté) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio coule en direct"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (v0.2.48, reporté) : le premier clic Appeler/Décrocher affiche la demande système → ACCORDEZ-LA"),
        Item("visio-camera", "VISIO SANS « no looper » (v0.2.48, reporté) : « caméra active (visio en émission) »"),
        Item("poll-local", "POLL PROPRE + FLOTTE SANS JOURNAL DE PAIR (v0.2.50, continue) : plus aucun « réplica <pair>… en échec (poll inbox…) » quand le pair s'éteint"),
        Item("raccroche-sans-crash", "RACCROCHER NE TUE PLUS L'APP (v0.2.50, continue) : aucun « FATAL EXCEPTION » dans l'export"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) »"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : nœud 0.2.38 confirmé terrain"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_51 = listOf(
        Item("appel-tient", "L'APPEL TIENT APRÈS LE DÉCROCHÉ — ITEM DÉCISIF n°1 (NOUVEAU v0.2.51, cause racine du terrain 25/10 04:58 « ça décroche mais l'appel coupe immédiatement » : 3 appels × 2 téléphones, 6/6 effondrés à 300-700 ms du « actif ») : appel entre les 2 téléphones v0.2.51 → décrocher → l'appel RESTE ACTIF et l'AUDIO COULE dans les DEUX SENS ; dans l'export de chaque téléphone : « appel actif » SANS effondrement qui suit, l'AudioTrack livre des frames (plus « 0 frames delivered »), et AUCUN « work failed to complete: 14 » ni « Invalid to call at Released state » (la signature de l'effondrement). RAPPEL DE LA PANNE : le csd-0 AAC annoncé au pair était un « csd factice » {0,0} — la lecture d'outputFormat juste après start() rend null sur les codecs C2 (Android 12+) — et ce csd invalide TUAIT le décodeur du pair au démarrage ; sa 1ʳᵉ trame F_AUDIO levait IllegalStateException → l'appel s'effondrait sans que l'app meure (aucun FATAL : le raccroché v0.2.50 tient)"),
        Item("csd-calcule", "CSD-0 CALCULÉ VISIBLE (NOUVEAU v0.2.51) : dans l'export, juste après la mise en marche de l'encodeur : « csd-0 AAC CALCULÉ (1408) — le codec ne l'expose pas encore au démarrage (C2 : null) : plus de csd factice qui tue le décodeur du pair » (l'AudioSpecificConfig est désormais une fonction pure du format — LC/16 kHz/mono — plus aucune course au format du codec)"),
        Item("csd-pair-assaini", "INTEROP v0.2.51 ↔ v0.2.50 (NOUVEAU v0.2.51) : si UN des téléphones reste en v0.2.50, l'appel TIENT quand même — l'export du v0.2.51 montre « csd-0 pair INVALIDE (2 o) — remplacé par le calcul local (1408) : pair v0.2.50 ? décodeur sauvé, appel maintenu » (la config audio étant FIXE des deux côtés, le csd local calculé est toujours le bon)"),
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR — ITEM DÉCISIF n°2 : l'export de CHAQUE téléphone contient « câblage v0.2.51 vérifié : csd-0 AAC calculé (plus de csd factice qui tue le décodeur du pair), offre sur la boîte du pair puis flotte, poll réplica propre + flotte, raccroché et refus hors thread UI, tirage NYX-LINK flotte seule, verrou média ACCEPTED||PENDING »"),
        Item("sonnerie-retablie", "LA SONNERIE SONNE (v0.2.50, continue) : « appel entrant de <pair>… (cid …) » chez l'appelé ; « offre reçue — connexion média déjà là, adoptée » = décrochage instantané"),
        Item("raccroche-sans-crash", "RACCROCHER NE TUE PLUS L'APP (v0.2.50, continue) : Raccrocher/Refuser pendant sonnerie ou appel actif → retour propre à la conversation, AUCUN « FATAL EXCEPTION » dans l'export"),
        Item("onion-confirme", "ONION VRAIMENT SERVI APRÈS CHAQUE DÉMARRAGE (v0.2.49, reporté) : « onion CONFIRMÉ servi par les HSDirs » ≤ ~3 min après « onion publié » (ou « JAMAIS SERVI → RELANCE DU SERVICE » puis CONFIRMÉ)"),
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ (v0.2.48, reporté) : « appel entrant authentifié de <pair>… (cid …) » — le HELLO call accepté, la cause de tout refus journalisée"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (v0.2.48, reporté) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio COULE EN DIRECT"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (v0.2.48, reporté) : le premier clic Appeler/Décrocher affiche la demande système → ACCORDEZ-LA ; les deux sens s'entendent"),
        Item("visio-camera", "VISIO SANS « no looper » (v0.2.48, reporté) : appel visio → « caméra active (visio en émission) » + « visio annoncée dans le HELLO »"),
        Item("poll-local", "POLL PROPRE + FLOTTE SANS JOURNAL DE PAIR (v0.2.50, continue) : plus aucun « réplica <pair>… en échec (poll inbox…) » quand le pair s'éteint"),
        Item("depot-direct-pair", "DÉPÔT DIRECT AU PAIR PLUS JAMAIS REFUSÉ (v0.2.48, reporté) : « dépôt direct événements : 1/1 ingérés par le pair »"),
        Item("hsfetch-port", "HSFETCH DE GUÉRISON OPÉRATIONNEL (v0.2.48, reporté) : plus JAMAIS « HSFETCH … échoué : adresse onion invalide (65 ≠ 56) »"),
        Item("tirage-flotte", "TIRAGE NYX-LINK SANS 404 NI VERS-LE-PAIR (v0.2.48, reporté) : le canal tire UNIQUEMENT vers un réplica de flotte"),
        Item("sonde-muette", "SONDES SANS BRUIT (v0.2.48, reporté) : « connexion muette fermée (sonde de joignabilité…) » en INFO"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) » + AUCUN MORT en boucle"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : nœud 0.2.38 confirmé terrain 24/10 06:42"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    val ITEMS_V0_2_50 = listOf(
        Item("sonnerie-retablie", "LA SONNERIE SONNE DE NOUVEAU — ITEM DÉCISIF n°1 (NOUVEAU v0.2.50, cause racine du terrain 24/10 11:47 « appels ne sonnent plus ») : appel entre les 2 téléphones v0.2.50 → l'appelé SONNE (notification + overlay entrant) et son export montre « appel entrant de <pair>… (cid …) » ; le partage de preuve : chez l'APPELANT « signalisation d'appel → boîte du pair <addr>… » ou « → flotte <addr>… », chez l'APPELÉ « ← inbox PROPRE n évts » juste avant la sonnerie. RAPPEL DE LA PANNE : chaque téléphone POLLAIT le réplica embarqué DE L'AUTRE (fusion « découverts d'abord ») — l'offre dormait dans le journal local de l'appelé pendant qu'il interrogeait celui de l'appelant : 3 appels, 0 sonnerie"),
        Item("raccroche-sans-crash", "RACCROCHER NE TUE PLUS L'APP — ITEM DÉCISIF n°2 (NOUVEAU v0.2.50) : pendant une sonnerie sortante (ou un appel actif), taper Raccrocher (et Refuser sur un entrant) → l'app RESTE VIVANTE (retour propre à la conversation) ; l'export NE CONTIENT AUCUN « FATAL EXCEPTION : NetworkOnMainThreadException » (terrain 24/10 : DEUX crashes en 80 s — conn.send sur le thread UI à chaque abandon d'appel, l'app emportait tor + réplica et le pair voyait son poll expirer 29 s)"),
        Item("build-propre", "BUILD PROPRE PREUVE PAR MARQUEUR — ITEM DÉCISIF n°3 : l'export de CHAQUE téléphone contient « câblage v0.2.50 vérifié : offre sur la boîte du pair puis flotte, poll réplica propre + flotte, raccroché et refus hors thread UI, tirage NYX-LINK flotte seule, verrou média ACCEPTED||PENDING »"),
        Item("onion-confirme", "ONION VRAIMENT SERVI APRÈS CHAQUE DÉMARRAGE (v0.2.49, reporté) : « onion CONFIRMÉ servi par les HSDirs » ≤ ~3 min après « onion publié » (ou « JAMAIS SERVI → RELANCE DU SERVICE » puis CONFIRMÉ)"),
        Item("appel-authentifie", "APPEL AUTHENTIFIÉ CHEZ L'APPELÉ (v0.2.48, reporté) : « appel entrant authentifié de <pair>… (cid …) » — le HELLO call accepté, la cause de tout refus journalisée"),
        Item("media-45s", "MÉDIA DIRECT ÉTABLI (v0.2.48, reporté) : « média essai 1 (45 s) → <pair>…:47471 » puis l'audio COULE EN DIRECT ; avec la sonnerie rétablie, l'offre arrive PENDANT la fenêtre → « offre reçue — connexion média déjà là, adoptée (cid …) » = décrochage INSTANTANÉ"),
        Item("micro-permission", "MICRO DEMANDÉ ET OUVERT (v0.2.48, reporté) : le premier clic Appeler/Décrocher affiche la demande système → ACCORDEZ-LA ; les deux sens s'entendent"),
        Item("visio-camera", "VISIO SANS « no looper » (v0.2.48, reporté) : appel visio → « caméra active (visio en émission) » + « visio annoncée dans le HELLO »"),
        Item("poll-local", "POLL PROPRE + FLOTTE SANS JOURNAL DE PAIR (NOUVEAU v0.2.50) : l'export ne montre PLUS AUCUN « réplica <pair>… en échec (poll inbox…) » quand le pair s'éteint/plante (le poll ne touche plus jamais son journal) ; la « connexion aux replicas un peu longue » du terrain se résorbe : la lecture propre est en procédé (~ms), seule la flotte passe par Tor"),
        Item("depot-direct-pair", "DÉPÔT DIRECT AU PAIR PLUS JAMAIS REFUSÉ (v0.2.48, reporté) : « dépôt direct événements : 1/1 ingérés par le pair »"),
        Item("hsfetch-port", "HSFETCH DE GUÉRISON OPÉRATIONNEL (v0.2.48, reporté) : plus JAMAIS « HSFETCH … échoué : adresse onion invalide (65 ≠ 56) »"),
        Item("tirage-flotte", "TIRAGE NYX-LINK SANS 404 NI VERS-LE-PAIR (v0.2.48, reporté) : le canal tire UNIQUEMENT vers un réplica de flotte"),
        Item("sonde-muette", "SONDES SANS BRUIT (v0.2.48, reporté) : « connexion muette fermée (sonde de joignabilité…) » en INFO"),
        Item("bind-ipv4-preuve", "BIND IPv4 (v0.2.47, ACQUIS — continue) : « réplica temporaire prêt (local 127.0.0.1:PORT) » + « écouteur direct prêt (local 127.0.0.1:PORT) » + AUCUN MORT en boucle"),
        Item("serveur-detach", "SERVEUR DÉPLOYÉ SANS FENÊTRE ORPHELINE (v0.2.37+, continue) : nœud 0.2.38 confirmé terrain 24/10 06:42"),
        Item("replica-flotte-joignable", "SANITY : messages + sonnerie d'appel passent par le réplica de flotte (offre ≤ 15 s), carte réplica ONLINE en console"),
        Item("conflux-zero", "TOR SANS CONFLUX (validation continue) : AUCUN « path restriction type 2 » dans le tor.log au bootstrap"),
        Item("maj-auto-test", "MAJ AUTO CANAL TEST (validation continue) : à la publication de CETTE release, la version TEST précédente la détecte en ≤ 30 min, l'INSTALLATION aboutit"),
        Item("logs-arrivent", "Bouton rouge (build test, carte dépliée) : l'export de logs ARRIVE dans le dossier Drive — l'état reste visible MÊME CARTE REPLIÉE (validation continue)"),
        Item("clavier", "Clavier ouvert (ajout contact, chat, réglages, verrou) : le bas de la page reste visible (validation continue)"),
        Item("regressions-msg", "Régressions : messages texte + accusés ✓✓ + média à vue limitée + blocage/déblocage"),
    )

    fun items(): List<Item> = ITEMS_V0_2_61 + ITEMS_V0_2_60 + ITEMS_V0_2_59 + ITEMS_V0_2_58 + ITEMS_V0_2_57 + ITEMS_V0_2_56 + ITEMS_V0_2_55 + ITEMS_V0_2_53


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
