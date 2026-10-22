# ADR-010 — Modération anti-abus et biométrie : limites de collecte

Statut : **Accepté** (2026-10-12, session 9 — cadrage demandé par l'opérateur)

## Contexte

L'opérateur du réseau doit pouvoir gérer les abus (spam, harcèlement, activités
illégales). Deux orientations ont été examinées :

1. **Collecte d'éléments d'identité réels à l'insu des utilisateurs** (ex. :
   capture photo à la création d'identité, envoyée au réplica, stockée par
   l'opérateur pour modération) — proposée puis évaluée.
2. **Biométrie comme authentification des applications** (client messagerie et
   console nyx-admin) — demandée et acceptée, sous condition de localité.

## Décision 1 — REJET de toute collecte d'identité réelle, dissimulée ou non consentie

Le projet n'implémentera **jamais** : capture photo/vidéo/audio de l'utilisateur,
KYC, contacts, clipboard, géolocalisation ou tout autre élément d'identité
réelle, envoyés à un réplica — **et plus particulièrement pas de façon
non documentée, non consentie ou dissimulée**. Motifs :

- **Architecture** (contradiction frontale) : Nyx est **sans compte** — la
  création d'identité génère une paire de clés locale (ADR-003), le client
  n'envoie au réplica que des événements signés chiffrés (ADR-001/002) ; « les
  clés d'identité ne quittent jamais l'appareil » (§9.2). Un réplica ne peut pas
  « classer les informations du compte » : il n'y a pas de compte et rien
  d'identifiant ne lui parvient. La fonction demanderait donc de créer un flux
  d'identification qui détruit la promesse du produit.
- **Légal** (contexte UE/France, distribution publique) : les données
  biométriques sont des données sensibles (RGPD art. 9) — traitement interdit
  sans consentement explicite, lui-même imposé par une information claire
  (art. 12-14) ; une capture à l'insu de la personne constitue une atteinte à la
  vie privée (infraction pénale) ; une fonction de surveillance dissimulée dans
  une app distribuée publiquement est qualifiable de logiciel espion
  (stalkerware), avec sanctions administratives (CNIL) et pénales pour
  l'éditeur. La dissimulation (« n'apparaît dans aucune documentation ») est un
  aggravant, pas une protection.
- **Convention de nommage des intentions** : la modération n'est pas un motif
  recevable pour une collecte cachée. Toute collecte future éventuelle devra :
  être documentée publiquement, faire l'objet d'un consentement explicite, être
  minimisée, et faire l'objet d'un nouvel ADR — et la messagerie resterait E2E
  (les réplicas n'ont pas accès aux contenus).

Garde-fou technique (déjà actif) : l'audit §9.1 bloque toute permission hors
liste fermée (`AUT-PERM`, CRIT) — `CAMERA`/`RECORD_AUDIO` ne peuvent pas entrer
dans le manifest du client sans un changement explicite et documenté de cette
règle.

## Décision 2 — Modération par mécanismes protocolaires TRANSPARENTS

La gestion des abus s'appuie sur le protocole, jamais sur l'identification des
personnes :

| Mécanisme | Principe | Phase |
|---|---|---|
| **Signalements signés** | un utilisateur émet un événement `report` (signature vérifiable, quota limité) | v0.2 |
| **Blocklists de réplicas** | un auteur signalé par N sources distinctes peut être exclu du relais/stockage par un réplica ; les listes se partagent entre réplicas (même gossip) ; action auditable et réversible | v0.2 |
| **Orphanage** | les réplicas cessent de stocker/propager un auteur banni ; son journal reste intact chez ceux qui le conservent (pas de réécriture d'histoire — invariant append-only respecté) | v0.2 |
| **Rate-limiting / preuve de travail** | coût à la création d'identité et à l'envoi pour limiter spam/sybil | v0.3 |
| **Market** (phase 2) | escrow multisig + réputation portée par les événements signés — la protection est protocolaire, pas identitaire (ADR-006) | phase 2 |

Limitation assumée : un utilisateur banni peut régénérer une identité (c'est le
prix de l'anonymat) ; le rate-limiting/PoW en augmente le coût. Les contenus E2E
restant inaccessibles aux réplicas, la modération est **comportementale et
métadonnée**, jamais de contenu.

## Décision 3 — Biométrie : authentification LOCALE uniquement, opt-in

La biométrie est acceptée comme **verrouillage local des applications** — jamais
comme collecte :

- **Client Android** : `androidx.biometric` (BiometricPrompt, biométrie classe 2/3,
  repli code PIN de l'appareil) pour verrouiller l'accès à l'app (ouverture +
  retour au premier plan) et autoriser les actions sensibles. L'OS effectue la
  vérification ; l'app ne reçoit qu'un succès/échec — **aucune donnée
  biométrique n'est lue, stockée ou transmise** (les empreintes/visages restent
  dans le Keystore/TEE de l'appareil). Opt-in à l'onboarding, désactivable.
- **Console nyx-admin (ADR-008)** : Windows Hello (déverrouillage de la clé
  admin protégée DPAPI/TPM) côté Windows ; BiometricPrompt côté APK admin
  (déblocage de la clé admin sqlcipher). La clé admin elle-même reste ed25519.
- **Le serveur Go lui-même** : aucun capteur, aucun utilisateur en face — la
  biométrie ne s'applique qu'aux applications qui y accèdent (client, console).
  Aucune authentification biométrique ne transite sur le fil.

## Conséquences

- ✅ Le projet reste cohérent avec ses ADR (002/003/005) et son modèle de menace ;
  il reste publiquement documenté sans fonction dissimulée.
- ✅ L'opérateur dispose d'un cadre de modération réel et légal (blocklists,
  signalements, PoW) — spécification détaillée à l'ouverture de la phase
  concernée (à faire évoluer via `protocol/specs/` D'ABORD, règle §5).
- ⚠️ Nyx n'est **pas** un outil de contrôle des personnes : si l'objectif était
  d'identifier les utilisateurs de façon certaine, il faudrait un produit
  différent (comptes vérifiés + conformité RGPD complète + absence d'anonymat)
  — décision de refonte explicite requise (rouvrir ADR-003), jamais par porte
  dérobée.
