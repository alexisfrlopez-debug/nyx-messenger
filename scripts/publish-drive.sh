#!/usr/bin/env bash
# =============================================================================
# Nyx — Publication Drive systématique d'une release (règle §9.4, session 18)
#
#   À CHAQUE RELEASE (client OU console admin) :
#     1. ARCHIVE dans /livrables/archives/ tout livrable de la MÊME famille
#        encore présent dans dernieres-livrables/ dont la version n'est PAS
#        la version publiée (l'ancien quitte le jeu courant) :
#          client  → archives/v<ancienne-version>/
#          admin   → archives/nyx-admin-v<ancienne-version>/
#     2. TÉLÉVERSE le nouveau jeu dans /livrables/dernieres-livrables/
#        (uploads immuables : nom + taille identiques → skip) ;
#        les documents (--doc) vont dans /livrables/documents/.
#
# Détection de version par nommage (session 13) :
#   client : nyx-v<X>-… · SHA256SUMS-v<X>.txt · SHA256SUMS-latest.txt (jamais archivé)
#   admin  : nyx-admin-<plateforme>-v<X>-… · SHA256SUMS-nyx-admin-v<X>.txt
#
# Usage :
#   scripts/publish-drive.sh --kind client --version 0.2.2 \
#       --files livrables/dernieres-livrables/nyx-v0.2.2-…-release.apk \
#               livrables/dernieres-livrables/SHA256SUMS-v0.2.2.txt \
#       [--doc livrables/documents/audit-report.txt]
#   scripts/publish-drive.sh --kind admin --version 0.3.1 --files <jar|zip|apk…>
#
# Chaque release gère SA famille (client ↔ release.sh ; admin ↔ build scripts
# du dépôt privé) — l'invariant « dernieres-livrables = jeu courant » est
# maintenu par famille. Idempotent : relancé à l'identique, il
# n'archive rien et saute les uploads déjà présents.
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
GDRIVE="scripts/gdrive.py"
RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; NC=$'\033[0m'

KIND=""; VERSION=""; shift_files=(); doc_files=()
while [ $# -gt 0 ]; do
  case "$1" in
    --kind) KIND="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --files) shift; while [ $# -gt 0 ] && [[ "$1" != --* ]]; do shift_files+=("$1"); shift; done ;;
    --doc) shift; while [ $# -gt 0 ] && [[ "$1" != --* ]]; do doc_files+=("$1"); shift; done ;;
    *) echo "option inconnue : $1"; exit 2 ;;
  esac
done
[ "$KIND" = "client" ] || [ "$KIND" = "admin" ] || { echo "✗ --kind client|admin requis"; exit 2; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "✗ --version X.Y.Z requis (reçu : '$VERSION')"; exit 2; }

echo "${YLW}═══ PUBLICATION DRIVE — famille $KIND v$VERSION ═══${NC}"

[ -f "$GDRIVE" ] || { echo "${RED}✗ $GDRIVE introuvable${NC}"; exit 1; }
if ! python3 "$GDRIVE" --status >/dev/null 2>&1; then
  echo "${YLW}⏳ SKIP Drive — credentials absents/invalides (dossier « Tokens », RESTART.md §6)${NC}"
  echo "   → livrables en attente dans le miroir local livrables/ (relancer plus tard)"
  exit 0
fi

# ---------- 1. archivage des remplacées (même famille, version != VERSION) ----------
echo "── Archivage des versions remplacées ──"
ARCHIVED=0
while IFS= read -r line; do
  NAME=$(printf '%s' "$line" | awk -F'  ' '{print $2}')
  [ -n "$NAME" ] || continue
  OLDVER=""; FAM=""
  if [[ "$NAME" =~ ^nyx-v([0-9]+\.[0-9]+\.[0-9]+)- ]] || [[ "$NAME" =~ ^SHA256SUMS-v([0-9]+\.[0-9]+\.[0-9]+)\.txt$ ]]; then
    FAM="client"; OLDVER="${BASH_REMATCH[1]}"
  elif [[ "$NAME" =~ ^nyx-admin-[a-z]+-v([0-9]+\.[0-9]+\.[0-9]+)- ]] || [[ "$NAME" =~ ^SHA256SUMS-nyx-admin-v([0-9]+\.[0-9]+\.[0-9]+)\.txt$ ]]; then
    FAM="admin"; OLDVER="${BASH_REMATCH[1]}"
  else
    continue   # SHA256SUMS-latest.txt, documents égarés… : jamais archivés d'ici
  fi
  [ "$FAM" = "$KIND" ] || continue          # chaque release gère sa famille
  [ "$OLDVER" != "$VERSION" ] || continue   # version courante → reste en place
  ZONE="archives/v${OLDVER}"; [ "$FAM" = "admin" ] && ZONE="archives/nyx-admin-v${OLDVER}"
  if python3 "$GDRIVE" move "livrables/dernieres-livrables/$NAME" "livrables/$ZONE" >/dev/null; then
    echo "${GRN}  ↷ $NAME → $ZONE/${NC}"; ARCHIVED=$((ARCHIVED+1))
  else
    echo "${RED}  ✗ déplacement $NAME${NC}"; exit 1
  fi
done < <(python3 "$GDRIVE" list "livrables/dernieres-livrables" 2>/dev/null)
[ "$ARCHIVED" -eq 0 ] && echo "  rien à archiver (jeu courant déjà à jour)"

# ---------- 2. upload du nouveau jeu ----------
echo "── Upload du jeu courant ──"
for f in "${shift_files[@]}"; do
  [ -f "$f" ] || { echo "${RED}✗ fichier introuvable : $f${NC}"; exit 1; }
  # SHA256SUMS-latest.txt = fichier MUTABLE par définition (il pointe toujours
  # vers la version courante ; même taille à chaque version, contenu différent)
  # → JAMAIS d'upload immuable pour lui (sinon empreinte périmée sur le Drive).
  if [[ "$(basename "$f")" == "SHA256SUMS-latest.txt" ]]; then
    IM=""
  else
    IM="--immutable"
  fi
  if python3 "$GDRIVE" upload "$f" "livrables/dernieres-livrables" $IM >/dev/null; then
    echo "${GRN}  ✓ dernieres-livrables/ ← $(basename "$f")${NC}"
  else
    echo "${RED}  ✗ upload $(basename "$f")${NC}"; exit 1
  fi
done
for f in "${doc_files[@]}"; do
  [ -f "$f" ] || { echo "${RED}✗ document introuvable : $f${NC}"; exit 1; }
  if python3 "$GDRIVE" upload "$f" "livrables/documents" --immutable >/dev/null; then
    echo "${GRN}  ✓ documents/ ← $(basename "$f")${NC}"
  else
    echo "${RED}  ✗ upload $(basename "$f")${NC}"; exit 1
  fi
done

echo "${GRN}═══ PUBLICATION DRIVE TERMINÉE ($ARCHIVED archivée(s)) ═══${NC}"
