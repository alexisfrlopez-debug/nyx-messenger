# ADR-009 — Hébergement des réplicas : 100 % réseau, zéro local

Statut : **Accepté** (2026-10-12, demande utilisateur session 8)

## Contexte

Exigence de l'opérateur : **« les serveurs se déploient en service caché dès le
lancement d'un client, afin qu'il n'y ait rien de local, tout hébergé sur le
réseau »**. Autrement dit :

- aucun serveur chez l'opérateur (pas de machine dédiée, pas d'IP fixe chez lui) ;
- les réplicas vivent sur des **hôtes distants** (VPS anonymes loués en crypto,
  machines de la communauté) et ne sont exposés **que** comme hidden services ;
- l'infrastructure doit être **déjà en place et joignable au lancement d'un
  client** — le client ne déploie rien, il découvre le réseau.

## Décision

1. **Un réplica Nyx = un hidden service v3, rien d'autre.** Le binaire `nyx-server`
   n'écoute que sur `127.0.0.1` (API client + API admin) ; toute connectivité
   passe par l'onion service publié via le control port Tor avec la clé ed25519
   du nœud (l'adresse onion **est** son identité — ADR-003/005). Aucun port
   public, aucune IP claire, aucun DNS.
2. **Hôtes distants anonymes** : VPS sans KYC payés en crypto, ou machines
   bénévoles. Le bundle de déploiement (`deploy/`, v0.2) installe tor + nyx-server
   en une commande (script one-shot + unités systemd + torrc durci), génère
   l'identité sur l'hôte (elle ne transite jamais en clair), publie l'onion et
   rejoint les pairs existants.
3. **Découverte au lancement du client** : l'APK embarque une **liste de
   rendezvous signée** (adresses onion de réplicas d'amorçage + clés publiques,
   signée par la clé de release Nyx). Au démarrage : Tor embarqué → SOCKS →
   `GET /identity` + `GET /gossip/peers` sur un rendezvous → liste à jour des
   réplicas, enrichie dynamiquement (mécanisme déjà implémenté, docs/02 §4).
   « Rien de local » : le device ne fait tourner que le client — les données et
   l'infrastructure restent sur le réseau.
4. **Orchestration** : le provisionnement, le retrait et la surveillance des
   réplicas sont pilotés à distance par la console **nyx-admin** (ADR-008) ;
   l'opérateur n'ouvre jamais de shell et n'héberge jamais lui-même.
5. **Résilience** : ≥ 3 réplicas sur des hôtes/lieux/juridictions distincts
   (journal+gossip tolère les intermittences — ADR-001) ; un réplica retiré est
   remplacé par un nouveau provisionnement, le réseau re-converge seul.

> **Mise à jour (2026-10-13, ADR-012)** : le bundle de déploiement est réalisé
> et piloté depuis la console nyx-admin (assistant « Créer un réplica »).
> L'identité est forgée **par la console** (et non sur l'hôte) — amendement
> motivé et borné dans ADR-012 §2 : l'hébergeur lit le disque de toute façon ;
> la mitigation contre un hébergeur malveillant reste le remplacement du
> réplica (§5).

## Conséquences

- ✅ Aucune dépendance à une machine de l'opérateur ; le réseau survit à son
  absence (l'appli admin n'est nécessaire que pour opérer, pas pour vivre).
- ✅ Aucune surface exposée hors Tor, sur les réplicas comme chez l'opérateur.
- ⚠️ Confiance fractionnée dans les hébergeurs (ils voient des ciphertexts et un
  journal d'événements chiffrés, pas les contenus) : mitiger par le nombre et la
  dispersion des réplicas, pas par un contrat.
- ⚠️ Disponibilité = somme des disponibilités des hôtes ; le rendezvous embarqué
  doit toujours contenir ≥ 2 réplicas vivants → liste mise à jour à chaque release
  + enrichissement dynamique au premier contact.
- ⚠️ Le « déploiement dès le lancement du client » est donc une **découverte**,
  pas un provisionnement par le client : déployer depuis un téléphone exposerait
  des credentials d'hébergement dans l'APK — refusé. Le provisionnement reste une
  opération admin (ADR-008).
