# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-12** (session 5).
>
> **RÈGLES PERMANENTES SESSION 5+** (audit systématique, APK autonomes, synchro
> Drive+GitHub, livrables) : voir **§9** — elles s'appliquent à TOUTE session ultérieure.

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

## 3. État actuel (session 5 — 2026-10-12)

### Fait ✅ (session 5)
- **Règles permanentes §9 instaurées à la demande utilisateur** : audit systématique
  pré-compilation, APK 100 % autonomes (installation 1 clic), synchro Drive+GitHub
  systématique, livrables dans /livrables du Drive.
- **Pipeline de build complet** (`scripts/build-apk.sh`) : audit strict bloquant →
  build release signé → 13 contrôles d'autonomie APK → livraison `livrables/`.
- **Audit pré-compilation** (`scripts/audit.py` + `audit.sh`) : 6 catégories de
  contrôles (AUT/COH/SEC/DEAD/RED/OPT), allowlist documentée (`audit-allowlist.txt`),
  rapport Markdown horodaté dans `reports/`.
- **Premier cycle audit→corrections appliqué** (code jamais audité) :
  1. **Redondance supprimée** : `NyxRepository` construit à l'identique dans 3 écrans
     → singleton `ServiceLocator.repository` ;
  2. **Code mort supprimé** : `TorManager.socksProxy()`, objet `net/Replicas.kt`
     (jamais référencé), val `rng` (NyxRepository), bloc repo créé-puis-jeté
     de l'onboarding ;
  3. **Sécurité renforcée** : `MessageCipher.verifyEvent` + `NyxRepository.verifySignature`
     branchés (libsodium `cryptoSignVerifyDetached`) — plus de stub ;
  4. **UX** : bouton « Copier » l'adresse onion (strings copy/copied câblés) ;
  5. **Cohérence** : « Continuer »/« Restaurer… » externalisés en strings.xml ;
     string redondante `tor_online` supprimée ;
  6. **Signature release** ajoutée (signingConfigs via `android/keystore.properties`,
     git-ignoré ; keystore réel dans `/home/z/my-project/keystore/`) — l'APK release
     était NON signé avant (donc non installable en 1 clic) ;
  7. **.gitignore durci** : `.credentials/`, `*.jks`, `*.keystore`, `keystore.properties`,
     `reports/`, `livrables/`.
- **APK v0.1.0 livré et vérifié** : `nyx-v0.1.0-*-release.apk` (21 Mo, universel,
  signé, libTor.so ×4 ABI, natives sodium/sqlcipher/jna ×4 ABI, zéro GMS,
  permissions fermées) — SHA-256 dans `livrables/SHA256SUMS-latest.txt`.
- **Environnement reconstruit après reset du workspace** : JDK 21 Temurin
  (`tools/jdk-21.0.12.1+1`), SDK android-35 + build-tools 34.0.0
  (`tools/android-sdk`), wrapper Gradle 8.9 (déjà committé).
- `scripts/gdrive.py` réécrit (stdlib only : resolve/upload/list + refresh OAuth) ;
  `scripts/sync.sh` (commit+push GitHub, upload livrables+docs+archive vers Drive).
- **Credentials Drive/PAT : à récupérer du dossier « accès »** (§6) — la synchro
  est branchée mais en attente des fichiers (`client_secret_*.json`,
  `oauth_token_relais_*.json`, `token_git.txt`).

### Sessions précédentes (rappel)
- Session 4 : env. build installé, premiers builds verts (debug 39,9 Mo / release
  21,2 Mo), tor-binary 0.4.6.5 (Maven Central) remplace tor-android (ADR-007 §5),
  7 corrections de compilation (dont bug crypto clé publique 32 octets), packaging
  4 ABI vérifié.
- Session 3 : Tor embarqué (NyxTorConfig, TorControlClient, NyxTorService,
  TorManager, OnionServiceManager), câblage app, ADR-007.
- Sessions 1-2 : scaffold complet, serveur Go testé, devtool, dev-server.sh, docs.

### Validations
- **Compilé et vérifié (session 5)** : assembleRelease R8 signé (21 Mo), 13 contrôles
  d'autonomie APK verts, audit strict PASSED (0 CRIT, 0 MAJOR, 4 MINOR documentés).
- **Toujours non validé : le runtime device** (bootstrap tor embarqué, publication
  onion, échanges réplica) — impossible ici (pas d'émulateur, sandbox tue les démons).
- Runtime Tor réel (serveurs) : à faire sur machine utilisateur.

### À faire (ordre suggéré) 🔜
1. **Android : premier run device du Tor embarqué** — l'APK signé est dans `livrables/`.
2. **Test 3 réplicas sur machine avec Tor** — GUIDE_DEMARRAGE.md §2 (option 1).
3. TODO app restants (android/README.md) : persistance compteur de séquence, écran
   d'ajout de réplica, tests instrumentés.
4. Repli censure : ponts obfs4/snowflake via IPtProxy dans le torrc (ADR-007 §3).
5. CI GitHub (workflow Go, puis Android) ; v0.2+ : libsignal, chiffrement du champ
   `to`, purge TTL, push P2P direct, déploiement réplica prod (systemd + torrc durci).

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

# Android (wrapper Gradle 8.9 committé — SDK 35 + JDK 17+ requis)
cd android && ./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk (build vérifié 2026-10-12)
```

4. **Règles de session** (détail complet en **§9**) :
   - toute évolution du format filaire → `protocol/specs/` D'ABORD, puis serveur, puis client ;
   - **AUCUNE recompilation sans audit strict vert d'abord** : `scripts/audit.sh` (§9.1) ;
   - **AUCUNE recompilation sans pipeline** : `scripts/build-apk.sh` (audit → build → vérif autonomie → livrables) ;
   - **AUCUN APK non autonome** : Tor embarqué, signé release, universel, zéro service tiers (§9.2) ;
   - **SYNCHRO SYSTÉMATIQUE en fin de session** : `scripts/sync.sh` (GitHub + Drive, §9.3) ;
   - **LIVRABLES dans `livrables/` (local) et /livrables (Drive)** (§9.4) ;
   - aucun secret dans le dépôt (identités, clés, tokens) — `.gitignore` calibré §9.5 ;
   - terminer la session en mettant à jour ce fichier (§3 État + §8 Journal).

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
| Java | **Session 4 : JDK 21 complet Temurin** (`/home/z/my-project/tools/jdk-21.0.12.1+1`, javac+jlink — le JRE système manque de javac/jlink) ; `org.gradle.java.home` posé dans le `gradle.properties` du GRADLE_USER_HOME du workspace (pas dans le dépôt) |
| SDK Android | **Session 4** : cmdline-tools 12 + platform-tools + build-tools 34.0.0 + platform android-35 — `/home/z/my-project/tools/android-sdk` (référé par `android/local.properties`, git-ignoré) |
| Gradle | **Session 4** : 8.9 — binaire dans `/home/z/my-project/tools/gradle-8.9` + **wrapper committé** dans `android/` (GRADLE_USER_HOME du workspace : daemon off, Xmx2g, Kotlin in-process — contraintes sandbox 4 Go RAM) |
| Tor | **absent** de l'environnement (pas de root) → tests Tor à faire ailleurs |
| Python | 3.12 (module gdrive.py : stdlib only) |
| Git | 2.47.3 |

## 8. Journal des sessions (append-only)

| Date | Session | Résumé |
|---|---|---|
| 2026-10-12 | 1 | Clarification + validation des choix (réplication gossip, Signal+libsodium, identité onion, Kotlin, Go, MVP chat 1:1). Scaffold complet : docs (6 ADR), specs filaires, nœud Go compilé+testé (journal signé, onion v3, gossip NaCl, API), squelette Android Compose, scripts. **Dépôt GitHub `nyx-messenger` créé + push initial. Sauvegarde Drive complète (RESTART.md + README.md + archive tar.gz).** |
| 2026-10-12 | 2 | **Demande utilisateur : « comment lancer tout ça, options 1 et 2 »** → rédaction de GUIDE_DEMARRAGE.md (option 1 serveurs / option 2 Android + dépannage) ; scripts/dev-server.sh v2 (cookie Tor explicite — bug de premier lancement corrigé, câblage auto des pairs, stop, idempotent) ; ajout server/cmd/nyx-devtool (keygen/show/event/verify, smoke testé). Mécanique validée avec Tor factice (réplication Tor réel à faire sur machine de l'utilisateur). Push GitHub + sauvegarde Drive. |
| 2026-10-12 | 3 | **Demande utilisateur : « que l'appli installe son propre service Tor, autonomie totale »** → ADR-007 : Tor embarqué via tor-android (binaire `libtor.so` exécuté par notre `NyxTorService` au 1er plan, torrc généré, ports dynamiques) ; nouveau code : NyxTorConfig, TorControlClient (miroir Go), NyxTorService, TorManager réécrit (bootstrap %, publication auto ADD_ONION de l'identité), OnionServiceManager réel ; câblage MainActivity/Onboarding/bannière Tor/manifest/strings ; Gradle (Guardian maven, useLegacyPackaging). Orbot supprimé comme prérequis (repli censure documenté). Code non compilé ici (pas de SDK) — premier build device = TODO 1. Push GitHub + Drive v0.3. |
| 2026-10-12 | 4 | **Demande utilisateur : « installe tout ce qu'il faut pour compiler toi-même »** → environnement Android complet installé (JDK 21 Temurin, SDK 35, build-tools 34, Gradle 8.9, wrapper committé) ; **premiers builds verts : assembleDebug 39,9 Mo + assembleRelease (R8) 21,2 Mo** ; tor-android (maven Guardian injoignable) remplacé par **tor-binary 0.4.6.5 (Maven Central)** — même architecture, binaire `libTor.so` (ADR-007 §5) ; 7 corrections de code révélées par la compilation (package lazysodium v5, MessageCipher sur API Native byte[] avec sealOpen 5 args + ordre conversions, **bug crypto : pk 32 octets pas 64**, StateFlow kotlinx, @JvmField clash setter, jna @aar + exclusion, sqlcipher 4.5.4, R8 dontwarn awt) ; packaging 4 ABI vérifié dans l'APK. Reste : runtime sur device (TODO 1). Push GitHub + Drive v0.4. |
| 2026-10-12 | 5 | **Demande utilisateur : « audit systématique avant chaque recompilation ; APK entièrement autonomes (1 clic) ; synchro Drive+GitHub systématique ; livrables dans /livrables du Drive ; ajouter tout ça au fichier d'instructions »** → §9 instauré ; scripts/audit.py (6 catégories, strict, rapport MD) + build-apk.sh (audit bloquant → build release signé → 13 contrôles autonomie → livrables/) + sync.sh (GitHub+Drive) + gdrive.py réécrit ; premier cycle audit→corrections (singleton repository, mort : socksProxy/Replicas/rng/bloc onboarding, verifySignature branchée, bouton Copier adresse, strings, .gitignore durci) ; signature release ajoutée (keystore hors dépôt) — l'APK release n'était pas signé ; APK v0.1.0 21 Mo vérifié et livré. Environnement reconstruit (reset workspace). Synchro en attente des credentials du dossier « accès ». |

## 9. Règles permanentes — audit, APK autonomes, synchro, livrables (session 5+)

> Instaurées à la demande utilisateur (session 5). **Elles ne peuvent pas être
> contournées sans justification explicite consignée dans le journal (§8).**

### 9.1 Audit systématique AVANT chaque recompilation

- **Outil** : `scripts/audit.py` (+ wrapper `scripts/audit.sh`) — versionné dans le dépôt.
- **Appel obligatoire** : `python3 scripts/audit.py --root . --strict` — en mode strict,
  MAJOR bloque autant que CRIT. C'est le premier pas de TOUT build.
- **Catégories contrôlées** :
  | Code | Catégorie | Bloquant |
  |---|---|---|
  | AUT-* | Autonomie APK (dépendances interdites, Tor embarqué, permissions fermées, cleartext, allowBackup, R8, signature) | CRIT |
  | COH-* | Cohérence (manifest↔classes, gradle↔catalogue, strings, packages, thème) | CRIT |
  | SEC-* | Secrets (clés privées, PAT, JWT dans le dépôt ; couverture .gitignore) | CRIT / MAJOR |
  | DEAD-* | Code mort (fonctions Kotlin/Go jamais référencées, strings inutilisées) | MAJOR |
  | RED-* | Redondances (dépendances dupliquées, blocs dupliqués ≥ 6 lignes) | MAJOR |
  | OPT-* | Inventaire (TODO/FIXME du code, règles R8 crypto) | MINOR |
- **Faux positifs** : `scripts/audit-allowlist.txt` — une entrée = une justification
  en commentaire. Rapports horodatés dans `reports/` (git-ignoré).
- L'audit ne remplace PAS le compilateur : une erreur de compilation découverte après
  un audit vert est corrigée immédiatement, puis le cycle audit→build est relancé.

### 9.2 APK entièrement autonomes (installation en 1 clic)

Tout APK produit doit satisfaire, **vérifié à chaque build par `scripts/build-apk.sh`** :
1. **Tor embarqué** — `libTor.so` présent pour les 4 ABI (arm64-v8a, armeabi-v7a,
   x86, x86_64) ; jamais de dépendance à Orbot ni à un service externe (ADR-007) ;
2. **Signature release valide** — `apksigner verify` OK (installation directe) ;
   keystore hors dépôt (`/home/z/my-project/keystore/nyx-release.jks`, référencé par
   `android/keystore.properties` git-ignoré) — **sauvegarder le keystore + ses
   mots de passe dans le dossier Drive « accès »** : sans lui, aucune mise à jour
   signée de façon identique n'est possible ;
3. **APK universel unique** — pas de splits : un seul fichier à installer ;
4. **Natives critiques présentes** — libsodium.so, libsqlcipher.so, libjnidispatch.so
   par ABI ;
5. **Zéro service tiers** — aucun GMS/Firebase/analytique dans les dépendances ni
   dans les dex ; permissions limitées à la liste fermée (INTERNET,
   ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+DATA_SYNC), POST_NOTIFICATIONS) +
   permissions auto-définies dans notre namespace ;
6. **R8 + shrinkResources activés** (release optimisé) ;
7. **allowBackup=false** — les clés d'identité ne quittent jamais l'appareil.

Le pipeline `scripts/build-apk.sh` enchaîne : **audit strict → assembleRelease signé →
13 contrôles d'autonomie → livraison**. Un échec de contrôle = pas de livraison.

### 9.3 Synchro systématique Drive + GitHub

- **Outil** : `scripts/sync.sh ["message de commit"]` — en fin de tâche, à minima en
  fin de session, et systématiquement après chaque production de livrable.
- **GitHub** : commit (message explicite) + push sur
  `github.com/alexisfrlopez-debug/nyx-messenger` — PAT dans `.credentials/token_git.txt`.
- **Drive** : `scripts/gdrive.py` (stdlib only) — credentials dans
  `/home/z/my-project/.credentials/` (`client_secret_*.json` + `oauth_token_relais_*.json`,
  refresh ~7 j → ré-autorisation utilisateur si expiré). Uploads :
  `RESTART.md` + `README.md` + `GUIDE_DEMARRAGE.md` (racine du dossier « travail »),
  archive source tar.gz de l'état de session, et tous les livrables.
- Si les credentials manquent : **SKIP explicite** (jamais d'échec silencieux) et
  consigner ce qui reste en attente de synchro.
- À la reprise d'un workspace vierge : tout restaurer depuis le dépôt GitHub (public)
  + le dossier Drive « travail » (docs + archive) + le dossier « accès » (credentials).

### 9.4 Livrables dans /livrables

- Local : `livrables/` à la racine du dépôt (git-ignoré) — dépôt de sortie du pipeline.
- Drive : dossier **`/livrables`** à la racine du dossier « travail » (créé au besoin
  par `sync.sh`). Chaque APK y est accompagné de son rapport d'audit (`audit-report.txt`)
  et des empreintes (`SHA256SUMS-latest.txt`).
- Nommage : `nyx-v<versionName>-<AAAAMMJJ-HHMMSS>-release.apk`.

### 9.5 Hygiène permanente (rappel durci)

- Secrets HORS dépôt : `.credentials/` (workspace), `android/keystore.properties`,
  `*/local.properties`, tout `*.jks`/`*.keystore` — le `.gitignore` les couvre et
  l'audit (SEC-*) échoue sinon.
- Le keystore release ET ses mots de passe doivent exister à deux endroits : workspace
  courant + dossier Drive « accès ». En cas de perte : nouvelle clé = nouvelle
  signature = désinstallation obligatoire pour les utilisateurs.
