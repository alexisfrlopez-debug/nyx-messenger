# ADR-015 — Appels audio/visio P2P sur onion (feuille de route)

Statut : accepté (architecture) — implémentation reportée à v0.2.18+
Date : 2026-10-17

## Contexte

Demande opérateur : « j'aimerais implémenter toutes les "feature" qu'on a sur
les autres messengers (partage photo/video, partage de fichier, appels visio
etc...) ». Le partage de médias est livré en v0.2.17 (ADR implicite : fragments
« msg » E2E). Les appels temps réel méritent une décision d'architecture
propre : Tor impose des contraintes que Signal/WhatsApp n'ont pas, et la
réponse « WebRTC comme tout le monde » n'existe pas ici.

## Contraintes (non négociables)

1. **Onion-only** (ADR-007) : aucun trafic hors Tor, aucun révélateur d'IP.
2. Tor = TCP uniquement (pas d'UDP) → **ICE/STUN classiques sont morts** ;
   TURN-over-UDP de WebRTC ne peut pas servir tel quel.
3. Latence circuit onion : RTT typique 0,5-3 s par saut applicatif ; le médian
   doit être le chemin le plus court possible.
4. Les réplicas (VM opérateur) ne doivent PAS être dans le chemin média de
   bout en bout en régime normal (coût bande passante + confidentialité) —
   c'est un REPLI, pas la base.

## Décision

### Signalisation (zéro changement serveur)

Nouveau type d'événement adressé **« call »** (corps chiffré E2E opaque,
type à ajouter à la whitelist serveur — UN commit Go, UN déploiement réplica)
ou, pour éviter tout déploiement, réutilisation du type « msg » avec un corps
JSON signalé `{"v":1,"kind":"call", …}` (comme les fragments v0.2.17).
Messages de signalisation :

- `{"kind":"call","op":"offer","cid":…,"sdp":…,"audio":true,"video":false}`
- `{"kind":"call","op":"answer","cid":…,"sdp":…}`
- `{"kind":"call","op":"bye","cid":…,"reason":…}`
- `{"kind":"call","op":"ring","cid":…}` (sonnerie entrante + notification)

La latence de signalisation = poll inbox 15 s (ou fenêtre rapide 5 s) —
acceptable pour la DECROCHEMENT (l'établissement média prend le relais, la
sonnerie reste un push léger). Interdiction du poll comme transport média.

### Média — trois chemins, par ordre de préférence

1. **Direct onion-to-onion (cible v0.2.18)** : les DEUX clients publient déjà
   un service onion (identité). L'appelant ouvre une connexion SOCKS → onion
   du appelé sur un port média dédié (ADD_ONION `Port=<port>,<local>`), et
   transporte RTP/SRTP (ou un framing simple audio-over-TLS) par ce canal TCP.
   Latence = un circuit Tor (1 saut applicatif). Audio d'abord ; vidéo ensuite
   (H.264/VP8 encodage matériel, résolution réduite).
2. **Relais TURN TCP RFC 6062 (repli, déjà en dépôt)** : `server/internal/turn`
   (ADR-014, phase 2) expose une allocation relais TCP stdlib — le client
   réserve une allocation via le réplica et le média transite relais-onion.
   Ce chemin sert quand le direct échoue (NAT opérateur agressif côté tor,
   appareil sans onion publié).
3. **Jamais de serveur tiers** (coturn public, STUN public) — violation de
   l'ADR-007.

### Codec & budget

- Audio : Opus 16-24 kbps mono (Android MediaCodec Opus ou libopus embarquée).
- Video : VP8/H264 matériel, 320×480 @ 15 fps cible, ≤ 300 kbps.
- Overhead Tor : chaque paquet paie le circuit — MTU applicatif ~1400, batch
  d'envoi 40-60 ms pour amortir (latence ajoutée acceptée : < 100 ms).

### Verrous UI

- Permission RECORD_AUDIO (déjà obtenue pour les notes vocales v0.2.17) et
  CAMERA (à demander à l'usage visio uniquement).
- Écran d'appel dédié + notification de premier plan (type `specialUse` déjà
  en place pour tor — un second canal de notification pour les appels).
- Le verrou contact mutuel s'applique aux appels (même règle que les messages).

## Conséquences

- v0.2.18 visé : signalisation « call » + audio direct onion-to-onion
  (un seul sens audio, mockup écran d'appel, tests e2e sandbox avec 2 clients).
- La vidéo n'est PAS un objectif de v0.2.18 — l'audio stable prime (retour
  terrain mesurable sur les deux téléphones).
- Le relais TURN (chemin 2) s'activera derrière un flag réplica après
  validation du chemin direct.
- La latence Tor reste la limite honnête : un appel fonctionnel mais avec
  1-2 s de bout en bout doit être annoncé comme tel au terrain (c'est le prix
  de l'anonymat onion — ADR-010 documente déjà des limites analogiques).
