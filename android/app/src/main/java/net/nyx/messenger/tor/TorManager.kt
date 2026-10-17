package net.nyx.messenger.tor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gestion Tor — v0.2 : Tor EMBARQUÉ, sans Orbot (ADR-007).
 *
 * Flux : [start] démarre [NyxTorService] (premier plan) → le service exécute le
 * démon tor avec le torrc généré → ce manager surveille le control port jusqu'au
 * bootstrap complet (progression %) → publication automatique du service onion de
 * l'utilisateur (identité, ADR-003) si un fournisseur de clé a été passé à [start].
 *
 * Invariant de sécurité : AUCUN repli hors Tor. Si Tor échoue, le réseau Nyx est
 * simplement indisponible (offline by design).
 */
class TorManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    // v0.2.10 : idem NyxTorService — les coroutines d'infrastructure (watchBootstrap,
    // publication onion) ne doivent jamais remonter au handler par défaut (FATAL).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                android.util.Log.w("nyx-tor", "coroutine manager terminée : ${e.message}")
            }
    )

    private val _state = MutableStateFlow<TorState>(TorState.Checking)
    val state: StateFlow<TorState> = _state

    private var config: NyxTorConfig? = null
    private var bootstrapJob: Job? = null
    private var onionServiceId: String? = null
    private var keyProvider: (() -> ByteArray?)? = null

    sealed interface TorState {
        /** Avant toute tentative. */
        data object Checking : TorState
        /** Démon lancé, bootstrap en cours ([percent] de 0 à 99). */
        data class Starting(val percent: Int) : TorState
        /** Bootstrap terminé, SOCKS utilisable (onion publié si clé fournie). */
        data object Ready : TorState
        /** Tor absent ou en échec — réseau Nyx indisponible. */
        data class Failed(val reason: String) : TorState
    }

    /** Port SOCKS courant (pour RelayClient/Repository). Voir [activeSocksPort]. */
    val socksPort: Int get() = config?.socksPort ?: DEFAULT_SOCKS_PORT

    /**
     * Démarre Tor embarqué. [onionPrivateKeyProvider] : renvoie la clé privée
     * ed25519 (64 octets) de l'identité si elle existe — le service onion de
     * l'utilisateur est alors publié dès la fin du bootstrap.
     *
     * v0.2.9 — IDEMPOTENT côté manager aussi (retour opérateur : le bouton
     * « Créer mon identité » restait inerte) : [ConnexionsScreen] et
     * [OnboardingScreen] appellent start() à chaque entrée sur l'écran ; quand
     * le démon tourne déjà, l'ancien code réinitialisait l'état à Starting(0)
     * alors que le service ne re-notifie JAMAIS (alreadyRunning) → état
     * coincé, sonde réplica jamais relancée, bouton désactivé pour toujours.
     * Désormais : Ready/Starting en cours = on ne touche à rien (le service
     * re-attache lui-même via [reattachRunning] si besoin).
     */
    fun start(onionPrivateKeyProvider: (() -> ByteArray?)? = null) {
        if (onionPrivateKeyProvider != null) keyProvider = onionPrivateKeyProvider
        when (_state.value) {
            is TorState.Ready, is TorState.Starting -> {
                NyxTorService.start(appContext)  // no-op si le démon tourne déjà
                return
            }
            else -> {}
        }
        setState(TorState.Starting(0))
        NyxTorService.start(appContext)
    }

    /**
     * v0.2.9 — Re-attachement à un démon DÉJÀ EN COURS (service redémarré,
     * écran revisté, retour dans l'app) : on repart de la config connue et on
     * re-surveille le bootstrap — si tor est déjà prêt, la boucle le constate
     * en un poll et repasse Ready. Ne réinitialise JAMAIS un état Ready.
     */
    fun reattachRunning() {
        val cfg = config ?: return
        if (_state.value is TorState.Ready) return
        setState(TorState.Starting(0))
        reportStarted(cfg)
    }

    /** Arrêt demandé (UI) : démon tué via le service ; non bloquant. */
    fun stop() {
        bootstrapJob?.cancel()
        NyxTorService.stop(appContext)
        setState(TorState.Checking)
    }

    /**
     * v0.2.9 — Arrêt COMPLET et BLOQUANT (demande opérateur : « tuer le
     * processus TOR proprement en fermeture de l'application dès la première
     * étape de connexion »). Séquence : annulation de la surveillance → kill
     * direct borné du démon (SIGTERM, attente, SIGKILL — le verrou DataDirectory
     * et les ports sont libérés AVANT le retour) → arrêt du service (son
     * onDestroy ne re-tue plus rien). À appeler HORS thread UI.
     */
    fun stopNow() {
        bootstrapJob?.cancel()
        NyxTorService.stopDaemonSync()
        NyxTorService.stop(appContext)
        setState(TorState.Checking)
    }

    /**
     * Relance propre (v0.2.5, écran Connexions) : stop (tue le démon via
     * onDestroy) puis start (le service relance un démon frais). Un simple
     * « start » ne suffit pas quand le démon tourne encore : NyxTorService
     * ne re-déclenche pas [reportStarted] si le process existe déjà.
     *
     * v0.2.9 : exécuté HORS thread UI avec [stopNow] — l'attente de la mort
     * du démon garantit le relâchement du verrou DataDirectory avant que le
     * nouveau démon ne démarre (sinon il meurt sur « usually controlled by
     * another process » et l'écran passe Failed).
     */
    fun retry() {
        scope.launch {
            stopNow()
            start(keyProvider)
        }
    }

    /** Publie (ou re-publie) le service onion de l'utilisateur. Auto après bootstrap.
     *  v0.2.18 : + port média P2P (appels/fichiers direct) — écouteur démarré
     *  au préalable par ServiceLocator ([net.nyx.messenger.p2p.P2pServer.start]).
     *  v0.2.19 : en build « TEST 2 » (BuildConfig.EMBEDDED_REPLICA), le port 80
     *  de l'onion sert le RÉPLICA TEMPORAIRE ([net.nyx.messenger.replica.EmbeddedReplica],
     *  écouteur démarré au préalable par ServiceLocator) au lieu du port local
     *  inutilisé — un contact ajoute donc « <notre onion>:80 » dans ses réplicas. */
    suspend fun publishOwnOnion(localPort: Int? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = config ?: error("Tor non démarré")
            var target = localPort ?: cfg.onionLocalPort
            if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                net.nyx.messenger.replica.EmbeddedReplica.localPort > 0
            ) {
                target = net.nyx.messenger.replica.EmbeddedReplica.localPort
            }
            val key = keyProvider?.invoke() ?: error("identité absente (onboarding ?)")
            TorControlClient(cfg.controlPort).use { control ->
                control.authenticate(cfg.cookieFile)
                val sid = control.publishOnionEd25519V3(
                    key, target,
                    net.nyx.messenger.p2p.P2pServer.localPort.takeIf { it > 0 } ?: 0
                )
                onionServiceId = sid
                // Cohérence clé ↔ adresse (miroir de l'avertissement du serveur Go) :
                // la clé privée ed25519 contient la clé publique à [32..64). Si tor et
                // notre OnionCodec divergent, c'est un invariant cassé (voir error()).
                val expected = net.nyx.messenger.crypto.OnionCodec.pubKeyToOnion(key.copyOfRange(32, 64))
                val published = "$sid.onion"
                check(published.equals(expected, ignoreCase = true)) {
                    "ServiceID tor ($published) ≠ adresse calculée ($expected)"
                }
                published
            }
        }.onFailure { setState(TorState.Failed("publication onion : ${it.message}")) }
    }

    suspend fun unpublishOwnOnion() {
        val cfg = config ?: return
        val sid = onionServiceId ?: return
        withContext(Dispatchers.IO) {
            TorControlClient(cfg.controlPort).use { it.unpublish(sid) }
        }
        onionServiceId = null
    }

    /* ---------- callbacks du service (invoqués depuis NyxTorService) ---------- */

    fun attachConfig(cfg: NyxTorConfig) {
        config = cfg
        activeSocksPort = cfg.socksPort
        setState(TorState.Starting(0))
    }

    fun reportStarted(cfg: NyxTorConfig) {
        config = cfg
        watchBootstrap()
    }

    fun reportFailure(reason: String) {
        setState(TorState.Failed(reason))
    }

    fun reportStopped() {
        if (_state.value is TorState.Starting || _state.value == TorState.Ready) {
            setState(TorState.Failed("démon tor arrêté"))
        }
    }

    /**
     * v0.2.8 — Mise à jour centralisée de l'état Tor : le StateFlow (écran
     * Connexions) ET la notification de premier plan (statut réel exigé par
     * l'opérateur — « Démarrage de TOR » restait affiché en permanence car
     * personne ne rafraîchissait la notification après startForeground).
     */
    private fun setState(s: TorState) {
        _state.value = s
        val text: String
        val ready: Boolean
        when (s) {
            TorState.Checking -> { text = "Vérification de Tor…"; ready = false }
            is TorState.Starting -> {
                text = if (s.percent in 1..99) "Connexion Tor… ${s.percent} %"
                       else "Démarrage de Tor…"
                ready = false
            }
            TorState.Ready -> { text = "Tor actif — trafic anonymisé"; ready = true }
            is TorState.Failed -> {
                text = "Tor en échec — ${s.reason.take(90)}"
                ready = false
            }
        }
        NyxTorService.pushState(appContext, text, ready)
    }

    /** Surveille le control port jusqu'à la fin du bootstrap, puis publie l'onion. */
    private fun watchBootstrap() {
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch {
            val cfg = config ?: return@launch
            val control = TorControlClient(cfg.controlPort)
            try {
                // 1. Attente du control port + cookie (le démon vient de démarrer).
                var authenticated = false
                for (attempt in 1..BOOTSTRAP_TIMEOUT_S) {
                    if (!isActive) return@launch
                    try {
                        if (!cfg.cookieFile.exists()) { delay(1_000); continue }
                        control.authenticate(cfg.cookieFile)
                        authenticated = true
                        break
                    } catch (e: Exception) {
                        delay(1_000)
                    }
                }
                if (!authenticated) {
                    setState(TorState.Failed("control port Tor injoignable (${cfg.controlPort})"))
                    return@launch
                }

                // 2. Suivi de progression jusqu'au bootstrap complet.
                // v0.2.5 : chaque PALIER de pourcentage est logué (tag nyx-tor) —
                // les « Bootstrapped X% » de tor vont dans tor.log (Log notice
                // file) et NON dans logcat ; sans ce log rien ne dit dans
                // l'export si le démon avance, stagne à 0 % ou est terminé.
                var lastPct = -1
                while (isActive) {
                    val phase = control.bootstrapPhase()
                    TorControlClient.parsePercent(phase)?.let { pct ->
                        if (pct != lastPct) {
                            android.util.Log.i("nyx-tor", "bootstrap tor : $pct%")
                            lastPct = pct
                            // Notification mise à jour sur CHAQUE palier (v0.2.8).
                            setState(TorState.Starting(pct.coerceAtMost(99)))
                        }
                    }
                    if (TorControlClient.isDone(phase)) break
                    delay(POLL_INTERVAL_MS)
                }

                // 3. Tor prêt → publication du service onion de l'utilisateur.
                if (keyProvider != null) {
                    publishOwnOnion()
                        .onSuccess { addr ->
                            android.util.Log.i("nyx-tor", "onion service publié : $addr")
                        }
                        .onFailure { e ->
                            // Ne bloque pas l'état Ready : les réplicas restent joignables,
                            // seule l'adresse P2P de l'utilisateur manque (v0.2 : direct push).
                            android.util.Log.w("nyx-tor", "publication onion différée : ${e.message}")
                        }
                }
                setState(TorState.Ready)
            } catch (e: Exception) {
                setState(TorState.Failed("bootstrap : ${e.message}"))
            } finally {
                control.close()
            }
        }
    }

    /**
     * Fournit la clé d'identité et publie le service onion IMMÉDIATEMENT —
     * v0.2.3 : Tor démarre dès l'onboarding (sans clé, rien à publier), la
     * publication ne peut donc PAS repasser par [start] (le démon étant déjà
     * lancé, NyxTorService ne re-déclenche pas reportStarted et l'état
     * resterait bloqué à Starting). Appelé après création/restauration de
     * l'identité sur un tor déjà Ready.
     */
    fun provideKeyAndPublish(provider: () -> ByteArray?) {
        keyProvider = provider
        scope.launch {
            runCatching { publishOwnOnion() }
                .onSuccess { addr -> android.util.Log.i("nyx-tor", "onion service publié : $addr") }
                .onFailure { e ->
                    android.util.Log.w("nyx-tor", "publication différée : ${e.message}")
                    // La publication a peut-être mis Failed — tor lui-même est prêt.
                    if (_state.value is TorState.Failed) setState(TorState.Ready)
                }
        }
    }

    companion object {
        const val DEFAULT_SOCKS_PORT = 9050
        private const val BOOTSTRAP_TIMEOUT_S = 180
        private const val POLL_INTERVAL_MS = 1_000L

        /**
         * Port SOCKS exposé par le Tor embarqué — lu par RelayClient/Repository
         * (port dynamique choisi au démarrage, cf. NyxTorConfig).
         */
        @Volatile var activeSocksPort: Int = DEFAULT_SOCKS_PORT
            private set

        @Volatile private var instance: TorManager? = null
        fun get(context: Context): TorManager =
            instance ?: synchronized(this) {
                instance ?: TorManager(context.applicationContext).also { instance = it }
            }
    }
}
