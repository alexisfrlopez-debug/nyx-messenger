# Livrables Nyx — organisation

Ce dossier est le **miroir versionné** du dossier Drive privé `/livrables`
(dossier projet « Messagerie »). Les binaires ne sont pas stockés dans git :
ils sont distribués par les **Releases GitHub** (canal public, §9.6) et le
**Drive** (canal privé, §9.4). Git porte ici la structure de classement, les
empreintes SHA-256 et les rapports d'audit — tout ce qui est texte et petit.

## Arborescence

```
livrables/
├── archives/<version>/         ← anciennes versions (empreintes ; binaires = release vX archivée)
├── archives/nyx-admin-v<version>/ ← anciennes versions de la console (Drive uniquement)
├── documents/                  ← rapports d'audit et documents joints
└── dernieres-livrables/        ← jeu de livraison COURANT (ce qu'un nouvel utilisateur prend)
```

## Où trouver quoi

| Contenu | Canal |
|---|---|
| Dernière version client Android (APK signé) | [Release « Latest »](https://github.com/alexisfrlopez-debug/nyx-messenger/releases/latest) + Drive `dernieres-livrables/` |
| Anciennes versions du client | Releases archivées ([v0.1.0](https://github.com/alexisfrlopez-debug/nyx-messenger/releases/tag/v0.1.0) · [v0.1.1](https://github.com/alexisfrlopez-debug/nyx-messenger/releases/tag/v0.1.1) · [v0.1.2](https://github.com/alexisfrlopez-debug/nyx-messenger/releases/tag/v0.1.2) · [v0.2.0](https://github.com/alexisfrlopez-debug/nyx-messenger/releases/tag/v0.2.0)) + Drive `archives/<v>/` |
| Console d'administration nyx-admin (APK + zip Windows) | Drive `dernieres-livrables/` UNIQUEMENT (dépôt privé, **jamais** en release GitHub — ADR-008) |
| Rapports d'audit | `documents/` (ce dépôt) + Drive `documents/` |

## Règles de rangement (session 13)

1. **`dernieres-livrables/`** contient le jeu courant : la dernière APK client
   + `SHA256SUMS-latest.txt` + `SHA256SUMS-v<version>.txt`. Un livrable n'y
   reste que tant qu'il est la version à jour.
2. **`archives/v<version>/`** reçoit l'empreinte (et, sur le Drive, le binaire)
   de chaque version du CLIENT remplacée — un sous-dossier par version,
   immuable ; la CONSOLE admin archive en `archives/nyx-admin-v<version>/`
   (même convention, produit distinct pour éviter toute confusion de
   numérotation).
3. **`documents/`** reçoit les rapports d'audit et tout document joint à la
   livraison (rapport admin inclus sur le Drive).
4. Le passage de « dernière » à « archivée » est automatique à la prochaine
   livraison : `scripts/build-apk.sh` archive l'ancienne version avant de
   déposer la nouvelle (`scripts/sync.sh` propage le même rangement sur le
   Drive). Pour la console admin (dépôt privé), le rangement est fait à la
   main selon la même convention.
