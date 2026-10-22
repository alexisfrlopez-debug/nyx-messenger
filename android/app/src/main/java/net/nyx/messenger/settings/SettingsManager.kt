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

    // ---- réplica temporaire embarqué (v0.2.19 — build « test 2 ») ----

    private val _replicaEnabled = MutableStateFlow(prefs.getBoolean(KEY_REPLICA_ENABLED, true))
    /**
     * Interrupteur du réplica temporaire embarqué (pris en compte PAR
     * CONNEXION — [net.nyx.messenger.replica.EmbeddedReplica.serving] suit ce
     * flux). Par défaut ACTIF : « lorsque l'application est lancée, le
     * téléphone devienne lui-même un replica temporaire ».
     */
    val replicaEnabled: StateFlow<Boolean> = _replicaEnabled

    fun setReplicaEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_REPLICA_ENABLED, v).apply()
        _replicaEnabled.value = v
        net.nyx.messenger.replica.EmbeddedReplica.serving = v
    }

    /**
     * Réplicas SUPPLÉMENTAIRES (v0.2.19 — test 2) : adresses « onion[:port] »
     * ajoutées par l'utilisateur, PLACÉES AVANT les réplicas d'amorçage
     * embarqués (le premier de la liste effective est utilisé). Permet de
     * rattacher le client au réplica temporaire d'un contact.
     */
    var customReplicas: List<String>
        get() = net.nyx.messenger.net.BootstrapReplicas.parse(prefs.getString(KEY_CUSTOM_REPLICAS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_CUSTOM_REPLICAS, value.joinToString(",")).apply()

    // ---- messages éphémères (v0.2.26) ----

    /**
     * TTL éphémère d'une conversation, en secondes (0 = désactivé — défaut).
     * Valeurs proposées par l'UI : 0 / 3600 (1 h) / 86 400 (24 h) / 604 800
     * (7 j). Le TTL voyage SIGNÉ dans l'événement (wire-format.md — ttl) et
     * la péremption locale est appliquée des DEUX côtés (purge toutes les
     * 30 s + refus des événements déjà expirés à l'arrivée).
     */
    fun ephemeralTtl(peer: String): Long =
        prefs.getLong(KEY_EPHEMERAL_PREFIX + peer.trim().lowercase(), 0L)

    fun setEphemeralTtl(peer: String, seconds: Long) {
        prefs.edit().putLong(KEY_EPHEMERAL_PREFIX + peer.trim().lowercase(), seconds).apply()
    }

    companion object {
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CLOSE = "close_behavior"
        private const val KEY_REPLICA_ENABLED = "replica_enabled"
        private const val KEY_CUSTOM_REPLICAS = "custom_replicas"
        private const val KEY_EPHEMERAL_PREFIX = "eph_ttl_"

        @Volatile private var instance: SettingsManager? = null
        fun get(context: Context): SettingsManager =
            instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
    }
}
