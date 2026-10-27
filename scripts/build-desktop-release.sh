#!/usr/bin/env bash
# =============================================================================
# Nyx — Build complet de la livraison DESKTOP Windows (famille desktop ADR-019).
# Session 71 (correctif v0.3.1) : pipeline PERSISTÉ — la session 70 assemblait
# les livrables commande par commande, ce script en est l'enregistrement
# exécutable (règle de persistance des scripts).
#
# Étapes :
#   1. Tests JVM (:app:test) — dont NativesTest (régression « libsodium ne
#      charge pas sans copie système », situation Windows du terrain)
#   2. Fat jar (:app:fatJar) + VÉRIFICATIONS du contenu :
#        - win32-x86-64/sodium.dll   (convention ressources JNA)
#        - linux-x86-64/libsodium.so
#        - nyx-native/libgcc_s_seh-1.dll + libwinpthread-1.dll (compagnes MinGW)
#        - dll skiko Windows
#   3. Selftest headless du jar (--selftest : libsodium / onion v3 /
#      identité scellée / signature NYX1)
#   4. Assemblage payload : jar → payload/bundle/nyx-messenger/ + compagnes
#      MinGW → runtime/bin/ (second ordre de recherche des dépendances)
#   5. Zip portable → livrables/dernieres-livrables/
#   6. Installateur Go : GOOS=windows, SHA-256 du payload en ldflags
#      (+ variante Linux pour le smoke)
#   7. Smoke : installateur -silent -dir <temp> → SHA + essentiels (dont les
#      deux dll compagnes) + selftest du jar INSTALLÉ
#   8. SHA256SUMS-desktop + audit.py strict
#
# Usage :
#   GO_BIN=/chemin/vers/go scripts/build-desktop-release.sh --version 0.3.1
#   (GO_BIN par défaut : go du PATH ; le bac à sable n'en a pas —
#    GO_BIN=/home/z/my-project/diag/go-ex/usr/lib/go-1.24/bin/go)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    *) echo "option inconnue : $1"; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || { echo "✗ --version requis (ex. 0.3.1)"; exit 2; }

GRN=$'\033[32m'; RED=$'\033[31m'; YLW=$'\033[33m'; NC=$'\033[0m'
FAT="desktop/app/build/libs/nyx-messenger-$VERSION-desktop.jar"
BUNDLE=/home/z/my-project/nyx-project/payload/bundle/nyx-messenger
LIVR="livrables/dernieres-livrables"
ZIP="$LIVR/nyx-messenger-windows-v$VERSION.zip"
EXE="$LIVR/nyx-messenger-windows-v$VERSION-installer.exe"
GO_BIN="${GO_BIN:-go}"
SMOKE=/home/z/my-project/diag/desktop-build-smoke

echo "${YLW}═══ BUILD DESKTOP v$VERSION ═══${NC}"

# ---------- 1+2. Tests + fat jar ----------
echo "── tests JVM + fat jar…"
(cd desktop && ./gradlew -q :app:test :app:fatJar)
[ -f "$FAT" ] || { echo "${RED}✗ $FAT absent${NC}"; exit 1; }

# Listing sur FICHIER puis grep — un `unzip -l | grep -q` sous pipefail
# subit des faux négatifs SIGPIPE (grep -q referme le tube, unzip → 141)
# quand l'entrée est tôt dans la liste (cf. nyx-native/).
mkdir -p /home/z/my-project/diag
unzip -l "$FAT" > /home/z/my-project/diag/fatjar.list
check_in_jar() {
  grep -q " $1\$" /home/z/my-project/diag/fatjar.list || { echo "${RED}✗ entrée absente du fat jar : $1${NC}"; exit 1; }
}
check_in_jar "win32-x86-64/sodium.dll"
check_in_jar "linux-x86-64/libsodium.so"
check_in_jar "nyx-native/libgcc_s_seh-1.dll"
check_in_jar "nyx-native/libwinpthread-1.dll"
unzip -l "$FAT" | grep -i 'skiko' | grep -i '\.dll' >/dev/null \
  || { echo "${RED}✗ dll skiko Windows absente du fat jar${NC}"; exit 1; }
echo "${GRN}  ✓ tests verts + natifs vérifiés dans le jar (JNA + compagnes + skiko win)${NC}"

# ---------- 3. Selftest headless du jar ----------
echo "── selftest headless du jar…"
rm -rf "$SMOKE"; mkdir -p "$SMOKE/tmp"
set +e
OUT=$(java -Djava.io.tmpdir="$SMOKE/tmp" -jar "$FAT" --selftest 2>&1); RC=$?
set -e
grep -q "\[SELFTEST\] OK —" <<<"$OUT" || { echo "$OUT"; echo "${RED}✗ selftest a échoué (rc=$RC)${NC}"; exit 1; }
grep -m6 "\[SELFTEST\]" <<<"$OUT"
echo "${GRN}  ✓ selftest OK (rc=$RC)${NC}"

# ---------- 4. Payload ----------
echo "── assemblage payload…"
cp "$FAT" "$BUNDLE/nyx-messenger.jar"
cp desktop/app/src/main/resources/nyx-native/libgcc_s_seh-1.dll "$BUNDLE/runtime/bin/"
cp desktop/app/src/main/resources/nyx-native/libwinpthread-1.dll "$BUNDLE/runtime/bin/"
# v0.3.2 — sources du bundle VERSIONNÉES dans le dépôt (lanceur javaw,
# LISEZMOI correctifs, icône raccourcis) : le dépôt est la source de
# vérité, payload/bundle n'est plus qu'une zone d'assemblage.
cp desktop/installer/bundle/nyx-messenger.bat "$BUNDLE/nyx-messenger.bat"
cp desktop/installer/bundle/LISEZMOI.txt "$BUNDLE/LISEZMOI.txt"
cp desktop/installer/bundle/nyx.ico "$BUNDLE/nyx.ico"
[ -f "$BUNDLE/nyx-messenger.bat" ] && [ -f "$BUNDLE/LISEZMOI.txt" ] && [ -f "$BUNDLE/nyx.ico" ] \
  || { echo "${RED}✗ bat/LISEZMOI/ico absents du bundle${NC}"; exit 1; }
echo "${GRN}  ✓ bundle à jour (jar + compagnes MinGW + lanceur + LISEZMOI + icône)${NC}"

# ---------- 5. Zip portable ----------
echo "── zip portable…"
rm -f "$ZIP"
(cd "$(dirname "$BUNDLE")" && zip -qr "$ROOT/$ZIP" "$(basename "$BUNDLE")")
[ -f "$ZIP" ] || { echo "${RED}✗ zip non créé${NC}"; exit 1; }
echo "${GRN}  ✓ $(du -h "$ZIP" | cut -f1) — $ZIP${NC}"

# ---------- 6. Installateur ----------
echo "── installateur Go (cross GOOS=windows)…"
SHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
cp "$ZIP" desktop/installer/payload.zip
(cd desktop/installer && GOOS=windows "$GO_BIN" build \
   -ldflags "-X main.payloadSHA=$SHA -X main.version=$VERSION" -o "$ROOT/$EXE" .)
(cd desktop/installer && "$GO_BIN" build \
   -ldflags "-X main.payloadSHA=$SHA -X main.version=$VERSION" -o "$SMOKE/installateur-linux" .)
echo "${GRN}  ✓ exe + variante smoke linux (payload SHA $SHA)${NC}"

# ---------- 7. Smoke installateur ----------
echo "── smoke installateur (-silent)…"
DEST="$SMOKE/install"
"$SMOKE/installateur-linux" -silent -dir "$DEST" > "$SMOKE/install.log" 2>&1 \
  || { cat "$SMOKE/install.log"; echo "${RED}✗ installateur a échoué${NC}"; exit 1; }
grep -q "SHA-256 vérifié" "$SMOKE/install.log" \
  || { cat "$SMOKE/install.log"; echo "${RED}✗ SHA du payload non vérifié${NC}"; exit 1; }
grep -q "fichiers essentiels vérifiés" "$SMOKE/install.log" \
  || { cat "$SMOKE/install.log"; echo "${RED}✗ fichiers essentiels non vérifiés${NC}"; exit 1; }
[ -f "$DEST/nyx-messenger/nyx.ico" ] \
  || { echo "${RED}✗ icône nyx.ico absente du payload installé${NC}"; exit 1; }
grep -q "raccourci desktop" "$SMOKE/install.log" \
  && echo "${YLW}  (info) raccourcis tentés sous Linux — chemin résolu par l'API shell Windows au vrai install${NC}" \
  || true
N=$(cd "$DEST/nyx-messenger" && echo $(ls -R | wc -l))
set +e
OUT2=$(cd "$DEST/nyx-messenger" && java -Djava.io.tmpdir="$SMOKE/tmp" -jar nyx-messenger.jar --selftest 2>&1); RC2=$?
set -e
grep -q "\[SELFTEST\] OK —" <<<"$OUT2" \
  || { echo "$OUT2"; echo "${RED}✗ selftest du jar INSTALLÉ a échoué (rc=$RC2)${NC}"; exit 1; }
echo "${GRN}  ✓ installé : SHA + essentiels + $N entrées + selftest du jar installé OK${NC}"

# ---------- 8. SHA256SUMS + audit ----------
echo "── SHA256SUMS + audit strict…"
EXE_SHA=$(sha256sum "$EXE" | cut -d' ' -f1)
cat > "$LIVR/SHA256SUMS-desktop-v$VERSION.txt" <<EOF
$SHA  nyx-messenger-windows-v$VERSION.zip
$EXE_SHA  nyx-messenger-windows-v$VERSION-installer.exe
EOF
mkdir -p "$SMOKE/audit"
python3 scripts/audit.py --strict --report-dir "$SMOKE/audit" | tail -4
mkdir -p livrables/documents
REP=$(ls -t "$SMOKE/audit" | head -1)
cp "$SMOKE/audit/$REP" "livrables/documents/audit-report-desktop-v$VERSION.txt"
echo "${GRN}  ✓ audit copié : livrables/documents/audit-report-desktop-v$VERSION.txt${NC}"

echo "${GRN}═══ BUILD v$VERSION TERMINÉ ═══${NC}"
echo "  zip : $ZIP"
echo "        $SHA"
echo "  exe : $EXE"
echo "        $EXE_SHA"
