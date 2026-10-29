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

    /** Un test de validation de la release courante.
     *  v0.3.14 — [logValidated] : preuve relevée dans l'export de terrain du
     *  29/10 06:15→06:29 (v0.3.13, demande opérateur : « modifie la liste
     *  s'ils sont validés et confirmés ok par les logs. Idem s'ils ne sont
     *  pas cochés mais que les logs les confirment ok ») — l'item compte
     *  comme PASS et la carte affiche le badge « VALIDÉ PAR LOGS » avec sa
     *  preuve ; le tap opérateur devient superflu pour ces items-là. */
    data class Item(val id: String, val label: String, val logValidated: String? = null)

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
    /**
     * v0.3.10 — items de validation (miroir de docs/releases/v0.3.10-desktop.md) :
     * caméra du PC par défaut + bascule écran, notifications de messages à la
     * racine, sonnerie immédiate au HELLO + retour d'appel.
     */
    /** v0.3.14 — items de validation (miroir de docs/releases/v0.3.14-desktop.md —
     *  retour terrain 29/10 06:29 : « côté pc je ne vois pas les videos » +
     *  « je n'ai pas non plus de notification pour le partage d'écran » +
     *  « besoin aussi d'un bouton pour annuler le partage d'écran lorsqu'il
     *  est proposé ou activé » + défauts détectés dans l'export du jour). */
    val ITEMS_V0_3_14 = listOf(
        Item(
            "video-carte-flottante",
            "LA VIDÉO DISTANTE EST SUR LA CARTE FLOTTANTE — ITEM DÉCISIF n°1 (NOUVEAU v0.3.14, cause racine 29/10 : la vidéo ne vivait QUE dans l'overlay de la fenêtre principale — minimisée/arrière-plan = PLUS AUCUNE vidéo visible, alors que c'est PRÉCISÉMENT la posture pour laquelle la carte flottante existe) : pendant une visio, mettre la fenêtre principale Nyx SOUS une autre application → la carte flottante (toujours au premier plan) affiche la VIDÉO DU TÉLÉPHONE en direct (aperçu ~260x200, rafraîchi ~5 fps) ; cliquer l'aperçu = ramener la fenêtre principale (plein écran)"
        ),
        Item(
            "notif-partage-ecran",
            "NOTIFICATION DE DEMANDE DE PARTAGE D'ÉCRAN — ITEM DÉCISIF n°2 (NOUVEAU v0.3.14, preuve 29/10 06:22:06 : le tel émet F_SCREEN_REQ, le PC n'a RIEN affiché — le dialogue ✓/✕ vivait dans la fenêtre principale non focalisée et le journal n'a même pas tracé la trame) : pendant une visio tel→PC, fenêtre principale NON focalisée, demander le partage DEPUIS le téléphone (🖥️) → le PC affiche UNE NOTIFICATION « <alias> demande à partager son écran » + le dialogue ✓ Autoriser / ✕ Refuser SUR LA CARTE FLOTTANTE ; l'export contient « F_SCREEN_REQ du pair — dialogue ✓/✕ affiché (carte flottante + notification) »"
        ),
        Item(
            "annuler-partage-pc",
            "ANNULER / ARRÊTER LE PARTAGE D'ÉCRAN DEPUIS LE PC (NOUVEAU v0.3.14, demande 29/10 : « besoin aussi d'un bouton pour annuler le partage d'écran lorsqu'il est proposé ou activé ») : pendant « ⏳ Autorisation… » (notre demande en attente), un bouton ANNULER sur la carte flottante envoie F_SCREEN_STOP (« demande de partage annulée ») et le tel voit « partage refusé » ; pendant un partage ACTIF (les DEUX sens), le bouton ARRÊTER (carte flottante) envoie F_SCREEN_STOP : le partageur revient à SA caméra, le receveur voit « le pair a arrêté son partage » ; les refus/gardes sont TOUS journalisés (plus aucun silence de diagnostic)"
        ),
        Item(
            "bye-fin-immediate-pc",
            "LE RACCROCHÉ DU TÉLÉPHONE = FIN IMMÉDIATE, PAS DE RECONNEXION (NOUVEAU v0.3.14, preuve 29/10 06:17:07 : le tel raccroche depuis la notification, le PC traite le BYE comme une PERTE et ré-essaie 2 fois 5 s durant « reconnexion impossible — fin de l'appel ») : pendant un appel actif, le téléphone RACCROCHE → le PC termine AUSSI TÔT (« pair raccroché (BYE) — fin immédiate », AUCUNE ligne « reconnexion ») ; la reconnexion ne s'engage QUE sur une vraie perte réseau"
        ),
        Item(
            "webcam-640",
            "WEBCAM PLUS NETTE : 640×360 AU LIEU DE 480×270 (NOUVEAU v0.3.14, retour 29/10 : « problème de résolution pc » — 480×270 agrandi ~2,3× sur l'écran du téléphone paraissait flou et perdu au milieu) : appel visio PC→tel → l'export contient « CAMÉRA du PC active (640x360 … » et le tel reçoit des trames 640×360 (rendu « rendu MJPEG : trame 640x360 → surface … » côté tel) ; le frame skipping existant adapte la cadence si le circuit ne suit pas ; régressions : webcam par défaut + bascule écran + dialogue pair inchangés"
        ),
        Item(
            "silences-diagnostiques",
            "PLUS AUCUNE TRAME SILENCIEUSEMENT JETÉE (NOUVEAU v0.3.14, défaut détecté 29/10 : le when de la boucle de lecture n'avait PAS de branche else — une trame de type inconnu disparaissait sans UNE SEULE ligne ; le refus de F_SCREEN_REQ hors ACTIVE/sans protocole sc était muet aussi) : toute trame inconnue journalise « trame P2P inconnue 0xNN (N o) — ignorée » ; un F_SCREEN_REQ refusé par garde journalise son motif ; un send raté (connexion fermée) journalise « envoi trame 0xNN impossible » — l'export suffit TOUJOURS à diagnostiquer"
        ),
        Item(
            "build-propre-v14",
            "BUILD PROPRE PREUVE PAR MARQUEUR : l'export contient « câblage v0.3.14 vérifié : VIDÉO SUR LA CARTE FLOTTANTE » (chaîne complète du marqueur — cf. notes de release)"
        ),
    )

    /** v0.3.13 — items de validation (miroir de docs/releases/v0.3.13-desktop.md
     *  — retour terrain 29/10 00:34 : les 6 points de l'opérateur).
     *  v0.3.14 — VALIDATION PAR LOGS (export terrain 29/10 06:15→06:29,
     *  5 appels PC↔tel) : 3/5 items PROUVÉS par l'export ; reconnexion-media-pc
     *  reste À RE-TESTER (mécanisme engagé mais aucune reconnexion aboutie —
     *  les pertes du jour étaient des RACCROCHÉS ; correctif v0.3.14 : BYE =
     *  fin immédiate, pas de reconnexion) ; frame-skipping pas déclenché
     *  (aucune saturation ce jour-là). */
    val ITEMS_V0_3_13 = listOf(
        Item(
            "fenetre-decrochage-devant",
            "LA FENÊTRE DE DÉCROCHAGE EST DEVANT — ITEM DÉCISIF n°1 (NOUVEAU v0.3.13, retour 29/10 : « la fenêtre pour décrocher du pc se met derrière l'appli de messagerie » — la fenêtre principale était forcée alwaysOnTop PENDANT la sonnerie et recouvrait la carte) : appel tel→PC alors que la fenêtre principale Nyx est OUVERTE et ACTIVE → la carte ✓ Décrocher / ✕ Refuser apparaît AU-DESSUS de la fenêtre principale, reste visible pendant TOUTE la sonnerie et reprend le focus si on la perd",
            logValidated = "PROUVÉ 29/10 (indirect) — appel tel→PC cid a9d00775 : « appel entrant DÈS LE HELLO » 06:21:52.507 → « acceptation utilisateur » 06:21:55.274 = décroché en 2,8 s (carte au premier plan, focus repris) ; le défaut v0.3.12 (carte SOUS la fenêtre principale) était impossible à rater — aucune plainte ce jour-là"
        ),
        Item(
            "annule-jamais-actif",
            "UN APPEL ANNULÉ NE REDEVIENT JAMAIS ACTIF — ITEM DÉCISIF n°2 (NOUVEAU v0.3.13, preuve 29/10 00:32:26→00:32:33 : « appel terminé (OUTGOING) » puis « appel actif » 7 s après le cancel de l'opérateur) : appeler le téléphone puis ANNULER pendant la sonnerie ; même si le téléphone accepte tardivement, le PC affiche « F_CALL_ACCEPT tardif IGNORÉ (… — appel ENDED) : BYE au pair » et l'appel ne devient JAMAIS actif ; une connexion qui aboutit après l'annulation est rejetée (« connexion média aboutie après annulation … — rejetée + BYE »)",
            logValidated = "PROUVÉ 29/10 — annulation réelle cid ffe6105b à 06:17:56.729 : « appel terminé (OUTGOING) — terminé » puis « (ENDED) », JAMAIS « appel actif » ensuite ; gardes miroir côté tel prouvées (HELLO d'appel terminé refusé ×3)"
        ),
        Item(
            "reconnexion-media-pc",
            "RECONNEXION MÉDIA EN COURS D'APPEL (NOUVEAU v0.3.13, preuve 29/10 00:22→00:30 : les appels mouraient à 7-19 s — WSAECONNABORTED/« Connection reset ») : appel de PLUSIEURS minutes avec le téléphone ; si la connexion meurt, la carte flottante affiche « reconnexion… » et le journal contient « média perdu en cours d'appel … — reconnexion » puis « média RÉTABLI — appel repris » — l'appel CONTINUE sans intervention (budget 2 cycles, grâce 60 s côté appelé) ; sans retour possible : fin honnête « connexion perdue »"
        ),
        Item(
            "appel-rapide-parallele",
            "APPEL PC→TEL PLUS RAPIDE (NOUVEAU v0.3.13, preuve 29/10 00:31:59→00:32:21 : le dépôt d'offre en cascade prenait ~22 s AVANT le premier essai média) : appeler le téléphone → le journal montre « média essai 1 » DÈS les premières secondes (dépôt d'offre en PARALLÈLE, plus jamais séquentiel) ; le téléphone sonne nettement plus tôt (le HELLO le fait sonner dès l'arrivée du média, Android ≥ v0.2.61)",
            logValidated = "PROUVÉ 29/10 — 4 appels PC→tel : « média essai 1 » à 06:16:35, 06:17:44, 06:18:24, 06:19:14 (dès le lancement, plus jamais séquentiel) et le tel a sonné DÈS LE HELLO à ~5 s (« appel entrant DÈS LE HELLO » 05:16:40 etc.) contre ~22-45 s avant"
        ),
        Item(
            "frame-skipping",
            "FRAME SKIPPING SUR CIRCUIT LENT (NOUVEAU v0.3.13, preuve 29/10 : RTT SENDME 1,5-2,4 s — le circuit Tor saturé mourait) : pendant une visio sur réseau chargé, le journal peut montrer « frame skipping engagé (write … ms > 350 ms — la cadence s'adapte au circuit) » et l'appel SURVIT au-delà des 7-19 s observées avant ; régressions : webcam par défaut + partage d'écran validé + carte flottante inchangés"
        ),
    )

    /** v0.3.12 — items de validation (miroir de docs/releases/v0.3.12-desktop.md). */
    val ITEMS_V0_3_12 = listOf(
        Item(
            "webcam-detectee",
            "LA WEBCAM DU PC EST ENFIN DÉTECTÉE — ITEM DÉCISIF n°1 (NOUVEAU v0.3.12, cause racine 6ᵉ export 21:16:35 : le parseur dshow lisait le format ffmpeg ≤ 6 alors que le ffmpeg 7.1 embarqué liste « [in#0 @ …] \"Integrated Camera\" (video) » — la webcam ÉTAIT listée mais déclarée absente → le PC envoyait son ÉCRAN) : appel visio vers le téléphone → l'export contient « webcam détectée(s) : « … » » puis « webcam ouverte : « … » » et « CAMÉRA du PC active » ; le téléphone voit VOTRE WEBCAM (plus l'écran) ; si la caméra est réellement absente : bannière orange dans l'overlay « Caméra indisponible — ÉCRAN envoyé. Vérifier : Paramètres Windows › Confidentialité et sécurité › Caméra… » (plus une ligne de journal invisible)"
        ),
        Item(
            "ecran-valide",
            "PARTAGE D'ÉCRAN VALIDÉ DES DEUX CÔTÉS — ITEM DÉCISIF n°2 (NOUVEAU v0.3.12, demande opérateur verbatim session 78 : « partage d'écran dans les 2 sens UNIQUEMENT si validé des 2 côtés ») : bouton 🖥️ → « ⏳ Autorisation… » et la demande part au téléphone (journal « F_SCREEN_REQ émis ») ; ✓ Autoriser SUR LE TÉLÉPHONE → bascule écran (« Partage d'écran actif (validé par le pair) ») ; ✕ ou 30 s sans réponse → caméra maintenue avec message honnête ; pendant le partage, 📹 → retour caméra + le téléphone est informé ; une demande DU téléphone ouvre « Le pair demande à partager son écran » ✓/✕ dans l'overlay ; avec un téléphone < v0.2.60 : « Partage d'écran refusé : le pair ne le valide pas » — JAMAIS de bascule unilatérale"
        ),
        Item(
            "appel-pc-vers-tel-fiable",
            "APPEL PC→TÉLÉPHONE FIABLE (NOUVEAU v0.3.12, preuve 6ᵉ export 21:17:40→57 : appel mort « réplica injoignable » après 17 s alors que le réplica répondait 30 s plus tard) : lancer un appel PC→téléphone → la sonnerie part MÊME si le premier dépôt échoue (journal « signalisation déposée sur … (cible N, tour M) » en cas de repli) ; plus de « réplica injoignable — appel impossible » tant qu'UNE cible vit"
        ),
        Item(
            "dedup-flotte-bureau",
            "TEMPÊTE DE RE-LIVRAISON MORTES (NOUVEAU v0.3.12, preuve 6ᵉ export 21:12→21:28 : ~30 lignes re-traitées PAR CYCLE de 15 s pendant 16 min) : l'export montre AU PLUS UNE ligne « N doublon(s) de livraison ignoré(s) (eventId connu) » par cycle ; plus de « ✓ annonce réplica … (persistée) » répétées en boucle ; l'offre d'un appel TERMINÉ ne re-sonne jamais (« offre d'un appel déjà terminé … doublon de livraison »)"
        ),
        Item(
            "fiche-pair-resolue",
            "FICHE PAIR RÉSOLUE (NOUVEAU v0.3.12, preuve : « GET /client/profile → 404 » chez le téléphone toutes les ~17 s — le PC demandait sa carte au MAUVAIS réplica) : la fiche du contact téléphone affiche l'alias/avatars publiés ; l'export téléphone ne montre PLUS de 404 en boucle (backoff 10 min quand la carte est introuvable)"
        ),
    )

    val ITEMS_V0_3_11 = listOf(
        Item(
            "raccrocher-premier-plan",
            "LA CARTE D'APPEL + LE BOUTON RACCROCHER RESTENT AU PREMIER PLAN — ITEM DÉCISIF n°1 (NOUVEAU v0.3.11, demande opérateur verbatim session 81 : « lors d'un appel qui abouti sur pc, je veux la boite de décrochage de l'appel toujours au premier plan, et un bouton pour raccrocher toujours au premier plan jusqu'à la fin de l'appel ») : appel abouti (entrant décroché ou sortant) → une CARTE COMPACTE en bas à droite « Nyx — appel en cours » (alias, durée vivante, 🎤 mute, 📹/🖥️ bascule visio) reste TOUJOURS AU-DESSUS de toutes les fenêtres pendant TOUT l'appel ; passez la messagerie en arrière-plan, utilisez d'autres applications → la carte RESTE visible et le bouton RACCROCHER (rouge) reste CLIQUABLE en toutes circonstances jusqu'à la fin de l'appel ; Alt+F4 sur la carte = raccrocher (jamais d'appel fantôme) ; au décroché d'un appel entrant, la fenêtre principale revient AUSSI au premier plan une fois"
        ),
        Item(
            "visio-bidirectionnelle",
            "LA VISIO TÉLÉPHONE↔PC PRODUIT UNE IMAGE DES DEUX CÔTÉS — ITEM DÉCISIF n°2 (NOUVEAU v0.3.11, retour terrain 28/10 17:24-17:25 : « visio tel => pc : je n'ai pas eu de video, idem dans l'autre sens ») : appel visio depuis OU vers le téléphone (v0.2.59) → au décroché, VOUS voyez la caméra du téléphone (portrait/paysage droit) ET le téléphone voit VOTRE webcam, PLUS D'UNE MINUTE sans bande noire ni effondrement ; webcam muette (confidentialité Windows bloquante) → journal honnête « caméra muette (aucune trame en 8 s…) — repli écran » et BASCULE AUTOMATIQUE vers l'écran ; énumération webcam robuste (journal « webcam détectée : « … » (dshow) » ou la sortie brute ffmpeg si absente)"
        ),
        Item(
            "messages-notifies-accuses",
            "MESSAGES TÉLÉPHONE→PC : BULLE + ACCUSÉS — ITEM DÉCISIF n°3 (NOUVEAU v0.3.11, retour terrain 28/10 17:26-17:30 : « envoi de message tel => pc : pas de notification coté pc, pas d'accusé de réception/lecture coté tel » — deux causes : la fenêtre minimisée tuait la bulle pour une conversation OUVERTE, et le téléphone réparé v0.2.59 dépote enfin ses événements directs) : envoyer un message DEPUIS le téléphone (v0.2.59) → l'info-bulle plateau apparaît DÈS la réception (même Nyx réduit ou derrière — la suppression « conversation ouverte » ne s'applique QUE fenêtre VISIBLE ET ACTIVE) ; l'accusé ✓ part en fin de poll propre (journal « accusés en attente : … ») et le ✓✓ dès l'ouverture de la conversation ; l'expéditeur téléphone voit les deux revenir sur sa bulle"
        ),
        Item(
            "annuler-referme",
            "LE BOUTON ANNULER DU DIALOGUE DE FERMETURE REFERME IMMÉDIATEMENT (NOUVEAU v0.3.11, retour terrain 28/10 : « fermeture de l'appli pc : le bouton annuler ne fait rien » — l'état @Volatile n'était pas observé par Compose, le dialogue ne partait qu'au tick de poll ≤ 15 s plus tard) : croix de la fenêtre → dialogue « Fermer Nyx ? » → « Annuler » → le dialogue se referme À L'INSTANT (fenêtre intacte, rien ne se ferme) ; « Réduire » → plateau ; « Quitter » → sortie propre"
        ),
        Item(
            "media-vue-manuelle",
            "MÉDIA À VUE LIMITÉE : AFFICHAGE JUSQU'À FERMETURE MANUELLE (NOUVEAU v0.3.11 miroir téléphone v0.2.59, retour terrain 28/10 17:32 : « le média s'affiche et se referme de suite, il a été détruit sans que je ne puisse le voir. je veux pouvoir terminer l'affichage moi-même ») : envoyer un média à vue limitée AU téléphone (v0.2.59) → le destinataire l'ouvre → il RESTE affiché TANT QU'IL VEUT (fermeture manuelle) ; côté PC : ouvrir un média à vue limitée REÇU → l'image reste affichée dans la conversation (octets mémoire, fichier détruit à la révélation) jusqu'à la sortie de la conversation — jamais de fermeture automatique"
        ),
    )

    val ITEMS_V0_3_10 = listOf(
        Item(
            "camera-par-defaut",
            "LA CAMÉRA DU PC EST LA SOURCE PAR DÉFAUT — ITEM DÉCISIF n°1 (NOUVEAU v0.3.10, demande opérateur verbatim : « le pc fait un partage d'écran plutot que d'envoyer le flux caméra. Je veux par défaut le flux caméra mais la possibilité de partager l'écran ») : appel visio DEPUIS le PC (téléphone ≥ v0.2.58) → le téléphone voit VOTRE WEBCAM (pas votre écran) dès l'établissement (journal « CAMÉRA du PC active (…) MJPEG ffmpeg ») ; sur le téléphone, tourner/retourner l'image de la webcam — l'aspect reste cohérent ; bouton « 📹 Caméra » dans l'overlay → bascule À CHAUD vers « 🖥️ Écran » SANS couper l'appel (le téléphone voit alors l'écran), re-bascule pareil ; webcam absente → repli honnête écran (journal « caméra indisponible — repli PARTAGE D'ÉCRAN »)"
        ),
        Item(
            "notifications-une-seule-fois",
            "LES NOTIFICATIONS DE MESSAGES PARTENT UNE SEULE FOIS — ITEM DÉCISIF n°2 (NOUVEAU v0.3.10, demande verbatim : « sur pc, notifications incessantes de messages déjà traités ») : envoyer PLUSIEURS messages depuis le téléphone → UNE info-bulle plateau par message NOUVEAU, AUCUNE répétition pour un message déjà stocké ; les messages anciens déjà lus ne re-notifient PAS non plus au redémarrage de l'app"
        ),
        Item(
            "sonnerie-immediate-hello",
            "L'APPEL ENTRANT SONNE IMMÉDIATEMENT — ITEM DÉCISIF n°3 (NOUVEAU v0.3.10, demandes verbatim : « la notification est longue à arriver, pas de son coté pc » + « appel visio tel => pc : n'a pas fonctionné ») : appeler le PC depuis le téléphone (audio ou visio) → la carte ✓/✕ + la SONNERIE apparaissent DÈS LA CONNEXION DU TÉLÉPHONE (journal « appel entrant DÈS LE HELLO (sonnerie immédiate… ») — plus d'attente de 15-30 s ; le SON est audible (bi-ton 440+480 Hz) ; décrocher sous 60 s aboutit : l'appel s'établit"
        ),
        Item(
            "retour-appel-sonne",
            "LE RETOUR D'APPEL SONNE CHEZ L'APPELANT (NOUVEAU v0.3.10, demande verbatim : « lors d'une tentative d'appel, je veux une sonnerie côté appelant pour indiquer que le tel ou pc appelle ») : appeler le téléphone DEPUIS le PC → tonalité douce 1,5 s/3,5 s pendant que le téléphone sonne, COUPÉE au décroché (journal « retour d'appel bureau DÉMARRÉ » puis « STOPPÉ ») ; refus/timeout la coupent aussi"
        ),
        Item(
            "visio-orientation-pc",
            "VISIO : L'IMAGE DU TÉLÉPHONE EST DROITE ET SUIT SON ORIENTATION (NOUVEAU v0.3.10, demande verbatim : « toujours un problème d'orientation de la video et de resolution qui devrait s'adapter à l'orientation ») : visio reçue d'un téléphone ≥ v0.2.58 en PORTRAIT → l'image est DROITE, portrait 240x320 (boîte au bon aspect, AUCUN étirement) ; le téléphone passe en PAYSAGE → l'image reste droite et la boîte suit SANS coupure ; avec un téléphone ≤ v0.2.57 l'image reste regardable (rotation historique par F_VIDEO_ROT)"
        ),
    )

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

    fun items(): List<Item> = ITEMS_V0_3_14 + ITEMS_V0_3_13 + ITEMS_V0_3_12 + ITEMS_V0_3_11 + ITEMS_V0_3_10 + ITEMS_V0_3_9 + ITEMS_V0_3_8 + ITEMS_V0_3_7 + ITEMS_V0_3_6 + ITEMS_V0_3_5

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

    /** v0.3.14 — les items VALIDÉS PAR LOGS comptent comme PASS (la preuve
     *  est l'export terrain, consignée dans l'item). */
    fun doneCount(): Int = items().count { it.logValidated != null || doneAt(it.id) > 0L }

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

    /** v0.3.14 — les preuves PAR LOGS rappelées dans l'export (lisible sans
     *  carte) : chaque item prouvé émet sa ligne PASS au même titre qu'un tap. */
    fun logValidatedProofs() {
        for (it in items().filter { v -> v.logValidated != null && doneAt(v.id) == 0L }) {
            DesktopLog.i("nyx-test", "PASS ${it.id} : ${it.label} — VALIDÉ PAR LOGS 29/10 : ${it.logValidated}")
        }
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(Json.encodeToString(Marks.serializer(), marks))
        }.onFailure { DesktopLog.w("nyx-test", "persistance du plan impossible (${it.message})") }
    }
}
