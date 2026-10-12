# Environnement de développement

## 1. Prérequis

| Outil | Version | Usage | Installation |
|---|---|---|---|
| Go | ≥ 1.22 | Nœud serveur | `scripts/bootstrap.sh` (tarball utilisateur si pas de root) |
| Tor (démon) | ≥ 0.4.7 | control port + SOCKS | `apt install tor` (ou brew/Windows Expert Bundle) |
| Android Studio | ≥ Koala | App | android.com/studio (SDK 35, build-tools 35) |
| JDK | 21 | Gradle/Android | inclus avec Android Studio |
| git | ≥ 2.40 | versioning | — |

Le script `scripts/bootstrap.sh` automatise tout ce qui est automatisable (Go utilisateur,
torrc de développement, vérification Android SDK) et fonctionne sans droits root.

## 2. Compiler et lancer un nœud serveur

```bash
cd server
go build -o nyx-server ./cmd/nyx-server
# Tor doit tourner avec control port 9051 (voir torrc dev dans scripts/)
./nyx-server --data-dir ./data1 --http-port 8080
# → « onion=abcd….onion  api=127.0.0.1:8080 »
```

Configuration optionnelle : `server/config.json` (copier depuis `config.example.json`)
pour déclarer les pairs :

```json
{ "peers": [ { "onion": "xyz….onion", "gossip_pub": "base64(clé X25519)" } ] }
```

## 3. Simuler 3 réplicas et voir la réplication

```bash
scripts/dev-server.sh   # démarre 3 nœuds configurés comme pairs l'un de l'autre
# Puis injecter un événement de test :
curl --socks5-hostname 127.0.0.1:9050 -X POST http://<onion-A>/client/event -d @ev.json
# ou en local : curl -X POST http://127.0.0.1:8080/client/event -d @ev.json
# Vérifier la propagation : GET /client/events sur le nœud B et C
```

## 4. Construire l'application Android

```bash
cd android
# local.properties : sdk.dir=/chemin/vers/Android/Sdk (auto-généré par Android Studio)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Important : le debug build exige qu'**Orbot** soit installé et démarré (le client échoue
volontairement sans Tor). Pour tester en boucle fermée : lancer `scripts/dev-server.sh`
sur une machine joignable et ajouter son onion dans les réplicas par défaut de l'app
(`net/Replicas.kt`).

## 5. Où mettre quoi

- Code serveur : `server/` — tout Go, aucun framework HTTP externe (bibliothèque
  standard + `golang.org/x/crypto`).
- Code app : `android/app/src/main/java/net/nyx/messenger/`.
- Spécs protocole : `protocol/specs/` — **toute évolution du format filaire ou de l'API
  doit d'abord modifier la spéc**, puis le code serveur, puis le client.
- Décisions : `docs/adr/` — une décision qui change l'architecture = un nouvel ADR.

## 6. Pièges connus

- Le control port Tor doit être activé (`ControlPort 9051` + `CookieAuthentication 1`
  **ou** `HashedControlPassword`) — sinon le nœud ne peut pas publier son onion.
- Sur certaines distributions, `tor` tourne en service système avec une config restreinte
  : utiliser le torrc fourni dans `scripts/` pour le dev.
- Gradle : si `libs.versions.toml` change, exécuter `./gradlew build --refresh-dependencies`.
- Lazysodium Android nécessite JNA @aar (déjà déclaré dans `libs.versions.toml`).
