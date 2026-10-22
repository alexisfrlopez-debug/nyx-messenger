package net.nyx.messenger.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v0.2.14 — récepteur des ACTIONS de notification d'invitation
 * (« Accepter » / « Refuser » directement depuis la notification — demande
 * opérateur : « une notification avec le choix d'accepter ou refuser »).
 *
 * goAsync() : le travail réseau (dépôt de l'ack signé via Tor) peut prendre
 * plusieurs secondes — le récepteur reste vivant jusqu'au finish(). L'appel
 * passe par le [net.nyx.messenger.di.ServiceLocator] (initialisé par
 * l'application, dont le processus est maintenu par le service Tor premier
 * plan + la boucle de poll).
 *
 * Après acceptation : ack signé déposé (ou différé — reparti par la boucle
 * 30 s), notification annulée. Non exporté (manifest) : seuls nos propres
 * PendingIntents peuvent le déclencher.
 */
class ContactActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val onion = intent.getStringExtra(ContactNotifier.EXTRA_ONION) ?: return
        val accept = intent.action == ContactNotifier.ACTION_ACCEPT
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = net.nyx.messenger.di.ServiceLocator.repository
                val res = if (accept) repo.acceptContact(onion) else repo.declineContact(onion)
                android.util.Log.i(
                    "nyx-notify",
                    "action notification ${if (accept) "accepter" else "refuser"} " +
                        "${onion.take(16)}… : ${res.fold({ "OK" }, { "échec ${it.message}" })}"
                )
            } catch (e: Exception) {
                android.util.Log.w("nyx-notify", "action notification échouée : ${e.message}")
            } finally {
                ContactNotifier.cancelInvitation(onion)
                result.finish()
            }
        }
    }
}
