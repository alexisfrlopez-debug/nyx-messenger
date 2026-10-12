package net.nyx.messenger.net

/**
 * Réplicas par défaut (bootstrap). En production : liste signée embarquée + éditable.
 * En dev : le nœud local lancé par scripts/dev-server.sh.
 */
object Replicas {
    data class Replica(val onion: String, val gossipPub: String = "", val label: String = "")

    // v0.1 dev : vide par défaut — l'utilisateur ajoute le réplica de test via l'UI.
    val DEFAULT: List<Replica> = listOf(
        // Replica("abcd…56.onion", "BASE64_GOLSSIP_PUB", "Réplica principal"),
    )
}
