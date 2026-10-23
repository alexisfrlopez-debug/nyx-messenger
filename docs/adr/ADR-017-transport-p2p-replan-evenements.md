# ADR-017 — Transport P2P de replan pour les événements (redondance des connexions)

- **Statut** : ACCEPTÉ (session 62 — 2026-10-23)
- **Demande opérateur (verbatim)** : « connexion aux replicas instables, il
  faudrait un 2e protocole de connexion qui assure une redondance/stabilité/
  rapidité de connexion tout en respectant les impératifs de sécurité
  (peut-être s'inspirer de Briar pour une connexion P2P pur capable de prendre
  le relais quand les 2 téléphones sont en ligne) »
- **Décision** : les événements applicatifs chiffrés (messages texte,
  signalisation d'appel) empruntent, en plus du journal répliqué, un
  **transport direct pair-à-pair onion→onion à travers Tor** — le modèle
  « distant » de Briar (chaque pair publie son onion, le pair le contacte
  directement) — avec **ordre adaptatif** : journal d'abord quand le réplica
  est sain, direct d'abord quand il est dégradé. L'onion répliqué RESTE le
  transport premier et le dépôt durable.

## 1. Contexte et constat terrain

Les logs v0.2.40-test du 23/10 (3 téléphones + console) montrent un **cercle
vicieux d'instabilité** sur les réseaux mobiles :

1. circuits Tor lents à bâtir (tor : « 18 timeouts and 337 buildtimes ») →
   chaque connexion au réplica échoue au **timeout de 25 s** (SocketTimeout) ;
2. chaque échec fait **basculer** le répliqué actif (v0.2.25) → churn ;
3. le watchdog `nyx-tor-heal` redémarre le démon tor après **3 échecs
   consécutifs** (01:06:52) alors que le démon venait de se bootstrapper
   (01:04:36) — le réseau était lent, pas le démon mort ;
4. le redémarrage **tue les points d'introduction** de l'onion du téléphone →
   descripteur invalide 30-60 s (propagation HSDirs) → « SOCKS server general
   failure » pour tous les pairs → appels impossibles, messages perdus.

Conclusion : réparer uniquement Tor ne suffit pas — il faut **un second
chemin de livraison** pour que l'application fonctionne même quand les
circuits vers les réplicas sont lents ou morts.

## 2. Décision détaillée

### 2.1 Ce qui est retenu : P2P onion→onion à travers Tor

Le canal direct existe déjà (ADR-015, v0.2.18) : chaque téléphone publie un
service onion à deux ports (API + média 47471), `P2pClient`/`P2pServer`
établissent des sockets authentifiés (HELLO signé ed25519 de la clé
d'identité). Briar utilise exactement cette architecture pour ses contacts
distants (transport « Bramble Tor » : onion de contact → onion de contact).

L'extension : un HELLO de type **`events`** pousse directement au pair les
MÊMES événements signés que ceux du journal (`WireEvent` : type, author, seq,
ts, ttl, to, body, sig). Le pair les ingère par la **même porte** que son
inbox (signature vérifiée, déchiffrée, dédupliquée par eventId) — zéro
nouvelle surface de validation, zéro changement serveur, compatibilité
ascendante (un pair < v0.2.41 refuse le HELLO `events` → l'expéditeur retombe
sur le journal).

**Pourquoi pas LAN/Bluetooth (Briar local) ?** Briar accepte de révéler son
IP locale à un pair de proximité ; Nyx a un impératif d'anonymat plus strict
(ADR-007 : TOUT le trafic passe par Tor, aucune exception). Le P2P
onion→onion **ne révèle rien de plus** que le canal média existant (déjà
utilisé pour les fichiers et les appels). Les transports de proximité
(LAN/mDNS, Bluetooth RFCOMM) sont **écartés en l'état** ; ils ne seraient
envisageables qu'en opt-in explicite par pair, via un nouvel ADR motivé.

### 2.2 Ordre adaptatif (redondance + rapidité)

- **Réplica sain** (aucun échec de transport depuis 3 min) : dépôt journal
  D'ABORD (durabilité multi-réplicas, inbox du pair), P2P direct en REPLI si
  le dépôt échoue par transport (timeout, SOCKS, circuit).
- **Réplica dégradé** (échec de transport < 3 min) : P2P direct D'ABORD
  (latence ~5-15 s quand le pair est en ligne vs 25 s+ vers un réplica
  mourant), dépôt journal en repli si le pair ne répond pas.
- Exactement **un chemin réussi par envoi** : si le direct réussit après un
  échec journal, le trou de séquence dans le journal est **auto-réparé** au
  dépôt suivant (recadrage + re-signature, mécanisme v0.2.10 existant). Le
  `seq` n'est pas une identité client — c'est une contrainte d'ordre du
  journal ; deux événements de contenu différent peuvent partager un `seq`
  (un côté direct, l'autre côté journal) sans collision (dédup par eventId).
- La signalisation d'appel (ring/offer/accept/bye) bénéficie du même repli :
  c'est le chemin critique du « décrochage impossible ».

### 2.3 Périmètre

- **Inclus** : messages texte (`msg`), signalisation d'appel (corps
  `kind=call` dans `msg`). Accusés de réception/lecture, annonces de contact
  et annonces de réplica RESTENT journal-only (non critiques en latence ;
  les invitations ne peuvent pas passer le verrou « contact accepté » du
  canal direct — l'invité n'a pas encore le lien).
- **Inchangé** : fichiers (chemin `file` v0.2.18 existant), média d'appel
  (trames audio/vidéo), poll d'inbox (le tir ne peut pas être remplacé par du
  push — l'inbox VIT chez le répliqué).

### 2.4 Anti-churn (correctif complémentaire, cause racine des logs)

Le redémarrage du démon tor après 3 échecs consécutifs est **contre-productif
quand il suit immédiatement un bootstrap réussi** (le réseau est lent, pas le
démon mort) : il tue les points d'introduction et déclenche la cascade
« descripteur mort → SOCKS general failure ». Nouvelle garde :

- seuil **3 → 5** échecs consécutifs ;
- le heal ne se déclenche **que si le démon est Ready depuis > 10 min**
  (un démon fraîchement bootstrapped construit encore ses circuits — le
  laisser vivre) ;
- cooldown **5 → 10 min**.

## 3. Conséquences

- Un message part désormais par DEUX chemins possibles — la disponibilité
  de livraison devient : journal OU pair en ligne (au lieu de : journal
  uniquement).
- Les appels se établissent même répliqué-mort tant que le pair est joignable
  (signalisation directe + média direct déjà P2P).
- Charge Tor légèrement accrue quand le direct est tenté (circuit
  supplémentaire vers l'onion du pair) — bornée par le timeout 20 s du
  repli événements (sans récupération HSFETCH dans ce chemin).
- Complexité : +1 HELLO kind, +1 type de trame, porte d'ingestion commune
  factorisée (`ingestInboxEvent`) — le chemin inbox et le chemin direct
  partagent TOUTE la validation (signature, déchiffrement, verrous contact,
  blocage, TTL, dédup, notification).
- Aucun changement serveur, aucun changement wire du journal, aucune
  migration de base.

## 4. Alternatives rejetées

| Alternative | Rejet |
|---|---|
| LAN/mDNS + Bluetooth (Briar local) | Révèle l'IP locale au réseau — contredit ADR-007 (tout via Tor) ; envisageable seulement en opt-in par pair via nouvel ADR |
| Relais par contact (pair relais nos événements vers le répliqué) | Ajoute une dépendance de disponibilité à un tiers + métadonnées de relais — le P2P direct fait mieux sans tiers |
| Augmenter les timeouts (25→60 s) | Aggrave la latence perçue et le churn — contredit le retour terrain v0.2.16 |
| Répliquer l'inbox côté pair (push-when-online) | L'inbox VIT chez le répliqué (ADR-001) — la déplacer casse le modèle hors-ligne |
