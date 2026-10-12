#!/usr/bin/env bash
# Nyx — bootstrap de l'environnement de développement
# Fonctionne SANS droits root (installe Go dans ~/.local, configure un torrc de dev).
set -euo pipefail

NYX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="$HOME/.local"
GO_VERSION="${GO_VERSION:-1.22.10}"

say() { printf '\033[1;35m[nyx]\033[0m %s\n' "$*"; }

# 1. Go (tarball utilisateur si absent)
if ! command -v go >/dev/null 2>&1 && [ ! -x "$LOCAL_DIR/go/bin/go" ]; then
    say "installation de Go $GO_VERSION dans $LOCAL_DIR/go…"
    case "$(uname -m)" in
        x86_64) GO_ARCH=amd64 ;;
        aarch64|arm64) GO_ARCH=arm64 ;;
        *) echo "architecture non supportée : $(uname -m)"; exit 1 ;;
    esac
    curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" -o /tmp/go.tgz
    mkdir -p "$LOCAL_DIR"
    tar -C "$LOCAL_DIR" -xzf /tmp/go.tgz
    rm -f /tmp/go.tgz
fi
export PATH="$PATH:$LOCAL_DIR/go/bin"
command -v go >/dev/null 2>&1 && say "Go : $(go version)" || { say "ERREUR : Go indisponible"; exit 1; }

# 2. Tor (paquet système recommandé)
if ! command -v tor >/dev/null 2>&1; then
    say "Tor absent — installer manuellement :"
    say "  Debian/Ubuntu : sudo apt install tor"
    say "  macOS         : brew install tor"
    say "  Windows       : Tor Expert Bundle (https://www.torproject.org)"
else
    say "Tor : $(tor --version | head -1)"
fi

# 3. torrc de développement (pas de root : répertoires dans le projet)
cat > "$NYX_ROOT/scripts/torrc-dev" <<EOF
# Tor de développement pour Nyx — NE PAS utiliser en production
SocksPort 127.0.0.1:9050
ControlPort 127.0.0.1:9051
CookieAuthentication 1
DataDirectory $NYX_ROOT/scripts/tor_data
Log notice file $NYX_ROOT/scripts/tor_data/tor.log
EOF
say "torrc de dev écrit : scripts/torrc-dev"

# 4. Java/Android SDK (vérification only — installation via Android Studio)
if command -v java >/dev/null 2>&1; then
    say "Java : $(java -version 2>&1 | head -1)"
else
    say "Java absent (requis pour l'app Android) — installer Android Studio ou Temurin 17+"
fi
if [ -n "${ANDROID_HOME:-}" ] || [ -d "$HOME/Android/Sdk" ]; then
    say "SDK Android détecté"
else
    say "SDK Android non détecté — installer Android Studio (build app uniquement)"
fi

# 5. Compilation du serveur
say "compilation du serveur…"
(cd "$NYX_ROOT/server" && go build -o nyx-server ./cmd/nyx-server && go test ./...)
say "serveur compilé : server/nyx-server ✓"

say "environnement prêt. Démarrage 3 réplicas de dev : scripts/dev-server.sh"
