package net.nyx.messenger

import android.app.Application
import android.util.Log
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.net.BootstrapReplicas

/**
 * Application Nyx — initialise le localisateur de services (DI manuelle volontaire :
 * le projet reste petit et auditable ; passage à Hilt si la surface l'exige).
 *
 * v0.2.3 : câblage des réplicas d'amorçage (bug v0.2.2 n°2 — [net.nyx.messenger.repo.NyxRepository.setReplicas]
 * n'était jamais appelé : aucun réplica rattaché, tout le réseau échouait).
 * La liste embarquée (BuildConfig.BOOTSTRAP_REPLICAS, fichier git-ignoré
 * replica-bootstrap.properties) est le pont en attendant le rendezvous signé
 * ADR-009 ; vide → l'app démarre sans réplica et l'onboarding l'indique.
 *
 * v0.2.7 : l'analyse passe par [BootstrapReplicas.parse] — l'ancien filtre
 * « endsWith(".onion") » rejetait les adresses avec port (« ….onion:80 »,
 * format du properties v0.2.6) → sonde réplica jamais lancée (écran
 * Connexions bloqué). La fonction est pure et testée (BootstrapReplicasTest).
 */
class NyxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        val list = BootstrapReplicas.parse(BuildConfig.BOOTSTRAP_REPLICAS)
        if (list.isNotEmpty()) {
            ServiceLocator.repository.setReplicas(list)
            Log.i("nyx-boot", "réplicas d'amorçage : ${list.size} (${list.first().take(16)}…)")
        } else {
            Log.w("nyx-boot", "aucun réplica d'amorçage embarqué — réseau en attente de configuration")
        }
    }
}
