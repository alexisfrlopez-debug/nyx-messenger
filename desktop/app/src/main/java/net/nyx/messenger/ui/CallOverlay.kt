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
import androidx.compose.runtime.DisposableEffect
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
import net.nyx.messenger.p2p.DesktopCallManager
import net.nyx.messenger.p2p.DesktopCallManager.Phase
import org.jetbrains.skia.Image.Companion.makeFromEncoded
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * v0.3.7 — OVERLAY D'APPEL (portage de l'écran d'appel Android — occupant
 * tout l'écran par-dessus l'application tant que la phase ≠ IDLE).
 *
 * v0.3.15 — PLEIN ÉCRAN VIDÉO + APERÇU LOCAL EN COIN (demande opérateur
 * 29/10, verbatim : « sur pc, je veux la possibilité de mettre la video
 * reçue en plein écran en gardant la video envoyée en fenêtre dans le coin
 * en haut à droite ») :
 *  - bouton ⛶ en visio active : la vidéo DU PAIR occupe TOUTE la fenêtre
 *    (maximisée OS via [DesktopCallManager.windowMaximizer]), rendue en
 *    ContentScale.Fit — l'aspect de la TRAME est RESPECTÉ : bandes noires
 *    symétriques, JAMAIS d'étirement (« je veux que l'application qui
 *    reçoit un flux video adapte le format du flux reçu pour l'afficher
 *    correctement en respectant les proportions d'origine par ajout de
 *    bandes noires afin d'éviter les déformations ») ;
 *  - NOTRE vidéo envoyée vit en PETITE FENÊTRE AU-DESSUS À DROITE
 *    ([DesktopCallVideo.localPreview] — caméra ~10 fps à chaud, écran au
 *    send) ;
 *  - quitter le plein écran : bouton dédié (la fenêtre OS est restaurée) ;
 *    la fin d'appel restaure AUSSI (jamais de fenêtre maximisée fantôme).
 */
@Composable
fun CallOverlay() {
    val ui by DesktopCallManager.ui.collectAsState()
    if (ui.phase == Phase.IDLE) return

    var elapsed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    LaunchedEffect(ui.phase, ui.startedAt) {
        while (ui.phase == Phase.ACTIVE) {
            elapsed = (System.currentTimeMillis() - ui.startedAt) / 1000
            delay(1000)
        }
    }

    // v0.3.15 — MODE PLEIN ÉCRAN (état UI local ; la fenêtre OS suit par le
    // maximiseur câblé dans Main.kt — jamais bloquant si non câblé).
    var fullscreen by remember { mutableStateOf(false) }

    /** Bascule le plein écran (état + fenêtre OS). */
    fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        runCatching { DesktopCallManager.windowMaximizer?.invoke(on) }
    }

    // Fin d'appel / fin de visio : TOUJOURS restaurer la fenêtre OS.
    LaunchedEffect(ui.phase, ui.videoEnabled) {
        if ((ui.phase != Phase.ACTIVE || !ui.videoEnabled) && fullscreen) {
            setFullscreen(false)
        }
    }
    // Sortie de composition (raccroché, masquage) : filet de sécurité.
    DisposableEffect(Unit) {
        onDispose { runCatching { DesktopCallManager.windowMaximizer?.invoke(false) } }
    }

    // v0.3.10 — trame vidéo distante : poll du moteur (le JPEG est déjà décodé
    // par skia). L'ASPECT VIT DANS LA TRAME ELLE-MÊME (JPEG auto-descriptif :
    // téléphones ≥ v0.2.58 qui PRÉ-TOURNENT à l'encodage — portrait ↔ paysage
    // suit l'orientation SANS métadonnée) ; la rotation graphicsLayer ne sert
    // plus QUE pour un pair ancien (≤ v0.2.57, F_VIDEO_ROT).
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var frameVersion by remember { mutableStateOf(0L) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    LaunchedEffect(ui.phase, ui.videoEnabled) {
        while (ui.phase == Phase.ACTIVE && ui.videoEnabled) {
            val engine = DesktopCallManager.remoteVideo
            val jpeg = engine?.latestJpeg
            val v = engine?.frameVersion ?: 0L
            if (jpeg != null && v != frameVersion) {
                frameVersion = v
                val decoded = runCatching { makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
                if (decoded != null) {
                    frame = decoded
                    frameW = decoded.width
                    frameH = decoded.height
                }
            }
            delay(150)
        }
        frame = null
    }

    // v0.3.15 — APERÇU LOCAL (notre vidéo ENVOYÉE) : poll du moteur —
    // caméra à chaud (~10 fps), écran à l'émission (~5 fps). Vit dans le
    // COIN EN PLEIN ÉCRAN, jamais en mode normal (pas de doublon avec la
    // carte flottante).
    var localFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var localVersion by remember { mutableStateOf(0L) }
    LaunchedEffect(ui.phase, ui.videoEnabled, fullscreen) {
        while (ui.phase == Phase.ACTIVE && ui.videoEnabled && fullscreen) {
            val engine = DesktopCallManager.remoteVideo
            val jpeg = engine?.localPreview
            val v = engine?.localPreviewVersion
            if (jpeg != null && v != null && v != localVersion) {
                localVersion = v
                runCatching { makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull()
                    ?.let { localFrame = it }
            }
            delay(200)
        }
        localFrame = null
    }

    // Rotation du pair — pair ancien UNIQUEMENT (≤ v0.2.57 : métadonnée de
    // présentation ; un téléphone ≥ v0.2.58 pré-tourne ses JPEG).
    val frameAspect = if (frameW > 0 && frameH > 0) frameW.toFloat() / frameH else 0f
    val announced = if (ui.videoPeerW > 0 && ui.videoPeerH > 0) {
        ui.videoPeerW.toFloat() / ui.videoPeerH
    } else 3f / 4f
    val rotLegacy = ui.videoPeerRot == 90 || ui.videoPeerRot == 270 || ui.videoPeerRot == 180
    val boxAspect = if (frameAspect > 0f) frameAspect else announced

    // ------------------------------------------------------------------
    // v0.3.15 — LAYOUT PLEIN ÉCRAN : le pair occupe TOUT, notre vidéo vit
    // en coin haut-droit, contrôles en bas, dialogue de partage TOUJOURS
    // visible (jamais enterré par le mode).
    // ------------------------------------------------------------------
    if (fullscreen && ui.phase == Phase.ACTIVE && ui.videoEnabled) {
        Surface(color = Color(0xFF000000), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Vidéo DU PAIR : Fit → letterbox noir symétrique (proportions
                // d'origine préservées, jamais d'étirement).
                val f = frame
                if (f != null) {
                    Image(
                        bitmap = f,
                        contentDescription = "caméra du pair",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = when (ui.videoPeerRot) {
                                    90 -> 90f; 180 -> 180f; 270 -> 270f; else -> 0f
                                }
                                // une image tournée de 90° doit rester contenue :
                                // on réduit pour ne pas déborder de la fenêtre.
                                val r = when (ui.videoPeerRot) {
                                    90, 270 -> {
                                        val ba = if (frameAspect > 0f) frameAspect else announced
                                        if (ba > 1f) 1f / ba else ba
                                    }
                                    else -> 1f
                                }
                                scaleX *= r.coerceAtMost(1f)
                                scaleY *= r.coerceAtMost(1f)
                            },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("caméra du pair…", fontSize = 16.sp, color = Color(0x998F7E))
                    }
                }
                // NOTRE vidéo envoyée — petite fenêtre COIN HAUT DROIT.
                val lf = localFrame
                if (lf != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                            .size(width = 224.dp, height = 138.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xE6000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = lf,
                            contentDescription = "votre vidéo envoyée",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        "votre vidéo",
                        fontSize = 9.sp,
                        color = Color(0x998F7E),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 156.dp, end = 14.dp),
                    )
                }
                // Identité + durée + bannière honnête (haut GAUCHE).
                Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
                    Text(
                        ui.alias.ifBlank { ui.peer.take(10) + "…" },
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xE8D5A8),
                    )
                    Text(
                        "visio — ${formatDuration(elapsed)}",
                        fontSize = 13.sp, color = Color(0xCCB8A6),
                    )
                    val engineNotice = DesktopCallManager.remoteVideo?.sourceNotice ?: ""
                    val banner = ui.screenNotice.ifBlank { engineNotice }
                    if (banner.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            banner, fontSize = 11.sp, color = Color(0xFFB74D),
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                    }
                }
                // Demande de partage du pair — JAMAIS enterrée en plein écran.
                if (ui.screenShareRequest) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xF70B0820))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Le pair demande à partager son écran :", fontSize = 12.sp, color = Color(0xE8DCC8))
                        Button(
                            onClick = { DesktopCallManager.answerScreenShare(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x2E7D32)),
                        ) { Text("✓", fontSize = 13.sp) }
                        Button(
                            onClick = { DesktopCallManager.answerScreenShare(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                        ) { Text("✕", fontSize = 13.sp) }
                    }
                }
                // ANNULER la demande en attente / ARRÊTER le partage actif.
                if (ui.screenSharePending) {
                    Button(
                        onClick = { DesktopCallManager.cancelScreenShareRequest() },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x8D6E63)),
                    ) { Text("Annuler la demande", fontSize = 12.sp) }
                } else if (ui.videoIsScreen || ui.peerScreenShare) {
                    Button(
                        onClick = { DesktopCallManager.stopScreenShare() },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x5E35B1)),
                    ) { Text(if (ui.videoIsScreen) "Arrêter mon partage" else "Arrêter le partage du pair", fontSize = 12.sp) }
                }
                // Contrôles en bas — semi-transparents, souris requise (fenêtre).
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 22.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xC80B0820))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { DesktopCallManager.toggleMute() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.muted) Color(0x6D4C41) else Color(0x37474F)
                        ),
                    ) { Text(if (ui.muted) "🔇" else "🎤", fontSize = 15.sp) }
                    Button(
                        onClick = { DesktopCallManager.toggleVideoSource() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.videoIsScreen) Color(0x5E35B1)
                            else if (ui.screenSharePending) Color(0x8D6E63) else Color(0x37474F)
                        ),
                    ) { Text(if (ui.videoIsScreen) "🖥️" else "📹", fontSize = 15.sp) }
                    Button(
                        onClick = { DesktopCallManager.toggleVideo() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.videoPaused) Color(0x6D4C41) else Color(0x37474F)
                        ),
                    ) { Text(if (ui.videoPaused) "▶️" else "⏸", fontSize = 15.sp) }
                    Button(
                        onClick = { setFullscreen(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x232043)),
                    ) { Text("⤢ Quitter le plein écran", fontSize = 13.sp) }
                    Button(
                        onClick = { DesktopCallManager.hangup() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                    ) { Text("Raccrocher", fontSize = 14.sp) }
                }
            }
        }
        return
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
            if (ui.videoEnabled && ui.phase == Phase.ACTIVE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(if (rotLegacy) 1f / boxAspect else boxAspect) // la boîte suit l'aspect réel
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
                                // rotation caméra du pair — pair ancien UNIQUEMENT
                                // (≤ v0.2.57 : métadonnée de présentation ; un
                                // téléphone ≥ v0.2.58 pré-tourne ses JPEG).
                                .graphicsLayer {
                                    rotationZ = when (ui.videoPeerRot) {
                                        90 -> 90f; 180 -> 180f; 270 -> 270f; else -> 0f
                                    }
                                    // une image tournée de 90° doit rester contenue :
                                    // on réduit pour ne pas déborder de la boîte.
                                    val r = when (ui.videoPeerRot) {
                                        90, 270 -> {
                                            val ba = if (frameAspect > 0f) frameAspect else announced
                                            if (ba > 1f) 1f / ba else ba
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
                Phase.OUTGOING -> "appel sortant… ${ui.message}".trim()
                Phase.INCOMING ->
                    if (ui.videoEnabled) "appel VISIO entrant — anonyme et chiffré"
                    else "appel entrant — anonyme et chiffré"
                Phase.WAIT -> "connexion…"
                Phase.ACTIVE -> formatDuration(elapsed)
                Phase.ENDED -> ui.message.ifBlank { "terminé" }
                else -> ""
            }
            Text(phrase, fontSize = 16.sp, color = Color(0xCCB8A6), textAlign = TextAlign.Center)
            if (ui.phase == Phase.INCOMING) {
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
                Phase.INCOMING -> Row(
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
                Phase.OUTGOING,
                Phase.WAIT,
                -> Button(
                    onClick = { DesktopCallManager.hangup("annulé") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                ) { Text("Raccrocher", fontSize = 16.sp) }
                Phase.ACTIVE -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { DesktopCallManager.toggleMute() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ui.muted) Color(0x6D4C41) else Color(0x37474F)
                            ),
                        ) { Text(if (ui.muted) "🔇 Micro coupé" else "🎤 Micro actif", fontSize = 14.sp) }
                        if (ui.videoEnabled) {
                            // v0.3.12 — bascule écran VALIDÉE PAR LE PAIR (session 78 :
                            // « partage d'écran … UNIQUEMENT si validé des 2 côtés ») :
                            // le bouton envoie une DEMANDE ; la bascule n'a lieu qu'à
                            // la réception du F_SCREEN_ACCEPT (DesktopCallManager).
                            Button(
                                onClick = { DesktopCallManager.toggleVideoSource() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (ui.videoIsScreen) Color(0x5E35B1)
                                    else if (ui.screenSharePending) Color(0x8D6E63) else Color(0x37474F)
                                ),
                            ) {
                                Text(
                                    when {
                                        ui.videoIsScreen -> "🖥️ Écran"
                                        ui.screenSharePending -> "⏳ Autorisation…"
                                        else -> "📹 Caméra"
                                    }, fontSize = 14.sp
                                )
                            }
                            Button(
                                onClick = { DesktopCallManager.toggleVideo() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (ui.videoPaused) Color(0x6D4C41) else Color(0x37474F)
                                ),
                            ) { Text(if (ui.videoPaused) "⏸ Vidéo en pause" else "⏸ Pause vidéo", fontSize = 14.sp) }
                            // v0.3.15 — PLEIN ÉCRAN (demande 29/10 : la vidéo reçue
                            // en plein écran + la vidéo envoyée en coin haut-droit).
                            Button(
                                onClick = { setFullscreen(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x232043)),
                            ) { Text("⛶ Plein écran", fontSize = 14.sp) }
                        }
                        Button(
                            onClick = { DesktopCallManager.hangup() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                        ) { Text("Raccrocher", fontSize = 14.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    // v0.3.12 — DEMANDE DU PAIR (F_SCREEN_REQ) : validation ✓/✕
                    // AVANT que SON écran arrive chez nous.
                    if (ui.screenShareRequest) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Le pair demande à partager son écran :", fontSize = 13.sp, color = Color(0xE8DCC8))
                            Button(
                                onClick = { DesktopCallManager.answerScreenShare(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x2E7D32)),
                            ) { Text("✓ Autoriser", fontSize = 13.sp) }
                            Button(
                                onClick = { DesktopCallManager.answerScreenShare(false) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3261E)),
                            ) { Text("✕ Refuser", fontSize = 13.sp) }
                        }
                    }
                    // v0.3.12 — état honnête de NOTRE source (repli écran, écran
                    // partagé validé…) : bannière sous les boutons, plus jamais
                    // une ligne de journal invisible (terrain 28/10 : « le pc
                    // fait un partage d'écran » sans savoir pourquoi).
                    val engineNotice = DesktopCallManager.remoteVideo?.sourceNotice ?: ""
                    val banner = ui.screenNotice.ifBlank { engineNotice }
                    if (banner.isNotEmpty()) {
                        Text(
                            banner, fontSize = 11.sp, color = Color(0xFFB74D),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (ui.videoEnabled)
                            if (ui.videoIsScreen)
                                "La vidéo passe par Tor (~5 trames/s) : votre ÉCRAN est partagé " +
                                    "(validé par le pair) — basculez sur 📹 pour revenir à la caméra. " +
                                    "Une latence de 0,5 à 3 s est normale."
                            else
                                "La vidéo passe par Tor (~5 trames/s) : votre CAMÉRA est partagée " +
                                    "(🖥️ envoie une demande de partage d'écran — le pair doit l'autoriser) ; " +
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
