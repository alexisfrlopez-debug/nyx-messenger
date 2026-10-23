package net.nyx.messenger.repo

/**
 * v0.2.45 — DÉCLENCHEUR DU HSFETCH DE GUÉRISON (fonction pure — test JVM).
 *
 * CAUSE RACINE terrain 23/10 19:0x (session 67) : le déploiement 1-clic du
 * serveur (systemctl stop → ADD_ONION sans Detach → service détruit) laisse
 * l'ANCIEN descripteur servi par les HSDirs pendant la fenêtre de
 * re-publication ; un client qui le fetchait dans cette fenêtre LE CACHE
 * jusqu'à ~24 h (tor hs_cache.c — expiration au début de la prochaine
 * période temporelle) → tous ses polls vers ce réplica meurent en timeout
 * 25 s en boucle, alors qu'un appareil au cache VIERGE joint le même serveur
 * en ~227 ms (asymétrie Blackview 5G / Xiaomi WiFi du retour terrain).
 *
 * LA GUÉRISON : après 2 échecs réseau CONSÉCUTIFS du même réplica, forcer
 * HSFETCH (contournement du cache client — mécanique v0.2.37) : le prochain
 * poll récupère le descripteur frais. Cooldown par réplica : un HSFETCH est
 * une commande contrôle tor, inutile de la répéter si le nœud est
 * réellement en panne.
 */
object DescriptorHeal {

    /**
     * [consecutiveFails] — échecs réseau consécutifs du réplica (remis à
     *   zéro à chaque succès de sonde/poll) ;
     * [lastHealAtMs] — 0 si jamais guéri ;
     * [nowMs] — horloge courante.
     */
    fun shouldHeal(
        consecutiveFails: Int,
        lastHealAtMs: Long,
        nowMs: Long,
        cooldownMs: Long,
    ): Boolean = consecutiveFails >= 2 &&
        (lastHealAtMs == 0L || nowMs - lastHealAtMs > cooldownMs)
}
