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

## État au 29 octobre 2026 (session 86 — correctif livraison TERMINÉ)

- **Dernière clôture** : session **84** — 2026-10-29, closeout §9.11 ; sessions **85-86** = correctifs de livraison (clé debug → re-signage → token mort → rebuild)
- **Messagerie Android** : **v0.2.62** (versionCode 66) — build **092836** : std `71bbd2e5…` + test `1bad1eec…`, clé release `b0d17630…`, **jeton Drive vif embarqué dans le flavor test** (le 080342 portait encore le jeton mort) ; GitHub v0.2.62 + Drive `dernieres-livrables/` **SYNCHRONISÉS** (les 2 canaux à jour, SUMS vérifiés)
- **Messagerie desktop** : **v0.3.14** — livrée (release GitHub v0.3.14-desktop + Drive, exe + zip + SUMS)
- **Serveur** : v0.2.38 — binaire en asset GitHub (convention Drive respectée)
- **Console admin** : v0.8.12 (dépôt privé nyx-admin)
- **Fiche de présentation** : 27ᵉ édition (36 pages) — cite v0.2.62/v0.3.14
- **Token Drive** : **VIF** — refresh token ré-authorisé par l'opérateur le 29/10 08:35 (`1//0ePNdRY0…`), installé dans `.credentials/tokens/` + `Tokens/` Drive + `android/test-upload.properties` (embarqué APK test) ; l'ancien relais (22/10) est mort au cycle ~7 j — si 400 invalid_grant revient → ré-auth interactive (procédure session 53)
- **Terrain attendu** : RE-TÉLÉCHARGER v0.2.62 (build **092836** — GitHub ou Drive, ignorer tout APK 064311/080342 déjà téléchargé), installer PAR-DESSUS (même clé, rien désinstaller) → **vérifier que le bouton « envoyer les logs » du téléphone fonctionne** (preuve que le jeton vif embarqué) + TestPlan v0.2.62 (7 items) + v0.3.14 (7 items) — voir §3 RESTART.md (pointeur rapide session 86).

## Contenu de la session 86 (correctif livraison, pas de closeout complet)

Reprise de la session 85 interrompue sur « token Drive mort → sync EN ATTENTE ». (a)
l'opérateur a fourni la ré-auth OAuth (callback collé) → nouveau refresh token échangé et
installé partout (credentials + backup Drive Tokens/ + test-upload.properties) ; (b) BUG DE
CACHE DE REPRISE corrigé dans `scripts/build-apk.sh` : l'empreinte de reprise ignorait les
fichiers git-ignorés qui alimentent le build (`test-upload.properties`, `keystore.properties`,
`replica-bootstrap.properties`) → un changement de credentials resservait l'ANCIEN APK du
cache /tmp — le re-signage 080342 portait donc ENCORE le jeton mort ; ces 3 fichiers entrent
désormais dans l'empreinte ; (c) REBUILD v0.2.62 (build 092836) : apksigner ×2 =
`CN=Nyx Messenger` `b0d17630…`, dex = `0.2.62-test (code 66)` + NOUVEAU jeton présent /
ANCIEN absent ; (d) livraison remplacée PARTOUT : GitHub (assets + corps PATCHé) + Drive
(`publish-drive.sh`) ; (e) les 2 APK 064311 CLÉ DEBUG étaient ENCORE dans
`dernieres-livrables/` Drive (la corbeille de la session 85 avait échoué sur le token mort)
→ corbeillés POUR DE VRAI (sinon quiconque les téléchargeait re-provoquait le conflit).

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
