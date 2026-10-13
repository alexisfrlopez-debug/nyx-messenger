#!/usr/bin/env bash
# =============================================================================
# Nyx — Publication d'une Release GitHub (règle permanente, RESTART.md §9.6)
#
#   1. Vérifie le PAT (dossier dédié « Tokens », restauré en .credentials/)
#   2. Crée + pousse le tag git v<version> (annoté, sur --commit ou HEAD)
#   3. Crée la Release via l'API GitHub (idempotent : réutilisée si existante)
#   4. Téléverse les assets : APK signé + SHA256SUMS + rapport d'audit
#
# Usage :
#   scripts/release.sh --version 0.1.1 \
#     [--apk livrables/dernieres-livrables/nyx-v0.1.1-…-release.apk] [--commit <sha>]
#     [--title "…"] [--notes-file docs/releases/v0.1.1.md] [--prerelease]
#
# Notes de version : par convention `docs/releases/v<version>.md` (versionnées
# dans le dépôt) ; à défaut un résumé générique est produit.
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
REPO="alexisfrlopez-debug/nyx-messenger"
CREDS=/home/z/my-project/.credentials
RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; NC=$'\033[0m'

VERSION=""; APK=""; COMMIT=""; TITLE=""; NOTES_FILE=""; PRERELEASE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --commit) COMMIT="$2"; shift 2 ;;
    --title) TITLE="$2"; shift 2 ;;
    --notes-file) NOTES_FILE="$2"; shift 2 ;;
    --prerelease) PRERELEASE=true; shift ;;
    *) echo "option inconnue : $1"; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || { echo "✗ --version requis (ex. 0.1.1)"; exit 2; }
TAG="v$VERSION"

echo "${YLW}═══ RELEASE NYX — $TAG ═══${NC}"

# ---------- 0. PAT ----------
PAT=""
for f in "$CREDS/token_git.txt" "$CREDS/tokens/token_git.txt"; do
  [ -f "$f" ] && PAT=$(tr -d '[:space:]' < "$f") && break
done
[ -n "$PAT" ] || { echo "${RED}✗ PAT absent (restaurer token_git.txt du dossier Drive dédié « Tokens », RESTART.md §6)${NC}"; exit 1; }

# ---------- 1. APK ----------
if [ -z "$APK" ]; then
  APK=$(ls -t livrables/dernieres-livrables/nyx-v"$VERSION"*.apk livrables/nyx-v"$VERSION"*.apk 2>/dev/null | head -1 || true)
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "${RED}✗ APK v$VERSION introuvable dans livrables/ (lancer scripts/build-apk.sh d'abord)${NC}"; exit 1; }
echo "  APK      : $APK"
APK_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
# empreintes « latest » : layout session 13 (dernieres-livrables/) ou ancien
SUMS_LATEST="livrables/dernieres-livrables/SHA256SUMS-latest.txt"
[ -f "$SUMS_LATEST" ] || SUMS_LATEST="livrables/SHA256SUMS-latest.txt"

# ---------- 2. Notes ----------
if [ -z "$NOTES_FILE" ]; then NOTES_FILE="docs/releases/$TAG.md"; fi
if [ -f "$NOTES_FILE" ]; then
  NOTES=$(cat "$NOTES_FILE")
else
  NOTES="Livrable Nyx $TAG. APK universel signé, Tor embarqué (4 ABI), 100 % autonome.
SHA-256 : \`$APK_SHA\`"
  echo "${YLW}  ⚠ notes absentes ($NOTES_FILE) — résumé générique utilisé${NC}"
fi
NOTES="$NOTES

---
**Empreinte SHA-256** : \`$APK_SHA\`

**Installation (Android 8+, 1 clic)** : télécharger l'APK → autoriser l'installation
de cette source → installer. Aucune dépendance externe : Tor est embarqué, aucun
compte, aucun service tiers."
[ -n "$TITLE" ] || TITLE="Nyx $TAG"

# ---------- 3. Tag ----------
[ -n "$COMMIT" ] || COMMIT=$(git rev-parse HEAD)
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "  tag      : $TAG existe déjà (réutilisé)"
else
  git tag -a "$TAG" "$COMMIT" -m "Nyx $TAG — APK $APK_SHA" \
    && echo "  tag      : $TAG créé sur $COMMIT"
fi
PUSH_URL="https://alexisfrlopez-debug:${PAT}@github.com/${REPO}.git"
git push "$PUSH_URL" "refs/tags/$TAG" 2>&1 | rg -v "remote:" | head -2 \
  && echo "${GRN}  ✓ tag poussé${NC}" || { echo "${RED}✗ push du tag échoué${NC}"; exit 1; }

# ---------- 4. Release (créer ou récupérer) ----------
API="https://api.github.com/repos/$REPO"
BODY_JSON=$(python3 - "$TITLE" "$TAG" "$NOTES_FILE" "$NOTES" "$PRERELEASE" <<'PY'
import json, sys
title, tag, notes_file, notes, pre = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
print(json.dumps({
    "tag_name": tag, "name": title, "body": notes,
    "draft": False, "prerelease": pre == "true"
}))
PY
)
HTTP=$(curl -s -o /tmp/nyx-release.json -w "%{http_code}" \
  -X POST "$API/releases" -H "Authorization: Bearer $PAT" \
  -H "Accept: application/vnd.github+json" -d "$BODY_JSON")
if [ "$HTTP" = "201" ]; then
  REL_ID=$(python3 -c "import json;print(json.load(open('/tmp/nyx-release.json'))['id'])")
  echo "${GRN}  ✓ release créée (id $REL_ID)${NC}"
elif [ "$HTTP" = "422" ]; then
  REL_ID=$(curl -s "$API/releases/tags/$TAG" -H "Authorization: Bearer $PAT" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  echo "${YLW}  release déjà existante (id $REL_ID) — assets ajoutés/maj${NC}"
else
  echo "${RED}✗ création release HTTP $HTTP${NC}"; head -3 /tmp/nyx-release.json; exit 1
fi

# ---------- 5. Assets ----------
upload() { # $1=fichier  $2=nom d'asset  $3=content-type
  EXISTING=$(curl -s "$API/releases/$REL_ID/assets" -H "Authorization: Bearer $PAT" \
    | python3 -c "import json,sys;print(','.join(a['name'] for a in json.load(sys.stdin)))")
  if echo ",$EXISTING," | rg -q ",$2,"; then
    echo "${YLW}  ⏳ asset $2 déjà présent (SUPPRIMER l'asset sur GitHub pour le remplacer)${NC}"; return 0
  fi
  HTTP=$(curl -s -o /tmp/nyx-asset.json -w "%{http_code}" \
    -X POST "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$2" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: $3" --data-binary "@$1")
  [ "$HTTP" = "201" ] && echo "${GRN}  ✓ asset $2${NC}" || { echo "${RED}✗ asset $2 (HTTP $HTTP)${NC}"; return 1; }
}

upload "$APK" "$(basename "$APK")" "application/vnd.android.package-archive"

# Empreintes + audit : copies nominatives par version (sinon « latest » écrase le sens)
cp -f "$SUMS_LATEST" "livrables/SHA256SUMS-$TAG.txt" 2>/dev/null || true
[ -f "livrables/SHA256SUMS-$TAG.txt" ] && upload "livrables/SHA256SUMS-$TAG.txt" "SHA256SUMS-$TAG.txt" "text/plain"
[ -f livrables/audit-report.txt ] && upload livrables/audit-report.txt "audit-report-$TAG.txt" "text/plain"

# ---------- 6. Drive : archivage des remplacées + upload du jeu courant (§9.4) ----------
# Systématique à CHAQUE release (session 18) : l'ancien APK client quitte
# dernieres-livrables/ pour archives/v<ancienne>/, le nouveau jeu est téléversé.
PUBLISH="scripts/publish-drive.sh"
if [ -x "$PUBLISH" ]; then
  DRIVE_FILES=("$APK")
  [ -f "livrables/SHA256SUMS-$TAG.txt" ] && DRIVE_FILES+=("livrables/SHA256SUMS-$TAG.txt")
  [ -f "$SUMS_LATEST" ] && DRIVE_FILES+=("$SUMS_LATEST")
  DRIVE_DOCS=()
  [ -f "livrables/audit-report.txt" ] && DRIVE_DOCS+=("livrables/audit-report.txt")
  if "$PUBLISH" --kind client --version "$VERSION" --files "${DRIVE_FILES[@]}" --doc "${DRIVE_DOCS[@]}"; then
    echo "${GRN}  ✓ Drive /livrables à jour (§9.4)${NC}"
  else
    echo "${YLW}  ⚠ publication Drive incomplète — relancer scripts/publish-drive.sh --kind client --version $VERSION${NC}"
  fi
else
  echo "${YLW}  ⚠ scripts/publish-drive.sh absent — publication Drive à faire manuellement${NC}"
fi

REL_URL=$(curl -s "$API/releases/tags/$TAG" -H "Authorization: Bearer $PAT" | python3 -c "import json,sys;print(json.load(sys.stdin).get('html_url',''))")
echo "${GRN}═══ RELEASE $TAG PUBLIÉE → $REL_URL ═══${NC}"
