package net.nyx.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import net.nyx.messenger.notify.DesktopNotifier
import net.nyx.messenger.p2p.DesktopCallManager
import org.jetbrains.skia.Image.Companion.makeFromEncoded

/**
 * v0.3.11 — FENÊTRE D'APPEL FLOTTANTE, TOUJOURS AU PREMIER PLAN PENDANT TOUT
 * L'APPEL (demande opérateur session 81, verbatim : « lors d'un appel qui
 * abouti sur pc, je veux la boite de décrochage de l'appel toujours au
 * premier plan, et un bouton pour raccrocher toujours au premier plan jusqu'à
 * la fin de l'appel »).
 *
 * Terrain 28/10 : la carte de décrochage ([IncomingCallWindow]) disparaissait
 * au décroché et l'overlay d'appel vit DANS la fenêtre principale — dès que
 * celle-ci passait SOUS une autre application, PLUS AUCUN raccroché n'était
 * atteignable.
 *
 * Cette carte prend le relais de la sonnerie et couvre TOUT l'appel :
 *  - INCOMING : la carte dédiée plein centre ([IncomingCallWindow]) reste le
 *    panneau principal — cette carte flottante n'apparaît PAS (pas de doublon) ;
 *  - OUTGOING/WAIT/ACTIVE : carte compacte EN BAS À DROITE de l'écran —
 *    alias, durée vivante, VIDÉO DU PAIR EN DIRECT (v0.3.14), micro, bascule
 *    caméra/écran (visio), ANNULER/ARRÊTER le partage d'écran (v0.3.14) et
 *    surtout RACCROCHER — au-dessus de TOUT, cliquable en toutes circonstances ;
 *  - ENDED : la carte se referme (le motif bref vit dans l'overlay principal).
 *
 * v0.3.14 — LA CARTE EST DEVENUE LE POSTE DE VISIO (retour terrain 29/10 :
 * « côté pc je ne vois pas les videos » — la vidéo ne vivait QUE dans l'overlay
 * de la fenêtre principale, invisible dès qu'elle passait en arrière-plan,
 * PRÉCISÉMENT la posture pour laquelle cette carte existe) : aperçu du pair
 * rafraîchi ~6 fps, clic = ramener la fenêtre principale (plein écran) ; le
 * dialogue ✓/✕ de PARTAGE D'ÉCRAN vit AUSSI ici (« je n'ai pas non plus de
 * notification pour le partage d'écran ») + les boutons ANNULER (demande en
 * attente) et ARRÊTER (partage actif) (« besoin aussi d'un bouton pour
 * annuler le partage d'écran lorsqu'il est proposé ou activé »).
 *
 * Fermer la carte (Alt+F4) pendant un appel actif = raccrocher : jamais d'état
 * coincé, jamais d'appel fantôme. Elle ne montre que l'alias — JAMAIS de
 * contenu de message (miroir IncomingCallWindow/CallLockActivity).
 */
@Composable
fun CallFloatWindow() {
    val ui by DesktopCallManager.ui.collectAsState()
    // La carte flottante vit de la PREMIÈRE tonalité à la fin d'appel — sauf
    // pendant la sonnerie entrante (la carte DÉDIÉE centrée IncomingCallWindow
    // joue ce rôle, pas de doublon visuel).
    val ringing = ui.phase == DesktopCallManager.Phase.INCOMING
    val alive = ui.phase != DesktopCallManager.Phase.IDLE && ui.phase != DesktopCallManager.Phase.ENDED
    if (!alive || ringing) return

    // Durée vivante de l'appel actif (miroir CallOverlay).
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.phase, ui.startedAt) {
        while (ui.phase == DesktopCallManager.Phase.ACTIVE) {
            elapsed = (System.currentTimeMillis() - ui.startedAt) / 1000
            delay(1000)
        }
    }

    // v0.3.14 — APERÇU DU PAIR EN DIRECT : poll du moteur vidéo (miroir
    // CallOverlay — le JPEG est décodé par skia, l'aspect vit dans la trame).
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameVersion by remember { mutableStateOf(0L) }
    LaunchedEffect(ui.phase, ui.videoEnabled) {
        while (ui.phase == DesktopCallManager.Phase.ACTIVE && ui.videoEnabled) {
            val engine = DesktopCallManager.remoteVideo
            val jpeg = engine?.latestJpeg
            val v = engine?.frameVersion ?: 0L
            if (jpeg != null && v != frameVersion) {
                frameVersion = v
                val decoded = runCatching { makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
                if (decoded != null) frame = decoded
            }
            delay(160)
        }
        frame = null
    }

    // Hauteur adaptée : vidéo (~172 dp) et/ou dialogue de partage visibles.
    val showVideo = ui.phase == DesktopCallManager.Phase.ACTIVE && ui.videoEnabled
    val cardH = 210.dp + (if (showVideo) 178.dp else 0.dp) +
        (if (ui.screenShareRequest || ui.screenSharePending || ui.videoIsScreen || ui.peerScreenShare) 54.dp else 0.dp)

    Window(
        title = "Nyx — appel en cours",
        state = rememberWindowState(
            position = WindowPosition(Alignment.BottomEnd),
            width = 300.dp, height = cardH,
        ),
        alwaysOnTop = true,
        resizable = false,
        undecorated = true,
        transparent = true,
        icon = nyxWindowIcon(),
        onCloseRequest = { DesktopCallManager.hangup("raccroché (carte flottante)") },
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Color(0xF70B0820), RoundedCornerShape(14.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(34.dp)
                        .background(Color(0xFF232043), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🌙", fontSize = 16.sp) }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        ui.alias.ifBlank { ui.peer.take(10) + "…" },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Text(
                        when (ui.phase) {
                            DesktopCallManager.Phase.OUTGOING -> "appel sortant…"
                            DesktopCallManager.Phase.WAIT -> "connexion…"
                            DesktopCallManager.Phase.ACTIVE ->
                                if (ui.videoEnabled) "visio active — ${formatDuration(elapsed)}"
                                else "appel actif — ${formatDuration(elapsed)}"
                            else -> ""
                        },
                        fontSize = 11.sp, color = Color(0xCCB8A6),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // v0.3.14 — VIDÉO DU PAIR SUR LA CARTE (la cause racine du
            // « côté pc je ne vois pas les videos ») : l'aperçu vit ICI,
            // toujours au premier plan ; un clic ramène la fenêtre principale
            // (plein écran, réglages de l'appel).
            if (showVideo) {
                val f = frame
                val fa = if (f != null) f.width.toFloat() / f.height else 4f / 3f
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio(fa)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF000000))
                        .clickable { DesktopNotifier.activateWindow() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (f != null) {
                        Image(
                            bitmap = f,
                            contentDescription = "caméra du pair",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("caméra du pair…", fontSize = 10.sp, color = Color(0x998F7E))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // v0.3.14 — DIALOGUE PARTAGE D'ÉCRAN DU PAIR (F_SCREEN_REQ) :
            // il vivait uniquement dans l'overlay de la fenêtre principale —
            // invisible en arrière-plan (« pas de notification pour le
            // partage d'écran »). ICI il est TOUJOURS au premier plan.
            if (ui.phase == DesktopCallManager.Phase.ACTIVE && ui.screenShareRequest) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${ui.alias.ifBlank { "Le pair" }}\npartage son écran ?",
                        fontSize = 10.sp, color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { DesktopCallManager.answerScreenShare(true) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32), contentColor = Color.White,
                        ),
                        modifier = Modifier.size(width = 52.dp, height = 34.dp),
                    ) { Text("✓", fontSize = 14.sp) }
                    Button(
                        onClick = { DesktopCallManager.answerScreenShare(false) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD94343), contentColor = Color.White,
                        ),
                        modifier = Modifier.size(width = 52.dp, height = 34.dp),
                    ) { Text("✕", fontSize = 14.sp) }
                }
                Spacer(Modifier.height(8.dp))
            }

            // v0.3.14 — ANNULER NOTRE demande en attente / ARRÊTER le partage
            // actif (« besoin aussi d'un bouton pour annuler le partage
            // d'écran lorsqu'il est proposé ou activé »).
            if (ui.phase == DesktopCallManager.Phase.ACTIVE &&
                (ui.screenSharePending || ui.videoIsScreen || ui.peerScreenShare)) {
                if (ui.screenSharePending) {
                    Button(
                        onClick = { DesktopCallManager.cancelScreenShareRequest() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8D6E63), contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                    ) { Text("Annuler la demande", fontSize = 11.sp) }
                } else {
                    Button(
                        onClick = { DesktopCallManager.stopScreenShare() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5E35B1), contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                    ) { Text(if (ui.videoIsScreen) "Arrêter mon partage" else "Arrêter le partage du pair", fontSize = 11.sp) }
                }
                Spacer(Modifier.height(8.dp))
            }

            // v0.3.14 — message transitoire du protocole de partage (refus,
            // validation, révocation) visible SANS la fenêtre principale.
            if (ui.phase == DesktopCallManager.Phase.ACTIVE && ui.screenNotice.isNotEmpty()) {
                Text(
                    ui.screenNotice,
                    fontSize = 9.sp, color = Color(0xCCB8A6),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }

            // RACCROCHER — le bouton central de la demande : TOUJOURS au
            // premier plan, jusqu'à la FIN de l'appel.
            Button(
                onClick = { DesktopCallManager.hangup() },
                modifier = Modifier.size(width = 220.dp, height = 46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD94343),
                    contentColor = Color.White,
                ),
            ) { Text("Raccrocher", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { DesktopCallManager.toggleMute() },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ui.muted) Color(0x6D4C41) else Color(0xFF37474F),
                        contentColor = Color.White,
                    ),
                ) { Text(if (ui.muted) "🔇" else "🎤", fontSize = 13.sp) }
                if (ui.videoEnabled) {
                    Button(
                        onClick = { DesktopCallManager.toggleVideoSource() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.videoIsScreen) Color(0xFF5E35B1) else Color(0xFF37474F),
                            contentColor = Color.White,
                        ),
                    ) { Text(if (ui.videoIsScreen) "🖥️" else "📹", fontSize = 13.sp) }
                    Button(
                        onClick = { DesktopCallManager.toggleVideo() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.videoPaused) Color(0x6D4C41) else Color(0xFF37474F),
                            contentColor = Color.White,
                        ),
                    ) { Text(if (ui.videoPaused) "▶️" else "⏸", fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Carte d'appel — toujours au premier plan",
                fontSize = 9.sp, color = Color(0x998F7E),
            )
        }
    }
}

private fun formatDuration(s: Long): String {
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
