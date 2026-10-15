package net.nyx.messenger.ui.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.diag.DriveUploader
import net.nyx.messenger.diag.LogCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CARTE DÉBOGAGE — build « test » UNIQUEMENT (l'appelant est gardé par
 * BuildConfig.TEST_BUILD) : gros bouton rouge en tête de l'écran conversations.
 *
 * Un appui = pipeline complet :
 *   1. collecte des logs (logcat + en-tête diagnostic, cf. LogCollector) ;
 *   2. dépôt direct dans le dossier de travail Drive (cf. DriveUploader).
 * L'état s'affiche sous le bouton (collecte → envoi → déposé / erreur).
 * Aucune donnée de conversation n'est collectée — journaux système uniquement.
 */
@Composable
fun TestLogExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.08f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.debug_card_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    ok = null
                    status = null
                    scope.launch {
                        val cfg = DriveUploader.configFromBuild()
                        if (!cfg.isConfigured) {
                            ok = false
                            status = context.getString(R.string.debug_no_credentials)
                            running = false
                            return@launch
                        }
                        status = context.getString(R.string.debug_collecting)
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val name = "nyx-logs-v${net.nyx.messenger.BuildConfig.VERSION_NAME}-$stamp.txt"
                        val content = withContext(Dispatchers.IO) {
                            LogCollector.collect(context).toByteArray(Charsets.UTF_8)
                        }
                        status = context.getString(R.string.debug_uploading)
                        val res = withContext(Dispatchers.IO) {
                            DriveUploader.upload(name, content, cfg)
                        }
                        ok = res.ok
                        status = if (res.ok) {
                            context.getString(R.string.debug_done, res.fileName)
                        } else {
                            context.getString(R.string.debug_failed, res.error ?: "?")
                        }
                        running = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Text(
                    if (running) stringResource(R.string.debug_button_running)
                    else stringResource(R.string.debug_button),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (ok) {
                        true -> Color(0xFF2E7D32)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
