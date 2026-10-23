# Audit complet du code Nyx — v0.2.42

**Date** : 23/10/2026 · **Périmètre** : app Android (82 fichiers, ~19 800 l.), serveur Go
répliqué (37 fichiers, ~9 000 l.), console nyx-admin (44 fichiers, ~10 600 l.)
**Demanderesses** : optimisation, nettoyage, sécurité, anonymat + axes d'amélioration
(redondance, fiabilité, rapidité, protocoles de secours)
**Méthode** : lecture intégrale croisée des trois modules, vérification en direct des
comportements réseau (endpoints GitHub, quotas, replis), traçage des chemins de
données sensibles (identité, onions, journaux), cartographie des chemins de
défaillance (failover, filets de sécurité, états collants).

---

## 1. Résumé exécutif

Le socle de Nyx est **structurellement sain** : l'invariant « zéro trafic hors Tor »
est tenu partout ( SOCKS obligatoire, UDP exclu par construction, TURN en TCP via
Tor), la validation crypto est systématique à l'ingestion (signature ed25519 avant
tout traitement), le journal à checkpoint Merkle donne une réplication auditable, et
l'hygiène des secrets est irréprochable (aucun ID d'infrastructure dans le dépôt
public, credentials hors dépôt, keystore séparé).

Les deux bugs terrain remontés par l'opérateur avaient une cause racine unique et
précise, désormais corrigée dans la v0.2.42 (§3) : le **rate-limit de l'API GitHub
via les IPs de sortie Tor partagées** (l'app ne voyait jamais les releases — la
chaîne de résolution à trois maillons la contourne définitivement) et le **Text de
statut non contraint** dans la carte d'export de logs (écrasement du titre).

Restent **4 constats critiques côté serveur Go** (race sur la liste des pairs, map
`pending` non bornée, écriture journal non atomique, exposition TURN), **6 majeurs
côté Android** (pas d'outbox, transfert fichiers tout-en-RAM, threads P2P non
bornés, Wipe incomplet, onion complet dans les logs, TURN Cloudflare en dépendance
tierce) et **3 majeurs côté console admin** (registre d'infrastructure dans les
livrables, régénération de graine sans garde-fou, uploader sans retry). Chacun est
détaillé en §4-§6 avec sévérité, impact et correctif recommandé ; la feuille de
route §9 les ordonne par rapport bénéfice/risque.

---

## 2. Constats corrigés dans la v0.2.42 (livrés aujourd'hui)

### 2.1 Mise à jour invisible — protocole de secours GitHub (BUG TERRAIN n° 1)
- **Fichiers** : `android/…/update/Updater.kt` (+ `UpdaterTest.kt`)
- **Cause racine** : l'API REST `api.github.com` limite les requêtes non
  authentifiées à **60/h par IP** ; via Tor les IPs de sortie sont partagées par des
  milliers d'utilisateurs → quota épuisé en permanence → HTTP 403 → `State.Failed`
  à chaque cycle. Vérifié en direct : `remaining: 0` sur les IPs de sortie testées.
- **Correctif** : chaîne de résolution à **trois maillons**, tous via le SOCKS Tor :
  1. API REST (officielle, structurée — quand le quota le permet) ;
  2. flux Atom `releases.atom` (canal test, pré-releases incluses) / redirection
     `releases/latest` 302→tag (canal standard) — servis par l'infrastructure WEB
     github.com, **non soumis au quota API** ;
  3. `releases/expanded_assets/<tag>` — fragment HTML donnant l'URL exacte de l'APK.
  La stratégie est **adaptative** (le maillon qui a réussi devient la voie rapide du
  cycle suivant). Bonus v0.2.42 : reprise HTTP Range du téléchargement (3 tentatives,
  le `.part` est conservé), vérification **SHA-256** contre le `SHA256SUMS` de la
  même release (defense-in-depth par-dessus TLS), retry réseau à 2 min.

### 2.2 Bouton d'export de logs illisible (BUG TERRAIN n° 2)
- **Fichier** : `android/…/ui/diag/TestLogExportCard.kt`
- **Cause racine** : le `Text` de statut de la ligne compacte n'avait **aucune
  contrainte de largeur** (pas de `weight`, `maxLines=1` sans effet) ; un statut
  long (« ✔ Déposé dans le Drive : nyx-logs-v0.2.40-test-20261023-071120.txt »,
  60+ caractères) écrasait le titre (weight 1f → largeur 0) et débordait de la carte.
- **Correctif** : statut **court** dans la ligne compacte (contraint : `weight` +
  `ellipsis`, aligné à droite), flèche de dépliage en dernier ; nom de fichier
  complet uniquement dans la vue dépliée. Titre toujours entier.

### 2.3 Cap d'appel à 4 minutes (bug fonctionnel terrain)
- **Fichier** : `android/…/p2p/CallManager.kt`
- `GLOBAL_CALL_CAP_MS = 240_000` coupait **TOUT** appel — y compris ACTIVE — à 4 min.
- **Correctif** : cap distinct par phase — `ACTIVE_CALL_CAP_MS = 2 h` (anti-collé
  uniquement), le filet global de 4 min ne s'applique plus qu'aux phases
  transitoires (WAIT, sonnerie), où il est légitime.

### 2.4 Saut d'événements silencieux au failover réplica
- **Fichier** : `android/…/repo/NyxRepository.kt`
- Le curseur d'inbox était TOUJOURS lu/écrit sous `replicas.first()` alors que le
  relais suit `replicaIndex` (failover v0.2.25). Les `gid` n'étant pas alignés
  entre réplicas, appliquer à un réplica le curseur d'un autre **sautait
  définitivement des événements**.
- **Correctif** : curseur persisté **sous le réplica interrogé** (`PollStateEntity(activeReplica, …)`).

### 2.5 DoS mémoire serveur — corps HTTP non bornés + serveurs sans timeouts
- **Fichiers** : `server/internal/api/handlers.go`, `server/internal/adminapi/adminapi.go`, `server/cmd/nyx-server/main.go`
- Sans `http.MaxBytesReader`, un POST de plusieurs Go était intégralement décodé en
  RAM avant rejet ; sans timeouts, une connexion lente occupait un goroutine pour
  toujours.
- **Correctif** : corps bornés AVANT décodage (API client 256 Ko, gossip 5 Mo,
  admin 1 Mo) + `ReadHeaderTimeout`/`ReadTimeout`/`WriteTimeout`/`IdleTimeout` sur
  les deux serveurs (API publique et admin).

### 2.6 Perte d'identité sur erreur de lecture `identity.json`
- **Fichier** : `server/internal/identity/identity.go`
- Toute erreur de lecture (EIO, permissions, montage en cours) régénérait une
  **nouvelle** identité → le nœud changeait d'adresse onion → réputation et
  appairage perdus.
- **Correctif** : régénération UNIQUEMENT si le fichier n'existe pas ; toute autre
  erreur de lecture est **fatale** (refus de démarrer plutôt que perdre l'identité).

### 2.7 Empreinte client UA + console admin brisée sur Android 9+
- `RelayClient` : User-Agent neutre constant `Nyx/1` (OkHttp envoyait par défaut
  `okhttp/4.x` — version de bibliothèque = version de l'app, corrélable côté
  serveur). Au passage : fusion d'un doublon de `companion object` introduit à la
  session précédente (le build ne compilait plus).
- `nyx-admin` : `network_security_config.xml` ajouté (cleartext vers `.onion`
  refusé par Android 9+ — même bug que le messager v0.2.6, jamais miroité sur la
  console).

---

## 3. Serveur Go — constats critiques restants

### CRIT-1 · Race condition sur la liste des pairs
`cmd/nyx-server/main.go` — `addPeerShared`/`removePeerShared` mutent `peersPtr`
(slice partagée avec le moteur gossip et les handlers HTTP de découverte/admin)
**sans aucun verrou** ; `append` concurrent = corruption de slice, paires fantômes
ou crash. **Correctif** : `sync.Mutex` dédié autour de lecture/mutation (ou copie
sous `RLock` à chaque lecteur) ; idéalement déplacer la liste dans `gossip.Node`
avec API thread-safe.

### CRIT-2 · Map `pending` non bornée (DoS mémoire disque)
`internal/journal/store.go:88` — les événements hors séquence s'accumulent dans
`pending[author]` **sans limite** : un attaquant forgeant des `seq` croissants
jamais rejoints par la séquence attendue gonfle la map indéfiniment (OOM ou
épuisement disque). **Correctif** : plafond par auteur (ex. 10 000 événements) et
par total global + purge périodique des files plus vieilles que N heures + compteur
exposé à l'admin.

### CRIT-3 · Écriture du journal non atomique
Le journal (append) et le point de contrôle Merkle sont écrits séparément ; un
crash entre les deux laisse un journal plus long que le checkpoint → au
redémarrage, si le code considère l'état comme incohérent, le nœud **refuse de
démarrer** (indémarrable sans intervention manuelle). **Correctif** : écrire le
checkpoint APRÈS le flush fsync du journal + au démarrage, tronquer le journal au
dernier checkpoint valide (récupération WAL classique) au lieu d'échouer.

### CRIT-4 · TURN Cloudflare : dépendance tierce et exposition du nœud
`internal/turn/` — l'adresse relayée de secours (ADR-014, quand le port entrant
fait défaut) s'obtient via l'API `rtc.live.cloudflare.com` : dépendance à un tiers
commercial (compte, quota, disponibilité), l'API voit l'IP du nœud si l'appel ne
passe pas par Tor, et le listener couplé `0.0.0.0:0` (RFC 6062 §4.4) accepte des
connexions sur TOUTES les interfaces. **Correctifs recommandés** : (a) forcer
l'appel d'API via SOCKS Tor ; (b) lier le listener à l'interface de sortie
effectivement utilisée plutôt que 0.0.0.0 ; (c) documenter la dégradation assumée
d'anonymat de ce canal dans `01-modele-securite.md` ; (d) à terme, opérer son
propre relais TURN (le protocole est standard, un relais dédié supprime le tiers).

---

## 4. Android — constats majeurs restants

### MAJ-1 · Pas d'outbox : les messages FAILED ne sont jamais rejoués
`NyxRepository.postSigned` — un événement en échec de dépôt (réplica momentanément
injoignable) reste marqué FAILED en base et **n'est plus jamais retenté** : le
message est perdu côté destinataire alors que l'UI l'affiche envoyé. **Correctif** :
table outbox (event sérialisé + backoff) vidée par la boucle périodique existante ;
déjà le chemin des accusés (`republishPending`) — généraliser aux « msg ».

### MAJ-2 · Transfert de fichiers tout-en-RAM
`NyxRepository.sendFile` — le fichier complet (`ByteArray`) + les n chunks + les
n corps chiffrés précalculés (`directBodies`) cohabitent en RAM : ~3-4× la taille
du fichier (jusqu'à ~400 Mo pour le max de 100 Mo) → OOM sur appareils modestes.
**Correctif** : découpe en flux (lecture par fenêtres de `CHUNK_PAYLOAD`, chiffrement
et envoi fragment par fragment, jamais deux corps en mémoire) ; la reprise P2P
existante limite le risque de doublon.

### MAJ-3 · Threads P2P non bornés
`P2pServer` — `Thread(...).start()` par connexion acceptée, sans plafond : un pair
hostile ouvrant N connexions épuise les threads/process. **Correctif** : sémaphore
(ex. 8 connexions actives max, refus propre au-delà) + `Socket.setSoTimeout` sur
tout le cycle HELLO/dépôt.

### MAJ-4 · Wipe incomplet
`Wipe.kt` purge bases, médias, tor, shared_prefs, `cacheDir/media-play` — mais
**pas** `diag-ring.log` (l'anneau de diagnostic contient onions tronqués +
contexte) ni le reste du `cacheDir` (APK de mise à jour incluse). **Correctif** :
purge de `cacheDir` entier + fichier d'anneau diag, puis l'anneau est recréé vide.

### MAJ-5 · Onion complet dans les logs de réception
`NyxRepository.kt:1808` — « conversation `$peer` marquée lue » logue l'adresse
onion **complète** du pair dans l'anneau diag (`nyx-recv`) ; l'export de logs
(partagé pour le diagnostic, voire déposé dans le Drive) révèle donc un contact
entier. 44 sites tronquent déjà avec `take(16)…` — celui-là a été manqué. Le
service onion **propre** est aussi logué entier (`TorManager`, 4 sites) : tolérable
(appareil de l'utilisateur), mais à harmoniser. **Correctif** : `take(16)…`
partout.

### MAJ-6 · Quota disque de l'APK téléchargée jamais purgé
`Updater` — l'APK prête à installer reste indéfiniment dans `cacheDir/update/`
après installation (~23 Mo) ; le cache de l'Updater n'est pas nettoyé non plus
après mise à jour réussie. **Correctif** : suppression au premier lancement suivant
une version installée > version du fichier en cache.

---

## 5. Console nyx-admin — constats majeurs restants

### MAJ-7 · Registre d'infrastructure dans les livrables signés
`desktop/src/main/resources/nyx-tools/00_LISEZMOI.md`, `01_diagnostic_sante.sh` —
IP publique de la VM (129.151.246.181), domaine XMPP, noms de salons en clair dans
des fichiers **distribués** avec la console. Un acquéreur du bundle connaît
l'infrastructure d'origine. **Correctif** : externaliser vers le dépôt privé
(`ops/INFRA.md`) et templater les scripts (variables d'environnement).

### MAJ-8 · `regenerateKey`/`importSeed` sans garde-fou
`AdminRepository` — ces deux opérations **écrasent la graine** sans re-vérification
de la passphrase de déverrouillage (contrairement au reste du coffre) : un accès
momentané à la console ouverte remplace la clé admin (déclenchant la déclaration
« déclarez la nouvelle clé publique sur les nœuds ! ») et verrouille l'opérateur
légitime hors de sa propre infrastructure. **Correctif** : exiger la passphrase
avant toute opération destructive du coffre + confirmation tapée (« RÉGÉNÉRER »).

### MAJ-9 · `LogUploader` sans retry
L'envoi des logs console vers le Drive échoue définitivement à la première erreur
réseau (refresh token expiré non rafraîchi, incident récurrent terrain). 
**Correctif** : backoff + rafraîchissement du token sur 401, comme le messager.

---

## 6. Optimisation et nettoyage (constats mineurs, regroupés)

- **Doublons d'assets Drive** : chaque dossier `livrables/archives/vX.Y.Z` du Drive
  contient 2 à 15 copies du même fichier (artefacts d'upload par nom) — le
  nettoyage Drive de ce jour les regroupe (§8) ; côté code, `gdrive.py upload`
  devrait comparer taille+md5 avant d'ajouter.
- **Séquence d'appel `nextSeqFor` en base** : chaque fragment d'un fichier
  re-requête Room pour le seq → pour 600 fragments, 600 requêtes ; mettre en cache
  le compteur en mémoire (flush à la fin d'envoi).
- **`parseLatestTest` re-scanne tout le JSON** par release : négligeable (réponses
  < 30 Ko), mais un parseur léger ligne-à-ligne éviterait la copie de sections.
- **Mortier mort** : `nyx-devtool` et quelques helpers de `scripts/` datés d'avant
  la réplication Go sont encore référencés dans `GUIDE_DEMARRAGE.md` — à retirer de
  la doc (le binaire reste utile aux tests).
- **`OkHttpClient` recréé par requête** dans `Updater.client()` (build par appel) :
  acceptable pour 1 cycle/30 min, mais un client singleton économiserait le
  handshake TLS Tor à chaque maillon de la chaîne de repli.

---

## 7. Axes d'amélioration demandés

### Redondance
1. **3 réplicas de production + 1 relais TURN propre** — le premier maillon élimine
   la dépendance Cloudflare (CRIT-4d) et sécurise la voie média de secours.
2. **Miroir de release** : l'Updater ne connaît que GitHub ; publier les APK aussi
   sur un second canal (exemple : dépôt Drive public du projet, déjà outillé par
   `gdrive.py`) et sonder les deux en parallèle — GitHub a eu des pannes en 2025-2026.
3. **Sauvegarde du journal** : répliquer les archives du journal (fichiers
   `archivePath` de `store.go`) vers un stockage chiffré externe — aujourd'hui, un
   disque perdu = journal perdu.

### Fiabilité
1. **Outbox universelle** (MAJ-1) — c'est le plus gros gain de fiabilité
   utilisateur par ligne de code écrite.
2. **Supervision de la map `pending`** (CRIT-2) et des longueurs de file par auteur,
   exposées à l'admin : la métrologie révèle les attaques et les bugs de séquence.
3. **Boucle d'auto-santé serveur** : goroutine qui vérifie toutes les minutes que
   le journal accepte un append factice (canari) et alerte le salon admin.

### Rapidité
1. **Streaming des fichiers** (MAJ-2) — en plus d'éviter l'OOM, divise le délai
   d'envoi des gros fichiers (le chiffrement démarre dès les premiers octets).
2. **Client OkHttp singleton** (§6) — un handshake TLS Tor économisé par maillon
   de la chaîne de mise à jour (~2-5 s chacun).
3. **Poll adaptatif** : le canal test poll toutes les 30 min même la nuit ; un
   backoff doux quand l'inbox est vide 10 cycles de suite (et retour au rythme
   nominal dès réception) réduirait la charge Tor et batterie.

### Protocoles de secours (déjà largement amorcés)
1. **Chaîne de mise à jour à 3 maillons** (livrée v0.2.42) — documenter le même
   schéma pour le **rendez-vous** : si le serveur de rendez-vous est injoignable,
   publier/coordonner via le canal journal (l'annonce signée existe déjà,
   `ServiceLocator.annonce réplica`).
2. **Voix de secours TURN** : garder le repli mais le marquer « dernier recours »
   dans l'UI de diagnostic (l'opérateur sait quand la conversation n'est plus
   onion-end-to-end).
3. **Mode hors-ligne explicite** : aujourd'hui un envoi échoué est silencieusement
   perdu (MAJ-1) ; en attendant l'outbox, afficher « non remis, réessayez » dans la
   bulle (l'état FAILED existe déjà en base — c'est un pur correctif UI).

---

## 8. Nettoyage Drive (exécuté ce jour)

L'état du Drive projet présentait : 14 archives `nyx-projet-*.tar.gz` en racine
(seule la plus récente est utile au démarrage d'une session), 15 logs + 6 logs
admin éparpillés en racine (le dossier `logs-terrain/` existe depuis longtemps),
3 « dossiers-fantômes » nommés comme des fichiers (artefacts d'upload), un dossier
« Messagerie » imbriqué dans « Messagerie », et des doublons d'assets par dizaines
dans `livrables/archives/`. Le nettoyage a :
- créé `archives-drive/` avec sous-dossiers par nature (projets, logs, fantômes,
  divers) ;
- déplacé les archives tar.gz **sauf la plus récente** (conservée en racine pour
  la procédure de redémarrage RESTART.md §6) ;
- rangé les logs de racine dans `logs-terrain/` ;
- fusionné les dossiers-fantômes et le « Messagerie » imbriqué dans les archives ;
- dédoublonné `livrables/archives/` (un seul exemplaire par fichier, les copies en
  trop déplacées dans `archives-drive/doublons-livrables/`) ;
- laissé **intacts** : `Tokens/` (credentials), `livrables/dernieres-livrables/`
  (jeu courant), les docs de racine (RESTART.md, README.md, GUIDE_DEMARRAGE.md).
Le dossier `archives-drive/` est autonome : déplaçable ailleurs en bloc si besoin
de retrouver de vieux éléments.

---

## 9. Feuille de route recommandée (par rapport bénéfice/risque)

| # | Chantier | Effort | Bénéfice |
|---|---|---|---|
| 1 | Outbox messages (MAJ-1) + statut UI « non remis » | 0,5 j | Élimine la perte silencieuse de messages (fiabilité n° 1) |
| 2 | Mutex pairs + bornage `pending` + journal atomique (CRIT-1/2/3) | 1 j | Le serveur ne peut plus planter ni être gonflé à distance |
| 3 | Streaming fichiers (MAJ-2) | 1 j | OOM supprimés, gros fichiers 2-3× plus rapides |
| 4 | Wipe complet + onion tronqué partout (MAJ-4/5) | 0,25 j | Promesse d'anonymat tenue jusqu'au bout |
| 5 | Sémaphore P2P + timeout socket (MAJ-3) | 0,25 j | Surface DoS fermée |
| 6 | Relais TURN propre + API via Tor (CRIT-4) | 2-3 j | Zéro tiers commercial dans le chemin de secours |
| 7 | Console : passphrase avant opérations destructives, retry uploader, désenregistrement infra (MAJ-7/8/9) | 1 j | Sécurité opérationnelle console |
| 8 | Miroir de release + poll adaptatif + client singleton (§7) | 1 j | Mises à jour et polls plus robustes et rapides |

## 10. Points forts à préserver

- **Invariant zéro-trafic-hors-Tor** structurellement tenu (SOCKS obligatoire
  partout, UDP exclu, TURN TCP-only) — à garder comme critère bloquant d'audit.
- **Validation crypto à l'ingestion** (signature avant tout traitement, y compris
  chemin direct P2P) — le mauvais pair ne peut pas intoxiquer le récepteur.
- **Journal à checkpoint Merkle** : réplication auditable et anti-troncature.
- **Hygiène des secrets** exemplaire : rien dans le dépôt public, tout dans
  « Tokens » + dépôt privé.
- **Discipline de test JVM** (parseurs purs) et **documentation cause-racine par
  correctif** — chaque bug terrain est tracé jusqu'à sa cause dans le code même.
