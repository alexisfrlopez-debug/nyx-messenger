# Binaires tor embarqués (libTor.so)

Remplacent la dépendance Maven `io.matthewnelson.topl-android:tor-binary:0.4.6.5`
(EOL — « No more HSDir available to query » sur le consensus 2026 : appels directs
mort-terrés, P2P fichiers dégradé vers le relais).

| ABI          | Version tor | Provenance (APK officiel « fullperm », Guardian Project) | SHA-256 |
|--------------|-------------|----------------------------------------------------------|---------|
| arm64-v8a    | 0.4.9.14    | orbot-android release `17.9.7-RC-1-tor-0.4.9.14` (security release) — `lib/arm64-v8a/libtor.so` | `67ce86a6664c330f49037487f0931498e2a0f82099fbfaf3de7a62c32cf5e996` |
| armeabi-v7a  | 0.4.9.13    | orbot-android release `17.9.6-RC-1-tor-0.4.9.13` (universal) — `lib/armeabi-v7a/libtor.so` | `8948b5e5d94f5332c0d5ffa223c06a6614001dc4289cf4d4c63488700445f341` |
| x86          | 0.4.9.13    | idem — `lib/x86/libtor.so` | `7fa2d687753cf5bef361c0b7fd6175a135ec6a0402beb02d03ac048478ffdb06` |
| x86_64       | 0.4.9.13    | idem — `lib/x86_64/libtor.so` | `bfc69415504d53fb21b9e0aa8e68a37afd628e608e53f8348a267642216203f3` |

Caractéristiques vérifiées au poste (2026-10-17) :

- ELF Android (NDK r30), PT_INTERP `/system/bin/linker[64]` → exécutables
  directement comme daemon tor (README guardianproject/tor-android : « The
  included libtor.so binaries can also be used directly as a tor daemon ») —
  comportement identique à l'ancien libTor.so topl consommé par `NyxTorService`.
- Dépendances dynamiques limitées aux libs système (`libm/log/dl/libc.so`) —
  libevent 2.1.13 / OpenSSL 3.x / zlib / zstd liés STATIQUEMENT.
- minSdk binaire = 24 ≤ minSdk application = 26 ; `useLegacyPackaging` actif
  (jniLibs extraits dans `nativeLibraryDir`, exécutables via `ProcessBuilder`).
- `NyxTorService` charge `libTor.so` (premier candidat de `TOR_BINARY_CANDIDATES`).

Pour mettre à jour : télécharger le nouvel APK « fullperm » de
guardianproject/orbot-android, extraire `lib/<abi>/libtor.so`, renommer
`libTor.so` par ABI, vérifier `file` (ELF Android) et `strings | grep 0\.4\.`,
coller le SHA-256 dans ce tableau. Alternative de choix si gpmaven redevient
joignable : revenir à `org.torproject.jni:tor-android` (Maven Guardian) et
supprimer ces binaires.
