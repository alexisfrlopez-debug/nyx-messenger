package net.nyx.messenger.tor

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Gestion Tor — v0.1 : s'appuie sur Orbot (démon externe, SOCKS5 local 9050).
 * v0.2 : IPtProxy embarqué (Tor natif dans l'app, sans dépendance externe — ADR-004).
 *
 * Invariant de sécurité : AUCUN repli hors Tor. Si Tor est absent, le réseau est
 * simplement indisponible (offline by design).
 */
class TorManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<TorState>(TorState.Checking)
    val state: StateFlow<TorState> = _state

    sealed interface TorState {
        data object Checking : TorState
        data object Ready : TorState
        data object Offline : TorState // Orbot absent ou non démarré
    }

    /** Proxy SOCKS5 fourni par Orbot (127.0.0.1:9050). */
    fun socksProxy(): Proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress("127.0.0.1", SOCKS_PORT)
    )

    /** Vérifie qu'Orbot est installé (le test de joignabilité réel est fait par RelayClient). */
    fun refreshState() {
        val installed = try {
            appContext.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        _state.value = if (installed) TorState.Ready else TorState.Offline
        // NOTE v0.2 : vérification active (bootstrap status via control port / SOCKS probe)
    }

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val SOCKS_PORT = 9050

        @Volatile private var instance: TorManager? = null
        fun get(context: Context): TorManager =
            instance ?: synchronized(this) {
                instance ?: TorManager(context.applicationContext).also { instance = it }
            }
    }
}
