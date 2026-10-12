#!/usr/bin/env bash
# Nyx — démarre un Tor de dev + N réplicas locaux configurés comme pairs.
# Usage : scripts/dev-server.sh [N]   (N réplicas, défaut 3, ports 8081..8080+N)
set -euo pipefail

NYX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
N="${1:-3}"
DEV_DIR="$NYX_ROOT/scripts/dev"
TORRC="$NYX_ROOT/scripts/torrc-dev"

command -v tor >/dev/null 2>&1 || { echo "Tor requis (apt install tor / brew install tor)"; exit 1; }
[ -x "$NYX_ROOT/server/nyx-server" ] || { echo "compiler d'abord : (cd server && go build -o nyx-server ./cmd/nyx-server)"; exit 1; }

mkdir -p "$DEV_DIR"

# 1. Tor de dev
if ! curl -s --max-time 3 --socks5-hostname 127.0.0.1:9050 http://check.torproject.org >/dev/null 2>&1; then
    echo "[dev] démarrage Tor (torrc-dev)…"
    mkdir -p "$NYX_ROOT/scripts/tor_data"
    tor -f "$TORRC" &
    echo "[dev] attente du bootstrap Tor (~15 s)…"
    sleep 15
else
    echo "[dev] Tor déjà actif sur 9050"
fi

# 2. Réplicas
echo "[dev] création de $N réplicas dans $DEV_DIR"
for i in $(seq 1 "$N"); do
    PORT=$((8080 + i))
    DIR="$DEV_DIR/node$i"
    mkdir -p "$DIR"
    if [ ! -f "$DIR/identity.json" ]; then
        echo "[dev] nœud $i : première création d'identité"
    fi
    nohup "$NYX_ROOT/server/nyx-server" \
        --data-dir "$DIR" --http-port "$PORT" \
        > "$DEV_DIR/node$i.log" 2>&1 &
    echo "[dev] nœud $i lancé (API locale : 127.0.0.1:$PORT) — log : scripts/dev/node$i.log"
    sleep 1
done

echo
echo "[dev] les nœuds démarrent sans pairs. Pour les connecter :"
echo "  1. lire chaque identité :  curl -s http://127.0.0.1:PORT/identity"
echo "  2. renseigner les pairs dans scripts/dev/nodeK/config.json (onion + gossip_pub)"
echo "  3. redémarrer les nœuds — la réplication gossip démarre alors (60 s ou à chaque POST)"
echo
echo "[dev] pour observer : tail -f $DEV_DIR/node1.log"
