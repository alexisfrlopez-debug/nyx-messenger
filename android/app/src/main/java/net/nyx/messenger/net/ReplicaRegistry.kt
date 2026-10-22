package net.nyx.messenger.net

import android.content.SharedPreferences
import android.util.Log
import net.nyx.messenger.repo.ReplicaCodec

/**
 * v0.2.25 — REGISTRE DES RÉPLICAS DÉCOUVERTS (demande opérateur : « je voudrais
 * qu'ils s'ajoutent automatiquement à la connexion et restent en mémoire pour
 * accélérer les connexions suivantes »).
 *
 * Chaque annonce `{"replica":"<onion>:<port>"}` d'un contact accepté (type
 * « contact » chiffré E2E — cf. [ReplicaCodec]) est ingérée ICI :
 *   - garde-fous d'identité (l'adresse annoncée = l'auteur, cf. ReplicaCodec) ;
 *   - horodatage + PERSISTANCE (SharedPreferences — survit aux redémarrages,
 *     « restent en mémoire pour accélérer les connexions suivantes ») ;
 *   - TTL : une découverte non ré-annoncée pendant [ReplicaCodec.DISCOVERY_TTL_MS]
 *     (7 jours) disparaît de la liste (un téléphone ne sert pas un réplica
 *     éternellement) ;
 *   - à chaque découverte NOUVELLE (ou re-annoncée plus récemment), le callback
 *     [onChanged] est invoqué → la liste effective des réplicas est re-fusionnée
 *     et appliquée (cf. NyxApplication : custom > DÉCOUVERTS > rendezvous >
 *     bootstrap) sans redémarrage.
 *
 * Fil d'exécution : ingestion (NyxRepository, thread poll) → [ingest] →
 * [onChanged] → NyxRepository.setReplicas(merged) → le relais est reconstruit
 * paresseusement au prochain usage (relay() null-able).
 */
object ReplicaRegistry {

    private var prefs: SharedPreferences? = null
    private val entries = LinkedHashMap<String, Long>() // addr → ts dernière annonce
    private val _lock = Any()

    /** Invoqué quand la liste effective doit être re-fusionnée (thread poll). */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Charge la persistance au démarrage (NyxApplication). Idempotent. */
    fun init(p: SharedPreferences) {
        synchronized(_lock) {
            if (prefs == p) return
            prefs = p
            entries.clear()
            val raw = p.getString(KEY, "") ?: ""
            for (line in raw.split('\n')) {
                if (line.isBlank()) continue
                val idx = line.lastIndexOf('|')
                if (idx <= 0) continue
                val addr = line.substring(0, idx).trim().lowercase()
                val ts = line.substring(idx + 1).toLongOrNull() ?: continue
                if (BootstrapReplicas.ENTRY.matches(addr)) entries[addr] = ts
            }
        }
    }

    /**
     * Ingestion d'une annonce vérifiée (l'appelant a déjà appliqué les garde-fous
     * ReplicaCodec). Renvoie true si l'état a changé (nouvelle entrée).
     */
    fun ingest(replicaAddr: String, now: Long): Boolean {
        val addr = replicaAddr.trim().lowercase()
        if (!BootstrapReplicas.ENTRY.matches(addr)) return false
        var changed = false
        synchronized(_lock) {
            val prev = entries[addr]
            if (prev == null) {
                entries[addr] = now
                changed = true
            } else {
                entries[addr] = maxOf(prev, now) // re-annonce → rafraîchit le TTL
            }
            persistLocked()
        }
        if (changed) {
            // Taille brute (entries.size) et NON list() : la liste épure à la
            // volée au now RÉEL — un appel ici retirerait l'entrée qui vient
            // d'être insérée avec un horodatage de test/horloge décalée.
            Log.i("nyx-replica", "réplica découvert et persisté : ${addr.take(20)}… (${entries.size} au total)")
        }
        return changed
    }

    /**
     * Liste effective des découverts — triés par ANNONCE LA PLUS RÉCENTE
     * d'abord (le réplica le plus fraîchement annoncé est probablement en
     * ligne), doublons de la liste custom exclus par l'appelant.
     * Les entrées périmées (> TTL) sont éliminées à la volée.
     */
    fun list(now: Long = System.currentTimeMillis()): List<String> {
        val out = ArrayList<String>()
        synchronized(_lock) {
            val it = entries.entries.iterator()
            var dirty = false
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > ReplicaCodec.DISCOVERY_TTL_MS) {
                    it.remove(); dirty = true; continue
                }
                out.add(e.key)
            }
            if (dirty) persistLocked()
        }
        return out.sortedByDescending { entries[it] ?: 0L }
    }

    /** Vidage manuel (Paramètres — diagnostics). */
    fun clear() {
        synchronized(_lock) {
            entries.clear()
            persistLocked()
        }
    }

    private fun persistLocked() {
        val p = prefs ?: return
        val sb = StringBuilder()
        for ((addr, ts) in entries) sb.append(addr).append('|').append(ts).append('\n')
        p.edit().putString(KEY, sb.toString()).apply()
    }

    private const val KEY = "discovered_replicas"
}
