# Nyx — Guide de lancement

> **Tout ce qu'il faut pour démarrer le projet en local**, pas à pas : les serveurs
> (option 1) puis l'application Android (option 2). Les commandes sont données pour
> Linux/macOS (sous Windows : utiliser WSL2). Dernière mise à jour : 2026-10-12.

---

## 0. Carte : qu'est-ce qu'on lance ?

```
   OPTION 2                          OPTION 1
   App Android (téléphone)           3 réplicas Nyx (ton PC)
  ┌─────────────────┐              ┌──────────────────────────────┐
  │  Compose UI     │              │  node1 ⇄ node2 ⇄ node3       │
  │  crypto ed25519 │   Tor        │  journal signé + gossip NaCl │
  │  Orbot (SOCKS)  │◀═══════════▶ │      ⇅          ⇅            │
  │                 │  (onions)    │   Tor de dev (9050/9051)     │
  └─────────────────┘              └──────────────────────────────┘
        chaque nœud = un onion v3 publié par Tor = son identité
```

| Option | Quoi | Où | Durée |
|---|---|---|---|
| **1** | Tor + 3 réplicas câblés automatiquement + test de réplication | PC (Linux/macOS/WSL2) | ~10 min |
| **2** | APK Android + Orbot + connexion au réplica de dev | Android Studio + téléphone | ~20 min |

L'option 2 a besoin de l'option 1 (ou d'un réplica déployé ailleurs) pour avoir
quelque chose à qui parler. Commence par l'option 1.

---

## 1. Prérequis (une seule fois)

| Outil | Pour quoi | Installation |
|---|---|---|
| **git** | récupérer le dépôt | `apt install git` (déjà présent sur macOS) |
| **Go ≥ 1.22** | compiler le serveur | automatique via `scripts/bootstrap.sh` (installe dans `~/.local`, **sans root**) |
| **Tor ≥ 0.4.7** | publier les onions + SOCKS | Debian/Ubuntu : `sudo apt install tor` · macOS : `brew install tor` |
| **curl** | tests API | généralement présent |
| **Android Studio** (option 2) | compiler l'app | https://developer.android.com/studio (SDK 35, JDK 17+ inclus) |
| **Orbot** (option 2) | Tor sur Android | Play Store ou F-Droid, sur le téléphone |

```bash
git clone https://github.com/alexisfrlopez-debug/nyx-messenger
cd nyx-messenger
scripts/bootstrap.sh        # Go utilisateur + torrc de dev + build + tests verts
```

> Le Tor système, s'il tourne en service (`systemctl status tor`), interfère avec le
> dev : le script `dev-server.sh` le détecte et te dit quoi faire (voir §4).

---

## 2. OPTION 1 — Lancer les serveurs (3 réplicas répliqués)

### 2.1 Lancement en une commande

```bash
scripts/dev-server.sh 3
```

Le script fait tout, dans l'ordre :

1. démarre un **Tor de développement** (`scripts/torrc-dev` : SOCKS 9050, control 9051,
   cookie auth) s'il n'en existe pas déjà un utilisable ;
2. lance **3 réplicas** (`scripts/dev/node1..3`, API locale `127.0.0.1:8081-8083`) —
   chaque nœud génère ou recharge son identité (`identity.json`) et demande à Tor la
   publication de **son** onion v3 (la clé privée ed25519 est la sienne, l'adresse est
   donc **stable d'un lancement à l'autre**) ;
3. **câble automatiquement les pairs** : lit l'onion + la clé gossip de chacun et écrit
   le `config.json` des deux autres (tu n'as rien à écrire à la main) ;
4. redémarre les nœuds pour charger les pairs, puis vérifie les 3 API.

Sortie attendue (adresses d'exemple — les tiennes seront différentes) :

```
[nyx-dev] ✔ 3 réplicas en cours, câblés comme pairs (gossip chiffré via Tor, période 15s)

  Nœud   API locale            Adresse onion (= identité)
  ----   --------------------  ------------------------------------------------
  1      http://127.0.0.1:8081  l5vqoj7rsd7dq4mlqxxxlj4lojhigydj2lx754utgs5duzrogbbjraad.onion
  2      http://127.0.0.1:8082  x7rak3iqyst6r6tqzx2zihqqturcruaklxcc6sa5d34fgql7cb5u7xad.onion
  3      http://127.0.0.1:8083  qf2t6sdraczdxpjb6ejhwkibrm5cn7gxiz5dwebo4mwh5rkscpe6t3id.onion
```

> ⏱️ **Patience Tor** : la publication des onions par le réseau Tor prend ~10-30 s après
> le bootstrap. Le gossip inter-nœuds fonctionne dès que les circuits sont établis.
> Suis la progression : `tail -f scripts/tor_data/tor.log` (ligne `Bootstrapped 100%`).

### 2.2 Le test de réplication (la démonstration)

On crée une **identité cliente** « alice » — une simple paire ed25519 dont la clé
publique *est* son adresse onion — puis on dépose un événement signé sur le nœud 1 et
on le retrouve sur les nœuds 2 et 3.

```bash
# 1. identité cliente de test (une fois ; fichier 0600, ne pas committer)
go run ./server/cmd/nyx-devtool keygen /tmp/alice.json
#    → identité créée : abc…xyz.onion

# 2. événement signé (séquence auto-incrémentée dans le fichier)
go run ./server/cmd/nyx-devtool event --keys /tmp/alice.json --body "salut nyx" > /tmp/ev.json

# 3. contrôle avant envoi (structure + signature + horloge)
go run ./server/cmd/nyx-devtool verify /tmp/ev.json

# 4. dépôt sur le nœud 1
curl -s -X POST http://127.0.0.1:8081/client/event -d @/tmp/ev.json
#    → {"accepted":true,"id":"…"}

# 5. réplication : l'événement doit apparaître sur les nœuds 2 et 3
A=$(go run ./server/cmd/nyx-devtool show --keys /tmp/alice.json | cut -d' ' -f2)
curl -s "http://127.0.0.1:8082/client/events?author=$A"
curl -s "http://127.0.0.1:8083/client/events?author=$A"
```

Le push vers les pairs part **immédiatement** après chaque POST (signal interne), et de
toute façon toutes les 15 s en dev. En cas de doute :

```bash
tail -f scripts/dev/node1.log        # lignes « [gossip] push <onion>: N acceptés »
```

**Message privé** (test de la boîte de réception d'un destinataire précis) :

```bash
go run ./server/cmd/nyx-devtool keygen /tmp/bob.json
B=$(go run ./server/cmd/nyx-devtool show --keys /tmp/bob.json | cut -d' ' -f2)
go run ./server/cmd/nyx-devtool event --keys /tmp/alice.json --to "$B" --body "pour bob" > /tmp/ev2.json
curl -s -X POST http://127.0.0.1:8081/client/event -d @/tmp/ev2.json
curl -s "http://127.0.0.1:8082/client/inbox?to=$B"      # visible par bob uniquement
```

À noter : en v0.1 le champ `body` est opaque pour le serveur (base64) ; le chiffrement
de bout en bout (sealed box, puis Signal Protocol en v0.2) vit dans l'application —
c'est voulu : le serveur ne doit jamais pouvoir lire les messages.

### 2.3 Arrêt et relance

```bash
scripts/dev-server.sh stop     # arrête les nœuds + le Tor de dev (pas le Tor système)
scripts/dev-server.sh 3        # relance propre : identités et journaux conservés
```

Données locales (toutes ignorées par git) :

| Chemin | Contenu |
|---|---|
| `scripts/dev/nodeN/identity.json` | clés du nœud (ed25519 onion + X25519 gossip) — **son adresse pour toujours** |
| `scripts/dev/nodeN/events.jsonl` | journal d'événements (append-only) |
| `scripts/dev/nodeN/config.json` | pairs déclarés |
| `scripts/dev/nodeN.log` + `nodeN.pid` | log et pid du nœud |
| `scripts/tor_data/` | données et logs du Tor de dev |

> 🔑 **La clé fait l'adresse** : supprimer `identity.json` = changer d'adresse onion.
> Pour un réplica de longue durée, sauvegarde ce fichier.

### 2.4 Comprendre : les briques à la main

Le script automatise ce qui suit — utile à lire une fois :

```bash
# 1. Un Tor avec control port + cookie (sinon le nœud ne peut pas publier son onion)
tor -f scripts/torrc-dev

# 2. Un nœud : crée/lit identity.json, publie l'onion, sert l'API, boucle gossip
./server/nyx-server --data-dir ./data --http-port 8080

# 3. Flags utiles
#    --control-port 9051      control port Tor (défaut)
#    --socks-port 9050        SOCKS Tor pour le gossip sortant (défaut)
#    --cookie-file <chemin>   cookie control port (auto-détecté : /run/tor/…, /var/run/tor/…)
#    --control-password ***   alternative au cookie
#    --config <chemin>        config.json (défaut : <data-dir>/config.json)
#    --gossip-interval 60     période de réplication en secondes (15 en dev)

# 4. Vérifications de base
curl -s http://127.0.0.1:8080/health      # {"ok":true,"onion":"…","events":N}
curl -s http://127.0.0.1:8080/identity    # onion + clé gossip (base64)
```

Déclarer un pair à la main (ce que fait le script pour toi) :

```json
{ "peers": [ { "onion": "abcd….onion", "gossip_pub": "base64 de GET /identity" } ] }
```

### 2.5 Après le dev : un vrai déploiement (aperçu)

Un réplica de production = le même binaire statique + un Tor avec
`HiddenServiceDir`/`ADD_ONION` persistant + un service systemd + pare-feu n'ouvrant
**rien** (tout passe par Tor). Le durcissement complet (persistance onion, supervision,
rotation des logs) est décrit dans `docs/00-architecture.md` §déploiement — à traiter
en v0.2 après validation du MVP.

---

## 3. OPTION 2 — Lancer l'application Android

### 3.1 Construire l'APK

Avec **Android Studio** (recommandé) : `File → Open` → sélectionner le dossier
`android/` du dépôt → laisser Gradle se synchroniser (première fois : plusieurs
minutes) → `Build → Build Bundle(s)/APK(s) → Build APK(s)`.

En ligne de commande (SDK 35 + JDK 17+ installés) :

```bash
cd android
./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3.2 Orbot : le Tor du téléphone

1. Installer **Orbot** (Play Store ou F-Droid).
2. Lancer Orbot et appuyer sur le bouton onion jusqu'à « connecté ».
3. C'est tout : Nyx parle à Tor via **SOCKS `127.0.0.1:9050`** fourni par Orbot.
   Le « mode VPN » d'Orbot n'est pas requis (le client Nyx force déjà tout son trafic
   dans le tunnel SOCKS) — l'activer ne gêne pas.

⚠️ **Sans Orbot démarré, Nyx échoue volontairement** : aucune requête ne doit sortir
hors Tor (« offline by design »).

### 3.3 Relier l'app à ton réplica de dev

1. Sur ton PC, lance l'option 1 et note l'adresse onion du nœud 1 (sortie du script,
   ou `curl -s http://127.0.0.1:8081/identity`).
2. Dans l'app : l'écran d'ajout de réplica (TODO n°4 de `android/README.md`) n'est pas
   encore branché. En attendant, déclare le réplica en dur puis recompile :

   ```kotlin
   // android/app/src/main/java/net/nyx/messenger/net/Replicas.kt
   val DEFAULT: List<Replica> = listOf(
       Replica("l5vqoj7rsd….onion", "egspCf/MvyyQ+yDohiMZZqGEViO3PA6r/0C5O42yPBM=", "dev node1"),
   )
   ```

   (`gossip_pub` = champ `gossip_pub` renvoyé par `/identity` — il sert au canal
   chiffré serveur↔serveur ; le client lui-même n'en a pas besoin pour déposer/lire.)
3. Recompilé + installé : l'app passe par Orbot → circuit Tor → onion du nœud 1 → API
   (`/client/event`, `/client/inbox`). Le téléphone n'a besoin d'aucun port ouvert.

> 📱 **Émulateur ou téléphone ?** Les deux marchent ; sur l'émulateur il faut installer
> Orbot *dans* l'émulateur (image Play Store ou APK F-Droid). Un téléphone physique
> évite les surprises de réseau de l'émulateur.

### 3.4 État v0.1 de l'app (honrête et important)

| Fonctionnel | À faire (voir `android/README.md`) |
|---|---|
| Identité ed25519 + adresse onion (Keystore + IdentityManager) | `verifySignature` (crypto_sign_verify_detached) |
| Chiffrement des messages (sealed box libsodium) | Implémentation `OnionServiceManager` (publication via Orbot) |
| UI onboarding / liste / chat (Compose) | Écran d'ajout de réplica + persistance |
| Room + SQLCipher (stockage local chiffré) | Compteur de séquence par auteur (fichier scellé) |
| RelayClient SOCKS5 (tout le trafic dans Tor) | Tests instrumentés crypto sur device |

Le squelette compile à partir des sources de la session 1 mais **n'a pas encore été
compilé sur une machine avec le SDK Android** — le premier build peut révéler des
ajustements (versions de plugins, ABI natives). C'est le travail prévu de l'option 2.

---

## 4. Dépannage

| Symptôme | Cause | Remède |
|---|---|---|
| `Tor requis` au lancement du script | Tor non installé | `sudo apt install tor` / `brew install tor` |
| `quelque chose écoute déjà sur 9050 (SOCKS) mais pas de control port` | service Tor système sans ControlPort | `sudo systemctl stop tor` puis relancer `dev-server.sh` |
| Log nœud : `control port injoignable (nouvel essai dans 5 s)` | Tor pas lancé ou ControlPort désactivé | `tor -f scripts/torrc-dev` ; vérifier `ControlPort 9051` + `CookieAuthentication 1` |
| Log nœud : `cookie introuvable (…)` ou `authentification` | cookie absent/illisible | notre torrc le met dans `scripts/tor_data/control.authcookie` (le script le passe automatiquement) ; sinon `--control-password` |
| POST accepté mais rien sur les autres nœuds | circuits Tor pas prêts, ou pairs mal déclarés | attendre `Bootstrapped 100%` ; `curl /identity` des deux nœuds et comparer au `config.json` ; `tail -f scripts/dev/nodeN.log` |
| `signature invalide pour …` | body/signature modifiés à la main | ne jamais éditer le JSON signé ; régénérer avec `nyx-devtool event` |
| `horodatage hors tolérance` | horloge fausse (> 24 h de dérive) | resynchroniser l'horloge (NTP) |
| `le port 8081 reste occupé` | ancien nœud zombie | `scripts/dev-server.sh stop` puis relancer ; en dernier recours `pkill nyx-server` |
| `AVERTISSEMENT: ServiceID tor ≠ identité calculée` | ne doit jamais arriver | signaler : la clé identity.json est corrompue ou Tor anormal |
| Gradle : dépendances introuvables | cache/plugin | `./gradlew build --refresh-dependencies` ; vérifier SDK 35 |
| Android : crash natif lazysodium/JNA | ABI/ empaquetage | vérifier `jna` en `@aar` (déjà dans `libs.versions.toml`) ; tester sur arm64 |
| Android : tout échoue au réseau | Orbot pas démarré | lancer Orbot et attendre « connecté » |

---

## 5. Hygiène de développement

- **Rien de secret ne part sur git** : `.gitignore` couvre `scripts/dev/`, `tor_data/`,
  `identity*.json`, clés, logs. Vérifie avec `git status` avant chaque commit.
- Les identités (nœuds ET clients de test comme alice) sont **définitives** : c'est
  leur adresse. Ne les supprime que volontairement.
- Toute évolution du format filaire commence dans `protocol/specs/` (specs d'abord,
  serveur ensuite, client enfin) — règle du projet.
- Les décisions d'architecture se prennent via des ADR dans `docs/adr/`.

## 6. Pour aller plus loin

- `docs/00-architecture.md` — vue d'ensemble (nœuds, réplication, phases 2-3)
- `docs/01-modele-securite.md` — menaces et garanties
- `docs/02-protocole-replication.md` + `docs/03-protocole-messagerie.md` — protocoles
- `protocol/specs/wire-format.md` + `protocol/specs/api.md` — format filaire et API
- `docs/04-roadmap.md` — v0.2 (Signal Protocol, IPtProxy embarqué, purge TTL…)
- `RESTART.md` — point d'entrée des sessions de travail (agent ou humain)
