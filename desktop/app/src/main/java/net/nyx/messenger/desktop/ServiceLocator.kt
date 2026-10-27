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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Assembleur applicatif desktop — équivalent du ServiceLocator Android :
 * wiring identité/magasin/Tor/réplica/repository + BOUCLE DE POLL au niveau
 * APPLICATION (30 s — invariant v0.2.10 Android : un appareil sans conversation
 * ouverte reçoit quand même ses événements) + annonces de réplica (30 min).
 */
object ServiceLocator {

    const val VERSION = "0.3.3"

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

    /** v0.3.3 — un seul démarrage de backend à la fois (retry inclus). */
    private val starting = AtomicBoolean(false)

    /** v0.3.3 — boucles de poll/annonce lancées ? (idempotence de startBackend). */
    private val loopsRunning = AtomicBoolean(false)

    fun init() {
        DesktopPaths.ensure()
        // v0.3.3 — anneau de diagnostic PERSISTANT (miroir DiagLog v0.2.33) :
        // les échecs de DÉMARRAGE survivent au redémarrage de l'app.
        DesktopLog.initFile(File(DesktopPaths.dataDir, "logs/nyx-desktop.log"))
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
        tor.startAndWait(priv, EmbeddedReplica.localPort)
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

    /** Arrêt PROPRE : DEL_ONION + démon tor + réplique (dialogue Quitter / retry). */
    fun shutdownBackend() {
        EmbeddedReplica.stop()
        tor.shutdownNow()
        loopsRunning.set(false)
        _torState.value = TorState()
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

    const val RENDEZVOUS_PUBKEY = "M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q="
}
