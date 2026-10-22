# Architecture Nyx (v0.1)

## 1. Vue d'ensemble

Nyx est un système distribué à trois niveaux, entièrement contenu dans le réseau Tor :

```
                    ┌─────────────────────────── Réseau Tor ───────────────────────────┐
                    │                                                                  │
 ┌──────────────┐   │   ┌─────────────────┐  gossip (NaCl)  ┌─────────────────┐        │
 │  App Android │   │   │  Nœud serveur A  │◄──────────────►│  Nœud serveur B  │        │
 │  (Kotlin +   │◄──┼──►│  onion A.onion   │                │  onion B.onion   │        │
 │  Compose)    │   │   │  journal signé   │◄──────────────►│  journal signé   │        │
 │  = onion     │   │   └────────┬────────┘  gossip (NaCl)  └────────┬────────┘        │
 │    service   │   │            │  gossip (NaCl)                    │                 │
 │    client    │   │   ┌────────▼────────┐                 ┌────────▼────────┐        │
 └──────────────┘   │   │  Nœud serveur C  │◄──────────────►│     … n         │        │
                    │   └─────────────────┘                 └─────────────────┘        │
                    └──────────────────────────────────────────────────────────────────┘
```

- **Chaque utilisateur** héberge son propre onion service v3 depuis l'application
  (via Orbot/IPtProxy sur Android) : son adresse onion `…onion` est **à la fois son identité
  et son point d'écoute**. Aucune inscription nulle part.
- **Chaque serveur** (nœud `nyx-server` en Go) héberge aussi un onion service. Il maintient
  un **journal d'événements signés** et se réplique en pair-à-pair avec les autres nœuds
  (*gossip* chiffré NaCl). Un client peut déposer et récupérer ses messages depuis
  n'importe quel réplica — la disponibilité ne dépend d'aucun serveur unique.
- **Le contenu est chiffré de bout en bout** : les serveurs ne voient que des enveloppes
  opaques et des métadonnées de routage minimales.

## 2. Composants

### 2.1 Application Android (`android/`)

| Module interne | Rôle |
|---|---|
| `tor/TorManager` | Connexion au démon Tor (Orbot en v0.1, IPtProxy embarqué en v0.2) ; fournit un proxy SOCKS5 local (127.0.0.1:9050) |
| `tor/OnionServiceManager` | Création/publication du service onion v3 du client (clé d'identité = clé onion) |
| `crypto/IdentityManager` | Génération et persistance sécurisée de la paire ed25519 (Android Keystore + stockage chiffré) |
| `crypto/SessionManager` | Chiffrement E2E : sealed box NaCl (v0.1), Signal Protocol/libsignal (v0.2) |
| `data/db` | Base locale Room + SQLCipher : messages, contacts, événements en attente |
| `net/RelayClient` | Client HTTP (OkHttp) forcé via SOCKS5 Tor ; parle à n'importe quel réplica |
| `ui/` | Jetpack Compose : onboarding (création d'identité), conversations, chat |

### 2.2 Nœud serveur (`server/`, Go)

| Module interne | Rôle |
|---|---|
| `internal/tor` | Client minimal du control port Tor : `AUTHENTICATE`, `ADD_ONION ED25519-V3:…` — le service onion du nœud utilise **la clé d'identité du nœud** |
| `internal/journal` | Événements signés ed25519, représentation canonique, validation, persistance JSONL append-only |
| `internal/gossip` | Boucle de réplication pair-à-pair : push/pull chiffré NaCl entre réplicas, dé-duplication par `sha256(canonique)` |
| `internal/api` | Routes client + gossip (voir `protocol/specs/api.md`) |
| `internal/config` | Configuration JSON : pairs connus, ports, répertoire de données |

Le nœud est **un binaire statique unique**, sans dépendance à des services tiers. Il
nécessite un démon Tor à côté de lui (control port 9051, SOCKS 9050).

## 3. Flux principaux

### 3.1 Premier démarrage d'un utilisateur

1. L'app génère une paire ed25519 → Tor la publie comme service onion v3.
2. L'adresse `…onion` obtenue est l'identité de l'utilisateur ; elle est affichée (QR code).
3. L'app enregistre la liste des réplicas par défaut (adresses onion des serveurs Nyx).
4. Aucune donnée personnelle n'est demandée ; la clé privée est scellée localement.

### 3.2 Envoi d'un message (Alice → Bob)

1. Alice écrit un message ; `SessionManager` chiffre `body` pour la clé publique de Bob
   (sealed box v0.1 / Double Ratchet v0.2).
2. L'app construit un événement `msg` signé (clé d'Alice), `to = bob…onion`, et le POSTe
   à **son réplica de rattachement** (n'importe lequel, via Tor).
3. Le serveur valide (signature, séquence, taille), l'append au journal, répond 200.
4. La boucle gossip réplique l'événement vers tous les réplicas connus.
5. Le client de Bob, en polling `GET /client/inbox?to=bob…onion` sur son réplica, reçoit
   l'événement, vérifie la signature d'Alice, déchiffre, stocke localement, notifie.

Latence attendue : publication onion côté Alice déjà établie (service permanent) →
l'acheminement Tor bout-en-bout se situe typiquement entre 1 et 5 s ; le polling client
(v0.1, 10 s) peut l'augmenter. Une v0.2 remplacera le polling par des connexions directes
entre onion services des deux clients (push P2P instantané) — le serveur reste le filet
de disponibilité (mode asynchrone).

### 3.3 Réplication serveur ↔ serveur

Chaque nœud maintient pour chaque pair un curseur par auteur (`{author: dernier_seq_envoyé}`).
Periodiquement (60 s par défaut) et à chaque nouvel événement : push puis pull avec chaque
pair. Tout événement reçu est revalidé intégralement (signature, séquence par auteur) avant
insertion — un pair compromis ne peut injecter que des événements **signés par les clés
d'auteurs légitimes** qu'il a légitimement reçus : il peut censurer/retarder, jamais forger.
La résolution de conflit est triviale par conception : le séquencement est **par auteur**
(deux serveurs ne peuvent diverger sur l'ordre des événements d'un même auteur, et
l'ordre entre auteurs n'a pas de sémantique de dépendance en v0.1).

## 4. Choix structurants (renvois ADR)

| Sujet | Décision | ADR |
|---|---|---|
| Réplication | Journal signé + gossip (pas de Raft, pas de quorum en v0.1) | ADR-001 |
| Chiffrement | Signal Protocol (E2E) + libsodium (canaux, stockage) | ADR-002 |
| Identité | L'adresse onion est l'identité (ed25519) | ADR-003 |
| Client mobile | Kotlin natif + Jetpack Compose | ADR-004 |
| Langage serveur | Go | ADR-005 |
| Extensions futures | Market store puis trade crypto (phase 2/3) | ADR-006 |

## 5. Ce que l'architecture v0.1 ne fait PAS (volontairement)

- Pas de chiffrement du champ `to` (métadonnée de routage visible des serveurs) → v0.2.
- Pas de purge serveur des événements TTL → v0.2 (les clients honorent le TTL).
- Pas de groupe multi-participants → phase 2 (nécessite sender keys / MLS).
- Pas de pièces jointes volumineuses → phase 2 (chunks chiffrés sur le journal).
- Pas de séquenceur/quorum pour les opérations transactionnelles → phase 2 (market).
  Les interfaces (`journal.Event`) sont conçues pour accueillir un type `tx` séquencé
  par quorum sans casser le format existant.
