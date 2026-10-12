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
   binaire tor fourni par la bibliothèque **tor-android** (Guardian Project, la même
   qu'utilise Orbot) avec un **torrc généré par l'application** (ports choisis au
   lancement, cookie d'authentification, répertoire de données privé) ;
2. **publication de son propre onion service** — dès que Tor a bootstrappé, l'app
   publie le service onion v3 de l'utilisateur via le control port
   (`ADD_ONION ED25519-V3:<clé>`) : **l'identité de l'utilisateur devient une adresse
   onion réelle, joignable** (ADR-003), prête pour le push P2P direct (roadmap v0.2+) ;
3. **réplicas joignables sans rien installer d'autre** — tout le trafic client passe
   par le SOCKS5 du Tor embarqué (127.0.0.1:port choisi dynamiquement).

## 2. Choix technique : exécution directe plutôt que TorService

tor-android fournit à la fois le binaire (`libtor.so`, exécutable, par ABI) et un
`TorService` prêt à l'emploi. **Nous utilisons uniquement le binaire**, exécuté par
NOTRE service au premier plan avec NOTRE torrc :

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
  qu'à embarquer les transports pluggables, pas tor (choix abandonné : tor-android
  est la source du démon).
- **Coût** : APK ~10-15 Mo de plus (libtor.so par ABI) ; RAM ~30-60 Mo quand Tor
  tourne. Accepté : l'autonomie est une exigence de premier ordre.
- **Tests** : premier build + validation bootstrap à faire sur device (pas de SDK
  Android dans l'environnement de développement — voir RESTART.md §7).

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
