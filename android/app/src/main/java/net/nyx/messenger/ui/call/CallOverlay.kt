package net.nyx.messenger.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.nyx.messenger.R
import net.nyx.messenger.p2p.CallManager

/**
 * v0.2.18 — ÉCRAN D'APPEL (ADR-015) : composé GLOBALEMENT (NyxNavHost,
 * au-dessus de la navigation) tant qu'un appel est en cours — sonnerie
 * sortante/entrante, connexion, conversation, fin. Latence Tor annoncée
 * honnêtement (ADR-010/015) : 0,5-3 s bout en bout, prix de l'anonymat.
 */
@Composable
fun CallOverlay() {
    val ui by CallManager.ui.collectAsState()
    if (ui.phase == CallManager.Phase.IDLE) return

    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.phase, ui.startedAt) {
        seconds = 0
        while (ui.phase == CallManager.Phase.ACTIVE) {
            seconds = (System.currentTimeMillis() - ui.startedAt) / 1000
            delay(500)
        }
    }

    val statusText = when (ui.phase) {
        CallManager.Phase.OUTGOING -> stringResource(R.string.call_status_ringing)
        CallManager.Phase.INCOMING -> stringResource(R.string.call_status_incoming)
        CallManager.Phase.WAIT -> stringResource(R.string.call_status_connecting)
        CallManager.Phase.ACTIVE -> {
            val m = seconds / 60
            val s = seconds % 60
            "%d:%02d".format(m, s)
        }
        CallManager.Phase.ENDED -> ui.message
        else -> ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f))
            .padding(24.dp)
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                ui.alias.ifBlank { ui.peer.take(12) + "…" },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f)
            )
            if (ui.phase == CallManager.Phase.ACTIVE && ui.muted) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.call_muted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            when (ui.phase) {
                CallManager.Phase.INCOMING -> {
                    // Refuser (rouge) / Accepter (vert).
                    CircleButton(
                        icon = Icons.Filled.Close,
                        tint = Color.White,
                        bg = MaterialTheme.colorScheme.error,
                        desc = stringResource(R.string.call_reject)
                    ) { CallManager.rejectIncoming() }
                    CircleButton(
                        icon = Icons.Filled.Call,
                        tint = Color.White,
                        bg = Color(0xFF2E7D32),
                        desc = stringResource(R.string.call_accept)
                    ) { CallManager.acceptIncoming() }
                }
                CallManager.Phase.ACTIVE -> {
                    CircleButton(
                        icon = if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        tint = if (ui.muted) MaterialTheme.colorScheme.error else Color.White,
                        bg = Color(0xFF444444),
                        desc = stringResource(R.string.call_mute)
                    ) { CallManager.toggleMute() }
                    CircleButton(
                        icon = Icons.Filled.CallEnd,
                        tint = Color.White,
                        bg = MaterialTheme.colorScheme.error,
                        desc = stringResource(R.string.call_hangup)
                    ) { CallManager.hangup() }
                    CircleButton(
                        icon = Icons.Filled.VolumeUp,
                        tint = if (ui.speaker) MaterialTheme.colorScheme.tertiary else Color.White,
                        bg = Color(0xFF444444),
                        desc = stringResource(R.string.call_speaker)
                    ) { CallManager.toggleSpeaker() }
                }
                else -> {
                    // Sonnerie (sortante), connexion, fin : raccrocher/fermer.
                    CircleButton(
                        icon = Icons.Filled.CallEnd,
                        tint = Color.White,
                        bg = MaterialTheme.colorScheme.error,
                        desc = stringResource(R.string.call_hangup)
                    ) {
                        if (ui.phase == CallManager.Phase.INCOMING) CallManager.rejectIncoming()
                        else CallManager.hangup()
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    bg: Color,
    desc: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bg)
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(32.dp))
    }
}
