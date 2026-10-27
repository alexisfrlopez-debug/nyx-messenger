package net.nyx.messenger.repo

/**
 * v0.2.52 — RESYNCHRONISATION DE SÉQUENCE D'UN DÉPÔT — objet PUR.
 *
 * Un réplica peut refuser un dépôt (HTTP 400) quand le seq de l'auteur est
 * DÉCALÉ de ce qu'il attend : « seq N en attente (attendu M) » (dépôts
 * précédents perdus — terrain 25/10 05:35:02 : l'offre partie avant un
 * mort de tor n'est jamais arrivée, le dépôt suivant était en avance) ou
 * « seq N déjà dépassée (dernière M) » (redémarrage local de compteur).
 *
 * La voie DIRECTE resynchronisait déjà (regex inline dans postSigned) ;
 * le même traitement est désormais partagé avec la voie LIEN INVERSÉ —
 * [expectedFrom] rend le seq à RE-JOUER (attendu M, ou dernière M + 1),
 * null si le message n'est pas un refus de séquence (échec réseau, autre
 * erreur applicative).
 */
object SeqResync {
    private val ATTENDU = Regex("attendu (\\d+)")
    private val DERNIERE = Regex("dernière (\\d+)")

    /** Seq à rejouer depuis le corps d'un 400 de réplica (null = pas un refus de séquence). */
    fun expectedFrom(msg: String?): Long? {
        if (msg == null) return null
        ATTENDU.find(msg)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        DERNIERE.find(msg)?.groupValues?.get(1)?.toLongOrNull()?.let { return it + 1 }
        return null
    }
}
