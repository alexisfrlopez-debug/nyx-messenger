#!/usr/bin/env bash
# =============================================================================
# Nyx — Synchro systématique (règle permanente, RESTART.md §9)
#   1. Git : commit (message passé en argument ou auto) + push GitHub
#   2. Drive : archive projet + livrables → /livrables (ARBORESCENCE miroir :
#      archives/<v>/, documents/, dernieres-livrables/ — session 13) + docs racine
#      dossier projet « Messagerie » (résolution par noms — scripts/gdrive.py)
#
# Idempotent, tolérant aux absences (credentials manquants → SKIP explicite,
# jamais d'échec silencieux). Usage : scripts/sync.sh ["message de commit"]
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
CREDS=/home/z/my-project/.credentials
RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; NC=$'\033[0m'

echo "${YLW}═══ SYNCHRO NYX ═══${NC}"

# ---------- 1. GitHub ----------
echo "── GitHub ──"
MSG="${1:-chore(nyx): synchro session — audit + build + livraison}"
if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -m "$MSG" | head -2 || true
else
  echo "  aucun changement à committer"
fi
if [ -f "$CREDS/token_git.txt" ] || [ -f "$CREDS/tokens/token_git.txt" ]; then
  GIT_TOKEN_FILE="$CREDS/token_git.txt"; [ -f "$GIT_TOKEN_FILE" ] || GIT_TOKEN_FILE="$CREDS/tokens/token_git.txt"
  PAT=$(tr -d '[:space:]' < "$GIT_TOKEN_FILE")
  ORIGIN_URL="https://alexisfrlopez-debug:${PAT}@github.com/alexisfrlopez-debug/nyx-messenger.git"
  if git push "$ORIGIN_URL" HEAD 2>&1 | rg -v "remote:" | head -3; then
    echo "${GRN}  ✓ push GitHub${NC}"
  else
    echo "${RED}  ✗ push GitHub (PAT invalide ou réseau)${NC}"
  fi
else
  echo "${YLW}  ⏳ SKIP push — token_git.txt absent (PAT à restaurer du dossier Drive dédié « Tokens », RESTART.md §6)${NC}"
fi

# ---------- 2. Drive ----------
echo "── Drive ──"
if [ -d "$CREDS" ] && python3 scripts/gdrive.py --status >/dev/null 2>&1; then
  # a) livrables → /livrables (arborescence miroir, uploads immuables :
  #    un livrable publié ne change jamais → skip si nom+taille identiques)
  if [ -d livrables ]; then
    while IFS= read -r f; do
      rel="${f#livrables/}"
      sub="livrables/$(dirname "$rel")"   # dirname "." → dossier racine livrables
      python3 scripts/gdrive.py upload "$f" "$sub" --immutable >/dev/null \
        && echo "${GRN}  ✓ Drive/$sub ← $rel${NC}" \
        || echo "${RED}  ✗ upload $rel${NC}"
    done < <(find livrables -type f)
  fi
  # b) docs principaux → racine du dossier projet « Messagerie » (RESTART.md, README.md…)
  for f in RESTART.md README.md GUIDE_DEMARRAGE.md; do
    [ -f "$f" ] || continue
    python3 scripts/gdrive.py upload "$f" "" >/dev/null 2>&1 \
      && echo "${GRN}  ✓ Drive racine ← $f${NC}" \
      || echo "${RED}  ✗ upload $f${NC}"
  done
  # c) archive complète du projet (état session) — hors /tmp (règle workspace)
  mkdir -p "$ROOT/../archives"
  ARCHIVE="$ROOT/../archives/nyx-projet-$(date +%Y%m%d-%H%M%S).tar.gz"
  tar --exclude='android/app/build' --exclude='android/.gradle' --exclude='.git' \
      --exclude='livrables' --exclude='reports' --exclude='local.properties' \
      --exclude='keystore.properties' \
      -czf "$ARCHIVE" . 2>/dev/null
  python3 scripts/gdrive.py upload "$ARCHIVE" "" >/dev/null 2>&1 \
    && echo "${GRN}  ✓ Drive ← archive source ($(basename "$ARCHIVE"))${NC}" \
    || echo "${RED}  ✗ archive source${NC}"
else
  echo "${YLW}  ⏳ SKIP Drive — credentials absents ou invalides (restaurer du dossier Drive dédié « Tokens » : client_secret + oauth_token, RESTART.md §6)${NC}"
  echo "     → les livrables restent dans $ROOT/livrables/ en attente de synchro"
fi

echo "${GRN}═══ SYNCHRO TERMINÉE ═══${NC}"
