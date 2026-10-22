# Déploiement des serveurs Nyx — services cachés, hébergement 100 % réseau

> Ce document explique **comment les serveurs (réplicas) Nyx sont déployés et
> exploités** conformément aux ADR-008 et ADR-009 : uniquement en **hidden
> services Tor**, sur des **hôtes distants**, sans rien héberger en local, avec
> une console d'administration distante (Windows + Android).
> Décisions de fond : docs/adr/ADR-009 (hébergement), ADR-008 (console admin),
> ADR-001 (réplication journal+gossip), ADR-005 (serveur Go).

## 1. Le modèle en une image

```
                         ┌──────────── RÉSEAU TOR ────────────┐
   APPLI MESSAGERIE      │                                    │      OPÉRATEUR
   (Android, v0.1.x)     │  réplica A   réplica B   réplica C │      (nulle part
        ┌───────────────▶│  .onion A    .onion B    .onion C  │◀────── en particulier)
        │  SOCKS (Tor    │   ▲            ▲            ▲      │
        │  embarqué)     │   └── gossip NaCl inter-réplicas ──┘   CONSOLE NYX-ADMIN
        │                │        (journal signé répliqué)       (Windows / Android)
        │                │            ▲            ▲             │
        │                │            └────┬───────┘             │
        │                │        onion d'ADMIN par nœud ◀───────┘  (onion auth +
        │                │        (127.0.0.1:9091 → onion)          commandes signées)
        │                └────────────────────────────────────────────┘
        │
   « Rien de local » : le téléphone ne fait tourner QUE le client.
   Chaque réplica vit sur un hôte distant (VPS anonyme / machine communautaire)
   et n'expose AUCUNE IP, AUCUN port : seulement son adresse .onion.
```

Un **réplica** est un binaire Go autonome (`nyx-server`) qui :

1. génère (ou charge) son identité ed25519 dans `<data-dir>/identity.json` (0600) ;
2. publie son **onion service v3** via le control port Tor — l'adresse `.onion`
   dérive de la clé publique : **l'adresse est son identité, immuable** ;
3. n'écoute que sur `127.0.0.1` (API client :8080, API admin :9091) — il n'existe
   aucune porte d'entrée hors Tor ;
4. maintient le **journal d'événements signés** (append-only) et le réplique par
   **gossip chiffré NaCl** avec les autres réplicas (docs/02) ;
5. découvre les nouveaux pairs dynamiquement (`GET /gossip/peers`) — la liste
   statique de bootstrap n'est qu'un point de départ.

## 2. « Dès le lancement d'un client » : la découverte

Quand un client démarre, **rien ne se déploie** — le réseau existe déjà, il le
rejoint :

1. Le Tor embarqué de l'app s'amorce (bannière %).
2. L'app lit sa **liste de rendezvous** embarquée : adresses onion de réplicas
   d'amorçage + leurs clés, **signées par la clé de release Nyx** (introduite en
   v0.2 ; en v0.1 la liste de dev est celle du GUIDE_DEMARRAGE.md).
3. Via SOCKS, elle interroge un rendezvous : `GET /identity` (clé gossip du
   nœud) puis `GET /gossip/peers` (les pairs connus) → **carte à jour du réseau**.
4. Elle choisit un réplica joignable et y publie/lit ses événements ; les
   réplicas propagent par gossip — le client n'a jamais besoin de parler à
   plusieurs serveurs.

> Pourquoi le client ne « déploie » pas les serveurs lui-même : déployer un
> serveur suppose des credentials d'hébergement (VPS…) — les embarquer dans un
> APK public les exposerait. Le provisionnement est une opération **admin**
> (§4) ; le client, lui, ne fait que **découvrir**. C'est la lecture retenue de
> « déploiement dès le lancement d'un client » (ADR-009 §3).

## 3. Hébergement : « rien de local, tout sur le réseau »

| Rôle | Où ça tourne | Ce qui est exposé |
|---|---|---|
| Client messagerie | appareil Android de l'utilisateur | rien (il initie les connexions via Tor) |
| Réplicas nyx-server | hôtes distants anonymes (VPS sans KYC loués en crypto, machines communautaires) | 2 onions v3 par nœud : API client + API admin (onion auth) |
| Console nyx-admin | PC Windows / téléphone Android de l'opérateur | rien (elle initie les connexions via Tor) |

- L'opérateur n'héberge **rien** chez lui : ni serveur, ni IP fixe, ni port.
- Les hôtes sont interchangeables et dispersés (lieux/juridictions/FAI
  différents) ; ≥ 3 réplicas en régime nominal (ADR-001 : chaque réplica reste
  utile isolément, la convergence est garantie dès qu'un chemin existe).
- Un hébergeur compromis ne voit que des ciphertexts et un journal d'événements
  chiffrés ; le contenu des messages reste protégé de bout en bout (E2E).

## 4. Cycle de vie d'un réplica (orchestré depuis nyx-admin)

```
provisionné ──▶ publié ──▶ déclaré ──▶ actif ──▶ (maintenance) ──▶ retiré
```

1. **Provisionner** — l'opérateur fournit à la console les accès d'un hôte
   neuf ; la console/le bundle `deploy/` (v0.2) exécute le script one-shot :
   installation de Tor + `nyx-server`, unités systemd (`Restart=always`), torrc
   durci, génération de l'identité **sur l'hôte** (elle ne transite jamais en
   clair), publication de l'onion, connexion aux réplicas existants.
2. **Publier** — l'onion apparaît ; l'opérateur relève l'adresse
   (`GET /admin/identity`) et la fait certifier par les autres réplicas
   (échange signé — le pair est adopté quand des événements signés par lui
   circulent, docs/02 §4).
3. **Déclarer** — ajout à la liste de rendezvous signée de la prochaine release
   APK + ajout aux `config.json` des pairs (commande admin `/admin/peers`).
4. **Actif** — gossip push/pull toutes les 60 s, resync immédiat après chaque
   événement client ; backoff exponentiel sur les pairs injoignables.
5. **Maintenance** — `/admin/maintenance on|off` : le nœud cesse d'accepter les
   clients mais continue le gossip (drain progressif sans perte).
6. **Retirer** — déclaration aux pairs (`DELETE /admin/peers`), retrait du
   rendezvous, arrêt gracieux (flush journal, fermeture onion), destruction de
   l'hôte. Les données survivent sur les autres réplicas (par conception).

## 5. Réplication et suppression (rappel des invariants)

- **Ajout d'un réplica neuf** : il rejoint le réseau avec `want = {tout: 0}` et
  aspire le journal complet depuis n'importe quel pair (lots de 500) — première
  synchro = streaming logique, aucune intervention manuelle.
- **Resync forcé** : commande admin `POST /admin/peers/resync` (curseurs remis à
  zéro pour un pair).
- **Suppression** : le journal est **append-only** (invariant ADR-001) — pas de
  suppression unitaire, elle casserait les chaînes de séquence et signatures.
  L'élagage se fait **par checkpoint signé** : racine de Merkle du journal
  co-signée par N réplicas ; les événements antérieurs sont archivés hors ligne
  puis élagués. La console admin exécute ce protocole (v0.4 admin) : sélection
  → vote/co-signature → archivage → élagage vérifiable.

## 6. Sécurité du plan de contrôle (résumé — détail ADR-008/APPLI_ADMIN.md)

1. **Deux onions par nœud** : API client (publique, anonyme) ≠ API admin
   (réservée) — surface séparée, périmètre séparé.
2. **Tor ClientAuthorization** sur l'API admin : seuls les appareils déclarés
   (PC + téléphone de l'opérateur) peuvent même établir la connexion.
3. **Commandes signées ed25519** (+ nonce anti-replay, fenêtre horodatage ±120 s)
   ; clé publique déclarée par nœud (`admin.authorized_keys`) → révocable
   individuellement (perte du téléphone = retrait de sa clé).
4. **Journal d'audit** de toutes les commandes admin sur chaque nœud
   (`/admin/log`).
5. Clé admin chiffrée sur les devices (sqlcipher sur Android, DPAPI sur Windows).

## 7. Feuille de route opérationnelle

| Étape | Contenu | État |
|---|---|---|
| v0.1 (actuel) | Déploiement manuel : `scripts/dev-server.sh N` (Tor de dev + N réplicas câblés), GUIDE_DEMARRAGE.md | ✅ livré |
| v0.2 | Bundle `deploy/` : script one-shot + systemd + torrc durci pour hôte distant ; liste de rendezvous signée embarquée dans l'APK | planifié |
| v0.2 admin | API admin dans nyx-server (read-only : status/peers/log) + console nyx-admin Windows/Android | planifié (ADR-008) |
| v0.3 admin | start/stop gracieux + réplication (add/remove/resync) + maintenance | planifié |
| v0.4 admin | élagage par checkpoint signé + rotation d'identité + provisioning assisté | planifié |
