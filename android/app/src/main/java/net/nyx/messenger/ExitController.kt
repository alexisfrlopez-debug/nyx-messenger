package net.nyx.messenger

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.nyx.messenger.settings.SettingsManager
import net.nyx.messenger.tor.TorManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Contrôle de fermeture de l'app (v0.2.0) — demande de l'opérateur (session 12) :
 * quand l'utilisateur ferme l'application, lui demander s'il veut
 *  1. la RÉDUIRE : Tor reste actif, notifications actives, la messagerie
 *     continue de recevoir en arrière-plan (service au premier plan ADR-007) ;
 *  2. la QUITTER COMPLÈTEMENT : tous les processus — Tor compris — sont tués
 *     PROPREMENT (arrêt du service au premier plan → destruction du démon tor
 *     enfant via onDestroy, puis fin de la tâche).
 *
 * Le réglage [SettingsManager.CloseBehavior] court-circuite le dialogue quand
 * l'utilisateur a fait un choix permanent (modifiable dans Paramètres).
 */
object ExitController {

    /** État du dialogue (mutableStateOf → recomposition Compose automatique). */
    var showDialog by mutableStateOf(false)

    /**
     * Appelé par le bouton retour système : décider selon le réglage.
     * Retourne true si l'événement est consommé (dialogue affiché, réduire,
     * ou quitter) ; false si l'activité doit se terminer normalement
     * (ex. onboarding sans identité).
     */
    fun handleBack(activity: android.app.Activity): Boolean {
        val settings = SettingsManager.get(activity)
        return when (settings.closeBehavior.value) {
            SettingsManager.CloseBehavior.MINIMIZE -> { minimize(activity); true }
            SettingsManager.CloseBehavior.EXIT -> { exitCompletely(activity); true }
            SettingsManager.CloseBehavior.ASK -> {
                showDialog = true
                true
            }
        }
    }

    /** Réduire : la tâche part en arrière-plan, Tor + notifications restent actifs. */
    fun minimize(activity: android.app.Activity) {
        showDialog = false
        activity.moveTaskToBack(true)
    }

    /**
     * Quitter complètement et PROPREMENT (v0.2.9) :
     *  1. arrêt BLOQUANT du démon tor ([TorManager.stopNow] — SIGTERM, attente
     *     bornée, SIGKILL : verrou DataDirectory + ports libérés AVANT de
     *     quitter ; c'est la fermeture propre exigée dès la première étape) ;
     *  2. arrêt du service au premier plan (inclus dans stopNow) ;
     *  3. fin de la tâche puis sortie du processus (aucun thread résiduel).
     * Le kill borné est hors thread UI — l'activité reste réactive pendant.
     */
    fun exitCompletely(activity: android.app.Activity) {
        showDialog = false
        val appContext = activity.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { TorManager.get(appContext).stopNow() }
            activity.finishAffinity()
            Runtime.getRuntime().exit(0)
        }
    }

}

/**
 * Dialogue de fermeture — version câblée à l'activité (les boutons ont besoin
 * de l'activité pour moveTaskToBack/finishAffinity).
 */
@Composable
fun ExitDialog(activity: android.app.Activity) {
    if (!ExitController.showDialog) return
    AlertDialog(
        onDismissRequest = { ExitController.showDialog = false },
        title = { Text(stringResource(R.string.exit_dialog_title)) },
        text = {
            Text(
                stringResource(R.string.exit_dialog_message),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { ExitController.minimize(activity) }) {
                Text(stringResource(R.string.exit_dialog_minimize))
            }
        },
        dismissButton = {
            TextButton(onClick = { ExitController.exitCompletely(activity) }) {
                Text(stringResource(R.string.exit_dialog_exit))
            }
        }
    )
}
