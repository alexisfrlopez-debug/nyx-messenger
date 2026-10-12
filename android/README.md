# Nyx Android

Application Android de Nyx — Kotlin 2.0, Jetpack Compose (Material 3), Room + SQLCipher,
libsodium (lazysodium), OkHttp forcé via SOCKS5 Tor.

## Construire

```bash
# Prérequis : Android Studio (SDK 35) ou SDK command-line + JDK 17+
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Installer **Orbot** et le démarrer avant l'app : sans Tor, les appels réseau échouent
délibérément (offline by design).

## État v0.1 (squelette)

| Composant | État |
|---|---|
| `crypto/` — KeystoreBox, IdentityManager (ed25519 + onion), MessageCipher (sealed box), OnionCodec | Écrit, à tester sur device |
| `tor/TorManager` — Orbot SOCKS 9050 | Écrit (détection basique) |
| `tor/OnionServiceManager` — interface + stub Orbot | Interface prête, implémentation à faire sur device |
| `net/RelayClient` — API client via SOCKS5 | Écrit |
| `data/db` — Room + SQLCipher | Écrit |
| `repo/NyxRepository` — envoi/réception/signature | Écrit, `verifySignature` à brancher (TODO) |
| `ui/` — onboarding, liste, chat | Écrit |

## TODO prioritaires (prochaine session)

1. Brancher `crypto_sign_verify_detached` dans `NyxRepository.verifySignature`.
2. Implémenter `OrbotOnionServiceManager.publish` (API onion v3 d'Orbot).
3. Persister le compteur de séquence par auteur (fichier scellé).
4. Écran d'ajout de réplica (adresse onion + clé gossip) + stockage.
5. Tests instrumentés crypto (round-trip sealed box, OnionCodec ↔ vecteurs Go).
6. v0.2 : libsignal (Signal Protocol) + IPtProxy embarqué.

## Conventions

- `kotlinx.serialization` pour tout le filaire (types `WireEvent` etc. miroir du Go).
- Aucune string de clé en clair dans les logs ; `error("…")` pour les invariants cassés.
- Toute évolution du format filaire commence dans `protocol/specs/` (dépôt racine).
