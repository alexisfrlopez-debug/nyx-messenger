# ADR-002 — Chiffrement : Signal Protocol (E2E) + libsodium (infrastructure)

Statut : **Accepté** (2026-10-12)

## Contexte

Trois candidats pour le chiffrement de bout en bout : Signal Protocol (Double Ratchet +
X3DH), libsodium seul (sealed box / secretstream), OpenPGP. Contraintes : mobile Android,
serveurs non fiables, canaux stables serveur↔serveur, messages éphémères futurs.

## Décision — architecture en couches

| Couche | Primitive | Usage |
|---|---|---|
| Contenu des messages | **Signal Protocol** (X3DH + Double Ratchet, libsignal) | E2E 1:1, forward secrecy par message, v0.2 |
| Interop v0.1 | Sealed box libsodium (`crypto_box_seal` vers la clé du destinataire) | Placeholder E2E fonctionnel avant libsignal |
| Canaux infrastructure | libsodium (`crypto_box` / secretbox) | Gossip serveur↔serveur |
| Stockage local | SQLCipher (AES-256) + Keystore Android | Base de l'app |
| Signatures | ed25519 (= clé onion) | Journal, authenticité des événements |

OpenPGP est rejeté : pas de forward secrecy native, gestion de clés lourde, quasi abandonné
des messageries modernes.

## Conséquences

- ✅ Le serveur ne peut jamais déchiffrer le contenu, quelle que soit la couche.
- ✅ Signal Protocol apporte forward secrecy + déni d'authenticité + self-healing.
- ⚠️ Compromis assumé : le journal signé persistant (réplication) affaiblit le déni
  d'authenticité global — on accepte de garder la preuve signée de qui a envoyé quoi à
  qui (métadonnée) tant que le contenu reste indéniable après v0.2 (ratchet).
- ⚠️ libsignal côté Android est une intégration significative (JNI/états de session) :
  d'où le placeholder sealed box en v0.1 pour débloquer le bout-en-bout.
- 🔎 Pré-requis v0.2 : publication de pré-clés via événements `prekey` (asynchronie X3DH).
