package net.nyx.messenger.repo

/**
 * v0.2.46 — DÉCISION DU REPLI LIEN INVERSÉ (ADR-018) — objet PUR.
 *
 * Terrain sessions 41 → 67 : l'onion ENTRANT d'un téléphone est
 * chroniquement injoignable (descripteur non propagé / points
 * d'introduction morts / cache client périmé jusqu'à 24 h) alors que ses
 * canaux SORTANTS vivent à 100 % (journal, /health, XMPP). Le repli
 * rejoue par un AUTRE réplica sain les requêtes destinées au téléphone
 * d'un PAIR — qui les tire par son canal sortant ([LinkChannel]).
 *
 * Décision (appelée par NyxRepository.linkRouterClient) :
 *  1. le réplica en échec doit être le TÉLÉPHONE d'un pair — un réplica
 *     DÉCOUVERT (annoncé par carte de profil via [ReplicaCodec]). Pour un
 *     serveur de flotte en échec le repli ne s'applique PAS : le serveur
 *     n'a pas de canal inversé, le /link/wait brûlerait 75 s pour rien ;
 *  2. un AUTRE réplica doit exister pour router (jamais le pair lui-même
 *     — c'est LE chemin mort par définition, miroir de relayHintFor) ;
 *  3. le SOCKS Tor doit être vivant (le repli part lui aussi par Tor).
 */
object LinkFallback {

    /** Clé d'hôte normalisée (« xxx…onion:80 » → base32 nu 56 car.). */
    fun hostKey(addr: String): String =
        addr.trim().lowercase().substringBefore(':').removeSuffix(".onion")

    /**
     * Le repli lien s'applique-t-il à [failedAddr] ?
     * @param discovered réplicas DÉCOUVERTS (ReplicaRegistry.list() — les
     *   annonces de carte de profil : TOUJOURS des téléphones de pairs)
     * @param others nos réplicas ≠ failedAddr (le routeur candidat)
     * @param socksPort port SOCKS Tor actif (≤ 0 = Tor pas prêt)
     */
    fun applicable(failedAddr: String, discovered: List<String>, others: List<String>, socksPort: Int): Boolean {
        val failed = hostKey(failedAddr)
        if (failed.length != 56) return false
        if (socksPort <= 0) return false
        // (1) le réplica en échec est le téléphone d'un pair.
        val isPeerPhone = discovered.any { hostKey(it) == failed }
        if (!isPeerPhone) return false
        // (2) un autre réplica sain pour router.
        return others.any { hostKey(it) != failed }
    }
}
