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
  gdrive.py upload <fichier> <chemin/>   → téléverse (remplace par nom)
  gdrive.py list <chemin/>               → contenu
  gdrive.py --status                     → credentials présents ? token valide ?
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

CREDS_DIR = Path("/home/z/my-project/.credentials")
API = "https://www.googleapis.com/drive/v3"
TOKEN_URL = "https://oauth2.googleapis.com/token"
# Racine ANCRÉE : dossier projet « Messagerie » (ne JAMAIS résoudre depuis la racine
# de Mon Drive — d'autres projets y ont des dossiers homonymes, ex. « livrables »).
ROOT_FOLDER_ID = "1xR8aME_sCa-8Q3OWBXjpO5pdCtwU4JmA"
FOLDER_MIME = "application/vnd.google-apps.folder"


class Drive:
    def __init__(self):
        self.token = None
        self.token_exp = 0
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
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
            return data if raw else (json.loads(data) if data else {})

    # ---------------------------------------------------------------- API haut niveau

    def resolve(self, path: str, create: bool = True) -> str:
        """Résout un chemin « a/b/c » depuis le dossier projet « Messagerie » (ancré, création au besoin)."""
        parent = ROOT_FOLDER_ID  # ancre : jamais « root »
        for part in [p for p in path.strip("/").split("/") if p]:
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
        return parent

    def upload(self, local: Path, remote_dir: str) -> str:
        """Téléverse local dans remote_dir (remplace un fichier du même nom)."""
        folder = self.resolve(remote_dir)
        name = local.name
        q = (f"name = '{name}' and '{folder}' in parents and trashed = false")
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id)&pageSize=5")
        existing = res.get("files", [])
        meta = json.dumps({"name": name, "parents": [folder]}).encode()
        url = f"{API}/files?uploadType=multipart" if not existing else f"{API}/files/{existing[0]['id']}?uploadType=multipart"
        method = "POST" if not existing else "PATCH"
        boundary = "nyx37boundary"
        mp = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
              f"{meta.decode()}\r\n--{boundary}\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
        mp += local.read_bytes() + f"\r\n--{boundary}--".encode()
        fid = self._req(method, url, mp, {"Content-Type": f"multipart/related; boundary={boundary}"})["id"]
        return fid

    def list(self, path: str):
        folder = self.resolve(path, create=False)
        q = f"'{folder}' in parents and trashed = false"
        res = self._req("GET", f"{API}/files?q={urllib.parse.quote(q)}&fields=files(id,name,mimeType,size)&pageSize=100")
        for f in res.get("files", []):
            print(f"{f['id']}  {f['name']}  {f.get('size', '-')}  {f['mimeType']}")


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
        print(d.upload(local, args[2]))
    elif cmd == "list":
        d.list(args[1])
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
