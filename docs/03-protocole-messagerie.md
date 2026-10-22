# Protocole de messagerie (v0.1 → v0.2)

## 1. Identité : l'adresse onion comme clé publique

Une adresse onion v3 encode une **clé publique ed25519** (56 caractères base32 :
clé publique + checksum + version). Conséquence structurante :

- L'utilisateur qui génère une paire ed25519 et la publie comme service onion obtient
  une identité dont **le nom est la clé de vérification** : vérifier un message signé
  ne requiert aucun annuaire, l'adresse suffit.
- Le client et le serveur partagent la même mécanique de création d'identité
  (`ADD_ONION ED25519-V3:<clé privée>` côté Tor).
- Conversion canonique ed25519 → X25519 (libsodium `crypto_sign_ed25519_*_to_curve25519`)
  pour tout ce qui est chiffrement Diffie-Hellman.

## 2. Cycle de vie d'un contact

1. Échange hors bande de l'adresse onion (QR code, lien d'invitation `nyx://add?o=…`,
   canal existant). Pas d'annuaire global — décision ADR-003.
2. L'app de l'ajouteur publie un événement `contact` **non chiffré** : annonce publique
   « j'ai ajouté X » (l'autre partie peut l'observer pour suggérer la réciproque).
3. La première session E2E s'établit à la première écriture (cf. §3).
4. En v0.2, l'événement `prekey` publie le bundle de pré-clés Signal (one-time prekeys)
   permettant l'asynchronie X3DH complète.

## 3. Chiffrement d'un message 1:1

### v0.1 — Sealed box (libsodium)

```
claire        = JSON {text, sent_at, reply_to?}
clé ephemère  = crypto_box_keypair()  (générée PAR MESSAGE)
body          = crypto_box_seal(claire, curve25519(pubkey_onion_du_destinataire))
```

Propriétés : chiffrement authentifié, anonyme (l'émetteur n'a pas besoin d'être connu
pour chiffrer), sans état partagé à gérer. L'authenticité vient de la signature de
l'événement (identité onion). Limite : pas de forward secrecy par message → v0.2.

### v0.2 — Signal Protocol (décision ADR-002)

- X3DH : établissement de secret partagé initial avec pré-clés publiées dans le journal
  (`prekey`), asynchrone — Bob peut être hors ligne.
- Double Ratchet : chaque message tourne la clé ; forward secrecy + self-healing.
- Implémentation : `libsignal-protocol` (au choix : le port Kotlin officiel ou via JNI).
- Les événements `msg` portent alors `body = {ratchet_header, ciphertext}` ; le format
  filaire de l'événement reste inchangé.

## 4. Messages éphémères

- Champ `ttl` (secondes) dans l'événement ; `ttl = 0` = permanent.
- Sémantique côté client (v0.1) : le message est effacé de la base locale après
  `ttl` secondes à compter du **déchiffrement** ; l'UI n'affiche jamais un message expiré.
- Côté serveur en v0.1 : conservé (limitation assumée, voir modèle de sécurité).
  v0.2 : purge serveur des événements expirés (GC par balayage du journal).

## 5. Réception et fiabilité

- Polling `GET /client/inbox?to=<moi>&since=<dernier_seq_appliqué>` toutes les 10 s
  (paramétrable, économie de batterie : fenêtre adaptative en arrière-plan).
- Chaque événement reçu est vérifié (signature, chaînage seq par auteur) **avant** stockage.
- Le dernier `seq` appliqué est persisté par auteur : un redémarrage de l'app reprend
  exactement où elle s'était arrêtée (sémantique at-least-once + dé-duplication par `id`).
- Accusés de réception (phase 2) : événement `msg` de type `receipt` chiffré au retour.

## 6. Connectivité Android

- Tout le HTTP client passe par le SOCKS5 local de Tor (Orbot v0.1) : `RelayClient`
  construit son `OkHttpClient` avec `Proxy(Type.SOCKS, 127.0.0.1, 9050)` — en cas
  d'absence de Tor, les appels échouent (jamais de repli hors Tor, par conception).
- Le service onion du client est hébergé via Orbot (API onion v3) en v0.1 ;
  v0.2 : IPtProxy embarqué pour un Tor natif dans l'app (sans Orbot).
