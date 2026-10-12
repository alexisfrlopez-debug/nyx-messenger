# ADR-008 — Console d'administration distante (Windows + Android)

Statut : **Accepté** (2026-10-12, demande utilisateur session 8)

## Contexte

Le réseau Nyx nécessite un opérateur : surveiller les réplicas (journal, pairs,
latence gossip, stockage), démarrer/arrêter des nœuds, gérer la réplication
(ajout/retrait de pairs, resync), archiver/élaguer (suppression conforme au
modèle journal), faire tourner une identité. Contraintes héritées du projet :

- l'opérateur **n'héberge rien en local** (ADR-009) — l'administration doit donc
  être **100 % distante**, via Tor exclusivement ;
- aucune IP claire, aucun port ouvert : un SSH serait un artefact de coordination
  centralisé et une surface d'attaque (et suppose un accès shell à l'hôte, que
  l'opérateur ne veut pas manipuler au quotidien) ;
- la console ne doit PAS être fusionnée avec l'app messagerie (clés admin ≠
  clés utilisateur ; surface et permissions séparées ; révocable indépendamment).

## Décision

1. **Application dédiée « nyx-admin »**, livrée sur **Windows** et **Android**,
   dans le même dépôt (`admin/`), séparée du client messagerie.
2. **Plan de contrôle** : chaque nœud `nyx-server` expose une **API d'administration
   HTTP distincte** (`127.0.0.1:9091`), publiée comme **onion service v3 séparé**
   de l'API client, protégé par **Tor ClientAuthorization** (onion auth : seuls
   les appareils de l'opérateur sont joignables en amont même de l'API).
3. **Authentification applicative** : clé ed25519 racine de l'opérateur ; chaque
   commande est **signée** (méthode + chemin + corps + nonce + horodatage) et le
   nonce est mis en cache anti-replay. La clé publique est déclarée dans la
   config du nœud (`admin.authorized_keys`, révocable).
4. **Code partagé** : module Kotlin `admin-core` (client API, signature des
   commandes, modèle d'état) réutilisé par deux surcouches UI Compose :
   - **Windows** : Compose Desktop + Tor « expert bundle » embarqué (driver tor.exe
     local, SOCKS) — même écosystème/langage que l'app Android, pas de réécriture
     du modèle ni de la crypto ;
   - **Android** : APK `nyx-admin` séparé, réutilisant le composant Tor embarqué
     du projet (tor-binary).
   Alternatives écartées : Electron/Tauri (pile JS, incohérente avec le projet),
   C#/.NET (réécriture complète modèle+crypto).
5. **Démarrage d'un nœud arrêté** : impossible via Tor (le process mort n'écoute
   plus) — assumé et documenté : les nœuds tournent sous `systemd` avec
   `Restart=always` sur leurs hôtes ; l'arrêt est **gracieux** (flush journal,
   fermeture onion) et le « démarrage » admin = levée de la maintenance
   (`/admin/maintenance off`) ou reprovisionnement. Un agent léger `nyx-agent`
   (supervision locale) est l'option d'évolution v0.3+.

## Conséquences

- ✅ Opération complète du réseau depuis un téléphone ou un PC, sans jamais
  exposer d'IP ni se connecter « en local » — cohérent avec le modèle de menace.
- ✅ Séparation des pouvoirs : compromettre l'app messagerie ne donne pas les
  clés admin, et réciproquement ; révocation par retrait de la clé publique.
- ✅ Journal d'audit des commandes admin sur chaque nœud (qui, quoi, quand, sig).
- ⚠️ Nouvelle surface API à auditer (commandes privilégiées) → toute commande
  admin passe par les mêmes contrôles d'audit que le reste (règle §9.1).
- ⚠️ Le redémarrage post-crash dépend de l'hôte (systemd) tant que `nyx-agent`
  n'existe pas — documenté dans docs/APPLI_ADMIN.md.
- Spec détaillée (commandes, formats, roadmap) : `docs/APPLI_ADMIN.md`.
