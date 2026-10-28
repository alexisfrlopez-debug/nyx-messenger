package net.nyx.messenger.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.nyx.messenger.p2p.CallManager

/**
 * v0.2.56 — ACTIONS DÉCROCHER/REFUSER DEPUIS LA NOTIFICATION (session 78 :
 * demande opérateur « pouvoir décrocher ou rejeter un appel quand le
 * téléphone est verrouillé sans devoir tout déverrouiller »).
 *
 * Ces actions fonctionnent SUR L'ÉCRAN VERROUILLÉ : le geste part de la
 * notification même (Android autorise les PendingIntents d'action de
 * notification au-dessus du keyguard — pas de déverrouillage système ni
 * d'application). Le process vit (service Tor au premier plan + poll au
 * niveau application), le média est indépendant de l'UI : l'appel s'établit
 * sans OUVRIR l'application.
 *
 * Sécurité : le receiver est exported=false — seuls NOS PendingIntents le
 * déclenchent. Aucune donnée de conversation n'est exposée.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val onion = intent.getStringExtra(ContactNotifier.EXTRA_ONION).orEmpty()
        when (intent.action) {
            ContactNotifier.ACTION_CALL_ANSWER -> {
                ContactNotifier.cancelCall(onion)
                CallManager.acceptIncoming()
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-call", "décroché DEPUIS LA NOTIFICATION (verrouillé — session 78)"
                )
            }
            ContactNotifier.ACTION_CALL_DECLINE -> {
                ContactNotifier.cancelCall(onion)
                CallManager.rejectIncoming()
                net.nyx.messenger.diag.DiagLog.i(
                    "nyx-call", "refusé DEPUIS LA NOTIFICATION (verrouillé — session 78)"
                )
            }
        }
    }
}
