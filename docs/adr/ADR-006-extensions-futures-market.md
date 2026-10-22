# ADR-006 — Extensions futures : market store (phase 2) et trade crypto (phase 3)

Statut : **Accepté** (2026-10-12) — décisions d'anticipation, implémentation différée

## Contexte

La trajectoire produit est : messagerie (v1) → market store entre pairs (phase 2) →
trade de cryptomonnaies (phase 3). L'architecture v0.1 doit préparer ces extensions
sans les construire prématurément ni verrouiller des choix incompatibles.

## Décisions d'anticipation

1. **Format d'événements extensible** : le champ `type` est un discriminant ouvert
   contrôlé (`msg`, `contact`, `prekey`, `meta` en v0.1 ; `listing`, `order`, `tx`,
   `attest` à la phase 2). La validation du journal est par type.
2. **Deux régimes de cohérence** :
   - messagerie / catalogue : journal + gossip (cohérence éventuelle) — inchangé ;
   - opérations transactionnelles (`order`, `tx`, escrow) : **séquenceur/quorum dédié**
     (2-of-3 serveurs d'arbitrage signent un certificat de séquencement attaché à
     l'événement `tx`), package `internal/seq`. Un réplica rejette tout `tx` sans
     certificat valide. Le chat ne transite jamais par ce chemin (isolation des pannes).
3. **Escrow multisig 2-of-3** (acheteur / vendeur / arbitre) : le serveur d'arbitrage
     ne détient que sa clé d'arbitrage ; jamais de clés de wallet utilisateur sur les
     serveurs ; litiges arbitrés par messagerie Nyx (preuves = événements signés).
4. **Réputation** : uniquement des attestations signées liées à des escrows réglés ;
     chaîne d'invitations signées pour élever le coût des Sybil ; aucun avis libre.
5. **Réglementaire (garde-fou)** : phase 3 « trade » — crypto↔crypto par atomic swaps
     seulement au départ ; tout service impliquant du **fiat** est réglementé
     (France : PSAN/AMF, KYC/AML) et incompatible avec l'anonymat : il devra faire
     l'objet d'une décision explicite (entité séparée, juristes) AVANT développement.
     Ce point est un frein volontaire, pas un détail technique.

## Conséquences

- ✅ Aucune refonte du journal nécessaire pour la phase 2 : les types s'ajoutent.
- ✅ L'isolation des régimes de cohérence protège la messagerie des lenteurs du market.
- ⚠️ Le quorum `tx` introduit un sous-ensemble de serveurs privilégiés (arbitres) :
  leur sélection et leur gouvernance seront un ADR dédié à la phase 2.
- 🔎 Référence architecturale : OpenBazaar (escrow multisig 2-of-3, réputation liée aux
  transactions) — fermé en 2020 mais documents d'architecture exploitables.
