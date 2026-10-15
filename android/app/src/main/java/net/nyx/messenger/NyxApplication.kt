package net.nyx.messenger

import android.app.Application
import android.util.Log
import net.nyx.messenger.di.ServiceLocator

/**
 * Application Nyx — initialise le localisateur de services (DI manuelle volontaire :
 * le projet reste petit et auditable ; passage à Hilt si la surface l'exige).
 *
 * v0.2.3 : câblage des réplicas d'amorçage (bug v0.2.2 n°2 — [net.nyx.messenger.repo.NyxRepository.setReplicas]
 * n'était jamais appelé : aucun réplica rattaché, tout le réseau échouait).
 * La liste embarquée (BuildConfig.BOOTSTRAP_REPLICAS, fichier git-ignoré
 * replica-bootstrap.properties) est le pont en attendant le rendezvous signé
 * ADR-009 ; vide → l'app démarre sans réplica et l'onboarding l'indique.
 */
class NyxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        val list = BuildConfig.BOOTSTRAP_REPLICAS.split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.endsWith(".onion") }
        if (list.isNotEmpty()) {
            ServiceLocator.repository.setReplicas(list)
            Log.i("nyx-boot", "réplicas d'amorçage : ${list.size} (${list.first().take(16)}…)")
        } else {
            Log.w("nyx-boot", "aucun réplica d'amorçage embarqué — réseau en attente de configuration")
        }
    }
}
