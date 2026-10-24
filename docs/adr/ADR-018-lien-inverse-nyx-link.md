# ADR-018 — NYX-LINK : lien inversé (le téléphone ne reçoit plus d'appels entrants Tor)

- **Statut** : ACCEPTÉ (session 68 — 2026-10-24)
- **Demande opérateur (verbatim)** : « je veux réglé le probleme "oignon
  entrant injoignable" qui apparait sur les replicas embarqués dans la
  console / serais-tu capable de créer ton propre protocole pour régler ça ?
  / serait-il possible que le problème récurrent de décrochage d'appels
  impossible soit lié ? (c'est toujours le cas actuellement) »
- **Décision** : NYX-LINK, protocole propriétaire d'**inversion de connexion**
  — le téléphone ne dépend plus de sa joignabilité ENTRANTE Tor pour servir
  son réplica embarqué. Il maintient un **canal sortant persistant**
  (long-poll HTTP) vers un réplica de flotte ; tout demandeur (console, pair,
  membre de la flotte) dépose sa requête sur ce réplica et y attend la
  réponse. Le réplica de flotte devient un **routeur applicatif** entre
  demandeurs et téléphones.

## 1. Contexte — le constat structurel (sessions 41 → 67)

Sept sessions de correctifs (v0.2.37 → v0.2.45) ont éradiqué les bugs
APPLICATIFS autour de l'onion embarqué : collision 550 adoptée, watchdogs
dé-doublés, republication destructrice supprimée, cache client guéri
(DescriptorHeal), backends morts relancés (chiens de garde OOM), serveur
Déployé avec Detach. **Il reste l'invivable structurel** : un service onion
hébergé dans un téléphone Android n'est pas un service joignable. Les logs
terrain du 23/10 (session 67, 4 exports v0.2.44-test + console v0.8.10)
prouvent la chronologie :

- 12:52:16 — le Tor du téléphone publie l'onion (`ports=80→33463,
  47471→35355`), intros établis (4/3), uploads initiés vers les HSDirs ;
- 12:52:52 → 14:13:22 — **1 h 20 d'injoignabilité CONTINUE** de l'onion
  (`SOCKS server general failure`, `Connect timed out`) malgré le service
  vivant, sur les DEUX téléphones (5G ET WiFi) ;
- 12:58:59 — un appel ENTRANT parvient pourtant au téléphone (la
  signalisation passe par le journal du réplica de flotte — chemin SORTANT) ;
- 12:59:13 — l'utilisateur décroche : **« acceptation utilisateur (média pas
  encore arrivé) »** — le média direct exige l'onion du pair (`:47471`),
  injoignable, et le relais `/call/relay` v0.2.44 était INERT (le serveur de
  flotte ne serait déployé en 0.2.36+ qu'à 19:03 le même jour).

La réponse à la question de l'opérateur est donc : **OUI, le décrochage
impossible est directement lié à l'oignon entrant injoignable** — la
signalisation passe (canal sortant), le média non (il exige le canal
entrant). Le relais média v0.2.44 (modèle TURN, `/call/relay`) traite déjà le
volet appel dès lors que le serveur de flotte est à jour ; NYX-LINK traite le
volet général : **rendre le réplica embarqué servi sans AUCUNE joignabilité
entrante**.

L'asymétrie exploitée : sur le terrain, les connexions **sortantes** du
téléphone (vers les réplicas de flotte) fonctionnent dans 100 % des
observations (journal, `/health` 375 ms, annonces, XMPP) alors que
l'entrée est chroniquement morte. C'est la caractéristique fondamentale des
réseaux mobiles (CGNAT, Doze, churn de réseau) — la même que celle qu'ont
résolue les protocoles d'inversion : XMPP bidirectionnel par flux sortant,
Briar mailbox, TURN over TCP.

## 2. Le protocole

### 2.1 Acteurs

| Rôle | Qui | Ce qu'il fait |
|---|---|---|
| **Routeur** | un réplica de flotte (VPS, descripteur stable) | héberge les files NYX-LINK, les long-polls |
| **Serveur-lien** | un téléphone Nyx (réplica embarqué actif) | tire ses requêtes (`/link/pull`) et répond (`/link/reply`) |
| **Demandeur** | console admin, pair, membre de flotte | dépose (`/link/send`) et attend (`/link/wait`) |

### 2.2 Routes (mux client-auth Tor — comme toutes les routes client)

1. `POST /link/send` — corps `{"to","id","method","path","body_b64"}`.
   Validation : `to` = onion v3 bien formé ; `id` = hex 16-64 unique ;
   `method` ∈ {GET, POST} ; `path` **whitelisté** : `/health`, `/client/event`,
   `/client/inbox`, `/client/profile`, `/client/alias` (le lien n'est PAS un
   proxy ouvert) ; corps ≤ 256 Ko (base64). File du destinataire bornée à
   **8 requêtes**, TTL **90 s** (drop silencieux au-delà). → `202 {"id":…}`.
2. `GET /link/pull?me=<onion>&auth=<ts:sig>` — **long-poll 45 s** du
   téléphone. `auth` = signature ed25519 du détenteur sur le canon
   `NYX-LINK-PULL\n<me>\n<ts>` (fenêtre ±300 s), vérifiée par dérivation
   `OnionToPubKey(me)` — le routeur ne connaît rien du membre. Répond dès
   qu'une requête attend (max 4 par lot, ordre FIFO) : `{"reqs":[{id,
   method, path, body_b64}…]}` ou `{"reqs":[]}` au timeout (keepalive). Un
   nouveau PULL remplace le précédent du même `me` (anti-zombie).
3. `POST /link/reply` — corps `{"id","from","status","body_b64","auth"}`.
   `auth` = signature du répondant sur `NYX-LINK-REPLY\n<id>\n<from>\n<status>
   \n<sha256(body_b64)>` (le canon couvre le HASH du corps — pas besoin de
   signer deux fois le payload). Réponse stockée TTL **120 s**, **une** par
   id (la première gagne). → `202`.
4. `GET /link/wait?id=<hex>` — **long-poll 75 s** du demandeur. →
   `200 {"status":…,"body_b64":…}` dès l'arrivée de la réponse, ou
   `408 {"error":"délai dépassé"}`.

### 2.3 Sécurité et anonymat

- **Tout le trafic reste dans Tor** (SOCKS des deux bouts) + client-auth du
  mux : le routeur ne voit que du ciphertext, exactement comme pour
  `/client/event` — les corps `/client/*` sont chiffrés E2E (msg) ou signés
  (inbox), et le **routeur local** du téléphone (EmbeddedReplica.route)
  re-valide signature/quotas/whitelist à l'exécution : le lien n'ajoute
  AUCUNE confiance, il ne fait qu'acheminer.
- PULL/REPLY authentifiés par signature (détenteur de l'adresse prouvé) ;
  SEND/WAIT non authentifiés mais gated client-auth + bornés (file 8, TTL,
  corps 256 Ko) — même modèle de confiance que le dépôt d'événements.
- Le routeur ne peut pas altérer les échanges sans détection : signatures
  E2E existantes couvrent les payloads applicatifs.
- Anonymat du demandeur préservé (pas d'identité exigée au SEND).

### 2.4 Politique de repli (messager)

L'onion direct reste le chemin PREMIER (latence minimale). NYX-LINK n'intervient
qu'en **repli réseau** : quand une requête vers le réplica embarqué d'un PAIR
échoue par transport (timeout/SOCKS), le client REJOUE la requête par le lien
inversé **via un AUTRE réplica sain** (jamais le pair lui-même — cf.
`relayHintFor`). Succès → réponse traitée à l'identique. Échec → failover
existant (markReplicaFailed).

### 2.5 Ce que NYX-LINK ne fait PAS (périmètre)

- Il ne remplace PAS le journal répliqué (dépôt durable) — il achemine des
  requêtes HTTP vers les réplicas embarqués.
- Il ne porte PAS le média d'appel (le relais `/call/relay` v0.2.36+ le fait
  en pipe d'octets, plus efficace pour 20-40 ms/trame).
- Il ne révèle PAS plus que le terrain : le routeur voit « un demandeur
  quelconque a parlé au téléphone X » — identique à ce que le réplica voit
  déjà pour chaque dépôt.

## 3. Conséquences

- **Console** : la sonde des réplicas embarqués gagne un repli lien inversé —
  la carte peut passer de « INJOIGNABLE » à **« EN LIGNE (via lien inversé) »**
  avec version/latence RÉELLES du téléphone — l'affichage demandé par
  l'opérateur (« régler le problème dans la console »).
- **Messager** : dépôts et polls vers les réplicas de PAIRS survivent à
  l'injoignabilité entrante ; `LinkChannel` (thread daemon) entretient le
  canal quand Tor est prêt.
- **Serveur** : 4 routes mémoire-bornées, refusées en maintenance, TTL courts
  — surface comparable à `/call/relay`.
- Coût : le téléphone maintient UNE requête en vol permanente (long-poll 45 s
  renouvelé) — négligeable devant le poll inbox existant (même cadence).
- Limite honnête : si le téléphone est tué (Doze profond/app fermée), le
  canal meurt — NYX-LINK ne rend pas un téléphone éteint joignable, il rend
  un téléphone VIVANT joignable malgré son onion cassé.
