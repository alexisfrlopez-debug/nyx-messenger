#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyx — Audit systématique pré-compilation (règle permanente, voir RESTART.md §9).

Exécution :  python3 scripts/audit.py [--root RACINE_DU_PROJET] [--strict]
             (wrapper : scripts/audit.sh)

Verdicts par catégorie :
  CRIT   → bloquant TOUJOURS (autonomie APK, cohérence, secrets)
  MAJOR  → bloquant en --strict (code mort, redondances, ressources inutiles)
  MINOR  → jamais bloquant (TODO/FIXME, inventaire, conseils)

Sortie : console colorée + rapport Markdown (reports/audit-<date>.md) + code retour
  0 = rien de bloquant · 2 = CRIT présent · 3 = MAJOR en mode strict · 1 = erreur interne

Faux positifs documentés : scripts/audit-allowlist.txt (une occurrence « fichier:motif » par ligne).
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- utilitaires

CRIT, MAJOR, MINOR = "CRIT", "MAJOR", "MINOR"

class Audit:
    def __init__(self, root: Path, strict: bool):
        self.root = root
        self.strict = strict
        self.findings = []          # list of (severity, check_id, message, path_or_None)
        self.passed = []            # check_ids OK
        self.allow = self._load_allowlist()

    def _load_allowlist(self):
        f = self.root / "scripts" / "audit-allowlist.txt"
        entries = set()
        if f.exists():
            for line in f.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    entries.add(line)
        return entries

    def allowed(self, path: Path, motif: str) -> bool:
        p = str(path.relative_to(self.root)) if path.is_relative_to(self.root) else str(path)
        return f"{p}:{motif}" in self.allow

    def add(self, sev, check, msg, path=None, motif=None):
        if path and motif and self.allowed(path, motif):
            return
        self.findings.append((sev, check, msg, str(path.relative_to(self.root)) if path and path.is_relative_to(self.root) else (str(path) if path else None)))

    def ok(self, check):
        self.passed.append(check)

    def verdict(self):
        crit = [f for f in self.findings if f[0] == CRIT]
        major = [f for f in self.findings if f[0] == MAJOR]
        if crit:
            return 2
        if major and self.strict:
            return 3
        return 0

    def summary(self):
        from collections import Counter
        c = Counter(f[0] for f in self.findings)
        v = self.verdict()
        label = {0: "✅ AUDIT PASSED", 2: "⛔ BLOCAGE CRITIQUE", 3: "⛔ BLOCAGE MAJOR (mode strict)", 1: "❌ ERREUR"}[v]
        return f"{label} — CRIT: {c[CRIT]} · MAJOR: {c[MAJOR]} · MINOR: {c[MINOR]}"


def read(p: Path):
    try:
        return p.read_text(encoding="utf-8")
    except Exception:
        return None


def iter_files(root: Path, *suffixes, skip_dirs=("build", ".git", ".gradle", "tools", "node_modules", ".credentials", "data")):
    for p in root.rglob("*"):
        if p.is_file() and p.suffix in suffixes:
            if any(part in skip_dirs for part in p.parts):
                continue
            yield p


# ---------------------------------------------------------------- checks

def check_autonomy(a: Audit):
    """CRIT — l'APK doit être 100 % autonome : Tor embarqué, zéro service tiers, réseau Tor uniquement."""
    man_path = a.root / "android/app/src/main/AndroidManifest.xml"
    man = read(man_path) or ""
    gradle = read(a.root / "android/app/build.gradle.kts") or ""
    toml = read(a.root / "android/gradle/libs.versions.toml") or ""
    all_build = gradle + toml

    # 1. Aucune dépendance à des services tiers
    for pattern, name in [(r"com\.google\.android\.gms|play-services|firebase", "Service Google (GMS/Firebase)"),
                          (r"com\.facebook|crashlytics|analitycs|analytics", "SDK tiers pistage/analytique")]:
        for f in [a.root / "android/app/build.gradle.kts", a.root / "android/gradle/libs.versions.toml"]:
            txt = read(f) or ""
            for m in re.finditer(pattern, txt, re.I):
                if not a.allowed(f, m.group(0)):
                    a.add(CRIT, "AUT-DEP", f"Dépendance interdite détectée ({name}) : {m.group(0)}", f)

    # 2. Tor embarqué obligatoire
    if "tor-binary" not in all_build:
        a.add(CRIT, "AUT-TOR", "Dépendance tor-binary absente — le Tor doit rester EMBARQUÉ (ADR-007)")

    # 3. Permissions : liste autorisée fermée
    allowed_perms = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                     "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                     "android.permission.POST_NOTIFICATIONS"}
    for m in re.finditer(r'<uses-permission\s+android:name="([^"]+)"', man):
        name = m.group(1)
        # autorisées : liste fermée + permissions auto-définies dans notre namespace
        # (générées par AGP, protection-level signature — jamais une dépendance externe)
        if name not in allowed_perms and not name.startswith("net.nyx.messenger."):
            a.add(CRIT, "AUT-PERM", f"Permission non autorisée hors liste fermée : {name}", man_path, name)

    # 4. Pas de trafic en clair
    if re.search(r'usesCleartextTraffic\s*=\s*"?true', man):
        a.add(CRIT, "AUT-CLEAR", "usesCleartextTraffic=true interdit (tout doit passer par Tor/HTTPS)", man_path)

    # 5. allowBackup=false obligatoire (clés identité)
    if 'allowBackup="true"' in man:
        a.add(CRIT, "AUT-BAK", "allowBackup=true interdit — les clés d'identité ne doivent jamais être sauvegardées", man_path)

    # 6. Release : minify + shrink + signature (installation 1 clic)
    rel = gradle.split("release {", 1)[-1] if "release {" in gradle else ""
    if rel:
        if "isMinifyEnabled = false" in rel:
            a.add(CRIT, "AUT-R8", "isMinifyEnabled=false sur release — R8 obligatoire (optimisation/autonomie)")
        if "isShrinkResources = false" in rel:
            a.add(CRIT, "AUT-R8", "isShrinkResources=false sur release — réduction des ressources obligatoire")
    else:
        a.add(CRIT, "AUT-R8", "Bloc buildTypes.release introuvable dans build.gradle.kts")
    if "signingConfig" not in gradle:
        a.add(CRIT, "AUT-SIGN", "Aucune signingConfig — l'APK release serait non signé, donc NON installable en 1 clic")

    # 7. Aucune URL en clair http:// vers un hôte LITTÉRAL hors localhost/127.0.0.1.
    # Les hôtes construits dynamiquement (variables $onion, concat Go) sont la convention
    # Nyx (tout host est .onion) — autorisés et relus en revue de code ; les hôtes
    # littéraux (domaine public en http) sont un CRIT.
    for f in iter_files(a.root, ".kt", ".go", skip_dirs=("build", ".git", ".gradle", "tools", "data", ".credentials")):
        txt = read(f)
        if not txt:
            continue
        for i, line in enumerate(txt.splitlines(), 1):
            if "http://" in line and not re.search(r'http://(127\.0\.0\.1|localhost|\$|"|\{)', line):
                a.add(CRIT, "AUT-URL", f"URL http:// en clair (hors localhost) ligne {i}: {line.strip()[:100]}", f, line.strip()[:60])
    if "AUT-DEP" not in [f[1] for f in a.findings]:
        a.ok("AUT-DEP")


def check_coherence(a: Audit):
    """CRIT — cohérence code ↔ manifest ↔ gradle ↔ ressources ↔ specs."""
    man = read(a.root / "android/app/src/main/AndroidManifest.xml") or ""
    gradle = read(a.root / "android/app/build.gradle.kts") or ""
    toml = read(a.root / "android/gradle/libs.versions.toml") or ""

    # 1. Classes du manifest existent
    src_root = a.root / "android/app/src/main/java"
    for m in re.finditer(r'android:name="(\.?[A-Za-z0-9_.]+)"', man):
        cls = m.group(1)
        if cls.startswith("."):
            cls = "net.nyx.messenger" + cls
        if cls.startswith(("android.", "net.nyx.messenger")) and "nyx" in cls:
            rel = cls.replace(".", "/") + ".kt"
            if not (a.root / "android/app/src/main/java" / rel).exists():
                a.add(CRIT, "COH-MAN", f"Classe référencée dans le manifest introuvable : {cls}")

    # 2. Accessors libs.xxx.xxx utilisés dans gradle ↔ alias du toml (mapping tiret↔point)
    used = set(re.findall(r'libs\.([a-z0-9.]+)', gradle))
    used = {u.rstrip(".") for u in used if not u.startswith("plugins.")}
    declared = set(re.findall(r'^([a-z0-9\-]+)\s*=\s*\{', toml, re.M))
    dotted = {d.replace("-", ".") for d in declared}
    for u in used:
        if u not in dotted:
            a.add(CRIT, "COH-GRADLE", f"Accessor libs.{u} introuvable dans le catalogue libs.versions.toml")

    # 3. Ressources @string référencées existent
    strings_xml = read(a.root / "android/app/src/main/res/values/strings.xml") or ""
    defined = set(re.findall(r'<string name="([^"]+)"', strings_xml))
    used_str = set()
    for f in iter_files(a.root, ".kt", ".xml", skip_dirs=("build", ".git", ".gradle", "tools", "data")):
        txt = read(f) or ""
        used_str |= set(re.findall(r'@string/([A-Za-z0-9_]+)', txt))
        used_str |= set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', txt))
    for s in used_str - defined:
        a.add(CRIT, "COH-RES", f"Ressource @string/{s} référencée mais non définie dans strings.xml")

    # 4. Package Kotlin = chemin du fichier (relatif à SA racine source :
    #    android/app/src/{main,test}/java — les autres .kt sont ignorés)
    source_roots = [
        a.root / "android/app/src/main/java",
        a.root / "android/app/src/test/java",
        a.root / "android/app/src/androidTest/java",
    ]
    for f in iter_files(a.root, ".kt", skip_dirs=("build", ".git", ".gradle", "tools", "data")):
        root_for_file = next((r for r in source_roots if f.is_relative_to(r)), None)
        if root_for_file is None:
            continue  # fichier hors racines sources (ex. archive, livrable)
        txt = read(f) or ""
        m = re.search(r'^package\s+([\w.]+)', txt, re.M)
        if m:
            expected = f.parent.relative_to(root_for_file)
            if str(expected).replace("/", ".") != m.group(1):
                a.add(CRIT, "COH-PKG", f"Package {m.group(1)} ≠ chemin {expected}", f)

    # 5. Thème référencé défini
    theme_ref = re.search(r'android:theme="@style/([^"]+)"', man)
    themes = read(a.root / "android/app/src/main/res/values/themes.xml") or ""
    if theme_ref and f'<style name="{theme_ref.group(1)}"' not in themes:
        a.add(CRIT, "COH-RES", f"Thème {theme_ref.group(1)} non défini dans themes.xml")

    a.ok("COH")


def check_dead_code(a: Audit):
    """MAJOR — code mort : fonctions/constantes déclarées jamais appelées (heuristiques + allowlist)."""
    # Kotlin
    kt_files = list(iter_files(a.root, ".kt", skip_dirs=("build", ".git", ".gradle", "tools", "data")))
    all_kt = "\n".join(read(f) or "" for f in kt_files)
    decl = re.compile(r'^\s*(?:private|internal|public)?\s*(?:suspend\s+)?fun\s+([a-zA-Z0-9_]+)\s*[<(]', re.M)
    per_file_decl = {}
    for f in kt_files:
        txt = read(f) or ""
        names = decl.findall(txt)
        if names:
            per_file_decl[f] = names
    for f, names in per_file_decl.items():
        for n in names:
            if n in ("main",):
                continue
            cnt = len(re.findall(rf'\b{re.escape(n)}\b', all_kt))
            if cnt <= 1:
                a.add(MAJOR, "DEAD-KT", f"Fonction Kotlin jamais référencée ailleurs : {n}()", f, n)

    # Go
    go_files = list(iter_files(a.root, ".go", skip_dirs=("build", ".git", "tools", "data")))
    all_go = "\n".join(read(f) or "" for f in go_files)
    for f in go_files:
        txt = read(f) or ""
        for n in re.findall(r'^func\s+([a-z][a-zA-Z0-9_]*)\s*\(', txt, re.M):
            if n == "main":
                continue
            cnt = len(re.findall(rf'\b{re.escape(n)}\b', all_go))
            if cnt <= 1:
                a.add(MAJOR, "DEAD-GO", f"Fonction Go non exportée jamais référencée : {n}()", f, n)

    # Ressources strings non utilisées
    strings_xml = read(a.root / "android/app/src/main/res/values/strings.xml") or ""
    used = set()
    for f in iter_files(a.root, ".kt", ".xml", skip_dirs=("build", ".git", ".gradle", "tools", "data")):
        used |= set(re.findall(r'@string/([A-Za-z0-9_]+)', read(f) or ""))
        used |= set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', read(f) or ""))
    defined = set(re.findall(r'<string name="([^"]+)"', strings_xml))
    for s in defined:
        if s not in used:
            a.add(MAJOR, "DEAD-RES", f"Ressource string inutilisée : {s}", a.root / "android/app/src/main/res/values/strings.xml", s)
    a.ok("DEAD")


def check_redundancy(a: Audit):
    """MAJOR — redondances : dépendances dupliquées, blocs de code dupliqués ≥6 lignes."""
    gradle = read(a.root / "android/app/build.gradle.kts") or ""
    deps = re.findall(r'(?:implementation|api|debugImplementation|ksp)\s*\(\s*libs\.([a-z0-9\-]+)\s*\)', gradle)
    seen, dupes = set(), set()
    for d in deps:
        if d in seen:
            dupes.add(d)
        seen.add(d)
    for d in dupes:
        a.add(MAJOR, "RED-DEP", f"Dépendance déclarée deux fois : libs.{d}")

    # blocs dupliqués entre fichiers Kotlin (fenêtre de 6 lignes non triviales)
    kt_files = [f for f in iter_files(a.root, ".kt", skip_dirs=("build", ".git", ".gradle", "tools", "data"))]
    windows = {}
    for f in kt_files:
        lines = [l.strip() for l in (read(f) or "").splitlines()]
        lines = [l for l in lines if l and not l.startswith(("import ", "package ", "//", "*", "/*"))]
        for i in range(len(lines) - 5):
            key = "\n".join(lines[i:i + 6])
            if len(key) > 120:  # ignore blocs triviaux/imports
                windows.setdefault(key, []).append(f.name)
    reported = set()
    for key, files in windows.items():
        if len(set(files)) > 1 and key not in reported:
            reported.add(key)
            a.add(MAJOR, "RED-DUP", f"Bloc de 6 lignes dupliqué entre fichiers : {sorted(set(files))[:3]}")
            if len(reported) >= 10:
                break
    a.ok("RED")


def check_secrets(a: Audit):
    """CRIT — aucun secret dans le dépôt."""
    patterns = [("BEGIN (RSA |EC )?PRIVATE KEY", "clé privée"),
                ("ghp_[A-Za-z0-9]{30,}", "PAT GitHub"),
                ("gho_[A-Za-z0-9]{30,}", "token GitHub OAuth"),
                ("AKIA[0-9A-Z]{16}", "clé AWS"),
                ("xox[baprs]-[A-Za-z0-9-]+", "token Slack"),
                ("AIza[0-9A-Za-z_-]{35}", "clé API Google"),
                ("eyJhbGciOi[A-Za-z0-9_.-]{40,}", "JWT service account")]
    for f in iter_files(a.root, ".kt", ".go", ".py", ".sh", ".json", ".md", ".kts", ".toml", ".properties", ".xml",
                        skip_dirs=("build", ".git", ".gradle", "tools", "data", ".credentials")):
        txt = read(f)
        if not txt:
            continue
        for pat, name in patterns:
            for m in re.finditer(pat, txt):
                a.add(CRIT, "SEC-KEY", f"Secret probable ({name}) détecté", f, m.group(0)[:24])
    gi = read(a.root / ".gitignore") or ""
    for must in ["local.properties", ".credentials", "*.jks", "keystore"]:
        if must not in gi:
            a.add(MAJOR, "SEC-GIT", f".gitignore ne couvre pas « {must} » (fuite possible de secret/de build)")
    a.ok("SEC")


def check_license(a: Audit):
    """CRIT — la licence est présente et cohérente (ADR-011 : AGPL-3.0-only)."""
    lic = read(a.root / "LICENSE")
    if not lic:
        a.add(CRIT, "COH-LIC", "Fichier LICENSE absent à la racine du dépôt (ADR-011)")
    elif "GNU AFFERO GENERAL PUBLIC LICENSE" not in lic:
        a.add(CRIT, "COH-LIC", "LICENSE n'est pas le texte AGPL-3.0 (ADR-011)")
    for rel in ("README.md", "server/README.md", "android/README.md"):
        txt = read(a.root / rel) or ""
        if "AGPL" not in txt:
            a.add(MAJOR, "COH-LIC", f"{rel} ne mentionne pas la licence AGPL-3.0 (ADR-011)")
    a.ok("COH-LIC")


def check_optim(a: Audit):
    """MINOR — inventaire qualité : TODO/FIXME du code de production uniquement, veilles R8."""
    todos = []
    code_suffixes = (".kt", ".go", ".kts", ".sh")  # pas les .md (roadmap/docs) ni l'outil d'audit lui-même
    for f in iter_files(a.root, *code_suffixes, skip_dirs=("build", ".git", ".gradle", "tools", "data")):
        if f.name == "audit.py":
            continue
        for i, line in enumerate((read(f) or "").splitlines(), 1):
            if re.search(r'\b(TODO|FIXME|XXX)\b', line):
                todos.append((f, i, line.strip()[:90]))
    for f, i, l in todos:
        a.add(MINOR, "OPT-TODO", f"TODO/FIXME {f.name}:{i} — {l}", f, l)

    rules = read(a.root / "android/app/proguard-rules.pro") or ""
    for lib, hint in [("lazysodium", "com.goterl"), ("sqlcipher", "sqlcipher"), ("jna", "jna")]:
        if lib.lower() not in rules.lower():
            a.add(MINOR, "OPT-R8", f"Aucune règle ProGuard explicite pour {lib} (risque d'élagage R8 du JNI)")
    a.ok("OPT")


# ---------------------------------------------------------------- rapport

def write_report(a: Audit, out_dir: Path):
    out_dir.mkdir(exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    md = out_dir / f"audit-{stamp}.md"
    from collections import Counter
    c = Counter(f[0] for f in a.findings)
    lines = [
        f"# Audit pré-compilation Nyx — {datetime.datetime.now().isoformat(timespec='seconds')}",
        "",
        f"**Verdict : {a.summary()}**",
        "",
        "| Catégorie | Nombre | Bloquant |",
        "|---|---|---|",
        f"| CRIT | {c[CRIT]} | toujours |",
        f"| MAJOR | {c[MAJOR]} | en --strict |",
        f"| MINOR | {c[MINOR]} | jamais |",
        "",
    ]
    by = {}
    for sev, chk, msg, path in sorted(a.findings, key=lambda x: (x[0], x[1])):
        by.setdefault((sev, chk), []).append((msg, path))
    for (sev, chk), items in by.items():
        lines.append(f"## {sev} · {chk}")
        lines.append("")
        for msg, path in items:
            lines.append(f"- {msg}" + (f" — `{path}`" if path else ""))
        lines.append("")
    md.write_text("\n".join(lines), encoding="utf-8")
    return md


def main():
    ap = argparse.ArgumentParser(description="Audit Nyx pré-compilation")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--strict", action="store_true", help="MAJOR devient bloquant")
    ap.add_argument("--report-dir", default=None)
    args = ap.parse_args()

    root = Path(args.root).resolve()
    a = Audit(root, args.strict)
    for check in (check_autonomy, check_coherence, check_secrets, check_license, check_dead_code, check_redundancy, check_optim):
        try:
            check(a)
        except Exception as e:  # un check qui plante ne doit pas tuer l'audit
            a.add(CRIT, "INTERNAL", f"Check {check.__name__} a échoué : {e}")

    print("\n=== AUDIT PRÉ-COMPILATION NYX ===")
    for sev, chk, msg, path in sorted(a.findings, key=lambda x: ({"CRIT": 0, "MAJOR": 1, "MINOR": 2}[x[0]], x[1])):
        icon = {"CRIT": "⛔", "MAJOR": "⚠️ ", "MINOR": "ℹ️ "}[sev]
        print(f"{icon} [{chk}] {msg}" + (f"  ({path})" if path else ""))
    if not a.findings:
        print("Aucune anomalie détectée.")
    print("\nChecks OK :", ", ".join(a.passed) if a.passed else "—")
    print(a.summary())

    report_dir = Path(args.report_dir) if args.report_dir else root / "reports"
    md = write_report(a, report_dir)
    print(f"Rapport : {md}")

    sys.exit(a.verdict())


if __name__ == "__main__":
    main()
