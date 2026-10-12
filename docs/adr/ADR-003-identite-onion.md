# ADR-003 — Identité : l'adresse onion est l'identité

Statut : **Accepté** (2026-10-12)

## Contexte

Trois candidats : (A) identité = paire ed25519 auto-générée dont l'adresse onion v3 est
la clé publique ; (B) comptes serveur classiques ; (C) comptes sur invitation.
Contrainte de fond : anonymat par défaut, zéro donnée personnelle, et à terme un market
qui exige de la réputation.

## Décision

- **v0.1 (messagerie)** : modèle (A). La paire ed25519 est générée sur l'appareil,
  publiée comme service onion v3 par Tor ; l'adresse `…onion` sert d'identifiant ET de
  clé de vérification des signatures. Aucun annuaire, aucune inscription, découverte de
  contacts par échange hors bande (QR / lien `nyx://add`).
- **Phase 2 (market)** : évolution vers (C) — pseudonyme persistant ancré sur l'identité
  onion, chaîne d'invitations signées (graphe de confiance anti-Sybil), réputation
  construite exclusivement sur des escrows réglés (attestations signées des parties).

(B) est rejeté d'office : centralisation, cible juridique, métadonnées d'inscription —
antithèse du projet.

## Conséquences

- ✅ Anonymat maximal ; la vérification cryptographique ne dépend d'aucun tiers.
- ✅ Identité et point d'écoute confondus : l'acheminement P2P direct (v0.2) est natif.
- ⚠️ Clé perdue = identité perdue : mitigation v0.2 par sauvegarde chiffrée (phrase
  BIP39) ; phase 2 éventuelle : garde partagée (Shamir 2-of-3 entre réplicas de confiance).
- ⚠️ Identités gratuites et jetables → Sybil : sans gravité pour la messagerie 1:1,
  critique pour le market → d'où l'évolution (C) à la phase 2.
- ⚠️ Pas de récupération par e-mail : assumé, c'est le prix de l'anonymat (documenté
  côté UX avec avertissement de sauvegarde à l'onboarding).
