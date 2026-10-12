# Spécification — Format filaire des événements Nyx (v1)

Statut : **acté** — implémenté dans `server/internal/journal/event.go`.

## Principe

Toute donnée persistée et répliquée par les serveurs Nyx est un **événement** : une unité
atomique, signée par son auteur, horodatée, séquencée par auteur. Le journal est *append-only* :
aucun événement n'est jamais modifié ni supprimé (le TTL est honoré côté clients en v0.1,
côté serveurs en v0.2).

## Schéma d'un événement

```json
{
  "type":  "msg",                     // "msg" | "contact" | "prekey" | "meta"
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
