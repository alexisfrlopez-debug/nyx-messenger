# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-12** (session 3).

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
| 4 | Client | Kotlin natif + Jetpack Compose ; **Tor embarqué dans l'app (tor-android) depuis la v0.2 — Orbot n'est plus requis** (repli censure documenté) | ADR-004, **ADR-007** |
| 5 | Serveur | Go ≥ 1.22, binaire statique, stdlib + x/crypto | ADR-005 |
| 6 | Phases 2/3 | Market : quorum `tx` dédié, escrow 2-of-3, réputation escrow ; fiat = réglementaire d'abord | ADR-006 |

Dossier des ADR : `docs/adr/`. Toute évolution d'architecture = nouvel ADR.

## 3. État actuel (session 3 — 2026-10-12)

### Fait ✅
- **ADR-007 : Tor embarqué dans l'app** — demande utilisateur « que l'appli installe
  son propre service Tor pour qu'elle soit entièrement autonome ». Choix :
  binaire `libtor.so` de la bibliothèque **tor-android** (Guardian Project) exécuté par
  NOTRE service au premier plan avec un torrc généré (ports sondés dynamiquement,
  cookie, `__OwningControllerProcess`) — plutôt que le TorService de la lib (contrôle
  total, symétrie parfaite avec le serveur Go). Orbot n'est plus requis (repli
  censure/ponts documenté, hors périmètre immédiat).
- Code Android Tor embarqué complet :
  `tor/NyxTorConfig.kt` (torrc, ports libres 9050→+40, répertoires privés),
  `tor/TorControlClient.kt` (control port minimal : AUTHENTICATE cookie, GETINFO
  status/bootstrap-phase, ADD_ONION ED25519-V3, DEL_ONION — miroir du Go),
  `tor/NyxTorService.kt` (service 1er plan dataSync, exec du binaire, drain des logs,
  notification persistante), `tor/TorManager.kt` réécrit (états Checking/Starting %/
  Ready/Failed, publication auto de l'onion dès le bootstrap, vérification clé↔adresse
  miroir du serveur, port SOCKS dynamique exposé via `TorManager.activeSocksPort`),
  `tor/OnionServiceManager.kt` réel (façade publish/unpublish).
- Câblage app : `MainActivity` (permission POST_NOTIFICATIONS 13+, démarrage Tor si
  identité), `OnboardingScreen` (démarrage Tor après création d'identité),
  `ConversationListScreen` (bannière d'état Tor avec progression %),
  `NyxRepository` (SOCKS port dynamique via `TorManager.activeSocksPort`),
  `AndroidManifest` (FOREGROUND_SERVICE_DATA_SYNC + service déclaré),
  strings FR (tor_starting/ready/failed).
- Gradle : dépôt Maven Guardian Project ajouté (`settings.gradle.kts`), dépendance
  `org.torproject.jni:tor-android:0.4.7.14` (`libs.versions.toml`, version à confirmer
  au premier build — liste des versions sur maven.guardianproject.net),
  `packaging { jniLibs { useLegacyPackaging = true } }` (obligatoire : le binaire tor
  doit être extrait sur disque pour être exécutable — W^X Android 10+).
- Guide de lancement (session 2) : `GUIDE_DEMARRAGE.md` — **option 1** (serveurs :
  Tor + 3 réplicas + test de réplication) et **option 2** (Android, désormais Tor
  embarqué sans Orbot), avec dépannage.
- scripts/dev-server.sh v2 (session 2) : cookie Tor explicite, câblage automatique des
  pairs, relance idempotente, sous-commande stop, gossip 15 s en dev.
- server/cmd/nyx-devtool (session 2) : keygen/show/event/verify — client minimal de test.
- Architecture : docs 00..05 + ADR-001..**007** ; specs filaires protocol/specs/.
- Nœud serveur Go compilé et testé (journal signé, onion v3, gossip NaCl, API) — tests verts.
- Git : dépôt `https://github.com/alexisfrlopez-debug/nyx-messenger` — à jour session 3.

### Validations
- Session 2 : go vet + go test verts ; devtool smoke testé (signature OK, falsification rejetée) ;
  dev-server.sh validé avec Tor factice (câblage pairs exact, identités stables). La
  réplication avec Tor réel reste à faire sur machine utilisateur (le sandbox tue les démons).
- Session 3 : code Android non compilé ici (pas de SDK) — **premier build + run device =
  prochaine étape critique** (voir TODO 1).

### À faire (ordre suggéré) 🔜
1. **Android : premier build + run device du Tor embarqué** — suivre GUIDE_DEMARRAGE.md
   §3 : build, install, vérifier bootstrap (bannière %), publication onion (logcat
   `nyx-tor`), connexions au réplica de dev. Ajuster si besoin (version tor-android,
   extraction du binaire, notification).
2. **Test 3 réplicas sur machine avec Tor** — GUIDE_DEMARRAGE.md §2 (option 1).
3. TODO app restants (android/README.md) : verifySignature (lazysodium), écran d'ajout
   de réplica, compteur de séquence persisté, tests instrumentés.
4. Repli censure : ponts obfs4/snowflake via IPtProxy injectés dans le torrc (ADR-007 §3).
5. CI GitHub (workflow Go, plus tard Android) ; v0.2+ : libsignal, chiffrement du champ
   `to`, purge TTL, push P2P direct (l'onion de l'utilisateur est déjà publié), déploiement
   réplica prod (systemd + torrc durci).

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
| 2026-10-12 | 3 | **Demande utilisateur : « que l'appli installe son propre service Tor, autonomie totale »** → ADR-007 : Tor embarqué via tor-android (binaire `libtor.so` exécuté par notre `NyxTorService` au 1er plan, torrc généré, ports dynamiques) ; nouveau code : NyxTorConfig, TorControlClient (miroir Go), NyxTorService, TorManager réécrit (bootstrap %, publication auto ADD_ONION de l'identité), OnionServiceManager réel ; câblage MainActivity/Onboarding/bannière Tor/manifest/strings ; Gradle (Guardian maven, useLegacyPackaging). Orbot supprimé comme prérequis (repli censure documenté). Code non compilé ici (pas de SDK) — premier build device = TODO 1. Push GitHub + Drive v0.3. |
