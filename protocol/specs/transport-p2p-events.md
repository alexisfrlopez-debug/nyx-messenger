# Spécification — Transport P2P de replan pour les événements (ADR-017)

> v0.2.41 — Canal direct onion→onion (extension du protocole ADR-015).
> Statut : IMPLEMENTÉ (client v0.2.41).

## 1. Principe

Un expéditeur dont le dépôt journal échoue (ou est dégradé) pousse
directement au pair les événements signés par le canal P2P existant (port
onion média 47471). Le pair les ingère par la MÊME porte que son inbox :
vérification de signature ed25519, déchiffrement sealed box, verrou contact
mutuel, blocage, TTL, dédup par eventId, notification. Le corps des trames
est EXACTEMENT le JSON `WireEvent` du journal (`protocol/specs/wire-format.md`)
— aucun format nouveau côté événement.

## 2. Poignée de main (extension HELLO)

```
HELLO (F_HELLO=1) payload = {"v":1,"t":"events","onion":"<expéditeur>",
                             "alias":"…","cid":"","csd":"","count":N,
                             "sig":"<b64>"}
```

- Chaîne signée (miroir de `P2pProtocol.helloSignedString`) :
  `nyx-p2p|events|<onion>|<count>` — même schéma que `file` (selector = nombre
  d'unités annoncées).
- Vérification : `onionToPubKey(onion)` + signature + contact accepté ou
  pending (`P2pServer.isAllowed`) — identique à `file`/`call`.

## 3. Transfert

```
F_EVENT (0x18) ×N : payload = UTF-8 JSON d'UN WireEvent signé
                    ({"type":"msg","author":…,"seq":…,"ts":…,"ttl":…,
                      "to":…,"body":<b64 chiffré>,"sig":…})
[shutdownOutput — demi-fermeture de l'expéditeur]
F_ACK (0x7F)      : payload = [acceptés:4 BE]  (nombre d'événements INGÉRÉS)
```

- Trame plafonnée à 4 Mo (limite `P2pProtocol.read` existante) ; un
  événement texte/signalisation ≪ 4 Ko.
- L'expéditeur considère le dépôt direct RÉUSSI si et seulement si
  `acceptés == N`.

## 4. Règles de sécurité (côté récepteur)

1. **HELLO authentifié** : signature ed25519 de la clé d'identité de
   l'expéditeur (l'adresse onion EST la clé publique) + lien contact
   ACCEPTED/PENDING — sinon connexion fermée sans ACK.
2. **Chaque événement re-vérifié individuellement** : signature de l'auteur
   sur le canonique (`MessageCipher.canonical`), `ev.to == <notre onion>`
   (un événement qui ne nous est pas adressé est rejeté), auteur == onion du
   HELLO (anti-mixage).
3. **Mêmes verrous que l'inbox** : contact mutuel, blocage local, TTL
   éphémère, péremption, signalisation d'appel fraîche (< 120 s).
4. **Dédup** : insertion `eventId` (SHA-256 du canonique) en conflit IGNORE —
   un événement reçu direct PUIS pollé (ou l'inverse) n'est jamais doublé.
5. **Anti-rejeu** : un eventId déjà présent est silencieusement ignoré
   (l'ACK compte les événements NOUVELLEMENT ingérés ou déjà connus comme
   acceptés — pas les REJETÉS).

## 5. Ordre adaptatif côté expéditeur

| État du réplica | 1ᵉʳ chemin | Repli |
|---|---|---|
| Sain (aucun échec de transport < 3 min) | Journal (POST /client/event) | Direct P2P si échec TRANSPORT (pas HTTP 4xx métier) |
| Dégradé (échec de transport < 3 min) | Direct P2P (timeout 20 s, sans HSFETCH) | Journal |

- Succès direct = message « envoyé » ; le journal reçoit l'événement SUIVANT
  avec recadrage de séquence (auto-réparation v0.2.10).
- Échec des DEUX chemins = message « échoué » (comportement antérieur).

## 6. Compatibilité

| Récepteur | Comportement |
|---|---|
| ≥ v0.2.41 | Ingère les événements directs (ACK N) |
| v0.2.18 – v0.2.40 | `parseHello` refuse `t=events` → connexion fermée sans ACK → l'expéditeur retombe sur le journal |
| < v0.2.18 | Pas de port média publié → échec de connexion → journal |

## 7. Anti-churn tor-heal (complément ADR-017 §2.4)

Le redémarrage automatique du démon tor (seuil d'échecs de transport
consécutifs) ne se déclenche désormais que si : échecs ≥ 5 ET démon Ready
depuis > 10 min ET cooldown 10 min écoulé. Un démon fraîchement bootstrappé
sur un réseau lent n'est PLUS tué (cause racine de la cascade
« points d'introduction morts → SOCKS general failure » des logs 23/10).
