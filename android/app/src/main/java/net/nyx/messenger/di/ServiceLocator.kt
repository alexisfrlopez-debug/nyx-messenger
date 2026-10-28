package net.nyx.messenger.di

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.nyx.messenger.crypto.IdentityManager
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.data.db.NyxDatabase
import net.nyx.messenger.repo.NyxRepository
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Localisateur de services — DI manuelle (volontairement simple à ce stade).
 * La clé SQLCipher est un secret aléatoire scellé par le Keystore : la base
 * n'est lisible que sur cet appareil déverrouillé.
 */
object ServiceLocator {

    lateinit var sodium: LazySodiumAndroid
        private set
    lateinit var cipher: MessageCipher
        private set
    lateinit var identity: IdentityManager
        private set
    lateinit var database: NyxDatabase
        private set
    lateinit var repository: NyxRepository
        private set

    @Volatile var initialized = false; private set

    /**
     * v0.2.18 — Mode de session (mots de passe normal/panique) : PANIQUE = base
     * leurre vide (nyx_decoy.db), SANS poll inbox (les messages du vrai compte
     * restent dans la boîte du réplica) et SANS canal direct P2P.
     */
    enum class SessionMode { NORMAL, PANIC }

    @Volatile var mode: SessionMode = SessionMode.NORMAL
        private set

    /** v0.2.14 : contexte application — accès plumbing (notifications, profil). */
    private var appCtx: Context? = null
    fun appContext(): Context =
        appCtx ?: error("ServiceLocator non initialisé")

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            appCtx = context.applicationContext
            // v0.2.18 : le mode panique est posé par LockScreen AVANT le
            // redémarrage du process (pendingMode) — lu ici, une seule fois.
            val pending = net.nyx.messenger.security.PasswordVault.consumePendingMode(context)
            mode = if (pending == net.nyx.messenger.security.PasswordVault.Mode.PANIC) {
                android.util.Log.i("nyx-boot", "mode PANIQUE — base leurre, poll et P2P désactivés")
                SessionMode.PANIC
            } else SessionMode.NORMAL

            sodium = LazySodiumAndroid(SodiumAndroid())
            cipher = MessageCipher(sodium)
            identity = IdentityManager.get(context)

            SQLiteDatabase.loadLibs(context)
            val dbPassphrase = dbPassphrase(context)
            // v0.2.18 : en mode panique, la base leurre (vide, même schéma) est
            // ouverte à la place de la vraie — contacts et historique invisibles.
            database = Room.databaseBuilder(
                context, NyxDatabase::class.java,
                if (mode == SessionMode.PANIC) NyxDatabase.NAME_DECOY else NyxDatabase.NAME
            )
                .openHelperFactory(SupportFactory(dbPassphrase, null, false))
                .addMigrations(
                    NyxDatabase.MIGRATION_1_2, NyxDatabase.MIGRATION_2_3,
                    NyxDatabase.MIGRATION_3_4, NyxDatabase.MIGRATION_4_5,
                    NyxDatabase.MIGRATION_5_6, NyxDatabase.MIGRATION_6_7,
                    NyxDatabase.MIGRATION_7_8,
                    // v0.2.26 : messages éphémères (colonne expireAt).
                    NyxDatabase.MIGRATION_8_9,
                    // v0.2.28 : politique d'affichage média (vue unique / 2 vues).
                    NyxDatabase.MIGRATION_9_10,
                    // v0.2.29 : blocage local des contacts (contacts.blockedAt).
                    NyxDatabase.MIGRATION_10_11
                )
                .build()

            // Repository unique partagé par tous les écrans (supprime la construction
            // redondante dans Onboarding/ConversationList/Chat — audit cycle 1).
            // v0.2.15 : + peerProfileDao (cache avatars/noms publics des contacts).
            // v0.2.17 : + attachmentDao (pièces jointes — photos/vidéos/fichiers).
            // v0.2.19 : + delPendingDao (suppressions « pour tous » différées).
            repository = NyxRepository(
                identity, cipher,
                database.messageDao(), database.contactDao(), database.pollStateDao(),
                database.seqStateDao(), database.peerProfileDao(), database.attachmentDao(),
                database.delPendingDao()
            )
            // v0.2.45 — GUÉRISON DU CACHE DESCRIPTEUR : le repo déclenche un
            // HSFETCH (contournement du cache client tor, v0.2.37) après 2 échecs
            // réseau consécutifs du même réplica — cause racine terrain 23/10 :
            // déploiement serveur → descripteur orphelin servi par les HSDirs →
            // caché ~24 h côté client → timeouts 25 s en boucle. Injecté ici
            // (le repo n'a pas le Context → TorManager).
            repository.descriptorRefresher = { onion ->
                net.nyx.messenger.tor.TorManager.get(context).forceDescriptorRefresh(onion)
            }

            // v0.2.14 : notifications système du cycle de vie des contacts
            // (invitation avec actions Accepter/Refuser, refus, suppression).
            net.nyx.messenger.notify.ContactNotifier.init(context)
            // v0.2.26 : notifications de MESSAGES reçus (texte + pièces jointes).
            net.nyx.messenger.notify.MessageNotifier.init(context)

            if (mode == SessionMode.PANIC) {
                // Mode panique : AUCUN poll (les messages du vrai compte
                // restent sur le réplica), AUCUN canal P2P, AUCUN appel.
                initialized = true
                return
            }

            // v0.2.18 — CANAL DIRECT P2P (appels + fichiers téléphone-à-téléphone) :
            // l'écouteur local démarre AVANT la publication onion (le port média
            // doit être servi dès l'annonce ADD_ONION) ; authentification par la
            // clé d'identité + verrou contact mutuel.
            val repoRef = repository
            net.nyx.messenger.p2p.P2pClient.me = identity.onionAddress
            net.nyx.messenger.p2p.P2pClient.signer = { canonical ->
                cipher.signEvent(canonical, identity.secretKey())
            }
            net.nyx.messenger.p2p.P2pVerifier.get = { onion, canonical, sig ->
                runCatching {
                    cipher.verifyEvent(canonical, sig, OnionCodec.onionToPubKey(onion))
                }.getOrDefault(false)
            }
            net.nyx.messenger.p2p.P2pServer.isAllowed = { peer ->
                // v0.2.48 — HARMONISATION AVEC LE RÉPLICA EMBARQUÉ (terrain 24/10
                // 08:49 : « HELLO events REFUSÉ de kxcwah… » alors que la
                // SONNERIE du même pair passait par le journal) : le média
                // exigeait ACCEPTED strict alors que messages + sonnerie passent
                // déjà à PENDING → un pair PENDING pouvait faire sonner et se
                // voir DÉCROCHER mais son média était refusé = « décrochage
                // impossible » STRUCTUREL. Même politique que le :80 (le pair
                // reste borné par l'existence du contact + les quotas).
                val st = runBlocking { database.contactDao().get(peer)?.status }
                st == net.nyx.messenger.data.db.ContactStatus.ACCEPTED ||
                    st == net.nyx.messenger.data.db.ContactStatus.PENDING
            }
            net.nyx.messenger.p2p.P2pServer.fileSink = { author, body ->
                repoRef.receiveDirectFragment(author, body)
            }
            // v0.2.41 — transport de replan ADR-017 : ingestion des événements
            // directs (messages + signalisation d'appel) par la porte commune.
            net.nyx.messenger.p2p.P2pServer.eventSink = { author, events ->
                repoRef.receiveDirectEvents(author, events)
            }
            net.nyx.messenger.p2p.P2pServer.callSink = { conn ->
                net.nyx.messenger.p2p.CallManager.onIncomingConnection(conn)
            }
            net.nyx.messenger.p2p.P2pServer.start()
            net.nyx.messenger.p2p.CallManager.init(repoRef, context)

            // v0.2.19 → v0.2.35 — RÉPLICA TEMPORAIRE EMBARQUÉ (TOUTES LES
            // VERSIONS — demande opérateur : « lorsque l'application est
            // lancée, le téléphone devienne lui-même un replica temporaire » ;
            // validé en « test 2 » de la v0.2.19 à la v0.2.34, intégré aux
            // versions normale et test depuis la v0.2.35).
            // Écouteur démarré AVANT la publication onion (le port 80 doit être
            // servi dès l'annonce — même ordre que P2pServer) ; Interrupteur et
            // plafonds dans Paramètres.
            if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA) {
                val settingsRef = net.nyx.messenger.settings.SettingsManager.get(context)
                net.nyx.messenger.replica.EmbeddedReplica.serving = settingsRef.replicaEnabled.value
                net.nyx.messenger.replica.EmbeddedReplica.verify = { author, canonical, sig ->
                    runCatching {
                        cipher.verifyEvent(canonical, sig, OnionCodec.onionToPubKey(author))
                    }.getOrDefault(false)
                }
                net.nyx.messenger.replica.EmbeddedReplica.isAllowed = { onion ->
                    val st = runBlocking { database.contactDao().get(onion)?.status }
                    st == net.nyx.messenger.data.db.ContactStatus.ACCEPTED ||
                        st == net.nyx.messenger.data.db.ContactStatus.PENDING
                }
                net.nyx.messenger.replica.EmbeddedReplica.start(
                    database, identity.onionAddress ?: "", net.nyx.messenger.BuildConfig.VERSION_NAME
                )

                // v0.2.46 — CANAL INVERSÉ NYX-LINK (ADR-018) : le téléphone
                // TIRE les requêtes destinées à son réplica embarqué par un
                // long-poll permanent vers le réplica actif de flotte (chemin
                // sortant prouvé à 100 % sur le terrain) — il ne dépend plus
                // de son onion ENTRANT, chroniquement injoignable. La sonde
                // de la console et les dépôts/polls des pairs aboutissent
                // même quand l'oignon du téléphone est mort dehors.
                net.nyx.messenger.net.LinkChannel.me = { identity.onionAddress }
                net.nyx.messenger.net.LinkChannel.signer = { canonical ->
                    cipher.signEvent(canonical, identity.secretKey())
                }
                net.nyx.messenger.net.LinkChannel.replicaProvider = {
                    // v0.2.48 — CIBLE DE TIRAGE = RÉPLICA DE FLOTTE UNIQUEMENT
                    // (terrain 24/10 : le canal tirait vers l'embarqué D'UN PAIR
                    // découvert — un téléphone est un TIREUR, il n'implémente
                    // pas les routes /link : 404 « route inconnue » + backoff
                    // 10 s à chaque cycle avant de retomber sur le nœud de
                    // flotte).
                    repository.fleetReplica
                }
                // v0.2.49 — MARQUEUR DE BUILD VÉRIFIABLE (terrain 24/10 11:0x :
                // l'APK v0.2.48 du 08:12 était un build MIXTE — la « reprise »
                // du pipeline sur mtimes ambigus a packagé des artefacts
                // périmés : les correctifs TorManager (HSFETCH 65 ≠ 56),
                // ServiceLocator (verrou média + tirage flotte) et NyxRepository
                // (fleetReplica) étaient ABSENTS du dex livré, seuls les fichiers
                // d'appel étaient à jour ; l'audit 26/26 passait car il vérifie
                // des propriétés, pas le contenu compilé). Cette ligne est la
                // PREUVE terrain que le câblage complet de CETTE version tourne :
                // une chaîne LITTÉRALE (jamais interpolée — les morceaux
                // interpolés ne survivent pas à R8 en un seul tenant) présente
                // dans le dex SI ET SEULEMENT SI ce fichier est compilé ; le
                // pipeline de build la VÉRIFIE dans le dex avant toute livraison
                // (scripts/build-apk.sh, garde-fou v0.2.49) et l'export de logs
                // la montre à chaque démarrage.
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-boot",
                    "câblage v0.2.57 vérifié : caméra PERDUE = visio perdue PAS appel perdu (callbacks caméra protégés, dégradation audio-only — terrain 28/10 : FATAL CameraDevice was already closed 0,6 s après un décroché visio verrouillé), DÉCROCHER VISIO depuis la notification OUVRE l'activité verrouillée (caméra au premier plan), refus/décroché verrouillés (intent plein écran + boutons notification + overlay au-dessus du verrou), micro ADPCM démarré avant lecture (plus d'ANR Error -38), ACCEPT avant toute trame média, latence audio interop réduite (tampon minimal + anti-arriéré), orientation vidéo suivie (rotation capteur→écran, trame F_VIDEO_ROT en cours d'appel), sonnerie possédée (boucle stoppée au décroché, canal silencieux), csd H.264 lu après la 1ʳᵉ trame caméra, raccroché et refus LIBRES immédiatement, démon tor RELANCÉ automatiquement s'il meurt, csd-0 AAC calculé, offre sur la boîte du pair puis flotte, poll réplica propre + flotte, codec ADPCM négociable (HELLO/ACCEPT ac — interop appels PC↔téléphone, émission basculable, réception logicielle, téléphone↔téléphone reste AAC), VISIO MJPEG interop PC↔téléphone (HELLO/ACCEPT vc, émission YUV→JPEG basculable, réception BitmapFactory→Canvas, téléphone↔téléphone reste H.264), accusés sans double émission + id local re-aligné au resync"
                )
                net.nyx.messenger.net.LinkChannel.socksProvider = {
                    // -1 tant que Tor n'est pas prêt (le canal dort, pas de
                    // churn d'erreurs au bootstrap) ; le port réel ensuite.
                    if (net.nyx.messenger.tor.TorManager.get(context).state.value
                        is net.nyx.messenger.tor.TorManager.TorState.Ready
                    ) net.nyx.messenger.tor.TorManager.activeSocksPort else -1
                }
                net.nyx.messenger.net.LinkChannel.dispatcher = { method, path, query, body ->
                    net.nyx.messenger.replica.EmbeddedReplica.dispatch(method, path, query, body)
                }
                net.nyx.messenger.net.LinkChannel.onReplicaFailed = { reason ->
                    repository.markReplicaFailed(reason)
                }
                net.nyx.messenger.net.LinkChannel.start()
            }

            startInboxPolling()

            // v0.2.20 — MISE À JOUR AUTOMATIQUE (build standard). v0.2.40 —
            // ÉTENDUE AU BUILD TEST (demande opérateur : « j'aimerais que la
            // version test se mette à jour automatiquement dès qu'une nouvelle
            // version est poussée sur github ») : les DEUX builds lancent la
            // boucle, sur des canaux DISJOINTS — standard : dernière release
            // VALIDÉE (non-pré-release, APK -release) ; test : dernière
            // release PUBLIÉE (pré-releases admises, APK -test, vérification
            // toutes les 30 min). Téléchargement via Tor puis installation
            // avec confirmation Android.
            net.nyx.messenger.update.Updater.currentVersion =
                net.nyx.messenger.BuildConfig.VERSION_NAME
            net.nyx.messenger.update.Updater.testChannel =
                net.nyx.messenger.BuildConfig.TEST_BUILD
            net.nyx.messenger.update.Updater.appContext = context.applicationContext
            val updateHandler = CoroutineExceptionHandler { _, e ->
                android.util.Log.w("nyx-update", "boucle mise à jour terminée : ${e.message}")
            }
            val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + updateHandler)
            updateScope.launch {
                net.nyx.messenger.update.Updater.autoLoop(
                    { net.nyx.messenger.tor.TorManager.activeSocksPort },
                    context.cacheDir
                )
            }

            initialized = true
        }
    }

    /**
     * v0.2.10 — POLL GLOBAL DE L'INBOX (v0.2.16 : boucle pilotée par l'état Tor) —
     * correctif de LIVRAISON. Jusqu'ici le poll ne vivait QUE dans l'écran de
     * conversation : un appareil sans conversation ouverte (ex. destinataire
     * d'une première demande de contact) ne récupérait JAMAIS ses événements —
     * messages et annonces de contact n'arrivaient jamais. La boucle vit au
     * niveau application :
     *  - tourne dès le lancement (no-op tant que Tor/l'identité ne sont pas
     *    prêts — pollInbox renvoie 0) ;
     *  - s'ajoute au poll fin (10 s) de l'écran de conversation ouvert ;
     *  - fonctionne au premier plan — aucune synchro en arrière-plan v0.1
     *    (process tué = pas de poll, cf. RESTART.md décision 17).
     *
     * v0.2.15 : 30 s → 15 s — retour terrain v0.2.14 (« temps de réception
     * d'invitation un peu longs ») : le délai perçu = intervalle de poll × 2
     * (dépôt chez l'invitant + récupération chez l'invité) + latence Tor.
     * La boucle porte AUSSI les accusés de réception/lecture (v0.2.15).
     *
     * v0.2.16 — DEUX correctifs du retour terrain « après fermeture/réouverture,
     * le client ne se connecte plus au réplica » (export Xiaomi 18:30:25 :
     * tor bootstrap 100 % en 2 s MAIS sonde /health « Connect timed out » 60 s) :
     *
     *  1. REPRISE RAPIDE : la boucle suit l'état de Tor (StateFlow). Chaque
     *     passage à Ready ouvre une fenêtre de 2 min à 5 s d'intervalle —
     *     les premières secondes après le bootstrap ne sont plus perdues (la
     *     boucle 15 s pouvait attendre un cycle complet avant la première
     *     vraie tentative). Hors Ready, AUCUNE tentative : les connexions
     *     lancées pendant le bootstrap pendaient jusqu'au timeout OkHttp
     *     (25 s depuis v0.2.16) et encombraient le pool de connexions.
     *
     *  2. LES ÉCHECS SONT TRACÉS : le runCatching silencieux ne disait RIEN
     *     de pourquoi le réplica restait injoignable — le diagnostic terrain
     *     reposait uniquement sur la sonde /health de l'écran Connexions.
     *     Désormais chaque échec est loggé nyx-poll (classe + message + durée)
     *     avec throttle : cause inchangée → 1 ligne/min, cause nouvelle → immédiat.
     *
     *  3. AUTO-GUÉRISON TOR (v0.2.17 — retour terrain v0.2.16 : « il faut
     *     parfois relancer l'app pour la connexion au réplica », export Xiaomi
     *     04:28 : 4× « Malformed reply from SOCKS server » en Tor Ready, relance
     *     app → health OK 4,6 s) : le démon tor se BLOQUE parfois en session
     *     (SOCKS cassé, circuits morts) alors que son état reste Ready. Trois
     *     échecs de TRANSPORT consécutifs (hors erreurs métier HTTP — le
     *     serveur répond, tor fonctionne) déclenchent un REDÉMARRAGE du démon
     *     ([TorManager.retry] — stopNow borné + relance fraîche, le remède que
     *     l'opérateur appliquait à la main), avec cooldown de 5 min pour ne
     *     jamais boucler.
     */
    private fun startInboxPolling() {
        val handler = CoroutineExceptionHandler { _, e ->
            android.util.Log.w("nyx-poll", "boucle inbox interrompue : ${e.message}")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            var nextPollAt = 0L
            var fastUntil = 0L          // fin de la fenêtre accélérée (elapsedRealtime)
            var lastFailKey: String? = null
            var lastFailLog = 0L
            // v0.2.25 : annonce du réplica embarqué — 0 = dès le premier tick Tor Ready.
            var nextAnnounceAt = 0L
            // v0.2.29 — cadence de ré-publication du profil (carte « replica ») :
            // 0 = dès le premier tick où les conditions sont réunies.
            var nextReplicaProfileAt = 0L
            // v0.2.26 : purge périodique des messages éphémères.
            var nextEphemeralSweepAt = 0L
            // v0.2.17 : compteur d'échecs de transport consécutifs (auto-guérison).
            var transportFails = 0
            var lastHealAt = 0L
            // v0.2.41 — ANTI-CHURN (ADR-017 §2.4, cause racine des logs 23/10 :
            // le heal tuait un démon FRAÎCHEMENT bootstrappé sur un réseau
            // simplement lent — mort des points d'introduction → cascade
            // « SOCKS server general failure »). On ne redémarre QUE si le
            // démon est Ready depuis longtemps ET que TOUT échoue encore.
            var readySinceMs = 0L
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val tor = net.nyx.messenger.tor.TorManager.get(appContext())
                val ready = tor.state.value is net.nyx.messenger.tor.TorManager.TorState.Ready
                if (ready && fastUntil == 0L) {
                    // Transition vers Ready (démarrage, retry, relance du démon).
                    fastUntil = now + FAST_WINDOW_MS
                    readySinceMs = now // v0.2.41 — âge du bootstrap pour l'anti-churn
                    android.util.Log.i("nyx-poll", "tor prêt — reprise rapide du poll")
                } else if (!ready) {
                    fastUntil = 0L      // tor retombe : la fenêtre n'a plus de sens
                    readySinceMs = 0L   // v0.2.41 — idem pour l'âge du Ready
                }
                if (ready && now >= nextPollAt) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    runCatching { repository.pollInbox() }
                        .onSuccess {
                            transportFails = 0
                        }
                        .onFailure { e ->
                            val t = android.os.SystemClock.elapsedRealtime()
                            val key = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
                            if (key != lastFailKey || t - lastFailLog > FAIL_THROTTLE_MS) {
                                lastFailKey = key
                                lastFailLog = t
                                android.util.Log.w("nyx-poll", "inbox échec (${t - t0} ms) : $key")
                            }
                            // v0.2.17 : une erreur MÉTIER (« HTTP 409/400 ») prouve
                            // que tor ET le réplica répondent — pas un cas de
                            // redémarrage du démon. Seuls les échecs de transport
                            // (SocketTimeout, SocketException, EOF…) comptent.
                            val transport = e.message?.contains("HTTP") != true
                            // v0.2.25 : FAILOVER réplica AVANT l'auto-guérison —
                            // un réplica de proximité peut être éteint (téléphone
                            // contact) : la bascule sur le suivant guérit sans
                            // redémarrer un démon tor sain. Le heal tor ne se
                            // déclenche que si TOUS les réplicas échouent.
                            if (transport) repository.markReplicaFailed("poll inbox : ${e.message?.take(80)}")
                            transportFails = if (transport) transportFails + 1 else 0
                            // v0.2.41 — GARDE ANTI-CHURN (ADR-017 §2.4) : le
                            // redémarrage du démon ne se déclenche PLUS sur un
                            // démon fraîchement bootstrappé (Ready < 10 min —
                            // il construit encore ses circuits, le réseau est
                            // simplement lent) : le tuer cassait ses points
                            // d'introduction et fabriquait la cascade « SOCKS
                            // server general failure » des logs 23/10 01:06.
                            // Décision pure partagée (TransportOrder, testée).
                            val readyForMs = if (readySinceMs > 0) now - readySinceMs else 0L
                            if (net.nyx.messenger.repo.TransportOrder.shouldHealTor(
                                    transportFails, readyForMs, now - lastHealAt,
                                    HEAL_THRESHOLD, HEAL_MIN_READY_MS, HEAL_COOLDOWN_MS
                                )
                            ) {
                                lastHealAt = android.os.SystemClock.elapsedRealtime()
                                transportFails = 0
                                android.util.Log.w(
                                    "nyx-tor-heal",
                                    "$HEAL_THRESHOLD échecs de transport consécutifs " +
                                        "(démon Ready depuis ${readyForMs / 1000} s) — " +
                                        "redémarrage du démon tor"
                                )
                                tor.retry()   // stopNow borné + relance fraîche (v0.2.9)
                            }
                        }
                    runCatching { repository.ensureContactNotified() }
                    // v0.2.14 : publication de profil FIABLE (demande opérateur :
                    // « vérifie que les infos publiques soient bien partagées
                    // entre les contacts ») — un profil rendu public alors que
                    // le réplica était injoignable ne partait JAMAIS.
                    runCatching { repository.ensureProfilePublished() }
                    // v0.2.15 : accusés de réception/lecture restés en attente
                    // (échec au flush inline — même retry que les annonces/acks).
                    runCatching { repository.ensureReceiptsSent() }
                    // v0.2.19 : demandes de suppression « pour tous » restées en
                    // attente (même mécanique de reprise que les accusés).
                    runCatching { repository.ensureDeletionsSent() }
                    // v0.2.26 : purge locale des messages éphémères (30 s).
                    if (now >= nextEphemeralSweepAt) {
                        nextEphemeralSweepAt = now + EPHEMERAL_SWEEP_MS
                        runCatching { repository.sweepEphemeral() }
                    }
                    // v0.2.25 — ANNONCE PÉRIODIQUE DU RÉPLICA EMBARQUÉ (build « test 2 ») :
                    // au premier tick Tor Ready puis toutes les 30 min, l'app annonce
                    // son réplica temporaire à ses contacts acceptés (type « contact »
                    // chiffré — cf. NyxRepository.announceReplica). Le destinataire
                    // persiste l'adresse et l'utilise en amorçage (ReplicaRegistry).
                    if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                        now >= nextAnnounceAt
                    ) {
                        nextAnnounceAt = now + ANNOUNCE_PERIOD_MS
                        val serving = net.nyx.messenger.replica.EmbeddedReplica.serving &&
                            net.nyx.messenger.replica.EmbeddedReplica.localPort > 0
                        val me = identity.onionAddress
                        // v0.2.29 — ANNONCE CONDITIONNÉE À LA PUBLICATION RÉELLE :
                        // annoncer « <onion>:80 » alors que l'ADD_ONION a échoué
                        // (ou n'a pas encore eu lieu) fabrique un réplica FANTÔME
                        // — le contact découvre une adresse SANS descripteur et
                        // obtient « Host unreachable » (cause racine terrain v0.2.28
                        // des réplicas injoignables ET des appels morts). On ne
                        // part que si TorManager a bien publié NOTRE adresse.
                        val published = tor.onionPublishedAddress
                        if (serving && me != null && published != null &&
                            published.equals(me, ignoreCase = true)
                        ) {
                            runCatching {
                                repository.announceReplica("$me:80")
                            }.onFailure { e ->
                                net.nyx.messenger.diag.DiagLog.w("nyx-replica", "annonce réplica échouée : ${e.message}")
                            }
                        } else if (serving && me != null) {
                            net.nyx.messenger.diag.DiagLog.i(
                                "nyx-replica",
                                "annonce réplica SAUTÉE : onion non publiée " +
                                    "(publiée=${published?.take(16) ?: "null"} ≠ nous=${me.take(16)}…) " +
                                    "— le watchdog TorManager republie"
                            )
                        }
                    }
                    // v0.2.29 — RÉPUBLICATION PÉRIODIQUE DU PROFIL PUBLIC (carte
                    // « replica ») : le champ réplica n'était inséré qu'À LA
                    // SAUVEGARDE du profil — un profil publié avant l'activation
                    // du réplica embarqué restait sans carte « replica » POUR
                    // TOUJOURS, et la console (/admin/replicas = agrégation des
                    // cartes de profil de moins de 7 jours) ne voyait JAMAIS les
                    // réplicas temporaires (« aucun ne s'affiche alors qu'ils
                    // sont découvrables »). Désormais : publication dès que le
                    // réplica sert ET que notre onion est publiée, puis toutes
                    // les 6 h (fraîcheur garantie face au TTL 7 jours côté
                    // relais) ; 10 min de délai de reprise après un échec.
                    if (net.nyx.messenger.BuildConfig.EMBEDDED_REPLICA &&
                        now >= nextReplicaProfileAt
                    ) {
                        val serving = net.nyx.messenger.replica.EmbeddedReplica.serving &&
                            net.nyx.messenger.replica.EmbeddedReplica.localPort > 0
                        val published = tor.onionPublishedAddress
                        if (serving && published != null) {
                            nextReplicaProfileAt = now + REPLICA_PROFILE_RETRY_MS
                            runCatching {
                                repository.publishProfile(
                                    net.nyx.messenger.profile.ProfileStore.get(appContext())
                                )
                            }.onSuccess {
                                nextReplicaProfileAt = now + REPLICA_PROFILE_PERIOD_MS
                                net.nyx.messenger.diag.DiagLog.i(
                                    "nyx-send",
                                    "✓ profil public (re)publié — carte réplica fraîche pour la console"
                                )
                            }.onFailure { e ->
                                net.nyx.messenger.diag.DiagLog.w("nyx-send", "✗ republication profil : ${e.message}")
                            }
                        }
                    }
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    nextPollAt = t1 + if (t1 < fastUntil) FAST_INTERVAL_MS else POLL_INTERVAL_MS
                }
                delay(POLL_TICK_MS)
            }
        }
    }

    private const val POLL_INTERVAL_MS = 15_000L
    private const val POLL_TICK_MS = 500L
    private const val FAST_INTERVAL_MS = 5_000L
    private const val FAST_WINDOW_MS = 120_000L
    private const val FAIL_THROTTLE_MS = 60_000L
    // v0.2.25 — annonce périodique du réplica embarqué aux contacts.
    private const val ANNOUNCE_PERIOD_MS = 30 * 60_000L
    // v0.2.29 — ré-publication du profil public (carte « replica » console) :
    // toutes les 6 h, reprise à 10 min après un échec.
    private const val REPLICA_PROFILE_PERIOD_MS = 6 * 3600_000L
    private const val REPLICA_PROFILE_RETRY_MS = 10 * 60_000L
    // v0.2.26 — purge locale des messages éphémères.
    private const val EPHEMERAL_SWEEP_MS = 30_000L
    // v0.2.17 — auto-guérison tor : seuil + cooldown.
    private const val HEAL_THRESHOLD = 5
    // v0.2.41 — ADR-017 §2.4 : un démon doit être Ready depuis ≥ 10 min avant
    // qu'un redémarrage soit envisagé (anti-churn), et le cooldown passe à 10 min.
    private const val HEAL_MIN_READY_MS = 10 * 60_000L
    private const val HEAL_COOLDOWN_MS = 10 * 60_000L

    /** Clé de base SQLCipher : 32 octets aléatoires, scellés via KeystoreBox dès le 1er lancement. */
    private fun dbPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("nyx_dbkey", Context.MODE_PRIVATE)
        val stored = prefs.getString("sealed", null)
        return if (stored != null) {
            net.nyx.messenger.crypto.KeystoreBox.open(Base64.decode(stored, Base64.DEFAULT))
        } else {
            val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(
                "sealed",
                Base64.encodeToString(net.nyx.messenger.crypto.KeystoreBox.seal(fresh), Base64.DEFAULT)
            ).apply()
            fresh
        }
    }
}
