package net.nyx.messenger.ui.diag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
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
 * BuildConfig.TEST_BUILD) : carte d'export de logs en tête de l'écran
 * conversations.
 *
 * v0.2.38 — REPLIÉE PAR DÉFAUT (retour opérateur : « l'écran principal de la
 * messagerie ne défile pas ») : la carte déployée (~170 dp : titre + gros
 * bouton rouge 72 dp + partage) écrasait la zone d'onglets sur les écrans
 * compacts. Désormais une LIGNE compacte (titre + état + ▶) ; le contenu
 * (gros bouton rouge + partage + état détaillé) s'ouvre au clic. L'état
 * courant (collecte…/envoi…/déposé/erreur) reste visible DANS la ligne même
 * repliée — l'opérateur voit le résultat sans déplier.
 *
 * Un appui = pipeline complet :
 *   1. collecte des logs (logcat + en-tête diagnostic, cf. LogCollector) ;
 *   2. dépôt direct dans le dossier de travail Drive (cf. DriveUploader).
 * Aucune donnée de conversation n'est collectée — journaux système uniquement.
 */
@Composable
fun TestLogExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    // v0.2.38 : repli par défaut (écran principal compact — voir doc classe).
    var expanded by remember { mutableStateOf(false) }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.08f))
    ) {
        Column(Modifier.padding(12.dp)) {
            // v0.2.38 — ligne d'en-tête compacte TOUJOURS visible (repli/dépli).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    stringResource(R.string.debug_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ok) {
                            true -> MaterialTheme.colorScheme.tertiary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
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
                        val stamp = stamp()
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
                        true -> MaterialTheme.colorScheme.tertiary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            // v0.2.9 — Recours SANS Drive (retour opérateur : « j'ai voulu
            // envoyer le log mais message d'erreur » — le refresh token
            // embarqué à la compilation peut expirer/révoquer, l'envoi Drive
            // n'est pas garanti) : le partage système (ACTION_SEND) fonctionne
            // toujours — e-mail, fichier, cloud de l'utilisateur…
            // v0.2.24 — LE PARTAGE PASSE PAR UN FICHIER (FileProvider) : le
            // EXTRA_TEXT transitait par le binder Android (~1 Mo max) alors
            // que la collecte peut atteindre ~1,5 Mo — TransactionTooLarge-
            // Exception silencieuse, la cible ne recevait RIEN (retour terrain
            // : « je n'ai pas pu envoyer les logs »). Le fichier (cache/logs/,
            // read-only via le FileProvider v0.2.20 déjà déclaré) contourne
            // la limite et préserve la pièce jointe (e-mail, Drive personnel,
            // stockage…). Ré-écrit à chaque partage (stamp unique).
            // v0.2.25 — CLIPDATA OBLIGATOIRE (retour terrain : « j'ai sélectionné
            // facebook messenger, la fenêtre s'est fermée puis plus rien ») :
            // la grant READ du EXTRA_STREAM n'est pas toujours propagée aux
            // cibles À TRAVERS le ChooserActivity (certaines ROMs / apps
            // exigeantes lisent l'URI dans un process séparé, la refusent
            // silencieusement et ferment) — le ClipData.newUri recopie la
            // permission pour CHAQUE cible du chooser (mécanisme canonique).
            // + EXTRA_TEXT court (résumé, utile e-mail) + try/catch : un
            // échec de launch ne laisse plus l'écran dans le silence, et le
            // partage est loggé (tag nyx-share) pour le diagnostic terrain.
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (sharing) return@OutlinedButton
                    sharing = true
                    scope.launch {
                        var send: Intent? = null
                        try {
                            val stamp = stamp()
                            send = withContext(Dispatchers.IO) {
                                val text = LogCollector.collect(context)
                                val dir = java.io.File(context.cacheDir, "logs")
                                    .apply { mkdirs() }
                                val file = java.io.File(dir,
                                    "nyx-logs-v${net.nyx.messenger.BuildConfig.VERSION_NAME}-$stamp.txt")
                                file.writeText(text, Charsets.UTF_8)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, context.packageName + ".update", file)
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT,
                                        "nyx-logs-v${net.nyx.messenger.BuildConfig.VERSION_NAME}-$stamp")
                                    putExtra(Intent.EXTRA_TITLE, file.name)
                                    putExtra(Intent.EXTRA_TEXT,
                                        "Journaux de diagnostic Nyx v${net.nyx.messenger.BuildConfig.VERSION_NAME}")
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    // Grants propagées à TOUTES les cibles du chooser
                                    // (FB Messenger etc. lisent l'URI hors-process).
                                    clipData = android.content.ClipData.newUri(
                                        context.contentResolver, file.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            android.util.Log.i("nyx-share",
                                "partage logs — fichier ${send?.extras?.getString(Intent.EXTRA_TITLE)}")
                            context.startActivity(
                                Intent.createChooser(send, null).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("nyx-share", "partage impossible : ${e.message}")
                            ok = false
                            status = "Partage impossible : ${e.message ?: "?"}"
                        } finally {
                            sharing = false
                        }
                    }
                },
                enabled = !sharing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (sharing) R.string.debug_share_running else R.string.debug_share))
            }
            } // fin contenu déplié (v0.2.38)
        }
    }
}
