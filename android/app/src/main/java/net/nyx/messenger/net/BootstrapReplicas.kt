package net.nyx.messenger.net

/**
 * Analyse de la liste des réplicas d'amorçage embarqués (v0.2.7).
 *
 * BUG v0.2.6 (régression silencieuse — logs device Blackview 16:36) : le
 * filtre « endsWith(".onion") » de NyxApplication rejetait les entrées
 * portant leur port (« ….onion:80 » — format écrit dans
 * replica-bootstrap.properties depuis la publication du data onion sur le
 * port 80) → liste vide au boot (« aucun réplica d'amorçage embarqué ») →
 * sonde réplica JAMAIS lancée → écran Connexions bloqué sur « en attente »,
 * ALORS QUE l'entête d'export de logs affichait la chaîne brute embarquée
 * (diagnostic trompeur). v0.2.5 embarquait l'adresse SANS port, d'où le
 * « ça marchait avant » : le réseau, Tor et le serveur n'étaient pas en cause.
 *
 * Format accepté (insensible à la casse, doublons éliminés) :
 *   <56 caractères base32 a-z2-7>.onion[:<port>]
 * séparés par virgule, point-virgule, espace, tabulation ou fin de ligne.
 * Toute autre entrée est écartée (garde-fou anti-garbage).
 *
 * Objet PUR (aucune classe Android) → testable en JVM (BootstrapReplicasTest).
 */
object BootstrapReplicas {

    /** v3 : exactement 56 caractères base32, puis « .onion », port optionnel. */
    private val ENTRY = Regex("^[a-z2-7]{56}\\.onion(:\\d+)?$")

    fun parse(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .filter { ENTRY.matches(it) }
            .distinct()
}
