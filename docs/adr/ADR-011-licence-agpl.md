# ADR-011 — Licence du projet : AGPL-3.0-only

Statut : **Accepté** — session 12 (2026-10-12)
Décideurs : opérateur (délégation au choix technique le plus adapté)

## Contexte

Le projet n'avait aucune licence explicite : par défaut du droit d'auteur, le
code publié sur GitHub était *« all rights reserved »* — ni réutilisable, ni
redistribuable légalement, ce qui contredit l'esprit d'un projet anonyme et
ouvert. La demande de l'opérateur (session 12) : « définis la license selon ce
qui te paraît le plus adapté ».

Le projet a trois composantes aux profils différents :

1. **nyx-server** (Go) — un service réseau destiné à être hébergé par des
   tiers (ADR-009 : hôtes distants anonymes) ;
2. **l'application Android** (Kotlin) — client final distribué en APK ;
3. **le protocole** (`protocol/specs/`) — documentation de format, destinée à
   être implémentée par des tiers (clients alternatifs, réplicas compatibles).

Le choix doit tenir compte du modèle de menace propre au projet : un serveur
Nyx est un service réseau — la faille classique de la GPL v2/v3 seule est le
« SaaS loophole » (quelqu'un héberge un fork modifié du serveur sans publier
les sources ; les utilisateurs ne peuvent pas vérifier ce qui leur répond).

## Options considérées

| Option | Avantages | Inconvénients |
|---|---|---|
| MIT / Apache-2.0 | maximaliste, adoption facile | tout fork fermé possible — y compris du serveur hébergé |
| GPL-3.0-only | copyleft fort | ne couvre pas l'hébergement en service (SaaS) |
| **AGPL-3.0-only** | copyleft + clause réseau (§13) : héberger un fork modifié oblige à offrir les sources aux utilisateurs du service | légèrement plus lourd pour les intégrateurs commerciaux |
| BSD-2 (style Tor) | compatible Tor Core | aucun copyleft |

## Décision

**AGPL-3.0-only** pour l'ensemble du dépôt public (serveur, client Android,
specs), et pour le dépôt privé `nyx-admin` (sa licence n'a d'effet que le jour
où il serait publié, mais autant l'aligner dès maintenant).

Précédents pertinents : Briar (AGPL-3.0), Signal (AGPL-3.0), Cwtch, GNU
Taler — tous des outils d'anonymat/communication privée, pour exactement la
même raison : garantir que tout nœud auquel un utilisateur confie des messages
reste auditable par cet utilisateur.

## Conséquences

- `LICENSE` (texte intégral AGPL-3.0) à la racine du dépôt public et du dépôt
  privé ; mention dans les README (racine, android/, server/).
- Le protocole (`protocol/specs/`) reste **librement implémentable** : une spec
  de format n'est pas du logiciel couvert ; implémenter le format filaire dans
  un autre projet n'en fait pas un dérivé. Un client compatible n'a donc pas à
  devenir AGPL — seule la réutilisation du CODE de ce dépôt est copyleftée.
- Copyright : « The Nyx Project Contributors » — pas de nom personnel
  (règle §9.7 : zéro information personnelle dans le public, ADR-010).
- La console `nyx-admin` étant PRIVÉE, sa distribution (Drive /livrables)
  n'est pas une distribution au sens AGPL ; si elle devenait publique, elle
  l'est sous AGPL-3.0 et ses sources seraient offertes.
- La chaîne de build vérifie l'existence du fichier LICENSE (audit COH-LIC,
  session 12) pour éviter une régression.
