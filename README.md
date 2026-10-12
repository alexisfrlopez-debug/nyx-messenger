# Nyx — Messagerie chiffrée sur Tor

> **Nyx** : messagerie Android de bout en bout, anonyme, passant par le réseau Tor,
> avec des serveurs hébergés en *hidden services* (onion services v3) capables de se répliquer.

## Vue d'ensemble

Nyx repose sur trois piliers :

1. **Anonymat réseau** — tout le trafic (clients et serveurs) transite exclusivement par Tor.
   Chaque utilisateur ET chaque serveur est un *onion service v3* : aucune IP n'est jamais exposée.
2. **Chiffrement en couches** — *Signal Protocol* (Double Ratchet + X3DH) pour le contenu
   des messages, *libsodium/NaCl* pour les canaux d'infrastructure (réplication serveur↔serveur,
   stockage local).
3. **Réplication résiliente** — chaque serveur maintient un journal d'événements signés
   (append-only) ; les serveurs se répliquent par *gossip* : aucun point unique de défaillance,
   aucune bascule lente, tolérance aux déconnexions.

L'identité d'un utilisateur **est** son adresse onion : une paire de clés ed25519 auto-générée,
sans inscription, sans numéro de téléphone, sans e-mail.

## État du projet

Voir **[RESTART.md](RESTART.md)** (état détaillé, instructions de reprise pour session agent)
et **[docs/04-roadmap.md](docs/04-roadmap.md)**.

| Composant | État (v0.1) |
|---|---|
| `server/` — nœud Go : onion service + journal signé + gossip + API client | POC fonctionnel |
| `android/` — application Kotlin/Jetpack Compose | Squelette structuré |
| `docs/` — architecture, sécurité, protocoles, ADRs | Rédigé |
| `protocol/` — spécifications filaires | Rédigé |

## Structure du dépôt

```
nyx/
├── RESTART.md            ← INSTRUCTIONS DE REDÉMARRAGE (lire en premier)
├── GUIDE_DEMARRAGE.md    ← COMMENT LANCER (option 1 : serveurs · option 2 : Android)
├── README.md             ← ce fichier
├── docs/                 ← architecture, sécurité, protocoles, ADRs, roadmap, setup
├── protocol/             ← spécifications des formats filaires (wire format, API)
├── server/               ← nœud serveur Go (hidden service + réplication)
├── android/              ← application Android (Kotlin, Jetpack Compose)
└── scripts/              ← bootstrap environnement, lancement dev
```

## Démarrage rapide (serveur)

**→ Guide pas à pas complet : [GUIDE_DEMARRAGE.md](GUIDE_DEMARRAGE.md)** (option 1 :
3 réplicas + test de réplication en une commande ; option 2 : app Android + Orbot).

Version courte :

```bash
# Prérequis : Go ≥ 1.22 (scripts/bootstrap.sh l'installe sans root) + Tor installé
scripts/bootstrap.sh           # environnement + build + tests (une seule fois)
scripts/dev-server.sh 3        # Tor de dev + 3 réplicas câblés automatiquement
go run ./server/cmd/nyx-devtool keygen /tmp/alice.json   # client de test (voir guide)
```

Pour un nœud unique à la main :

```bash
cd server
go build -o nyx-server ./cmd/nyx-server
./nyx-server --data-dir ./data --http-port 8080
# → affiche l'adresse .onion du nœud et démarre l'API
```

## Documentation

| Document | Contenu |
|---|---|
| [docs/00-architecture.md](docs/00-architecture.md) | Architecture globale, flux, composants |
| [docs/01-modele-securite.md](docs/01-modele-securite.md) | Menaces, garanties, limites connues |
| [docs/02-protocole-replication.md](docs/02-protocole-replication.md) | Journal signé, gossip, résolution de conflits |
| [docs/03-protocole-messagerie.md](docs/03-protocole-messagerie.md) | Flux d'un message, chiffrement E2E, éphémère |
| [docs/04-roadmap.md](docs/04-roadmap.md) | Phases v0.1 → market → trade, volet réglementaire |
| [docs/05-setup-dev.md](docs/05-setup-dev.md) | Installation environnement de développement |
| [docs/adr/](docs/adr/) | Décisions d'architecture (ADR-001 à ADR-006) |

## Licences et contributions

**Licence : AGPL-3.0-only** — texte intégral dans [LICENSE](LICENSE), décision
dans [docs/adr/ADR-011-licence-agpl.md](docs/adr/ADR-011-licence-agpl.md).
Copyright « The Nyx Project Contributors » (aucune information personnelle —
ADR-010). Le protocole (`protocol/specs/`) reste librement implémentable : une
spec de format n'est pas du logiciel couvert ; seules les réutilisations du
CODE de ce dépôt sont copyleftées (clause réseau §13 : héberger un fork
modifié du serveur oblige à offrir les sources aux utilisateurs du service).
Projet en développement actif — suivre le journal des sessions dans RESTART.md.
