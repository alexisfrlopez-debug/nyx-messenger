package net.nyx.messenger.tor

/**
 * Hébergement du service onion v3 du client (son identité = son point d'écoute — ADR-003).
 *
 * v0.1 : délégation à Orbot (menu « Onion Services » / API v3 custom service).
 * v0.2 : IPtProxy embarqué — publication directe de la clé ed25519 de l'identité
 *        (miroir de server/internal/tor/control.go : ADD_ONION ED25519-V3:<clé>).
 *
 * L'interface est déjà celle de la v0.2 pour que la migration n'exige aucune
 * modification du reste de l'application.
 */
interface OnionServiceManager {
    /** Publie le service onion du client (clé = identité). Retourne l'adresse obtenue. */
    suspend fun publish(privateKeyEd25519: ByteArray, localPort: Int): Result<String>

    /** Retire le service (arrêt propre). */
    suspend fun unpublish()
}

/** Implémentation v0.1 — Orbot : TODO brancher l'API onion d'Orbot (phase POC device). */
class OrbotOnionServiceManager : OnionServiceManager {
    override suspend fun publish(privateKeyEd25519: ByteArray, localPort: Int): Result<String> {
        // Orbot v0.5.x expose la création de services via son interface (activité dédiée).
        // Intégration précise à réaliser sur device — voir docs/05-setup-dev.md §Pièges.
        return Result.failure(NotImplementedError("Publication onion via Orbot : à intégrer (v0.1 device)"))
    }

    override suspend fun unpublish() { /* TODO */ }
}
