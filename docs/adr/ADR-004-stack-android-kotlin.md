# ADR-004 — Client Android natif Kotlin + Jetpack Compose

Statut : **Accepté** (2026-10-12)

## Contexte

Le client doit héberger un service onion (via Tor), chiffrer en E2E, parler à des
réplicas en SOCKS5, et rester fiable en arrière-plan (batterie, Doze). Candidats :
Kotlin natif, Flutter, React Native.

## Décision

**Kotlin natif + Jetpack Compose**, avec les briques :

| Brique | Choix |
|---|---|
| UI | Jetpack Compose (Material 3) |
| Tor v0.1 | Orbot (SOCKS5 local 9050 + API onion v3) |
| Tor v0.2 | IPtProxy embarqué (Tor natif, sans dépendance à une app externe) |
| Crypto | lazysodium-android (libsodium) ; libsignal v0.2 |
| Données | Room + SQLCipher |
| Réseau | OkHttp forcé SOCKS5 (aucun trafic clair possible) |
| Sérialisation | kotlinx.serialization (JSON filaire canonique contrôlé) |

Flutter/React Native rejetés : l'intégration Tor (services d'arrière-plan, sockets
bruts, publication d'onion service) exige un pont natif complet de toute façon — la
couche « multiplateforme » n'apporterait que de l'adhésif sur du natif obligatoire.
iOS viendra en phase 2 en réimplémentant la couche crypto/réseau (mêmes specs).

## Conséquences

- ✅ Meilleur contrôle des services d'arrière-plan (foreground service dédié à Tor),
  des sockets et de la sécurité mémoire.
- ✅ Compose accélère l'itération UI (onboarding, conversations, chat).
- ⚠️ Uniquement Android en phase 1 : assumé, l'utilisateur le demande explicitement.
- ⚠️ Reproductibilité F-Droid (builds déterministes) à traiter avant v1.0 (flagged dans
  la roadmap).
