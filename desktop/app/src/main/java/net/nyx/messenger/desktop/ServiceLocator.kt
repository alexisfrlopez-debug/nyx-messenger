package net.nyx.messenger.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.RecoveryKey
import net.nyx.messenger.net.BootstrapReplicas
import net.nyx.messenger.net.RendezvousList
import net.nyx.messenger.net.RendezvousOnce
import net.nyx.messenger.net.RelayClient
import net.nyx.messenger.net.ReplicaRegistry
import net.nyx.messenger.replica.EmbeddedReplica
import net.nyx.messenger.repo.DesktopRepository
import net.nyx.messenger.security.DesktopVault
import net.nyx.messenger.update.DesktopUpdater
import net.nyx.messenger.notify.DesktopNotifier
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Assembleur applicatif desktop — équivalent du ServiceLocator Android :
 * wiring identité/magasin/Tor/réplica/repository + BOUCLE DE POLL au niveau
 * APPLICATION (30 s — invariant v0.2.10 Android : un appareil sans conversation
 * ouverte reçoit quand même ses événements) + annonces de réplica (30 min).
 */
object ServiceLocator {

    const val VERSION = "0.3.10"

    /** v0.3.4 — mode de session (miroir SessionMode Android v0.2.18) :
     * PANIC = session LEURRE (données vides dans un répertoire séparé,
     * boucles réseau coupées). */
    enum class SessionMode { NORMAL, PANIC }

    @Volatile var sessionMode: SessionMode = SessionMode.NORMAL
        private set

    lateinit var identity: DesktopIdentity
        private set
    lateinit var store: DesktopStore
        private set
    lateinit var repository: DesktopRepository
        private set
    lateinit var tor: DesktopTor
        private set
    lateinit var settings: DesktopSettings
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- état vivant pour l'UI ----------------

    enum class TorPhase { OFF, STARTING, READY, FAILED }

    data class TorState(
        val phase: TorPhase = TorPhase.OFF,
        val percent: Int? = null,
        val onion: String? = null,
        val message: String? = null,
    )

    private val _torState = MutableStateFlow(TorState())
    val torState: StateFlow<TorState> = _torState.asStateFlow()

    private val _tick = MutableStateFlow(0L)      // incrémenté à chaque cycle de poll (rafraîchit l'UI)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val _lastPollNew = MutableStateFlow(0)
    val lastPollNew: StateFlow<Int> = _lastPollNew.asStateFlow()

    /**
     * v0.3.4 — état de la SONDE RÉPLICAS (miroir `repo.lastProbe` Android,
     * écran Connexions) : alimente l'écran de progression au démarrage et le
     * panneau Connexions. null = pas encore sondé (Tor en bootstrap).
     */
    data class ReplicaProbeState(
        val ok: Boolean? = null,
        val okCount: Int = 0,
        val total: Int = 0,
        val detail: String? = null,
        val at: Long = 0L,
    )

    private val _replicaProbe = MutableStateFlow(ReplicaProbeState())
    val replicaProbe: StateFlow<ReplicaProbeState> = _replicaProbe.asStateFlow()

    /** v0.3.4 — verrou applicatif (miroir AppLock Android) : true = écran de
     * verrouillage affiché (mot de passe NORMAL requis pour rouvrir). */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun lock() { _locked.value = true }
    fun unlock() { _locked.value = false }

    /** v0.3.4 — thème RÉACTIF (miroir Android AppCompatDelegate.setDefaultNightMode
     * qui applique à chaud) : la fenêtre suit ce flux, plus besoin de redémarrer. */
    private val _themeSetting = MutableStateFlow("nuit")
    val themeSetting: StateFlow<String> = _themeSetting.asStateFlow()

    fun setTheme(setting: String) {
        settings.update { it.copy(theme = setting) }
        _themeSetting.value = setting
    }

    /** v0.3.3 — un seul démarrage de backend à la fois (retry inclus). */
    private val starting = AtomicBoolean(false)

    /** v0.3.3 — boucles de poll/annonce lancées ? (idempotence de startBackend). */
    private val loopsRunning = AtomicBoolean(false)

    /** v0.3.4 — génération des boucles : une bascule de session arrête les
     * boucles vivantes (elles captivent la génération au départ et sortent
     * dès qu'elle change — sinon elles polleraient le magasin LEURRE). */
    @Volatile private var loopGeneration = 0

    fun init() {
        // v0.3.4 — session LEURRE en attente ? (miroir consumePendingMode Android :
        // le mode est consommé UNE fois — la session leurre vit dans un
        // répertoire séparé, la session réelle reste INTACTE au disque.)
        if (DesktopVault.consumePendingMode() == DesktopVault.Mode.PANIC) {
            enterDecoyPaths()
            sessionMode = SessionMode.PANIC
            DesktopLog.i("nyx-boot", "SESSION LEURRE (mode panic) — données réelles intactes au disque")
        }
        DesktopPaths.ensure()
        // v0.3.3 — anneau de diagnostic PERSISTANT (miroir DiagLog v0.2.33) :
        // les échecs de DÉMARRAGE survivent au redémarrage de l'app.
        DesktopLog.initFile(File(DesktopPaths.dataDir, "logs/nyx-desktop.log"))
        // v0.3.4 — plan de tests de validation (portage miroir diag/TestPlan.kt
        // Android v0.2.20 : carte plan de tests du build test).
        net.nyx.messenger.diag.TestPlan.init(DesktopPaths.dataDir)
        identity = DesktopIdentity(File(DesktopPaths.dataDir, "identity.json"))
        store = DesktopStore().apply { ensureLoaded() }
        settings = DesktopSettings(File(DesktopPaths.dataDir, "settings.json"))
        _themeSetting.value = settings.get().theme
        // v0.3.4 — MIGRATION : l'ancien réplica personnalisé UNIQUE (v0.3.3,
        // champ settings.customReplica) rejoint la LISTE customReplicas (la
        // nouvelle UI « Répliques personnalisés » n'écrit plus que la liste) —
        // sans ceci, un terrain v0.3.3 qui avait collé un onion le PERDAIT.
        runCatching {
            val legacy = settings.get().customReplica.trim().lowercase()
            if (legacy.isNotEmpty() && BootstrapReplicas.ENTRY.matches(legacy) &&
                settings.get().customReplicas.none { it.trim().lowercase() == legacy }
            ) {
                settings.update { it.copy(customReplicas = it.customReplicas + legacy, customReplica = "") }
                DesktopLog.i("nyx-boot", "migration v0.3.3→v0.3.4 : réplica personnalisé unique conservé dans la liste ($legacy)")
            }
        }
        ReplicaRegistry.init(File(DesktopPaths.dataDir, "discovered_replicas.txt"))
        ReplicaRegistry.onChanged = { rebuildReplicaList() }

        tor = DesktopTor(DesktopPaths.resolveTorBinary())
        repository = DesktopRepository(
            identity = identity,
            store = store,
            socksPortProvider = { if (tor.bootstrapped) tor.socksPort else 0 },
            descriptorRefresher = { onion -> tor.forceDescriptorRefresh(onion) },
        )
        repository.onChangeCallback = { rebuildReplicaList() }
        wireNotifier()
        wireP2p()
        rebuildReplicaList()
    }

    /**
     * v0.3.6 — PILE D'APPELS P2P (ADR-015, portage miroir Android) : écouteur
     * média (port 47471 onion → local), authentification HELLO (signature
     * ed25519 + contact accepté), client SOCKS sortant, gestionnaire d'appels.
     * L'écouteur démarre ICI — AVANT la publication onion, pour que le port
     * média soit servi dès l'annonce (miroir P2pServer Android).
     */
    private fun wireP2p() {
        net.nyx.messenger.p2p.DesktopP2pServer.verifier = { onion, canonical, sig ->
            val pub = runCatching { net.nyx.messenger.crypto.OnionCodec.onionToPubKey(onion) }.getOrNull()
            pub != null && MessageCipher.INSTANCE.verifyEvent(canonical, sig, pub)
        }
        net.nyx.messenger.p2p.DesktopP2pServer.isAllowed = { onion ->
            store.contact(onion)?.let { it.status == DesktopStore.ContactStatus.ACCEPTED || it.status == DesktopStore.ContactStatus.PENDING } == true
        }
        net.nyx.messenger.p2p.DesktopP2pServer.callSink = { conn ->
            net.nyx.messenger.p2p.DesktopCallManager.onIncomingConnection(conn)
        }
        // v0.3.10 — résolution d'alias pour la sonnerie immédiate sur HELLO
        // (la carte d'appel affiche l'alias du magasin, miroir chemin offre).
        net.nyx.messenger.p2p.DesktopCallManager.aliasOf = { onion ->
            store.contact(onion)?.alias
        }
        net.nyx.messenger.p2p.DesktopP2pServer.start()
        net.nyx.messenger.p2p.DesktopP2pClient.signer = { canonical ->
            identity.onionAddress?.let { me ->
                runCatching { MessageCipher.INSTANCE.signEvent(canonical, identity.secretKey()) }.getOrDefault("")
            } ?: ""
        }
        net.nyx.messenger.p2p.DesktopP2pClient.me = identity.onionAddress
        net.nyx.messenger.p2p.DesktopP2pClient.socksPort = { if (tor.bootstrapped) tor.socksPort else 0 }
        net.nyx.messenger.p2p.DesktopP2pClient.descriptorRefresher = { onion ->
            runCatching { tor.forceDescriptorRefresh(onion) }
        }
        net.nyx.messenger.p2p.DesktopCallManager.init(repository)
    }

    /** v0.3.4 — notifications plateau (miroir MessageNotifier/ContactNotifier). */
    private fun wireNotifier() {
        repository.onIncomingMessage = { peer, text ->
            val alias = store.contact(peer)?.alias ?: peer.take(10) + "…"
            DesktopNotifier.messageReceived(peer, alias, text)
        }
        repository.onIncomingInvite = { peer, alias ->
            DesktopNotifier.invitation(peer, alias)
        }
        repository.onContactAck = { _, ack, alias ->
            when (ack) {
                "declined" -> DesktopNotifier.declined(alias)
                "removed" -> DesktopNotifier.removed(alias)
            }
        }
    }

    /** Fusion des réplicas — miroir NyxApplication : custom > découverts > rendezvous,
     * + v0.3.4 : BOÎTES DES CONTACTS ACCEPTÉS (leur onion:80 — cf. doc
     * [contactBoxes]). */
    @Synchronized
    fun rebuildReplicaList() {
        val list = ArrayList<String>()
        // v0.3.4 — LISTE des réplicas personnalisés (miroir ReplicasListCard) ;
        // l'ANCIEN champ unique customReplica est migré vers la liste à
        // l'init (un v0.3.3 qui l'avait renseigné ne le perd pas).
        for (extra in settings.get().customReplicas) {
            val a = extra.trim().lowercase()
            if (BootstrapReplicas.ENTRY.matches(a)) list.add(a)
        }
        list.addAll(ReplicaRegistry.list())
        val rendezvous = rendezvousEntries()
        list.addAll(rendezvous)
        list.addAll(contactBoxes())
        repository.setReplicas(list.distinct())
    }

    /**
     * v0.3.4 (session 74 — retour terrain « ni les replicas ») : BOÎTES DES
     * CONTACTS comme amorçage de secours. UN FAIT ARCHITECTURAL : chaque
     * appareil Nyx sert son réplica embarqué SUR L'ONION DE SON IDENTITÉ
     * (port 80 → EmbeddedReplica — publication ADD_ONION de l'onion
     * d'identité). L'onion d'un contact accepté EST donc sa boîte — pas
     * besoin d'attendre son annonce pour la connaître : le carnet suffit.
     * (Sur Android, ces adresses entrent via les annonces → ReplicaRegistry
     * « découverts » ; ici le carnet RESTAURÉ joue ce rôle dès le premier
     * démarrage — exactement le fallback legacy Android, SANS dépendre d'une
     * flotte.) Cohérence de routage garantie par les invariants RepoRouting :
     * dépôt → la boîte du pair est une CIBLE valide (priorité 1) ; poll →
     * isPeerPhone FILTRE les boîtes des contacts, jamais sondées comme flotte.
     */
    private fun contactBoxes(): List<String> = runCatching {
        store.contacts()
            .filter { it.status == DesktopStore.ContactStatus.ACCEPTED }
            .mapNotNull { c -> contactBox(c.onion) }
            .distinct()
    }.getOrDefault(emptyList())

    /** Onion du contact (nu ou avec port) → adresse de boîte « onion:80 » normalisée. */
    internal fun contactBox(onion: String): String? {
        val host = onion.trim().lowercase().substringBeforeLast(':').removeSuffix(".onion")
        if (host.length != 56 || !host.all { it in 'a'..'z' || it in '2'..'7' }) return null
        return "$host.onion:80"
    }

    /** v0.3.4 — remplace la LISTE des réplicas personnalisés (Réglages). */
    fun setCustomReplicas(list: List<String>) {
        settings.update { it.copy(customReplicas = list.distinct()) }
        rebuildReplicaList()
    }

    private fun rendezvousEntries(): List<String> = rendezvousOnce.entries()

    /**
     * v0.3.5 (session 75) — vérification du rendezvous signé UNE SEULE FOIS par
     * process (miroir NyxApplication Android : vérifiée au boot, jamais
     * re-vérifiée). AVANT : [rebuildReplicaList] est appelé à CHAQUE cycle de
     * poll (30 s) et re-vérifiait la signature + rejouait l'avertissement —
     * l'export terrain v0.3.4 montrait « rendezvous signé INVALIDE » toutes
     * les ~30 s (spam + travail inutile : la ressource embarquée est
     * immuable). [RendezvousOnce] garantit le calcul unique, testé JVM.
     */
    private val rendezvousOnce = RendezvousOnce {
        runCatching {
            val raw = ServiceLocator::class.java.getResourceAsStream("/rendezvous.json")?.readBytes()
                ?: return@runCatching emptyList()
            val sig = ServiceLocator::class.java.getResourceAsStream("/rendezvous.json.sig")?.readBytes()
                ?: return@runCatching emptyList()
            val verified = RendezvousList.load(raw, sig, RENDEZVOUS_PUBKEY)
            if (verified == null) {
                // v0.3.5 — RÉSOLU À LA RACINE : la signature embarquée était
                // VALIDE depuis v0.2.23, mais le .sig (TEXTE base64) était
                // passé OCTETS BRUTS au vérificateur (89 ≠ 64) → rejet
                // instantané (constat session 75 ; le fallback « boîtes des
                // contacts + réplique personnalisée » reste le filet si une
                // future liste était réellement corrompue).
                DesktopLog.w(
                    "nyx-boot",
                    "rendezvous signé INVALIDE (signature/format) — fallback : boîtes des contacts + réplique personnalisée"
                )
                return@runCatching emptyList()
            }
            DesktopLog.i(
                "nyx-boot",
                "rendezvous signé VÉRIFIÉ : ${verified.entries.size} entrée(s) embarquée(s)" +
                    " (${verified.entries.joinToString(", ") { it.onion }})"
            )
            verified.entries.map { it.onion }
        }.getOrDefault(emptyList())
    }

    // ---------------- démarrage complet (onboarding validé) ----------------

    /**
     * Démarre Tor + réplique embarquée + publication de l'onion d'identité,
     * puis lance les boucles. NON BLOQUANT (v0.3.3) : le travail lourd part
     * sur Dispatchers.IO — retour immédiat pour l'UI.
     *
     * v0.3.3 (session 73 — retour terrain « ne se connecte pas à tor ») :
     *  - **corrigé de fond** : cette fonction était appelée depuis le thread
     *    UI AWT (LaunchedEffect) et BLOQUAIT jusqu'à 4 minutes (cookie 60 s +
     *    bootstrap 180 s) — fenêtre « Ne répond pas », progression jamais
     *    peinte : l'app PARAISSAIT morte/ne pas se connecter pendant tout le
     *    bootstrap, et un échec n'était visible qu'après le gel ;
     *  - **retry automatique 1×** : au premier échec (tor orphelin, pic
     *    réseau…), arrêt propre + kill des tor.exe orphelins + nouvelle
     *    tentative AVANT de déclarer FAILED (miroir TorManager.retry Android) ;
     *  - [retryBackend] (bouton « Réessayer » de la bannière) relance à tout
     *    moment après un échec.
     */
    fun startBackend(onBootstrapReady: () -> Unit = {}) {
        val me = identity.onionAddress ?: error("identité absente — démarrage refusé")
        if (!starting.compareAndSet(false, true)) {
            DesktopLog.w("nyx-tor", "startBackend appelé pendant un démarrage en cours — ignoré")
            return
        }
        _torState.value = TorState(TorPhase.STARTING)

        scope.launch {
            try {
                attemptBackendStart(me)
                _torState.value = TorState(TorPhase.READY, 100, me)
                DesktopLog.i("nyx-tor", "backend PRÊT (Tor bootstrappé + onion publié)")
                onBootstrapReady()
                startLoops()

                // Annonce de NOTRE réplica aux contacts (l'adresse publiée = onion:80).
                runCatching { repository.announceReplica("$me:80") }
            } catch (first: Exception) {
                DesktopLog.w("nyx-tor", "1ʳᵉ tentative de démarrage en échec : ${first.message}")
                _torState.value = TorState(
                    TorPhase.STARTING,
                    message = "échec — nouvelle tentative en cours…"
                )
                runCatching { shutdownBackend() }
                try {
                    DesktopLog.i("nyx-tor", "2ᵉ tentative (après kill des tor orphelins)")
                    attemptBackendStart(me)
                    _torState.value = TorState(TorPhase.READY, 100, me)
                    DesktopLog.i("nyx-tor", "backend PRÊT à la 2ᵉ tentative")
                    onBootstrapReady()
                    startLoops()
                    runCatching { repository.announceReplica("$me:80") }
                } catch (second: Exception) {
                    DesktopLog.w(
                        "nyx-tor",
                        "2ᵉ tentative en échec — backend FAILED : ${second.message}"
                    )
                    _torState.value = TorState(
                        TorPhase.FAILED,
                        message = second.message ?: "échec inconnu"
                    )
                }
            } finally {
                starting.set(false)
            }
        }
    }

    /** Une tentative complète (réplique embarquée + Tor + publication). Bloquant — IO seulement. */
    private fun attemptBackendStart(me: String) {
        // La réplique embarquée doit être PRÊTE avant la publication (port 80 servi).
        EmbeddedReplica.verify = { author, canonical, sig ->
            val pub = runCatching { net.nyx.messenger.crypto.OnionCodec.onionToPubKey(author) }.getOrNull()
            pub != null && MessageCipher.INSTANCE.verifyEvent(canonical, sig, pub)
        }
        EmbeddedReplica.isAllowed = { onion ->
            onion == me || store.contact(onion)?.let { it.status == DesktopStore.ContactStatus.ACCEPTED || it.status == DesktopStore.ContactStatus.PENDING } == true
        }
        if (settings.get().embeddedReplicaEnabled) {
            EmbeddedReplica.serving = true
        }
        EmbeddedReplica.start(me, VERSION)

        val priv = identity.secretKey()
        tor.onBootstrap = { pct -> _torState.value = _torState.value.copy(percent = pct) }
        // v0.3.6 — écouteur média (appels directs) publié avec l'onion : le
        // port 47471 est mappé sur l'écouteur P2P démarré à l'init.
        val mediaPort = if (net.nyx.messenger.p2p.DesktopP2pServer.localPort > 0)
            net.nyx.messenger.p2p.DesktopP2pServer.localPort else 0
        tor.startAndWait(priv, EmbeddedReplica.localPort, mediaPort)
    }

    /** v0.3.3 — relance manuelle depuis la bannière (état FAILED). */
    fun retryBackend() {
        if (_torState.value.phase == TorPhase.READY || starting.get()) return
        DesktopLog.i("nyx-tor", "retryBackend demandé depuis l'UI")
        runCatching { shutdownBackend() }
        startBackend()
    }

    private fun startLoops() {
        if (!loopsRunning.compareAndSet(false, true)) return
        // v0.3.4 — session LEURRE : AUCUNE boucle réseau (miroir Android — ni
        // poll, ni annonces, ni sonde, ni mise à jour).
        if (sessionMode == SessionMode.PANIC) {
            loopsRunning.set(false)
            DesktopLog.i("nyx-boot", "session leurre : boucles réseau coupées")
            return
        }
        val gen = loopGeneration
        scope.launch {
            while (isActive && gen == loopGeneration) {
                // v0.3.10 — 15 s (miroir Android ≤ 15 s) : l'offre d'appel
                // arrivait jusqu'à 30 s après le dépôt (terrain 28/10 : le PC
                // « n'a jamais vu la notification ») ; la sonnerie démarre
                // désormais DÈS le HELLO média, l'offre confirme en ≤ 15 s.
                delay(15_000L)
                runCatching { repository.pollInbox() }
                    .onSuccess { n -> if (n > 0) _lastPollNew.value = n }
                runCatching { repository.ensureContactNotified() }
                runCatching { store.purgeExpired(System.currentTimeMillis()) }
                // v0.3.4 — la fusion des réplicas est RAFRAÎCHIE à chaque cycle :
                // les boîtes des contacts (nouvelles acceptations, restaurations
                // de sauvegarde) entrent dans la liste sans attendre une annonce.
                runCatching { rebuildReplicaList() }
                // v0.3.4 — fiches pair : TTL 1 h (miroir refreshPeerProfiles Android).
                runCatching { repository.refreshPeerProfiles() }
                _tick.value = System.currentTimeMillis()
            }
        }
        // Re-annonce du réplica toutes les 30 min tant qu'il sert (miroir Android).
        scope.launch {
            while (isActive && gen == loopGeneration) {
                delay(30 * 60_000L)
                if (settings.get().embeddedReplicaEnabled) {
                    runCatching {
                        identity.onionAddress?.let { repository.announceReplica("$it:80") }
                    }
                }
            }
        }
        // v0.3.4 — republication du profil public toutes les 6 h (miroir Android :
        // fraîcheur du champ replica + retry implicite des publications ratées).
        scope.launch {
            while (isActive && gen == loopGeneration) {
                delay(6 * 3_600_000L)
                runCatching { repository.publishProfile() }
            }
        }
        // v0.3.4 — BOUCLE DE SONDE RÉPLICAS (miroir ReplicaProbeEffect Android :
        // démarre SEULEMENT quand Tor est prêt — jamais pendant le bootstrap ;
        // 8 s entre les tentatives ; s'ARRÊTE dès qu'un réplica répond, l'écran
        // de progression et le panneau Connexions observent replicaProbe).
        scope.launch {
            while (isActive && gen == loopGeneration) {
                runProbe()
                if (_replicaProbe.value.ok == true) break
                delay(8_000L)
            }
        }
        // v0.3.4 — mise à jour automatique (miroir Updater Android : contrôle
        // dès Tor prêt puis 6 h / 30 min selon le canal).
        runCatching { DesktopUpdater.start(scope) }
    }

    /**
     * v0.3.4 — sonde GET /health de TOUS les réplicas connus (flotte + boîtes
     * des contacts) via le SOCKS de Tor (miroir NyxRepository.replicaHealth +
     * écran Connexions Android). Ne lance JAMAIS d'exception : l'état tombe
     * en « dégradé » (ok=false + détail) et l'appelant réessaie.
     */
    private suspend fun runProbe() {
        try {
            val socks = if (tor.bootstrapped) tor.socksPort else 0
            if (socks <= 0) {
                _replicaProbe.value = ReplicaProbeState(detail = "Tor non prêt")
                return
            }
            val targets = repository.replicas.toList()
            if (targets.isEmpty()) {
                _replicaProbe.value = ReplicaProbeState(detail = "aucun réplica connu")
                return
            }
            var okCount = 0
            var lastDetail: String? = null
            for (addr in targets) {
                val r = RelayClient.of(addr, socks).healthDetailed()
                if (r.ok) okCount++ else lastDetail = "${addr.take(20)}… ${r.detail}"
            }
            _replicaProbe.value = ReplicaProbeState(
                ok = okCount > 0,
                okCount = okCount,
                total = targets.size,
                detail = if (okCount > 0) null else lastDetail,
                at = System.currentTimeMillis(),
            )
            DesktopLog.i(
                "nyx-probe",
                "sonde réplicas : $okCount/${targets.size} joignable(s)" +
                    (_replicaProbe.value.detail?.let { " — $it" } ?: "")
            )
        } catch (e: Exception) {
            _replicaProbe.value = ReplicaProbeState(ok = false, detail = e.message ?: "sonde impossible")
        }
    }

    /** v0.3.4 — sonde manuelle (bouton « Réessayer » / panneau Connexions). */
    fun probeReplicasNow() {
        scope.launch { runProbe() }
    }

    /** Poll immédiat (ouverture d'app, envoi, action manuelle). */
    fun pollNow() {
        scope.launch {
            runCatching { repository.pollInbox() }
                .onSuccess { n -> if (n > 0) _lastPollNew.value = n }
            _tick.value = System.currentTimeMillis()
        }
    }

    /** Arrêt PROPRE : DEL_ONION + démon tor + réplique (dialogue Quitter / retry). */
    fun shutdownBackend() {
        EmbeddedReplica.stop()
        tor.shutdownNow()
        loopGeneration++      // v0.3.4 — stoppe les boucles vivantes
        loopsRunning.set(false)
        _torState.value = TorState()
        // v0.3.4 — la sonde réplicas repart de zéro (un retry ne doit JAMAIS
        // réafficher un « joignable » périmé sur l'écran de progression).
        _replicaProbe.value = ReplicaProbeState()
    }

    /** Création d'identité depuis l'onboarding. */
    fun createIdentity(alias: String?, password: CharArray?): String {
        val onion = identity.createIfNeeded(password, alias)
        return onion
    }

    /**
     * v0.3.2 — Restauration d'un FICHIER DE SAUVEGARDE DE COMPTE (retour
     * terrain : « je n'arrive pas à restaurer un fichier de sauvegarde que
     * j'ai généré avec l'appli téléphone »). Accepte, comme l'Android :
     *   - l'enveloppe chiffrée nyx-enc-v1.… (Android ≥ v0.2.26) — mot de
     *     passe du compte téléphone requis ;
     *   - le JSON v1/v2 en clair (Android v0.2.3-v0.2.25) ;
     *   - la clé nue « nyx1.… » collée.
     * [identityPassword] = mot de passe de SCELLEMENT LOCAL optionnel (ne
     * quitte pas cette machine — distinct du mot de passe de sauvegarde).
     */
    fun restoreFromBackup(
        text: String,
        backupPassword: CharArray? = null,
        identityPassword: CharArray? = null,
    ): String {
        val t = text.trim()
        if (t.isEmpty()) error("sauvegarde vide")
        val plain: String = if (net.nyx.messenger.crypto.PasswordCrypto.isEncrypted(t)) {
            val pwd = backupPassword ?: error("mot de passe de sauvegarde requis (fichier chiffré)")
            val bytes = net.nyx.messenger.crypto.PasswordCrypto.decrypt(t, pwd)
                ?: error("mot de passe de sauvegarde incorrect, ou fichier altéré/tronqué")
            bytes.toString(Charsets.UTF_8)
        } else t
        val found = net.nyx.messenger.crypto.IdentityBackup.extract(plain)
            ?: error("contenu de sauvegarde méconnaissable — ni JSON Nyx, ni clé nyx1.… (fichier tronqué ?)")
        return BackupRestore.applyRestore(identity, store, found, identityPassword)
    }

    /** v0.3.2 — JSON v2 de sauvegarde (onboarding/Paramètres — RED-DUP). */
    fun exportBackupJson(): String = BackupRestore.exportBackupJson(identity, store)

    fun recoveryKey(): String? {
        if (!identity.exists) return null
        val priv = runCatching { identity.secretKey() }.getOrNull() ?: return null
        return RecoveryKey.encode(priv.copyOfRange(0, 32))
    }

    /** Wipe complet (retour à l'onboarding). */
    fun wipeAll() {
        shutdownBackend()
        store.wipe()
        identity.wipe()
        ReplicaRegistry.list().forEach { /* les découvertes repartent de zéro */ }
    }

    // ---------------- v0.3.4 : parité Android — comptes & sécurité ----------------

    /** TTL éphémère d'une conversation (secondes ; 0 = OFF). */
    fun ephemeralTtl(peer: String): Long = settings.ephemeralTtl(peer)

    fun setEphemeralTtl(peer: String, ttlSeconds: Long) {
        settings.setEphemeralTtl(peer, ttlSeconds)
    }

    /** Export des conversations choisies (JSON chiffrable par mot de passe côté UI). */
    fun exportConversationsJson(peers: List<String>): String =
        repository.exportConversationsJson(peers)

    fun importConversationsJson(text: String): Int =
        repository.importConversationsJson(text)

    /** Consommation d'une vue sur un média limité (écran de conversation). */
    fun consumeMediaView(fid: String): Int = repository.consumeMediaView(fid)

    /**
     * « Déconnecter le compte » (miroir AccountCard Android v0.2.27) :
     * vérifie le mot de passe NORMAL puis efface TOUT (identité, magasin,
     * réglages, coffre) — retour à l'onboarding SANS quitter l'app.
     */
    fun disconnectAccount(password: CharArray): Boolean {
        if (DesktopVault.isSet(DesktopVault.Mode.NORMAL) &&
            DesktopVault.verify(password) == null) return false
        wipeEverything()
        DesktopLog.i("nyx-wipe", "compte déconnecté — retour à l'onboarding")
        return true
    }

    /**
     * DESTRUCTION (miroir Wipe.execute Android v0.2.18) : efface session
     * réelle ET leurre, sans confirmation (c'est le sens même du mot de passe).
     */
    fun destructionWipe() {
        wipeEverything()
        DesktopLog.w("nyx-wipe", "DESTRUCTION exécutée (mot de passe destruction)")
    }

    private fun wipeEverything() {
        shutdownBackend()
        val decoy = decoyDir()
        val wasDecoy = sessionMode == SessionMode.PANIC
        restoreRealPaths()
        if (wasDecoy) {
            // Les instances vivantes pointent sur le LEURRE : effacer le RÉEL
            // via des instances neuves, puis raser le répertoire leurre.
            runCatching { DesktopStore().apply { ensureLoaded() }.wipe() }
            runCatching { DesktopIdentity(File(DesktopPaths.dataDir, "identity.json")).wipe() }
        } else {
            store.wipe()
            identity.wipe()
        }
        DesktopVault.wipe()
        runCatching { replicaDirDelete() }
        runCatching { File(DesktopPaths.dataDir, "update").deleteRecursively() }
        runCatching { File(DesktopPaths.dataDir, "discovered_replicas.txt").delete() }
        runCatching { File(DesktopPaths.dataDir, "settings.json").delete() }
        runCatching { File(DesktopPaths.dataDir, "logs").deleteRecursively() }
        runCatching { decoy.deleteRecursively() }
        sessionMode = SessionMode.NORMAL
        _locked.value = false
    }

    private fun replicaDirDelete() {
        File(DesktopPaths.dataDir, "replica").deleteRecursively()
        File(DesktopPaths.dataDir, "tor").deleteRecursively()
    }

    // ---------------- session leurre (miroir pending_mode Android) ----------------

    /** Répertoire de données du leurre : <réel>-decoy (jamais mélangés). */
    internal fun decoyDir(): File {
        val real = realDataDir()
        return File(real.parentFile, real.name + "-decoy")
    }

    private fun realDataDir(): File =
        initialDataDirOverride?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".nyx-messenger")

    internal fun enterDecoyPaths() {
        System.setProperty("nyx.data.dir", decoyDir().absolutePath)
    }

    internal fun restoreRealPaths() {
        val initial = initialDataDirOverride
        if (initial == null) System.clearProperty("nyx.data.dir")
        else System.setProperty("nyx.data.dir", initial)
    }

    /** Capture l'override -Dnyx.data.dir AVANT toute redirection (init). */
    private val initialDataDirOverride: String? = System.getProperty("nyx.data.dir")

    /**
     * Bascule de session SANS relancer le processus : arrête le backend,
     * re-pointe le répertoire de données et ré-assemble l'app (miroir
     * restartProcess Android — l'équivalent bureau du kill+relance).
     */
    fun switchSessionMode(mode: DesktopVault.Mode) {
        DesktopVault.setPendingMode(mode)
        shutdownBackend()
        if (mode == DesktopVault.Mode.PANIC) {
            enterDecoyPaths()
            sessionMode = SessionMode.PANIC
            DesktopLog.i("nyx-boot", "bascule SESSION LEURRE (panic)")
        } else {
            restoreRealPaths()
            sessionMode = SessionMode.NORMAL
            DesktopLog.i("nyx-boot", "retour SESSION NORMALE")
        }
        DesktopPaths.ensure()
        DesktopLog.initFile(File(DesktopPaths.dataDir, "logs/nyx-desktop.log"))
        identity = DesktopIdentity(File(DesktopPaths.dataDir, "identity.json"))
        store = DesktopStore().apply { ensureLoaded() }
        settings = DesktopSettings(File(DesktopPaths.dataDir, "settings.json"))
        _themeSetting.value = settings.get().theme
        ReplicaRegistry.init(File(DesktopPaths.dataDir, "discovered_replicas.txt"))
        repository = DesktopRepository(
            identity = identity,
            store = store,
            socksPortProvider = { if (tor.bootstrapped) tor.socksPort else 0 },
            descriptorRefresher = { onion -> tor.forceDescriptorRefresh(onion) },
        )
        repository.onChangeCallback = { rebuildReplicaList() }
        wireNotifier()
        rebuildReplicaList()
        _tick.value = System.currentTimeMillis()
    }

    const val RENDEZVOUS_PUBKEY = "M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q="
}
