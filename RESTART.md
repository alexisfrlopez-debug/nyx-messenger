# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-12** (session 4).

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

## 3. État actuel (session 4 — 2026-10-12)

### Fait ✅
- **Environnement de build Android installé et premier build réussi** (demande
  utilisateur : « installe tout ce qu'il faut pour compiler toi-même ») :
  - JDK 21 complet (Temurin, javac+jlink) et SDK Android (cmdline-tools,
    platform-tools, build-tools 34.0.0, platform 35) installés hors dépôt
    (`/home/z/my-project/tools/`, référencés par `local.properties` git-ignoré) ;
  - **Gradle 8.9 + wrapper committé** dans `android/` (gradlew, wrapper jar) —
    plus besoin d'installation Gradle sur une machine neuve ;
  - `assembleDebug` **VERT** (39,9 Mo) et `assembleRelease` **VERT** (R8, 21,2 Mo).
- **Changement de source du binaire tor (ADR-007 §5)** : `tor-android` (maven
  Guardian, injoignable depuis certains réseaux/CI, ni release GitHub ni Central)
  → **`io.matthewnelson.topl-android:tor-binary:0.4.6.5`** (Maven Central, exécutable
  tor PIE vérifié par ABI, nom **`libTor.so`**). `NyxTorService.resolveTorBinary()`
  cherche `libTor.so` puis `libtor.so` — retour à tor-android possible sans réécriture.
  Architecture inchangée : toujours notre torrc + exécution directe + control port.
- **7 corrections de code révélées par le premier build** (code jamais compilé) :
  1. lazysodium : package `com.goterl.lazycode.lazysodium` → `com.goterl.lazysodium`
     (v5) ; constructeurs `LazySodiumAndroid(SodiumAndroid())` ;
  2. `MessageCipher` réécrit sur les interfaces **Native** (byte[] bruts) : les
     variantes « Lazy » passent par des String et corrompraient les corps binaires ;
     `cryptoBoxSealOpen` prend (m, c, len, **pk, sk**) ; conversions
     `convertPublicKey/SecretKeyEd25519ToCurve25519(curve, ed)` — ordre documenté ;
  3. **Bug crypto réel** : allocation de la clé publique avec `Sign.BYTES` (64) au lieu
     de 32 → `Sign.ED25519_PUBLICKEYBYTES`/`ED25519_SECRETKEYBYTES` (sinon adresse
     onion fausse) ;
  4. `ChatScreen` : type `kotlinx.coroutines.flow.StateFlow` (pas dans compose.runtime),
     factory ViewModel sans DSL `initializer` ;
  5. `NyxRepository` : `@JvmField var replicas` (clash JVM du setter avec `setReplicas`) ;
  6. JNA en artefact **`@aar`** + exclusion du jar desktop transitif (dupliqués de
     classes) ; R8 : `-dontwarn java.awt.*` (branches desktop de JNA) ;
  7. sqlcipher 4.5.6 → **4.5.4** (dernière version publiée de l'artefact).
- Vérification du contenu APK : `libTor.so` (6,6 Mo), `libsodium.so`,
  `libsqlcipher.so`, `libjnidispatch.so` présents par ABI (arm64-v8a, armeabi-v7a,
  x86, x86_64) ; `package net.nyx.messenger`, minSdk 26, targetSdk 35.
- Docs à jour : ADR-007 (§5 amendement + chiffres réels), android/README.md (état
  « Compilé ✓ » + points de vigilance), GUIDE_DEMARRAGE.md (carte sans Orbot, build
  vérifié, dépannage tor-binary), worklog.
- Session 3 (rappel) : ADR-007 + code Tor embarqué complet (NyxTorConfig,
  TorControlClient, NyxTorService, TorManager, OnionServiceManager), câblage app
  (MainActivity, bannière, manifest), guide de lancement options 1/2, dev-server.sh v2,
  devtool, serveur Go testé.
- Git : dépôt `https://github.com/alexisfrlopez-debug/nyx-messenger` — à jour session 4.

### Validations
- Session 2 : go vet + go test verts ; devtool smoke testé ; dev-server.sh validé avec
  Tor factice. Réplication Tor réel : **toujours à faire sur machine utilisateur**.
- Session 3 : code Android écrit, non compilé (pas de SDK).
- Session 4 : **compilation Android complète verte (debug + release R8)**, packaging
  4 ABI vérifié dans l'APK. **Reste non validé : le runtime** (bootstrap tor, socket
  SOCKS local, publication ADD_ONION, échanges avec réplica) — impossible ici
  (pas d'émulateur ; le sandbox tue les démons longs).

### À faire (ordre suggéré) 🔜
1. **Android : premier run device du Tor embarqué** — l'APK est déjà compilé (ou
   recompile-le en 1 cmd grâce au wrapper). Suivre GUIDE_DEMARRAGE.md §3 : install,
   bootstrap (bannière %), publication onion (logcat `nyx-tor`), connexions au réplica
   de dev. Ajuster si besoin (extraction du binaire, notification, ports).
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

# Android (wrapper Gradle 8.9 committé — SDK 35 + JDK 17+ requis)
cd android && ./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk (build vérifié 2026-10-12)
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
