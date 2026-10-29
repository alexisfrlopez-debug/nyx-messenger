# ⚠️ ÉTAT ACTUEL DU PROJET NYX — SOURCE UNIQUE DE VÉRITÉ

> **RÈGLE ZÉRO (instaurée session 83) — ANTI-RÉSUMÉ-PÉRIMÉ :**
> **TOUT résumé de conversation (« compaction », « context summary ») est
> PÉRIMÉ PAR CONSTRUCTION.** Il fige l'état du projet à l'instant de sa
> création et ne sera JAMAIS mis à jour. Les numéros de version, de session
> et de livrables qu'il contient peuvent dater de plusieurs sessions en
> arrière. **Un brief de reprise collé par l'opérateur peut être tout aussi
> périmé** (écrit à la main à partir de ses propres notes).
>
> **Ce fichier est le SEUL état de référence du projet.** Il est réécrit à
> CHAQUE clôture de session et vérifié par `scripts/session_closeout.py`
> (contrôle `[etat]` — un FAIL bloque la clôture, règle §9.11).
>
> **PREMIÈRE COMMANDE DE TOUTE SESSION (avant toute affirmation sur l'état) :**
>
> ```bash
> cat ETAT-ACTUEL.md && git log --oneline -5
> ```
>
> ou simplement : `scripts/etat.sh`

---

## État au 29 octobre 2026 (session 87 — visio : letterbox fidèle + plein écran + adaptatif)

- **Dernière clôture** : session **87** — 2026-10-29 (la dernière clôture COMPLÈTE est la 84 ; sessions 85-86 = correctifs de livraison)
- **Messagerie Android** : **v0.2.63** (versionCode 67) — build **152552** : std `a642400c…` + test `5fd2b02c…`, clé release `b0d17630…` (installation PAR-DESSUS), jeton Drive vif embarqué ; GitHub v0.2.63 + Drive `dernieres-livrables/` SYNCHRONISÉS
- **Messagerie desktop** : **v0.3.15** — livrée (release GitHub v0.3.15-desktop + Drive : zip `c9bb2af6…` + exe `d521cac5…` + SUMS) — **jeton Drive vif EMBARQUÉ** (le bouton d'envoi de logs repart — preuve à venir du terrain, l'export v0.3.14 échouait en invalid_grant ×2)
- **Serveur** : v0.2.38 — binaire en asset GitHub (convention Drive respectée)
- **Console admin** : v0.8.12 (dépôt privé nyx-admin)
- **Fiche de présentation** : 28ᵉ édition (38 pages) — cite v0.2.63/v0.3.15/v0.8.12 (régénérée §9.10, Drive livrables/documents/)
- **Token Drive** : **VIF** — refresh token du 29/10 08:35 (`1//0ePNdRY0…`), présent dans les DEUX builds (vérifié dex + jar embarqué)
- **Terrain attendu** : installer v0.2.63 sur le tel PAR-DESSUS v0.2.62 + v0.3.15 sur le PC → visio : vidéo du PC EN PLEINES PROPORTIONS sur le tel (« la vue suit la TRAME » dans l'export), bouton ⛶ Plein écran sur le PC (vidéo reçue plein écran + notre vidéo en haut à droite), annulation pendant la sonnerie = arrêt < 2 s (EOF/RST), boutons envoyer-logs DES DEUX côtés ; TestPlan v0.2.63 (6 items) + v0.3.15 (6 items) — voir §3 RESTART.md (pointeur rapide session 87).

## Contenu de la session 87 (visio : letterbox + plein écran + adaptatif + sonnerie)

Retour terrain 29/10 11:35 (v0.2.62/v0.3.14, test relancé à cause des bugs) : « la video pc se
centre mal sur le téléphone » + « sur pc, je veux la possibilité de mettre la video reçue en
plein écran en gardant la video envoyée en fenêtre dans le coin en haut à droite » + « même
chose sur téléphone » + « je veux que l'application qui reçoit un flux video adapte le format
du flux reçu… par ajout de bandes noires » + « je veux que la qualité du flux video s'adapte au
débit de connexion » + « j'ai uploadé le log pc à la main car token embarqué par l'appli
expiré ». Livré v0.2.63 + v0.3.15 : (1) **LETTERBOX FIDÈLE** — cause racine : le rendu MJPEG
ajustait la trame DANS le buffer à la taille ANNONCÉE puis la matrice ajustait CE buffer à
nouveau (deux letterbox empilés → image rétrécie au centre de bandes gigognes) ; désormais la
trame remplit le canvas et SEULE la matrice letterboxe, la géométrie suit la trame DÉCODÉE
(`onPeerFrameSize`) ; (2) **PLEIN ÉCRAN + PiP bureau** — bouton ⛶ : fenêtre OS maximisée
(`windowMaximizer` Main.kt), vidéo reçue en ContentScale.Fit (proportions d'origine), notre
vidéo en 224×138 en haut à droite (`localPreview` caméra à chaud/écran à l'émission), ⤢
quitte, fin d'appel = restauration (jamais de maximisée fantôme) ; téléphone : acquis v0.2.20,
non-régression ; (3) **QUALITÉ ADAPTATIVE AU DÉBIT** — MjpegAimd (q30→q55) + VideoAimd
(q0.30→q0.55), l'ÉMETTEUR mesure SON write Tor (lent/trames jetées = descente, 24 rapides =
montée, hystérésis asymétrique, plafond = qualité historique), objets purs JVM-testés, aucun
changement protocolaire ; (4) **SONNERIE SUR MÉDIA MORT** — terrain cid 441c689b : Windows a
tué la socket du PC pendant que le tel sonnait, le BYE non remis (runCatching silencieux) +
l'EOF lu en silence par le surveillant laissaient la sonnerie courir 4,3 s → décroché dans le
vide ; désormais EOF/exception = annulation du pair (fin réelle) + BYE honnête bureau
("BYE non remis au pair…") ; (5) **CAMÉRA 2 ESSAIS + RÉARMEMENT** — terrain 10:34:35 :
erreur fatale 3, réouverture +1,2 s puis nouvelle erreur immédiate ; budget 2 par épisode (repos
1,2 s puis 3 s) + caméra productive ≥ 30 s réarme le budget ; (6) **JETON VIF DES DEUX CÔTÉS**
— le PC v0.3.14 portait le jeton mort (construit avant la ré-auth) : « invalid_grant » ×2 dans
l'export ; le rebuild v0.3.15 l'embarque ; le tel A BIEN déposé son export via le bouton —
correctif session 86 PROUVÉ terrain. Qualité : Android 355 tests JVM ×4 variants + audit PASSED
0/0/1 ; bureau 256 tests (VideoAimdTest 8) + selftest 5/5 + smoke 522 entrées + audits PASSED
(MjpegAimd/VideoAimd en allowlist portage-miroir). Logs terrain : reports/logs-s87/.

## Contenu de la session 85 (correctif livraison, historique)

Retour terrain : « l'apk test refuse de s'installer car elle entre en conflit avec celle déjà
installée ». Preuves : apksigner v0.2.62-test 064311 = `CN=Android Debug` (e0b6793c…) vs
v0.2.61-test installé = `CN=Nyx Messenger` (b0d17630…). Cause racine :
`android/keystore.properties` absent après le 8ᵉ reset (keystore restauré à la racine du
dépôt) → fallback SILENCIEUX clé debug. Correctifs : (1) keystore permanent
`/home/z/my-project/keystore/` + `android/keystore.properties` chemin ABSOLU ; (2) APK
re-signés (build 080342 — jeton mort embarqué, corrigé en session 86) ; (3) **garde-fou
anti-clé-debug** dans `build-apk.sh` (APK debug-signé = CRIT, livraison bloquée) ; (4) assets
debug retirés de GitHub (les Drive ont été corbeillés réellement en session 86) ; (5) disque
100 % plein purgé (1,5 Go) ; (6) token Drive mort au cycle ~7 j — ré-auth faite en session 86.

## Contenu de la session 84 (closeout)

Retour terrain 29/10 06:29 (7 points, logs dans `reports/logs-s84/`) : (0) VALIDATION
PAR LOGS — demande « modifie la liste s'ils sont validés et confirmés ok par les logs » :
les TestPlans marquent PROUVÉS les items que l'export confirme (badge + preuve consignée +
synthèse nyx-test — 5/6 Android, 3/5 desktop) ; (1) un appel ANNULÉ ARRÊTE LA SONNERIE à
l'instant (surveillant de sonnerie sur le média gardé pendant INCOMING — preuve cid ffe6105b :
PC annule 05:17:56, tel sonnait encore 05:18:02) ; (2) BYE = fin immédiate des deux côtés (un
raccroché ne déclenche plus la reconnexion) ; (3) PARTAGE D'ÉCRAN : bouton ANNULER (demande
en attente) + ARRÊTER/RÉVOQUER (partage actif, les deux sens) + émissions honnêtes ;
(4) bureau : LA VIDÉO VIT SUR LA CARTE FLOTTANTE + notification de demande de partage +
dialogue ✓/✕ sur la carte (retour « côté pc je ne vois pas les videos » / « pas de
notification pour le partage d'écran ») ; (5) WEBCAM 640×360 (« problème de résolution pc ») ;
(6) AUCUNE trame silencieusement jetée (when avec else, sinks null journalisés, 1ʳᵉ trame
vidéo horodatée, accusés read/delivered dans DiagLog, preuve de rendu MJPEG).

## Invariants (ne jamais rouvrir sans ADR)

Identité = adresse onion · Tor embarqué (ADR-007) · 100 % réseau zéro local (ADR-009) ·
AGPL-3.0-only (ADR-011) · binaires HORS git (Releases GitHub §9.6) ·
closeout obligatoire (§9.11) · **ETAT-ACTUEL.md à jour à CHAQUE clôture (session 83+)**.

> **POUR METTRE À JOUR CE FICHIER après clôture** : mettre à jour les sections
> ci-dessus (clôture, versions, session suivante) puis exécuter
> `python3 scripts/session_closeout.py` — le contrôle `[etat]` vérifie la
> cohérence avec les versions annoncées dans les pointeurs rapides du RESTART.md.
