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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                net.nyx.messenger.diag.DiagLog.w("nyx-tor", "coroutine manager terminée : ${e.message}")
            }
    )

    private val _state = MutableStateFlow<TorState>(TorState.Checking)
    val state: StateFlow<TorState> = _state

    private var config: NyxTorConfig? = null
    private var bootstrapJob: Job? = null
    private var onionServiceId: String? = null
    private var keyProvider: (() -> ByteArray?)? = null

    /**
     * v0.2.43 — Port média du DERNIER enregistrement réussi de CE processus tor
     * (0 = enregistré sans port média). Sert à l'adoption de collision : si le
     * service existant a été enregistré SANS le port média (écouteur P2P pas
     * encore lié à l'époque) et qu'on en demande un maintenant, il faut un
     * rafraîchissement propre (DEL+pause+ADD) plutôt qu'une adoption aveugle.
     */
    @Volatile private var registeredMediaPort: Int = 0

    /**
     * v0.2.29 — Adresse onion RÉELLEMENT PUBLIÉE (ADD_ONION réussi) ou null.
     *
     * CAUSE RACINE « host unreachable » (terrain v0.2.28) : l'annonce du
     * réplica temporaire partait toutes les 30 min ([ServiceLocator]) même
     * quand la publication onion avait ÉCHOUÉ (« publication onion différée »
     * — sans AUCUNE relance, la publication ne repartait qu'au prochain
     * démarrage complet de l'app) → les contacts découvraient un réplica
     * FANTÔME (aucun descripteur dans les HSDir → « Host unreachable » côté
     * pair), et les appels entrants mouraient pareil (port 47471 du même
     * descripteur). Les consommateurs DOIVENT vérifier ce champ :
     *  - l'annonce du réplica temporaire (ServiceLocator) est sautée si null ;
     *  - le watchdog [publicationWatchdog] REPUBLIE tant que ce champ est
     *    null alors que tor est Ready (fin de l'échec à vie) ;
     *  - v0.2.43 : le watchdog de sondes [probeWatchdog] ne déclenche PLUS de
     *    republication forcée (la sonde de notre propre onion est INDICATIVE :
     *    le cache client peut servir notre ANCIEN descripteur jusqu'à 24 h —
     *    terrain 22-23/10 + expérimentation tor, session 64).
     */
    @Volatile var onionPublishedAddress: String? = null
        private set

    /** Boucle « republier tant que non publié » (un seul à la fois). */
    private var publicationWatchdog: Job? = null
    /** Boucle de sondes périodiques de NOTRE onion publiée (un seul à la fois). */
    private var probeWatchdog: Job? = null

    /** v0.2.45 — chien de garde des backends locaux (réplica + P2P, sonde loopback 60 s). */
    private var backendWatchdog: Job? = null
    /** Échecs consécutifs des sondes (seuil → alerte journalisée, PAS de republication). */
    @Volatile private var probeFails = 0

    /**
     * v0.2.34 — Sérialisation publish/unpublish (terrain 22/10 02:52:41 :
     * trois connexions contrôle ouvertes LA MÊME SECONDE, deux ADD_ONION avec
     * la même clé en concurrence → tor 551 « Onion Service private key
     * collides with an existing v3 service » + tor_bug_occurred_). Le
     * publicationWatchdog, le probeWatchdog et l'annonce périodique peuvent
     * tous vouloir republier en même temps : désormais UN SEUL à la fois.
     */
    private val onionMutex = Mutex()

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
     *  v0.2.19 → v0.2.35 : depuis la v0.2.35 (fusion des versions), le port 80
     *  de l'onion sert le RÉPLICA TEMPORAIRE dans TOUTES les versions
     *  ([net.nyx.messenger.replica.EmbeddedReplica],
     *  écouteur démarré au préalable par ServiceLocator) au lieu du port local
     *  inutilisé — un contact ajoute donc « <notre onion>:80 » dans ses réplicas. */
    suspend fun publishOwnOnion(localPort: Int? = null): Result<String> = onionMutex.withLock {
        publishOwnOnionLocked(localPort)
    }

    private suspend fun publishOwnOnionLocked(localPort: Int?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            onionPublishedAddress = null // (re)publication — l'ancienne n'est plus garantie
            val cfg = config ?: error("Tor non démarré")
            var target = localPort ?: cfg.onionLocalPort
            if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                net.nyx.messenger.replica.EmbeddedReplica.localPort > 0
            ) {
                target = net.nyx.messenger.replica.EmbeddedReplica.localPort
            }
            // v0.2.28 — ATTENTE DU PORT MÉDIA (race de démarrage) : si l'écouteur
            // P2P n'est pas encore lié au moment de l'ADD_ONION, le descripteur
            // publié N'AURAIT PAS le port 47471 → tous les appels/fichiers
            // directs échoueraient (« Connect timed out » côté pair) jusqu'à la
            // prochaine republication. On attend gracieusement (≤ 8 s) que
            // P2pServer expose son port local avant de publier.
            var mediaPort = net.nyx.messenger.p2p.P2pServer.localPort
            if (mediaPort == 0) {
                var waited = 0
                while (mediaPort == 0 && waited < 8_000) {
                    delay(500)
                    waited += 500
                    mediaPort = net.nyx.messenger.p2p.P2pServer.localPort
                }
            }
            val key = keyProvider?.invoke() ?: error("identité absente (onboarding ?)")
            // Cohérence clé ↔ adresse (miroir de l'avertissement du serveur Go) :
            // la clé privée ed25519 contient la clé publique à [32..64). Si tor et
            // notre OnionCodec divergent, c'est un invariant cassé (voir error()).
            val expected = net.nyx.messenger.crypto.OnionCodec.pubKeyToOnion(key.copyOfRange(32, 64))
            val expectedSid = expected.removeSuffix(".onion")
            TorControlClient(cfg.controlPort).use { control ->
                control.authenticate(cfg.cookieFile)
                // v0.2.43 — COLLISION 550 = SERVICE DÉJÀ VIVANT → ADOPTION, pas échec.
                // CAUSE RACINE session 64 (terrain 22-23/10 + EXPÉRIMENTATION tor réelle,
                // scripts tor_repro_*) : ADD_ONION Flags=Detach survit à la fermeture de
                // la connexion contrôle et CONTINUE d'établir ses points d'intro et
                // d'uploader son descripteur (47 × « 200 OK HS descriptor stored » en
                // laboratoire). Une collision (« erreur Tor 550 — Onion address
                // collision ») signifie donc « déjà publié dans CE démon tor », PAS un
                // échec. Le terrain v0.2.42 la traitait en échec : le watchdog
                // republicait toutes les 30 s → collision à chaque fois → état
                // « onion NON publiée » FANTÔME (le service servait réellement) +
                // assertion interne tor (hs_service_add_ephemeral) à chaque tentative.
                val sid = try {
                    control.publishOnionEd25519V3(key, target, mediaPort)
                } catch (e: IllegalStateException) {
                    val msg = e.message ?: ""
                    if (!msg.contains("collision")) throw e
                    if (mediaPort > 0 && registeredMediaPort != mediaPort) {
                        // L'enregistrement existant (fait SANS port média — écouteur
                        // P2P non lié à l'époque) est INCOMPLET : rafraîchissement
                        // propre DEL_ONION + pause + ADD (le service nous appartient,
                        // pas de course possible : onionMutex sérialise tout).
                        net.nyx.messenger.diag.DiagLog.w(
                            "nyx-tor", "collision 550 : service existant SANS port média — " +
                                "rafraîchissement DEL+ADD (ports=80→$target, média→$mediaPort)"
                        )
                        control.unpublish(expectedSid)
                        delay(REPUBLISH_PAUSE_MS) // anti-collision 550 (v0.2.37)
                        control.publishOnionEd25519V3(key, target, mediaPort)
                    } else {
                        // Service déjà enregistré AVEC les bons ports : on l'ADOPTTE —
                        // il est vivant, ses uploads suivent leur cours ; le traiter
                        // en échec était la boucle sans fin du terrain.
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-tor", "collision 550 : service déjà vivant (Detach) — ADOPTION " +
                                "de $expected (les uploads tor continuent)"
                        )
                        expectedSid
                    }
                }
                onionServiceId = sid
                val published = "$sid.onion"
                check(published.equals(expected, ignoreCase = true)) {
                    "ServiceID tor ($published) ≠ adresse calculée ($expected)"
                }
                // v0.2.28 — trace de diagnostic décisive : le descripteur publié
                // porte-t-il le port média ? (le pair qui appelle ne peut joindre
                // que ce qui est DANS le descripteur HSDir.)
                if (mediaPort > 0) {
                    net.nyx.messenger.diag.DiagLog.i(
                        "nyx-tor", "onion publié : $published ports=80→$target, " +
                            "${net.nyx.messenger.p2p.P2pProtocol.MEDIA_ONION_PORT}→$mediaPort"
                    )
                    // SONDE AUTO (v0.2.28) : 15 s après publication (délai de
                    // propagation HSDir), l'app appelle SON PROPRE port média —
                    // échec = descripteur non propagé / port absent → la cause
                    // « impossible de décrocher » est visible DANS le log.
                    scope.launch {
                        delay(15_000)
                        selfTestMediaPort(published)
                    }
                } else {
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-tor", "onion publié SANS port média (écouteur P2P non lié) — " +
                            "appels directs indisponibles jusqu'à republication"
                    )
                }
                // v0.2.29 — l'adresse est PUBLIÉE : les annonces de réplica et
                // le watchdog s'appuient désormais sur un état RÉEL (plus rien
                // de « supposé ») — cf. [onionPublishedAddress].
                registeredMediaPort = mediaPort
                onionPublishedAddress = published
                startProbeWatchdog(published)
                published
            }
        }.onFailure { setState(TorState.Failed("publication onion : ${it.message}")) }
    }

    /**
     * v0.2.28 — sonde auto du port média : connexion à notre PROPRE onion sur
     * MEDIA_ONION_PORT via le SOCKS local (chemin onion complet, comme un
     * pair). OK = descripteur propagé + port servi ; échec = diagnostic
     * « impossible de décrocher » enregistré dans le log terrain.
     *
     * v0.2.37 — l'échec à +15 s est PRÉVISIBLE quand l'app vient de
     * redémarrer : le nouveau descripteur (nouveaux points d'intro) met
     * 30-60 s à atteindre les 8 HSDirs — la sonde lit l'ANCIEN descripteur.
     * Depuis P2pClient.connect (récupération HSFETCH intégrée), un échec
     * déclenche automatiquement HSFETCH + une 2e tentative ; le message
     * final n'annonce plus « appels cassés » à tort — il explique le délai
     * de propagation réel.
     */
    private fun selfTestMediaPort(ownOnion: String) {
        runCatching {
            val s = net.nyx.messenger.p2p.P2pClient.connect(ownOnion, 20_000)
            runCatching { s.close() }
        }.fold(
            onSuccess = {
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-p2p", "auto-sonde média OK : ${ownOnion.take(16)}…:" +
                        "${net.nyx.messenger.p2p.P2pProtocol.MEDIA_ONION_PORT} joignable (descripteur publié)"
                )
            },
            onFailure = { e ->
                net.nyx.messenger.diag.DiagLog.w(
                    "nyx-p2p", "auto-sonde média ÉCHOUÉE : ${ownOnion.take(16)}…:" +
                        "${net.nyx.messenger.p2p.P2pProtocol.MEDIA_ONION_PORT} (${e.message}) " +
                        "— descripteur pas encore propagé aux HSDirs (normal ~1 min après un " +
                        "redémarrage de l'app) ou port média absent ; HSFETCH déjà tenté par la récupération"
                )
            }
        )
    }

    /**
     * v0.2.29 — Boucle « REPUBLIER tant que non publié » : tor Ready + clé
     * d'identité présente + [onionPublishedAddress] null = le téléphone est
     * INJOIGNABLE en direct (pas de réplica temporaire, pas d'appels entrants)
     * alors qu'il croit être en ligne. Toutes les [PUBLICATION_RETRY_MS], un
     * essai de publication. Terrain v0.2.28 : un seul échec « publication
     * onion différée » au démarrage laissait l'appareil fantôme JUSQU'AU
     * prochain redémarrage complet — pendant ce temps il ANNONCAIT quand
     * même son réplica (cause « host unreachable » chez les contacts).
     */
    private fun startPublicationWatchdog() {
        if (publicationWatchdog?.isActive == true) return
        publicationWatchdog = scope.launch {
            while (isActive) {
                delay(PUBLICATION_RETRY_MS)
                val st = _state.value
                if (st is TorState.Ready && keyProvider != null && onionPublishedAddress == null) {
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-tor",
                        "watchdog : tor Ready mais onion NON publiée — republication"
                    )
                    // v0.2.43 — PLUS de runCatching autour (miroir du correctif
                    // probeWatchdog) : publishOwnOnion renvoie un Result, l'envelopper
                    // d'un second rendait onFailure INATTEIGNABLE — « onion service
                    // publié : Failure(…550 collision) » était journalisé via la
                    // branche SUCCÈS (terrain 23/10 09:12:56) et l'adoption de
                    // collision ne pouvait jamais opérer.
                    publishOwnOnion()
                        .onSuccess { net.nyx.messenger.diag.DiagLog.i("nyx-tor", "onion service publié : $it") }
                        .onFailure { net.nyx.messenger.diag.DiagLog.w("nyx-tor", "publication onion différée : ${it.message}") }
                }
            }
        }
    }

    /**
     * v0.2.29 — Sondes périodiques de NOTRE onion publiée (chemin onion
     * COMPLET via notre propre SOCKS, comme un pair) :
     *  - port média 47471 (appels/fichiers P2P) si l'écouteur P2P est lié ;
     *  - port 80 (réplica temporaire embarqué — GET /health) si servi.
     *
     * v0.2.37 — LECTURE CORRIGÉE DES ÉCHECS (terrain v0.2.36 + source tor
     * 0.4.9.14) : une sonde qui échoue avec « SOCKS server general failure »
     * signifie presque toujours que le HSDir consulté sert ENCORE l'ANCIEN
     * descripteur (points d'introduction morts — NOTRE app vient de redémarrer)
     * ou que NOTRE cache client garde ce descripteur périmé (jusqu'à 24 h !).
     * Ce n'est PAS une panne de notre service. La bonne réaction est
     * HSFETCH (re-demande du descripteur en contournant le cache) puis
     * re-sonder — la RÉPUBLICATION FORCÉE (DEL+ADD) est désormais un DERNIER
     * RECOURS [PROBE_FAILS_BEFORE_REPUBLISH] : elle tue les points d'intro
     * VIVANTS et ouvre une NOUVELLE fenêtre de descripteur périmé (30-60 s)
     * pour tous les pairs — terrain 12:41:52 : elle a même rencontré la
     * collision 550 (tor_bug_occurred_ + assertion hs_service_add_ephemeral).
     * Chaque résultat est journalisé (évidence terrain nominative).
     */
    private fun startProbeWatchdog(published: String) {
        probeWatchdog?.cancel() // une seule boucle — la republication relance la nouvelle
        probeFails = 0
        probeWatchdog = scope.launch {
            delay(PROBE_FIRST_DELAY_MS) // laisse la 1re propagation HSDir (+ sonde 15 s v0.2.28)
            while (isActive) {
                if (_state.value !is TorState.Ready) {
                    probeFails = 0 // démon absent/redémarré — pas une panne du service
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                val probe = probeSelf(published)
                if (probe.total == 0) {
                    // Rien à sonder (build standard, ni média ni réplica servi).
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                if (probe.failed == 0) {
                    if (probeFails > 0) {
                        net.nyx.messenger.diag.DiagLog.i("nyx-tor", "auto-sonde : $published de nouveau joignable")
                    }
                    probeFails = 0
                } else {
                    // v0.2.37/v0.2.43 — HSFETCH D'ABORD (la cause n°1 reste le
                    // descripteur périmé : cache client jusqu'à 24 h, HSDirs en
                    // cours de propagation). Classification v0.2.45 — CORRIGÉE
                    // par la preuve terrain 23/10 + rejeu sandbox
                    // (scripts/tor_deadbackend.py) :
                    //  - « SOCKS server general failure » = le rendezvous Tor a
                    //    ABOUTI mais le flux s'est fait REFUSER au DERNIER
                    //    MAILLON — le plus souvent un descripteur ORPHELIN côté
                    //    HSDir (nœud distant redémarré sans Detach, ex.
                    //    déploiement) OU un backend local mort ; la variante
                    //    « cache client périmé » reste possible ;
                    //  - « Host unreachable » = pas de descripteur servi par les
                    //    HSDirs (propagation en cours, ou réseau mobile hostile).
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-tor",
                        "auto-sonde ÉCHOUÉE (${probe.failed}/${probe.total}) : $published — " +
                            "(${probe.detail}) — HSFETCH puis nouvelle sonde"
                    )
                    forceDescriptorRefresh(published)
                    delay(PROBE_HSFETCH_SETTLE_MS)
                    val reprobe = probeSelf(published)
                    if (reprobe.failed == 0) {
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-tor", "auto-sonde : $published joignable après HSFETCH " +
                                "(descripteur périmé confirmé — service sain)"
                        )
                        probeFails = 0
                        delay(PROBE_INTERVAL_MS)
                        continue
                    }
                    probeFails++
                    // v0.2.45 — VERDICT LOCAL (cause racine terrain 23/10) : la sonde
                    // externe échoue → nos backends LOCAUX acceptent-ils encore les
                    // connexions ? Un échec externe + backend local vivant = réseau/
                    // descripteur ; un backend local mort = le service Tor est sain
                    // mais les mappings pointent vers un port mort (relancé par le
                    // chien de garde startBackendWatchdog dans les 60 s).
                    runCatching {
                        val replicaAlive = net.nyx.messenger.replica.EmbeddedReplica.alive()
                        val p2pAlive = net.nyx.messenger.p2p.P2pServer.alive()
                        net.nyx.messenger.diag.DiagLog.i(
                            "nyx-tor", "verdict auto-sonde : backends locaux " +
                                "(réplica=${if (replicaAlive) "vivant" else "MORT"}, " +
                                "p2p=${if (p2pAlive) "vivant" else "MORT"}) — " +
                                if (replicaAlive || p2pAlive) "échec réseau/descripteur, service local OK"
                                else "backends locaux morts — relance en cours (chien de garde)"
                        )
                    }
                    net.nyx.messenger.diag.DiagLog.w(
                        "nyx-tor",
                        "auto-sonde ÉCHOUÉE après HSFETCH — tentative $probeFails/" +
                            "$PROBE_FAILS_BEFORE_ALERT (${reprobe.detail})"
                    )
                    if (probeFails >= PROBE_FAILS_BEFORE_ALERT) {
                        probeFails = 0
                        // v0.2.43 — PLUS DE RÉPUBLICATION FORCÉE. Terrain 22-23/10 +
                        // expérimentation (tor_repro_*) : le DEL_ONION+ADD_ONION du
                        // dernier recours TUAIT des points d'introduction VIVANTS,
                        // rouvrait une fenêtre de descripteur périmé pour TOUS les
                        // pairs, et courait contre le publicationWatchdog (collision
                        // 550 en boucle + assertion tor). tor re-téléverse
                        // périodiquement ses descripteurs et ré-établir ses points
                        // d'intro TOUT SEUL — l'app n'a rien à casser ici. Les canaux
                        // SORTANTS (réplication, appels sortants, annonces) restent
                        // fonctionnels même quand l'onion ENTRANT est injoignable.
                        net.nyx.messenger.diag.DiagLog.w(
                            "nyx-tor", "ONION INJOIGNABLE DEPUIS $PROBE_FAILS_BEFORE_ALERT " +
                                "salves malgré HSFETCH : $published (${reprobe.detail}). " +
                                "AUCUNE republication forcée (v0.2.43 : elle cassait plus " +
                                "qu'elle ne réparait — collisions 550 + churn de descripteurs). " +
                                "tor poursuit ses uploads en autonomie ; sonde indicative seulement."
                        )
                    }
                }
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /** Résultat d'une salve de sondes. */
    private data class ProbeResult(val total: Int, val failed: Int, val detail: String)

    /**
     * v0.2.45 — CHIEN DE GARDE DES BACKENDS LOCAUX. CAUSE RACINE terrain 23/10
     * (prouvée par rejeu tor sandbox, scripts/tor_deadbackend.py) : les sockets
     * du réplica temporaire (port 80) et de l'écouteur P2P (port 47471) peuvent
     * mourir SILENCIEUSEMENT — le service onion, lui, reste parfaitement sain
     * (INTRODUCE2 reçus, rendezvous ouverts) et sert des mappings vers des
     * ports locaux MORTS : chaque flux entrant reçoit « Connection refused » →
     * END misc → « SOCKS server general failure » chez TOUS les pairs, et ce
     * diagnostic a été CONFONDU avec « points d'introduction morts » pendant
     * plusieurs sessions. La vérification est une simple connexion TCP sur
     * 127.0.0.1 (le seul verdict honnête) ; en cas de mort : relance de
     * l'écouteur (nouveau port) PUIS republication — le rafraîchissement
     * collision-550 (v0.2.43) met à jour les mappings si le port a changé.
     */
    private fun startBackendWatchdog() {
        backendWatchdog?.cancel()
        backendWatchdog = scope.launch {
            delay(BACKEND_PROBE_FIRST_MS)
            while (isActive) {
                if (_state.value is TorState.Ready && onionPublishedAddress != null) {
                    var healed = false
                    runCatching {
                        val replicaPort = net.nyx.messenger.replica.EmbeddedReplica.localPort
                        if (replicaPort > 0 &&
                            net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                            !net.nyx.messenger.replica.EmbeddedReplica.alive()
                        ) {
                            net.nyx.messenger.replica.EmbeddedReplica.restartIfDead()
                            healed = true
                        }
                        if (net.nyx.messenger.p2p.P2pServer.localPort > 0 &&
                            !net.nyx.messenger.p2p.P2pServer.alive()
                        ) {
                            net.nyx.messenger.p2p.P2pServer.restartIfDead()
                            healed = true
                        }
                    }
                    if (healed) {
                        net.nyx.messenger.diag.DiagLog.w(
                            "nyx-tor",
                            "backend local relancé (réplica=${net.nyx.messenger.replica.EmbeddedReplica.localPort}, " +
                                "p2p=${net.nyx.messenger.p2p.P2pServer.localPort}) — republication onion " +
                                "pour recâbler les mappings"
                        )
                        runCatching { publishOwnOnion() }
                    }
                }
                delay(BACKEND_PROBE_INTERVAL_MS)
            }
        }
    }

    /** Sonde notre propre onion : port média (P2P) + port 80 (réplica embarqué). */
    private suspend fun probeSelf(published: String): ProbeResult {
        var total = 0
        var failed = 0
        val details = ArrayList<String>(2)
        // 1. Port média (appels/fichiers P2P) — connexion TCP pure comme un pair.
        if (net.nyx.messenger.p2p.P2pServer.localPort > 0) {
            total++
            val r = runCatching {
                val s = net.nyx.messenger.p2p.P2pClient.connect(published, 20_000)
                runCatching { s.close() }
            }
            if (r.isFailure) {
                failed++
                details.add("média: ${r.exceptionOrNull()?.message?.take(80)}")
            }
        }
        // 2. Port 80 — réplica temporaire embarqué (GET /health, la même route
        //    que le relais : RelayClient parle à l'un comme à l'autre).
        if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
            net.nyx.messenger.replica.EmbeddedReplica.serving &&
            net.nyx.messenger.replica.EmbeddedReplica.localPort > 0
        ) {
            total++
            val r = runCatching {
                net.nyx.messenger.net.RelayClient
                    .forReplica("$published:80", socksPort = activeSocksPort)
                    .healthDetailed()
            }
            val ok = r.getOrNull()?.ok == true
            if (!ok) {
                failed++
                details.add(
                    "réplica: " + (r.exceptionOrNull()?.message
                        ?: r.getOrNull()?.detail ?: "échec")?.take(80)
                )
            }
        }
        return ProbeResult(total, failed, details.joinToString("; "))
    }

    suspend fun unpublishOwnOnion() = onionMutex.withLock { unpublishOwnOnionLocked() }

    private suspend fun unpublishOwnOnionLocked() {
        val cfg = config ?: return
        val sid = onionServiceId ?: return
        withContext(Dispatchers.IO) {
            TorControlClient(cfg.controlPort).use { it.unpublish(sid) }
        }
        onionServiceId = null
        onionPublishedAddress = null
    }

    /**
     * v0.2.37 — RÉCUPÉRATION DESCRIPTEUR : HSFETCH force tor à re-demander le
     * descripteur du service [onion] (56 caractères, sans suffixe) aux HSDirs
     * responsables, en contournant le cache client (voir TorControlClient.hsfetch
     * pour la cause racine terrain : descripteur périmé à points d'introduction
     * morts après un redémarrage de l'app du pair → « SOCKS server general
     * failure » jusqu'à 24 h sans ça).
     *
     * Bloquant ~100 ms (une commande contrôle) — appelé depuis un thread IO.
     * Jamais d'exception : Result, l'appelant décide (une récupération ratée
     * n'est jamais fatale, le prochain cycle naturel de tor re-essaiera).
     */
    fun forceDescriptorRefresh(onion: String): Result<Unit> = runCatching {
        val cfg = config ?: error("tor non démarré")
        // v0.2.48 — ROBUSTESSE AUX ADRESSES DE RÉPLICA (terrain 24/10 06:39 :
        // « HSFETCH kxcwahchrt… échoué : adresse onion invalide (65 ≠ 56) » —
        // l'appelant (guérison DescriptorHeal du repo) passait l'adresse
        // COMPLÈTE « <56>.onion:80 » (65 car.) : le suffixe .onion n'était PAS
        // en fin de chaîne, removeSuffix ne retirait rien, le require tuait la
        // guérison À CHAQUE FOIS → les descripteurs périmés restaient en
        // cache ~24 h. Strip du port AVANT le suffixe — normalisation unique
        // déléguée à [net.nyx.messenger.net.LinkFallback.hostKey], testée JVM.
        val host = net.nyx.messenger.repo.LinkFallback.hostKey(onion)
        require(host.length == 56) { "adresse onion invalide (${host.length} ≠ 56)" }
        TorControlClient(cfg.controlPort).use { control ->
            control.authenticate(cfg.cookieFile)
            control.hsfetch(host)
        }
        net.nyx.messenger.diag.DiagLog.i(
            "nyx-tor", "HSFETCH ${host.take(10)}… — descripteur re-demandé aux HSDirs " +
                "(cache client contourné)"
        )
    }.onFailure { e ->
        net.nyx.messenger.diag.DiagLog.w(
            "nyx-tor", "HSFETCH ${onion.take(10)}… échoué : ${e.message?.take(100)}"
        )
    }

    /* ---------- callbacks du service (invoqués depuis NyxTorService) ---------- */

    fun attachConfig(cfg: NyxTorConfig) {
        config = cfg
        activeSocksPort = cfg.socksPort
        // v0.2.29 : NOUVEAU démon — le service onion éphémère (ADD_ONION) est
        // mort avec l'ancien processus ; l'adresse publiée n'est plus valable
        // jusqu'à la re-publication (watchBootstrap va la refaire).
        onionPublishedAddress = null
        setState(TorState.Starting(0))
    }

    fun reportStarted(cfg: NyxTorConfig) {
        config = cfg
        watchBootstrap()
        // v0.2.29 : boucle « republier tant que non publié » — un échec de
        // publication au bootstrap (« publication onion différée ») n'est plus
        // définitif : réessai toutes les 30 s tant que tor est Ready.
        startPublicationWatchdog()
        // v0.2.45 : chiens de garde des BACKENDS LOCAUX (réplica temporaire +
        // écouteur P2P). Terrain 23/10 (prouvé au sandbox) : ces sockets peuvent
        // mourir SILENCIEUSEMENT alors que tor et le process vivent — l'onion
        // reste publié avec des mappings vers des ports MORTS, tous les flux
        // entrants reçoivent « Connection refused »/END misc et les pairs voient
        // « SOCKS server general failure ». La sonde est LOCALE (2 connexions
        // loopback) : coût nul, fréquence 60 s.
        startBackendWatchdog()
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
                            net.nyx.messenger.diag.DiagLog.i("nyx-tor", "onion service publié : $addr")
                        }
                        .onFailure { e ->
                            // Ne bloque pas l'état Ready : les réplicas restent joignables,
                            // seule l'adresse P2P de l'utilisateur manque (v0.2 : direct push).
                            net.nyx.messenger.diag.DiagLog.w("nyx-tor", "publication onion différée : ${e.message}")
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
                .onSuccess { addr -> net.nyx.messenger.diag.DiagLog.i("nyx-tor", "onion service publié : $addr") }
                .onFailure { e ->
                    net.nyx.messenger.diag.DiagLog.w("nyx-tor", "publication différée : ${e.message}")
                    // La publication a peut-être mis Failed — tor lui-même est prêt.
                    if (_state.value is TorState.Failed) setState(TorState.Ready)
                }
        }
    }

    companion object {
        const val DEFAULT_SOCKS_PORT = 9050
        private const val BOOTSTRAP_TIMEOUT_S = 180
        private const val POLL_INTERVAL_MS = 1_000L

        // v0.2.29 — watchdogs de publication : réessai de publication toutes les
        // 30 s tant que l'onion n'est pas publiée ; sondes de notre propre
        // onion 2 min après publication puis toutes les 10 min — depuis la
        // v0.2.37, un échec déclenche d'abord HSFETCH (descripteur périmé).
        // v0.2.43 — la sonde est désormais INDICATIVE : 4 salves persistantes
        // (~40 min) produisent une ALERTE JOURNALISÉE (plus aucune republication
        // forcée — terrain 22-23/10 : elle tuait des points d'intro vivants et
        // déclenchait des collisions 550 en boucle avec le publicationWatchdog).
        private const val PUBLICATION_RETRY_MS = 30_000L
        private const val PROBE_FIRST_DELAY_MS = 2 * 60_000L
        private const val PROBE_INTERVAL_MS = 10 * 60_000L
        private const val PROBE_FAILS_BEFORE_ALERT = 4
        // v0.2.37 — délai de guérison après HSFETCH (le fetch + le stockage du
        // descripteur + la selection d'un point d'intro frais prennent ~10-15 s).
        private const val PROBE_HSFETCH_SETTLE_MS = 15_000L

        /** v0.2.45 — chien de garde backends : 1re sonde à 45 s, puis 60 s (loopback, coût nul). */
        private const val BACKEND_PROBE_FIRST_MS = 45_000L
        private const val BACKEND_PROBE_INTERVAL_MS = 60_000L
        // v0.2.37 — pause DEL_ONION → ADD_ONION : le terrain v0.2.36 (12:41:52)
        // a montré la collision 550 quand les deux commandes s'enchaînent en
        // <100 ms (tor n'a pas fini de libérer le service : assertion non
        // fatale hs_service_add_ephemeral).
        private const val REPUBLISH_PAUSE_MS = 750L

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
