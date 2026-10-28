package net.nyx.messenger.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.p2p.DesktopCallManager
import org.jetbrains.skia.Image.Companion.makeFromEncoded
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * v0.3.7 — OVERLAY D'APPEL (portage de l'écran d'appel Android — occupant
 * tout l'écran par-dessus l'application tant que la phase ≠ IDLE).
 *
 * Sortant : « sonnerie… » + tentative média + Raccrocher.
 * Entrant : alias + message « appel entrant… anonyme et chiffré » +
 * Accepter (vert) / Refuser (rouge).
 * Actif : durée vivante + Micro (mute) + Raccrocher (+ 📹 pause du partage
 * quand la visio est négociée).
 * Terminé : motif 3 s (miroir CallUi.message) puis retour IDLE.
 *
 * v0.3.7 — VISIO MJPEG (interop téléphone ≥ v0.2.55) : la caméra du
 * téléphone s'affiche (poll du dernier JPEG du moteur vidéo → décodage
 * skia), avec la rotation du pair (F_VIDEO_ROT) portée par la VUE ; le
 * bureau partage son ÉCRAN (l'utilisateur est prévenu honnêtement).
 */
@Composable
fun CallOverlay() {
    val ui by DesktopCallManager.ui.collectAsState()
    if (ui.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.IDLE) return

    var elapsed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    LaunchedEffect(ui.phase, ui.startedAt) {
        while (ui.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE) {
            elapsed = (System.currentTimeMillis() - ui.startedAt) / 1000
            delay(1000)
        }
    }

    // v0.3.7 — trame vidéo distante : poll du moteur (le JPEG est déjà décodé
    // par skia — rotation appliquée par graphicsLayer, miroir VideoFit Android).
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameVersion by remember { mutableStateOf(0L) }
    LaunchedEffect(ui.phase, ui.videoEnabled) {
        while (ui.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE && ui.videoEnabled) {
            val engine = DesktopCallManager.remoteVideo
            val jpeg = engine?.latestJpeg
            val v = engine?.frameVersion ?: 0L
            if (jpeg != null && v != frameVersion) {
                frameVersion = v
                frame = runCatching { makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
            }
            delay(150)
        }
        frame = null
    }

    Surface(
        color = Color(0xF70B0820),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Avatar (lune dorée — miroir Android) — remplacé par la vue
            // DISTANTE quand la visio est active (miroir Android : le pair
            // occupe l'écran, l'avatar est pour l'audio seul).
            if (ui.videoEnabled && ui.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE) {
                val aspect = if (ui.videoPeerW > 0 && ui.videoPeerH > 0) {
                    ui.videoPeerW.toFloat() / ui.videoPeerH
                } else 3f / 4f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f / aspect) // l'aspect annoncé oriente la boîte
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    val f = frame
                    if (f != null) {
                        Image(
                            bitmap = f,
                            contentDescription = "caméra du pair",
                            modifier = Modifier
                                .fillMaxSize()
                                // rotation caméra du pair (métadonnée de présentation)
                                .graphicsLayer {
                                    rotationZ = when (ui.videoPeerRot) {
                                        90 -> 90f; 180 -> 180f; 270 -> 270f; else -> 0f
                                    }
                                    // une image tournée de 90° doit rester contenue :
                                    // on réduit pour ne pas déborder de la boîte.
                                    val r = when (ui.videoPeerRot) {
                                        90, 270 -> {
                                            val boxAspect = aspect
                                            if (boxAspect > 1f) 1f / boxAspect else boxAspect
                                        }
                                        else -> 1f
                                    }
                                    scaleX *= r.coerceAtMost(1f)
                                    scaleY *= r.coerceAtMost(1f)
                                },
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("caméra du pair…", fontSize = 14.sp, color = Color(0x998F7E))
                    }
                }
            } else {
                Box(
                    modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🌙", fontSize = 42.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                ui.alias.ifBlank { ui.peer.take(10) + "…" },
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xE8D5A8),
            )
            Spacer(Modifier.height(8.dp))
            val phrase = when (ui.phase) {
                net.nyx.messenger.p2p.DesktopCallManager.Phase.OUTGOING -> "appel sortant… ${ui.message}".trim()
                net.nyx.messenger.p2p.DesktopCallManager.Phase.INCOMING ->
                    if (ui.videoEnabled) "appel VISIO entrant — anonyme et chiffré"
                    else "appel entrant — anonyme et chiffré"
                net.nyx.messenger.p2p.DesktopCallManager.Phase.WAIT -> "connexion…"
                net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE -> formatDuration(elapsed)
                net.nyx.messenger.p2p.DesktopCallManager.Phase.ENDED -> ui.message.ifBlank { "terminé" }
                else -> ""
            }
            Text(phrase, fontSize = 16.sp, color = Color(0xCCB8A6), textAlign = TextAlign.Center)
            if (ui.phase == net.nyx.messenger.p2p.DesktopCallManager.Phase.INCOMING) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ui.videoEnabled)
                        "Appel visio — l'audio et la vidéo sont directs onion-to-onion."
                    else
                        "Appel audio — décrochez dans la fenêtre, l'audio est direct onion-to-onion.",
                    fontSize = 12.sp, color = Color(0x998F7E)
                )
            }
            Spacer(Modifier.height(36.dp))

            when (ui.phase) {
                net.nyx.messenger.p2p.DesktopCallManager.Phase.INCOMING -> Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Button(
                        onClick = { DesktopCallManager.acceptIncoming() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x2E7D32)),
                    ) { Text(if (ui.videoEnabled) "📹 Accepter la visio" else "Accepter", fontSize = 16.sp) }
                    Button(
                        onClick = { DesktopCallManager.rejectIncoming() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                    ) { Text("Refuser", fontSize = 16.sp) }
                }
                net.nyx.messenger.p2p.DesktopCallManager.Phase.OUTGOING,
                net.nyx.messenger.p2p.DesktopCallManager.Phase.WAIT,
                -> Button(
                    onClick = { DesktopCallManager.hangup("annulé") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                ) { Text("Raccrocher", fontSize = 16.sp) }
                net.nyx.messenger.p2p.DesktopCallManager.Phase.ACTIVE -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Button(
                            onClick = { DesktopCallManager.toggleMute() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ui.muted) Color(0x6D4C41) else Color(0x37474F)
                            ),
                        ) { Text(if (ui.muted) "🔇 Micro coupé" else "🎤 Micro actif", fontSize = 15.sp) }
                        if (ui.videoEnabled) {
                            Button(
                                onClick = { DesktopCallManager.toggleVideo() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (ui.videoPaused) Color(0x6D4C41) else Color(0x37474F)
                                ),
                            ) { Text(if (ui.videoPaused) "📹 Partage en pause" else "📹 Écran partagé", fontSize = 15.sp) }
                        }
                        Button(
                            onClick = { DesktopCallManager.hangup() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                        ) { Text("Raccrocher", fontSize = 15.sp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (ui.videoEnabled)
                            "La vidéo passe par Tor (~5 trames/s) : votre ÉCRAN est partagé, " +
                                "vous voyez la caméra du téléphone. Une latence de 0,5 à 3 s est normale."
                        else
                            "L'audio passe par Tor — une latence de 0,5 à 3 secondes est normale.",
                        fontSize = 11.sp, color = Color(0x776B5E), textAlign = TextAlign.Center
                    )
                }
                else -> {}
            }
        }
    }
}

private fun formatDuration(s: Long): String {
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
