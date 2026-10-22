#!/usr/bin/env bash
# =============================================================================
# Nyx — Pipeline de build APK (règle permanente, RESTART.md §9)
#
#   1. Audit pré-compilation STRICT (bloquant : CRIT/MAJOR → pas de build)
#   2. Build release signé — DEUX variants depuis v0.2.35 (fusion demandée
#      par l'opérateur : le réplica temporaire embarqué, validé en « test 2 »
#      de la v0.2.19 à la v0.2.34, est intégré AUX DEUX versions ; chaque
#      version livre un APK « standard » et un APK « test » à gros bouton
#      rouge d'export de logs — le flavor diag2 est supprimé)
#   3. Vérification d'autonomie des 2 APK (signature, Tor embarqué, ABIs,
#      permissions, absence de splits) — CRIT si un manquement
#   4. Livraison : livrables/dernieres-livrables/ (local) — l'ancienne version
#      est archivée dans livrables/archives/v<version>/ ; sync.sh pousse le même
#      rangement vers le Drive /livrables (miroir, §9.4)
#
# Usage : scripts/build-apk.sh [--skip-audit]   (--skip-audit : interdit en règle générale)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; NC=$'\033[0m'
STAMP=$(date +%Y%m%d-%H%M%S)
LIVRABLES="$ROOT/livrables/dernieres-livrables"
ARCHIVES="$ROOT/livrables/archives"
DOCS="$ROOT/livrables/documents"
mkdir -p "$LIVRABLES" "$ARCHIVES" "$DOCS"

# ---------- environnement ----------
export JAVA_HOME="${JAVA_HOME:-/home/z/my-project/tools/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-/home/z/my-project/tools/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/z/my-project/gradle-home}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "${RED}JDK introuvable ($JAVA_HOME)${NC}"; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-35" ] || { echo "${RED}SDK android-35 introuvable ($ANDROID_HOME)${NC}"; exit 1; }
command -v python3 >/dev/null || { echo "${RED}python3 requis${NC}"; exit 1; }

# ---------- 1bis. GARDE-FOU réplica d'amorçage (session 29) ----------
# Incident v0.2.14 (16/10 11:03) : replica-bootstrap.properties (GIT-IGNORÉ —
# règle zéro ID d'infra sur le dépôt public) perdu au reset du bac à sable →
# build compilé avec BOOTSTRAP_REPLICAS="" → APK livré SANS réplica d'amorçage
# → « aucun réplica d'amorçage embarqué — réseau en attente de configuration »
# sur le terrain. L'audit et les tests JVM ne voient pas un BuildConfig vide.
if [ "${NYX_ALLOW_NO_REPLICA:-0}" != "1" ]; then
  PROP="$ROOT/android/replica-bootstrap.properties"
  if [ ! -f "$PROP" ]; then
    echo "${RED}✗ android/replica-bootstrap.properties ABSENT${NC}"
    echo "  → l'APK serait livré sans réplica d'amorçage (incident v0.2.14, session 29)."
    echo "  → restaurer le fichier (Drive, dossier de travail racine) ou :"
    echo "     printf 'replicas=<onion>:80\n' > android/replica-bootstrap.properties"
    echo "  → build de dev pur SANS réplica : NYX_ALLOW_NO_REPLICA=1 $0"
    exit 1
  fi
  grep -q "^replicas=.*\.onion" "$PROP" || {
    echo "${RED}✗ replica-bootstrap.properties sans entrée onion (clé 'replicas')${NC}"; exit 1
  }
  echo "réplica d'amorçage : $(grep '^replicas=' "$PROP" | head -1 | cut -d= -f2 | cut -c1-16)…"
  # test-upload.properties (git-ignoré aussi) : son absence = build test sans
  # upload Drive (message visible dans l'app, mais différé silencieux au build).
  [ -f "$ROOT/android/test-upload.properties" ] || \
    echo "${YLW}⚠ android/test-upload.properties ABSENT — l'APK test n'embarquera PAS d'upload Drive (restaurer du Drive, racine du dossier de travail)${NC}"
fi

# ---------- 1. audit (bloquant) ----------
if [ "${1:-}" != "--skip-audit" ]; then
  echo "${YLW}═══ 1/4 AUDIT PRÉ-COMPILATION (strict) ═══${NC}"
  python3 scripts/audit.py --root . --strict
  echo "${GRN}Audit : PASSED${NC}"
else
  echo "${YLW}⚠  audit SKIPPÉ — interdit hors exception documentée${NC}"
fi

# ---------- 2. build ----------
echo "${YLW}═══ 2/4 BUILD RELEASE SIGNÉ — variants standard + test ═══${NC}"
# Mémoire bornée par ligne de commande (priorité sur GRADLE_USER_HOME/gradle.properties
# qui porte -Xmx2g) : conteneur 4,1 Go — sans borne, le JVM culmine à ~2,3 Go RSS
# et l'OOM-killer du noyau tue le build (session 15). Kotlin in-process + 1 worker.
# v0.2.35 : espace disque restreint — les variants sont compilés SÉQUENTIELLEMENT
# (build → copie APK en /tmp → clean → variant suivant) pour ne jamais garder
# deux jeux d'intermédiaires sur le disque.
build_one() { # build_one <Flavor> <glob-sortie-attendue>
  local FLAVOR="$1" OUT="$2" FOUND
  ( cd android && ./gradlew ":app:assemble${FLAVOR}Release" --console=plain -q --no-daemon \
      -Dorg.gradle.jvmargs="-Xmx1408m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" \
      -Dorg.gradle.workers.max=1 )
  FOUND=$(ls -t $OUT 2>/dev/null | head -1)
  [ -n "$FOUND" ] && [ -f "$FOUND" ] || { echo "${RED}APK $FLAVOR introuvable après build ($OUT)${NC}"; exit 1; }
  cp "$FOUND" "/tmp/nyx-apk-$FLAVOR.apk"
  # v0.2.35 — anti-splits : mémorise le NOMBRE d'APK produits par CE variant
  # (le contrôle d) de check_apk ne doit PAS compter /tmp — tous les variants
  # s'y accumulent en mode séquentiel — mais le compte du BUILD de CE variant).
  ls $OUT 2>/dev/null | wc -l > "/tmp/nyx-apk-count-$FLAVOR.txt"
  ( cd android && ./gradlew clean --console=plain -q --no-daemon \
      -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" -Dorg.gradle.workers.max=1 )
}
build_one Standard "android/app/build/outputs/apk/standard/release/*.apk"
build_one Diag "android/app/build/outputs/apk/diag/release/*.apk"
APK_STD=/tmp/nyx-apk-Standard.apk
APK_TEST=/tmp/nyx-apk-Diag.apk
echo "APK standard : $APK_STD ($(du -h "$APK_STD" | cut -f1))"
echo "APK test     : $APK_TEST ($(du -h "$APK_TEST" | cut -f1))"

# ---------- 3. vérification d'autonomie (les 2 APK) ----------
BT="$ANDROID_HOME/build-tools/34.0.0"
check_apk() { # check_apk <chemin-apk> <étiquette>
  local APK_SRC="$1" LABEL="$2"
  echo "${YLW}── Autonomie : $LABEL ──${NC}"
  local FAIL=0
  check() { # check <label> <ok:0/1> <détail>
    if [ "$2" -eq 0 ]; then echo "  ${GRN}✓${NC} $1 ${3:-}"; else echo "  ${RED}✗ $1 ${3:-}${NC}"; FAIL=1; fi
  }

  # a) signature valide (installation 1 clic)
  if "$BT/apksigner" verify --print-certs "$APK_SRC" > /tmp/apkcerts.txt 2>&1; then
    check "Signature valide (installation 1 clic)" 0
  else
    check "Signature valide (installation 1 clic)" 1
  fi

  # Listing du zip UNE FOIS dans un fichier — le pipe `unzip | rg -q` est
  # RACEUX : rg -q ferme le tube dès le premier match → SIGPIPE chez unzip →
  # pipefail transforme un MATCH en échec (v0.2.18 : « Tor embarqué x86 ✗ »
  # et « libsodium x86 ✗ » ALÉATOIRES sur un APK qui les contient).
  local APK_LIST="/tmp/apklist-$LABEL.txt"
  unzip -l "$APK_SRC" > "$APK_LIST" 2>/dev/null

  # b) Tor EMBARQUÉ présent par ABI
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    rg -q "lib/$abi/libTor.so" "$APK_LIST" \
      && check "Tor embarqué $abi (libTor.so)" 0 || check "Tor embarqué $abi (libTor.so)" 1
  done

  # c) libs natives critiques par ABI
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    MISS=""
    for lib in libsodium.so libsqlcipher.so libjnidispatch.so; do
      rg -q "lib/$abi/$lib" "$APK_LIST" || MISS="$MISS $lib"
    done
    [ -z "$MISS" ] && check "Natives critiques $abi (sodium/sqlcipher/jna)" 0 \
                      || check "Natives critiques $abi" 1 "manquent:$MISS"
  done

  # d) pas de split APK (un fichier unique = installation 1 clic) — v0.2.35 :
  #    compte les sorties du BUILD de CE VARIANT (fichier /tmp/nyx-apk-count-<F>
  #    écrit par build_one), PAS le contenu de /tmp où les 2 variants
  #    s'accumulent en mode séquentiel (faux positif « 2 APK trouvés »).
  _FLAVOR="$(basename "$APK_SRC" .apk)"; _FLAVOR="${_FLAVOR#nyx-apk-}"
  N_SPLITS=$(cat "/tmp/nyx-apk-count-$_FLAVOR.txt" 2>/dev/null || echo 0)
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
    | rg -v "INTERNET|ACCESS_NETWORK_STATE|FOREGROUND_SERVICE|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS|USE_BIOMETRIC|USE_FINGERPRINT|RECORD_AUDIO|CAMERA" \
    | rg -v "net\.nyx\.messenger(\.test)?\.[A-Z_]+" || true)
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

  # NB : « [ … ] && { … } » en dernière instruction de fonction retourne 1 quand
  # FAIL=0 — avec set -e cela tue le pipeline (bug session 20, standard passait,
  # le script s'arrêtait AVANT la vérification du variant test). Forme if/then.
  if [ "$FAIL" -ne 0 ]; then
    echo "${RED}⛔ VÉRIFICATION AUTONOMIE ($LABEL) : ÉCHEC — APK non livré${NC}"; exit 2
  fi
}
check_apk "$APK_STD" "standard"
check_apk "$APK_TEST" "test"
echo "${GRN}Autonomie : vérifiée (Tor embarqué, signé, universel, zéro dépendance externe)${NC}"

# ---------- 4. livraison ----------
echo "${YLW}═══ 4/4 LIVRAISON ═══${NC}"
# versionName = X.Y.Z (standard) et X.Y.Z-test (test flavor) → version commune X.Y.Z
VERSION=$(aapt2="$BT/aapt2"; "$aapt2" dump badging "$APK_STD" | rg -o "versionName='[^']+'" | cut -d"'" -f2)
NAME_STD="nyx-v${VERSION}-${STAMP}-release.apk"
NAME_TEST="nyx-v${VERSION}-${STAMP}-test.apk"

# a) archivage de la version précédente (rangement session 13 : une version
#    remplacée quitte dernieres-livrables/ pour archives/v<version>/)
shopt -s nullglob
for old_apk in "$LIVRABLES"/nyx-v*-release.apk; do
  case "$(basename "$old_apk")" in
    "nyx-v${VERSION}-"*) continue ;;  # même version → remplacement, pas archivage
  esac
  oldver=$(basename "$old_apk" | sed -E 's/^nyx-v([0-9]+\.[0-9]+\.[0-9]+)-.*/\1/')
  mkdir -p "$ARCHIVES/v${oldver}"
  mv -f "$old_apk" "$ARCHIVES/v${oldver}/"
  [ -f "$LIVRABLES/SHA256SUMS-v${oldver}.txt" ] \
    && mv -f "$LIVRABLES/SHA256SUMS-v${oldver}.txt" "$ARCHIVES/v${oldver}/"
  echo "  ↷ v${oldver} archivée → livrables/archives/v${oldver}/"
done
shopt -u nullglob

# b) dépôt des deux livrables
cp "$APK_STD"  "$LIVRABLES/$NAME_STD"
cp "$APK_TEST" "$LIVRABLES/$NAME_TEST"
rm -f /tmp/nyx-apk-Standard.apk /tmp/nyx-apk-Diag.apk
SHA_STD=$(sha256sum "$LIVRABLES/$NAME_STD" | cut -d' ' -f1)
SHA_TEST=$(sha256sum "$LIVRABLES/$NAME_TEST" | cut -d' ' -f1)
{
  echo "$SHA_STD  $NAME_STD"
  echo "$SHA_TEST  $NAME_TEST"
} > "$LIVRABLES/SHA256SUMS-latest.txt"
{
  echo "$SHA_STD  $NAME_STD"
  echo "$SHA_TEST  $NAME_TEST"
} > "$LIVRABLES/SHA256SUMS-v${VERSION}.txt"
# rapport d'audit de ce cycle joint → livrables/documents/
LAST_REPORT=$(ls -t reports/audit-*.md 2>/dev/null | head -1 || true)
[ -n "$LAST_REPORT" ] && cp "$LAST_REPORT" "$DOCS/audit-report.txt"

cat << EOF

${GRN}════════════════════════════════════════════════${NC}
${GRN}✅ LIVRABLES PRODUITS (standard + test — réplica embarqué intégré aux deux)${NC}
  APK std    : $LIVRABLES/$NAME_STD
               SHA-256 $SHA_STD
  APK test   : $LIVRABLES/$NAME_TEST
               SHA-256 $SHA_TEST
  Suivant    : scripts/release.sh --version $VERSION --apk "$LIVRABLES/$NAME_STD" --test-apk "$LIVRABLES/$NAME_TEST"
${GRN}════════════════════════════════════════════════${NC}
EOF
