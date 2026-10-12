package net.nyx.messenger.settings

import android.content.Context

/**
 * Réglages utilisateur (opt-in, locaux — jamais transmis).
 * v0.1.2 : verrou biométrique de l'application (ADR-010 §3 : déverrouillage LOCAL,
 * l'OS vérifie, l'app ne reçoit qu'un succès/échec ; aucune donnée biométrique
 * n'est lue, stockée ou transmise).
 */
class SettingsManager private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    /** Verrou biométrique de l'app — opt-in à l'onboarding, désactivable. */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    companion object {
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock"

        @Volatile private var instance: SettingsManager? = null
        fun get(context: Context): SettingsManager =
            instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
    }
}
