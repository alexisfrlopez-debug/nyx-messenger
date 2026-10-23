package net.nyx.messenger.repo

/**
 * v0.2.41 — DÉCISIONS DE TRANSPORT (ADR-017 — « 2ᵉ protocole de connexion ») :
 * logique PURE et testée, partagée par [NyxRepository] (ordre d'envoi) et le
 * watchdog tor de ServiceLocator (garde anti-churn).
 *
 * Ordre adaptatif — le journal répliqué reste le chemin PREMIER ; le canal
 * direct P2P (onion→onion, inspiration Briar) prend le relais :
 *  - réplica DÉGRADÉ (échec de transport récent) → direct D'ABORD ;
 *  - échec TRANSPORT du journal → direct en REPLI ;
 *  - exactement UN chemin réussi par envoi.
 */
object TransportOrder {

    /**
     * Le chemin journal est-il DÉGRADÉ ? Vrai ssi un échec de transport vers
     * un réplica date de moins de [windowMs] (0 = jamais échoué → sain).
     */
    fun relayDegraded(lastTransportFailAtMs: Long, nowMs: Long, windowMs: Long): Boolean =
        lastTransportFailAtMs > 0L && nowMs - lastTransportFailAtMs < windowMs

    /**
     * v0.2.41 — Garde ANTI-CHURN du redémarrage tor (cause racine des logs
     * 23/10 : le heal tuait un démon fraîchement bootstrappé sur un réseau
     * simplement lent → points d'introduction morts → cascade « SOCKS server
     * general failure »). Le démon n'est redémarré que si :
     *  - au moins [threshold] échecs de transport consécutifs, ET
     *  - le démon est Ready depuis ≥ [minReadyMs] (un bootstrap récent
     *    construit encore ses circuits — le laisser vivre), ET
     *  - le cooldown depuis le dernier heal est écoulé.
     */
    fun shouldHealTor(
        consecutiveTransportFails: Int,
        readyForMs: Long,
        msSinceLastHeal: Long,
        threshold: Int = 5,
        minReadyMs: Long = 10 * 60_000L,
        cooldownMs: Long = 10 * 60_000L,
    ): Boolean =
        consecutiveTransportFails >= threshold &&
            readyForMs >= minReadyMs &&
            msSinceLastHeal > cooldownMs
}
