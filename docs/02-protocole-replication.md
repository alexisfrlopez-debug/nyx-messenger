# Protocole de réplication — Journal signé + gossip (v0.1)

## 1. Pourquoi ce modèle

Les trois candidats évalués (voir ADR-001) : journal+gossip, Raft, quorum 2/3.

Raft et le quorum exigent des échanges synchronisés entre serveurs — or sur Tor, chaque
connexion coûte 1 à 3 s et un hidden service peut être injoignable plusieurs minutes.
Le journal+gossip est le seul modèle où **chaque réplica reste utile isolément** : il
continue d'accepter des événements, et se resynchronise quand les pairs reviennent.
C'est le modèle de Secure Scuttlebutt et d'IPFS (IPLD logs), éprouvé pour des réseaux
intermittents — exactement le profil de Tor.

## 2. Invariants du journal

1. **Append-only** : un événement journalisé n'est jamais modifié ni supprimé (v0.1).
2. **Séquencé par auteur** : `seq` strictement croissant, sans trou, par auteur.
3. **Signé** : signature ed25519 vérifiable avec l'adresse onion de l'auteur.
4. **Content-addressé** : `id = sha256(représentation canonique)` — identifiant stable,
   utilisé pour la dé-duplication (un événement reçu deux fois est ignoré silencieusement).
5. **Répliqué à l'identique** : la réplication transmet les événements, pas un état —
   chaque réplica re-derive l'état (boîtes de réception, contacts) en rejouant le journal.

## 3. Algorithme gossip (v0.1, anti-entropie push/pull)

```
Boucle par pair P (toutes les 60 s, et immédiatement après chaque POST /client/event) :
  1. PUSH : envoyer à P tous les événements {author: a, seq > curseur[P][a]}
     (curseur = dernier seq déjà envoyé à P pour cet auteur), par lots de 500.
  2. PULL : demander à P tous les événements {author: a, seq > mon_curseur[a]}.
  3. Valider chaque événement reçu (signature, seq, taille) ; insérer ou ignorer.
  4. Mettre à jour les curseurs.
```

Propriétés :

- **Convergence garantie** : tout événement accepté par au moins un nœud finit sur tous
  les nœuds connectés (par induction des push/pull successifs).
- **Idempotence** : tous les échanges sont re-jouables sans effet de bord (curseurs + `id`).
- **Frais de démarrage** : un réplica neuf rejoint le réseau avec `want = {tout: 0}` et
  récupère le journal complet depuis n'importe quel pair (snapshot logique par streaming
  de lots de 500).

## 4. Découverte des pairs

- Liste statique de bootstrap dans la configuration (`config.json : peers`).
- Enrichissement dynamique : `GET /gossip/peers` renvoie les pairs connus du pair
  interrogé ; le nœud les ajoute à sa liste (avec leur clé gossip publique — jamais
  prise sur parole du pair, mais vérifiée par le fait qu'au moins un événement signé
  par cet auteur circule déjà).

## 5. Croissance et limites

- Chaque réplica doit conserver l'intégralité du journal en v0.1 : la croissance est
  linéaire avec le trafic global. Horizon v0.2 : élagage par checkpoint signé
  (un checkpoint = racine d'un arbre de Merkle du journal, signé par N réplicas ;
  les événements antérieurs au checkpoint peuvent être archivés hors ligne).
- Détection de pairs défaillants : backoff exponentiel sur les erreurs (30 s → 30 min),
  réactivation par la boucle de découverte.
- La latence Tor n'affecte que la **propagation** (les clients parlent déjà à un réplica
  joignable) ; elle n'affecte ni l'acceptation, ni l'ordre, ni l'intégrité.

## 6. Extension prévue pour le market (phase 2, ADR-006)

Les opérations transactionnelles (`tx` : création de commande, libération d'escrow,
arbitrage) exigeront qu'un événement `tx` ne soit accepté par un réplica **que** s'il
porte un certificat de séquencement : 2 des 3 signatures de serveurs d'un pool dédié
(ou un « leader » élu parmi les réplicas pour les `tx`, avec bascule lente acceptée car
le chemin critique commercial tolère des secondes). La messagerie, elle, continue de
circuler sans aucune coordination — le ralentissement du module market n'impacte pas
le chat.
