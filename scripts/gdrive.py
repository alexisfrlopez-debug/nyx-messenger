#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyx — Module de synchro Google Drive (stdlib uniquement, règle permanente RESTART.md §9).

Credentials attendus dans /home/z/my-project/.credentials/ OU .credentials/tokens/
(HORS dépôt, git-ignoré — restaurés du dossier Drive dédié « Tokens », RESTART.md §6) :
  - client_secret_*.json        (client_id + client_secret, format console Google)
  - oauth_token_relais_*.json   (refresh_token, accès Drive)

Usage :
  gdrive.py find <chemin/a/b>            → ID du dossier (créé au besoin) ; chemin racine = dossier projet « Messagerie »
  gdrive.py upload <fichier> <chemin/> [--immutable]
                                         → téléverse (remplace par nom) ; avec --immutable,
                                           saute si même nom + même taille (livrables figés)
  gdrive.py list <chemin/>               → contenu (direct)
  gdrive.py tree <chemin/>               → contenu récursif (dossiers puis fichiers)
  gdrive.py move <chemin/fichier> <dossier-dest/> → déplace (addParents/removeParents, dossier dest créé au besoin)
  gdrive.py cat <chemin/fichier>         → contenu du fichier (vérification d'intégrité à distance)
  gdrive.py --status                     → credentials présents ? token valide ?
"""
import json
import mimetypes
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

CREDS_DIR = Path("/home/z/my-project/.credentials")
API = "https://www.googleapis.com/drive/v3"
UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"  # endpoint d'upload (segment /upload OBLIGATOIRE)
TOKEN_URL = "https://oauth2.googleapis.com/token"
# Racine ANCRÉE par NOM (règle session 10 : AUCUN ID d'infrastructure dans ce
# dépôt PUBLIC — les IDs vivent au dépôt privé `nyx-admin`, ops/INFRA.md).
# Résolution : dossier « Messagerie » cherché à la racine Mon Drive (nom unique).
# Override optionnel : variable d'environnement NYX_ROOT_FOLDER_ID (p.ex. extraite
# du lien Drive fourni par l'utilisateur) — JAMAIS codée ici.
ROOT_FOLDER_NAME = "Messagerie"
FOLDER_MIME = "application/vnd.google-apps.folder"


class Drive:
    def __init__(self):
        self.token = None
        self.token_exp = 0
        self._resolve_cache: dict[str, str] = {}  # chemin → ID dossier (par processus)
        self.client_id, self.client_secret, self.refresh_token = self._load_creds()

    @staticmethod
    def _load_creds():
        # Cherche dans .credentials/ puis .credentials/tokens/ (procédure §6)
        search_dirs = [CREDS_DIR, CREDS_DIR / "tokens"]
        cs, rt = None, None
        for base in search_dirs:
            if cs is None:
                for f in sorted(base.glob("client_secret*.json")) if base.exists() else []:
                    data = json.loads(f.read_text())
                    key = next(iter(data))  # "installed" ou "web"
                    cs = data[key]
                    break
        for base in search_dirs:
            if rt is None:
                for f in sorted(base.glob("oauth_token*.json")) if base.exists() else []:
                    data = json.loads(f.read_text())
                    rt = data.get("refresh_token") or data.get("refreshToken")
                    break
        if not (cs and rt):
            return None, None, None
        return cs["client_id"], cs["client_secret"], rt

    @property
    def ok(self) -> bool:
        return bool(self.client_id and self.client_secret and self.refresh_token)

    def _access_token(self) -> str:
        if self.token and time.time() < self.token_exp - 60:
            return self.token
        if not self.ok:
            raise SystemExit("Credentials Drive absents dans " + str(CREDS_DIR))
        body = urllib.parse.urlencode({
            "client_id": self.client_id, "client_secret": self.client_secret,
            "refresh_token": self.refresh_token, "grant_type": "refresh_token",
        }).encode()
        req = urllib.request.Request(TOKEN_URL, data=body, method="POST")
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read())
        self.token = data["access_token"]
        self.token_exp = time.time() + int(data.get("expires_in", 3600))
        return self.token

    def _req(self, method: str, url: str, body=None, headers=None, raw=False):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self._access_token()}")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        with urllib.request.urlopen(req, timeout=600) as r:
            data = r.read()
            return data if raw else (json.loads(data) if data else {})

    # ---------------------------------------------------------------- API haut niveau

    def _find_child(self, name: str, parent: str) -> str:
        """ID du dossier `name` sous `parent` — unicité exigée (sinon erreur explicite)."""
        q = (f"name = '{name}' and mimeType = '{FOLDER_MIME}' "
             f"and '{parent}' in parents and trashed = false")
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name)&pageSize=10")
        files = res.get("files", [])
        if len(files) == 1:
            return files[0]["id"]
        where = "à la racine" if parent == "root" else f"sous {parent}"
        if not files:
            raise SystemExit(f"Dossier « {name} » introuvable {where} — vérifier le Drive / fournir NYX_ROOT_FOLDER_ID")
        raise SystemExit(f"{len(files)} dossiers « {name} » homonymes {where} — lever l'ambiguïté via NYX_ROOT_FOLDER_ID")

    def root_folder_id(self) -> str:
        """ID du dossier projet — env var si fournie, sinon résolution par CHEMIN DE NOMS
        (« z.ai/Messagerie » — aucun ID codé en dur, règle session 10)."""
        import os
        env = os.environ.get("NYX_ROOT_FOLDER_ID", "").strip()
        if env:
            return env
        parent = "root"
        for name in ["z.ai", ROOT_FOLDER_NAME]:
            parent = self._find_child(name, parent)
        return parent

    def resolve(self, path: str, create: bool = True) -> str:
        """Résout un chemin « a/b/c » depuis le dossier projet « Messagerie » (création au besoin).
        Cache par processus : évite les requêtes redondantes (arbres de fichiers du miroir)."""
        norm = "/".join(p for p in path.strip("/").split("/") if p)
        if norm in self._resolve_cache:
            return self._resolve_cache[norm]
        parent = self.root_folder_id()  # ancre : jamais « root »
        prefix = ""
        for part in [p for p in norm.split("/") if p]:
            prefix = f"{prefix}/{part}" if prefix else part
            if prefix in self._resolve_cache:
                parent = self._resolve_cache[prefix]
                continue
            q = (f"name = '{part}' and mimeType = '{FOLDER_MIME}' "
                 f"and '{parent}' in parents and trashed = false")
            res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name)&pageSize=5")
            files = res.get("files", [])
            if files:
                parent = files[0]["id"]
            elif create:
                body = json.dumps({"name": part, "mimeType": FOLDER_MIME,
                                   "parents": [parent]}).encode()
                parent = self._req("POST", f"{API}/files", body,
                                   {"Content-Type": "application/json"})["id"]
            else:
                raise SystemExit(f"Chemin introuvable : {part} dans {path}")
            self._resolve_cache[prefix] = parent
        return parent

    def upload(self, local: Path, remote_dir: str, immutable: bool = False) -> str:
        """Téléverse local dans remote_dir (crée ou remplace un fichier du même nom).
        immutable=True : si un fichier du même nom avec la MÊME TAILLE existe déjà,
        il est considéré identique (les livrables publiés ne changent jamais) — skip."""
        folder = self.resolve(remote_dir)
        name = local.name
        # Session 58 — GARDE-FOU : un DOSSIER homonyme ne doit JAMAIS être retenu
        # (incident sessions 57/58 : dossiers parasites « RESTART.md » / « . » à la
        # racine interceptaient l'upload par nom → PATCH média sur un dossier).
        q = (f"name = '{name}' and mimeType != '{FOLDER_MIME}' "
             f"and '{folder}' in parents and trashed = false")
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,size)&pageSize=5")
        existing = res.get("files", [])
        if existing:
            if immutable and str(local.stat().st_size) == str(existing[0].get("size", "")):
                return existing[0]["id"]  # déjà présent à l'identique — pas de re-téléversement
            # Mise à jour : PATCH média seul — le multipart n'est pas supporté sur PATCH (v3).
            # Le nom/parent étant inchangés (même nom, même dossier), le contenu suffit.
            ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
            url = f"{UPLOAD_API}/files/{existing[0]['id']}?uploadType=media"
            return self._req("PATCH", url, local.read_bytes(), {"Content-Type": ctype})["id"]
        meta = json.dumps({"name": name, "parents": [folder]}).encode()
        url = f"{UPLOAD_API}/files?uploadType=multipart"
        boundary = "nyx37boundary"
        mp = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
              f"{meta.decode()}\r\n--{boundary}\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
        mp += local.read_bytes() + f"\r\n--{boundary}--".encode()
        fid = self._req("POST", url, mp, {"Content-Type": f"multipart/related; boundary={boundary}"})["id"]
        return fid

    def move(self, src_path: str, dest_dir_path: str) -> str:
        """Déplace un fichier vers un dossier destination.
        src_path = chemin COMPLET incluant le nom du fichier (dernier segment).
        Le dossier destination est créé au besoin (resolve)."""
        parts = [p for p in src_path.strip("/").split("/") if p]
        if len(parts) < 1:
            raise SystemExit("Chemin source vide")
        name = parts[-1]
        src_parent = self.resolve("/".join(parts[:-1]), create=False)
        dest_dir = self.resolve(dest_dir_path)  # créé au besoin
        if src_parent == dest_dir:
            raise SystemExit(f"Source et destination identiques — rien à déplacer : {src_path}")
        q = f"name = '{name}' and '{src_parent}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name)&pageSize=10")
        found = res.get("files", [])
        if len(found) != 1:
            raise SystemExit(f"{len(found)} fichier(s) « {name} » dans le dossier source — déplacement annulé (unicité exigée)")
        url = (f"{API}/files/{found[0]['id']}?addParents={dest_dir}&removeParents={src_parent}"
               f"&fields=id,name,parents")
        return self._req("PATCH", url, b"", {"Content-Type": "application/json"})["id"]

    def delete(self, path: str) -> str:
        """Met à la corbeille un fichier OU un dossier vide (nom unique exigé).
        Chemin complet ; le dernier segment est la cible."""
        parts = [p for p in path.strip("/").split("/") if p]
        if not parts:
            raise SystemExit("Chemin vide")
        name = parts[-1]
        parent = self.resolve("/".join(parts[:-1]), create=False)
        q = f"name = '{name}' and '{parent}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name,mimeType)&pageSize=10")
        found = res.get("files", [])
        if len(found) != 1:
            raise SystemExit(f"{len(found)} élément(s) « {name} » — suppression annulée (unicité exigée)")
        target = found[0]
        if target["mimeType"] == FOLDER_MIME:
            inside_q = f"'{target['id']}' in parents and trashed = false"
            inside = self._req("GET", (f"{API}/files?q={urllib.parse.quote(inside_q)}"
                                       f"&fields=files(id)&pageSize=1")).get("files", [])
            if inside:
                raise SystemExit("dossier non vide — suppression annulée")
        body = b'{"trashed": true}'
        self._req("PATCH", f"{API}/files/{target['id']}", body, {"Content-Type": "application/json"})
        return target["id"]

    def cat(self, path: str) -> bytes:
        """Contenu d'un fichier distant (GET ?alt=media) — vérification d'intégrité."""
        parts = [p for p in path.strip("/").split("/") if p]
        if len(parts) < 1:
            raise SystemExit("Chemin vide")
        name = parts[-1]
        parent = self.resolve("/".join(parts[:-1]), create=False)
        q = f"name = '{name}' and '{parent}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id)&pageSize=10")
        found = res.get("files", [])
        if len(found) != 1:
            raise SystemExit(f"{len(found)} fichier(s) « {name} » — lecture annulée (unicité exigée)")
        return self._req("GET", f"{API}/files/{found[0]['id']}?alt=media", raw=True)

    def list(self, path: str):
        folder = self.resolve(path, create=False)
        q = f"'{folder}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name,mimeType,size)&pageSize=100")
        for f in res.get("files", []):
            print(f"{f['id']}  {f['name']}  {f.get('size', '-')}  {f['mimeType']}")

    def tree(self, path: str, prefix: str = ""):
        """Listing récursif : dossiers d'abord, puis fichiers (noms + tailles)."""
        folder = self.resolve(path, create=False)
        q = f"'{folder}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}"
                               f"&fields=files(id,name,mimeType,size)&pageSize=100")
        entries = res.get("files", [])
        dirs = [e for e in entries if e["mimeType"] == FOLDER_MIME]
        files = [e for e in entries if e["mimeType"] != FOLDER_MIME]
        for e in dirs:
            print(f"{prefix}{e['name']}/")
            self.tree(f"{path}/{e['name']}", prefix + "  ")
        for e in files:
            size = int(e.get("size", 0))
            human = f"{size / 1048576:.1f} Mo" if size >= 1048576 else f"{size} o"
            print(f"{prefix}{e['name']}  ({human})")


def main():
    args = sys.argv[1:]
    if not args or args[0] == "--status":
        d = Drive()
        if not d.ok:
            print("STATUS: absents — mettre client_secret_*.json + oauth_token_relais_*.json dans "
                  "/home/z/my-project/.credentials/ ou .credentials/tokens/ "
                  "(restaurer du dossier Drive dédié « Tokens », RESTART.md §6)")
            sys.exit(1)
        try:
            d._access_token()
            print("STATUS: OK — token Drive valide")
        except Exception as e:
            print(f"STATUS: token invalide ({e}) — ré-autorisation utilisateur requise")
            sys.exit(2)
        return

    d = Drive()
    if not d.ok:
        raise SystemExit("Credentials Drive absents — voir gdrive.py --status")
    cmd = args[0]
    if cmd == "find":
        print(d.resolve(args[1]))
    elif cmd == "upload":
        local = Path(args[1])
        if not local.exists():
            raise SystemExit(f"Fichier introuvable : {local}")
        immutable = "--immutable" in args[2:]
        rest = [a for a in args[2:] if a != "--immutable"]
        print(d.upload(local, rest[0], immutable=immutable))
    elif cmd == "list":
        d.list(args[1])
    elif cmd == "tree":
        d.tree(args[1] if len(args) > 1 else "")
    elif cmd == "move":
        print(d.move(args[1], args[2]))
    elif cmd == "cat":
        sys.stdout.buffer.write(d.cat(args[1]))
    elif cmd == "delete":
        print(d.delete(args[1]))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
