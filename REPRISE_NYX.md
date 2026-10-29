# REPRISE DU PROJET NYX — Modèle de brief de démarrage de session

> **À l'opérateur (Alek)** : copiez-ce modèle pour démarrer une session NYX.
> Il est VOLONTAIREMENT vide de tout numéro de version, de session et de
> livrable — ces chiffres sont périmés par construction dans un brief écrit
> à la main (incident récurrent : sessions ouvertes sur « livrer v0.2.56 »
> alors que v0.2.60 était déjà livrée). **L'état réel vit dans le dépôt.**

---

## Ce que l'agent doit faire À VOTRE PLACE dès le premier message

**ÉTAPE 0 — RÈGLE ZÉRO (avant toute chose) :**

```bash
cat ETAT-ACTUEL.md && git log --oneline -5     # ou : scripts/etat.sh
```

- `ETAT-ACTUEL.md` (racine du dépôt) = la seule source de vérité : dernière
  clôture, versions livrées, session en cours, prochaine action attendue.
- Si un résumé de conversation ou un brief contredit ce fichier,
  **c'est le résumé/brief qui a tort — sans exception.**
- Puis lire RESTART.md §3 (les instructions les plus récentes sont EN TÊTE).

## Ce que VOTRE brief doit contenir (et ne doit PAS contenir)

À ÉCRIRE dans le brief :
- le **retour terrain / la demande** (ce que vous avez observé, vos phrases
  exactes sont les plus précieuses) ;
- les **fichiers joints** : logs terrain → dossier Drive `logs-terrain`
  (l'agent les télécharge et les enregistre dans `reports/logs-sXX/`) ;
- le **ordre de priorité** si plusieurs sujets ;
- les **contraintes** (délai, appareil de test, version d'Android…).

À NE PAS écrire dans le brief :
- ~~numéros de version~~ (l'agent lit les fichiers de build : toujours vrais) ;
- ~~« la dernière session a livré X »~~ (l'agent lit `ETAT-ACTUEL.md`) ;
- ~~numéros de session~~ (l'agent lit `git log` + RESTART.md §8) ;
- ~~état du dépôt~~ (l'agent vérifie `git status` + `scripts/etat.sh`).

## Modèle à copier-coller

```
Projet NYX — session du [DATE].

Retour terrain (vérifiez d'abord les logs dans le Drive logs-terrain,
enregistrez-les dans reports/logs-sXX/ comme le veut la règle §9.9) :

1. [votre observation 1, phrases exactes]
2. [votre observation 2]
…

Priorités : [ordre souhaité, ou « à votre diagnostic »]

Contraintes : [ex. test sur Blackview XPLORE 1 / Windows 11, délai…]
```

---

## Rappels d'hygiène du projet (pour l'agent)

- Règle §9.9 : TOUT log terrain est ENREGISTRÉ avant tout travail.
- Règle §9.11 : la session se clôture par `python3 scripts/session_closeout.py` → PASS.
- À CHAQUE clôture : `ETAT-ACTUEL.md` est réécrit (le contrôle `[etat]`
  vérifie sa cohérence — un FAIL bloque la clôture).
- Versions vivantes : `android/app/build.gradle.kts` (versionName/versionCode)
  et `desktop/app/build.gradle.kts` + constante `VERSION` desktop.
