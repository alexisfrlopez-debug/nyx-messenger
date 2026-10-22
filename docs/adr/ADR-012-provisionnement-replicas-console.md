# ADR-012 — Provisionnement des réplicas depuis la console (création autonome)

Statut : **Accepté** (2026-10-13, demande utilisateur session 14)

## Contexte

ADR-009 (§2 et §4) prévoit que le provisionnement des réplicas soit piloté par la
console nyx-admin (dépôt privé) : « l'opérateur n'ouvre jamais de shell et
n'héberge jamais lui-même ». Le bundle de déploiement (`deploy/`, ADR-009 §2)
n'était pas encore réalisé ; jusqu'ici l'ajout d'un réplica dans la console
supposait un nœud **déjà déployé** (onion d'admin relevé dans les logs, clé
client-auth transmise hors bande). Demande opérateur (session 14) :

> « concernant les replicas, je veux qu'ils puissent se créer seuls dans la
> console admin quand je clique sur "ajouter replica" mais garder la
> possibilité d'en ajouter des existants »

Contraintes structurelles :

- la console ne parle **qu'au plan de contrôle** (`/admin/*` sur onion dédié) ;
  elle ne peut ni louer un VPS, ni exécuter quoi que ce soit sur un hôte absent ;
- un réplica non déployé n'a ni onion, ni hôte : la « création » ne peut donc
  pas être une connexion — c'est la **préparation exhaustive de tout ce que
  l'hôte devra être** ;
- ADR-009 §2 exigeait que l'identité soit « générée sur l'hôte (elle ne transite
  jamais en clair) ».

## Décision

1. **Assistant « Créer un réplica » dans la console.** Le bouton
   « Ajouter un réplica » propose désormais deux voies :
   - **Créer** — la console forge l'intégralité de l'identité du futur réplica
     (clé onion ed25519 → adresse client, identité onion d'admin dédiée, clé
     gossip X25519, paire client-auth x25519 de l'onion d'admin), puis produit
     un **bundle de déploiement** `nyx-replica-<libellé>-<horodatage>.tar.gz`
     contenant : le binaire `nyx-server` (linux/amd64), `identity.json` +
     `admin_identity.json` (pré-remplis — `LoadOrCreate` du serveur les charge
     tels quels), `config.json` (pairs = parrain sélectionné, clé admin de la
     console dans `admin.authorized_keys`), un `torrc` durci, l'unité systemd
     `nyx-server.service` (durcies), un `install.sh` one-shot (Debian/Ubuntu :
     tor, utilisateur dédié, permissions, systemd) et un `LISEZMOI.txt` +
     `SHA256SUMS`. L'opérateur copie le bundle sur l'hôte et lance **une seule
     commande** — l'ADR-009 §2 (bundle one-shot) est réalisé, piloté par la
     console.
   - **Existant** — flux inchangé (URL onion d'admin + clé client-auth), pour
     les nœuds provisionnés par d'autres moyens ou déjà en place.
2. **Amendement ADR-009 §2 — l'identité est forgée par la console, pas par
   l'hôte.** Raison honnête : un hébergeur qui lit le disque du VPS lit
   *de toute façon* `identity.json` après installation — générer la clé sur
   l'hôte ne protège que contre l'interception *pendant le transit*, négligeable
   devant l'exposition permanente sur disque. Le maillon opérateur (transfert
   du bundle) reste sous son contrôle (scp/SSH, suppression après installation).
   La vraie mitigation contre un hébergeur malveillant reste ADR-009 §5 :
   **remplacer le réplica** par un re-provisionnement ailleurs (la flotte
   re-converge seule). L'exigence « ne transite jamais en clair » est remplacée
   par : *transit chiffré (onion + SSH), secret sur disque hôte inévitable et
   assumé, révocation par remplacement*.
3. **Cycle de vie « en attente → actif » automatique.** Le réplica créé est
   inscrit dans la console avec l'état `pending` : la console connaît déjà ses
   deux adresses onion et sa clé client-auth (elle les a générées). À chaque
   rafraîchissement, si le nœud devient joignable : passage automatique en
   `actif`, puis **câblage automatique dans la flotte** — pour chaque réplica
   joignable existant : `GET /admin/identity` (onion client + gossip_pub) puis
   `POST /admin/peers` dans les deux sens (persistance config.json côté nœuds,
   re-déclaration inoffensive ignorée). Aucun shell, aucune saisie manuelle :
   l'ajout d'un pair est corrigé au passage (le `gossip_pub` est désormais
   récupéré automatiquement sur `GET /identity` du pair — API client publique —
   au lieu du booléen erroné envoyé jusqu'ici, rejeté 400 par le serveur).
4. **Barrière n°1 côté service (client-auth Tor de l'onion d'admin) : report
   documenté.** La publication des onions par le nœud passe par le control port
   (`ADD_ONION`), qui ne permet pas de déclarer `HiddenServiceAuthorizedClients`
   (torrc uniquement). La barrière n°2 (signature ed25519 + nonce sur chaque
   commande) protège déjà toutes les routes `/admin/*` ; la console génère et
   conserve malgré tout la paire client-auth, et le bundle embarque la clé
   publique (`admin_client_auth.pub`) — l'activation côté service se fera avec
   le passage à un torrc gérant les hidden services (v0.3 serveur), sans
   re-provisionner.
5. **Aucune extension protocolaire.** Le format filaire (wire-format.md) est
   intact : la création d'un réplica est une opération d'exploitation (fichiers
   pré-posés + API admin existante), pas un événement du journal. Aucune
   modification de nyx-server n'est requise (identités pré-remplies déjà
   supportées par `LoadOrCreate`).

## Conséquences

- ✅ « Se créer seuls » : de la console, un réplica complet est prêt en deux
  écrans (libellé + parrain → bundle) ; la détection et le câblage sont
  automatiques ; l'opérateur ne fait que copier un fichier et lancer une
  commande sur l'hôte (inevitable — la console ne possède pas l'hôte).
- ✅ Zéro secret de messagerie impliqué : identités de réplicas = infrastructure
  uniquement, régénérables (ADR-009 §5).
- ⚠️ Le bundle contient des secrets d'infrastructure : signalé dans l'UI et le
  LISEZMOI (transfert chiffré, suppression après installation, re-provision si
  doute). Un bundle volé ≠ données exposées (journal chiffré), mais permet
  l'usurpation de l'onion → remplacer le réplica.
- ⚠️ Binaire linux/amd64 embarqué (~12 Mo) : les autres architectures passeront
  par « Ajouter existant » ou une variante future du bundle.
- ℹ️ La création depuis la console **desktop** uniquement (système de fichiers)
  ; la console Android conserve l'ajout de réplicas existants.
