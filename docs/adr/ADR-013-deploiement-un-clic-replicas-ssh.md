# ADR-013 — Déploiement en un clic des réplicas (SSH) et gestion de la flotte dans la console

Statut : **Accepté** (2026-10-13, demande utilisateur session 16)

## Contexte

ADR-012 dote la console d'un assistant « Créer un réplica » : la console forge
l'intégralité de l'identité du futur nœud et produit un bundle one-shot
(`tar.gz` : binaire `nyx-server`, identités pré-remplies, `config.json`, torrc
durci, unité systemd, `install.sh`). Mais le déploiement restait **manuel** :
l'opérateur devait copier le bundle sur l'hôte (`scp`), l'extraire (`tar xzf`)
puis lancer `install.sh` — trois manipulations hors console. Retour opérateur
(session 16) :

> « le replica ne se déploie pas tout seul. dans l'onglet flotte, je voudrais
> un bouton pour déployer en un clic et un pour supprimer/modifier/renommer
> le replica »

Deux attentes distinctes :

1. **Déploiement automatisé** — la console doit pousser le bundle sur l'hôte et
   l'installer elle-même, en un clic ;
2. **Gestion de la flotte** — chaque réplica doit pouvoir être renommé, modifié
   (onion d'admin / clé client-auth) ou retiré depuis la console (aujourd'hui :
   rien n'existe dans l'UI, seul `removeReplica` existe en ViewModel sans
   bouton).

Contraintes : la console ne parle qu'au plan de contrôle (`/admin/*`) et ne
possède aucun hôte ; elle ne peut louer de VPS ni exécuter quoi que ce soit sur
une machine dont elle n'a pas les accès. Un déploiement automatisé suppose donc
que **l'opérateur fournisse l'accès SSH à SON hôte** à la console (hôte qu'il
loue et possède par ailleurs — inchangé, ADR-009 §2).

## Décision

1. **« Déployer » en un clic (console desktop).** Tout réplica « en attente de
   déploiement » porte un bouton **Déployer** (carte de la Flotte, et bouton
   « Déployer maintenant… » à la fin de l'assistant de création). Un dialogue
   recueille l'accès SSH de l'hôte : hôte (IP ou DNS), port, utilisateur
   (root recommandé — l'`install.sh` exige root ; sinon `sudo -n` est tenté),
   et une authentification **mot de passe OU clé privée** (+ passphrase de clé
   éventuelle). La console exécute alors, via un client SSH embarqué
   (bibliothèque **JSch, fork mwiede `com.github.mwiede:jsch`** — artefact
   unique, zéro dépendance transitive, maintenu, JDK pur) :

   ```
   SFTP  put  <bundle>.tar.gz → /tmp/nyx-deploy-<horodatage>.tar.gz
   exec       D=$(mktemp -d /tmp/nyx-replica.XXXXXX)
              && tar xzf /tmp/nyx-deploy-….tar.gz -C "$D"
              && cd "$D" && sh install.sh
              ; RC=$? ; rm -rf "$D" /tmp/nyx-deploy-….tar.gz ; exit $RC
   ```

   sortie de `install.sh` **streamée en direct** dans le dialogue (apt-get,
   tor, systemd visibles) ; nettoyage systématique du bundle et du dossier
   extrait sur l'hôte (secrets d'infrastructure — ADR-012 §2), même en cas
   d'échec. Le nœud démarre ; la boucle de rafraîchissement existante le passe
   « actif » et le câble dans la flotte (ADR-012 §3, inchangé).

2. **Identité de déploiement = paramètre, pas état de la console.** La console
   retient par réplica **uniquement les paramètres non secrets** (hôte, port,
   utilisateur) et le chemin local du dernier bundle (`bundlePath`) pour
   pré-remplir le dialogue. **Ni mot de passe, ni clé privée, ni passphrase ne
   sont persistés** : ils sont redemandés à chaque déploiement (motif : une
   fuite du fichier de réglages ne doit jamais donner accès aux hôtes). Le
   bundle lui-même reste sous le contrôle de l'opérateur (Avertissement
   affiché dans l'assistant : transfert chiffré, suppression après
   installation — ADR-012 §2).

3. **Intégrité de l'hôte vérifiée à la première connexion (TOFU journalisé).**
   L'empreinte de la clé d'hôte SSH est affichée dans le journal du dialogue ;
   elle est épinglée dans `known_hosts` propre à la console (`~/.nyx-admin/
   known_hosts`) — les connexions suivantes échouent si l'hôte présente une
   clé différente (MITM détecté), avec message explicite invitant à vérifier
   puis à réinitialiser l'épinglage.

4. **Gestion de la flotte (desktop + Android).** Chaque carte réplica de
   l'onglet Flotte expose un menu (⋯) : **Renommer** (libellé libre),
   **Modifier** (URL de l'onion d'admin, clé client-auth — validations
   identiques à l'ajout), **Supprimer** (confirmation explicite). Le retrait
   reste **strictement local** : la console cesse d'afficher et d'interroger le
   réplica, mais **le nœud n'est PAS arrêté ni effacé** (message affiché) —
   pour révoquer réellement un réplica compromis : ADR-009 §5 (re-
   provisionnement ailleurs, la flotte re-converge) puis arrêt de l'ancien
   nœud par son hébergeur. La modification de la clé client-auth (et le
   retrait) régénère les fichiers `ClientOnionAuthDir` du Tor embarqué
   (relance automatique — sans effet sur le nœud distant).

5. **Périmètre desktop pour le déploiement SSH.** La console Android conserve
   la gestion (renommer/modifier/supprimer) mais PAS le déploiement SSH
   (bibliothèque JSch et FileDialog absents du module mobile ; un VPS ne se
   pilote pas utilement depuis un téléphone — ADR-012 §5 déjà : création =
   desktop). L'onglet Flotte Android affiche le badge « en attente de
   déploiement » avec l'invitation à déployer depuis le desktop.

6. **Aucune extension protocolaire, aucun changement serveur.** Le déploiement
   automatisé est une opération d'exploitation (SSH + SFTP + `install.sh`
   existant) ; le filaire est intact.

### Alternatives écartées

- **Agent nyx sur l'hôte / API d'hébergeur (DigitalOcean, etc.)** : la console
  posséderait alors les credentials du compte d'hébergement — surface d'attaque
  majeure et dépendance à des APIs tierces centralisées ; antithétique à
  ADR-009 (zéro local, zéro compte tiers). L'opérateur reste propriétaire de
  son hôte et de son accès SSH.
- **Ligne de commande `ssh`/`scp` externe** : disponible sur Windows 10+ mais
  pas d'authentification par mot de passe scriptable sans `sshpass` (absent),
  pas de contrôle du known_hosts, parsing fragile. Bibliothèque embarquée =
  comportement identique partout.
- **Images cloud (cloud-init/Terraform)** : injoignables hors Tor sans
  surcouche, et ne résolvent pas l'identité pré-forgée (ADR-012).

## Conséquences

- ✅ « Déployer en un clic » : de la carte du réplica → bundle poussé,
  installé, service démarré, promotion + câblage flotte automatiques — l'unique
  saisie est l'accès SSH de l'hôte (une fois par déploiement).
- ✅ Flotte gérable : renommer/modifier/supprimer accessibles depuis les deux
  consoles ; retrait explicite « console seulement ».
- ⚠️ Le mot de passe SSH transite en mémoire de la console le temps du
  déploiement (non persisté, non journalisé — le log n'affiche jamais
  l'authentification) ; la clé d'hôte est épinglée (TOFU journalisé).
- ⚠️ `install.sh` exige root : un utilisateur non-root `sudo -n` est tenté
  (échec clair si sudo exige un mot de passe — recommandation : connexion root
  ou sudo NOPASSWD, choix de l'opérateur sur son hôte).
- ℹ️ Fat jar +~400 Ko (jsch) — négligeable devant les natives Compose.
- ℹ️ Le bundle déplacé/renommé après création reste déployable : le dialogue
  propose de relocaliser le fichier si `bundlePath` ne pointe plus vers un
  bundle existant.
