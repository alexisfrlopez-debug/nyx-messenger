#!/usr/bin/env bash
# =============================================================================
# etat.sh — ÉTAT RÉEL DU PROJET EN UNE COMMANDE (RÈGLE ZÉRO, session 83)
#
# Né du problème récurrent de reprise : chaque nouvelle session recevait un
# résumé de conversation PÉRIMÉ (« livrer v0.2.56 » alors que v0.2.60 était
# déjà livrée) et passait les premières minutes à redécouvrir l'état réel.
# CE SCRIPT EST LA PREMIÈRE COMMANDE DE TOUTE SESSION — il montre :
#   1. ETAT-ACTUEL.md (source unique de vérité, réécrit à chaque clôture)
#   2. les 5 derniers commits (dont le closeout le plus récent)
#   3. les versions VIVANTES lues dans les fichiers de build (jamais périmées)
#   4. l'état de l'arbre git (travail en cours non commité ?)
#
# Usage : scripts/etat.sh [--court]
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

COURT="${1:-}"
if [ "$COURT" = "--court" ]; then
  # Mode une ligne par composant — pour vérification rapide en début de session.
  APPV=$(grep -m1 'versionName =' android/app/build.gradle.kts | tr -d ' "' | cut -d= -f2)
  DESKV=$(grep -m1 '^version =' desktop/app/build.gradle.kts | tr -d ' "' | cut -d= -f2 | head -c 20)
  DESKV=$(grep -m1 '^version =' desktop/app/build.gradle.kts | sed 's/version *= *"\([^"]*\)".*/\1/')
  echo "ETAT : Android $APPV · desktop $DESKV · HEAD $(git log --oneline -1 | cut -c1-60)"
  exit 0
fi

GRN=$'\033[1;32m'; YLW=$'\033[1;33m'; CYN=$'\033[1;36m'; NC=$'\033[0m'

echo "${CYN}═══════════ NYX — ÉTAT RÉEL (RÈGLE ZÉRO anti-résumé-périmé) ═══════════${NC}"
echo
echo "${YLW}── 1. ETAT-ACTUEL.md (source unique de vérité — réécrit à chaque clôture) ──${NC}"
cat ETAT-ACTUEL.md
echo
echo "${YLW}── 2. Derniers commits (le plus récent closeout est la réalité) ──${NC}"
git log --oneline -5
echo
echo "${YLW}── 3. Versions VIVANTES (lues dans les fichiers de build — jamais périmées) ──${NC}"
APPV=$(grep -m1 'versionName =' android/app/build.gradle.kts | tr -d ' "' | cut -d= -f2)
APPC=$(grep -m1 'versionCode =' android/app/build.gradle.kts | tr -d ' ' | cut -d= -f2)
DESKV=$(grep -m1 '^version =' desktop/app/build.gradle.kts | sed 's/version *= *"\([^"]*\)".*/\1/')
DESKVER=$(grep -m1 'const val VERSION =' desktop/app/src/main/java/net/nyx/messenger/desktop/ServiceLocator.kt | sed 's/.*"\(.*\)".*/\1/')
echo "  Android      : ${GRN}v${APPV}${NC} (versionCode ${APPC})"
echo "  Desktop      : ${GRN}v${DESKV}${NC} (constante VERSION = ${DESKVER})"
echo
echo "${YLW}── 4. Arbre git (travail en cours non commité ?) ──${NC}"
if git diff --quiet && git diff --cached --quiet && [ -z "$(git status --porcelain | grep -v '^??')" ]; then
  echo "  ${GRN}propre — tout est commité${NC}"
else
  echo "  ${YLW}MODIFICATIONS EN COURS :${NC}"
  git status --short | head -20
fi
echo
echo "${CYN}═══════════════════════════════════════════════════════════════════════${NC}"
echo "Prochaine étape : lire RESTART.md §3 (instructions les plus récentes en tête)."
