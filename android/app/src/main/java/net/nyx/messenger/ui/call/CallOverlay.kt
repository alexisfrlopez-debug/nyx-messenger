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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import net.nyx.messenger.p2p.VideoFit

/**
 * v0.2.18 — ÉCRAN D'APPEL (ADR-015) : composé GLOBALEMENT (NyxNavHost,
 * au-dessus de la navigation) tant qu'un appel est en cours — sonnerie
 * sortante/entrante, connexion, conversation, fin. Latence Tor annoncée
 * honnêtement (ADR-010/015) : 0,5-3 s bout en bout, prix de l'anonymat.
 *
 * v0.2.53 — LA VIDÉO SUIT LE TÉLÉPHONE (terrain 25/10 08:41 : « l'image video
 * a une orientation d'écran figée ») : les DEUX vues sont des TextureView
 * dont la MATRICE porte la rotation ([VideoFit] — rotation + fit centré,
 * letterbox implicite sur fond noir). Le flux H.264 et le décodeur ne
 * changent JAMAIS quand le téléphone tourne : seule la matrice change —
 * aucune coupure, aucune resynchronisation. La rotation LOCALE (aperçu) et
 * celle du PAIR (vue distante) arrivent par l'état [CallManager.ui]
 * (videoLocalRot / videoPeerRot, mises à jour par F_VIDEO_ROT en direct).
 */
@Composable
fun CallOverlay() {
    val ui by CallManager.ui.collectAsState()
    if (ui.phase == CallManager.Phase.IDLE) return

    // v0.2.48 — PERMISSION MICRO AU DÉCROCHAGE (terrain 24/10 : elle n'était
    // JAMAIS demandée — l'appelé décrochait puis n'était pas entendu ;
    // CallAudio dégrade en réception seule avec cause journalisée, mais on
    // la demande au moment où l'utilisateur exprime l'intention de parler).
    // v0.2.53 — CAMÉRA EN MÊME TEMPS (terrain 25/10 : la visio ENTRANTE
    // tombait en audio-only faute de permission caméra jamais demandée côté
    // appelé — demandée désormais AU DÉMARRAGE, ici en repli au décrochage,
    // les DEUX en une seule boîte) : l'appel continue de sonner pendant la
    // demande ; réponse → décrochage immédiat, même si refusées.
    val ansCtx = androidx.compose.ui.platform.LocalContext.current
    val answerPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { CallManager.acceptIncoming() }

    // v0.2.60 — PARTAGE D'ÉCRAN VALIDÉ : le lancement MediaProjection exige
    // une ACTIVITÉ (dialogue système) — le moteur d'appel passe par
    // [CallManager.screenShareStarter], câblé ICI. La capture ne part qu'après
    // le F_SCREEN_ACCEPT du pair ET ce consentement système (les DEUX côtés).
    val projectionPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
            net.nyx.messenger.p2p.screenshare.ScreenShare.grant(ansCtx, res.resultCode, res.data!!)
            CallManager.onScreenProjectionGranted(true)
        } else {
            CallManager.onScreenProjectionGranted(false)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        CallManager.screenShareStarter = { onResult ->
            val ctx = ansCtx
            val mgr = ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            CallManager.setPendingProjectionResult(onResult)
            projectionPerm.launch(mgr.createScreenCaptureIntent())
        }
    }

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
        // v0.2.53 — les deux deviennent des TextureView à MATRICE (rotation).
        if (ui.phase == CallManager.Phase.ACTIVE && ui.videoEnabled) {
            // ----- Vue DISTANTE (le pair) : matrice videoPeerRot/videoPeerW/H -----
            var remoteW by remember { mutableStateOf(0) }
            var remoteH by remember { mutableStateOf(0) }
            var remoteSt by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }
            var remoteBufKey by remember { mutableStateOf("") }
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) {
                                remoteW = w; remoteH = h
                                remoteSt = st
                                // Buffer à la taille d'encodage du pair : le décodeur
                                // rend 1:1, la matrice met à l'échelle (image nette).
                                if (ui.videoPeerW > 0 && ui.videoPeerH > 0) {
                                    runCatching { st.setDefaultBufferSize(ui.videoPeerW, ui.videoPeerH) }
                                }
                                CallManager.setRemoteSurface(android.view.Surface(st))
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) { remoteW = w; remoteH = h }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                remoteSt = null
                                CallManager.setRemoteSurface(null); return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                update = { tv ->
                    val st = remoteSt
                    if (st != null && ui.videoPeerW > 0 && ui.videoPeerH > 0) {
                        val key = "${ui.videoPeerW}x${ui.videoPeerH}"
                        if (key != remoteBufKey) {
                            remoteBufKey = key
                            runCatching { st.setDefaultBufferSize(ui.videoPeerW, ui.videoPeerH) }
                        }
                    }
                    // Rotation du pair (HELLO/ACCEPT puis F_VIDEO_ROT en direct) :
                    // matrice SANS miroir — l'image du pair est vue telle qu'il l'a cadrée.
                    if (remoteW > 0 && remoteH > 0 && ui.videoPeerW > 0 && ui.videoPeerH > 0) {
                        applyVideoTransform(tv, remoteW, remoteH, ui.videoPeerW, ui.videoPeerH, ui.videoPeerRot, false)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // ----- APERÇU LOCAL (ma caméra) : matrice videoLocalRot/W/H + MIROIR front -----
            var prevW by remember { mutableStateOf(0) }
            var prevH by remember { mutableStateOf(0) }
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) {
                                prevW = w; prevH = h
                                CallManager.setPreviewSurface(android.view.Surface(st))
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) { prevW = w; prevH = h }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                CallManager.setPreviewSurface(null); return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                update = { tv ->
                    // Miroir si caméra FRONT (l'utilisateur se voit comme dans un
                    // miroir ; le flux ENVOYÉ reste non miroir — standard).
                    if (prevW > 0 && prevH > 0 && ui.videoLocalW > 0 && ui.videoLocalH > 0) {
                        applyVideoTransform(tv, prevW, prevH, ui.videoLocalW, ui.videoLocalH, ui.videoLocalRot, ui.videoLocalFront)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp)
                    .size(110.dp, 150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black) // letterbox noir dans le cadre arrondi
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
            // v0.2.59 — émission locale coupée (caméra perdue) mais réception
            // maintenue : dit LE pourquoi de l'aperçu noir (terrain 28/10 :
            // « je n'ai pas eu de video » — la vue distante reste, la locale
            // seule est morte).
            if (ui.phase == CallManager.Phase.ACTIVE && ui.videoEnabled && ui.videoSendLost) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Votre caméra est coupée — vous recevez toujours la vidéo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            // v0.2.60 — messages du protocole de partage d'écran (refus,
            // validation, révocation, attente).
            if (ui.phase == CallManager.Phase.ACTIVE && ui.screenNotice.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ui.screenNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            // v0.2.60 — DEMANDE DU PC (F_SCREEN_REQ) : validation ✓/✕ AVANT que
            // son écran n'arrive chez nous (partage VALIDÉ des 2 côtés).
            if (ui.phase == CallManager.Phase.ACTIVE && ui.screenShareRequest) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Le PC demande à\npartager son écran",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Button(
                        onClick = { CallManager.answerScreenShare(true) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        )
                    ) { androidx.compose.material3.Icon(Icons.Filled.Check, null) }
                    Button(
                        onClick = { CallManager.answerScreenShare(false) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { androidx.compose.material3.Icon(Icons.Filled.Close, null) }
                }
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
                        // v0.2.53 — caméra demandée EN MÊME TEMPS : la visio
                        // entrante ne dépend plus d'une permission jamais vue.
                        val missing = mutableListOf<String>()
                        if (ansCtx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) missing += android.Manifest.permission.RECORD_AUDIO
                        if (ansCtx.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) missing += android.Manifest.permission.CAMERA
                        if (missing.isEmpty()) CallManager.acceptIncoming()
                        else answerPerm.launch(missing.toTypedArray())
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
                        // v0.2.60 — PARTAGE D'ÉCRAN VALIDÉ : demande au pair
                        // (F_SCREEN_REQ) ; la capture ne démarre qu'à SON
                        // accept ET au dialogue système (les DEUX validations).
                        CircleButton(
                            icon = Icons.Filled.ScreenShare,
                            tint = if (ui.screenSharing) MaterialTheme.colorScheme.tertiary else Color.White,
                            bg = Color(0xFF444444),
                            desc = "Partage d'écran (validé par le pair)"
                        ) { CallManager.requestScreenShare() }
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

/**
 * v0.2.53 — Applique la matrice de présentation ([VideoFit] : rotation
 * horaire + fit centré + miroir optionnel) à un TextureView vidéo.
 */
private fun applyVideoTransform(
    tv: android.view.TextureView,
    viewW: Int, viewH: Int,
    bufW: Int, bufH: Int,
    rotationDeg: Int,
    mirror: Boolean,
) {
    val m = android.graphics.Matrix()
    m.setValues(VideoFit.values(viewW, viewH, bufW, bufH, rotationDeg, mirror))
    tv.setTransform(m)
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
