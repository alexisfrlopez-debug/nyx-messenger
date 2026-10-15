#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyx — Miroir d'environnement → Drive (règle permanente RESTART.md §9.7).

APRÈS CHAQUE TÂCHE (en plus de la synchro git — sync.sh) : sauvegarde miroir de
l'environnement dans le dossier de travail Drive, sous
`sauvegardes/session-<AAAA-MM-JJ>/` :

  worklog.md             journal multi-agents (/home/z/my-project/worklog.md)
  RESTART.md             instructions permanentes (HEAD du dépôt)
  INFRA_LOCAL.md         IDs Drive / état credentials (local, hors git)
  env_snapshot.txt       état de l'environnement généré à la volée
  nyx-projet-v<X>.tar.gz archive git du dépôt public (git archive HEAD)
  scripts_local/         scripts d'atelier hors dépôt (/home/z/my-project/scripts)
  download/              livrables & artefacts locaux (< 20 Mo par fichier)

Propriétés : stdlib uniquement (§9), idempotent (relancé le même jour → mise à
jour par nom), JAMAIS de suppression côté Drive (leçon incident restauration),
SKIP explicite si un élément est absent. Usage : python3 scripts/mirror_env.py
"""
import datetime
import subprocess
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gdrive import Drive  # noqa: E402

ENV_ROOT = Path("/home/z/my-project")
REPO = ENV_ROOT / "tmp" / "nyx-messenger"
LOCAL_SCRIPTS = ENV_ROOT / "scripts"
DOWNLOAD = ENV_ROOT / "download"
INFRA_LOCAL = ENV_ROOT / ".credentials" / "INFRA_LOCAL.md"
WORKLOG = ENV_ROOT / "worklog.md"
MAX_FILE = 20 * 1024 * 1024  # 20 Mo par fichier


def snapshot_text(d: Drive) -> str:
    def run(cmd, cwd=REPO):
        try:
            return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                                  timeout=30).stdout.strip()
        except Exception as e:  # noqa: BLE001
            return f"(indisponible: {e})"

    head = run(["git", "log", "--oneline", "-1"])
    branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    ahead = run(["git", "status", "-sb"])
    tasks = ""
    if WORKLOG.exists():
        ids = [l for l in WORKLOG.read_text(errors="replace").splitlines()
               if l.startswith("Task ID:")]
        tasks = f"{len(ids)} tâches — dernière : {ids[-1].strip() if ids else '—'}"
    lines = [
        "=== NYX — instantané d'environnement ===",
        f"date : {datetime.datetime.now().isoformat(timespec='seconds')}",
        f"dépôt public ({branch}) : {head}",
        f"état git : {ahead.splitlines()[0] if ahead else '?'}",
        f"worklog : {tasks}",
        f"credentials : {'OK' if d.ok else 'ABSENTS'} — client_secret, "
        f"oauth_token_relais, compte de service, PAT (noms dans .credentials/tokens/)",
        "",
        "inventaire /home/z/my-project :",
    ]
    for p in sorted(ENV_ROOT.iterdir()):
        lines.append(f"  {p.name}/" if p.is_dir() else f"  {p.name}")
    return "\n".join(lines) + "\n"


def project_archive(dest: Path) -> Path | None:
    version = ""
    gradle = REPO / "android" / "app" / "build.gradle.kts"
    if gradle.exists():
        for line in gradle.read_text().splitlines():
            if 'versionName' in line and '=' in line:
                version = line.split('"')[1] if '"' in line else ""
                break
    name = f"nyx-projet-v{version or 'x'}.tar.gz"
    out = dest / name
    r = subprocess.run(["git", "archive", "--format=tar.gz", "-o", str(out), "HEAD"],
                       cwd=REPO, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ⚠ archive projet SKIP — {r.stderr.strip()[:120]}")
        return None
    return out


def upload_tree(d: Drive, local_dir: Path, remote_dir: str, session: str, label: str):
    if not local_dir.exists():
        print(f"  ⏳ SKIP {label} — {local_dir} absent")
        return
    n = 0
    # Niveau supérieur UNIQUEMENT : pas de descente récursive (les clones de
    # dépôts nichés — p.ex. scripts/nyx-messenger — sont couverts par git +
    # l'archive nyx-projet-v*.tar.gz, pas par le miroir).
    for f in sorted(local_dir.iterdir()):
        if not f.is_file() or f.stat().st_size > MAX_FILE:
            if f.is_dir():
                print(f"  · {label}/{f.name}/ ignoré (dossier — couvert par git)")
            continue
        d.upload(f, f"{session}/{label}", immutable=True)  # inchangé (nom+taille) → skip
        n += 1
    print(f"  ✓ {label} — {n} fichier(s)")


def main():
    d = Drive()
    if not d.ok:
        raise SystemExit("Credentials Drive absents — miroir impossible (§6)")
    session = "sauvegardes/session-" + datetime.date.today().isoformat()
    print(f"═══ MIROIR ENVIRONNEMENT → Drive : {session}/ ═══")

    # NB : upload(local, remote_dir) — remote_dir est un DOSSIER ; le nom distant
    # est local.name (lire la docstring gdrive.py — leçon 1ʳᵉ exécution).
    jobs = [
        (WORKLOG, "worklog.md"),
        (REPO / "RESTART.md", "RESTART.md"),
        (INFRA_LOCAL, "INFRA_LOCAL.md"),
    ]
    for local, label in jobs:
        if local.exists():
            d.upload(local, session)
            print(f"  ✓ {label}")
        else:
            print(f"  ⏳ SKIP {label} — absent")

    snap = ENV_ROOT / "tmp" / "env_snapshot.txt"
    snap.parent.mkdir(parents=True, exist_ok=True)
    snap.write_text(snapshot_text(d))
    d.upload(snap, session)
    print("  ✓ env_snapshot.txt")

    arch = project_archive(snap.parent)
    if arch:
        d.upload(arch, session)
        print(f"  ✓ {arch.name} ({arch.stat().st_size // 1024} Ko)")

    upload_tree(d, LOCAL_SCRIPTS, "", session, "scripts_local")
    upload_tree(d, DOWNLOAD, "", session, "download")
    print("═══ miroir terminé ═══")


if __name__ == "__main__":
    main()
