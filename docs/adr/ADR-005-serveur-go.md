# ADR-005 — Serveur en Go

Statut : **Accepté** (2026-10-12)

## Contexte

Le nœud serveur doit : piloter le control port Tor (publier un onion service v3 avec une
clé ed25519 donnée), servir une API HTTP, répliquer par gossip chiffré NaCl, tourner sur
des VPS minimaux, évoluer vers le market (phase 2). Candidats : Go, Python, Rust.

## Décision

**Go** (≥ 1.22), bibliothèque standard + `golang.org/x/crypto` (nacl, ed25519) +
`golang.org/x/net` (SOCKS5). Aucun framework web — `net/http` suffit.

| Critère | Go | Python | Rust |
|---|---|---|---|
| Binaire déployable | ✅ statique unique | ⚠️ runtime + deps | ✅ |
| Control port Tor | ✅ socket texte simple | ✅ (stem) | ⚠️ |
| NaCl/libsodium équivalent | ✅ x/crypto/nacl | ⚠️ binding PyNaCl | ✅ (sodium) |
| Concurrence (gossip + API + GC) | ✅ goroutines natives | ⚠️ GIL | ✅ |
| Vitesse de développement | ✅ | ✅✅ | ⚠️ |

Rust (arti) est le choix le plus « Tor-natif » mais son coût de développement est
incompatible avec le besoin de débloquer vite un POC ; il reste envisageable en v1.x
pour un nœud embarqué arti — les specs filaires sont agnostiques du langage.

## Conséquences

- ✅ Déploiement : copier un binaire + démon Tor ; idéal pour des réplicas peu coûteux.
- ✅ Le module `internal/` isole tor / journal / gossip / api : le remplacement d'une
  couche n'affecte pas les autres.
- ⚠️ Le control port minimaliste est implémenté maison (~200 lignes) plutôt que d'ajouter
  une dépendance : surface d'audit réduite, mais à tester rigoureusement (AUTHENTICATE
  cookie, parsing des réponses 250).
- 🔎 Phase 2 : le séquenceur `tx` (ADR-006) s'ajoutera comme package `internal/seq`
  sans modifier le format filaire des événements de messagerie.
