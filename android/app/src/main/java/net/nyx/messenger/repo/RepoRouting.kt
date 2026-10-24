package net.nyx.messenger.repo

/**
 * v0.2.50 — DÉCISIONS DE ROUTAGE JOURNAL (extraites de NyxRepository pour être
 * testables JVM — terrain 24/10 11:47, « appels ne sonnent plus ») :
 *
 * L'INCIDENT. La liste des réplicas est fusionnée « DÉCOUVERTS d'abord, puis
 * rendez-vous/flotte » (NyxApplication v0.2.25 — les réplicas de proximité
 * servent l'amorçage). À deux téléphones, chaque carnet contenait donc
 * L'ONION DE L'AUTRE en index 0 : l'appelant déposait l'offre dans le journal
 * DE L'APPELÉ (bonne cible par hasard — 400-seq puis repli lien l'y déposait
 * en local), mais l'appelé POLLAIT le journal DE L'APPELANT, où ses propres
 * événements n'atterrissent JAMAIS → l'offre était invisible → la machine
 * « média AVANT l'offre — connexion gardée » attendait un courrier qui ne
 * viendrait pas → AUCUNE sonnerie, trois appels d'affilée.
 *
 * L'INVARIANT RESTAURÉ. Les événements adressés à un pair n'atterrissent que
 * dans DEUX endroits lisibles par lui :
 *   1. SON PROPRE réplica embarqué (dépôt direct sur son onion, ou requête
 *      rejouée par le lien inversé qui s'exécute sur SON téléphone) — il le
 *      lit désormais EN PROCÉDÉ à chaque cycle (dispatch local) ;
 *   2. LA FLOTTE (rendez-vous commun à tous les carnets).
 * Le journal embarqué D'UN TIERS n'est ni une cible de dépôt ni une source de
 * lecture : personne n'y dépose mes événements, et le pair visé ne le lit pas.
 */
object DepositRouting {

    /** Normalisation d'adresse (délégation à LinkFallback, 3 formats défensifs). */
    private fun key(addr: String): String = LinkFallback.hostKey(addr)

    /**
     * CIBLE DE DÉPÔT pour les événements adressés à [peer] :
     *   1. LE RÉPLICA DU PAIR (sa boîte — il la lit en local à chaque cycle) ;
     *   2. sinon LA FLOTTE (premier réplica qui n'est le téléphone d'aucun
     *      contact — rendez-vous commun) ;
     *   3. sinon le premier réplica connu (champ dégradé sans flotte).
     * [replicas] : liste fusionnée courante ; [isPeerPhone] : l'adresse est-elle
     * le téléphone d'un contact (carnet) ?
     */
    suspend fun depositTarget(
        peer: String,
        replicas: List<String>,
        isPeerPhone: suspend (String) -> Boolean,
    ): String? {
        if (replicas.isEmpty()) return null
        val peerKey = key(peer)
        // 1. La boîte du pair — priorité : dépôt le plus court, lu en local.
        for (it in replicas) if (key(it) == peerKey) return it
        // 2. La flotte — jamais le journal d'un tiers.
        for (it in replicas) if (!isPeerPhone(it)) return it
        // 3. Champ dégradé (aucune flotte connue) : le premier réplica.
        return replicas.first()
    }

    /**
     * SOURCES DE LECTURE de l'inbox (poll) : UNIQUEMENT les réplicas de
     * FLOTTE — le journal embarqué d'un pair ne contient jamais mes
     * événements (l'incident du 24/10 : le poll y brûlait ses allers-retours
     * onion pour rien, et mourait 29 s quand le pair plantait). Le réplica
     * PROPRE est lu à part, en procédé (EmbeddedReplica.dispatch) — il n'est
     * pas concerné par cette liste.
     */
    suspend fun pollTargets(
        replicas: List<String>,
        isPeerPhone: suspend (String) -> Boolean,
    ): List<String> {
        val out = ArrayList<String>(replicas.size)
        for (addr in replicas) if (!isPeerPhone(addr)) out.add(addr)
        return out
    }
}
