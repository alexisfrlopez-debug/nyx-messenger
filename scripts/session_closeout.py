#!/usr/bin/env python3
# =============================================================================
# session_closeout.py — GARDE-FOU DE CLÔTURE DE SESSION (règle permanente §9.11)
#
# Né du manquement de la session 68 (24/10/2026) : la console v0.8.12 avait été
# construite et annoncée dans le pointeur rapide, mais la synchro Drive n'avait
# pas pu se faire (client_secret OAuth absent) ; le workspace a été réinitialisé
# entre-temps et les 3 binaires console (APK + zip + exe) ont été PERDUS — la
# session 69 a synchronisé le reste sans remarquer le trou, et la fiche 8e
# édition est sortie en citant une console v0.8.11 périmée.
#
# RÈGLE : UNE SESSION NE SE CLÔTURE JAMAIS SANS `session_closeout.py` → PASS.
# Le script croise le DÉCLARATIF (toutes les versions annoncées « livrées »
# dans les pointeurs rapides du RESTART.md) avec le RÉEL (dépôts GitHub +
# arborescence Drive + fiche de présentation). Toute version jamais annoncée
# reste due pour toujours : elle ne « disparaît » pas des pointeurs suivants.
#
# Vérifications :
#   [git]     messager : arbre propre, HEAD poussé, tag v{APP} poussé
#   [gh-rel]  release GitHub v{APP} + assets attendus (2 APK + SUMS)
#   [git]     console  : arbre propre, HEAD poussé, versionName == attendu
#   [drive]   app      : 2 APK v{APP} + SHA256SUMS-v{APP}.txt + latest
#   [drive]   serveur  : nyx-server-{SRV}-linux-amd64 dans dernieres-livrables
#   [drive]   console  : APK + zip + installer.exe + SUMS v{CONSOLE}
#   [drive]   archives : versions précédentes archivées (WARN)
#   [drive]   fiche    : Nyx-fiche-de-presentation.pdf présent dans documents
#   [drive]   restart  : RESTART.md racine synchronisé (taille ≈ dépôt)
#   [fiche]   cohérence factuelle : le script de la fiche cite les 3 versions
#   [worklog] section de la session courante (WARN)
#
# Sortie : tableau PASS/FAIL/WARN/SKIP + verdict ; exit 1 si au moins un FAIL.
# Usage : python3 scripts/session_closeout.py [--json] [--repo DIR]
#   Env : NYX_CREDS (défaut /home/z/my-project/creds), NYX_ADMIN_DIR (défaut
#   /home/z/my-project/nyx-admin), NYX_DRIVE_CHAIN (défaut z.ai/Messagerie).
# =============================================================================
import argparse, fnmatch, json, os, re, subprocess, sys, urllib.parse, urllib.request

# ---------------------------------------------------------------- constantes
GITHUB_API = "https://api.github.com"
REPO_MESSENGER = "alexisfrlopez-debug/nyx-messenger"
REPO_ADMIN = "alexisfrlopez-debug/nyx-admin"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

RESULTS = []  # [(statut, domaine, message)]


def add(status, domain, msg):
    RESULTS.append((status, domain, msg))


def semver(s):
    return tuple(int(p) for p in s.split("."))


# ---------------------------------------------------------------- RESTART.md
def parse_attendus(restart_path):
    """Extrait, de CHAQUE pointeur rapide du RESTART.md, les versions annoncées
    livrées. Retient le MAX sémver par composant (= dette de livraison)."""
    text = open(restart_path, encoding="utf-8").read()
    # Blocs « Pointeur rapide (session N — date) » : chacun court jusqu'au
    # pointeur suivant (ils sont en ordre anti-chronologique dans le §8).
    marks = list(re.finditer(r"Pointeur rapide \(session (\d+)", text))
    if not marks:
        raise SystemExit("Aucun « Pointeur rapide » dans RESTART.md — introuvable ?")
    blocks = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else min(len(text), m.start() + 6000)
        blocks.append((int(m.group(1)), text[m.start():end]))
    zero = (0, 0, 0)
    best = {"app": ("", zero), "serveur": ("", zero), "console": ("", zero),
            "desktop": ("", zero)}
    sess = {}
    for num, raw in blocks:
        flat = re.sub(r">\s*|\n", " ", raw)  # recoud les lignes blockquote
        m = re.search(r"messagerie\s*\*\*v(\d+\.\d+\.\d+)", flat)
        if m and semver(m.group(1)) > best["app"][1]:
            best["app"] = (m.group(1), semver(m.group(1)))
        m = re.search(r"serveur\s+\**\s*v?(\d+\.\d+\.\d+)", flat)
        if m and semver(m.group(1)) > best["serveur"][1]:
            best["serveur"] = (m.group(1), semver(m.group(1)))
        m = re.search(r"console\s*\*\*v(\d+\.\d+\.\d+)", flat)
        if m and semver(m.group(1)) > best["console"][1]:
            best["console"] = (m.group(1), semver(m.group(1)))
        # session 71 — famille desktop (ADR-019) : « CLIENT DESKTOP WINDOWS
        # vX.Y.Z » dans les pointeurs (jamais « messagerie **v… » pour ne pas
        # polluer la détection de l'app Android).
        m = re.search(r"CLIENT DESKTOP WINDOWS\s*\**\s*v(\d+\.\d+\.\d+)", flat)
        if m and semver(m.group(1)) > best["desktop"][1]:
            best["desktop"] = (m.group(1), semver(m.group(1)))
        sess[num] = raw
    missing = [k for k, v in best.items() if not v[0] and k != "desktop"]
    if missing:
        raise SystemExit(f"RESTART.md : version(s) annoncée(s) introuvable(s) : {missing}")
    last_session = max(sess)
    return {k: v[0] for k, v in best.items()}, last_session


# ---------------------------------------------------------------- git helpers
def git(repo, *args, check=True):
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or r.stdout.strip())
    return r.stdout.strip()


def check_git(repo, name, expected_tag=None, expected_version=None):
    if not os.path.isdir(os.path.join(repo, ".git")):
        add("WARN", f"git/{name}", f"dépôt {repo} absent du poste — vérification GitHub seule")
        return None
    dirty = git(repo, "status", "--porcelain", check=False)
    if dirty:
        add("FAIL", f"git/{name}", f"arbre sale ({len(dirty.splitlines())} fichiers) — committer")
    else:
        add("PASS", f"git/{name}", "arbre propre")
    git(repo, "fetch", "origin", "--quiet", check=False)
    head, remote = git(repo, "rev-parse", "HEAD"), git(repo, "rev-parse", "origin/main", check=False)
    if not remote:
        add("FAIL", f"git/{name}", "origin/main introuvable (fetch raté ?)")
    elif head != remote:
        add("FAIL", f"git/{name}", f"HEAD {head[:7]} != origin/main {remote[:7]} — POUSSER")
    else:
        add("PASS", f"git/{name}", f"HEAD {head[:7]} poussé sur origin/main")
    if expected_tag:
        tags = git(repo, "ls-remote", "--tags", "origin", f"refs/tags/{expected_tag}", check=False)
        if tags:
            add("PASS", f"git/{name}", f"tag {expected_tag} présent sur le distant")
        else:
            add("FAIL", f"git/{name}", f"tag {expected_tag} ABSENT du distant — pousser --tags")
    if expected_version:  # console : versionName du build.gradle.kts
        bg = os.path.join(repo, "android", "build.gradle.kts")
        if os.path.isfile(bg):
            m = re.search(r'versionName\s*=\s*"([^"]+)"', open(bg, encoding="utf-8").read())
            if m:
                if m.group(1) == expected_version:
                    add("PASS", f"git/{name}", f"versionName {expected_version} conforme au déclaratif")
                else:
                    add("FAIL", f"git/{name}",
                        f"versionName {m.group(1)} != version annoncée {expected_version}")
    return head


# ---------------------------------------------------------------- GitHub API
def gh(path, pat=None):
    headers = {"Accept": "application/vnd.github+json"}
    if pat:
        headers["Authorization"] = f"token {pat}"
    req = urllib.request.Request(GITHUB_API + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r), None
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:
        return None, str(e)


def check_release(app, pat):
    rel, err = gh(f"/repos/{REPO_MESSENGER}/releases/tags/v{app}", pat)
    if err:
        add("FAIL", "gh-rel/app", f"release v{app} injoignable ({err})")
        return
    assets = [a["name"] for a in rel.get("assets", [])]
    wanted = [f"nyx-v{app}-*-release.apk", f"nyx-v{app}-*-test.apk", f"SHA256SUMS-v{app}.txt"]
    miss = [w for w in wanted if not any(fnmatch.fnmatch(a, w) for a in assets)]
    if miss:
        add("FAIL", "gh-rel/app", f"release v{app} : assets manquants {miss} (présents : {assets})")
    else:
        add("PASS", "gh-rel/app", f"release v{app} complète ({len(assets)} assets)")
    srv_asset = f"nyx-server-*-linux-amd64"
    if not any(fnmatch.fnmatch(a, srv_asset) for a in assets):
        add("WARN", "gh-rel/app", "aucun binaire serveur en asset de la release "
             "(convention v0.2.44+ : il DOIT être au moins dans le Drive)")


def check_release_desktop(desktop, pat):
    """Release GitHub de la famille desktop (session 71) : zip portable +
    installateur.exe + SHA256SUMS en assets."""
    rel, err = gh(f"/repos/{REPO_MESSENGER}/releases/tags/v{desktop}-desktop", pat)
    if err:
        add("FAIL", "gh-rel/desktop", f"release v{desktop}-desktop injoignable ({err})")
        return
    assets = [a["name"] for a in rel.get("assets", [])]
    wanted = [f"nyx-messenger-*-v{desktop}.zip",
              f"nyx-messenger-*-v{desktop}-installer.exe",
              f"SHA256SUMS-desktop-v{desktop}.txt"]
    miss = [w for w in wanted if not any(fnmatch.fnmatch(a, w) for a in assets)]
    if miss:
        add("FAIL", "gh-rel/desktop",
            f"release v{desktop}-desktop : assets manquants {miss} (présents : {assets})")
    else:
        add("PASS", "gh-rel/desktop",
            f"release v{desktop}-desktop complète ({len(assets)} assets)")


# ---------------------------------------------------------------- Drive API
class Drive:
    """API Drive v3 minimaliste (réutilise le refresh_token des creds)."""

    def __init__(self, creds_dir):
        self.token_file = os.path.join(creds_dir, "access_token.json")
        self.relay = os.path.join(creds_dir, "oauth_token_relais.json")
        self.token = None

    def _refresh(self):
        d = json.load(open(self.relay))
        data = urllib.parse.urlencode({
            "client_id": d["client_id"], "client_secret": d["client_secret"],
            "refresh_token": d["refresh_token"], "grant_type": "refresh_token"}).encode()
        req = urllib.request.Request("https://oauth2.googleapis.com/token", data=data, method="POST")
        with urllib.request.urlopen(req, timeout=30) as r:
            tok = json.load(r)
        tok["_at"] = __import__("time").time()
        json.dump(tok, open(self.token_file, "w"))
        return tok

    def call(self, path, params=None):
        import time as _t
        if self.token and _t.time() - self.token["_at"] < self.token["expires_in"] - 120:
            pass
        else:
            self.token = self._refresh()
        url = "https://www.googleapis.com/drive/v3" + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + self.token["access_token"]})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)

    def children(self, fid):
        files, pt = [], None
        while True:
            q = f"'{fid}' in parents and trashed=false"
            params = {"q": q, "fields": "nextPageToken,files(id,name,size,modifiedTime)",
                      "pageSize": 200}
            if pt:
                params["pageToken"] = pt
            d = self.call("/files", params)
            files += d.get("files", [])
            pt = d.get("nextPageToken")
            if not pt:
                return files

    def resolve(self, fid, name):
        for f in self.children(fid):
            if f["name"] == name:
                return f
        return None


def drive_tree(drive, chain):
    """Résolution par NOMS (aucun ID dans ce dépôt public — §9.7) :
    racine → z.ai → Messagerie."""
    fid = "root"
    for name in chain:
        node = drive.resolve(fid, name)
        if not node:
            raise SystemExit(f"Drive : dossier « {name} » introuvable sous « {fid} »")
        fid = node["id"]
    return node


def check_drive(drive, project, attendus, repo):
    livrables = drive.resolve(project["id"], "livrables")
    if not livrables:
        add("FAIL", "drive", "dossier livrables/ introuvable dans le projet Drive")
        return
    dl = drive.resolve(livrables["id"], "dernieres-livrables")
    docs = drive.resolve(livrables["id"], "documents")
    arch = drive.resolve(livrables["id"], "archives")
    if not (dl and docs and arch):
        add("FAIL", "drive", f"structure livrables/ incomplète (dl={bool(dl)} docs={bool(docs)} arch={bool(arch)})")
        return
    files = drive.children(dl["id"])
    names = [f["name"] for f in files]

    def want(pattern, label, fail=True):
        hits = [n for n in names if fnmatch.fnmatch(n, pattern)]
        if hits:
            add("PASS", "drive/" + label, hits[0])
        elif fail:
            add("FAIL", "drive/" + label, f"{pattern} ABSENT de dernieres-livrables/")
        else:
            add("WARN", "drive/" + label, f"{pattern} absent de dernieres-livrables/")

    app, srv, cons = attendus["app"], attendus["serveur"], attendus["console"]
    want(f"nyx-v{app}-*-release.apk", "app")
    want(f"nyx-v{app}-*-test.apk", "app")
    want(f"SHA256SUMS-v{app}.txt", "app")
    want("SHA256SUMS-latest.txt", "app")
    want(f"nyx-server-{srv}-linux-amd64", "serveur")
    want(f"nyx-admin-android-v{cons}-*.apk", "console")
    want(f"nyx-admin-windows-v{cons}-*.zip", "console")
    want(f"nyx-admin-windows-v{cons}-*-installer.exe", "console")
    want(f"SHA256SUMS-nyx-admin-v{cons}.txt", "console")
    desk = attendus.get("desktop")
    if desk:
        want(f"nyx-messenger-*-v{desk}.zip", "desktop")
        want(f"nyx-messenger-*-v{desk}-installer.exe", "desktop")
        want(f"SHA256SUMS-desktop-v{desk}.txt", "desktop")

    # versions périmées encore dans dernieres-livrables → à archiver (WARN)
    stale = [(r"nyx-admin-android-v(\d+\.\d+\.\d+)-", "console", cons),
             (r"nyx-server-(\d+\.\d+\.\d+)-linux", "serveur", srv),
             (r"nyx-v(\d+\.\d+\.\d+)-\d{8}-", "app", app)]
    if desk:
        # session 71 — le zip portable n'a PAS de suffixe après la version
        # (« nyx-messenger-windows-v0.3.0.zip ») d'où [.-] ; le SHA256SUMS
        # desktop est traité à part (même leçon que publish-drive.sh).
        stale += [(r"nyx-messenger-(?:windows|linux|macos)-v(\d+\.\d+\.\d+)[.-]", "desktop", desk),
                  (r"SHA256SUMS-desktop-v(\d+\.\d+\.\d+)\.txt", "desktop", desk)]
    for pat, comp, cur in stale:
        for n in names:
            m = re.match(pat, n)
            if m and m.group(1) != cur:
                add("WARN", "drive/archives", f"{n} (v{m.group(1)} périmée dans dernieres-livrables — archiver)")

    # fiche dans documents/
    fiches = [f for f in drive.children(docs["id"]) if "fiche" in f["name"].lower()]
    if not fiches:
        add("FAIL", "drive/fiche", "aucune fiche de présentation dans livrables/documents/")
    else:
        add("PASS", "drive/fiche", f"{fiches[0]['name']} ({fiches[0]['modifiedTime'][:10]})")

    # RESTART.md racine synchronisé (proxy : taille à ±5 % du dépôt)
    rst = drive.resolve(project["id"], "RESTART.md")
    local = os.path.join(repo, "RESTART.md")
    if rst and os.path.isfile(local):
        ls, rs = os.path.getsize(local), int(rst.get("size", 0))
        if rs >= ls * 0.95:
            add("PASS", "drive/restart", f"RESTART.md racine à jour ({rs//1024} Ko, {rst['modifiedTime'][:10]})")
        else:
            add("FAIL", "drive/restart", f"RESTART.md racine périmé ({rs//1024} Ko < local {ls//1024} Ko) — resynchroniser")
    elif not rst:
        add("FAIL", "drive/restart", "RESTART.md absent de la racine du projet Drive")


def check_fiche(repo, attendus):
    fp = os.path.join(repo, "scripts", "fiche_presentation.py")
    if not os.path.isfile(fp):
        add("SKIP", "fiche", "scripts/fiche_presentation.py absent du dépôt")
        return
    text = open(fp, encoding="utf-8").read()
    for key, label in [("app", "app"), ("console", "console"), ("serveur", "serveur")]:
        v = attendus[key]
        if f"v{v}" in text or (key == "serveur" and re.search(rf"\b{v}\b", text)):
            add("PASS", "fiche/" + label, f"version {v} citée dans la fiche")
        else:
            add("FAIL", "fiche/" + label,
                f"la fiche NE CITE PAS la version {label} {v} annoncée livrée — régénérer la fiche")
    desk = attendus.get("desktop")
    if desk:
        if f"v{desk}" in text:
            add("PASS", "fiche/desktop", f"version desktop {desk} citée dans la fiche")
        else:
            add("FAIL", "fiche/desktop",
                f"la fiche NE CITE PAS la version desktop {desk} annoncée livrée — régénérer la fiche")


def check_worklog(last_session):
    wl = "/home/z/my-project/worklog.md"
    if os.path.isfile(wl) and f"session {last_session}" in open(wl, encoding="utf-8").read().lower():
        add("PASS", "worklog", f"section session {last_session} présente")
    else:
        add("WARN", "worklog", f"aucune section « session {last_session} » dans worklog.md")


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description="Garde-fou de clôture de session Nyx")
    ap.add_argument("--repo", default=os.path.dirname(SCRIPT_DIR))
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    creds = os.environ.get("NYX_CREDS", "/home/z/my-project/creds")
    admin_dir = os.environ.get("NYX_ADMIN_DIR", "/home/z/my-project/nyx-admin")
    chain = os.environ.get("NYX_DRIVE_CHAIN", "z.ai/Messagerie").split("/")

    attendus, last_session = parse_attendus(os.path.join(args.repo, "RESTART.md"))
    print(f"Attendus (max des pointeurs RESTART.md, session la + récente : {last_session}) :")
    desk = attendus.get("desktop")
    print(f"  app v{attendus['app']} · serveur v{attendus['serveur']} · console v{attendus['console']}"
          + (f" · desktop v{desk}" if desk else "") + "\n")

    pat = None
    pat_file = os.path.join(creds, "token_git.txt")
    if os.path.isfile(pat_file):
        pat = open(pat_file).read().strip()

    check_git(args.repo, "messager", expected_tag=f"v{attendus['app']}")
    check_git(admin_dir, "console", expected_version=attendus["console"])
    check_release(attendus["app"], pat)
    if desk:
        check_release_desktop(desk, pat)
        tags = git(args.repo, "ls-remote", "--tags", "origin",
                   f"refs/tags/v{desk}-desktop", check=False)
        if tags:
            add("PASS", "git/messager", f"tag v{desk}-desktop présent sur le distant")
        else:
            add("FAIL", "git/messager", f"tag v{desk}-desktop ABSENT du distant — pousser")
    else:
        add("SKIP", "gh-rel/desktop", "aucun client desktop annoncé dans RESTART.md")
    if pat:
        rel, err = gh(f"/repos/{REPO_ADMIN}/branches/main", pat)
        if err:
            add("WARN", "gh/console", f"branche main nyx-admin injoignable ({err})")
        else:
            add("PASS", "gh/console", f"nyx-admin main = {rel['commit']['sha'][:7]}")
    else:
        add("WARN", "gh/console", "PAT absent — dépôt privé nyx-admin non vérifié")

    relay = os.path.join(creds, "oauth_token_relais.json")
    if os.path.isfile(relay):
        try:
            drive = Drive(creds)
            project = drive_tree(drive, chain)
            check_drive(drive, project, attendus, args.repo)
        except SystemExit as e:
            add("FAIL", "drive", str(e))
        except Exception as e:
            add("FAIL", "drive", f"erreur API Drive : {e}")
    else:
        add("FAIL", "drive", f"{relay} absent — synchro Drive impossible À CAUSE DE ÇA (leçon session 68)")

    check_fiche(args.repo, attendus)
    check_worklog(last_session)

    if args.json:
        print(json.dumps({"attendus": attendus,
                          "resultats": [{"statut": s, "domaine": d, "message": m} for s, d, m in RESULTS]},
                         ensure_ascii=False, indent=2))
    order = {"FAIL": 0, "WARN": 1, "SKIP": 2, "PASS": 3}
    icon = {"PASS": "✔", "FAIL": "✘", "WARN": "⚠", "SKIP": "·"}
    for s, d, m in sorted(RESULTS, key=lambda r: (order[r[0]], r[1])):
        print(f"  {icon[s]} [{s:4}] {d:16} {m}")
    nf = sum(1 for s, _, _ in RESULTS if s == "FAIL")
    nw = sum(1 for s, _, _ in RESULTS if s == "WARN")
    print()
    if nf:
        print(f"══ CLÔTURE REFUSÉE : {nf} FAIL, {nw} WARN — corriger AVANT de clôturer la session ══")
        sys.exit(1)
    print(f"══ CLÔTURE VALIDÉE : 0 FAIL, {nw} WARN — session synchronisée de bout en bout ══")
    sys.exit(0)


if __name__ == "__main__":
    main()
