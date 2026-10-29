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

## État au 29 octobre 2026 (fin de session 84)

- **Dernière clôture** : session **84** — 2026-10-29, closeout §9.11 (le SHA du commit final est dans `git log`)
- **Messagerie Android** : **v0.2.62** (versionCode 66) — livrée (release GitHub v0.2.62 + Drive, v0.2.61 archivée)
- **Messagerie desktop** : **v0.3.14** — livrée (release GitHub v0.3.14-desktop + Drive, exe + zip + SUMS, v0.3.13 archivée)
- **Serveur** : v0.2.38 — binaire en asset GitHub (convention Drive respectée)
- **Console admin** : v0.8.12 (dépôt privé nyx-admin)
- **Fiche de présentation** : 27ᵉ édition (36 pages) — cite v0.2.62/v0.3.14 (vidéo sur la carte flottante bureau, notification + ANNULER/ARRÊTER le partage d'écran, badge PROUVÉ par logs, webcam 640×360 ; QA pdftotext v0.2.62×10 / v0.3.14×10, bug de concaténation du Tableau 5 corrigé)
- **Terrain attendu** : retour d'expérience sur v0.2.62 / v0.3.14 → TestPlan v0.2.62 (7 items, décisifs `bye-pendant-sonnerie` + `annuler-partage-tel`) + v0.3.14 (7 items, décisifs `video-carte-flottante` + `notif-partage-ecran`) — la carte affiche déjà les items VALIDÉS PAR LOGS du 29/10 (badge + preuve) ; voir §3 RESTART.md (pointeur rapide session 84).

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
