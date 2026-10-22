# Spécification — Transport XMPP MUC pour réplicas et clients Nyx (v1)

Statut : **acté** (ADR-014) — implémentation de référence :
`server/internal/transportxmpp/`.

> Ce document spécifie comment un nœud Nyx (réplica ou client) utilise un
> serveur XMPP tiers quelconque (ex. hookipa.net — ejabberd, XEP-0045/0313
> vérifiés) comme **bus de messages opt-in**. L'onion v3 (ADR-009) RESTE le
> transport par défaut ; ce transport n'existe que pour les hôtes sans port
> entrant. Toutes les charges utiles sont des **événements signés du journal**
> (wire-format.md) — le serveur XMPP n'a accès à aucun clair.

## 1. Principe

```
réplica A ──join MUC──► salon « nyx-bus@conference.exemple.net » ◄──join MUC── réplica B
    │                                        │                                      │
    └── message groupchat « nyx1 … » ────────┴─────── message groupchat « nyx1 … »┘
                                             │
                                     MAM (XEP-0313) = rattrapage du journal
```

- Le salon MUC joue le rôle du **canal de diffusion** que jouent normalement
  les connexions onion entre réplicas ;
- chaque message `groupchat` transporte **une fragment d'enveloppe Nyx** ;
- l'authenticité vient de la **signature ed25519** de chaque événement
  (l'expéditeur XMPP n'est pas une autorité) ; la dé-dup par `id` (sha256
  canonique) rend le rejeu inerte ;
- **aucun changement du format filaire** : `Event` (type, author, seq, ts, ttl,
  to, body, sig) transite intégralement.

## 2. Adressage et comptes

| Objet | Règle |
|---|---|
| Compte XMPP du nœud | **JID pseudonyme dédié au réseau** (`nyx-<16 hex aléatoires>@<hôte>`), créé sans email ni donnée personnelle ; jamais réutilisé pour un usage humain |
| Ressource | `nyx` (bind) — le `nick` du MUC peut valoir la partie locale du JID |
| Salon | un seul salon par réseau, ex. `nyx-bus@conference.<hôte>` ; config `xmpp.muc` |
| Lien JID ↔ onion | **pas une donnée du filaire** — la correspondance est un secret de config locale (`data_dir/config.json`) |

Le serveur XMPP observe : JID, IP (ou sortie Tor), timing, tailles. Aucun clair.

## 3. Configuration (serveur — `config.json`)

```json
{
  "xmpp": {
    "enabled": true,
    "host": "hookipa.net",
    "port": 5222,
    "jid": "nyx-0f3a9c2b7d51e8a4@hookipa.net",
    "password": "SECRET — data_dir/config.json uniquement",
    "muc": "nyx-bus@conference.hookipa.net",
    "nick": "nyx-0f3a9c2b7d51e8a4",
    "tor_socks": "127.0.0.1:9050",
    "tls_enforce": true
  }
}
```

- `tor_socks` vide = connexion directe (clearnet) ; renseigné = toutes les
  connexions XMPP passent par le SOCKS5 indiqué (Tor = anonymisation de l'IP).
- `tls_enforce` (défaut `true`) : STARTTLS ou TLS direct OBLIGATOIRE avant toute
  authentification — le client refuse de s'authentifier en clair.
- Le mot de passe ne figure **jamais** dans `config.example.json` (valeur
  factice) ni dans le dépôt (règle §9.5).

## 4. Séquence de connexion (implémentée)

1. **Dial** : TCP direct, ou via SOCKS5 (`tor_socks`) vers `host:port` ;
2. **Stream** : `<stream:stream xmlns='jabber:client'
   xmlns:stream='http://etherx.jabber.org/streams' to='<host>' version='1.0'>` ;
3. **STARTTLS** : si la fonctionnalité est offerte (ou TLS direct sur 5223) —
   obligatoire si `tls_enforce` ; reprise du stream après `<proceed/>` ;
4. **SASL** : mécanisme **SCRAM-SHA-1** en priorité (jamais le mot de passe en
   clair sur le fil), repli **PLAIN** si seul mécanisme offert (sous TLS) ;
5. **Bind** : `<iq type='set'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'>
   <resource>nyx</resource></bind></iq>` ;
6. **Join MUC (XEP-0045)** :
   `<presence to='<muc>/<nick>'><x xmlns='http://jabber.org/protocol/muc'/></presence>`
   ; `<201/>` = salon créé ; statut 110 = présence self confirmée ;
7. **Catch-up MAM** (§7) puis boucle d'échange (§5/§6).

Déconnexions : reconnexion en backoff exponentiel (30 s → 15 min, même modèle
que le gossip), puis re-catch-up MAM.

## 5. Encodage des enveloppes — corps `nyx1`

Le corps (`<body>`) d'un message `groupchat` porte :

```
nyx1 <JSON base64url>
```

Le JSON décodé (enveloppe de transport, distincte de l'Event) :

```json
{
  "v": 1,
  "id": "0f3a9c2b7d51e8a4",
  "from": "abcd…56.onion",
  "seq": 42,
  "frag": { "i": 1, "n": 2 },
  "data": "BASE64URL(Event complet, canonique, signé)"
}
```

| Champ | Rôle |
|---|---|
| `v` | version du format d'enveloppe de transport (`1`) |
| `id` | identifiant logique du message (aléatoire) — regroupement des fragments |
| `from` / `seq` | lecture rapide (log, filtre) — **non fiables seuls** : la vérité est dans l'Event signé |
| `frag` | fragment `i` sur `n` (enveloppe monolithique : `{"i":1,"n":1}`) |
| `data` | événement JSON du journal (wire-format.md), base64url, signé |

**Fragmentation** : `max_stanza_size` usuel des serveurs publics ≈ 64 Ko →
un corps complet DOIT rester **≤ 48 Ko** (fragment monolithique ≤ 45 KiB de
base64, fragments coupés à 40 KiB — multiple de 4). Les fragments sont
réassemblés par `(id, i/n)` — un événement n'est reconstitué et validé que
lorsque `n` fragments distincts sont réunis. Cache des fragments partiels :
128 messages max, éviction FIFO, TTL 10 min. Bornes d'émission : Event ≤
34 560 octets (base64 46 080) — au-delà, refus explicite.

**Diffusion** : chaque réplica publie dans le salon **uniquement ses
événements propres** (`author` = son onion) — les événements d'autrui arrivent
par les publications des autres réplicas ou le gossip onion ; la dé-dup par
`id` absorbe les recouvrements. Rythme : ≤ 5 messages/seconde, pause de 300 ms
entre lots — respect du flood control des serveurs publics (modulable).

## 6. Réception et intégration au journal

À chaque message `groupchat` dont le corps commence par `nyx1 ` :

1. ignorer les messages émis par soi (`from` = notre JID full dans le salon —
   écho du serveur, y compris via résultats MAM) ;
2. décoder l'enveloppe, réassembler les fragments (§5) ;
3. décoder l'`Event` (wire-format.md) et l'intégrer via
   `journal.Store.Append(ev, now)` — la validation complète s'applique
   (signature ed25519 sur la forme canonique, seq strict par auteur, taille,
   types connus) ; l'ajout est **idempotent** (`id` = sha256) ;
4. si l'ajout a accepté un événement nouveau → signaler `Kick` (le gossip
   onion existant re-propage au reste de la flotte).

Rejets (signature invalide, seq incohérente) : journalisés, jamais fatales —
le salon peut contenir du bruit tiers.

## 7. Catch-up MAM (XEP-0313)

Au join, puis après chaque reconnexion :

```xml
<iq type='set' id='mam1'>
  <query xmlns='urn:xmpp:mam:2' queryid='q1'>
    <set xmlns='http://jabber.org/protocol/rsm'><max>50</max></set>
  </query>
</iq>
```

- les résultats arrivent en `<message><result xmlns='urn:xmpp:mam:2'
  queryid='q1' id='<uid>'>…<forwarded>…<message type='groupchat'><body>nyx1 …` ;
- pagination : relancer avec `<set><after><uid></after></set>` tant que
  `complete='false'` (borne dure : 50 pages/session de catch-up) ;
- le curseur `uid` du dernier message traité est persisté en mémoire du
  transport (redémarrage = catch-up depuis la fenêtre récente ; la dé-dup par
  `id` absorbe les redondances) ;
- le traitement des résultats MAM suit §6 (même intégration).

## 8. Clients (Android) — usage

Un client peut joindre le même salon pour **soumettre** ses événements signés
(`POST /client/event` restant possible via un réplica onion). La lecture via
MUC n'est PAS recommandée pour un client (pas d'ordre global, pas d'inbox
filtrée) — le rattrapage client passe par `/client/inbox` d'un réplica.
Le transport XMPP est donc **primaires = réplicas** ; l'usage client est un
dégradé documenté (envoi seulement).

## 9. Non-goals (v1)

- pas de MUC privé, pas de plusieurs salons, pas de whitelist MUC ;
- pas d'OMEMO (inutile : les charges sont déjà E2E signées, souvent chiffrées) ;
- pas de BOSH/WebSocket ;
- pas de synchronisation fine des curseurs MAM entre réplicas.
