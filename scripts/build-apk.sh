#!/usr/bin/env bash
# =============================================================================
# Nyx — Pipeline de build APK (règle permanente, RESTART.md §9)
#
#   1. Audit pré-compilation STRICT (bloquant : CRIT/MAJOR → pas de build)
#   2. Build release signé (universal APK, 4 ABI, Tor embarqué)
#   3. Vérification d'autonomie de l'APK (signature, Tor embarqué, ABIs,
#      permissions, absence de splits) — CRIT si un manquement
#   4. Livraison : livrables/ (local) → sync.sh pousse vers Drive /livrables
#
# Usage : scripts/build-apk.sh [--skip-audit]   (--skip-audit : interdit en règle générale)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; NC=$'\033[0m'
STAMP=$(date +%Y%m%d-%H%M%S)
LIVRABLES="$ROOT/livrables"
mkdir -p "$LIVRABLES"

# ---------- environnement ----------
export JAVA_HOME="${JAVA_HOME:-/home/z/my-project/tools/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/home/z/my-project/tools/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/z/my-project/gradle-home}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "${RED}JDK introuvable ($JAVA_HOME)${NC}"; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-35" ] || { echo "${RED}SDK android-35 introuvable ($ANDROID_HOME)${NC}"; exit 1; }
command -v python3 >/dev/null || { echo "${RED}python3 requis${NC}"; exit 1; }

# ---------- 1. audit (bloquant) ----------
if [ "${1:-}" != "--skip-audit" ]; then
  echo "${YLW}═══ 1/4 AUDIT PRÉ-COMPILATION (strict) ═══${NC}"
  python3 scripts/audit.py --root . --strict
  echo "${GRN}Audit : PASSED${NC}"
else
  echo "${YLW}⚠  audit SKIPPÉ — interdit hors exception documentée${NC}"
fi

# ---------- 2. build ----------
echo "${YLW}═══ 2/4 BUILD RELEASE SIGNÉ ═══${NC}"
( cd android && ./gradlew :app:assembleRelease --console=plain -q )
APK_SRC=$(ls -t android/app/build/outputs/apk/release/*.apk | head -1)
[ -f "$APK_SRC" ] || { echo "${RED}APK introuvable après build${NC}"; exit 1; }
echo "APK brut : $APK_SRC ($(du -h "$APK_SRC" | cut -f1))"

# ---------- 3. vérification d'autonomie ----------
echo "${YLW}═══ 3/4 VÉRIFICATION AUTONOMIE APK ═══${NC}"
BT="$ANDROID_HOME/build-tools/34.0.0"
FAIL=0
check() { # check <label> <ok:0/1> <détail>
  if [ "$2" -eq 0 ]; then echo "  ${GRN}✓${NC} $1 ${3:-}"; else echo "  ${RED}✗ $1 ${3:-}${NC}"; FAIL=1; fi
}

# a) signature valide (installation 1 clic)
if "$BT/apksigner" verify --print-certs "$APK_SRC" > /tmp/apkcerts.txt 2>&1; then
  SIG_ALGO=$("$BT/apksigner" verify -v "$APK_SRC" | rg -o "Verified using .*" | head -1 | cut -d: -f1)
  check "Signature valide (installation 1 clic)" 0
else
  check "Signature valide (installation 1 clic)" 1
fi

# b) Tor EMBARQUÉ présent par ABI
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  unzip -l "$APK_SRC" | rg -q "lib/$abi/libTor.so" \
    && check "Tor embarqué $abi (libTor.so)" 0 || check "Tor embarqué $abi (libTor.so)" 1
done

# c) libs natives critiques par ABI
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  MISS=""
  for lib in libsodium.so libsqlcipher.so libjnidispatch.so; do
    unzip -l "$APK_SRC" | rg -q "lib/$abi/$lib" || MISS="$MISS $lib"
  done
  [ -z "$MISS" ] && check "Natives critiques $abi (sodium/sqlcipher/jna)" 0 \
                    || check "Natives critiques $abi" 1 "manquent:$MISS"
done

# d) pas de split APK (un fichier unique = installation 1 clic)
N_SPLITS=$(ls android/app/build/outputs/apk/release/*.apk 2>/dev/null | wc -l)
[ "$N_SPLITS" -eq 1 ] && check "APK universel unique (pas de splits)" 0 \
                     || check "APK universel unique" 1 "$N_SPLITS APK trouvés"

# e) permissions : liste fermée minimale (+ permissions auto-définies par notre
#    propre namespace — ex. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION générée par AGP,
#    protection-level signature : jamais une dépendance externe).
#    USE_BIOMETRIC / USE_FINGERPRINT : déverrouillage LOCAL opt-in (ADR-010 §3) —
#    apportées par androidx.biometric au manifest fusionné ; l'OS vérifie,
#    l'app ne reçoit qu'un succès/échec, aucune donnée biométrique n'est lue.
BADGE=$("$BT/aapt2" dump badging "$APK_SRC")
BAD_PERMS=$(echo "$BADGE" | rg "uses-permission" \
  | rg -v "INTERNET|ACCESS_NETWORK_STATE|FOREGROUND_SERVICE|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS|USE_BIOMETRIC|USE_FINGERPRINT" \
  | rg -v "net\.nyx\.messenger\.[A-Z_]+" || true)
[ -z "$BAD_PERMS" ] && check "Permissions minimales (liste fermée)" 0 \
                   || check "Permissions non autorisées" 1 "$BAD_PERMS"

# f) aucune dépendance aux services tiers dans les dex
if unzip -p "$APK_SRC" classes*.dex 2>/dev/null | rg -q "com/google/android/gms"; then
  check "Aucun service Google/GMS dans l'APK" 1
else
  check "Aucun service Google/GMS dans l'APK" 0
fi

# g) identité du paquet
PKG=$(echo "$BADGE" | rg -o "package: name='[^']+'" | cut -d"'" -f2)
VER=$(echo "$BADGE" | rg -o "versionName='[^']+'" | cut -d"'" -f2)
NATIVE=$(echo "$BADGE" | rg -o "native-code:.*" | head -1)
check "Package $PKG version $VER" 0
echo "  ℹ  $NATIVE"

[ "$FAIL" -ne 0 ] && { echo "${RED}⛔ VÉRIFICATION AUTONOMIE : ÉCHEC — APK non livré${NC}"; exit 2; }
echo "${GRN}Autonomie : vérifiée (Tor embarqué, signé, universel, zéro dépendance externe)${NC}"

# ---------- 4. livraison ----------
echo "${YLW}═══ 4/4 LIVRAISON ═══${NC}"
VERSION="$VER"
NAME="nyx-v${VERSION}-${STAMP}-release.apk"
cp "$APK_SRC" "$LIVRABLES/$NAME"
SHA=$(sha256sum "$LIVRABLES/$NAME" | cut -d' ' -f1)
echo "$SHA  $NAME" > "$LIVRABLES/SHA256SUMS-latest.txt"
# rapport d'audit de ce cycle joint au livrable
LAST_REPORT=$(ls -t reports/audit-*.md 2>/dev/null | head -1 || true)
[ -n "$LAST_REPORT" ] && cp "$LAST_REPORT" "$LIVRABLES/audit-report.txt"

cat << EOF

${GRN}════════════════════════════════════════════════${NC}
${GRN}✅ LIVRABLE PRODUIT${NC}
  APK      : $LIVRABLES/$NAME
  Taille   : $(du -h "$LIVRABLES/$NAME" | cut -f1)
  SHA-256  : $SHA
  Suivant  : scripts/sync.sh  (GitHub + Drive /livrables)
${GRN}════════════════════════════════════════════════${NC}
EOF
