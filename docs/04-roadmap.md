# Roadmap Nyx

## Phase 1 — Messagerie (v0.1 → v1.0)

### v0.1 — POC bout en bout *(session en cours)*
- [x] Décisions d'architecture actées (ADRs 001–006)
- [x] Spécifications filaires (journal, API)
- [x] Nœud Go : onion service via control port, journal signé, gossip NaCl, API client
- [x] Squelette Android Kotlin/Compose (identité, Tor, DB, UI, client réplica)
- [ ] Compilation du nœud Go et test 3 réplicas locaux (Tor)
- [ ] App Android : écran onboarding + envoi 1:1 fonctionnel via Orbot
- [ ] Dépôt GitHub `nyx-messenger` créé et pousse initial

### v0.2 — Fondations sécurité complètes
- [ ] Signal Protocol (X3DH + Double Ratchet) via libsignal, remplace la sealed box
- [ ] Chiffrement du champ `to` (filtrage client) — supprime la métadonnée de routage
- [ ] IPtProxy embarqué (Tor natif dans l'app, sans Orbot obligatoire)
- [ ] Purge serveur des événements TTL ; GC du journal
- [ ] Push P2P direct entre clients (onion ↔ onion) en plus du polling
- [ ] Sauvegarde de l'identité par phrase secrète (BIP39) — « clé perdue = compte perdu »

### v0.3 — Robustesse réseau
- [ ] Checkpoints signés du journal (élagage, reprise rapide des réplicas neufs)
- [ ] Backoff/monitoring des pairs ; métriques (taille journal, latence propagation)
- [ ] Groupes (sender keys), pièces jointes chiffrées (chunks)
- [ ] Tests d'intrusion d'un réplica hostile (fork du journal, rejeux, Sybil)

### v1.0 — Première version utilisable
- [ ] 3 réplicas en production (VPS séparés, Tor persistant), liste de bootstrap signée
- [ ] F-Droid : build reproductible (gradle reproducible + signature)
- [ ] Documentation utilisateur ; audit croisé du modèle de sécurité

## Phase 2 — Market store (v1.x → v2.0) *(prévisionnel)*

1. **Identité commerciale** : pseudonyme persistant (dérivé de l'identité onion), chaîne
   d'invitations signées, réputation construite **uniquement** sur les escrows réglés.
2. **Catalogue** : événements `listing` signés (titre, prix, description, media chiffrés).
3. **Commandes** : événements `tx` à cohérence forte — séquenceur/quorum dédié
   (2-of-3 serveurs d'arbitrage), jamais via le gossip pur.
4. **Escrow** : multisig 2-of-3 (acheteur, vendeur, arbitre) — Monero (natif Tor) et/ou
   Lightning ( Tor-friendly) ; le serveur d'arbitrage ne détient que sa clé d'arbitrage.
5. **Litiges** : flux d'arbitrage par messagerie Nyx (preuves = événements signés).

## Phase 3 — Trade cryptomonnaie (v2.x → v3.0) *(prévisionnel — volet réglementaire d'abord)*

- **Sans fiat** (possible techniquement dès la phase 2) : échanges crypto↔crypto entre
  pairs avec atomic swaps (Monero ↔ Bitcoin/Lightning), modèle décentralisé.
- **Avec fiat** : service réglementé — en France, enregistrement PSAN (AMF), KYC/AML,
  conformité DSA pour la marketplace. **Incompatible avec l'anonymat total** : à décider
  explicitement (entité séparée, juristes, périmètre géographique) AVANT tout
  développement. Recommandation : phase 3 = uniquement crypto↔crypto + escrow ; le fiat
  passe par des plateformes réglementées externes en lien sortant.

## Suivi des sessions de travail

Chaque session agent met à jour : les cases ci-dessus, le journal des sessions dans
`RESTART.md` (§ Journal), et le worklog partagé si existant.
