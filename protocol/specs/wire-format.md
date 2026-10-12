# Spécification — Format filaire des événements Nyx (v1)

Statut : **acté** — implémenté dans `server/internal/journal/event.go`.
> v1.1 (2026-10-12, session 10) : ajout du type **`alias`** (réservation de pseudonyme
> unique, support des invitations) — voir §6.
> v1.2 (2026-10-12, session 12) : ajout du type **`profile`** (carte de profil à
> visibilité par champ) — voir §7.

## Principe

Toute donnée persistée et répliquée par les serveurs Nyx est un **événement** : une unité
atomique, signée par son auteur, horodatée, séquencée par auteur. Le journal est *append-only* :
aucun événement n'est jamais modifié ni supprimé (le TTL est honoré côté clients en v0.1,
côté serveurs en v0.2).

## Schéma d'un événement

```json
{
  "type":  "msg",                     // "msg" | "contact" | "prekey" | "meta" | "alias" (v1.1) | "profile" (v1.2)
  "author": "abcd...56.onion",        // adresse onion v3 de l'auteur (= sa clé publique)
  "seq": 42,                          // séquence par auteur, strictement croissante, sans trou
  "ts": 1728739200,                   // horodatage unix en secondes (déclaré par l'auteur)
  "ttl": 0,                           // durée de vie en secondes ; 0 = permanent
  "to": "efgh...56.onion",            // destinataire prévu (facultatif ; "" = diffusion)
  "body": "base64(...)",              // charge utile CHIFFRÉE côté client (opaque au serveur)
  "sig": "base64(...)"                // signature ed25519 sur la représentation canonique
}
```

Champ calculé (non transmis) : `id = sha256(canonique)` en hexadécimal, sert de clé de
dé-duplication et de curseur de réplication.

## Représentation canonique signée

La signature couvre une chaîne canonique déterministe, sans ambiguïté de sérialisation :

```
canonique = "NYX1" "\n" type "\n" author "\n" seq "\n" ts "\n" ttl "\n" to "\n" sha256_hex(body)
```

La signature est calculée avec la clé privée **ed25519 dont la partie publique est
l'adresse onion de l'auteur** (l'adresse onion v3 encode la clé publique ed25519 — voir
`docs/03-protocole-messagerie.md`, section « Identité »). Un serveur vérifie donc qu'un
événement signé `abcd…onion` provient bien du détenteur de ce service onion.

## Règles de validation (implémentées côté serveur)

1. La signature ed25519 doit être valide pour `author`.
2. `seq` doit valoir `dernier_seq(author) + 1` — un événement hors séquence est rejeté
   (les événements futurs sont conservés en attente, les événements dupliqués ignorés).
3. `ts` doit être raisonnable (± 24 h de l'horloge du serveur — tolérance pour la latence Tor).
4. `body` doit être un base64 valide, taille ≤ 256 Ko (v0.1).
5. `type` doit appartenir à l'ensemble connu.

Un événement invalide est rejeté **sans** être répliqué — c'est ce qui rend le journal
immuable face à un serveur compromis : il ne peut ni forger, ni réordonner (seq), ni
faire passer un événement pour un autre auteur.

## Types d'événements (v0.1)

| Type | Corps (`body` après déchiffrement côté destinataire) | Usage |
|---|---|---|
| `msg` | JSON `{text?, attachment?, sent_at}` chiffré au destinataire | Message 1:1 |
| `contact` | JSON `{alias, onion, added_at}` NON chiffré (annoncé publiquement par l'auteur) | Annonce « j'ajoute X » |
| `prekey` | JSON de pré-clés Signal (bundle), chiffré ou public selon la clé | Établissement de session (v0.2) |
| `meta` | Réservé (purge TTL, routage) | Phase 2 |
| `alias` **(v1.1)** | JSON NON chiffré `{"alias":"…","action":"claim"}` (base64) | Réservation d'un pseudonyme unique — §6 |
| `profile` **(v1.2)** | JSON NON chiffré `{"fields":{…}}` (base64) si diffusion ; chiffré si adressé | Carte de profil à visibilité par champ — §7 |

## 6. Type `alias` — pseudonyme unique (v1.1, session 10)

### 6.1 Objet

Permettre à un utilisateur de réserver un **alias unique** (pseudonyme lisible) lié à
son adresse onion, pour les invitations (« ajoute-moi par `@mon_alias` ») — demandé
par l'opérateur (session 10). L'alias est **optionnel** : l'adresse onion reste
l'identité (ADR-003) ; l'alias est une étiquette publique ET SÉPARABLE — il ne doit
jamais contenir d'élément d'identité réelle (ADR-010).

### 6.2 Format

- Événement standard (canonique NYX1 inchangé) : `type = "alias"`, `to = ""`
  (diffusion), `body` = base64 du JSON : `"alias": string, "action": "claim"`
  (seule action v1.1 ; une future `release` fera l'objet d'une révision).
- Format d'alias : `^[a-z0-9_]{3,24}$` — minuscules, chiffres, underscore,
  3 à 24 caractères. Validation côté serveur (rejet 400) et côté client.
- L'unicité est **globale au réseau de réplicas** via le journal : premier
  arrivé, signé, servi.

### 6.3 Conflits et convergence

Deux auteurs peuvent prétendre au même alias (gossip intermittent). Règle
**déterministe** appliquée par tous les réplicas au replay comme à l'append :

> **Le gagnant est le claim dont l'événement a le plus petit `id`
> (sha256 canonique, comparaison hexadécimale lexicographique).**

Les réplicas convergent donc vers le même détenteur sans coordination. Un claim
perdant reste dans le journal (append-only) mais ne résout pas l'alias ; le client
peut le détecter par résolution et re-claim (nouvelle seq).

### 6.4 Résolution

Voir `api.md` §`GET /client/alias?name=` : `{taken, by}`. Pas de listing global
(anti-énumération) : seul le résolveur point à point existe. Réplicas recommandés :
limiter les requêtes de résolution par circuit (implémentation v0.2) ; les clients
ne doivent résoudre qu'à la demande explicite de l'utilisateur.

### 6.5 Limites v1.1

- Pas d'expiration ni de libération (`release` prévu en révision future).
- Pas de limite du nombre d'alias par auteur (recommandation réplicas : refuser
  au-delà de N par auteur — mise en œuvre v0.2 si abus, cf. ADR-010 PoW).

## 7. Type `profile` — carte de profil à visibilité par champ (v1.2, session 12)

### 7.1 Objet

Publier une **carte de profil** associée à l'adresse onion : nom affiché, bio,
site web, localisation, avatar. Chaque champ est **public** (diffusé dans le
journal) ou **privé** (ne quitte JAMAIS l'appareil en v1.2 — stockage local
uniquement ; un partage E2E champ par champ est envisagé en révision future).
Cette granularité répond à la demande de l'opérateur (session 12) tout en
respectant ADR-010 : la publication est un choix explicite par champ, aucun
champ n'est public par défaut, et rien n'identifie l'utilisateur en dehors de
ces choix.

### 7.2 Format

- Événement standard (canonique NYX1 inchangé) : `type = "profile"`.
- **Diffusion** (`to = ""`) : `body` = base64 du JSON (NON chiffré — contenu
  public par définition) :
  ```json
  {"fields":{"display_name":{"value":"Anon","public":true},"avatar":{"value":"<base64 image>","public":true}}}
  ```
- **Adressé** (`to = <contact>`) : réservé au futur partage E2E — en v1.2 le
  serveur le traite comme un corps opaque (comme `msg`) ; seul le cas diffusion
  est validé sémantiquement et indexé.
- Clés de champs **fermées** (rejet 400 sinon) :

  | Clé | Limite |
  |---|---|
  | `display_name` | ≤ 64 caractères |
  | `bio` | ≤ 280 caractères |
  | `website` | ≤ 128 caractères |
  | `location` | ≤ 64 caractères |
  | `avatar` | base64 image ≤ 65 536 caractères (≈ 48 Ko binaires) |

- Un champ diffusé DOIT porter `"public":true`. **Invariant serveur** : un
  événement `profile` en diffusion contenant un champ `public:false` est rejeté
  (400) — un champ privé ne doit jamais apparaître dans le journal public.
- Un événement `profile` en diffusion avec `fields` vide est valide (efface le
  profil public).

### 7.3 Sémantique « dernier gagne »

Contrairement à `alias` (règle min-id), le profil est **remplaçable** : la carte
effective d'un auteur est celle de son événement `profile` au **seq le plus
élevé** (ordre du journal). Les réplicas convergent naturellement au replay.

### 7.4 Résolution

Voir `api.md` §`GET /client/profile?author=` : la carte publique la plus
récente de l'auteur. Les clients ne doivent consulter des profils qu'à la
demande explicite de l'utilisateur (pas de moissonnage — anti-énumération,
même logique que §6.4).

### 7.5 Limites v1.2

- Les champs privés restent locaux (aucun canal de partage E2E encore défini —
  révision future du présent §7).
- Pas d'historique de profils exposé (seule la carte courante est résolue) ;
  le journal conserve néanmoins les versions successives (append-only).
- `avatar` : le client est responsable du redimensionnement/recodage (v1.2 :
  256×256 JPEG côté application) ; le serveur n'applique que la limite de taille.

## Chiffrement du corps (v0.1)

`body` d'un `msg` est chiffré par l'émetteur avec **libsodium `crypto_box_seal`**
(scyllis scellée) vers la clé X25519 dérivée de la clé ed25519 du destinataire
(conversion `crypto_sign_ed25519_pk_to_curve25519`). Le serveur ne peut pas déchiffrer.

Migration prévue (v0.2, décision ADR-002) : remplacement par les sessions Signal Protocol
(X3DH + Double Ratchet) via `libsignal`. Le format d'événement ne change pas — seul le
contenu de `body` évolue ; le champ `type` reste le discriminant.

## Éphémère

`ttl > 0` signifie : « le destinataire doit effacer ce message `ttl` secondes après
déchiffrement ou après affichage (selon le type) ». En v0.1 les serveurs conservent
l'événement (limitation documentée dans le modèle de sécurité) ; en v0.2 les serveurs
appliquent la purge (`meta` / garbage collection) et les clients l'effacement local.
