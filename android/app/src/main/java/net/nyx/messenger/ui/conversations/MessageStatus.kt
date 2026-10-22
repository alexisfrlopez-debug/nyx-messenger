package net.nyx.messenger.ui.conversations

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.nyx.messenger.R
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.repo.NyxRepository

/**
 * v0.2.15 — ÉTIQUETTE D'ÉTAT D'UN MESSAGE SORTANT (demande opérateur :
 * « un accusé de réception et un accusé de lecture côté expéditeur… dans la
 * conversation elle-même en dessous du dernier message » et « dans la page
 * conversation à côté des alias »).
 *
 * CÔTÉ EXPÉDITEUR (state + accusés horodatés par les receipts du pair) :
 *   pending → « Envoi via Tor… » ; failed → « Échec » ;
 *   sent    → « ✓ Envoyé » (déposé au réplica) ;
 *   sent+deliveredAt → « ✓✓ Reçu » (le pair a REÇU) ;
 *   sent+readAt      → « ✓✓ Lu » (le pair a LU).
 * CÔTÉ DESTINATAIRE : null (rien sous un message reçu — l'indicateur non-lu
 * vit dans la liste : point + compteur, effacé à l'ouverture du chat).
 *
 * Partagé par [ChatScreen] (sous chaque bulle sortante) et
 * [ConversationListScreen] (à côté de l'aperçu du dernier message).
 */
@Composable
fun outgoingStatusLabel(m: MessageEntity): String? {
    if (!m.outgoing) return null
    return when {
        m.state == NyxRepository.STATE_PENDING -> stringResource(R.string.sending)
        m.state == NyxRepository.STATE_FAILED -> stringResource(R.string.failed)
        m.readAt > 0L -> stringResource(R.string.msg_state_read)
        m.deliveredAt > 0L -> stringResource(R.string.msg_state_delivered)
        else -> stringResource(R.string.sent) // déposé au réplica, accusé en attente
    }
}
