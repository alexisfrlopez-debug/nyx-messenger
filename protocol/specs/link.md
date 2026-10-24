# Spec — NYX-LINK (lien inversé, ADR-018)

Transport : HTTP/1.1 sur le mux client-auth Tor du réplica routeur.
Encodage : JSON. Corps de requêtes/du lien bornés à 256 Ko (base64 inclus).
Toutes les routes refusées en maintenance (503) comme `/call/relay`.

## Routes

### POST /link/send — dépôt d'une requête pour un téléphone

```json
{
  "to":   "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774q.onion",
  "id":   "1f2e3d4c5b6a79880f1e2d3c4b5a6978",
  "method": "GET",
  "path":   "/health",
  "body_b64": ""
}
```

- `to` : onion v3 (56 car. + `.onion` ou nu).
- `id` : hex minuscule 16-64 car., choisi par le demandeur (aléatoire).
- `method` : `GET` ou `POST` ; `path` : **whitelist** `/health`,
  `/client/event`, `/client/inbox`, `/client/profile`, `/client/alias`
  (query string incluse dans `path`, ex. `/client/inbox?to=x&after=0&auth=…`).
- `body_b64` : corps brut (vide pour GET).

Réponses : `202 {"id":"…"}` accepté · `400` validation · `429` file du
destinataire pleine (8) · `503` maintenance.

### GET /link/pull?me=&auth=ts:sig — long-poll du téléphone (45 s)

- `me` : onion nu (56 car.).
- `auth` : `<ts>:<sig_b64>` — signature ed25519 (clé d'identité dont dérive
  `me`) du canon `NYX-LINK-PULL\n<me>\n<ts>` ; fenêtre ±300 s.

```json
{"reqs": [{"id":"…","method":"GET","path":"/health","body_b64":""}]}
```

- répond immédiatement si des requêtes attendent (FIFO, max 4/lot) ;
- sinon attend jusqu'à 45 s puis `{"reqs":[]}` (keepalive) ;
- un nouveau PULL du même `me` réveille l'ancien (réponse vide) — canal unique.

Erreurs : `401` auth invalide/absente · `400` onion invalide.

### POST /link/reply — réponse du téléphone

```json
{
  "id": "…", "from": "kxcwah…onion", "status": 200,
  "body_b64": "eyJvayI6dHJ1ZX0=", "auth": "<ts>:<sig_b64>"
}
```

- `auth` : signature du canon `NYX-LINK-REPLY\n<id>\n<from>\n<status>\n<sha256(body_b64)>`.
- `body_b64` ≤ 256 Ko ; `status` ∈ [200, 599].
- une seule réponse par id (la première gagne) ; TTL 120 s.

Réponses : `202` · `400` validation · `401` auth · `409` déjà répondu.

### GET /link/wait?id= — long-poll du demandeur (75 s)

- `200 {"status":200,"body_b64":"…"}` dès l'arrivée de la réponse ;
- `408 {"error":"délai dépassé — téléphone sans canal ou injoignable"}` au
  timeout ; `400` id invalide.

## Canons de signature (Kotlin ↔ Go — octets exacts)

```
NYX-LINK-PULL\n<me_onion>\n<ts_secondes>
NYX-LINK-REPLY\n<id>\n<from_onion>\n<status>\n<sha256_hex(body_b64)>
```

`me`/`from` : forme AVEC suffixe `.onion` (le réplica embarqué reçoit la
forme complète dans `ownOnion`). Sha256 en hex minuscule du TEXTE base64
(pas des octets décodés — le canon signe ce qui transite).

## Comportement du téléphone (LinkChannel)

1. Tor prêt + réplicas configurés → boucle daemon : PULL sur le réplica
   actif (jamais l'onion d'un pair — `relayHintFor`), exécution locale
   (`EmbeddedReplica.dispatch`) de chaque requête, REPLY signé.
2. Échec réseau : backoff 5 s → 10 s → 30 s (plafond) ; échecs répétés →
   `markReplicaFailed` (failover existant, profite de DescriptorHeal).
3. Journal `nyx-link` via DiagLog (survit à l'export de diagnostic) :
   « canal ouvert (réplica X…) », « requête <id…> servie localement → 200 »,
   « réponse déposée », « canal rétabli (n ième) ».

## Comportement du demandeur (repli)

Requête vers le réplica embarqué d'un PAIR en échec TRANSPORT (timeout,
SOCKS) → rejeu `send`+`wait` via un AUTRE réplica sain (30 s de budget total)
→ succès : réponse traitée à l'identique ; échec : failover existant.
