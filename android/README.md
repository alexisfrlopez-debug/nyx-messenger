# Nyx Android

Application Android de Nyx — Kotlin 2.0, Jetpack Compose (Material 3), Room + SQLCipher,
libsodium (lazysodium), OkHttp forcé via SOCKS5, **Tor embarqué** (ADR-007 : démon tor
inclus dans l'app, plus aucune dépendance à Orbot).

## Construire

```bash
# Prérequis : Android Studio (SDK 35) ou SDK command-line + JDK 17+
cd android
./gradlew :app:assembleDebug          # wrapper Gradle 8.9 committé (télécharge au 1er run)
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Build vérifié (2026-10-12)** : `assembleDebug` (39,9 Mo) et `assembleRelease`
> (R8, 21,2 Mo) passent. Toutes les dépendances viennent de Maven Central —
> aucun dépôt tiers. Points de vigilance hérités du premier build :
> - tor embarqué = `io.matthewnelson.topl-android:tor-binary:0.4.6.5` (ADR-007 §5) —
>   le binaire s'appelle **`libTor.so`** (T majuscule) dans `nativeLibraryDir` ;
> - `jna` doit rester en artefact **`@aar`** avec l'exclusion du jar transitif
>   (`app/build.gradle.kts`) ;
> - sqlcipher = 4.5.4 (dernière version de l'artefact `net.zetetic:android-database-sqlcipher`).

## Tor embarqué (ADR-007)

| Élément | Rôle |
|---|---|
| `tor/NyxTorConfig` | répertoires privés (`filesDir/tor/`), choix des ports libres, génération du torrc |
| `tor/NyxTorService` | service au premier plan : exécute `libTor.so -f torrc`, notification persistante |
| `tor/TorControlClient` | control port minimal (AUTHENTICATE cookie, GETINFO, ADD_ONION, DEL_ONION) — miroir du serveur Go |
| `tor/TorManager` | orchestration + état (`Starting %` → `Ready` / `Failed`) + publication auto de l'onion |
| `tor/OnionServiceManager` | façade `publish/unpublish` — la clé vient du Keystore, jamais écrite sur disque |

Flux au lancement : identité existe → `TorManager.start { clé }` → `NyxTorService` →
process tor → bootstrap (progression dans la bannière de l'écran Conversations) →
`ADD_ONION` de l'identité → **l'adresse onion de l'utilisateur est réellement publiée**.

## État v0.2 (Tor embarqué)

| Composant | État |
|---|---|
| `crypto/` — KeystoreBox, IdentityManager, MessageCipher, OnionCodec | **Compilé ✓** (API lazysodium 5.x corrigée, conversions `convert*Ed25519ToCurve25519`) — à tester sur device |
| `tor/NyxTorConfig` + `NyxTorService` + `TorControlClient` | **Compilé ✓** (exécution directe de `libTor.so`, résolution tolérante du nom de binaire) — **premier run device à valider** |
| `tor/TorManager` (bootstrap + publication onion) | **Compilé ✓** — à valider sur device |
| `net/RelayClient` — API client via SOCKS5 dynamique | **Compilé ✓** |
| `data/db` — Room + SQLCipher (4.5.4) | **Compilé ✓** |
| `repo/NyxRepository` — envoi/réception/signature | **Compilé ✓**, `verifySignature` branché (libsodium `cryptoSignVerifyDetached`) — à tester sur device |
| `ui/` — onboarding (démarrage Tor), bannière Tor, liste, chat | **Compilé ✓** |
| **Build APK** — debug 39,9 Mo / release R8 21,2 Mo | **VERT ✓ (2026-10-12)** — packaging 4 ABI vérifié |

## TODO prioritaires (prochaine session)

1. **Premier run device + validation Tor embarqué** : installer l'APK déjà compilé,
   bootstrap complet, bannière % progressive, publication onion vérifiée
   (logcat `nyx-tor`), test d'un POST sur un réplica de dev (`scripts/dev-server.sh`
   côté PC). Le build est déjà vert — plus rien à compiler, tout est au runtime.
2. ~~Brancher `crypto_sign_verify_detached`~~ **Fait (session 5)** : `MessageCipher.verifyEvent` + `NyxRepository.verifySignature`.
3. Persister le compteur de séquence par auteur (fichier scellé).
4. Écran d'ajout de réplica (adresse onion) + stockage — v0.1 : ajout via `pendingAdd`/lien `nyx://add` à compléter.
5. Repli « censure » : ponts (obfs4/snowflake via IPtProxy) injectés dans le torrc —
   la variante Orbot reste documentée dans ADR-007 §3.
6. v0.3 : libsignal (Signal Protocol), push P2P direct (l'onion de l'utilisateur est
   déjà publié — port cible `NyxTorConfig.onionLocalPort`).

## Build & livraison (règles permanentes — RESTART.md §9)

```bash
scripts/audit.sh            # audit pré-compilation STRICT (bloquant) — toujours AVANT le build
scripts/build-apk.sh        # pipeline : audit → build release signé → vérif autonomie → livrables/
scripts/sync.sh             # synchro systématique : GitHub push + Drive (/livrables + docs + archive)
```

- Tout APK produit est **autonome** : Tor embarqué (libTor.so ×4 ABI), signature release
  (keystore local, `android/keystore.properties` git-ignoré), APK universel unique,
  zéro service tiers — vérifié par le pipeline (13 contrôles).
- Les livrables atterrissent dans `livrables/` (local) puis `/livrables` du Drive via `sync.sh`.

## Conventions

- `kotlinx.serialization` pour tout le filaire (types `WireEvent` etc. miroir du Go).
- Aucune string de clé en clair dans les logs ; `error("…")` pour les invariants cassés.
- Toute évolution du format filaire commence dans `protocol/specs/` (dépôt racine).
- Décisions d'architecture → nouvel ADR dans `docs/adr/` (dernier : ADR-007, Tor embarqué).
