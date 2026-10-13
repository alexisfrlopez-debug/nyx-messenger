# ADR-014 — Transports alternatifs « commodity » pour les réplicas : XMPP MUC et relais TURN

Statut : **Accepté** (2026-10-13, demande opérateur session 17)

## Contexte

ADR-009 fixe l'hébergement des réplicas : un réplica Nyx est **exclusivement** un
hidden service v3 publié par son hôte, joint via Tor. Ce modèle fonctionne dès lors
que l'hôte peut (a) ouvrir une connectivité sortante vers Tor et (b) être atteint
par les clients via l'onion. Deux demandes de l'opérateur (session 17) remettent
en question la simplicité de cette mise en œuvre pour des hébergements gratuits :

1. **« est-il possible de déployer sur un serveur xmpp type hookipa.net ?
   je voudrais pouvoir le faire »** — hookipa.net est un fournisseur XMPP public
   gratuit (ejabberd, Class A sur providers.xmpp.net, 100 % au compliance tester,
   XEP 0045 MUC / 0313 MAM+MAM-MUC / 0160 / 0363 / 0421 / 0384 vérifiés). Il
   fournit des **comptes de chat**, pas un hébergement d'exécutables : il est
   impossible d'y faire tourner le binaire `nyx-server`, d'y publier un onion
   service ou d'y ouvrir un port. En revanche, un salon MUC (Multi-User Chat) est
   un **bus de diffusion persistant** joignable en **sortie seule** par quiconque
   possède un compte — exactement la primitive de transport qui manque aux hôtes
   qui ne peuvent rien exposer en entrant.
2. **« je regarde aussi pour utiliser un serveur TURN Cloudflare »** — clé TURN
   créée par l'opérateur (Token ID + API Token). TURN (RFC 5766/6062) est un
   relais TCP/UDP standard, joignable partout, qui permet à un nœud **sans port
   entrant et sans onion** de recevoir des connexions via une adresse relayée
   éphémère.

Le problème commun : l'onion v3 suppose un hôte capable de publier un hidden
service ; or les hébergements gratuits (dont les fournisseurs XMPP) n'offrent ni
shell, ni Tor control port, ni port entrant. Il manque un **transport de repli
« commodity »**, opt-in, pour que des réplicas tournent sur ces hôtes.

Invariants qui doivent survivre (ADR-002, ADR-003, ADR-009) :

- le serveur de transport ne doit **jamais** voir de clair (les enveloppes Nyx
  sont déjà signées ed25519 par leur auteur — authentification E2E indépendante
  du transport) ;
- l'identité d'un utilisateur reste **son adresse onion** (ed25519) ;
- l'onion v3 **reste le transport par défaut** des réplicas (ADR-009 inchangé) ;
- la pile Go reste **stdlib + x/crypto (+ x/net)** (ADR-005) — aucun SDK tierce
  (mellium, pion) ne sera embarqué.

## Décision

**Deux transports alternatifs OPT-IN, jamais par défaut :**

### 1. Transport XMPP MUC (phase 1 — implémenté cette session)

Le réplica (ou un client) joint un **salon MUC** d'un serveur XMPP quelconque et
s'en sert de bus de messages :

- les **enveloppes Nyx** (événements signés du journal — wire-format.md) sont
  encodées base64, préfixées `nyx1`, et publiées comme corps de messages
  `groupchat` du salon ;
- **MAM (XEP-0313)** sert de rattrapage : un nœud qui (re)joint le salon aspires
  l'historique et rejoue les événements dans son journal (dé-dup par `id` déjà
  native au `journal.Store`) ;
- le serveur XMPP ne voit que du **chiffré opaque signé** (il héberge le bus, pas
  le contenu) — ADR-002/009 préservés ; la signature ed25519 de chaque événement
  rend l'injection forgée impossible et le rejeu inerte (dé-dup) ;
- **bénéfice clé** : le réplica devient **outbound-only** (aucun port entrant,
  aucun onion obligatoire) → il tourne sur n'importe quel hôte gratuit, et le
  déploiement SSH ADR-013 se réutilise tel quel (binaire + `config.json`) ;
- les connexions XMPP peuvent passer **via le SOCKS5 de Tor** (`tor_socks`)
  vers le hostname clearnet du fournisseur (hookipa.net n'a pas d'onion public
  documenté) — Tor n'est donc plus le transport mais reste disponible comme
  canal d'accès anonymisé.

### 2. Transport relais TURN Cloudflare (phase 1 = credentials ; phase 2 = allocation)

- Un réplica derrière réseau bloquant obtient du **backend** (lui-même ou la
  console nyx-admin — **jamais l'APK**) des **credentials TURN éphémères** via
  l'API Cloudflare (`POST /v1/turn/keys/{key_id}/credentials/generate-ice-servers`,
  Bearer API Token, TTL court) → `iceServers` (STUN + TURN udp/tcp + **turns:5349
  TCP**, indistinguable de HTTPS) ;
- **phase 2** (spécifiée, non implémentée) : le réplica effectue une **allocation
  TCP RFC 6062** (commande `CONNECT`) chez le relais et publie l'**adresse
  relayée** dans le rendezvous signé ; les clients la joignent via
  `turns:5349` à travers le SOCKS5 de Tor (Tor = TCP only : UDP exclu) ;
- **compromis assumé et documenté** : Cloudflare observe IP/timing/volume du
  réplica → voie **OPT-IN**, jamais par défaut, jamais pour un opérateur menaçé
  par Cloudflare comme partie adverse.

### Règles transverses

1. **Défaut inchangé (ADR-009)** : `xmpp.enabled=false` et TURN désactivé par
   défaut ; aucun paquet Nyx n'émane d'un MUC tant que l'opérateur n'a pas
   explicitement configuré la section `xmpp` de `config.json`.
2. **Amendement ADR-005 (borné)** : le client XMPP est **écrit dans le dépôt**
   (`server/internal/transportxmpp/`) — XML streams, SASL PLAIN/SCRAM-SHA-1,
   bind, MUC, MAM — en stdlib + x/crypto **uniquement**. Motivation : un client
   XMPP est un protocole texte simple ; une dépendance tierce (mellium…) serait
   plus lourde à auditer que ~800 lignes maison, et l'exception « control port
   Tor maison » (ADR-005) a déjà établi ce précédent. Pion (WebRTC/TURN) est
   **écarté** : la phase 1 n'exige aucune allocation ICE.
3. **Secrets** : mot de passe XMPP, API Token Cloudflare → uniquement dans
   `data_dir/config.json` (ou variables d'environnement pour le CLI) — jamais
   dans le dépôt, jamais dans `config.example.json` (valeurs factices), jamais
   dans l'APK.
4. **JIDs pseudonymes dédiés** : le compte XMPP d'un réplica est un compte
   jetable créé pour le réseau Nyx, sans donnée personnelle ; le lien
   JID ↔ onion ne fait pas partie du filaire (il vit dans la config locale).

### Alternatives écartées

- **Héberger `nyx-server` « sur » hookipa.net** : impossible (fournisseur de
  comptes chat, pas d'exécution de binaires, pas de ports entrants).
- **Bridges Tor (obfs4/snowflake) à la place de TURN** : nécessite des bridges
  opérés par nous — c'est exactement l'infrastructure qu'on cherche à éviter ;
  TURN délègue le relais à un tiers gratuit existant.
- **Dépendre de mellium/pion** : surface d'audit et dépendances transitives
  incompatibles avec la règle ADR-005 (cf. amendement ci-dessus).
- **Faire du MUC le transport par défaut** : le serveur XMPP verrait
  métadonnées (JID, timing, taille) d'un réseau entier — l'onion reste
  supérieur quand il est disponible.

## Conséquences

- ✅ Un réplica peut tourner sur un hôte **gratuit sans port entrant** (XMPP MUC
  outbound-only) — demande opérateur satisfaite ; SSH ADR-013 réutilisé tel quel.
- ✅ Le journal, la signature ed25519, la dé-dup et le gossip HTTP/onion restent
  **strictement inchangés** : XMPP n'est qu'un canal d'octets pour des
  événements déjà authentifiés.
- ⚠️ Disponibilité dépendante du fournisseur XMPP (quota, flood control,
  `max_stanza_size` ~64 Ko → fragmentation des enveloppes à ~48 Ko).
- ⚠️ TURN phase 2 : métadonnées visibles de Cloudflare — opt-in documenté ;
  l'allocation RFC 6062 côté client reste à implémenter (spec seule cette
  session, pion exclu — implémentation maison à la phase 2).
- ℹ️ `docs/adr/ADR-009` reste LA référence pour le défaut ; ce présent ADR
  l'amende en « transports opt-in de repli », il ne le remplace pas.
