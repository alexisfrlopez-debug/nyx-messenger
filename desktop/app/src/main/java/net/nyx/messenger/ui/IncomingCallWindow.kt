package net.nyx.messenger.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import net.nyx.messenger.p2p.DesktopCallManager

/**
 * v0.3.9 — FENÊTRE DE NOTIFICATION D'APPEL ENTRANT (demande opérateur
 * session 79, verbatim : « appels tel => pc : je veux une notification avec
 * une icone pour décrocher et une pour refuser l'appel »).
 *
 * Terrain 28/10 10:47:11 (export v0.3.8) : un appel téléphone→PC a sonné
 * 43 s SANS décrochage — l'opérateur n'a pas trouvé l'écran d'acceptation
 * dans la fenêtre principale et a RELANCÉ l'application à la place.
 *
 * Cette fenêtre est IMPOSSIBLE À MANQUER : petite carte dédiée SANS barre
 * de titre, TOUJOURS AU-DESSUS, centrée sur l'écran, indépendante de l'état
 * de la fenêtre principale (réduite au plateau, derrière d'autres fenêtres,
 * app verrouillée). Deux ICÔNES GÉANTES :
 *
 *   ✓ Décrocher (vert) → [DesktopCallManager.acceptIncoming]
 *   ✗ Refuser  (rouge) → [DesktopCallManager.rejectIncoming]
 *
 * Elle s'ouvre SEULE pendant la sonnerie (Phase.INCOMING, rendue depuis la
 * racine de application{} — AU-DESSUS du verrou de l'app) et se REFERME
 * SEULE au changement de phase (décroché → l'overlay d'appel de la fenêtre
 * principale prend le relais ; refusé/manqué → motif de fin bref). Fermer
 * la carte (Alt+F4) = refuser : jamais d'état coincé. Elle ne montre que
 * l'alias — JAMAIS de contenu de message (miroir CallLockActivity Android).
 */
@Composable
fun IncomingCallWindow() {
    val ui by DesktopCallManager.ui.collectAsState()
    // La carte n'existe QUE pendant la sonnerie (INCOMING) : au décroché,
    // la fenêtre principale — déjà ramenée au premier plan — prend le relais
    // (overlay d'appel complet : connexion, durée, visio, raccrocher).
    if (ui.phase != DesktopCallManager.Phase.INCOMING) return

    Window(
        title = "Nyx — appel entrant",
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            width = 480.dp, height = 330.dp,
        ),
        alwaysOnTop = true,
        resizable = false,
        undecorated = true,
        transparent = true,
        icon = nyxWindowIcon(),
        onCloseRequest = { DesktopCallManager.rejectIncoming() },
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Color(0xF70B0820), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Lune dorée (miroir avatar d'appel Android/desktop).
            Box(
                modifier = Modifier.size(64.dp)
                    .background(Color(0xFF232043), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("🌙", fontSize = 30.sp) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Appel entrant",
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFFE8C46B),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ui.alias.ifBlank { ui.peer.take(10) + "…" },
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = Color.White, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ui.videoEnabled)
                    "Appel visio — anonyme et chiffré, direct onion-to-onion"
                else
                    "Appel audio — anonyme et chiffré, direct onion-to-onion",
                fontSize = 12.sp, color = Color(0xCCB8A6),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            // DEUX ICÔNES GÉANTES — la demande opérateur exacte.
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { DesktopCallManager.acceptIncoming() },
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2FBF71),
                            contentColor = Color.White,
                        ),
                    ) { Text("✓", fontSize = 38.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text("Décrocher", fontSize = 14.sp, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { DesktopCallManager.rejectIncoming() },
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD94343),
                            contentColor = Color.White,
                        ),
                    ) { Text("✕", fontSize = 34.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text("Refuser", fontSize = 14.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "La fenêtre Nyx s'ouvre au décroché — une latence de 0,5 à 3 s est normale sur Tor.",
                fontSize = 11.sp, color = Color(0x998F7E),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Icône lune dorée (miroir nyx.ico — zéro ressource embarquée).
 *  v0.3.11 : internal — partagée avec [CallFloatWindow]. */
internal fun nyxWindowIcon(): androidx.compose.ui.graphics.painter.Painter {
    val img = net.nyx.messenger.notify.DesktopNotifier.moonIcon(64)
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "png", out)
    return androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(out.toByteArray()).asImageBitmap()
    )
}
