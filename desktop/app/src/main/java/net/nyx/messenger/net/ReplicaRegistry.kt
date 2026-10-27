package net.nyx.messenger.net

import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.repo.ReplicaCodec
import java.io.File

/**
 * Registre des réplicas découverts — portage desktop de ReplicaRegistry
 * Android (v0.2.25) : persistance fichier (remplace SharedPreferences),
 * TTL 7 jours, re-fusion de la liste effective via [onChanged].
 */
object ReplicaRegistry {

    private var storeFile: File? = null
    private val entries = LinkedHashMap<String, Long>() // addr → ts dernière annonce
    private val _lock = Any()

    @Volatile var onChanged: (() -> Unit)? = null

    fun init(file: File) {
        synchronized(_lock) {
            if (storeFile == file) return
            storeFile = file
            entries.clear()
            val raw = if (file.isFile) file.readText() else ""
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

    fun ingest(replicaAddr: String, now: Long): Boolean {
        val addr = replicaAddr.trim().lowercase()
        synchronized(_lock) {
            val known = entries[addr]
            if (known != null && known >= now - ReplicaCodec.DISCOVERY_TTL_MS && known >= now) {
                return false // déjà connue ET plus récente ou égale
            }
            if (known != null && now <= known) return false
            entries[addr] = now
            purgeLocked(now)
            persistLocked()
        }
        return true
    }

    /** Adresses DÉCOUVERTES valides (TTL 7 j) — lues par la fusion des réplicas. */
    fun list(now: Long = System.currentTimeMillis()): List<String> = synchronized(_lock) {
        purgeLocked(now)
        entries.keys.toList()
    }

    private fun purgeLocked(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ReplicaCodec.DISCOVERY_TTL_MS) it.remove()
        }
    }

    private fun persistLocked() {
        val f = storeFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(entries.entries.joinToString("\n") { "${it.key}|${it.value}" })
        }.onFailure {
            DesktopLog.w("nyx-replica-registry", "persistance impossible : ${it.message}")
        }
    }
}
