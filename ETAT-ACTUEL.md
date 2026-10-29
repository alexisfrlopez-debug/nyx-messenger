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

## État au 29 octobre 2026 (fin de session 83)

- **Dernière clôture** : session **83** — 2026-10-29, closeout §9.11 (le SHA du commit final est dans `git log`)
- **Messagerie Android** : **v0.2.61** (versionCode 65) — livrée (release GitHub v0.2.61 + Drive, v0.2.60 archivée)
- **Messagerie desktop** : **v0.3.13** — livrée (release GitHub v0.3.13-desktop + Drive, exe + zip + SUMS, v0.3.12 archivée)
- **Serveur** : v0.2.38 — binaire en asset GitHub (convention Drive respectée)
- **Console admin** : v0.8.12 (dépôt privé nyx-admin)
- **Fiche de présentation** : 26ᵉ édition (36 pages) — cite v0.2.61/v0.3.13
- **Terrain attendu** : retour d'expérience sur v0.2.61 / v0.3.13 → TestPlan v0.2.61 (6 items) + v0.3.13 (5 items), décisifs : `raccrocher-notification` + `annulation-propagee` (Android) / `fenetre-decrochage-devant` + `annule-jamais-actif` (desktop) — voir §3 RESTART.md (pointeur rapide session 83).

## Contenu de la session 83 (closeout)

Retour terrain 29/10 00:34 (6 points, logs dans `reports/logs-s83/`) : (1) appel annulé ne ressuscite plus —
3 gardes anti-ghost-call ; (2) reconnexion média en cours d'appel (2 cycles, visio continue) ; (3) raccrocher
depuis la notification d'appel en cours ; (4) accusé « lu » par deep-link notification→conversation ;
(5) sonnerie dès le HELLO + offre parallèle + fenêtre décrochage devant ; (6) poll dès bootstrap 75 % +
warm-up circuits + frame skipping. NOUVEAU AUSSI : RÈGLE ZÉRO anti-résumé-périmé — ce fichier devient la
source unique de vérité, réécrite à chaque clôture et vérifiée par le contrôle `[etat]` de
`scripts/session_closeout.py`.

## Invariants (ne jamais rouvrir sans ADR)

Identité = adresse onion · Tor embarqué (ADR-007) · 100 % réseau zéro local (ADR-009) ·
AGPL-3.0-only (ADR-011) · binaires HORS git (Releases GitHub §9.6) ·
closeout obligatoire (§9.11) · **ETAT-ACTUEL.md à jour à CHAQUE clôture (session 83+)**.

> **POUR METTRE À JOUR CE FICHIEER après clôture** : mettre à jour les sections
> ci-dessus (clôture, versions, session suivante) puis exécuter
> `python3 scripts/session_closeout.py` — le contrôle `[etat]` vérifie la
> cohérence avec les versions annoncées dans les pointeurs rapides du RESTART.md.
