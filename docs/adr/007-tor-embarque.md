# ADR-007 — Tor embarqué dans l'application (autonomie totale)

- **Statut** : accepté (2026-10-12)
- **Décideurs** : porteur du projet + agent
- **Amende** : ADR-004 (client Android) — la voie « Orbot v0.1 → IPtProxy v0.2 » est
  remplacée par « Orbot v0.1 → Tor embarqué v0.2 »
- **Contexte utilisateur** : « je veux que l'appli installe son propre service Tor
  pour qu'elle soit entièrement autonome »

## 1. Décision

L'application Android embarque **son propre démon Tor** : plus aucune dépendance à
Orbot (ni à toute autre application externe). Nyx est autonome au sens complet :

1. **démon Tor in-process** — un service au premier plan (`NyxTorService`) lance le
   binaire tor fourni par la bibliothèque **tor-binary** (`io.matthewnelson.topl-android`,
   Maven Central — voir §5 pour le changement de source) avec un **torrc généré par
   l'application** (ports choisis au lancement, cookie d'authentification, répertoire
   de données privé) ;
2. **publication de son propre onion service** — dès que Tor a bootstrappé, l'app
   publie le service onion v3 de l'utilisateur via le control port
   (`ADD_ONION ED25519-V3:<clé>`) : **l'identité de l'utilisateur devient une adresse
   onion réelle, joignable** (ADR-003), prête pour le push P2P direct (roadmap v0.2+) ;
3. **réplicas joignables sans rien installer d'autre** — tout le trafic client passe
   par le SOCKS5 du Tor embarqué (127.0.0.1:port choisi dynamiquement).

## 2. Choix technique : exécution directe plutôt que TorService

La bibliothèque de packaging du binaire fournit à la fois le tor exécutable
(`libTor.so`, par ABI) et — pour tor-android — un `TorService` prêt à l'emploi.
**Nous utilisons uniquement le binaire**, exécuté par NOTRE service au premier plan
avec NOTRE torrc :

| Critère | Exécution directe (retenu) | TorService (bibliothèque) |
|---|---|---|
| Contrôle du torrc (ports, cookie, logs) | total, déterministe | conventions internes de la lib |
| Symétrie avec le serveur Go (`internal/tor`) | parfaite (même protocole control port) | partielle |
| Publication onion par notre clé Keystore | directe (ADD_ONION) | pareil, via control port |
| Couplage aux évolutions de l'API TorService | nul | fort |
| Auditabilité (fichiers lisibles, logs) | torrc + tor.log dans filesDir | opaque |

Le cycle de vie est géré par un **service Android au premier plan** (type `dataSync`)
avec notification persistante « Nyx — Tor actif », conforme aux exigences
Android 13+ (POST_NOTIFICATIONS) et 14+ (foregroundServiceType).

## 3. Conséquences

- **Packaging** : `useLegacyPackaging = true` (jniLibs) — le binaire `libtor.so` doit
  être **extrait sur le disque** pour être exécuté depuis `nativeLibraryDir`
  (interdit depuis l'APK ; W^X Android 10+ tolère l'exécution depuis
  `nativeLibraryDir`, répertoire en lecture seule).
- **Ports dynamiques** : SOCKS et control port sont choisis au démarrage (probe de
  ports libres à partir de 9050/9051, repli 9150/9151) — cohabitation possible avec
  un Orbot installé, sans conflit.
- **Sécurité** : la clé ed25519 de l'identité ne quitte jamais le processus — elle
  est déscellée (KeystoreBox) uniquement pour le `ADD_ONION`, en mémoire. Le cookie
  control port est généré par tor dans le répertoire de données privé de l'app.
  `__OwningControllerProcess` fait mourir tor si le processus app meurt.
- **Orbot n'est plus requis** ; il reste utilisable en mode « repli censure »
  (réseaux bloquant les nœuds d'entrée directs : Orbot + ponts obfs4/snowflake).
  Cette variante est documentée mais hors périmètre v0.2 — IPtProxy ne servira
  qu'à embarquer les transports pluggables, pas tor (choix abandonné : le binaire
  embarqué est la source du démon — cf. §5).
- **Coût** : mesuré au build réel (2026-10-12) — APK release R8 = 21,2 Mo, debug =
  39,9 Mo (libTor.so ≈ 6,6 Mo par ABI) ; RAM ~30-60 Mo quand Tor tourne. Accepté :
  l'autonomie est une exigence de premier ordre.
- **Build** : `assembleDebug` et `assembleRelease` **vérifiés verts** dans
  l'environnement de développement (SDK 35, JDK 21, Gradle 8.9, wrapper committé).
  La validation runtime (bootstrap, publication onion) reste à faire sur device —
  voir RESTART.md §7.

## 4. Ce qui change côté code

| Composant | Avant (v0.1) | Après (v0.2, ADR-007) |
|---|---|---|
| `tor/TorManager` | détection Orbot (paquet installé) | orchestration : service → process tor → bootstrap (progression) → prêt |
| `tor/NyxTorService` | — | service au 1er plan hébergeant le process tor |
| `tor/NyxTorConfig` | — | répertoires, choix des ports, génération du torrc |
| `tor/TorControlClient` | — | client control port minimal (miroir de `server/internal/tor/control.go`) |
| `tor/OnionServiceManager` | stub Orbot | implémentation réelle via `ADD_ONION` (clé = identité) |
| `net/RelayClient` | SOCKS 9050 fixe | SOCKS sur le port annoncé par TorManager |
| Orbot | prérequis absolu | non requis (repli censure, plus tard) |

## 5. Amendement (2026-10-12) — source du binaire tor + build réel

Premier build complet réalisé dans l'environnement de développement. Deux
découvertes ont imposé des ajustements, **sans changer l'architecture** (le binaire
est toujours exécuté directement par `NyxTorService` avec notre torrc) :

1. **Source du binaire : `tor-binary` au lieu de `org.torproject.jni:tor-android`.**
   Le dépôt Maven du Guardian Project (`maven.guardianproject.net`) est injoignable
   depuis certains environnements réseau/CI (DNS filtré), et tor-android n'a ni
   release GitHub ni présence sur Maven Central. La bibliothèque
   `io.matthewnelson.topl-android:tor-binary:0.4.6.5` (TorOnionProxyLibrary,
   Maven Central) embarque le même artefact : un **exécutable tor PIE** (ELF DYN avec
   PT_INTERP, vérifié) par ABI sous le nom **`libTor.so`** (T majuscule), extrait dans
   `nativeLibraryDir` via `useLegacyPackaging`. `NyxTorService.resolveTorBinary()`
   cherche `libTor.so` puis `libtor.so` (retour possible à tor-android sans
   réécriture). Version tor 0.4.6.5 : toutes les primitives utilisées (control port,
   cookie, `ADD_ONION ED25519-V3`, `__OwningControllerProcess`) y sont présentes.
2. **Dépendances calées sur les versions réellement publiées** : sqlcipher
   `4.5.4` (la 4.5.6 n'existe pas sous l'artefact `net.zetetic:android-database-sqlcipher`),
   JNA forcé en **artefact `@aar`** avec exclusion du jar desktop transitif de
   lazysodium-android (sinon `checkDuplicateClasses` échoue), R8 calmé sur les
   branches desktop `java.awt` de JNA (`-dontwarn`, cf. `proguard-rules.pro`).

Résultats : `assembleDebug` 39,9 Mo / `assembleRelease` (R8) 21,2 Mo, `libTor.so`,
`libsodium.so`, `libsqlcipher.so`, `libjnidispatch.so` présents par ABI dans l'APK.
