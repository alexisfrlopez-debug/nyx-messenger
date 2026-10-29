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

## État au 29 octobre 2026

- **Dernière clôture** : session **82** — 2026-10-29, commit `aeac0c2`, closeout §9.11 **PASS (0 FAIL, 2 WARN)**
- **Messagerie Android** : **v0.2.60** (versionCode 64) — livrée (release GitHub + Drive, archivage OK)
- **Messagerie desktop** : **v0.3.12** — livrée (release GitHub + Drive, exe + zip + SUMS)
- **Serveur** : v0.2.38 — binaire en asset GitHub (convention Drive respectée)
- **Console admin** : v0.8.12 (dépôt privé nyx-admin)
- **Terrain attendu** : retour d'expérience sur v0.2.60 / v0.3.12 → voir §3 RESTART.md (instructions session 82)

## Session EN COURS : 83

- **Déclencheur** : retour terrain opérateur Alek du 2026-10-29 00:34 (logs v0.2.60/v0.3.12 dans `reports/logs-s83/`) — 6 points : connexion tel lente au réplica, fenêtre décrochage derrière, résolution vidéo + coupure, accusé « reçu » pas « lu », raccrocher depuis la notification, appel annulé qui se relance.
- **Cible** : **v0.2.61 Android (versionCode 65) + v0.3.13 desktop**
- **État d'avancement** : code écrit et compilé des deux côtés (desktop : tests JVM OK ; Android : variant standard OK) — restent tests des autres variants, builds release, audits, release GitHub, Drive, worklog, pointeur rapide.

## Invariants (ne jamais rouvrir sans ADR)

Identité = adresse onion · Tor embarqué (ADR-007) · 100 % réseau zéro local (ADR-009) ·
AGPL-3.0-only (ADR-011) · binaires HORS git (Releases GitHub §9.6) ·
closeout obligatoire (§9.11) · **ETAT-ACTUEL.md à jour à CHAQUE clôture (session 83+)**.

> **POUR METTRE À JOUR CE FICHIEER après clôture** : mettre à jour les sections
> ci-dessus (clôture, versions, session suivante) puis exécuter
> `python3 scripts/session_closeout.py` — le contrôle `[etat]` vérifie la
> cohérence avec les versions annoncées dans les pointeurs rapides du RESTART.md.
