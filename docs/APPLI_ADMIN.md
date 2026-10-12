# nyx-admin — Console d'administration (Windows + Android) — spécification v1

> Application **séparée** de la messagerie, réservée à l'opérateur du réseau :
> monitoring, démarrage/arrêt, réplication, suppression (élagage), maintenance.
> Architecture : docs/adr/ADR-008 ; hébergement : ADR-009 ; exploitation :
> docs/DEPLOIEMENT_SERVEUR.md.

## 1. Objectif et périmètre

`nyx-admin` donne à l'opérateur un **accès privilégié à distance** aux réplicas
Nyx, exclusivement via Tor, sans jamais ouvrir de shell ni héberger quoi que ce
soit en local. Ce n'est PAS un client de messagerie : aucune clé utilisateur, un
trousseau séparé (clé admin ed25519), une app séparée (les deux APK peuvent
cohabiter sur le même téléphone sans partager de données).

| | App messagerie | nyx-admin |
|---|---|---|
| Utilisateur | n'importe qui | l'opérateur uniquement |
| Clés | identité utilisateur (onion) | clé admin ed25519 racine |
| Cible | API client des réplicas | API admin (onion séparé, onion auth) |
| Distribution | release publique GitHub | release GitHub (asset séparé, APK `nyx-admin` + zip Windows) |

## 2. Architecture

```
┌────────────── nyx-admin (UI Compose) ──────────────┐
│  Écrans : Flotte · Réplica · Journal · Clés        │
│  admin-core (Kotlin partagé) :                     │
│   · client API admin (HTTP via SOCKS)              │
│   · signature ed25519 des commandes + nonce        │
│   · modèle d'état (flotte, santé, curseurs)        │
│   · trousseau (clé admin chiffrée)                 │
└──────────────┬─────────────────────────────────────┘
               │ SOCKS
         Tor local (embarqué : tor-binary sur Android,
         expert bundle sur Windows)
               │
               ▼  onion service d'ADMIN de chaque réplica
        nyx-server : API admin 127.0.0.1:9091
        (Tor ClientAuthorization + vérif signature + nonce cache)
               │
               ▼
        actions locales du nœud (journal, pairs, maintenance, élagage)
```

- **admin-core** : module Kotlin commun (aucune dépendance UI) — une seule
  implémentation du protocole, deux surcouches.
- **Windows** : Compose Desktop (JDK jlinké), tor.exe de l'expert bundle piloté
  via control port (même logique que `TorManager` Android), distribution zip
  signé (future release GitHub).
- **Android** : APK `nyx-admin` (module `admin/android`), réutilise le
  composant Tor embarqué du projet (tor-binary + `NyxTorService` adapté),
  base de la clé admin en sqlcipher.
- Le **moteur de build** et l'audit (§9.1) couvriront ces modules : toute
  recompilation de nyx-admin passe par le même cycle audit → build → livrables
  → release (règle §9.6).

## 3. Authentification (2 barrières)

1. **Tor ClientAuthorization** : le service onion d'admin n'accepte que les
   clients Tor déclarés (clés auth privées distribuées par la console lors du
   provisionnement) — barrière réseau, avant même HTTP.
2. **Signature ed25519 de chaque commande** :
   ```
   X-Nyx-Admin: <pubkey base64>
   X-Nyx-Ts:    <unix secondes>      (fenêtre ±120 s)
   X-Nyx-Nonce: <aléatoire 32 octets, cache serveur anti-replay>
   X-Nyx-Sig:   ed25519( "nyx-admin-v1" || ts || nonce || METHOD || PATH || sha256(body) )
   ```
   La clé publique est déclarée dans la config du nœud :
   ```json
   { "admin": { "authorized_keys": ["BASE64-1", "BASE64-2"] } }
   ```
   Perte d'un appareil → retrait de sa clé (`DELETE /admin/keys`) : révocation
   immédiate, sans toucher aux autres appareils.

## 4. API admin (v1)

Préfixe : `/admin/*` — port 9091, onion dédié. Tout est journalisé dans le
journal d'audit admin (local, inclus dans `/admin/log`).

### Monitoring (read-only)
| Commande | Renvoie |
|---|---|
| `GET /admin/status` | version, uptime, état (actif/maintenance), nb événements, taille journal, dernier seq par auteur, état Tor (bootstrap %, circuits) |
| `GET /admin/peers` | pairs : onion, dernier contact, curseurs push/pull, backoff, latence dernière ronde |
| `GET /admin/resources` | CPU/RAM/disque (lecture `/proc`), taille data-dir |
| `GET /admin/log?tail=n` | journal d'audit des commandes admin (qui/quoi/quand/sig) |

### Démarrage / arrêt
| Commande | Effet |
|---|---|
| `POST /admin/maintenance {"on":true}` | cesse d'accepter les clients, gossip maintenu (drain) |
| `POST /admin/shutdown` | arrêt gracieux : flush journal, fermeture onion, exit 0 — systemd `Restart=always` le relance (assumé ADR-008 §5 ; supervision `nyx-agent` en option v0.3) |
| `POST /admin/restart` | arrêt gracieux + systemd relance |

### Réplication
| Commande | Effet |
|---|---|
| `POST /admin/peers {"onion":…, "gossip_pub":…}` | ajoute un pair (persisté dans config.json) |
| `DELETE /admin/peers/{onion}` | retire un pair |
| `POST /admin/peers/resync {"onion":…}` | remet le curseur à zéro et aspire tout le journal du pair |

### Suppression (élagage conforme au journal append-only)
| Commande | Effet |
|---|---|
| `POST /admin/checkpoint/propose {"before_seq":…}` | construit la racine de Merkle du préfixe, demande la co-signature aux pairs (quorum) |
| `POST /admin/checkpoint/apply` (2 étapes, confirmation) | archive le préfixe hors ligne puis élague — **vérifiable** : la racine signée reste prouvable |
| Suppression unitaire d'un événement | **interdite** (invariant ADR-001 — la commande n'existe pas) |

### Identité / clés
| Commande | Effet |
|---|---|
| `GET /admin/identity` | onion, clé publique, date de création |
| `POST /admin/identity/rotate` | rotation onion (annoncée aux pairs, double publication le temps de la bascule) |
| `GET/POST/DELETE /admin/keys` | gestion des clés admin autorisées |

## 5. Écrans (v1)

1. **Flotte** — vue d'ensemble : une carte par réplica (état, santé, événements,
   pairs vivants, latence) ; indicateurs agrégés (réplication complète ?,
   quorum checkpoint ?).
2. **Réplica** — détail d'un nœud : status/resources/peers, actions
   (maintenance, restart, shutdown, resync, add/remove pair).
3. **Journal** — exploration du journal d'événements (compteurs, derniers seq,
   recherche par auteur) + historique des checkpoints.
4. **Audit** — commandes admin passées (par nœud), résultat, signature.
5. **Clés** — appareils admin autorisés par nœud, ajout/révocation.

## 6. Modèle de menace (console)

- Vol du PC/du téléphone : la clé admin est chiffrée (DPAPI + passphrase sur
  Windows, sqlcipher + auth biométrique Android) ; révocation par retrait de la
  clé sur les nœuds (depuis l'autre appareil).
- Compromission du trousseau : les commandes signées ne donnent pas d'accès aux
  clés des réplicas ni aux contenus (séparation admin ↔ données utilisateur).
- Rejeu : nonce + fenêtre temporelle (§3).
- La console ne stocke AUCUNE donnée utilisateur — seulement la topologie.

## 7. Roadmap

| Version | Contenu |
|---|---|
| admin v0.2 | `admin-core` + API read-only côté nyx-server + console Windows & Android (monitoring) |
| admin v0.3 | start/stop/maintenance + réplication (add/remove/resync) + révocation de clés |
| admin v0.4 | checkpoints signés (élagage), rotation d'identité, assistant de provisionnement (ADR-009 §2) |
| ultérieur | `nyx-agent` (supervision locale start/stop), multi-opérateurs (quorum de clés admin) |
