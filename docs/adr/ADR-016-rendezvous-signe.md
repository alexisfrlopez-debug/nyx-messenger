# ADR-016 — Rendezvous signé embarqué (suite ADR-009 §3)

> Statut : **implémenté v0.2.23** (2026-10-17). Complète ADR-009 §3 (découverte
> au lancement) et ADR-014 §2 (branchement TURN phase 2). Clôture l'item n°4
> du plan d'action (RESTART.md §8).

## 1. Contexte

ADR-009 §3 exige que l'APK embarque une **liste de rendezvous signée**
(adresses onion des réplicas d'amorçage, signée par la clé de release Nyx).
Le pont v0.2.3–v0.2.22 (`replica-bootstrap.properties`, git-ignoré, **non
signé**) remplit le rôle fonctionnel mais ne garantit ni l'origine ni
l'intégrité de la liste — un APK recompilé (ou un build détourné) pourrait
amorcer le client sur un réplica contrôlé par un tiers. Incident connexe
(v0.2.14, session 29) : la perte du fichier git-ignoré au reset du bac à
sable a livré des APK SANS réplica d'amorçage.

## 2. Décision

### 2.1 Format v1 — deux fichiers inséparables

    rendezvous.json        {"format":"nyx-rendezvous","version":1,
                            "generatedAt":<unix>,"replicas":[{"onion":"…[:port]","note":"…"}]}
    rendezvous.json.sig    base64( signature ed25519 détachée )

**Domaine de séparation** : `b"nyx-rendezvous-v1|"` préfixé aux octets signés.

**La signature porte sur les OCTETS BRUTS du .json** — aucune canonicalisation
JSON : l'éditeur (Python), le serveur (Go) et le client (Kotlin) lisent les
mêmes octets ; le JSON n'est réinterprété qu'APRÈS vérification. Ce choix
élimine toute divergence de sérialisation entre langages (source classique
d'échec de vérification multi-implémentations).

### 2.2 Clé de release rendezvous

- Paire **ed25519 dédiée** (distincte du keystore APK RSA et des clés
  d'identité des nœuds) — rotation possible sans re-publier les réplicas.
- **Clé privée HORS dépôt** : `/home/z/my-project/keystore/rendezvous-ed25519.json`
  + miroir Drive (règle §9.5, même traitement que `nyx-release.jks`).
- **Clé publique embarquée dans l'APK** (`BuildConfig.RENDEZVOUS_PUBKEY`) :
  `M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q=`.
- La clé de test des suites unitaires = vecteur RFC 8032 n°1 (jamais en prod).

### 2.3 Client (code commun `main` — toutes les versions)

- `crypto`+`net/RendezvousList.kt` (pur, JVM-testable, BC rfc8032 — déjà
  dépendance) : vérifie puis interprète ; onion normalisées (minuscules,
  port conservé, **checksum SHA3 validé** via `OnionCodec` — anti-injection),
  entrées invalides écartées une à une, doublons dédupliqués, liste vide ou
  altérée → **refus en bloc**.
- `NyxApplication` : fusion prioritaire **CUSTOM (test 2) > rendezvous signé >
  bootstrap legacy** (`BootstrapReplicas`, conservé en filet de sécurité si
  l'asset est absent/invalidé — jamais l'inverse).
- Assets embarqués : `assets/rendezvous.json` + `.sig` (publics, versionnés
  dans le dépôt ; la signature les rend intamprétables).

### 2.4 Serveur (rendezvous servi et mis à jour par l'opérateur)

- **`GET /rendezvous`** (non authentifié, servi même en maintenance — la liste
  est publique et auto-vérifiable) : envelope `{"json_b64","sig_b64"}` ;
  404 si non configuré (comportement v0.2.22 inchangé).
- **`POST /admin/rendezvous`** (API admin — dépôt privé APPLI_ADMIN.md) :
  le serveur **vérifie la signature avec `config.rendezvous.pubkey`** avant
  d'écrire (temp+rename atomique de `.json` + `.sig`) ; signature invalide
  → 409, rien n'écrit ; pubkey non configurée → 400 (lecture seule).
  → l'opérateur (console) met à jour la liste SANS rebuild : le client
  re-vérifie de toute façon la signature avec SA clé embarquée.
- Config : section `rendezvous` {`file`, `pubkey`} — `config.example.json` à jour.

### 2.5 Outillage

- `scripts/ed25519lib.py` : ed25519 pur-Python (RFC 8032) **validé 3/3
  vecteurs §7.1** (pubkey + signature + vérification + détection tamper).
- `scripts/gen_rendezvous.py` : `keygen` / `make --onion … --note …` /
  `sign` / `verify` — l'outil de release qui produit l'asset embarqué.

## 3. Conséquences

- ✅ La liste d'amorçage est **intamprétable** : toute modification d'un
  octet invalide la signature (garde anti-régression RendezvousListTest ×10).
- ✅ Un build sans liste (incident v0.2.14) devient **impossible à confondre
  avec un build légitime** : l'asset absent → fallback explicite legacy +
  log `nyx-boot` distinct.
- ✅ La mise à jour des réplicas d'amorçage ne nécessite plus une release
  (POST admin signé) — et reste verrouillée par la clé de release.
- ⚠️ Rotation de la clé de rendezvous = nouvelle pubkey embarquée = release
  (l'ancienne liste reste valide pour les clients jusqu'à mise à jour).
- ⚠️ La liste embarquée doit contenir **≥ 2 réplicas vivants** (ADR-009 §5) —
  à enrichir à chaque nouvelle réplique déployée (`make --onion` répété).
- ADR-014 phase 2 (publication d'adresse relayée TURN dans le rendezvous)
  reste branchée sur CE format : le réplica-relais n'a plus qu'à soumettre
  une liste enrichie via la même voie admin (resté non implémenté ici).

## 4. Références

- ADR-009 §3 (découverte), ADR-014 §2 (TURN opt-in), ADR-012 (provisionnement)
- `net/RendezvousList.kt`, `NyxApplication.kt`, `internal/api/handlers.go`
  (`handleRendezvous`), `internal/adminapi/handlers_rendezvous.go`
- `scripts/gen_rendezvous.py`, `scripts/ed25519lib.py`, `scripts/ed25519_kat.py`
