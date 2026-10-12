# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-12** (session 1).

---

## 1. Le projet en 30 secondes

**Nyx** = messagerie Android **anonyme et chiffrée de bout en bout**, entièrement passant
par **Tor**. Les serveurs sont des **hidden services (onion v3) répliqués** entre eux.
L'identité d'un utilisateur est **son adresse onion** (paire ed25519 auto-générée).
Trajectoire : v1 messagerie → phase 2 **market store** entre pairs (escrow multisig,
réputation) → phase 3 trade crypto (crypto↔crypto d'abord ; le fiat est réglementé —
PSAN/AMF — voir ADR-006, décision explicite requise avant tout développement).

## 2. Décisions actées (ne pas rouvrir sans nouvel ADR)

| # | Sujet | Décision | ADR |
|---|---|---|---|
| 1 | Réplication serveurs | Journal d'événements signés + gossip NaCl (PAS de Raft/quorum pour la messagerie) | ADR-001 |
| 2 | Chiffrement | Signal Protocol E2E (v0.2) + libsodium (canaux/stockage) ; sealed box en v0.1 | ADR-002 |
| 3 | Identité | Adresse onion = identité (ed25519) ; invitations à la phase 2 | ADR-003 |
| 4 | Client | Kotlin natif + Jetpack Compose (Orbot v0.1, IPtProxy v0.2) | ADR-004 |
| 5 | Serveur | Go ≥ 1.22, binaire statique, stdlib + x/crypto | ADR-005 |
| 6 | Phases 2/3 | Market : quorum `tx` dédié, escrow 2-of-3, réputation escrow ; fiat = réglementaire d'abord | ADR-006 |

Dossier des ADR : `docs/adr/`. Toute évolution d'architecture = nouvel ADR.

## 3. État actuel (session 1 — 2026-10-12)

### Fait ✅
- Architecture complète rédigée : `docs/00-architecture.md`, `01-modele-securite.md`,
  `02-protocole-replication.md`, `03-protocole-messagerie.md`, `04-roadmap.md`,
  `05-setup-dev.md` + 6 ADR.
- Spécifications filaires : `protocol/specs/wire-format.md`, `protocol/specs/api.md`.
- **Nœud serveur Go compilé et testé** (`server/`) :
  - client control port Tor minimal (`ADD_ONION ED25519-V3:…` — l'identité EST l'onion) ;
  - journal signé ed25519, séquence stricte par auteur, events en attente, JSONL append-only ;
  - décodage/codage adresse onion v3 (checksum SHA3) aller-retour testé ;
  - gossip chiffré NaCl (push/pull + curseurs) via SOCKS5 ;
  - API client (`/health`, `/identity`, `/client/event`, `/client/inbox`, `/client/events`)
    et gossip (`/gossip/peers|push|pull`) ;
  - tests unitaires verts (`go test ./...`), build : `server/nyx-server`.
- **Squelette Android complet** (`android/`) : Gradle version-catalog, Compose, Room+SQLCipher,
  lazysodium, OkHttp SOCKS5 ; crypto (KeystoreBox, IdentityManager, MessageCipher,
  OnionCodec miroir du Go), TorManager (Orbot), OnionServiceManager (interface),
  RelayClient, NyxRepository, UI (onboarding/liste/chat). **Non encore compilé** (pas de
  SDK Android dans l'environnement de la session 1) — `android/README.md` liste les TODO device.
- Scripts : `scripts/bootstrap.sh` (env sans root), `scripts/dev-server.sh` (3 réplicas),
  `scripts/torrc-dev` (généré par bootstrap).
- Git : branche `main` committée, dépôt GitHub **créé et poussé** :
  `https://github.com/alexisfrlopez-debug/nyx-messenger` (61 fichiers).

### À faire (ordre suggéré) 🔜
1. **Test 3 réplicas sur machine avec Tor** : `scripts/bootstrap.sh` puis
   `scripts/dev-server.sh 3` ; connecter les pairs (cf. sortie du script) ; observer la
   réplication (POST d'un événement sur node1 → visible sur node2/node3).
2. **Android sur device** : suivre `android/README.md` (TODO prioritaires) — compiler,
   brancher Orbot, point d'accès réplica de dev, `verifySignature` (crypto_sign_verify_detached).
3. **CI GitHub** (facultatif) : workflow Go (build + tests) ; plus tard build Android.
4. v0.2 (voir roadmap `docs/04-roadmap.md`) : libsignal, chiffrement du champ `to`,
   IPtProxy, purge TTL serveur, push P2P direct, sauvegarde BIP39.

## 4. Structure du projet

```
nyx/
├── RESTART.md              ← ce fichier (racine du répertoire de travail)
├── README.md               ← vue d'ensemble
├── docs/                   ← architecture, sécurité, protocoles, roadmap, setup, ADR/
├── protocol/specs/         ← wire-format.md, api.md  (TOUTE évolution commence ici)
├── server/                 ← nœud Go (cmd/nyx-server, internal/{tor,journal,gossip,api,identity,config})
├── android/                ← app Kotlin/Compose (voir android/README.md)
└── scripts/                ← bootstrap.sh, dev-server.sh, torrc-dev
```

## 5. Comment redémarrer une session agent rapidement

1. **Lire d'abord** : ce fichier, puis `README.md`, puis `docs/00-architecture.md`
   et `docs/04-roadmap.md`. Ne rien deviner : les specs filaires font foi
   (`protocol/specs/`).
2. **Prompt type à donner à l'agent** (copier-coller) :

   > Tu travailles sur le projet Nyx (messagerie chiffrée sur Tor, serveurs onion
   > répliqués). Lis d'abord RESTART.md à la racine : contexte, décisions actées,
   > état, TODO. Consulte ensuite docs/adr/ avant toute décision d'architecture.
   > Ta mission : [DÉCRIRE LA TÂCHE — ex. « TODO n°1 : test 3 réplicas »].
   > Mets à jour RESTART.md (§3 et §8) à la fin de la session.

3. **Commandes de référence** :

```bash
# Serveur (Go ≥ 1.22 requis — go installé dans ~/.local/go si pas de root)
cd server && go build -o nyx-server ./cmd/nyx-server && go test ./...
./nyx-server --data-dir ./data --http-port 8080     # Tor doit tourner (ControlPort 9051, Cookie 1)

# Environnement complet + 3 réplicas de dev
scripts/bootstrap.sh
scripts/dev-server.sh 3

# Android (Android Studio + SDK 35)
cd android && ./gradlew :app:assembleDebug
```

4. **Règles de session** :
   - toute évolution du format filaire → `protocol/specs/` D'ABORD, puis serveur, puis client ;
   - aucun secret dans le dépôt (identités, clés, tokens) — `.gitignore` déjà calibré ;
   - terminer la session en mettant à jour ce fichier (§3 État + §8 Journal) ;
   - re-sauvegarder le projet vers le Drive (voir §6).

## 6. Accès et sauvegarde

| Ressource | Référence |
|---|---|
| Répertoire de travail (sauvegarde systématique) | Dossier Drive « travail » (lien fourni par l'utilisateur dans la conversation d'origine) |
| Dossier « accès » | Contient : `client_secret_*.json`, `oauth_token_relais_*.json` (Drive, scope complet, cycle ~7 j → ré-autorisation à prévoir), `physichong-*.json` (compte de service), `token_git.txt` (PAT GitHub fine-grained, compte `alexisfrlopez-debug`) |
| Module de synchro Drive | `scripts/gdrive.py` (session 1 ; hors dépôt — voir note) + credentials dans `.credentials/` du workspace (JAMAIS dans le dépôt) |
| GitHub | Dépôt : **`github.com/alexisfrlopez-debug/nyx-messenger`** (créé session 1) ; `token_git.txt` = PAT fine-grained du compte `alexisfrlopez-debug` (création/push OK) |

Note : `scripts/gdrive.py` et `.credentials/` vivent dans le **workspace de la session**
(`/home/z/my-project/`), pas dans le dépôt Nyx. Une nouvelle session doit les récupérer
depuis le dossier Drive « accès » (tout y est : token OAuth + client secret).
Ré-autorisation OAuth : le refresh_token expire ~7 jours (note dans oauth_token_relais).
Si expiré → demander à l'utilisateur une nouvelle autorisation.

## 7. Environnement de la session 1 (référence)

| Outil | Version/Emplacement |
|---|---|
| Go | 1.22.10 — `/home/z/.local/go/bin/go` (tarball utilisateur, pas de root) |
| Java | OpenJDK 21 (présent) — Gradle Android OK |
| Tor | **absent** de l'environnement session 1 (pas de root) → tests Tor à faire ailleurs |
| Python | 3.12 (module gdrive.py : stdlib only) |
| Git | 2.47.3 |

## 8. Journal des sessions (append-only)

| Date | Session | Résumé |
|---|---|---|
| 2026-10-12 | 1 | Clarification + validation des choix (réplication gossip, Signal+libsodium, identité onion, Kotlin, Go, MVP chat 1:1). Scaffold complet : docs (6 ADR), specs filaires, nœud Go compilé+testé (journal signé, onion v3, gossip NaCl, API), squelette Android Compose, scripts. **Dépôt GitHub `nyx-messenger` créé + push initial. Sauvegarde Drive complète (RESTART.md + README.md + archive tar.gz).** |
