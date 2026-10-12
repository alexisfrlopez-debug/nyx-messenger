# Spécification — API du nœud serveur Nyx (v1)

Statut : **acté** — implémenté dans `server/internal/api/handlers.go`.

Le nœud expose **une seule interface HTTP** sur son service onion (port 80 côté Tor,
redirigé vers le port HTTP local). Deux familles de routes : **client** (applications
Android) et **gossip** (nœuds serveur entre eux).

Important : ces routes sont accessibles **uniquement via Tor** (l'adresse onion n'est
joignable que par le réseau onion). En développement local, on peut les interroger
directement sur `127.0.0.1:<http-port>`.

## Routes client

### `GET /health`

État du nœud. Réponse :

```json
{ "ok": true, "version": "0.1.0", "onion": "abcd…56.onion", "events": 1284 }
```

### `GET /identity`

Identité du nœud : adresse onion + clé publique de canal gossip (X25519, base64).
Sert aux nouveaux réplicas pour rejoindre le réseau de réplication.

### `POST /client/event`

Soumission d'un événement signé par un **client** (l'application Android signe avec sa
clé d'identité). Corps : un événement au format filaire (voir `wire-format.md`).

- `200` → `{"accepted": true, "id": "…sha256…"}` — événement validé, journalisé, sera répliqué.
- `400` → signature invalide, seq incorrect, corps trop grand, type inconnu.
- `409` → événement déjà connu (dé-duplication par `id`).

### `GET /client/inbox?to=<onion>&after=<cursor>&limit=`

Boîte de réception d'un utilisateur : renvoie les événements où `to == <onion>`
(ordre du journal, au-delà du curseur global `after`). Le curseur est une position
dans le journal du réplica (entier) : première interrogation, utiliser `after=0` ;
ré-interroger ensuite avec la valeur `cursor` renvoyée dans la réponse
(`{"events": [...], "cursor": n}`). Limite : 500 événements par appel.

> **Limitation documentée** : le serveur connaît le champ `to` (métadonnée de routage).
> Le plan v0.2 (voir `01-modele-securite.md`) prévoit le chiffrement du champ `to` avec
> filtrage client (modèle « pawl/prefix filter » de Cwtch) pour supprimer cette fuite.

### `GET /client/events?author=<onion>&since=<seq>`

Journal public d'un auteur (utile pour les événements `contact` non chiffrés et la
récupération d'historique d'un compte sur un nouveau réplica).

### `GET /client/alias?name=<alias>` — v1.1 (session 10)

Résolution d'un **pseudonyme unique** (voir `wire-format.md` §6). Réponse :

```json
{ "alias": "mon_alias", "taken": true, "by": "abcd…56.onion" }
```

- `taken=false` (et `by` absent) → alias libre, claim possible.
- `taken=true` → `by` = détenteur gagnant (règle du plus petit `id` — déterministe
  et convergente entre réplicas).
- `400` → paramètre `name` manquant ou format invalide (`^[a-z0-9_]{3,24}$`).
- Le claim lui-même est un événement standard envoyé à `POST /client/event`
  (`type="alias"`, body JSON base64 `{"alias":"…","action":"claim"}`) — aucun
  endpoint dédié : la réservation passe par le journal comme tout le reste.
- **Pas de listing global** (anti-énumération) : résolution point à point uniquement.

## Routes gossip (nœud ↔ nœud)

### `GET /gossip/peers`

Liste des réplicas connus (adresses onion + clés gossip publiques). Permet la découverte
progressive du réseau de réplicas (joindre ce que l'on apprend).

### `POST /gossip/push`

Réplication **sortante** d'un pair vers nous. Corps (chiffré au canal NaCl — voir plus bas) :

```json
{ "sender": "abcd…onion", "cursor": {"efgh…onion": 120}, "events": [ /* événements */ ] }
```

`cursor` = pour chaque auteur connu, le dernier `seq` que **nous** avons déjà envoyé à ce
pair. Le pair renvoie `{"accepted": n, "rejected": m}` après validation de chaque événement.

### `POST /gossip/pull`

Demande de réplication **entrante**. Corps chiffré :

```json
{ "sender": "abcd…onion", "want": {"efgh…onion": 0}, "limit": 500 }
```

Réponse : les événements de `want` (par auteur, après le `seq` demandé) que nous possédons.

## Chiffrement du canal gossip

Chaque requête/réponse gossip est encapsulée dans une **scellée NaCl** (`crypto_secretbox`)
dont la clé est dérivée du secret partagé X25519 (`crypto_box_beforenm`) entre la clé
`gossip_pub` de l'expéditeur et celle du destinataire. Format binaire :

```
[ nonce 24 octets ][ secretbox(payload) ]
```

Chaque nœud vérifie que `sender` correspond bien à la clé utilisée (le déchiffrement
échoue sinon) — authentification mutuelle sans certificat ni IP.

## Codes de retour

| Code | Signification |
|---|---|
| 200 | Succès |
| 400 | Requête ou événement invalide (détail dans le corps) |
| 401 | Canal gossip non authentifiable (déchiffrement impossible) |
| 404 | Ressource inconnue |
| 409 | Doublon (dé-duplication par `id`) |
| 503 | Nœud en cours de démarrage (Tor non prêt) |

## Conventions

- Tout le contenu est `application/json` (sauf enveloppes gossip binaires).
- Les horodatages sont des entiers unix en secondes.
- Les tailles limites v0.1 : événement ≤ 256 Ko, lot gossip ≤ 5 Mo, 500 événements par réponse.
