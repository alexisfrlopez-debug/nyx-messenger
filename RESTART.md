# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-12** (session 2).

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

## 3. État actuel (session 2 — 2026-10-12)

### Fait ✅
- **GUIDE_DEMARRAGE.md** (racine) : guide pas à pas complet — **option 1** (serveurs :
  Tor + 3 réplicas + test de réplication) et **option 2** (Android + Orbot + connexion
  au réplica de dev), avec dépannage.
- **scripts/dev-server.sh réécrit (v2)** : détection du Tor correcte (control port 9051,
  pas seulement SOCKS 9050), cookie passé explicitement (`--cookie-file
  scripts/tor_data/control.authcookie` — l'auto-détection Go ne couvre que /run/tor,
  **bug de premier lancement corrigé**), câblage automatique des pairs (lecture des
  identity.json, écriture des config.json, redémarrage), relance idempotente,
  sous-commande `stop`, gossip 15 s en dev, diagnostic enrichi.
- **server/cmd/nyx-devtool** : client minimal de test — `keygen` (identité cliente
  ed25519 → onion), `show`, `event` (événement signé sur stdout, seq auto-incrémentée,
  --to pour les messages privés), `verify` (Validate complète). Smoke testé : signature
  valide acceptée, body falsifié rejeté.
- Architecture complète rédigée : `docs/00-architecture.md`, `01-modele-securite.md`,
  `02-protocole-replication.md`, `03-protocole-messagerie.md`, `04-roadmap.md`,
  `05-setup-dev.md` + 6 ADR (session 1).
- Spécifications filaires : `protocol/specs/wire-format.md`, `protocol/specs/api.md`.
- **Nœud serveur Go compilé et testé** (`server/`) : client control port Tor minimal
  (ADD_ONION ED25519-V3), journal signé ed25519 (seq stricte, pending, JSONL), onion v3
  codec aller-retour, gossip NaCl via SOCKS5, API client+gossip, tests verts.
- **Squelette Android complet** (`android/`) : Gradle version-catalog, Compose,
  Room+SQLCipher, lazysodium, OkHttp SOCKS5, TorManager (Orbot), UI onboarding/liste/chat.
  **Toujours non compilé** (pas de SDK Android dans l'environnement) — TODO dans
  `android/README.md`.
- Scripts : `scripts/bootstrap.sh` (env sans root), `scripts/dev-server.sh` (v2),
  `scripts/torrc-dev`.
- Git : branche `main`, dépôt GitHub `https://github.com/alexisfrlopez-debug/nyx-messenger`
  — à jour session 2 (guide + devtool + scripts v2).

### Validations session 2
- `go vet` + `go test ./...` verts ; build des deux binaires OK.
- devtool : cycle keygen→event→verify vérifié (y compris rejet d'un body falsifié).
- dev-server.sh : mécanique validée avec un Tor factice (TCP) — phases, câblage des
  pairs (config.json contient exactement les N-1 autres, sans soi-même), persistence
  d'identité au redémarrage, health checks, stop. **La réplication gossip avec Tor réel
  reste à tester sur machine disposant de Tor** (l'environnement de la session tue les
  démons longs et n'a pas Tor — voir §7).

### À faire (ordre suggéré) 🔜
1. **Test 3 réplicas sur machine avec Tor** — suivre GUIDE_DEMARRAGE.md §2 (une
   commande + test devtool). C'est l'option 1 du guide.
2. **Android sur device** — GUIDE_DEMARRAGE.md §3 : premier build (ajustements probables),
   Orbot, réplica de dev dans Replicas.kt, puis TODO de android/README.md
   (verifySignature, OnionServiceManager, écran d'ajout de réplica).
3. **CI GitHub** (facultatif) : workflow Go (build + tests) ; plus tard build Android.
4. v0.2 (voir roadmap `docs/04-roadmap.md`) : libsignal, chiffrement du champ `to`,
   IPtProxy, purge TTL serveur, push P2P direct, sauvegarde BIP39, déploiement d'un
   réplica de prod (systemd + torrc durci).

## 4. Structure du projet

```
nyx/
├── RESTART.md              ← ce fichier (racine du répertoire de travail)
├── GUIDE_DEMARRAGE.md      ← COMMENT LANCER (option 1 serveurs / option 2 Android)
├── README.md               ← vue d'ensemble
├── docs/                   ← architecture, sécurité, protocoles, roadmap, setup, ADR/
├── protocol/specs/         ← wire-format.md, api.md  (TOUTE évolution commence ici)
├── server/                 ← nœud Go (cmd/{nyx-server,nyx-devtool}, internal/{tor,journal,gossip,api,identity,config})
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

3. **Commandes de référence** (détail complet : GUIDE_DEMARRAGE.md) :

```bash
# Environnement + build + tests
scripts/bootstrap.sh
cd server && go build -o nyx-server ./cmd/nyx-server && go test ./...

# Tor de dev + 3 réplicas câblés automatiquement + test de réplication
scripts/dev-server.sh 3
go run ./server/cmd/nyx-devtool keygen /tmp/alice.json
go run ./server/cmd/nyx-devtool event --keys /tmp/alice.json --body "salut" > /tmp/ev.json
curl -s -X POST http://127.0.0.1:8081/client/event -d @/tmp/ev.json

# Nœud unique manuel (Tor doit tourner : ControlPort 9051, Cookie 1)
./nyx-server --data-dir ./data --http-port 8080

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
| 2026-10-12 | 2 | **Demande utilisateur : « comment lancer tout ça, options 1 et 2 »** → rédaction de GUIDE_DEMARRAGE.md (option 1 serveurs / option 2 Android + dépannage) ; scripts/dev-server.sh v2 (cookie Tor explicite — bug de premier lancement corrigé, câblage auto des pairs, stop, idempotent) ; ajout server/cmd/nyx-devtool (keygen/show/event/verify, smoke testé). Mécanique validée avec Tor factice (réplication Tor réel à faire sur machine de l'utilisateur). Push GitHub + sauvegarde Drive. |
