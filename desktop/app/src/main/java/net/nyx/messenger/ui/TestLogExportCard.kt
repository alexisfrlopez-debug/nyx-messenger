package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.diag.DriveUploader
import net.nyx.messenger.diag.LogCollector
import java.io.File

/**
 * v0.3.3 (session 73 — demande opérateur verbatim : « je veux une version test
 * avec le bouton d'envoi de logs comme pour la version android ») — CARTE
 * DÉBOGAGE DESKTOP, miroir de l'Android `ui/diag/TestLogExportCard.kt` :
 * carte repliée en tête des conversations, gros bouton rouge d'envoi.
 *
 * Reprend les leçons terrain Android :
 *  - v0.2.38 : repliée par défaut (ligne compacte + ▶), le contenu s'ouvre au
 *    clic — l'écran principal reste lisible ;
 *  - v0.2.42 : le statut de la ligne compacte est COURT (« ✔ Envoyés » /
 *    « ✗ Échec »), contraint au reste de la ligne (weight + ellipsis) — le
 *    nom de fichier complet (60+ caractères) n'apparaît qu'en vue dépliée,
 *    sur sa propre ligne multi-lignes ;
 *  - v0.2.9 : RECOURS SANS Drive — l'équivalent desktop du partage système
 *    Android (ACTION_SEND) est « Enregistrer dans un fichier… »
 *    (JFileChooser) : fonctionne toujours, même credentials absents/expirés.
 *
 * Un clic = pipeline complet (identique à l'Android) :
 *   1. collecte des journaux (en-tête diagnostic + tor.log + console du
 *      démon + journal persistant — cf. LogCollector) ;
 *   2. dépôt direct dans le dossier Drive de débogage (cf. DriveUploader).
 * Aucune donnée de conversation n'est collectée — journaux système uniquement.
 */
@Composable
fun TestLogExportCard() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    // v0.2.38 (miroir) : repliée par défaut — écran principal compact.
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFB71C1C).copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Ligne d'en-tête compacte : titre TOUJOURS entier, statut COURT
            // contraint au reste (weight + ellipsis), flèche en dernier.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    "ENVOI DE LOGS (test)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                val shortStatus = when {
                    status == null -> null
                    ok == true -> "✔ Envoyés"
                    ok == false -> "✗ Échec"
                    else -> status
                }
                shortStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ok) {
                            true -> MaterialTheme.colorScheme.tertiary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (shortStatus == null) Spacer(Modifier.weight(1f))
                Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dépose les journaux de diagnostic (version, état Tor, tor.log, " +
                        "sondes réplicas) dans le dossier de débogage de l'opérateur. " +
                        "Aucun message ni contact n'est collecté.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (running) return@Button
                        running = true
                        ok = null
                        status = "collecte des journaux…"
                        scope.launch {
                            val cfg = DriveUploader.config()
                            if (!cfg.isConfigured) {
                                ok = false
                                status = "✗ Credentials Drive absents de ce build " +
                                    "(utilisez « Enregistrer dans un fichier… »)"
                                running = false
                                return@launch
                            }
                            status = "envoi vers le Drive…"
                            val name = LogCollector.fileName()
                            val res = withContext(Dispatchers.IO) {
                                val content = LogCollector.collect().toByteArray(Charsets.UTF_8)
                                DriveUploader.upload(name, content, cfg)
                            }
                            ok = res.ok
                            status = if (res.ok) {
                                DesktopLog.i("nyx-logs", "logs déposés dans le Drive : $name")
                                "✔ Déposé dans le Drive : $name"
                            } else {
                                DesktopLog.w("nyx-logs", "échec d'envoi des logs : ${res.error}")
                                "✗ Échec : ${res.error ?: "?"}"
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
                        if (running) "ENVOI EN COURS…" else "ENVOYER LES LOGS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                // État DÉTAILLÉ (nom de fichier complet) — vue dépliée seulement.
                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (ok) {
                            true -> MaterialTheme.colorScheme.tertiary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                // v0.2.9 (miroir) : recours SANS Drive — l'équivalent desktop du
                // partage système Android : enregistrement local du même fichier.
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (saving) return@OutlinedButton
                        saving = true
                        scope.launch {
                            try {
                                val name = withContext(Dispatchers.IO) { LogCollector.fileName() }
                                val fc = javax.swing.JFileChooser()
                                fc.dialogTitle = "Enregistrer les journaux de diagnostic Nyx"
                                fc.selectedFile = File(name)
                                if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val text = withContext(Dispatchers.IO) { LogCollector.collect() }
                                    withContext(Dispatchers.IO) {
                                        fc.selectedFile.writeText(text, Charsets.UTF_8)
                                    }
                                    ok = true
                                    status = "journal enregistré : ${fc.selectedFile.absolutePath}"
                                    DesktopLog.i("nyx-logs", "journal enregistré localement : ${fc.selectedFile.name}")
                                }
                            } catch (e: Exception) {
                                ok = false
                                status = "enregistrement impossible : ${e.message ?: "?"}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (saving) "Enregistrement…" else "Enregistrer dans un fichier…")
                }
            } // fin contenu déplié (v0.2.38 miroir)
        }
    }
}
