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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.viewinterop.AndroidView
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

    // v0.2.48 — PERMISSION MICRO AU DÉCROCHAGE (terrain 24/10 : elle n'était
    // JAMAIS demandée — l'appelé décrochait puis n'était pas entendu ;
    // CallAudio dégrade en réception seule avec cause journalisée, mais on
    // la demande au moment où l'utilisateur exprime l'intention de parler).
    val ansCtx = androidx.compose.ui.platform.LocalContext.current
    val answerPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { CallManager.acceptIncoming() }

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
            .background(Color.Black)
            .padding(24.dp)
    ) {
        // v0.2.20 — VISIO : rendu DISTANT plein écran + APERÇU local en
        // picture-in-picture quand la vidéo est négociée et l'appel actif.
        if (ui.phase == CallManager.Phase.ACTIVE && ui.videoEnabled) {
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                                CallManager.setRemoteSurface(h.surface)
                            }
                            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                                CallManager.setRemoteSurface(null)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) { CallManager.setPreviewSurface(android.view.Surface(st)) }
                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                CallManager.setPreviewSurface(null); return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp)
                    .size(110.dp, 150.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Column(
            Modifier.align(
                if (ui.phase == CallManager.Phase.ACTIVE && ui.videoEnabled) Alignment.TopCenter
                else Alignment.Center
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!(ui.phase == CallManager.Phase.ACTIVE && ui.videoEnabled)) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
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
                    ) {
                        // v0.2.48 — micro demandé avant de décrocher (l'appel
                        // continue de sonner pendant la demande ; réponse →
                        // décrochage immédiat, même si refusée).
                        if (ansCtx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) CallManager.acceptIncoming()
                        else answerPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                CallManager.Phase.ACTIVE -> {
                    CircleButton(
                        icon = if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        tint = if (ui.muted) MaterialTheme.colorScheme.error else Color.White,
                        bg = Color(0xFF444444),
                        desc = stringResource(R.string.call_mute)
                    ) { CallManager.toggleMute() }
                    if (ui.videoEnabled) {
                        CircleButton(
                            icon = Icons.Filled.Videocam,
                            tint = Color.White,
                            bg = Color(0xFF444444),
                            desc = stringResource(R.string.call_camera_toggle)
                        ) { CallManager.toggleCamera() }
                    }
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
