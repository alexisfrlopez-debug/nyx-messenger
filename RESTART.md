# ⚡ NYX — Instructions de redémarrage projet

> **CE FICHIER EST LE POINT D'ENTRÉE OBLIGATOIRE de toute nouvelle session de travail
> (agent ou humain) sur le projet Nyx.** Il est à jour de l'état réel du projet.
> Dernière mise à jour : **2026-10-13** (session 16).
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
| 7 | Console d'admin | **nyx-admin : appli SÉPARÉE Windows + Android** (monitoring, start/stop, réplication, élagage) — plan de contrôle = onion d'admin dédié + onion auth + commandes signées ed25519 | ADR-008 |
| 8 | Hébergement réplicas | **100 % réseau, zéro local** : réplicas sur hôtes distants, exposés UNIQUEMENT en hidden services ; le client DÉCOUVRE le réseau (rendezvous signé embarqué), il ne déploie rien | ADR-009 |
| 9 | Modération & biométrie | **AUCUNE collecte d'identité réelle (jamais cachée/non consentie)** ; modération protocolaire transparente (signalements, blocklists, PoW) ; **biométrie = déverrouillage LOCAL opt-in** (BiometricPrompt client, Windows Hello admin) — aucune donnée biométrique ne quitte le device | ADR-010 |
| 10 | Console admin (révision) | **Code/spec admin = dépôt PRIVÉ `nyx-admin`** ; **livrables = Drive privé `/livrables`** (zip Windows autonome + APK signé) — AUCUNE release GitHub pour l'admin ; le public n'a ni code, ni spec, ni binaires admin | ADR-008 (rév. session 11) |
| 11 | API admin serveur + profil + licence | **`/admin/*` implémentée dans nyx-server** (2 barrières : onion dédié 9091 + signature canonique `nyx-admin-v1` + nonce anti-replay ; checkpoints RFC 6962 ; défaut sûr sans clé) ; **type `profile`** (wire-format §7 : visibilité PAR CHAMP, privé = local uniquement, invariant serveur anti-fuite) ; **licence AGPL-3.0-only** (ADR-011) | ADR-011, APPLI_ADMIN.md §8 (privé) |
| 12 | Rangement des livrables | **`/livrables` en 3 zones (miroir Drive ↔ git public)** : `dernieres-livrables/` (jeu courant) · `archives/v<version>/` (versions remplacées, immuables) · `documents/` (rapports d'audit) ; binaires HORS git (Releases GitHub §9.6) — git porte structure + empreintes + rapports ; archivage automatique par `build-apk.sh`, propagation par `sync.sh` | §9.4 (rév. session 13) |
| 13 | Provisionnement des réplicas | **Création autonome depuis la console** : « Ajouter un réplica » offre Créer (la console forge les identités — onion client + admin + gossip + client-auth — et produit un bundle one-shot tar.gz : binaire serveur, identités pré-remplies, config, torrc durci, systemd, install.sh) ou Existant ; cycle « en attente → actif » + câblage flotte automatiques (pairs ajoutés des deux sens) ; identité forgée console = amendement motivé ADR-009 §2 (l'hôte lit le disque de toute façon) | ADR-012 |
| 14 | Déploiement + gestion des réplicas | **Déploiement EN UN CLIC depuis la console desktop** : bouton « Déployer » (carte « en attente » + fin d'assistant) → SSH/SFTP vers L'HÔTE DE L'OPÉRATEUR (hôte/port/utilisateur + mdp OU clé privée — saisie à chaque fois, AUCUN secret SSH persisté ; clé d'hôte épinglée TOFU journalisé) → install.sh streamé → nettoyage systématique ; JSch (fork mwiede) embarqué desktop ; **gestion flotte (desktop + Android)** : menu (⋯) Renommer / Modifier / Supprimer (retrait STRICTEMENT local — le nœud n'est pas arrêté ni effacé ; révocation réelle = re-provisionnement ADR-009 §5) ; zéro extension protocolaire | ADR-013 |
| 15 | Transports alternatifs des réplicas | **2 voies OPT-IN, jamais défaut** : (1) **XMPP MUC** — un salon MUC d'un serveur tiers (ex. hookipa.net) sert de bus aux enveloppes Nyx signées ; chaque réplica outbound-only publie ses événements propres, MAM = rattrapage ; le serveur XMPP ne voit que du chiffré ; réplica sur n'importe quel hôte gratuit sans port entrant, déploiement SSH ADR-013 inchangé ; (2) **relais TURN Cloudflare** — credentials éphémères générés par un backend (réplica/console, JAMAIS l'APK), allocation RFC 6062 = phase 2 ; métadonnées visibles du relais → opt-in documenté. L'onion v3 reste le transport premier (ADR-009). Client XMPP écrit dans le dépôt (stdlib + x/crypto — amendement ADR-005 ; ni mellium ni pion). | ADR-014 |

Dossier des ADR : `docs/adr/`. Toute évolution d'architecture = nouvel ADR.

## 3. État actuel (session 17 — 2026-10-13)

### Fait ✅ (session 17)
- **ADR-014 — transports alternatifs « commodity » (demande opérateur :
  « est-il possible de déployer sur un serveur xmpp type hookipa.net ? je
  voudrais pouvoir le faire » + « je regarde aussi pour utiliser un serveur
  TURN Cloudflare »)** :
  - **ADR-014 écrit** (docs/adr/) : 2 voies OPT-IN (XMPP MUC ; relais TURN),
    l'onion v3 RESTE le défaut (ADR-009 inchangé) ; amendement motivé ADR-005
    (client XMPP maison dans le dépôt, stdlib + x/crypto — ni mellium ni pion) ;
  - **specs d'abord (§9)** : protocol/specs/transport-xmpp.md (mapping MUC
    complet : join XEP-0045, enveloppes `nyx1` base64 fragmentées ≤ 48 Ko,
    rattrapage MAM XEP-0313, JIDs pseudonymes, SOCKS5 Tor optionnel,
    publication des SEULES événements propres) et protocol/specs/transport-turn.md
    (credentials éphémères Cloudflare phase 1 ; allocation RFC 6062 + publication
    au rendezvous signé = phase 2 spécifiée, non implémentée) ;
  - **client XMPP minimal** (server/internal/transportxmpp/, ~1 300 lignes) :
    XML streams, STARTTLS/TLS direct (tls_enforce, défaut true), **SASL
    SCRAM-SHA-1** (repli PLAIN sous TLS ; anti-MITM nonce + vérification v=
    serveur), bind, MUC join (presence + code 110), messages groupchat, MAM,
    dial via SOCKS5 Tor (x/net/proxy), reconnexion backoff 30 s → 15 min ;
  - **pompe journal ↔ MUC** : chaque réplica publie SES événements propres
    (curseur par auteur, 300 ms/lot), intègre ceux du salon via
    journal.Store.Append (validation signature + dé-dup id natives) et
    re-propage au gossip (OnNew → kick) ; réassemblage fragments (FIFO 128,
    TTL 10 min) ;
  - **config** : section `xmpp` {enabled, host, port, jid, password, muc,
    nick, tor_socks, tls_enforce} — mot de passe SECRET (data_dir/config.json
    uniquement ; config.example.json = valeur factice documentée) ; défaut =
    transport désactivé ;
  - **helper TURN** (server/internal/turn/ + CLI `nyx-devtool turn-creds`) :
    POST /v1/turn/keys/{key_id}/credentials/generate-ice-servers (Bearer,
    ttl ≤ 86400), filtre TCPOnly (UDP exclu — Tor TCP only), token lu depuis
    NYX_TURN_API_TOKEN (jamais argv) ;
  - **tests** : 12 tests Go verts (enveloppes aller-retour + fragmentation
    2 fragments + refus oversize, SCRAM client↔serveur math réel + rejet
    nonce imposteur + server-final falsifié, handshake complet contre faux
    serveur XMPP PLAIN et SCRAM, mot de passe erroné, TLS exigé, pompe :
    intégration événement signé + idempotence rejeu + bruit tiers ignoré +
    événement falsifié rejeté ; TURN : httptest + erreurs + filtre TCP) —
    `go vet` OK, **audit strict PASSED (CRIT 0/MAJOR 0)** ;
  - **audit.py précisé** : les identifiants d'espaces de noms XML
    (`xmlns='http://…'` — RFC 3986, jamais résolus en réseau) ne sont plus
    flaggés AUT-URL (faux positifs du transport XMPP) ;
  - ✅ **console nyx-admin : choix de transport APPLIQUÉ** (dès restauration des
    credentials) : assistant « Créer un réplica » gagne l'étape « Transport
    principal » — VPS+onion (défaut) / XMPP MUC (hôte, port 5222, JID, mot de
    passe masqué, salon, nick auto, Tor SOCKS optionnel, TLS verrouillé) — TURN
    affiché v0.4 phase 2 (désactivé) ; validation de forme avant génération
    (JID local@domaine, salon local@conf.domaine, port 1–65535, SOCKS) ; mot de
    passe XMPP = SECRET vivant UNIQUEMENT dans le config.json du bundle (jamais
    dans les réglages/journaux console) ; section `xmpp` écrite selon le choix
    (bundles onion inchangés — compat ascendante) ; badge XMPP/TURN sur la carte
    Flotte desktop + Android ; `ReplicaConfig.transport` (JSON round-trip,
    défaut onion) ; +10 tests JVM (18/18 verts) incluant extraction GNU tar
    réelle du bundle XMPP ; audit strict privé PASSED (faux positifs SEC-PERSONAL
    des JIDs d'exemple exclus, commentés) ; fat jar v0.3.1 construit — commit
    privé 3b43fe9 ; ⚠️ livrables installer.exe/zip/APK admin à produire sur
    l'env de build habituel (outils cross-build + SDK absents du conteneur
    restauré — SKIP consigné) ;
  - ✅ **synchro refaite** (dossier « Tokens » restauré depuis le Drive vers
    .credentials/tokens/ — §6) : push GitHub des deux dépôts (public b8cc2eb
    « ADR-014 transports alternatifs commodity » ; privé 3b43fe9 v0.3.1) ;
    snapshot Drive du worklog + RESTART.md → sauvegardes/session-2026-10-13/ ;
    ⚠️ rotation conseillée de l'API Token Cloudflare (collé en clair dans le
    chat — hygiène §9.5).

### Fait ✅ (session 16)
- **Déploiement en un clic des réplicas + gestion de la flotte (console
  nyx-admin v0.3.0)** — demande opérateur : « le replica ne se déploie pas
  tout seul. dans l'onglet flotte, je voudrais un bouton pour déployer en un
  clic et un pour supprimer/modifier/renommer le replica » (ADR-013) :
  - **SshDeployer** (desktop, JSch mwiede 0.2.21 — artefact unique, zéro
    dépendance transitive) : SFTP du bundle → /tmp, extraction, install.sh
    streamé ligne à ligne dans le dialogue, nettoyage systématique (trap EXIT
    — secrets d'infrastructure) ; `sudo -n` si utilisateur ≠ root ; commande
    distante validée par `sh -n` réel (tests JVM) ; annulation = déconnexion ;
  - **Sécurité** : AUCUN secret SSH persisté (seuls hôte/port/utilisateur +
    bundlePath par réplica — round-trip JSON testé y compris compat ascendante
    v0.2) ; clé d'hôte épinglée TOFU journalisé (~/.nyx-admin/known_hosts,
    format OpenSSH [hôte]:port) ; changement de clé = échec explicite (MITM) ;
  - **UI desktop** : bouton « Déployer » sur chaque carte « en attente » +
    « Déployer maintenant… » en fin d'assistant de création (replicaId filé) ;
    dialogue SSH (hôte/port/utilisateur, mot de passe OU clé privée +
    passphrase, choix/relocalisation du bundle, journal live auto-scroll) ;
    menu (⋯) par carte : **Renommer / Modifier / Supprimer** (confirmation) ;
    retrait relance le Tor embarqué (trousseau client-auth) ;
  - **UI Android** : même menu (⋯) Renommer/Modifier/Supprimer (KeyVault
    round-trip partagé via `ReplicaConfigJson` du core) ; badge « en attente »
    avec invitation à déployer depuis le desktop ;
  - **16 tests JVM verts** (round-trip + secrets jamais sérialisés + syntaxe
    sh -n des commandes root/sudo + quoting POSIX réel + tokens known_hosts +
    validation SshAuth) ; audits strict public/privé PASSED.
- **Livrables nyx-admin v0.3.0** : zip Windows 106 Mo + installer.exe 108 Mo
  (payload embarqué, SHA compilé, smoke test Linux : 223 fichiers extraits,
  essentiels OK) + APK admin 16 Mo versionCode 4 (9/9 contrôles autonomie) +
  SHA256SUMS ; LISEZMOI à jour (déploiement 1 clic + gestion flotte).

### Fait ✅ (session 15)
- **Correctif Android v0.2.1 — « choisir une photo de profil verrouille l'app
  et revient à l'écran d'invitation sans enregistrer »** (diagnostic : 3 défauts
  combinés) :
  1. l'ouverture du photo-picker système met MainActivity en pause →
     `AppLock.onBackgrounded()` re-verrouillait (opt-in biométrique actif) ;
     correctif : `AppLock.suspendNextBackground()` — drapeau one-shot consommé
     au prochain arrière-plan (flux système attendu : picker, feuille de
     partage) ; tout ARRIÈRE-PLAN suivant re-verrouille normalement ;
  2. `NyxNavHost` plaçait le verrou AVANT `rememberNavController()` : chaque
     verrouillage démontait la navigation (retour à l'écran d'accueil, éditions
     perdues, coroutine de l'écran annulée) → le verrou est désormais un
     CALQUE opaque AU-DESSUS de la navigation (le NavController, la pile et
     l'écran en cours survivent au verrouillage/déverrouillage) ; LockScreen
     passe opaque (fond du thème) ;
  3. lecture/décodage de la photo : `Dispatchers.IO` + `runCatching` (URI
     expirée ou image illisible → message d'erreur, jamais un crash) ;
  - même correctif appliqué au partage de « Mon adresse Nyx » (même cause) ;
  - **7 tests JVM AppLock** (suspension consommée une seule fois, purge du
    drapeau à enable/disable/init, re-verrouillage) — 22 tests verts au total ;
  - versionCode 5, release GitHub **v0.2.1** + Drive (build-apk.sh : archivage
    auto de v0.2.0).
- **Installateur Windows 1 clic pour nyx-admin** (demande opérateur : « un exe
  autosuffisant qui déploie tout en un clic ») — console **v0.2.1** :
  - `installer/` (Go, dépôt PRIVÉ) : payload zip EMBARQUÉ dans l'exe
    (`go:embed`) ; SHA-256 de référence COMPILÉ en dur (ldflags) et vérifié à
    l'exécution ; extraction anti Zip-Slip ; contrôle fichiers essentiels
    (java.exe/tor.exe/jar/lanceur) ; raccourcis Bureau + Menu Démarrer
    (PowerShell, redirections OneDrive gérées) ; `desinstaller.bat`
    (données `~\.nyx-admin` préservées) ; lancement proposé ; `-silent`/`-dir` ;
  - **smoke test réel sous Linux du MÊME binaire** (embed + intégrité +
    extraction : 223 fichiers en 1,2 s, contrôles OK) + 7 tests Go + vet +
    contrôles PE (`MZ`, taille ≥ payload) ; le zip « manière manuelle » reste
    livré ; APK admin v0.2.1 (versionCode 3) rebuildé ; SHA256SUMS régénéré ;
  - distribution Drive `dernieres-livrables/` + archivage v0.2.0
    (`archives/nyx-admin-v0.2.0/`) — toujours AUCUNE release GitHub admin
    (ADR-008).
- **Robustesse build (2  incidents durcis dans les scripts)** :
  - OOM-killer noyau (conteneur 4,1 Go — JVM de build à ~2,3 Go RSS) :
    `GRADLE_USER_HOME/gradle.properties` (-Xmx2g) ÉCRASE le gradle.properties
    projet → borne mémoire passée PAR LIGNE DE COMMANDE
    (`-Dorg.gradle.jvmargs=-Xmx1408m… --no-daemon`, 1 worker) dans
    build-apk.sh (public), build-apk.sh + build-windows.sh (privé) ;
  - disque plein pendant `go build` (embed 105 Mo) : purge
    gradle-home/caches/8.9 + go-build + intermédiaires (à surveiller, §9.5).

### Fait ✅ (session 14)
- **Provisionnement des réplicas depuis la console nyx-admin v0.2.0** (demande
  opérateur : « les réplicas se créent seuls dans la console quand je clique
  sur ajouter réplica, en gardant l'ajout d'existant » — ADR-012) :
  - admin-core : `OnionIdentity` (ed25519 + dérivation onion v3 SHA-3 BC +
    X25519 gossip + paire client-auth) — **vecteur de test croisé Go↔Kotlin** ;
    `ReplicaProvisioning` (bundle tar.gz ustar minimal : binaire nyx-server
    linux/amd64, identity.json + admin_identity.json pré-remplis — supportés
    tels quels par `LoadOrCreate` serveur, config.json [parrain + clé admin],
    torrc durci, unité systemd durcie, install.sh one-shot avec contrôle
    SHA256SUMS, LISEZMOI) — **bundle validé par GNU tar réel** (extraction +
    `sha256sum -c` + modes 755/600/644) ;
  - desktop : « Ajouter un réplica » = choix **Créer** (libellé + parrain
    joignable + FileChooser → bundle + instructions) / **Existant** (flux
    inchangé) ; réplica inscrit « en attente de déploiement » (badge) →
    promotion automatique à la première réponse → **câblage flotte
    bidirectionnel automatique** (GET /admin/identity → POST /admin/peers) ;
  - **correctif "Ajouter un pair"** : `addPeer` envoyait un booléen là où le
    serveur exige la chaîne base64 32 octets (400 systématique) — le gossip_pub
    est désormais récupéré automatiquement via `GET /identity` du pair (API
    client publique), saisie manuelle en secours ; miroir Android complet
    (KeyVault pending, promotion/câblage, addPeerDiscovered) ;
  - build-windows.sh embarque `server/nyx-server` (linux/amd64) dans le zip ;
  - 7 tests JVM verts ; audits public/privé PASSED ; console v0.2.0 livrée
    (zip Windows 106 Mo + APK Android signé) — Drive `dernieres-livrables/`.
- **ADR-012** + amendement ADR-009 §2 (identité forgée par la console : l'hôte
  lit le disque de toute façon — mitigation = remplacement du réplica) ;
  APPLI_ADMIN.md §9 (flux, format bundle, menace bundle) + §7 roadmap.

### Fait ✅ (session 13)
- **Rangement du dossier `/livrables` (Drive) + miroir sur GitHub** (demande
  utilisateur : « un sous-dossier pour ranger tous les dossiers archives, un
  pour stocker les documents et un pour déposer les dernières livrables —
  propage le rangement sur github ») :
  - `dernieres-livrables/` — jeu COURANT : APK v0.2.0 + SHA256SUMS (v0.2.0 +
    latest) + console admin v0.1.0 (APK + zip Windows + SHA256SUMS) ;
  - `archives/v0.1.0|v0.1.1|v0.1.2/` — APK + empreintes par version (immuables) ;
  - `documents/` — rapports d'audit (client + admin).
- **Drive** : 14 fichiers déplacés (nouveau `gdrive.py move`), arborescence
  vérifiée par `tree` ; IDs des 3 sous-dossiers consignés au privé (ops/INFRA.md).
- **Dépôt public** : `livrables/` versionné SÉLECTIVEMENT — `.gitignore` porte
  la structure (`*.md`/`*.txt` : README, empreintes, rapports) et exclut les
  binaires (Releases GitHub = canal des binaires, §9.6) ; `livrables/README.md`
  documente l'organisation et où trouver chaque livrable.
- **Pipeline adapté au rangement** : `build-apk.sh` livre dans
  `dernieres-livrables/` et ARCHIVE AUTOMATIQUEMENT la version remplacée dans
  `archives/v<version>/` (rapport d'audit → `documents/`) ; `sync.sh` upload
  récursif miroir (uploads immuables : nom + taille identiques → skip) ;
  `gdrive.py` : +`move`, +`tree`, +`upload --immutable` ; `release.sh` à jour.
- **Décision 12** + §9.3/§9.4 révisés + entrée 12 remise dans le tableau du
  journal (elle était appendue en fin de fichier) ; audits public et privé
  PASSED ; push public + privé ; synchro Drive complète.

### Fait ✅ (session 12)
- **API admin `/admin/*` côté nyx-server (TODO 3 réalisé)** : package
  `server/internal/adminapi` — authentification 2 barrières (onion d'admin
  DÉDIÉ publié en 9091 dès qu'une clé existe dans `config.json` ; signature
  ed25519 sur la chaîne canonique `nyx-admin-v1` exactement = AdminSigner.kt ;
  fenêtre ±120 s ; cache de nonce anti-replay 10 min), 12 endpoints de la spec
  (status/resources/peers/log, maintenance/shutdown/restart, add/remove/resync
  pairs, checkpoint propose/apply, identity, keys CRUD) + journal d'audit JSONL
  local ; **checkpoints** : racine de Merkle RFC 6962 signée par la clé onion,
  application en 2 étapes (archivage hors ligne + troncature vérifiable) ;
  sans clé admin configurée → AUCUN onion admin publié (défaut sûr).
- **Gossip enrichi** pour le monitoring : stats par pair (dernier contact,
  latence, backoff exponentiel borné, curseurs push/pull), RemovePeer/ResetPeer.
- **Type `profile` v1.2** (wire-format §7 + api.md) : validation serveur
  (clés fermées, limites, invariant « diffusion = tout public »), index
  dernier-seq-gagne, `GET /client/profile?author=`.
- **Client v0.2.0** : profil à visibilité PAR CHAMP (avatar photo picker +
  recodage 256×256 local, display_name/bio/website/location — privé = ne quitte
  JAMAIS l'appareil), ajout de contact PAR ALIAS (résolution §6), écran
  « Mon adresse Nyx » (copier/partager), écran Paramètres (thème système/clair/
  sombre, comportement de fermeture, verrou biométrique), **dialogue de
  fermeture** (réduire = Tor+notifications actifs / quitter = arrêt PROPRE de
  tous les processus Tor compris), profil contact depuis le chat.
- **Licence AGPL-3.0-only** (ADR-011) : LICENSE public + privé, README
  (racine/server/android), contrôle audit COH-LIC.
- Qualité : audit strict **0/0/4** ; 4 paquets Go verts (10 tests adminapi) ;
  12 tests JVM verts (7 profil) ; **APK v0.2.0 signé** (versionCode 4,
  SHA-256 ea020098…) dans livrables/ ; release GitHub v0.2.0 (docs/releases/).

### Fait ✅ (session 11)
- **Console nyx-admin v0.1.0 COMPILÉE ET LIVRÉE (Windows + Android)** — demande
  opérateur : « l'application d'administration serveur compilée pour windows et
  android et déposée dans le dossier livrables du drive » :
  1. **Dépôt privé `nyx-admin`** : modules `:core` (admin-core — client API
     `/admin/*` complet + signature ed25519 `X-Nyx-Admin/Ts/Nonce/Sig`, chaîne
     canonique `nyx-admin-v1` figée dans la spec §3, client-auth Tor via
     `.auth_private`/ClientOnionAuthDir, contrôle bootstrap miroir) +
     `:desktop` (Compose Desktop) + `:android` (APK `net.nyx.admin`, Tor
     embarqué 4 ABI, verrou biométrique opt-in ADR-010, clé scellée Keystore) ;
     pipeline dédié : `audit.py` (miroir §9.1) → `build-apk.sh` →
     `build-windows.sh`.
  2. **Livrables Drive `/livrables`** : `nyx-admin-android-v0.1.0-*.apk`
     (16 Mo, signé, universel, zéro GMS, SHA-256 `c65dfc9d…`) +
     `nyx-admin-windows-v0.1.0-*.zip` (103 Mo, 100 % autonome : fat jar +
     runtime Java 21 jlinké + tor 15.0.24 expert bundle + `nyx-admin.bat`,
     SHA-256 `5704c7f0…`) + `SHA256SUMS-nyx-admin-v0.1.0.txt` + rapport d'audit.
  3. **ADR-008 révisé** (code admin hors du public, distribution Drive — PAS de
     release GitHub pour l'admin) ; spec APPLI_ADMIN.md à jour (distribution,
     formats canoniques, §8 « État ») ; notes `docs/releases/v0.1.0.md` (privé).
  4. **Dépendance documentée** : l'API `/admin/*` côté nyx-server reste à
     implémenter (TODO 3) — la console diagnostique proprement (404 → « API
     admin absente ») et fonctionnera sans modification dès le déploiement.
  5. Enseignements build : conteneur 4,1 Go → Gradle mémoire bornée + Kotlin
     in-process (OOM kill sinon) ; `unzip | rg -q` sous `pipefail` = SIGPIPE →
     contrôles via fichiers temporaires.

### Fait ✅ (session 10)
- **v0.1.2 livrée** (versionCode 3, SHA-256 `40f97236…`) — deux fonctionnalités
  demandées par l'opérateur, cadrées ADR-010 + spec filaire :
  1. **Verrou biométrique local opt-in** : BiometricPrompt (androidx.biometric
     1.1.0 + fragment-ktx 1.8.5 explicite — lint), à froid et à chaque retour au
     1er plan ([AppLock]/[LockScreen]/[SettingsManager]) ; l'OS vérifie, l'app ne
     reçoit qu'un succès/échec — AUCUNE donnée biométrique lue/stockée/transmise ;
     MainActivity → FragmentActivity ; permissions USE_BIOMETRIC/USE_FINGERPRINT
     ajoutées à la liste fermée du contrôle d'autonomie (justifiée ADR-010) ;
     sortie de secours si le device perd ses moyens de vérification.
  2. **Alias unique (pseudonyme d'invitations)** : SPEC D'ABORD (wire-format.md
     §6 + api.md — règle §5), serveur Go complet (type `alias` validé, index
     déterministe min-id convergent, `GET /client/alias`), client : champ
     optionnel à l'onboarding, normalisation, vérification en ligne,
     **suggestions si pris**, claim signé différé automatiquement à la 1re
     connexion ([ensureAliasClaimed]). Alias optionnel/public — jamais de nom réel.
- **Tests** : 2 nouveaux tests Go verts (circuit claim→résolution + convergence
  min-id/replay) ; 4 tests Kotlin (AliasValidator, miroir serveur) — 8 JVM au total.
- **Hygiène du dépôt public (demande opérateur)** — nouvelle règle **§9.7** :
  1. **Dépôt privé `nyx-admin` créé** (même compte GitHub) : spec console admin
     (APPLI_ADMIN.md déplacée du public) + ops/INFRA.md (IDs Drive, inventaire
     credentials, procédure complète) + futur code console — plus rien d'admin
     sur le public ;
  2. **Zéro référence d'infrastructure sur le public** : IDs Drive + n° projet
     GCP purgés de RESTART.md (script purge_ids.py) et de gdrive.py (résolution
     par CHEMIN DE NOMS « z.ai/Messagerie » — testée de bout en bout ; override
     env NYX_ROOT_FOLDER_ID possible) ;
  3. Scan perso : commits déjà anonymes (Z User <z@container>) — aucune
     réécriture d'historique nécessaire ; le nom du compte GitHub (URL publique)
     est le seul identifiant restant (intrinsèque).
- Audit strict PASSED → build signé → 13 contrôles autonomie OK → APK livré.

### Fait ✅ (session 9)
- **ADR-010 (modération & biométrie) acté** après cadrage avec l'opérateur :
  1. rejet ferme de toute collecte d'identité réelle cachée/non consentie
     (contradictoire avec ADR-002/003 + RGPD art. 9/13 + infraction pénale en
     capture à l'insu) — verrou technique : AUT-PERM bloque CAMERA/MICRO (déjà actif) ;
  2. modération **protocolaire transparente** : signalements signés, blocklists
     de réplicas, orphanage, rate-limiting/PoW (spec filaire à ouvrir en phase 2) ;
  3. **biométrie acceptée = déverrouillage local opt-in** : BiometricPrompt
     (client Android), Windows Hello + biométrie (console nyx-admin) — l'OS
     vérifie, l'app ne reçoit qu'un succès/échec, rien ne transite sur le fil.
- Biométrie client à implémenter (verrou app ouverture + retour 1er plan,
  androidx.biometric, repli PIN device) → prochaine version APK v0.1.2.

### Fait ✅ (session 8)
- **Section Releases GitHub opérationnelle** (demande utilisateur : « une section
  releases dans le git où tu déposeras les nouvelles versions ») :
  `github.com/alexisfrlopez-debug/nyx-messenger/releases` — **v0.1.1 = Latest**
  (correctif SHA-3), **v0.1.0 = pré-release historique** (bug connu documenté) ;
  chaque release embarque l'APK signé + SHA256SUMS + rapport d'audit ; tags
  annotés poussés (v0.1.0→edc6cac, v0.1.1→98b5093).
- **`scripts/release.sh`** (versionné) : publication idempotente — tag annoté →
  release API → assets (APK/SHA256SUMS-vX/audit-report-vX) ; notes versionnées
  par convention `docs/releases/v<version>.md`. Nouvelle règle permanente
  **§9.6** : toute nouvelle version est publiée en release GitHub EN PLUS du
  dépôt /livrables du Drive.
- **Architecture de déploiement serveur actée** (demande utilisateur : « serveurs
  en service caché dès le lancement d'un client, rien de local, tout hébergé
  sur le réseau ») : **ADR-009** (réplicas sur hôtes distants, hidden services
  uniquement, client = découverte pas provisionnement) + **ADR-008** (console
  admin séparée Windows+Android) + doc d'exploitation **docs/DEPLOIEMENT_SERVEUR.md**
  (modèle, découverte, cycle de vie d'un réplica, sécurité du plan de contrôle,
  feuille de route).
- **Spec console nyx-admin rédigée** : **docs/APPLI_ADMIN.md** — 2 barrières
  d'auth (onion auth + commandes signées ed25519/nonce), API admin v1 (monitoring,
  maintenance, réplication, checkpoints d'élagage), écrans, modèle de menace,
  roadmap (v0.2 read-only → v0.4 élagage). Futur module `admin/` du dépôt.

### Fait ✅ (session 7)
- **Premier run device effectué par l'utilisateur : l'app se lance ✓** — mais crash
  à la création d'identité : `SHA3-256 MessageDigest not available`.
- **Cause** : `OnionCodec` utilisait `MessageDigest.getInstance("SHA3-256")` —
  Conscrypt ne fournit SHA-3 qu'à partir d'Android 14 (minSdk 26 → crash sur
  Android 8–13). **Correctif** : Bouncy Castle `bcprov-jdk18on` (SHA3Digest FIPS
  202, PAS Keccak — padding 0x06, comme `golang.org/x/crypto/sha3` côté Go).
- **4 tests unitaires JVM verts** (`OnionCodecTest` : vecteurs NIST, vecteur
  onion validé contre hashlib Python, aller-retour base32, checksum invalide
  refusé) — nouvelle infrastructure `app/src/test/` (junit 4.13.2 au catalogue).
- **`scripts/audit.py` corrigé** : le check COH-PKG plantait (exception interne
  → CRIT) sur tout fichier `.kt` hors `src/main/java` ; reconnaît désormais les
  racines `src/main|test|androidTest/java`.
- **Cycle complet vert** : audit strict PASSED → `assembleRelease` signé → 13
  contrôles d'autonomie OK → **APK v0.1.1 livré** (versionCode 2 ; 22 Mo,
  SHA-256 `411cdb15…`) — installable PAR-DESSUS la v0.1.0 (même signature).

### Fait ✅ (session 6)
- **Synchro Drive + GitHub DÉBLOQUÉE** : l'utilisateur a fourni un **dossier dédié
  « Tokens » dans le Drive** (voir §6) contenant tous les credentials ; accès API
  Drive vérifié (refresh OAuth OK) et PAT GitHub vérifiée.
- **Dossier `/livrables` créé sur le Drive** à la racine du dossier projet
  « Messagerie » — conformément à la règle §9.4 (référence exacte : dépôt privé
  `nyx-admin`, `ops/INFRA.md`).
- **Instructions mises à jour (ce fichier)** : le dossier dédié « Tokens » est
  désormais référencé partout (§6, §9.2, §9.3, §9.5) — c'est LA source de
  restauration des credentials d'une session neuve.
- Commits session 5 poussés vers GitHub (étaient en attente de credentials) +
  livraison de l'APK v0.1.0 dans `/livrables` du Drive + sauvegarde keystore
  dans « Tokens » (règle §9.5).

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
- ~~Credentials Drive/PAT en attente~~ **→ résolu en session 6** (dossier dédié
  « Tokens », §6).

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
1. **Android : run device de la v0.1.1** (release GitHub `v0.1.1`) — installer
   l'APK (par-dessus v0.1.0 si déjà installé), vérifier : création d'identité OK
   (correctif SHA-3), bootstrap tor (bannière %), publication onion (logcat
   `nyx-tor`), connexion au réplica de dev (GUIDE_DEMARRAGE.md §2 option 1 + §3).
2. **Test 3 réplicas sur machine avec Tor** — GUIDE_DEMARRAGE.md §2 (option 1).
3. **Déployer la flotte + associer la console** : DÉSORMAIS EN UN CLIC — dans
   nyx-admin v0.3.0 : « Ajouter un réplica » → Créer → « Déployer maintenant »
   (SSH vers l'hôte : mdp ou clé, install.sh streamé — ADR-013) ; puis la
   console détecte et câble la flotte automatiquement ; l'ONION D'ADMIN
   n'est plus à recopier depuis les logs (identité pré-forgée ADR-012).
   Tester le cycle complet console↔nœud.
4. **Rendezvous signé embarqué dans l'APK** (suite ADR-009 §3) — le bundle
   one-shot de déploiement est RÉALISÉ (ADR-012, assistant console) ; reste la
   liste de rendezvous des réplicas d'amorçage dans les releases.
5. Run device v0.2.1 : **CHOISIR UNE PHOTO DE PROFIL (correctif session 15 —
   doit rester sur l'écran profil, enregistrer et afficher l'avatar)**, profil
   (visibilité par champ), contact par alias, Mon adresse (partage), dialogue
   fermeture, thème ; côté Windows : installateur 1 clic (SmartScreen →
   « Exécuter quand même »), premier lancement, association console↔nœud.
6. TODO app restants (android/README.md) : persistance compteur de séquence, écran
   d'ajout de réplica, tests instrumentés.
7. Repli censure : ponts obfs4/snowflake via IPtProxy dans le torrc (ADR-007 §3).
8. **Livrables console v0.3.1 — PARTIEL** : jar desktop v0.3.1 construit et
   déposé (dernieres-livrables/, archive nyx-admin-v0.3.0 complète — session
   18) ; reste installer.exe + zip Windows + APK admin via
   scripts/build-windows.sh + build-apk.sh sur l'env de build habituel
   (JDK/tools + SDK Android), puis `publish-drive.sh --kind admin
   --version 0.3.1 --files …` + version consignée ici.
9. **TURN phase 2** (ADR-014/transport-turn.md §6) : allocation TCP RFC 6062
   côté réplica en stdlib (~600 lignes STUN/HMAC-SHA1), publication de
   l'adresse relayée dans le rendezvous signé, repli client documenté.
10. CI GitHub (workflow Go, puis Android) ; libsignal, chiffrement du champ
   `to`, purge TTL, push P2P direct.

## 4. Structure du projet

```
nyx/
├── RESTART.md              ← ce fichier (racine du répertoire de travail)
├── GUIDE_DEMARRAGE.md      ← COMMENT LANCER (option 1 serveurs / option 2 Android)
├── README.md               ← vue d'ensemble
├── docs/                   ← architecture, sécurité, protocoles, roadmap, setup, ADR/
│     ├── DEPLOIEMENT_SERVEUR.md (exploitation réplicas — ADR-008/009)
│     │     (la spec détaillée APPLI_ADMIN.md vit dans le dépôt PRIVÉ, §9.7)
│     └── releases/              ← notes de version (docs/releases/vX.Y.Z.md)
├── protocol/specs/         ← wire-format.md, api.md  (TOUTE évolution commence ici)
├── server/                 ← nœud Go (cmd/{nyx-server,nyx-devtool}, internal/{tor,journal,gossip,api,identity,config,transportxmpp,turn})
├── android/                ← app Kotlin/Compose (voir android/README.md)
├── admin/                  ← N'EXISTE PAS ICI : console nyx-admin = dépôt PRIVÉ dédié (ADR-008 rév. session 11)
└── scripts/                ← bootstrap.sh, dev-server.sh, torrc-dev, build-apk.sh, audit.sh, sync.sh, release.sh, publish-drive.sh
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

# Publier une release GitHub (APK dans livrables/ requis ; notes dans docs/releases/vX.Y.Z.md)
scripts/release.sh --version 0.1.1 [--commit <sha>] [--prerelease] [--title "…"]
#   → tag annoté poussé + release + assets (APK, SHA256SUMS-vX, audit-report-vX)
#   Releases actuelles : https://github.com/alexisfrlopez-debug/nyx-messenger/releases
```

4. **Règles de session** (détail complet en **§9**) :
   - toute évolution du format filaire → `protocol/specs/` D'ABORD, puis serveur, puis client ;
   - **AUCUNE recompilation sans audit strict vert d'abord** : `scripts/audit.sh` (§9.1) ;
   - **AUCUNE recompilation sans pipeline** : `scripts/build-apk.sh` (audit → build → vérif autonomie → livrables) ;
   - **AUCUN APK non autonome** : Tor embarqué, signé release, universel, zéro service tiers (§9.2) ;
   - **SYNCHRO SYSTÉMATIQUE en fin de session** : `scripts/sync.sh` (GitHub + Drive, §9.3) ;
   - **LIVRABLES rangés en 3 zones** (`dernieres-livrables/`, `archives/v<version>/`, `documents/`)
  dans `livrables/` (local) et `/livrables` (Drive) — miroir git↔Drive (§9.4) ;
   - aucun secret dans le dépôt (identités, clés, tokens) — `.gitignore` calibré §9.5 ;
   - terminer la session en mettant à jour ce fichier (§3 État + §8 Journal).

## 6. Accès et sauvegarde — dossier dédié « Tokens » + dépôt privé `nyx-admin`

> **LE DOSSIER DÉDIÉ DE L'AGENT SUR LE DRIVE EST « Tokens »** (nommé ainsi par
> l'utilisateur, session 6). Il est la source unique des credentials : toute
> session neuve commence par en restaurer le contenu vers
> `/home/z/my-project/.credentials/tokens/`.
>
> **RÈGLE (session 10) : aucune référence d'infrastructure exploitable
> (IDs de dossiers Drive, inventaire détaillé des credentials, numéros de
> projet) n'apparaît sur ce dépôt PUBLIC.** Elles vivent dans le **dépôt
> privé `nyx-admin`** (même compte GitHub, fichier `ops/INFRA.md`) —
> l'accès à ce dépôt suppose le PAT (dossier « Tokens »). Le dépôt public
> ne documente que la procédure par NOMS de dossiers.

| Ressource | Référence (sans identifiant — détail : dépôt privé `nyx-admin` ops/INFRA.md) |
|---|---|
| Dossier Drive projet | « **Messagerie** » (racine du Drive utilisateur — résolution par NOM ; contenu : RESTART.md, GUIDE_DEMARRAGE.md, README.md, archive tar.gz) |
| **Dossier dédié « Tokens » (credentials)** | sous-dossier « **Tokens** » de « Messagerie » — contenu : OAuth client · oauth_token_relais (refresh Drive, cycle ~7 j → ré-autorisation à prévoir) · compte de service · `token_git.txt` (PAT GitHub fine-grained) · `nyx-release.jks` + `keystore.properties` (keystore release — règle §9.5) |
| Dossier Drive `/livrables` | sous-dossier « **livrables** » de « Messagerie » — **3 zones** (session 13) : `dernieres-livrables/` (jeu courant) · `archives/v<version>/` (versions remplacées) · `documents/` (audits) — règle §9.4 ⚠️ 4 dossiers « livrables » homonymes existent ailleurs dans ce Drive (autres projets) : toujours descendre depuis « Messagerie », JAMAIS depuis la racine |
| Module de synchro Drive | `scripts/gdrive.py` (versionné, stdlib only, **aucun ID en dur** — résolution par noms depuis « Messagerie ») ; credentials dans `.credentials/` du workspace (JAMAIS dans le dépôt) |
| GitHub — dépôt public | `github.com/alexisfrlopez-debug/nyx-messenger` (code, docs génériques, releases) — `token_git.txt` = PAT fine-grained du compte `alexisfrlopez-debug` |
| GitHub — **dépôt privé** | `alexisfrlopez-debug/nyx-admin` — **spec console admin + ops/INFRA.md (IDs, credentials, restauration complète) + futur code console** — JAMAIS de contenu de ce dépôt vers le public |

**Procédure de restauration d'une session neuve** (workspace vierge) :
1. Cloner le dépôt public GitHub (code, docs, scripts, wrapper Gradle).
2. Cloner le dépôt privé `nyx-admin` (PAT requis) → `ops/INFRA.md` donne les IDs.
3. Télécharger le contenu du dossier « Tokens » → `.credentials/tokens/`
   (credentials + keystore) — tout y est, rien à redemander à l'utilisateur
   (fallback sans dépôt privé : résolution par noms depuis le lien « Messagerie »
   fourni par l'utilisateur — gdrive.py gère).
4. Reconstruire l'env. de build si besoin (JDK 21 + SDK android-35, §7) —
   ou vérifier qu'ils ont survécu dans `tools/`.
5. Lancer le cycle : audit → build → livrables → release GitHub → synchro (§9).

Ré-autorisation OAuth : le refresh_token expire ~7 jours (note dans
oauth_token_relais). Si expiré → demander à l'utilisateur une nouvelle autorisation
(les autres credentials ne périment pas).

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
| 2026-10-12 | 6 | **Demande utilisateur : « tu as un dossier dédié dans le drive, indique-le dans les instructions »** → dossier **« Tokens »** identifié sur le Drive (référence exacte : dépôt privé `nyx-admin`, `ops/INFRA.md`) : client_secret OAuth, oauth_token_relais, compte de service, PAT GitHub. Credentials restaurés, **accès API Drive vérifié (refresh OK) et PAT GitHub validée** ; **dossier `/livrables` créé** ; ce fichier mis à jour (§3, §6, §9) ; commits session 5 poussés vers GitHub ; APK v0.1.0 + rapport d'audit + SHA256 déposés dans /livrables ; keystore + keystore.properties sauvegardés dans « Tokens » (§9.5) ; archive session 6 uploadée. Synchro Drive+GitHub pleinement opérationnelle. |
| 2026-10-12 | 7 | **Retour utilisateur : l'app se lance mais crash à la création d'identité — « SHA3-256 MessageDigest not available »** → cause : `MessageDigest("SHA3-256")` absent d'Android ≤ 13 (Conscrypt ne fournit SHA-3 qu'à partir d'Android 14 ; minSdk 26) ; **correctif : Bouncy Castle bcprov-jdk18on (SHA3Digest FIPS 202, pas Keccak)** dans OnionCodec ; **4 tests unitaires JVM verts** (NIST + vecteur hashlib Python + aller-retour + checksum invalide) ; audit.py corrigé (COH-PKG plantait sur src/test) ; cycle complet : audit PASSED → release signée → 13 contrôles OK → **APK v0.1.1 (versionCode 2, SHA-256 411cdb15…) livré** dans /livrables ; docs à jour (android/README.md § Correctif v0.1.1). |
| 2026-10-12 | 8 | **Demande utilisateur : « section releases dans le git + explication déploiement serveur (hidden services dès le lancement d'un client, rien de local) + appli admin séparée Windows/Android + mettre à jour les instructions »** → **Releases GitHub actées** : script `release.sh` (tag annoté + release API + assets, idempotent), notes versionnées `docs/releases/`, **v0.1.1 = Latest** + v0.1.0 pré-release historique (APK+SHA256SUMS+audit en assets, tags v0.1.0→edc6cac, v0.1.1→98b5093) ; règle permanente **§9.6**. **ADR-009** (hébergement 100 % réseau, zéro local, rendezvous signé, client = découverte) + **ADR-008** (console nyx-admin séparée Win+Android, onion d'admin dédié, commandes signées) + **docs/DEPLOIEMENT_SERVEUR.md** (exploitation complète) + **docs/APPLI_ADMIN.md** (API admin v1, écrans, menace, roadmap). TODO n°3/n°4 ajoutés (admin, bundle deploy). |
| 2026-10-12 | 9 | **Cadrage opérateur : biométrie + modération** → ADR-010 : rejet de la collecte d'identité réelle cachée/non consentie (architecture sans compte + RGPD art. 9/13 + pénal) — la modération se fera par mécanismes protocolaires TRANSPARENTS (signalements signés, blocklists de réplicas, orphanage, PoW) ; **biométrie acceptée en déverrouillage LOCAL opt-in** (BiometricPrompt client / Windows Hello admin, aucune donnée biométrique transmise — l'OS vérifie) ; verrou technique AUT-PERM (CAMERA/MICRO bloqués) rappelé. Biométrie client planifiée pour v0.1.2. |
| 2026-10-12 | 10 | **Demande utilisateur : « périmètre tout » + alias unique pour invitations + admin non public + zéro info perso sur le git public** → **v0.1.2 livrée** : verrou biométrique opt-in (BiometricPrompt/FragmentActivity/AppLock — ADR-010, zéro donnée biométrique) + alias unique (spec filaire §6 d'abord, serveur Go + index min-id convergent + GET /client/alias, client onboarding avec suggestions, claim différé auto) ; tests : 2 Go + 4 Kotlin verts ; **dépôt privé `nyx-admin` créé** (spec admin + ops/INFRA.md déplacés du public) ; **IDs Drive/GCP purgés du public** (RESTART.md + gdrive.py → résolution par noms z.ai/Messagerie testée) ; commits déjà anonymes (pas de réécriture) ; règle permanente **§9.7** (hygiène publique). |
| 2026-10-12 | 11 | **Demande utilisateur : « l'application d'administration serveur compilée pour windows et android et déposée dans le dossier livrables du drive »** → **console nyx-admin v0.1.0 livrée** : dépôt privé (`:core` admin-core — client API `/admin/*` signé ed25519, chaîne canonique `nyx-admin-v1` figée, client-auth Tor `.auth_private` ; `:desktop` Compose Desktop ; `:android` APK `net.nyx.admin` Tor embarqué + verrou biométrique opt-in) ; pipeline dédié (audit.py miroir §9.1 → build-apk.sh → build-windows.sh) ; **livrables Drive `/livrables`** : APK 16 Mo signé (SHA c65dfc9d…) + zip Windows 103 Mo 100 % autonome (fat jar + Java 21 jlinké + tor 15.0.24, SHA 5704c7f0…) + SHA256SUMS + audit ; **ADR-008 révisé** (admin hors public, distribution Drive) ; spec/notes à jour ; API `/admin/*` serveur = TODO 3 (console opérationnelle dès déploiement). Corrections : Gradle mémoire bornée (OOM 4,1 Go), contrôles unzip via fichiers temporaires (SIGPIPE/pipefail). |
| 2026-10-12 | 12 | **Demande utilisateur : « implémente l'API admin + contact par alias + partage adresse onion + menu paramètres + édition de profil (avatar, visibilité par champ) + dialogue fermeture (réduire vs tuer proprement) + définis la licence »** → **API admin serveur complète** (adminapi : 2 barrières, anti-replay, 12 endpoints, checkpoints RFC 6962 signés, audit JSONL, onion dédié 9091, défaut sûr) + **type `profile` v1.2** (spec §7 d'abord ; invariant anti-fuite serveur ; index dernier-gagne ; GET /client/profile) + **client v0.2.0** (profil par champ avec avatar, contact par alias, Mon adresse partageable, Paramètres thème/fermeture, dialogue réduire vs quitter proprement) + **AGPL-3.0-only** (ADR-011, LICENSE public+privé, contrôle COH-LIC) ; audit strict 0/0/4, 4 paquets Go + 12 tests JVM verts, APK v0.2.0 signé (SHA ea020098…), release GitHub v0.2.0, sync Drive+GitHub, APPLI_ADMIN.md §8 mis à jour (API livrée). |
| 2026-10-12 | 13 | **Demande utilisateur : « dans le drive, dossier /livrables, classer le contenu : un sous-dossier pour ranger tous les dossiers archives, un pour stocker les documents et un pour déposer les dernières livrables — propage le rangement sur github »** → **Drive** : `/livrables` structuré en 3 zones — `archives/v0.1.0·v0.1.1·v0.1.2/` (APK+empreintes par version, immuables), `documents/` (rapports d'audit client+admin), `dernieres-livrables/` (jeu courant : APK v0.2.0, console admin v0.1.0 APK+zip, SHA256SUMS) — 14 fichiers déplacés via le nouveau `gdrive.py move`, arborescence vérifiée par `tree` ; **dépôt public** : miroir versionné SÉLECTIF (livrables/README.md + SHA256SUMS + audit-report.txt ; `.gitignore` porte la structure `*.md`/`*.txt`, exclut les binaires — Releases GitHub = canal des binaires) ; **pipeline adapté** : build-apk.sh livre dans `dernieres-livrables/` + ARCHIVAGE AUTO de la version remplacée + rapport d'audit vers `documents/`, sync.sh upload récursif miroir (uploads immuables nom+taille → skip), gdrive.py +`move`/`tree`/`upload --immutable`, release.sh à jour ; décision 12 + §9.3/§9.4 révisés + entrée 12 remise dans le tableau du journal ; IDs des 3 sous-dossiers consignés au privé (ops/INFRA.md) ; audit.py privé CORRIGÉ (binaires skippés — faux positif e-mail sur gradle-wrapper.jar ; mot de passe keystore : seuls les littéraux en dur sont flaggés, la lecture getProperty du git-ignoré keystore.properties est la bonne pratique §9.5) ; audits public et privé PASSED ; push public + privé ; synchro Drive complète. |
| 2026-10-13 | 15 | **Demandes utilisateur : (1) « quand je choisis une photo de profil et que je clique sur ok, l'app se verrouille et revient à l'écran d'invitation sans enregistrer » ; (2) « pour l'appli admin windows, un exe autosuffisant qui déploie tout en un clic »** → **v0.2.1 client** : AppLock.suspendNextBackground (picker/partage = arrière-plan ATTENDU, consommé une fois), verrou = calque opaque au-dessus de la navigation (NavController + pile + écran survivent — plus de retour à l'accueil ni de perte d'édition), décodage photo durci (IO + runCatching) ; 7 tests JVM AppLock (22 verts) ; APK signé + release GitHub + Drive. **nyx-admin v0.2.1** : installer/ Go (payload zip EMBARQUÉ dans l'exe, SHA de référence compilé vérifié à l'exécution, extraction anti Zip-Slip, raccourcis Bureau+Menu Démarrer, désinstalleur, -silent/-dir), smoke test Linux du même binaire (223 fichiers) + 7 tests Go + vet, zip manuel + APK admin rebuildés + SHA256SUMS ; ADR-008 respecté (Drive, pas de release GitHub admin). Build : mémoire gradle bornée par ligne de commande (OOM noyau durci dans les 3 scripts), purge disque. |
| 2026-10-13 | 16 | **Demande utilisateur : « le replica ne se déploie pas tout seul. dans l'onglet flotte, je voudrais un bouton pour déployer en un clic et un pour supprimer/modifier/renommer le replica »** → **ADR-013 + console nyx-admin v0.3.0** : SshDeployer (desktop, JSch mwiede — SFTP bundle → /tmp, extraction, install.sh STREAMÉ dans le dialogue, nettoyage trap EXIT, sudo -n si non-root, annulation = déconnexion) ; sécurité : AUCUN secret SSH persisté (hôte/port/utilisateur + bundlePath seulement — round-trip JSON + compat ascendante v0.2 testés), clé d'hôte épinglée TOFU journalisé (known_hosts console, [hôte]:port), changement de clé = échec explicite ; UI desktop : « Déployer » sur les cartes en attente + « Déployer maintenant… » en fin d'assistant + dialogue SSH (mdp OU clé + passphrase, relocalisation bundle, journal live) ; menu (⋯) Renommer/Modifier/Supprimer par carte (desktop + Android, retrait local — nœud intact, relance Tor client-auth) ; `ReplicaConfigJson` partagé core (SettingsStore + KeyVault) ; 16 tests JVM verts (sh -n réel des commandes root/sudo, quoting POSIX exécuté, tokens known_hosts) ; audits strict public/privé PASSED ; livrables : zip 106 Mo + installer.exe 108 Mo (smoke 223 fichiers) + APK admin 16 Mo (9/9 autonomie, versionCode 4) + SHA256SUMS → Drive. Dépôt privé : code+spec §9.4/§9.5+roadmap v0.3 ; public : ADR-013 + RESTART (décision 14). |
| 2026-10-13 | 14 | **Demande utilisateur : « les réplicas se créent seuls dans la console admin quand je clique sur ajouter réplica, en gardant la possibilité d'en ajouter des existants »** → **ADR-012 + console v0.2.0** : admin-core `OnionIdentity` (ed25519, onion v3 SHA-3 BC — vecteur croisé Go↔Kotlin, X25519) + `ReplicaProvisioning` (bundle tar.gz ustar : binaire serveur linux/amd64, identités pré-remplies, config parrain+clé admin, torrc durci, systemd, install.sh avec sha256sum -c, LISEZMOI) — validé par GNU tar réel ; UI desktop choix Créer/Existant + état « en attente » + promotion + câblage flotte automatiques ; **correctif addPeer (gossip_pub base64 via GET /identity — l'envoi du booléen était rejeté 400)** ; miroir Android ; ADR-009 §2 amendé (identité forgée console, menace honnête hébergeur) ; APPLI_ADMIN.md §9 ; 7 tests JVM verts ; audits PASSED ; livrables zip Windows 106 Mo + APK signé → Drive. |
| 2026-10-13 | 17 | **Demande utilisateur : « est-il possible de déployer sur un serveur xmpp type hookipa.net ? je voudrais pouvoir le faire » + « je regarde aussi pour utiliser un serveur TURN Cloudflare »** → **ADR-014 « Transports alternatifs commodity »** : analyse (hookipa.net = fournisseur de COMPTES chat, impossible d'y héberger le binaire ; la voie correcte = le MUC comme BUS de messages, enveloppes Nyx signées dedans, MAM = rattrapage) ; 2 voies OPT-IN (XMPP MUC ; TURN Cloudflare), onion v3 défaut (ADR-009), amendement ADR-005 (client XMPP maison stdlib+x/crypto, ni mellium ni pion) ; **specs d'abord** (transport-xmpp.md : join XEP-0045, enveloppes nyx1 base64 fragmentées ≤ 48 Ko sous max_stanza 64 Ko, MAM catch-up, JIDs pseudonymes, SOCKS5 Tor optionnel, publication des seuls événements propres ; transport-turn.md : creds éphémères Cloudflare — POST /v1/turn/keys/{id}/credentials/generate-ice-servers Bearer ttl ≤ 86400 —, phase 2 = allocation RFC 6062 + adresse relayée au rendezvous signé) ; **implémentation serveur** : internal/transportxmpp (XML streams, STARTTLS obligatoire défaut, SASL SCRAM-SHA-1 anti-MITM + repli PLAIN, bind, MUC join 110, groupchat, MAM, dial SOCKS5 Tor, backoff 30 s→15 min ; pompe journal↔MUC par-auteur, réassemblage FIFO 128/TTL 10 min ; OnNew→kick gossip) + config `xmpp` (password = SECRET data_dir only, example factice) + internal/turn + CLI `nyx-devtool turn-creds` (token via NYX_TURN_API_TOKEN, jamais argv ; TCPOnly — UDP exclu, Tor TCP only) ; **12 tests Go verts** (SCRAM math réel client↔serveur + imposteurs rejetés, handshake complet PLAIN/SCRAM contre faux serveur, TLS exigé, pompe : signé intégré/rejeu idempotent/falsifié rejeté) ; go vet OK, **audit strict PASSED 0/0** ; audit.py précisé (xmlns = identifiant d'espace de noms, pas une URL) ; smoke binaire (xmpp disabled → rien ; enabled → « transport MUC activé » + backoff propre, /health saine) ; ⚠️ **workspace reconstruit après reset** : nyx-messenger recloné (HEAD public dabe50c retrouvé sur GitHub — le « 1 commit non poussé » de la session 16 est donc soit déjà poussé, soit perdu avec l'ancien disque ; nyx-admin et .credentials/tokens/ à restaurer §6) ; console nyx-admin (choix transport) NON applicable sans le privé — modification préparée ; push/Drive en attente de credentials (§9.3 SKIP explicite). **Suite (même session, credentials restaurés)** : dossier « Tokens » téléversé du Drive vers .credentials/tokens/ (6 fichiers, §6) — gdrive.py --status OK ; push public b8cc2eb ; nyx-admin cloné (b1fcd90) puis **console v0.3.1** : assistant « Créer un réplica » + étape « Transport principal » (VPS+onion / XMPP MUC / TURN v0.4 désactivé), XmppParams (validation JID/salon/port/SOCKS), section `xmpp` du bundle (mot de passe = bundle ONLY), badge Flotte desktop+Android, `ReplicaConfig.transport` (défaut onion), +10 tests JVM (18/18), audit privé PASSED (JIDs d'exemple exclus SEC-PERSONAL, commentés), fat jar OK — commit privé 3b43fe9 poussé ; livrables admin = SKIP (outils cross-build + SDK absents du conteneur, à faire sur l'env habituel) ; worklog + RESTART sauvegardés → sauvegardes/session-2026-10-13/. |
| 2026-10-13 | 18 | **Demande utilisateur : « dépose les dernières livrables dans le drive et archive les anciennes qui s'y trouvent. il faut faire ça systématiquement à chaque release »** → **Règle §9.4 durcie (rév. session 18)** : nouveau `scripts/publish-drive.sh` — publication Drive SYSTÉMATIQUE par famille (client/admin) : archivage des versions remplacées de `dernieres-livrables/` → `archives/v<ancienne>/` (client) ou `archives/nyx-admin-v<ancienne>/` (admin), puis upload du jeu courant (uploads immuables nom+taille ; `SHA256SUMS-latest.txt` = MUTABLE par définition, jamais immuable) + documents → `documents/` ; appelé AUTOMATIQUEMENT par `release.sh` (étape 6, famille client), invocation directe pour l'admin ; `sync.sh` aligné (latest jamais immuable) ; `gdrive.py` +`cat` (lecture distante = vérification d'intégrité) ; **Drive vérifié/rangé** : jar console v0.3.1 en `dernieres-livrables/` (empreinte d1f56369… vérifiée cat ↔ sha256sum), `SHA256SUMS-nyx-admin-v0.3.1.txt` en place, archive `nyx-admin-v0.3.0/` complète (ID consigné ops/INFRA.md) ; `.gitignore` privé durci (artefacts de livraison racine) ; à-faire n°8 réduit (reste installer.exe/zip/APK admin sur env de build) ; audits public/privé PASSED, commits + pushs public/privé, RESTART synchronisé sur le Drive. |

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
   `android/keystore.properties` git-ignoré) — **keystore + mots de passe sauvegardés
   dans le dossier Drive dédié « Tokens »** (§6) : sans lui, aucune mise à jour
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
  `/home/z/my-project/.credentials/` (restaurés du **dossier dédié « Tokens »**, §6 ;
  refresh ~7 j → ré-autorisation utilisateur si expiré). Uploads :
  `RESTART.md` + `README.md` + `GUIDE_DEMARRAGE.md` (racine du dossier projet
  « Messagerie »), archive source tar.gz de l'état de session, et l'arborescence complète
  de `livrables/` — miroir 3 zones (archives/documents/dernieres-livrables),
  uploads immuables (référence exacte : dépôt privé `nyx-admin`, `ops/INFRA.md`).
- Si les credentials manquent : **SKIP explicite** (jamais d'échec silencieux) et
  consigner ce qui reste en attente de synchro.
- À la reprise d'un workspace vierge : tout restaurer depuis le dépôt GitHub (public)
  + le dossier Drive projet « Messagerie » (docs + archive) + le dossier dédié
  **« Tokens »** (credentials + keystore) — procédure §6.

### 9.4 Livrables dans /livrables — 3 zones (rév. sessions 13, 18)

> Demande utilisateur (session 13) : « un sous-dossier pour ranger tous les
> dossiers archives, un pour stocker les documents et un pour déposer les
> dernières livrables » — appliqué au Drive ET propagé sur GitHub (miroir).

- **Structure en 3 zones, MIROIR Drive ↔ dépôt public** :
  - `dernieres-livrables/` — jeu de livraison COURANT : dernière APK client +
    `SHA256SUMS-latest.txt` + `SHA256SUMS-v<version>.txt` ; sur le Drive
    s'ajoutent les livrables admin (APK + zip Windows + SHA256SUMS, §9.7) ;
  - `archives/v<version>/` — versions REMPLACÉES, immuables (APK + empreintes
    sur le Drive ; empreintes seules dans le git public) ;
  - `documents/` — rapports d'audit (`audit-report.txt` client ;
    `nyx-admin-audit-report.txt` sur le Drive) et documents joints.
- **Git public** : `livrables/` versionné SÉLECTIVEMENT — `.gitignore` accepte
  la structure (`*.md`/`*.txt` : README, empreintes, rapports) et exclut les
  binaires (`*.apk`/`*.zip`) ; les binaires voyagent par les Releases GitHub
  (§9.6) et le Drive. `livrables/README.md` documente l'organisation.
- **Drive** : dossier **`/livrables`** (« Messagerie » ; référence exacte :
  dépôt privé `nyx-admin`, `ops/INFRA.md`) — même arborescence ; `sync.sh`
  propage par upload récursif IMMUABLE (nom + taille identiques → skip).
- **Publication Drive SYSTÉMATIQUE à chaque release — `scripts/publish-drive.sh`
  (rév. session 18, demande opérateur : « il faut faire ça systématiquement à
  chaque release »)** : à CHAQUE version livrée (client OU console admin), le
  script 1) ARCHIVE dans `archives/…` tout livrable de la MÊME famille encore
  dans `dernieres-livrables/` dont la version n'est plus la version publiée
  (client → `archives/v<ancienne>/`, admin → `archives/nyx-admin-v<ancienne>/`),
  puis 2) TÉLÉVERSE le nouveau jeu dans `dernieres-livrables/` (uploads
  immuables nom+taille → skip ; `SHA256SUMS-latest.txt` = fichier MUTABLE par
  définition — jamais d'upload immuable pour lui) ; les documents (`--doc`)
  vont dans `documents/`. Appelé AUTOMATIQUEMENT par `release.sh` (famille
  client, étape 6) ; famille admin = invocation directe depuis le dépôt privé
  (`scripts/publish-drive.sh --kind admin --version X.Y.Z --files …`).
  Idempotent : relancé à l'identique, il n'archive rien et saute les uploads
  déjà présents. SKIP propre si credentials Drive absents (livrables en
  attente dans le miroir local, relance plus tard). `sync.sh` est aligné.
- **Archivage automatique côté build** : à chaque build, `build-apk.sh` déplace
  la version remplacée de `dernieres-livrables/` vers `archives/v<version>/`
  AVANT de déposer la nouvelle ; la console admin suit la même convention via
  `publish-drive.sh` (fin du rangement manuel).
- Vérification d'intégrité distante : `gdrive.py cat livrables/…` (contenu du
  fichier distant — comparer à l'empreinte locale).
- Nommage : `nyx-v<versionName>-<AAAAMMJJ-HHMMSS>-release.apk` (inchangé).

### 9.5 Hygiène permanente (rappel durci)

- Secrets HORS dépôt : `.credentials/` (workspace), `android/keystore.properties`,
  `*/local.properties`, tout `*.jks`/`*.keystore` — le `.gitignore` les couvre et
  l'audit (SEC-*) échoue sinon.
- Le keystore release ET ses mots de passe doivent exister à deux endroits : workspace
  courant + dossier Drive dédié **« Tokens »** (§6). En cas de perte : nouvelle clé =
  nouvelle signature = désinstallation obligatoire pour les utilisateurs.

### 9.6 Releases GitHub — TOUTE nouvelle version y est publiée (session 8+)

> Instaurée à la demande utilisateur (session 8) : « une section releases dans le
> git où tu déposeras les nouvelles versions ».

- **Où** : `github.com/alexisfrlopez-debug/nyx-messenger/releases` — c'est le
  canal de distribution des versions (en complément du /livrables Drive, §9.4).
- **Quoi** : chaque version livrée par `build-apk.sh` donne lieu à une **release
  GitHub** contenant : l'APK signé (asset), les empreintes (`SHA256SUMS-vX.Y.Z.txt`),
  le rapport d'audit (`audit-report-vX.Y.Z.txt`), et des notes de version.
- **Comment** : `scripts/release.sh --version X.Y.Z [--commit <sha>] [--prerelease]
  [--title "…"]` — crée/pousse le tag annoté `vX.Y.Z`, crée la release via l'API
  (idempotent : relancé, il réutilise la release existante), téléverse les assets.
- **Notes de version** : `docs/releases/vX.Y.Z.md` (versionnées dans le dépôt) —
  correctifs, contenu, bugs connus. Une release sans notes est un livrable incomplet.
- **Convention** : la release la plus récente NON pré-release est la version
  courante (« Latest » affichée sur le dépôt) ; une version à bug bloquant connu
  est (re)marquée pré-release avec le bug documenté et le renvoi vers la version
  corrigée (ex. v0.1.0).
- **Ordre du cycle de livraison** (règle de bout en bout) : audit §9.1 → build
  §9.2 → livrables locaux §9.4 → **release GitHub §9.6** → synchro Drive §9.3.
- **Exception (session 11)** : les livrables de la console **nyx-admin** ne
  passent PAS par une release GitHub (régle §9.7 — dépôt privé) : distribution
  directe dans `/livrables` (Drive), avec SHA256SUMS + rapport d'audit dédiés.

### 9.7 Hygiène du dépôt public — zéro info personnelle, admin séparé (session 10+)

> Instaurée à la demande utilisateur (session 10) : « les applis admin ne doivent
> pas être publiques sur git, et aucune info personnelle ne doit exister sur le
> git public (drive, mail, etc.) ».

- **Deux dépôts, deux visibilités** :
  | Dépôt | Visibilité | Contenu autorisé |
  |---|---|---|
  | `nyx-messenger` | **public** | code client/serveur, docs génériques, ADR résumés, releases APK, notes de version |
  | `nyx-admin` | **privé** | spec console admin (endpoints privilégiés), **code console (`:core`/`:desktop`/`:android`, session 11)**, pipeline de build admin, `ops/INFRA.md` : IDs d'infrastructure, inventaire credentials, procédure de restauration complète |
- **Interdit sur le dépôt PUBLIC** : tout identifiant d'infrastructure (IDs de
  dossiers Drive, numéros de projet GCP, URLs privées), toute info personnelle
  (email, nom réel, téléphone), toute référence au dossier « Tokens » autre que
  par NOM de dossier. `scripts/gdrive.py` : **aucun ID en dur** — résolution par
  chemin de noms (`z.ai/Messagerie`), override `NYX_ROOT_FOLDER_ID` si besoin.
- **Commits** : identité git neutre (`Z User <z@container>` — vérifier avant tout
  premier push d'un workspace neuf ; JAMAIS d'email personnel).
- **Fichiers des opérations sensibles** : ne jamais les créer sous `nyx/` — les
  créer dans `/home/z/my-project/nyx-admin/` (clone du privé) ou hors dépôt.
- L'audit §9.1 (SEC-*) complète cette règle : aucun secret dans le dépôt public ;
  `docs/APPLI_ADMIN.md` et tout doc d'endpoints privilégiés vivent au privé.
