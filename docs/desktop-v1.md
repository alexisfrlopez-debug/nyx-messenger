# Spécification — Client desktop Windows Nyx (v1)

Statut : **acté** (session 70 — ADR-019). Le protocole applicatif est
**strictement celui de `wire-format.md` v1.2 + `api.md` v1.2** — ce document
ne définit QUE les choix propres au portage desktop (stockage, processus Tor,
scellement identité, packaging, exclusions v1).

## 1. Périmètre v1 (cadrage opérateur session 70)

| Fonction | v1 desktop | Source du portage |
|---|---|---|
| Identité onion v3 (création, restauration par graine) | OUI | IdentityManager/IdentityBackup |
| Alias (claim + résolution) | OUI | AliasValidator + events `alias` |
| Contacts (ajout par onion/alias, annonces `contact`) | OUI | NyxRepository |
| Conversations + messages texte E2E (sealed box) | OUI | MessageCipher + WireEvent |
| Fichiers/images en chat (fragments ≤ 96 Ko, 64 Mo max) | OUI | ChunkCodec |
| Accusés ✓ (reçu) / ✓✓ (lu) | OUI | ReceiptCodec |
| Profils à visibilité par champ | OUI | ProfileStore + events `profile` |
| Suppression locale de messages | OUI | DeleteCodec |
| Réplique embarquée in-process (port 80 de l'onion) | OUI | EmbeddedReplica + ReplicaHttp/Validate |
| Tor embarqué (expert bundle, torrc généré) | OUI | NyxTorConfig + TorControlClient |
| Écran connexions (sondes /health flotte) | OUI | RelayClient.healthDetailed |
| Thème nuit (défaut) / aube | OUI | NyxPalette |
| Verrou par mot de passe opt-in | OUI | PasswordVault (portage) |
| Wipe (mot de passe destruction) | OUI | Wipe (portage) |
| Appels audio/visio (ADR-015) | **PHASE 2** | — |
| P2P direct events (ADR-017) / NYX-LINK (ADR-018) | **PHASE 2** (journal seul en v1 — repli pair spécifié) |
| Windows Hello (ADR-010 équivalent desktop) | PHASE 2 | — |
| Auto-update GitHub | PHASE 2 | — |

Interopérabilité : un pair Android ≥ v0.2.41 qui échoue en direct P2P
retombe sur le journal (ADR-017 §compat) → les échanges Android ↔ desktop
v1 passent par le journal répliqué sans aucun changement chez le pair.

## 2. Répertoir de données (Windows)

```
%USERPROFILE%\.nyx-messenger\
├── identity.json        # onion, pub b64, priv scellée (secretbox), alias, flags
├── settings.json        # thème, verrou opt-in, réplique on/off, comportement fermeture
├── store\
│   ├── conversations.json   # messages (texte+en-têtes fichiers), reçu/lu, état
│   ├── contacts.json        # carnet (onion, alias, état PENDING/ACCEPTED, notifyPending)
│   ├── seq_state.json       # séquence par auteur (miroir table Room seq_state)
│   ├── outbox.json          # événements en attente de dépôt (repli réseau)
│   ├── profiles.json        # cartes de profil locales (nôtres + contacts)
│   └── media\               # fichiers reconstitués + miniatures (accès par fid)
├── replica\
│   ├── events.jsonl         # journal du réplica embarqué (append, TTL 48 h)
│   └── state.json           # seq par auteur + curseurs inbox + quota fenêtre
└── tor\                    # torrc généré, data/, tor.log (miroir NyxTorConfig)
```

Écritures **atomiques** (tmp + rename) sous verrou par fichier ; aucun
fichier ne sort du répertoire (équivalent allowBackup=false + règles
d'hygiène §9.5 : pas de stockage externe).

## 3. Sceau de l'identité (desktop)

`identity.json` :
```json
{ "v":1, "onion":"…", "onion_pub":"<b64>", "alias":"…?", "alias_claimed":false,
  "sealed": { "alg":"secretbox-xsalsa20poly1305", "kdf":"argon2id-moderate",
              "salt":"<b64 16o>", "nonce":"<b24>", "box":"<b64>" } }
```

- Sans mot de passe de verrou configuré : la dérivation utilise un SEL
  aléatoire local (l'identité reste locale au profil utilisateur — menace =
  accès au fichier ; cohérent avec le modèle Android Keystore non-hardware).
- Avec mot de passe : Argon2id (libsodium) → clé 32 o → secretbox.
- Restauration par graine : format IdentityBackup existant (bip39-like 24
  mots du portage RecoveryKey) — import/export dans les Réglages.

## 4. Processus Tor (desktop)

1. Résolution du binaire : `<app>/tor/tor.exe` (bundle) sinon `tor` du PATH
   (repli documenté — l'autonomie 1-clic exige le bundle embarqué).
2. torrc généré à chaque démarrage (miroir NyxTorConfig) : SocksPort +
   ControlPort libres sondés, CookieAuthentication 1, DataDirectory
   `<data>\tor\data`, ConfluxEnabled 0, Log info file.
3. Le démon est un **processus enfant** (destroy au shutdown + arrêt propre
   DEL_ONION avant sortie) ; un tor.exe résiduel au démarrage est tué si
   son DataDirectory est le nôtre (verrou du répertoire).
4. Publication : ADD_ONION ED25519-V3 (dérivation scalaire RFC 8032 —
   CONTRAT TOR de TorControlClient), Flags=Detach, Port=80 → réplique
   embarquée locale. Port média 47471 NON publié en v1 (pas d'appels).
5. Bootstrap observé par GETINFO status/bootstrap-phase (progression % UI).

## 5. Boucles applicatives (miroir ServiceLocator Android)

- Poll inbox : **30 s** au niveau application (pas de l'écran) — lecture =
  réplique PROPRE en procédé + flotte en round-robin (invariant
  DepositRouting : dépôt → boîte du pair sinon flotte, JAMAIS le journal
  d'un tiers).
- Outbox : rejeu des dépôts en attente à chaque cycle + SeqResync sur 400.
- Purge TTL réplica : 48 h (même cadence Android).
- Fermeture : dialogue Réduire/Quitter — Quitter = arrêt PROPRE (DEL_ONION +
  destruction processus tor + flush stores).

## 6. Packaging & livraison

- `desktop/` produit `nyx-messenger-desktop-<version>.jar` (fat jar, Compose
  Desktop packaging `packageApplication` ou équivalent shadow minimal).
- Zip portable : `nyx-messenger-windows-v<version>.zip`
  = `runtime\` (JRE 21 Temurin win-x64) + `tor\` (expert bundle 15.x) +
  `libsodium.dll` (si requis hors jar) + `nyx-messenger.jar` +
  `nyx-messenger.bat` + `LISEZMOI.txt`.
- Installateur : `nyx-messenger-windows-v<version>.exe` — Go, `go:embed` du
  zip, SHA-256 vérifié (ldflags), raccourcis, `-silent`/`-dir`,
  `desinstaller.bat` (données `%USERPROFILE%\.nyx-messenger` préservées) —
  miroir de l'installateur nyx-admin.
- Contrôles d'autonomie (équivalent §9.2 adapté desktop) : tor.exe présent
  dans le zip ; jar exécutable par `runtime\bin\java.exe` seul ; zéro
  dépendance réseau au premier lancement (hors Tor lui-même) ; taille et
  empreintes SHA256SUMS publiés.

## 7. Tests (miroirs JVM + nouveaux)

Portages directs : OnionCodecTest, MessageCipherTest (vecteurs scellés),
ChunkCodecTest, ReceiptCodecTest, BootstrapReplicasTest, ReplicaValidateTest,
LoopbackBindTest, SeqResyncTest, DepositRoutingTest (selon disponibilité
côté Android — les mêmes fichiers sources pur JVM). Nouveaux : torrc
généré (ports/chemins/ConfluxEnabled), scellement identité (aller-retour
avec/sans mot de passe), stores atomiques (crash simulé tmp), réplique
desktop (dépôt→inbox→curseur, quotas, TTL), intégration loopback (dépôt
client A → réplica embarqué → lecture inbox signée).
