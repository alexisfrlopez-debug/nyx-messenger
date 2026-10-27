# ADR-019 — Client desktop Windows (portage Compose Desktop du messager)

- **Statut** : ACCEPTÉ (session 70 — 2026-10-27)
- **Demande opérateur (verbatim)** : « on reprend sur ce projet : [lien Drive].
  Maintenant je veux une version windows de cette application de messagerie »
- **Décision** : livrer un **client Nyx desktop Windows** sous la forme d'un
  module `desktop/` du dépôt public — **Kotlin + Compose Desktop (JVM)**,
  noyau logique **porté depuis l'app Android** (qui est déjà majoritairement
  du pur Kotlin testé JVM), Tor **embarqué via l'expert bundle officiel**
  (tor.exe lancé et piloté par le control port — même modèle que la
  console nyx-admin), **réplique embarquée in-process** (portage du
  EmbeddedReplica Android, PAS le binaire Go), livraison **zip portable +
  installateur.exe** (installateur Go à payload embarqué, miroir du pipeline
  nyx-admin).

## 1. Contexte

Le messager Nyx est Android-only (ADR-004 : Kotlin natif + Jetpack Compose).
La console d'administration nyx-admin (dépôt privé) a déjà validé la pile
Compose Desktop sur Windows : fat jar + runtime Java 21 + tor expert bundle
+ installateur Go multi-OS. L'opérateur demande désormais le **messager**
lui-même sous Windows. L'examen du code Android (94 fichiers, ~23 000 lignes)
montre que la majorité du noyau est du **pur Kotlin JVM** (mêmes sources
compilées pour les tests unitaires JVM de l'app) : OnionCodec, MessageCipher,
TorControlClient, ReplicaHttp, ReplicaValidate, EmbeddedReplica, codecs
(Chunk/Receipt/Delete/Replica/SeqResync), BootstrapReplicas, RendezvousList,
Loopback, MiniJson.

## 2. Décision détaillée

1. **Stack** : Compose Desktop (Kotlin 2.0.20, même version que l'app
   Android), coroutines + kotlinx-serialization (mêmes versions catalogue),
   OkHttp (SOCKS5 Tor), LazySodium **Java** (remplace la variante Android —
   mêmes interfaces `Box.Native`/`Sign.Native` ; natifs libsodium embarqués
   pour win/linux/mac via l'artefact Maven), Bouncy Castle (SHA3 onion v3 —
   déjà utilisé côté Android).
2. **Zéro changement filaire** : le client desktop parle le protocole
   existant (`protocol/specs/wire-format.md` v1.2 + `api.md` v1.2). AUCUNE
   évolution du format — l'interopérabilité Android ↔ Windows est un
   exigence de v1 (un téléphone v0.2.53 et un PC échangent messages,
   fichiers, accusés, contacts, profils par le journal répliqué).
3. **Tor embarqué** : expert bundle officiel torproject (tor.exe + dll),
   torrc généré par portage de NyxTorConfig (SocksPort/ControlPort dynamiques,
   CookieAuthentication, DataDirectory privé, ConfluxEnabled 0), démon lancé
   comme processus enfant, piloté par le `TorControlClient` existant
   (ADD_ONION ED25519-V3 Flags=Detach pour l'onion d'identité port 80 →
   réplique embarquée). Même garantie qu'ADR-007 : zéro dépendance à un Tor
   système pré-installé ; repli documenté si tor.exe système présent.
4. **Réplique embarquée in-process** : portage direct de l'EmbeddedReplica
   Android (5 routes, quotas, purge TTL 48 h, stockage) avec un stockage
   desktop **fichiers JSON atomiques** remplaçant Room/SQLCipher (v1 desktop :
   répertoire profil `%USERPROFILE%\.nyx-messenger`, fichiers 0600-ish,
   purge par Wipe). Le PC devient réplica temporaire quand l'app tourne —
   fidèle à la décision opérateur.
5. **Chiffrement local** : la clé privée d'identité est stockée chiffrée
   (secretbox libsodium, clé dérivée du mot de passe utilisateur via
   Argon2/compression libsodium) dans `identity.json` ; verrouillage opt-in
   de l'app au premier plan/démarrage (l'équivalent desktop du verrou
   biométrique ADR-010 — Windows Hello en phase 2). allowBackup équivalent :
   les clés ne quittent jamais le profil utilisateur.
6. **Périmètre v1** (cadré avec l'opérateur) : identité/onion, alias,
   contacts, conversations, chat E2E **texte + fichiers/images** (ChunkCodec,
   64 Mo max), accusés ✓✓, profils (visibilité par champ), réplique
   embarquée, écran connexions/sondes, thème nuit/aube. **Hors v1 (phase 2
   documentée)** : appels audio/visio (ADR-015 — pile MediaCodec Android non
   portable telle quelle), canal P2P direct ADR-017, NYX-LINK ADR-018,
   Windows Hello, auto-update. Les trames/traces de ces canaux sont IGNORÉES
   proprement côté desktop v1 (compat ascendante : le pair retombe sur le
   journal — comportement spécifié ADR-017 §interop).
7. **Livraison** : (a) zip portable 100 % autonome — runtime JRE 21 (Temurin,
   win x64) + tor expert bundle + libsodium.dll + fat jar + `nyx-messenger.bat` ;
   (b) installateur `nyx-messenger-windows-vX.exe` : binaire Go
   (`installer/`, GOOS=windows cross-compilé) au payload zip EMBARQUÉ
   (`go:embed`), SHA-256 compilé en dur (ldflags) et vérifié à l'extraction,
   anti Zip-Slip, raccourcis Bureau + Menu Démarrer, désinstalleur — miroir
   exact de l'installateur nyx-admin (ADR privé). Distribution : GitHub
   Releases (dépôt public — le messager n'est PAS soumis à la restriction
   admin ADR-008) + Drive `/livrables`.
8. **Audit/qualité** : le module desktop entre dans le périmètre
   `scripts/audit.py` (racines src/main/java standards JVM reconnues depuis
   la session 7) ; tests JVM desktop = portages des tests Android purs +
   nouveaux (stockage, torrc, scellement identité).

## 3. Conséquences

- Le dépôt public gagne `desktop/` (+ `installer/`) ; `android/` reste
  INTACT (aucune recompilation APK dans cette session — zéro risque de
  régression du pipeline build-apk.sh).
- Deux clients maintenus (Android + Desktop) partagent les mêmes specs
  filaires : toute évolution future du format passe TOUJOURS par
  `protocol/specs/` d'abord (règle §9 inchangée).
- Le réplica embarqué desktop duplique la logique de quotas Android
  (60 dépôts/min/auteur, 3 000 événements/48 MiB/500 par boîte, TTL 48 h) —
  les mêmes bornes, vérifiées par les mêmes tests miroirs.
- Surface crypto : libsodium via LazySodium Java (natives win/linux/mac
  embarquées dans l'artefact) + BC pour SHA-3 ; ed25519/X25519/secretbox
  uniquement — aucun ajout cryptographique maison.
