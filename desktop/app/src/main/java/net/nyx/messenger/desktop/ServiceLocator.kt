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
import net.nyx.messenger.net.ReplicaRegistry
import net.nyx.messenger.replica.EmbeddedReplica
import net.nyx.messenger.repo.DesktopRepository
import java.io.File
import java.util.Base64

/**
 * Assembleur applicatif desktop — équivalent du ServiceLocator Android :
 * wiring identité/magasin/Tor/réplica/repository + BOUCLE DE POLL au niveau
 * APPLICATION (30 s — invariant v0.2.10 Android : un appareil sans conversation
 * ouverte reçoit quand même ses événements) + annonces de réplica (30 min).
 */
object ServiceLocator {

    const val VERSION = "0.3.0"

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

    @Volatile var customReplica: String = ""
        private set

    fun init() {
        DesktopPaths.ensure()
        identity = DesktopIdentity(File(DesktopPaths.dataDir, "identity.json"))
        store = DesktopStore().apply { ensureLoaded() }
        settings = DesktopSettings(File(DesktopPaths.dataDir, "settings.json"))
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
        rebuildReplicaList()
    }

    /** Fusion des réplicas (miroir NyxApplication : custom > découverts > rendezvous). */
    @Synchronized
    fun rebuildReplicaList() {
        val list = ArrayList<String>()
        val custom = customReplica.trim().lowercase()
        if (BootstrapReplicas.ENTRY.matches(custom)) list.add(custom)
        list.addAll(ReplicaRegistry.list())
        list.addAll(rendezvousEntries())
        repository.setReplicas(list.distinct())
    }

    fun setCustomReplica(addr: String) {
        customReplica = addr
        rebuildReplicaList()
    }

    private fun rendezvousEntries(): List<String> {
        return runCatching {
            val raw = ServiceLocator::class.java.getResourceAsStream("/rendezvous.json")?.readBytes()
                ?: return emptyList()
            val sig = ServiceLocator::class.java.getResourceAsStream("/rendezvous.json.sig")?.readBytes()
                ?: return emptyList()
            val verified = RendezvousList.load(raw, sig, RENDEZVOUS_PUBKEY) ?: return emptyList()
            verified.entries.map { it.onion }
        }.getOrDefault(emptyList())
    }

    // ---------------- démarrage complet (onboarding validé) ----------------

    /**
     * Démarre Tor + réplique embarquée + publication de l'onion d'identité,
     * puis lance les boucles. Bloquant jusqu'à « tor prêt » (ou échec).
     */
    fun startBackend(onBootstrapReady: () -> Unit = {}) {
        val me = identity.onionAddress ?: error("identité absente — démarrage refusé")
        _torState.value = TorState(TorPhase.STARTING)

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
        try {
            tor.onBootstrap = { pct -> _torState.value = _torState.value.copy(percent = pct) }
            tor.startAndWait(priv, EmbeddedReplica.localPort)
            _torState.value = TorState(TorPhase.READY, 100, me)
            onBootstrapReady()
        } catch (e: Exception) {
            _torState.value = TorState(TorPhase.FAILED, message = e.message)
            throw e
        }

        // Annonce de NOTRE réplica aux contacts (l'adresse publiée = onion:80).
        scope.launch { runCatching { repository.announceReplica("$me:80") } }
        startLoops()
    }

    private fun startLoops() {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                runCatching { repository.pollInbox() }
                    .onSuccess { n -> if (n > 0) _lastPollNew.value = n }
                runCatching { repository.ensureContactNotified() }
                runCatching { store.purgeExpired(System.currentTimeMillis()) }
                _tick.value = System.currentTimeMillis()
            }
        }
        // Re-annonce du réplica toutes les 30 min tant qu'il sert (miroir Android).
        scope.launch {
            while (isActive) {
                delay(30 * 60_000L)
                if (settings.get().embeddedReplicaEnabled) {
                    runCatching {
                        identity.onionAddress?.let { repository.announceReplica("$it:80") }
                    }
                }
            }
        }
    }

    /** Poll immédiat (ouverture d'app, envoi, action manuelle). */
    fun pollNow() {
        scope.launch {
            runCatching { repository.pollInbox() }
                .onSuccess { n -> if (n > 0) _lastPollNew.value = n }
            _tick.value = System.currentTimeMillis()
        }
    }

    /** Arrêt PROPRE : DEL_ONION + démon tor + réplique (dialogue Quitter). */
    fun shutdownBackend() {
        EmbeddedReplica.stop()
        tor.shutdownNow()
        _torState.value = TorState()
    }

    /** Création d'identité depuis l'onboarding. */
    fun createIdentity(alias: String?, password: CharArray?): String {
        val onion = identity.createIfNeeded(password, alias)
        return onion
    }

    fun restoreIdentity(key: String, password: CharArray?): String =
        identity.restoreFromSeed(key, password)

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

    const val RENDEZVOUS_PUBKEY = "M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q="
}
