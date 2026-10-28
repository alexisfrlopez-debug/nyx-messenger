package net.nyx.messenger.ui.call

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.nyx.messenger.R
import net.nyx.messenger.p2p.CallManager
import net.nyx.messenger.p2p.CallManager.Phase

/**
 * v0.2.56 — ACTIVITÉ D'APPEL AU-DESSUS DU VERROUILLAGE SYSTÈME (session 78 :
 * « pouvoir décrocher ou rejeter un appel quand le téléphone est verrouillé
 * sans devoir tout déverrouiller »).
 *
 * Lancée par l'INTENT PLEIN ÉCRAN de la notification d'appel entrante
 * (USE_FULL_SCREEN_INTENT — le système l'affiche directement sur le
 * keyguard, écran allumé). Montre uniquement : alias de l'appelant, état de
 * l'appel, durée — JAMAIS de contenu de message (l'alias est déjà visible
 * sur la notification elle-même ; les appels sont chiffrés de bout en bout).
 *
 * Décrocher/Refuser/Raccrocher agissent DIRECTEMENT sur [CallManager] —
 * aucun déverrouillage (ni système, ni mot de passe de l'app) n'est requis.
 * La visio n'est PAS rendue ici (aucune surface caméra sur le keyguard —
 * l'émission/réception continuent, l'image attend le déverrouillage :
 * message honnête affiché). L'activité se ferme seule quand l'appel se
 * termine ; le verrou biométrique de l'app reste armé dessous.
 */
class CallLockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Au-dessus du keyguard + écran allumé (API 26 : flags fenêtre ; API
        // 27+ : API dédiée — les deux posés, redondance volontaire).
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CallLockScreen() }
    }
}

@Composable
private fun CallLockScreen() {
    val ui by CallManager.ui.collectAsState()
    val ctx = LocalContext.current

    // Fin automatique : appel terminé → brève contre-vue puis fermeture.
    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.IDLE) {
            (ctx as? Activity)?.finish()
        }
    }
    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.ENDED) {
            delay(1800)
            (ctx as? Activity)?.finish()
        }
    }

    val label = ui.alias.ifBlank { ui.peer.take(10) + "…" }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF10141A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (ui.phase) {
                Phase.INCOMING, Phase.WAIT -> {
                    Text(
                        stringRes(R.string.notif_call_title),
                        color = Color(0xFFE8C46B), fontSize = 16.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringRes(R.string.call_incoming_lock, label),
                        color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        OutlinedButton(
                            onClick = { CallManager.rejectIncoming() },
                            modifier = Modifier.size(86.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))
                        ) { Text("✕", fontSize = 30.sp) }
                        Button(
                            onClick = { CallManager.acceptIncoming() },
                            modifier = Modifier.size(86.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2FBF71), contentColor = Color.White
                            )
                        ) { Text("✓", fontSize = 34.sp) }
                    }
                    if (ui.phase == Phase.WAIT) {
                        Spacer(Modifier.height(22.dp))
                        Text(stringRes(R.string.call_connecting), color = Color(0xFF9AA3AE), fontSize = 14.sp)
                    }
                }
                Phase.ACTIVE -> {
                    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
                    }
                    val secs = ((nowMs - ui.startedAt).coerceAtLeast(0)) / 1000
                    Text(
                        stringRes(R.string.call_active_lock),
                        color = Color(0xFFE8C46B), fontSize = 16.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "%02d:%02d".format(secs / 60, secs % 60),
                        color = Color(0xFF9AA3AE), fontSize = 18.sp
                    )
                    if (ui.videoEnabled) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringRes(R.string.call_video_lock_hint),
                            color = Color(0xFF9AA3AE), fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedButton(
                            onClick = { CallManager.toggleMute() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (ui.muted) Color(0xFFE8C46B) else Color.White
                            )
                        ) { Text(stringRes(R.string.call_mute) + if (ui.muted) " ✕" else "", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { CallManager.toggleSpeaker() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (ui.speaker) Color(0xFFE8C46B) else Color.White
                            )
                        ) { Text(stringRes(R.string.call_speaker), fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { CallManager.hangup() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD94343), contentColor = Color.White
                        )
                    ) { Text(stringRes(R.string.call_hangup), fontSize = 16.sp) }
                }
                else -> {
                    // ENDED : contre-vue brève (fin auto par LaunchedEffect).
                    Text(
                        ui.message.ifBlank { stringRes(R.string.notif_call_title) },
                        color = Color(0xFF9AA3AE), fontSize = 18.sp, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun stringRes(res: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(res, *args)
