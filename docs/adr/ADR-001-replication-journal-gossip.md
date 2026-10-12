# ADR-001 — Réplication par journal signé + gossip

Statut : **Accepté** (2026-10-12)

## Contexte

Les serveurs doivent être des onion services Tor capables de se répliquer. Tor impose des
contraintes fortes : latence de 1 à 3 s par aller-retour, publication d'un hidden service
lente (descripteurs, points d'introduction), joignabilité intermittente. Trois candidats :

| | Journal + gossip | Raft (leader) | Quorum 2/3 |
|---|---|---|---|
| Cohérence | Éventuelle | Forte | Forte |
| Tolère les coupures Tor | ✅ native | ❌ bascule lente | ⚠️ écritures bloquées si < 2 nœuds |
| Résistance à la saisie d'un nœud | ✅ aucun privilège | ❌ leader ciblable | ✅ |
| Complexité | Moyenne | Moyenne | Élevée |
| Convient à la messagerie asynchrone | ✅ | ⚠️ | ⚠️ |

## Décision

Chaque nœud maintient un journal append-only d'événements signés, séquencé par auteur.
Réplication par anti-entropie push/pull périodique, chiffrée et authentifiée NaCl.
Pas de leader, pas de quorum en v0.1.

## Conséquences

- ✅ Chaque réplica reste utile isolément ; aucun single point of failure ; ajout de
  réplicas trivial.
- ⚠️ Cohérence éventuelle : acceptable pour la messagerie (l'ordre par auteur est la
  seule sémantique nécessaire).
- ⚠️ Le futur market exigera une couche transactionnelle dédiée (`tx` à cohérence forte,
  séquenceur/quorum réservé aux commandes/escrow) — ADR-006. Le chat n'en dépendra jamais.
- 🔎 Alternative rejetée : Raft — son incompatibilité avec les bascules lentes de Tor en
  ferait un SPOF de fait pendant chaque élection.
