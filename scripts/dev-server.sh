#!/usr/bin/env bash
# Nyx — démarre un Tor de développement + N réplicas locaux, câblés AUTOMATIQUEMENT
# comme pairs, puis vérifie la chaîne complète.
#
# Usage :
#   scripts/dev-server.sh [N]     démarre (ou redémarre proprement) N réplicas — défaut 3
#   scripts/dev-server.sh stop    arrête les réplicas et le Tor de développement
#
# Ports : Tor SOCKS 9050 / control 9051 (scripts/torrc-dev), API locales 8081..8080+N.
# Données : scripts/dev/nodeN/ (identités, journal, config) — jamais committées.
set -euo pipefail

NYX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
N="${1:-3}"
DEV_DIR="$NYX_ROOT/scripts/dev"
TOR_DIR="$NYX_ROOT/scripts/tor_data"
TORRC="$NYX_ROOT/scripts/torrc-dev"
TOR_LOG="$TOR_DIR/tor.log"
COOKIE="$TOR_DIR/control.authcookie"
GOSSIP_INTERVAL=15

say()  { printf '\033[1;36m[nyx-dev]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[nyx-dev] ⚠\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[nyx-dev] ERREUR\033[0m %s\n' "$*" >&2; exit 1; }

port_open() { timeout 1 bash -c ">/dev/tcp/$1/$2" 2>/dev/null; }

read_field() { # read_field <fichier.json> <clé> — JSON plat, sans dépendance jq
    sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$1" | head -1
}

stop_nodes() {
    local pidf pid i
    for i in $(seq 1 16); do
        pidf="$DEV_DIR/node$i.pid"
        [ -f "$pidf" ] || continue
        pid="$(cat "$pidf" 2>/dev/null || true)"
        if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            say "nœud arrêté (pid $pid, node$i)"
        fi
        rm -f "$pidf"
    done
}

wait_port_free() { # wait_port_free <port> — s'assure qu'un ancien process a bien libéré le port
    local port="$1" ok=""
    for _ in $(seq 1 20); do
        port_open 127.0.0.1 "$port" || { ok=1; break; }
        sleep 0.5
    done
    [ -n "$ok" ] || die "le port $port reste occupé (ancien processus non terminé ?) — voir : ps aux | grep nyx-server"
}

# ---- sous-commande stop ------------------------------------------------------
if [ "$N" = "stop" ]; then
    stop_nodes
    if [ -f "$TOR_DIR/tor-dev.pid" ]; then
        tpid="$(cat "$TOR_DIR/tor-dev.pid" 2>/dev/null || true)"
        if [ -n "${tpid:-}" ] && kill -0 "$tpid" 2>/dev/null; then
            kill "$tpid" 2>/dev/null || true
            say "Tor de développement arrêté (pid $tpid)"
        fi
        rm -f "$TOR_DIR/tor-dev.pid"
    else
        pkill -f "tor -f $TORRC" 2>/dev/null && say "Tor de développement arrêté" || say "pas de Tor de développement en cours"
    fi
    say "arrêté. (Un Tor système éventuel n'est pas touché.)"
    exit 0
fi

case "$N" in
    ''|*[!0-9]*) die "argument invalide : « $N » (nombre de réplicas ou « stop »)" ;;
esac
[ "$N" -ge 1 ] && [ "$N" -le 16 ] || die "nombre de réplicas : 1 à 16"

# ---- pré-vol -----------------------------------------------------------------
command -v tor >/dev/null 2>&1 || die "Tor requis — Debian/Ubuntu : sudo apt install tor · macOS : brew install tor"
[ -x "$NYX_ROOT/server/nyx-server" ] || die "serveur non compilé : (cd server && go build -o nyx-server ./cmd/nyx-server)"
mkdir -p "$DEV_DIR" "$TOR_DIR"

# ---- 1. Tor ------------------------------------------------------------------
if port_open 127.0.0.1 9051; then
    say "Tor déjà actif sur le control port 9051"
else
    if port_open 127.0.0.1 9050; then
        die "quelque chose écoute déjà sur 9050 (SOCKS) mais pas de control port sur 9051.
  → si c'est le Tor système : sudo systemctl stop tor   puis relance ce script
  → ou configure-le (ControlPort 9051 + CookieAuthentication 1) et relance."
    fi
    say "démarrage du Tor de développement (torrc : $TORRC)…"
    tor -f "$TORRC" > "$TOR_DIR/stdout.log" 2>&1 &
    echo $! > "$TOR_DIR/tor-dev.pid"
    ok=""
    for _ in $(seq 1 60); do
        if port_open 127.0.0.1 9051; then ok=1; break; fi
        sleep 1
    done
    [ -n "$ok" ] || die "Tor n'a pas ouvert son control port en 60 s — log : $TOR_LOG"
    say "Tor démarré (pid $(cat "$TOR_DIR/tor-dev.pid")) — bootstrap des circuits en arrière-plan (~10-30 s)"
fi

# Cookie du control port : notre torrc le met dans scripts/tor_data/.
# (Le nœud sait auto-détecter /run/tor/control.authcookie pour un Tor système.)
COOKIE_ARG=()
if [ -r "$COOKIE" ]; then
    COOKIE_ARG=(--cookie-file "$COOKIE")
else
    warn "cookie $COOKIE absent ou illisible — les nœuds tenteront l'auto-détection (/run/tor, /var/run/tor)"
fi

# ---- 2. arrêt des réplicas précédents (relance idempotente) -------------------
stop_nodes
sleep 1

# ---- 3. phase A : démarrage + création des identités --------------------------
say "lancement de $N réplicas (API locale : 127.0.0.1:8081..8080+$N)…"
for i in $(seq 1 "$N"); do
    PORT=$((8080 + i))
    DIR="$DEV_DIR/node$i"
    mkdir -p "$DIR"
    wait_port_free "$PORT"
    nohup "$NYX_ROOT/server/nyx-server" \
        --data-dir "$DIR" --http-port "$PORT" \
        "${COOKIE_ARG[@]}" \
        --gossip-interval "$GOSSIP_INTERVAL" \
        > "$DEV_DIR/node$i.log" 2>&1 &
    echo $! > "$DEV_DIR/node$i.pid"
done

say "attente des identités (ed25519 → adresse onion)…"
for i in $(seq 1 "$N"); do
    DIR="$DEV_DIR/node$i"
    ok=""
    for _ in $(seq 1 20); do
        if [ -s "$DIR/identity.json" ] && grep -q '"onion"' "$DIR/identity.json"; then ok=1; break; fi
        sleep 0.5
    done
    [ -n "$ok" ] || die "nœud $i : identité absente après 10 s — log : $DEV_DIR/node$i.log"
done

# ---- 4. phase B : câblage automatique des pairs -------------------------------
say "câblage des pairs (chaque nœud reçoit l'onion + clé gossip des $((N-1)) autres)…"
ONION_FILE="$DEV_DIR/.onions"
: > "$ONION_FILE"
for i in $(seq 1 "$N"); do
    o="$(read_field "$DEV_DIR/node$i/identity.json" onion)"
    p="$(read_field "$DEV_DIR/node$i/identity.json" gossip_pub)"
    [ -n "$o" ] && [ -n "$p" ] || die "nœud $i : identité incomplète"
    printf '%s %s\n' "$i" "$o" >> "$ONION_FILE"
done

for i in $(seq 1 "$N"); do
    DIR="$DEV_DIR/node$i"
    {
        echo '{'
        echo "  \"data_dir\": \"$DIR\","
        echo '  "peers": ['
        first=1
        for j in $(seq 1 "$N"); do
            [ "$j" = "$i" ] && continue
            oj="$(read_field "$DEV_DIR/node$j/identity.json" onion)"
            pj="$(read_field "$DEV_DIR/node$j/identity.json" gossip_pub)"
            [ $first -eq 1 ] || echo ','
            printf '    { "onion": "%s", "gossip_pub": "%s" }' "$oj" "$pj"
            first=0
        done
        echo
        echo '  ]'
        echo '}'
    } > "$DIR/config.json"
done

# ---- 5. phase C : redémarrage pour charger les pairs ---------------------------
stop_nodes
sleep 1
for i in $(seq 1 "$N"); do
    PORT=$((8080 + i))
    DIR="$DEV_DIR/node$i"
    wait_port_free "$PORT"
    nohup "$NYX_ROOT/server/nyx-server" \
        --data-dir "$DIR" --http-port "$PORT" \
        "${COOKIE_ARG[@]}" \
        --gossip-interval "$GOSSIP_INTERVAL" \
        > "$DEV_DIR/node$i.log" 2>&1 &
    echo $! > "$DEV_DIR/node$i.pid"
done

say "vérification de l'API…"
for i in $(seq 1 "$N"); do
    PORT=$((8080 + i))
    ok=""
    for _ in $(seq 1 20); do
        if curl -s --max-time 1 "http://127.0.0.1:$PORT/health" | grep -q '"ok":true'; then ok=1; break; fi
        sleep 0.5
    done
    if [ -z "$ok" ]; then
        echo "---- dernières lignes de $DEV_DIR/node$i.log ----" >&2
        tail -5 "$DEV_DIR/node$i.log" >&2 || true
        die "nœud $i : API injoignable"
    fi
done

# ---- 6. résumé -----------------------------------------------------------------
echo
say "✔ $N réplicas en cours, câblés comme pairs (gossip chiffré via Tor, période ${GOSSIP_INTERVAL}s)"
echo
echo "  Nœud   API locale            Adresse onion (= identité)"
echo "  ----   --------------------  ------------------------------------------------"
while read -r i o; do
    printf '  %s      http://127.0.0.1:%s  %s\n' "$i" "$((8080 + i))" "$o"
done < "$ONION_FILE"
echo
echo "  Logs    : scripts/dev/nodeN.log        Données : scripts/dev/nodeN/"
echo
echo "  TEST DE RÉPLICATION (depuis la racine du projet) :"
echo "    go build -o /tmp/nyx-devtool ./server/cmd/nyx-devtool"
echo "    /tmp/nyx-devtool keygen /tmp/alice.json"
echo "    /tmp/nyx-devtool event --keys /tmp/alice.json --body \"salut nyx\" > /tmp/ev.json"
echo "    curl -s -X POST http://127.0.0.1:8081/client/event -d @/tmp/ev.json"
echo "    # → l'événement se propage aux autres nœuds (immédiat, ou ≤ ${GOSSIP_INTERVAL}s) :"
echo "    A=\$(/tmp/nyx-devtool show --keys /tmp/alice.json | cut -d' ' -f2)"
echo "    curl -s \"http://127.0.0.1:8082/client/events?author=\$A\"   # nœud 2 : présent ✔"
echo "    curl -s \"http://127.0.0.1:8083/client/events?author=\$A\"   # nœud 3 : présent ✔"
echo
echo "  ARRÊT : scripts/dev-server.sh stop"
echo "  NOTE  : la publication des onions par Tor prend ~10-30 s après le bootstrap ;"
echo "          le gossip inter-nœuds démarre dès que les circuits sont établis."
