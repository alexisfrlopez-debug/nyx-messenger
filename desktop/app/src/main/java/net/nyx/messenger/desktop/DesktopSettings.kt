package net.nyx.messenger.desktop

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Réglages persistés desktop (miroir SettingsManager Android — sous-ensemble
 * v1 : thème, réplique embarquée, verrou, comportement de fermeture).
 */
class DesktopSettings(private val file: File) {

    @Serializable
    data class Data(
        val theme: String = "nuit",                 // "nuit" | "aube" | "système"
        val embeddedReplicaEnabled: Boolean = true, // interrupteur Paramètres (miroir Android)
        val lockEnabled: Boolean = false,           // verrou par mot de passe opt-in
        val closeMinimizes: Boolean = true,         // Réduire vs Quitter par défaut
        val customReplica: String = "",             // réplica saisi manuellement (Connexions)
    )

    @Volatile private var cached: Data = Data()

    init { load() }

    fun get(): Data = cached

    fun update(mutate: (Data) -> Data) {
        cached = mutate(cached)
        JsonStore.write(file, Data.serializer(), cached)
    }

    private fun load() {
        cached = JsonStore.read(file, Data.serializer()) ?: Data()
    }
}
