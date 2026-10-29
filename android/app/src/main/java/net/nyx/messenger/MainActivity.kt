package net.nyx.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.security.AppLock
import net.nyx.messenger.settings.SettingsManager
import net.nyx.messenger.tor.TorManager
import net.nyx.messenger.ExitDialog
import net.nyx.messenger.ui.NyxNavHost
import net.nyx.messenger.ui.theme.NyxBackground
import net.nyx.messenger.ui.theme.NyxTheme

/**
 * Activité unique (single-activity) — navigation Compose :
 * onboarding → liste des conversations → chat (+ adresse / profil / paramètres).
 *
 * Démarrage du Tor embarqué (ADR-007) dès que l'identité existe : le démon tor
 * bootstrape pendant que l'utilisateur navigue. La notification persistante est
 * demandée (Android 13+) au premier lancement.
 *
 * Note (v0.1.2) : FragmentActivity (hérite de ComponentActivity) — requise par
 * BiometricPrompt pour le verrou biométrique opt-in (ADR-010 §3). Le passage en
 * arrière-plan re-verrouille ([AppLock.onBackgrounded]).
 *
 * v0.2.0 (session 12) :
 *  - thème piloté par le réglage utilisateur (système/clair/sombre) ;
 *  - fermeture : le bouton retour ouvre le dialogue « Réduire (Tor actif) /
 *    Quitter (tout arrêter proprement) » — [ExitController] — sauf si
 *    l'utilisateur a choisi un comportement permanent dans Paramètres.
 */
class MainActivity : FragmentActivity() {

    /** v0.2.61 — DEEP-LINK notification → conversation : pair demandé par le
     *  tap sur une notification (EXTRA_ONION). État mutable observé par
     *  [NyxNavHost] — la navigation n'a lieu qu'une fois l'app déverrouillée
     *  et passée la porte connexions. */
    private val openPeerFromIntent = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** v0.2.61 — extrait le pair d'un intent de notification (message/appel). */
    private fun peerFromIntent(intent: android.content.Intent?): String? {
        val peer = intent?.getStringExtra(net.nyx.messenger.notify.ContactNotifier.EXTRA_ONION)
        return peer?.takeIf { it.isNotBlank() }?.trim()?.lowercase()
    }

    /**
     * v0.2.53 — PERMISSIONS DE DÉMARRAGE EN UNE SEULE BOÎTE (demande
     * opérateur 25/10 : « il faudrait que les demandes d'autorisation soient
     * faites dès l'installation de l'application pour éviter ça » — la visio
     * ENTRANTE tombait en audio-only : Xiaomi « permission caméra absente —
     * visio audio-only » pendant l'appel du Blackview, la permission n'était
     * demandée qu'au clic visio côté APPELANT). Android ne permet pas de
     * demander PENDANT l'installation : on demande AU PREMIER LANCEMENT —
     * notification (13+), caméra et micro ensemble, puis à chaque lancement
     * tant qu'elles manquent (le système gère « ne plus demander »). Les
     * demandes au clic (ChatScreen, décrochage v0.2.53) restent en repli.
     * Refusées : Tor tourne quand même, les appels restent audibles.
     */
    private val startupPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cam = grants[Manifest.permission.CAMERA] == true
            val mic = grants[Manifest.permission.RECORD_AUDIO] == true
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-boot",
                "permissions de démarrage : caméra=" + (if (cam) "accordée" else "refusée") +
                    ", micro=" + (if (mic) "accordé" else "refusé") +
                    " — la visio entrante ne sera plus jamais dégradée par une permission manquante"
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.2.28 — INTERDICTION DES CAPTURES D'ÉCRAN (demande opérateur :
        // « je veux interdire les captures d'écran dans l'application ») :
        // FLAG_SECURE sur la fenêtre racine = captures d'écran et
        // enregistrements d'écran NOIRS, aperçu multi-tâches masqué.
        // Inconditionnel (toute l'app : chats, médias, appels, réglages).
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        requestStartupPermissionsIfNeeded()
        startEmbeddedTor()
        // Verrou d'accès (ADR-010 §3) : biométrie opt-in OU un des trois mots
        // de passe (v0.2.18 : normal/panique/destruction) — verrouillé à froid.
        val biometricOptIn = SettingsManager.get(this).biometricLock
        val passwordLock = net.nyx.messenger.security.PasswordVault.anySet(this)
        val identityExists = ServiceLocator.identity.exists
        android.util.Log.i(
            "nyx-lock", "onCreate : identité=$identityExists, opt-in=$biometricOptIn, mdp=$passwordLock, " +
                "mode=${ServiceLocator.mode}"
        )
        AppLock.init(identityExists && (biometricOptIn || passwordLock))

        // v0.2.61 — le tap sur une notification de message ouvre LA
        // CONVERSATION (avant : l'extra était ignoré — l'app s'ouvrait sur la
        // liste, le message était « lu » sans ouvrir le chat → jamais
        // d'accusé « lu » chez l'expéditeur ; terrain 29/10 : « accusé reçu
        // mais pas lu »).
        peerFromIntent(intent)?.let { openPeerFromIntent.value = it }

        // Fermeture (v0.2.0, rév. v0.2.9) : SANS identité (première étape de
        // connexion), le retour système QUITTE avec arrêt PROPRE du démon tor
        // (il tourne pourtant dès l'onboarding depuis v0.2.3 — l'ancien code
        // le laissait orphelin, d'où des tor fantômes au relancement) ;
        // avec identité, dialogue « réduire ou quitter » ([ExitController]).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val identityExists = ServiceLocator.initialized && ServiceLocator.identity.exists
                when {
                    !identityExists -> ExitController.exitCompletely(this@MainActivity)
                    !ExitController.handleBack(this@MainActivity) -> finish()
                }
            }
        })

        setContent {
            val settings = SettingsManager.get(this)
            val themeMode by settings.themeMode.collectAsState()
            NyxTheme(mode = themeMode) {
                // v0.2.35 — fond « ciel de nuit » dégradé derrière TOUT l'app
                // (refonte esthétique ; les Scaffold/LockScreen restent
                // transparents au-dessus).
                NyxBackground(mode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // v0.2.31 — CORRECTIF SYSTÉMATIQUE CLAVIER (demande
                        // opérateur : « vérifie systématiquement qu'on puisse
                        // voir le bas de la page quand le clavier est ouvert ») :
                        // imePadding() à la RACINE — tout le contenu se hisse
                        // au-dessus du clavier, sur TOUS les écrans (les
                        // imePadding imbriqués — Chat v0.2.15, dialogue contacts
                        // — ne doublent pas : les insets sont consommés).
                        .imePadding()
                ) {
                    NyxNavHost(
                        // Lien d'invitation entrant : nyx://add?o=<onion>&k=<alias>
                        initialAddRequest = intent?.data?.let { uri ->
                            if (uri.scheme == "nyx" && uri.host == "add") uri.getQueryParameter("o") else null
                        },
                        exitDialog = { ExitDialog(this@MainActivity) },
                        // v0.2.61 — deep-link notification → conversation.
                        openPeer = openPeerFromIntent
                    )
                }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLock.onBackgrounded()
    }

    /** v0.2.61 — notification tapée alors que l'activité vit déjà
     *  (FLAG_ACTIVITY_SINGLE_TOP) : le pair est extrait et transmis à la
     *  navigation SANS recréer l'activité. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        peerFromIntent(intent)?.let { openPeerFromIntent.value = it }
    }

    private fun requestStartupPermissionsIfNeeded() {
        // v0.2.53 — UNE SEULE boîte : notification (13+) + caméra + micro.
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.RECORD_AUDIO
        if (wanted.isNotEmpty()) {
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-boot",
                "permissions demandées au premier démarrage : " + wanted.joinToString() +
                    " — plus jamais de visio dégradée à la première utilisation"
            )
            startupPermissions.launch(wanted.toTypedArray())
        } else {
            net.nyx.messenger.diag.DiagLog.i(
                "nyx-boot", "permissions de démarrage déjà toutes accordées (notif, caméra, micro)"
            )
        }
    }

    /** Tor embarqué : démarrage dès que l'identité existe (sinon après l'onboarding). */
    private fun startEmbeddedTor() {
        val identity = net.nyx.messenger.di.ServiceLocator.identity
        if (identity.exists) {
            TorManager.get(this).start { identity.secretKey() }
        }
    }
}
