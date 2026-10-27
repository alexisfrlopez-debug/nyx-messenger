package net.nyx.messenger.net

/**
 * v0.3.5 (session 75) — MEMOÏSATION du rendezvous signé : calcul UNE SEULE
 * fois par process.
 *
 * POURQUOI : la fusion des réplicas ([ServiceLocator.rebuildReplicaList]) est
 * rafraîchie à CHAQUE cycle de poll (30 s — les boîtes des contacts y entrent
 * sans attendre une annonce). Elle appellait le chargement du rendezvous
 * signé, qui RE-VÉRIFIAIT la signature ed25519 et REJOUAIT l'avertissement à
 * chaque cycle : l'export terrain v0.3.4 montrait « rendezvous signé
 * INVALIDE » toutes les ~30 s (12 occurrences en 4 minutes) — du SPAM et du
 * travail inutile pour une ressource embarquée IMMUABLE. L'Android vérifie
 * UNE fois au boot (NyxApplication.onCreate) ; ce portage retrouve le même
 * comportement au premier accès.
 *
 * Objet PUR (aucune dépendance Android/desktop) → testable JVM
 * (RendezvousOnceTest : double appel = un seul calcul).
 *
 * @param compute produit la liste (vérification + lecture de ressource) —
 *   appelé au plus UNE fois, résultats ultérieurs servis du cache.
 */
class RendezvousOnce(private val compute: () -> List<String>) {

    @Volatile
    private var computed = false

    @Volatile
    private var cached: List<String> = emptyList()

    /** Entrées vérifiées — calcule au premier appel, sert du cache ensuite. */
    @Synchronized
    fun entries(): List<String> {
        if (!computed) {
            cached = compute()
            computed = true
        }
        return cached
    }

    /** Diagnostic/tests : le calcul a-t-il déjà eu lieu ? */
    fun isComputed(): Boolean = computed
}
