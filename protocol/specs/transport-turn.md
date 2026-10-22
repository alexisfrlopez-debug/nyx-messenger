# Spécification — Transport relais TURN (Cloudflare) pour réplicas Nyx (v1)

Statut : **acté** (ADR-014) — implémentation phase 1 : `server/internal/turn/`
(génération de credentials) + CLI `nyx-devtool turn-creds` ; **allocation RFC
6062 = PHASE 2 implémentée** (§6 : `stun.go` + `alloc.go` + `worker.go` +
`reuseport_linux.go`, 16 tests verts dont E2E contre un faux relais local ;
config opt-in `turn` + worker de maintien dans nyx-server ; CLI
`nyx-devtool turn-alloc`) — reste à brancher : publication rendezvous signé
(§4, la structure du rendezvous n'existe pas encore côté serveur).

> Ce document spécifie comment un réplica Nyx privé de port entrant (VPS
> restrictif, réseau NAT strict) peut néanmoins **recevoir** des connexions via
> un relais TURN standard, en l'occurrence le service TURN gratuit de
> Cloudflare. Voie **OPT-IN, jamais par défaut** (ADR-009 inchangé) : le tiers
> relais observe IP/timing/volume — compromis assumé par l'opérateur qui
> l'active.

## 1. Rôles

```
                 ┌─────────────────────────────┐
  client/réplica │  turns:turn.cloudflare.com   │
  (via Tor       │        :5349 (TCP/TLS)       │
   SOCKS5) ─────►│  relais RFC 5766/6062        │
                 └──────────┬──────────────────┘
                            │ adresse relayée <ip>:<port>
                            ▼
                 réplica Nyx (allocation TCP RFC 6062,
                 outbound-only vers le relais)
```

- le réplica effectue une **allocation** chez le relais (sortie seule) ;
- l'**adresse relayée** obtenue est publiée comme point de jonction alternatif ;
- les pairs joignent cette adresse **via le relais** (indistinguishable de HTTPS
  sur `turns:5349`) ;
- Tor reste le canal d'accès (TCP only → UDP exclu : `turns:…5349?transport=tcp`
  est la seule URL utilisée dans le contexte Nyx).

## 2. Credentials éphémères (PHASE 1 — implémenté)

Les credentials TURN sont **jamais** statiques ni embarqués dans l'APK : ils
sont générés à la demande par un **backend de confiance** (le réplica lui-même
ou la console nyx-admin) :

```
POST https://rtc.live.cloudflare.com/v1/turn/keys/{key_id}/credentials/generate-ice-servers
Headers :
  Authorization: Bearer <API_TOKEN>
  Content-Type: application/json
Body :
  { "ttl": 86400 }
```

Réponse :

```json
{
  "iceServers": [
    {
      "urls": [
        "stun:stun.cloudflare.com:3478",
        "turn:turn.cloudflare.com:3478?transport=udp",
        "turn:turn.cloudflare.com:3478?transport=tcp",
        "turns:turn.cloudflare.com:5349?transport=tcp"
      ],
      "username": "<éphémère>",
      "credential": "<éphémère>"
    }
  ]
}
```

Implémentation de référence : `server/internal/turn/turncreds.go`
(`turn.GenerateICEServers`) — stdlib uniquement (net/http, encoding/json).
Exposition CLI :

```
NYX_TURN_API_TOKEN=… go run ./server/cmd/nyx-devtool turn-creds \
    --key-id <TOKEN_ID> [--ttl 86400]
```

- le token est lu depuis **l'environnement** (`NYX_TURN_API_TOKEN`) — jamais
  d'argument de ligne de commande (visible dans `ps`), jamais de fichier du
  dépôt ;
- en cas d'échec (401/403/429) : erreur explicite, aucun repli silencieux.

### Hygiène des secrets

| Secret | Où il vit | Où il ne vit JAMAIS |
|---|---|---|
| API Token Cloudflare | environnement de l'opérateur / `data_dir/config.json` (replica, phase 2) | dépôt, `config.example.json` (valeur factice documentée), APK, logs |
| username/credential éphémères | mémoire du processus, TTL ≤ 86400 s | disque |

> Note session 17 : l'API Token ayant transit en clair dans le chat, une
> **rotation** est conseillée une fois l'intégration testée.

## 3. URLs utilisées par Nyx

| URL | Usage Nyx |
|---|---|
| `stun:stun.cloudflare.com:3478` | ignorée par Nyx (pas d'ICE WebRTC) — acceptée pour compatibilité du modèle iceServers |
| `turn:…:3478?transport=udp` | **exclue** — Tor est TCP-only ; conservée dans la réponse brute de l'API |
| `turn:…:3478?transport=tcp` | repli si turns:5349 indisponible |
| `turns:turn.cloudflare.com:5349?transport=tcp` | **voie principale** : TLS, ressemble à HTTPS, passe les pare-feux et le SOCKS5 de Tor |

## 4. Publication de l'adresse relayée (phase 2 — spécification)

Le rendezvous signé (ADR-009 §3, embarqué dans l'APK) est **étendu** :

```json
{
  "onion": "abcd…56.onion",
  "gossip_pub": "…",
  "sig": "…",
  "transports": [
    { "kind": "turn", "urls": "turns:turn.cloudflare.com:5349?transport=tcp",
      "addr": "203.0.113.7:54321", "exp": 1730000000 }
  ]
}
```

- `kind=turn` : l'adresse `addr` est une **adresse relayée** (allocation
  RFC 6062 du réplica) ; `exp` = expiration (≤ TTL des credentials) ;
- la signature couvre `transports` : un tiers ne peut pas substituer une
  adresse relais à un réplica ;
- priorité client : onion v3 d'abord (ADR-009), transports alternatifs ensuite,
  **seulement si** l'onion échoue (journal du client affiche le repli).

## 5. Sécurité

- **confidentialité** : identique au transport onion — les connexions portent
  les mêmes enveloppes chiffrées/signées ; le relais ne voit que du TCP opaque ;
- **métadonnées** : Cloudflare observe IP/timing/volume (et la cible de
  l'allocation) → voie opt-in, à éviter si le modèle de menace inclut
  Cloudflare ;
- **disponibilité** : le relais est un SPOF par réplica — le réseau reste
  disponible tant qu'UN réplica au moins est joignable en onion ;
- **anti-abus** : credentials éphémères TTL court ; le relais n'est jamais
  publié seul (toujours attaché à un rendezvous signé).

## 6. Phase 2 — allocation RFC 6062 côté réplica (implémentée — session 18)

Implémentation : `server/internal/turn/` — stdlib (+ x/net pour SOCKS5) —
**pion écarté** (ADR-014) :

1. `DialControl` : TLS vers `turn.cloudflare.com:5349` (via SOCKS5 Tor si
   configuré) ; écouteur couplé SO_REUSEPORT créé AVANT le dial (le relais
   ouvre la connexion peer ENTRANTE vers le port source du contrôle, §4.4) ;
2. `Allocate` : `ALLOCATE` avec `REQUESTED-TRANSPORT=TCP (6)` (RFC 6062 §4.2),
   credentials SHORT-TERM (clé = credential éphémère, HMAC-SHA1 RFC 5389
   §15.4) + FINGERPRINT (CRC32 XOR « STUN ») ; XOR-RELAYED-ADDRESS + LIFETIME
   décodés ; réponses de succès sans MESSAGE-INTEGRITY rejetées (anti-MITM) ;
3. `Connect` : `CONNECT` vers le pair cible → `ConnectionAttempt` (indication
   mise en file si intercalée) → acceptation de la TCP entrante dont la SOURCE
   = adresse relayée (vérifiée, RFC 6062 §4.4) → `CONNECTION-BIND` (même
   TRANSACTION-ID que l'indication, signé) → canal applicatif retourné ;
4. publication au rendezvous (§4) : `Allocation.RelayAddr()` (et
   `RelayInfo.RelayAddr` du worker) — BRANCHEMENT À FAIRE quand le rendezvous
   signé existera côté serveur ;
5. `worker.MaintainRelay` : regénération des credentials éphémères (§2),
   `REFRESH` aux 2/3 du TTL, ré-allocation après rupture — backoff 30 s →
   15 min ; config opt-in `turn` {relay_url, key_id, api_token (SECRET),
   socks, lifetime} ; CLI `nyx-devtool turn-alloc` (diagnostic opérateur,
   token via `NYX_TURN_API_TOKEN`).

**Contrainte §4.4** : l'acceptation des connexions peer exige un chemin TCP
DIRECT (le relais ramène la connexion entrante par le chemin NAT déjà
traversé) — Tor/SOCKS5 ne sert qu'à la connexion de contrôle (gén/refresh).
Tests : `alloc_test.go` — faux relais TURN TCP local (ALLOCATE/CONNECT/
ConnectionAttempt/CONNECTION-BIND/REFRESH, relais d'octets bidirectionnel),
erreurs explicites (401 credential invalide, réponse mal signée rejetée,
frames tronquées, cookie invalide), URLs (turns:// et turns: sans //, UDP
refusé, ports par défaut 5349/3478, IPv6).

Budget réalisé : ~700 lignes + ~450 lignes de tests (estimation §6 : ~600).
