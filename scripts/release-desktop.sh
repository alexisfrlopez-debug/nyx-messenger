#!/usr/bin/env bash
# =============================================================================
# Nyx — Publication d'une Release GitHub DESKTOP (règle §9.6, famille desktop
# ADR-019 — session 70). Miroir de release.sh (famille client APK) :
#   1. Vérifie le PAT (dossier dédié « Tokens », §6)
#   2. Crée + pousse le tag git v<version>-desktop (annoté)
#   3. Crée la Release via l'API GitHub (idempotente)
#   4. Téléverse les assets : zip portable + installateur.exe + SHA256SUMS
#      + rapport d'audit
#   5. Drive : archivage des remplacées + upload du jeu courant (§9.4)
#
# Usage :
#   scripts/release-desktop.sh --version 0.3.0 \
#     [--zip livrables/dernieres-livrables/nyx-messenger-windows-v0.3.0.zip] \
#     [--exe livrables/dernieres-livrables/nyx-messenger-windows-v0.3.0-installer.exe] \
#     [--commit <sha>] [--title "…"] [--notes-file docs/releases/v0.3.0-desktop.md] [--prerelease]
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
REPO="alexisfrlopez-debug/nyx-messenger"
CREDS=/home/z/my-project/.credentials
GRN=$'\033[32m'; RED=$'\033[31m'; YLW=$'\033[33m'; NC=$'\033[0m'

VERSION=""; ZIP=""; EXE=""; COMMIT=""; TITLE=""; NOTES_FILE=""; PRERELEASE=false
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --zip) ZIP="$2"; shift 2 ;;
    --exe) EXE="$2"; shift 2 ;;
    --commit) COMMIT="$2"; shift 2 ;;
    --title) TITLE="$2"; shift 2 ;;
    --notes-file) NOTES_FILE="$2"; shift 2 ;;
    --prerelease) PRERELEASE=true; shift ;;
    *) echo "option inconnue : $1"; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || { echo "✗ --version requis (ex. 0.3.0)"; exit 2; }
TAG="v$VERSION-desktop"

echo "${YLW}═══ RELEASE NYX DESKTOP — $TAG ═══${NC}"

# ---------- 0. PAT ----------
PAT=""
for f in "$CREDS/token_git.txt" "$CREDS/tokens/token_git.txt"; do
  [ -f "$f" ] && PAT=$(tr -d '[:space:]' < "$f") && break
done
[ -n "$PAT" ] || { echo "${RED}✗ PAT absent (restaurer token_git.txt, RESTART.md §6)${NC}"; exit 1; }

# ---------- 1. Livrables ----------
if [ -z "$ZIP" ]; then
  ZIP=$(ls -t livrables/dernieres-livrables/nyx-messenger-windows-v"$VERSION".zip 2>/dev/null | head -1 || true)
fi
[ -n "$ZIP" ] && [ -f "$ZIP" ] || { echo "${RED}✗ zip portable v$VERSION introuvable (lancer le build desktop d'abord)${NC}"; exit 1; }
if [ -z "$EXE" ]; then
  EXE=$(ls -t livrables/dernieres-livrables/nyx-messenger-windows-v"$VERSION"-installer.exe 2>/dev/null | head -1 || true)
fi
[ -n "$EXE" ] && [ -f "$EXE" ] || { echo "${RED}✗ installateur.exe v$VERSION introuvable${NC}"; exit 1; }
echo "  zip      : $ZIP"
echo "  exe      : $EXE"
ZIP_SHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
EXE_SHA=$(sha256sum "$EXE" | cut -d' ' -f1)

# ---------- 2. Notes ----------
[ -n "$NOTES_FILE" ] || NOTES_FILE="docs/releases/v$VERSION-desktop.md"
if [ -f "$NOTES_FILE" ]; then
  NOTES=$(cat "$NOTES_FILE")
else
  NOTES="Nyx Desktop $TAG — portage Windows du messager (ADR-019)."
  echo "${YLW}  ⚠ notes absentes ($NOTES_FILE) — résumé générique${NC}"
fi
NOTES="$NOTES

---
**Empreintes SHA-256**

- zip portable : \`$ZIP_SHA\`
- installateur : \`$EXE_SHA\`

**Installation (Windows 10/11, 1 clic)** : télécharger \`nyx-messenger-windows-v$VERSION-installer.exe\`
→ exécuter → raccourcis Bureau/Menu Démarrer créés. Aucun prérequis : Java 21
(runtime Temurin) ET Tor 15.0.26 (expert bundle) sont embarqués. Le zip portable
est l'alternative sans installation (décompresser et lancer nyx-messenger.bat).

**Interopérabilité** : messagerie complète avec les téléphones Android Nyx
(même protocole filaire) — appels audio/visio en phase 2 (ADR-019 §6)."
[ -n "$TITLE" ] || TITLE="Nyx Desktop $TAG — messagerie Windows"

# ---------- 3. Tag ----------
[ -n "$COMMIT" ] || COMMIT=$(git rev-parse HEAD)
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "  tag      : $TAG existe déjà (réutilisé)"
else
  git tag -a "$TAG" "$COMMIT" -m "Nyx Desktop $TAG — zip $ZIP_SHA / exe $EXE_SHA" \
    && echo "  tag      : $TAG créé sur $COMMIT"
fi
PUSH_URL="https://alexisfrlopez-debug:${PAT}@github.com/${REPO}.git"
git push "$PUSH_URL" "refs/tags/$TAG" 2>&1 | rg -v "remote:" | head -2 \
  && echo "${GRN}  ✓ tag poussé${NC}" || { echo "${RED}✗ push du tag échoué${NC}"; exit 1; }

# ---------- 4. Release ----------
API="https://api.github.com/repos/$REPO"
BODY_JSON=$(python3 - "$TITLE" "$TAG" "$NOTES" "$PRERELEASE" <<'PY'
import json, sys
print(json.dumps({
    "tag_name": sys.argv[2], "name": sys.argv[1], "body": sys.argv[3],
    "draft": False, "prerelease": sys.argv[4] == "true"
}))
PY
)
HTTP=$(curl -s -o /tmp/nyx-desktop-release.json -w "%{http_code}" \
  -X POST "$API/releases" -H "Authorization: Bearer $PAT" \
  -H "Accept: application/vnd.github+json" -d "$BODY_JSON")
if [ "$HTTP" = "201" ]; then
  REL_ID=$(python3 -c "import json;print(json.load(open('/tmp/nyx-desktop-release.json'))['id'])")
  echo "${GRN}  ✓ release créée (id $REL_ID)${NC}"
elif [ "$HTTP" = "422" ]; then
  REL_ID=$(curl -s "$API/releases/tags/$TAG" -H "Authorization: Bearer $PAT" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  echo "${YLW}  release déjà existante (id $REL_ID) — assets ajoutés${NC}"
else
  echo "${RED}✗ création release HTTP $HTTP${NC}"; head -3 /tmp/nyx-desktop-release.json; exit 1
fi

# ---------- 5. Assets ----------
upload() {
  EXISTING=$(curl -s "$API/releases/$REL_ID/assets" -H "Authorization: Bearer $PAT" \
    | python3 -c "import json,sys;print(','.join(a['name'] for a in json.load(sys.stdin)))")
  if echo ",$EXISTING," | rg -q ",$2,"; then
    echo "${YLW}  ⏳ asset $2 déjà présent${NC}"; return 0
  fi
  HTTP=$(curl -s -o /tmp/nyx-desktop-asset.json -w "%{http_code}" \
    -X POST "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$2" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: $3" --data-binary "@$1")
  [ "$HTTP" = "201" ] && echo "${GRN}  ✓ asset $2${NC}" || { echo "${RED}✗ asset $2 (HTTP $HTTP)${NC}"; return 1; }
}
upload "$ZIP" "$(basename "$ZIP")" "application/zip"
upload "$EXE" "$(basename "$EXE")" "application/vnd.microsoft.portable-executable"
SUMS="livrables/dernieres-livrables/SHA256SUMS-desktop-v$VERSION.txt"
[ -f "$SUMS" ] && upload "$SUMS" "SHA256SUMS-desktop-v$VERSION.txt" "text/plain"
AUDIT="livrables/documents/audit-report-desktop-v$VERSION.txt"
[ -f "$AUDIT" ] && upload "$AUDIT" "audit-report-desktop-v$VERSION.txt" "text/plain"

# ---------- 6. Drive (§9.4 — famille desktop) ----------
PUBLISH="scripts/publish-drive.sh"
if [ -x "$PUBLISH" ]; then
  DRIVE_FILES=("$ZIP" "$EXE")
  [ -f "$SUMS" ] && DRIVE_FILES+=("$SUMS")
  DRIVE_DOCS=()
  [ -f "$AUDIT" ] && DRIVE_DOCS+=("$AUDIT")
  if "$PUBLISH" --kind desktop --version "$VERSION" --files "${DRIVE_FILES[@]}" \
     $( [ ${#DRIVE_DOCS[@]} -gt 0 ] && printf -- '--doc %s ' "${DRIVE_DOCS[@]}" ); then
    echo "${GRN}  ✓ Drive /livrables à jour (famille desktop)${NC}"
  else
    echo "${YLW}  ⚠ publication Drive incomplète — relancer publish-drive.sh --kind desktop --version $VERSION${NC}"
  fi
fi

REL_URL=$(curl -s "$API/releases/tags/$TAG" -H "Authorization: Bearer $PAT" | python3 -c "import json,sys;print(json.load(sys.stdin).get('html_url',''))")
echo "${GRN}═══ RELEASE $TAG PUBLIÉE → $REL_URL ═══${NC}"
