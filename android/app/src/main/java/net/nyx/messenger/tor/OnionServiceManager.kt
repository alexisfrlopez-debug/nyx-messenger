package net.nyx.messenger.tor

/**
 * Hébergement du service onion v3 du client (son identité = son point d'écoute — ADR-003).
 *
 * v0.2 (ADR-007) : publication RÉELLE via le control port du Tor embarqué —
 * `ADD_ONION ED25519-V3:<clé base64>` — exactement comme le fait le serveur Go
 * (server/internal/tor/control.go). La clé est déscellée du Keystore uniquement
 * en mémoire, au moment de la publication ; elle n'est jamais écrite sur disque.
 */
interface OnionServiceManager {
    /** Publie le service onion du client (clé = identité). Retourne l'adresse obtenue. */
    suspend fun publish(privateKeyEd25519: ByteArray, localPort: Int): Result<String>

    /** Retire le service (arrêt propre). */
    suspend fun unpublish()
}

/** Implémentation v0.2 — via le control port du Tor embarqué (ADR-007). */
class ControlPortOnionServiceManager(
    private val torManager: TorManager,
) : OnionServiceManager {

    override suspend fun publish(privateKeyEd25519: ByteArray, localPort: Int): Result<String> {
        require(privateKeyEd25519.size == 64) {
            "clé ed25519 : ${privateKeyEd25519.size} octets ≠ 64 (graine + clé publique)"
        }
        return torManager.publishOwnOnion(localPort = localPort)
    }

    override suspend fun unpublish() = torManager.unpublishOwnOnion()
}
