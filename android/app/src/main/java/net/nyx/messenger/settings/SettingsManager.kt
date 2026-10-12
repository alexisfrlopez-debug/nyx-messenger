package net.nyx.messenger.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Réglages utilisateur (opt-in, locaux — jamais transmis).
 *
 * v0.1.2 : verrou biométrique de l'application (ADR-010 §3 : déverrouillage LOCAL,
 * l'OS vérifie, l'app ne reçoit qu'un succès/échec ; aucune donnée biométrique
 * n'est lue, stockée ou transmise).
 *
 * v0.2.0 (session 12) :
 *  - thème de l'interface (système / clair / sombre) — réactif via [themeMode] ;
 *  - comportement à la fermeture (demander / réduire / quitter) — le dialogue
 *    « réduire (Tor actif) ou quitter (tout arrêter) » est piloté par ce réglage.
 */
class SettingsManager private constructor(context: Context) {

    enum class ThemeMode(val key: String) { SYSTEM("system"), LIGHT("light"), DARK("dark");
        companion object {
            fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: SYSTEM
        }
    }

    enum class CloseBehavior(val key: String) { ASK("ask"), MINIMIZE("minimize"), EXIT("exit");
        companion object {
            fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: ASK
        }
    }

    private val prefs = context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)

    // ---- verrou biométrique (v0.1.2, ADR-010 §3) ----

    /** Verrou biométrique de l'app — opt-in à l'onboarding, désactivable. */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    // ---- interface (v0.2.0) ----

    private val _themeMode = MutableStateFlow(ThemeMode.fromKey(prefs.getString(KEY_THEME, null)))
    /** Thème de l'interface — StateFlow réactif (l'activité le collecte). */
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.key).apply()
        _themeMode.value = mode
    }

    // ---- fermeture de l'app (v0.2.0) ----

    private val _closeBehavior = MutableStateFlow(CloseBehavior.fromKey(prefs.getString(KEY_CLOSE, null)))
    /** Comportement du bouton retour / fermeture — StateFlow réactif. */
    val closeBehavior: StateFlow<CloseBehavior> = _closeBehavior

    fun setCloseBehavior(b: CloseBehavior) {
        prefs.edit().putString(KEY_CLOSE, b.key).apply()
        _closeBehavior.value = b
    }

    companion object {
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CLOSE = "close_behavior"

        @Volatile private var instance: SettingsManager? = null
        fun get(context: Context): SettingsManager =
            instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
    }
}
