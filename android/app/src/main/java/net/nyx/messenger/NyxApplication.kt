package net.nyx.messenger

import android.app.Application
import android.util.Log
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.net.BootstrapReplicas
import net.nyx.messenger.net.RendezvousList

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
 *
 * v0.2.23 — RENDEZVOUS SIGNÉ (ADR-009 §3, ADR-016) : l'asset embarqué
 * assets/rendezvous.json (signé ed25519 par la clé de release, clé publique
 * BuildConfig.RENDEZVOUS_PUBKEY) est désormais la source d'amorçage de
 * référence. Une liste altérée est refusée EN BLOC et retombe sur le pont
 * legacy (v0.2.7) — jamais d'injection non signée. Priorité de fusion :
 *   1. réplicas CUSTOM (Paramètres — build « test 2 »)
 *   2. rendezvous signé (assets/rendezvous.json)
 *   3. bootstrap legacy (replica-bootstrap.properties)
 */
class NyxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // v0.2.19 : les réplicas CUSTOM (Paramètres — build « test 2 ») passent
        // AVANT les réplicas d'amorçage embarqués : le premier de la liste est
        // utilisé (envoi + inbox). Permet de rattacher le client au réplica
        // temporaire d'un contact (fonctionnement sans le serveur central).
        val settings = net.nyx.messenger.settings.SettingsManager.get(this)

        // v0.2.23 : rendezvous signé — vérification ed25519 AVANT tout usage.
        val rendezvous = try {
            val raw = assets.open("rendezvous.json").use { it.readBytes() }
            val sig = assets.open("rendezvous.json.sig").use { it.readBytes() }
            val v = RendezvousList.load(raw, sig, BuildConfig.RENDEZVOUS_PUBKEY)
            if (v == null) {
                Log.w("nyx-boot", "rendezvous signé INVALIDE (signature/format) — fallback legacy")
            }
            v?.entries?.map { it.onion }
        } catch (e: Exception) {
            Log.w("nyx-boot", "rendezvous signé indisponible (${e.javaClass.simpleName}) — fallback legacy")
            null
        }

        // v0.2.25 — RÉPLICAS DÉCOUVERTS (announces contacts chiffrées, cf.
        // ReplicaRegistry) : persistés, placés AVANT le rendezvous — les
        // réplicas de proximité servent l'amorçage et survivent au serveur
        // central ; la fusion est re-appliquée à chaque annonce entrante.
        val appPrefs = getSharedPreferences("nyx_settings", MODE_PRIVATE)
        net.nyx.messenger.net.ReplicaRegistry.init(appPrefs)
        val discovered = net.nyx.messenger.net.ReplicaRegistry.list()
        net.nyx.messenger.net.ReplicaRegistry.onChanged = {
            val remerged = buildList {
                addAll(settings.customReplicas)
                addAll(net.nyx.messenger.net.ReplicaRegistry.list())
                addAll(rendezvous ?: emptyList())
                addAll(BootstrapReplicas.parse(BuildConfig.BOOTSTRAP_REPLICAS))
            }.distinct()
            if (remerged.isNotEmpty()) ServiceLocator.repository.setReplicas(remerged)
        }

        val merged = (settings.customReplicas
                + discovered
                + (rendezvous ?: emptyList())
                + BootstrapReplicas.parse(BuildConfig.BOOTSTRAP_REPLICAS))
            .distinct()
        if (merged.isNotEmpty()) {
            ServiceLocator.repository.setReplicas(merged)
            Log.i(
                "nyx-boot",
                "réplicas : ${merged.size} (rendezvous=${rendezvous?.size ?: 0}, " +
                    "custom=${settings.customReplicas.size}, découverts=${discovered.size}, " +
                    "premier=${merged.first().take(16)}…)"
            )
        } else {
            Log.w("nyx-boot", "aucun réplica d'amorçage embarqué — réseau en attente de configuration")
        }
    }
}
