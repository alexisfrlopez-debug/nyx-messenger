# Nyx server — nœud serveur (Go)

Nœud autonome : publie son **onion service v3** (via le control port Tor, avec sa propre
clé ed25519 — l'adresse onion est donc son identité), maintient un **journal
d'événements signés** (JSONL append-only), et se réplique par **gossip chiffré NaCl**
avec les pairs.

## Compiler

```bash
go build -o nyx-server ./cmd/nyx-server
```

Dépendances : `golang.org/x/crypto` (ed25519, curve25519, nacl, sha3), `golang.org/x/net` (SOCKS5).

## Lancer

1. Un démon Tor doit tourner avec :

   ```
   ControlPort 9051
   CookieAuthentication 1
   SocksPort 9050
   ```

   (torrc de développement fourni dans `scripts/torrc-dev` — voir `scripts/dev-server.sh`)

2. Le nœud :

   ```bash
   ./nyx-server --data-dir ./data --http-port 8080
   ```

   Au démarrage il crée `identity.json` (clés, 0600), publie le service onion,
   charge le journal, démarre l'API et la boucle gossip.

## API

Voir `../protocol/specs/api.md`. Test rapide en local :

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/identity
```

## Réplication (réseaux de réplicas)

Déclarer les pairs dans `<data-dir>/config.json` (copier `config.example.json`) :

```json
{ "peers": [ { "onion": "abcd….onion", "gossip_pub": "BASE64" } ] }
```

`gossip_pub` s'obtient via `GET /identity` sur le pair. La découverte dynamique
(`GET /gossip/peers`) enrichit automatiquement cette liste (et elle est persistée).

## Sécurité locale

- `identity.json` et `events.jsonl` : permissions 0600, répertoire 0700.
- Le nœud n'accepte de connexions que sur 127.0.0.1 (tout le reste passe par Tor).
- Un événement reçu est **toujours** re-validé (signature + séquence) avant insertion.
