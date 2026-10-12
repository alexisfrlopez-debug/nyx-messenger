# Nyx Android

Application Android de Nyx — Kotlin 2.0, Jetpack Compose (Material 3), Room + SQLCipher,
libsodium (lazysodium), OkHttp forcé via SOCKS5, **Tor embarqué** (ADR-007 : démon tor
inclus dans l'app, plus aucune dépendance à Orbot).

## Construire

```bash
# Prérequis : Android Studio (SDK 35) ou SDK command-line + JDK 17+
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Tor embarqué** : la dépendance `org.torproject.jni:tor-android` vient du dépôt
> Maven du Guardian Project (`settings.gradle.kts`). Si la résolution échoue, vérifier
> les versions disponibles sur
> <https://maven.guardianproject.net/maven2/org/torproject/jni/tor-android/> et ajuster
> `torAndroid` dans `gradle/libs.versions.toml`. Au premier build sur device, contrôler
> que `libtor.so` est bien extrait dans `nativeLibraryDir` (exigé par
> `packaging { jniLibs { useLegacyPackaging = true } }`).

## Tor embarqué (ADR-007)

| Élément | Rôle |
|---|---|
| `tor/NyxTorConfig` | répertoires privés (`filesDir/tor/`), choix des ports libres, génération du torrc |
| `tor/NyxTorService` | service au premier plan : exécute `libtor.so -f torrc`, notification persistante |
| `tor/TorControlClient` | control port minimal (AUTHENTICATE cookie, GETINFO, ADD_ONION, DEL_ONION) — miroir du serveur Go |
| `tor/TorManager` | orchestration + état (`Starting %` → `Ready` / `Failed`) + publication auto de l'onion |
| `tor/OnionServiceManager` | façade `publish/unpublish` — la clé vient du Keystore, jamais écrite sur disque |

Flux au lancement : identité existe → `TorManager.start { clé }` → `NyxTorService` →
process tor → bootstrap (progression dans la bannière de l'écran Conversations) →
`ADD_ONION` de l'identité → **l'adresse onion de l'utilisateur est réellement publiée**.

## État v0.2 (Tor embarqué)

| Composant | État |
|---|---|
| `crypto/` — KeystoreBox, IdentityManager, MessageCipher, OnionCodec | Écrit, à tester sur device |
| `tor/NyxTorConfig` + `NyxTorService` + `TorControlClient` | Écrit (exécution directe du binaire) — **premier run device à valider** |
| `tor/TorManager` (bootstrap + publication onion) | Écrit — à valider sur device |
| `net/RelayClient` — API client via SOCKS5 dynamique | Écrit |
| `data/db` — Room + SQLCipher | Écrit |
| `repo/NyxRepository` — envoi/réception/signature | Écrit, `verifySignature` à brancher (TODO) |
| `ui/` — onboarding (démarrage Tor), bannière Tor, liste, chat | Écrit |

## TODO prioritaires (prochaine session)

1. **Premier build device + validation Tor embarqué** : bootstrap complet, bannière %
   progressive, publication onion vérifiée (logcat `nyx-tor`), test d'un POST sur un
   réplica de dev (`scripts/dev-server.sh` côté PC).
2. Brancher `crypto_sign_verify_detached` dans `NyxRepository.verifySignature`.
3. Persister le compteur de séquence par auteur (fichier scellé).
4. Écran d'ajout de réplica (adresse onion) + stockage ; alors `Replicas.DEFAULT`
   n'est plus nécessaire.
5. Repli « censure » : ponts (obfs4/snowflake via IPtProxy) injectés dans le torrc —
   la variante Orbot reste documentée dans ADR-007 §3.
6. v0.3 : libsignal (Signal Protocol), push P2P direct (l'onion de l'utilisateur est
   déjà publié — port cible `NyxTorConfig.onionLocalPort`).

## Conventions

- `kotlinx.serialization` pour tout le filaire (types `WireEvent` etc. miroir du Go).
- Aucune string de clé en clair dans les logs ; `error("…")` pour les invariants cassés.
- Toute évolution du format filaire commence dans `protocol/specs/` (dépôt racine).
- Décisions d'architecture → nouvel ADR dans `docs/adr/` (dernier : ADR-007, Tor embarqué).
