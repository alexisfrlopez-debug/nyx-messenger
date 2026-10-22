# Modèle de sécurité Nyx (v0.1)

## 1. Acteurs et capacités supposées

| Acteur | Capacités supposées |
|---|---|
| Utilisateur final | Passe le trafic par Tor ; peut être compromis localement (malware, saisie du téléphone) |
| Serveurs Nyx (réplicas) | Honnêtes-faillibles en v0.1 : ils voient des métadonnées et ne peuvent forger ; **ne pas y stocker de secret** |
| Réseau Tor | Anonymat réseau contre un adversaire global non omniscient (garanties standards de Tor) |
| Adversaire externe | Observation réseau, censure, scan d'adresses onion, Sybil sur le réseau Tor |
| Compromission d'un nœud serveur | Lecture de tout le journal (couches chiffrées intactes), censure, DoS — **pas** de falsification |

## 2. Garanties fournies

1. **Confidentialité du contenu** : les corps de messages sont chiffrés au destinataire
   (sealed box v0.1, Signal Protocol v0.2). Un serveur qui compromet tous les réplicas
   ne voit que des enveloppes opaques.
2. **Authenticité** : chaque événement est signé par la clé ed25519 de son auteur, qui est
   indissociable de son adresse onion. Un serveur ne peut pas attribuer un message à un
   autre utilisateur.
3. **Intégrité et non-réordonnancement** : la séquence par auteur (`seq`) rend toute
   divergence d'ordre détectable et non résolvable en faveur d'un faux.
4. **Anonymat réseau** : aucune IP n'apparaît dans le système ; clients et serveurs ne
   sont joignables qu'en onion. L'app n'utilise aucun réseau clair (le client HTTP est
   forcé SOCKS5 — échec en clair plutôt que fuite).
5. **Disponibilité par réplication** : la perte d'un réplica n'entraîne pas de perte de
   données tant qu'un autre réplica possède le journal (objectif : ≥ 3 réplicas en prod).
6. **Forward secrecy (v0.2, Signal Protocol)** : compromettre la clé d'identité ne
   déchiffre pas l'historique des sessions ratchetées antérieures.

## 3. Limites connues et acceptées en v0.1

| Limitation | Impact | Plan |
|---|---|---|
| Champ `to` en clair dans les événements | Un réplica sait qui écrit à qui (métadonnée de routage) | v0.2 : chiffrement de `to` + filtrage client (prefix filter façon Cwtch) |
| Horodatage `ts` déclaré par l'auteur | Un client peut mentir sur la date | Accepté en v0.1 (pas d'horloge distribuée fiable sans serveur d'horodatage) |
| Serveurs conservent les événements TTL | « Éphémère » n'est garanti que côté client | v0.2 : purge serveur par garbage collection |
| Polling (10 s) au lieu de push P2P | Latence de notification | v0.2 : connexions directes client↔client entre onion services |
| Clé perdue = identité perdue | Aucune récupération par conception | Options phase 2 : sauvegarde chiffrée par phrase secrète (BIP39), garde partagée (Shamir) |
| Vraisemblance du déni d'authenticité | Le journal signé persistant affaiblit le déni d'authenticité du Signal Protocol | Accepté : la réplication exige des signatures persistantes (compromis documenté ADR-002) |

## 4. Menaces spécifiques au futur market store (phase 2) — à intégrer dès maintenant

1. **Sybil / faux vendeurs** : identités jetables gratuites → attaque de réputation.
   Contre-mesures prévues : réputation construite uniquement sur des escrows réglés
   (attestations signées), chaîne d'invitations signées pour élever le coût de création
   d'identités frauduleuses.
2. **Double dépense / commandes dupliquées** : le gossip à cohérence éventuelle ne suffit
   pas pour les opérations transactionnelles → séquenceur/quorum dédié aux événements
   `tx` (ADR-006), jamais pour la messagerie.
3. **Clés de portefeuille** : interdiction absolue de confier une clé privée de wallet
   aux serveurs. Escrow = multisig 2-of-3 (acheteur, vendeur, arbitre) — le serveur
   d'arbitrage ne détient que sa propre clé d'arbitrage, jamais les autres.
4. **Réglementaire** : messagerie privée OK ; marketplace entre pairs = cadre e-commerce
   et DSA ; service d'échange crypto↔fiat = réglementé (France : PSAN/AMF, KYC/AML) —
   incompatible avec l'anonymat ; le volet « trade » devra être arhitecturé en conséquence
   (voir `04-roadmap.md`).

## 5. Hygiène cryptographique

- ed25519 pour les identités/signatures ; X25519 (dérivé des clés ed25519 par conversion
  canonique libsodium) pour l'échange ; XSalsa20-Poly1305 (secretbox/sealed box) pour les
  canaux ; SHA-256 pour l'adressage des événements.
- Zéro algorithme maison : uniquement libsodium (serveur Go : `golang.org/x/crypto/nacl`,
  API-équivalente libsodium) et libsignal côté Android.
- Les clés privées serveur (identité + gossip) sont stockées dans le répertoire de données
  avec permissions 0600 ; sur Android, scellées via Android Keystore + SQLCipher.
- Aléa : `crypto/rand` uniquement.
