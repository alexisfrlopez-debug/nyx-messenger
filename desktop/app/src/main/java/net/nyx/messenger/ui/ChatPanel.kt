package net.nyx.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.ServiceLocator
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser

/**
 * Panneau de conversation desktop — miroir de ChatScreen Android : bulles
 * (texte + fichiers), accusés ✓ (envoyé/délivré) et ✓✓ (lu), zone de saisie,
 * pièce jointe (JFileChooser → fragments ChunkCodec), actualisation au tick.
 */
@Composable
fun ChatPanel(peer: String, tick: Long, onClose: () -> Unit) {
    val contact = ServiceLocator.store.contact(peer)
    val messages = remember(tick, peer) { ServiceLocator.store.messagesWith(peer) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll vers le bas à l'arrivée de nouveaux messages.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Profil public du pair : récupéré à l'ouverture (carte signée du journal).
    var peerDisplayName by remember(peer) { mutableStateOf<String?>(null) }
    LaunchedEffect(peer) {
        runCatching {
            ServiceLocator.repository.fetchProfile(peer)
            ServiceLocator.store.peerProfile(peer)?.let { p ->
                peerDisplayName = p.displayName.ifBlank { null }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // — en-tête —
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✕", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onClose() })
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    peerDisplayName ?: contact?.alias?.ifBlank { peer.take(14) + "…" } ?: (peer.take(14) + "…"),
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    peer, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // — messages —
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                MessageBubble(m)
            }
        }

        error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // — saisie —
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    val fc = JFileChooser()
                    if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val f: File = fc.selectedFile
                        sending = true
                        scope.launch {
                            ServiceLocator.repository.sendFile(peer, f).fold(
                                onSuccess = { error = null },
                                onFailure = { error = it.message }
                            )
                            sending = false
                        }
                    }
                },
                enabled = !sending
            ) { Text("📎") }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message chiffré de bout en bout…", fontSize = 13.sp) },
                maxLines = 4,
                enabled = !sending
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = {
                    if (input.isBlank()) return@TextButton
                    val text = input
                    input = ""
                    sending = true
                    scope.launch {
                        ServiceLocator.repository.send(peer, text).fold(
                            onSuccess = { error = null },
                            onFailure = { error = it.message; input = text }
                        )
                        sending = false
                        ServiceLocator.pollNow()
                    }
                },
                enabled = !sending && input.isNotBlank()
            ) { Text("Envoyer") }
        }
    }
}

@Composable
private fun MessageBubble(m: DesktopStore.Msg) {
    val mine = m.outgoing
    Box(
        Modifier.fillMaxWidth().padding(start = if (mine) 64.dp else 0.dp, end = if (mine) 0.dp else 64.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            Modifier
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (m.kind == DesktopStore.KIND_FILE && m.fid.isNotBlank()) {
                FileBubble(m)
            } else {
                Text(
                    m.text,
                    fontSize = 13.sp,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    formatTs(m.ts),
                    fontSize = 9.sp,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.outline
                )
                if (mine) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            m.state == DesktopStore.STATE_PENDING -> "⏳"
                            m.state == DesktopStore.STATE_FAILED -> "✗"
                            m.rcptRead > 0 -> "✓✓"
                            m.rcptDelivered > 0 -> "✓"
                            else -> "✓"
                        },
                        fontSize = 10.sp,
                        color = if (m.state == DesktopStore.STATE_FAILED) MaterialTheme.colorScheme.error
                        else if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBubble(m: DesktopStore.Msg) {
    val meta = ServiceLocator.store.attachment(m.fid)
    val local = meta?.localPath?.takeIf { it.isNotBlank() }?.let { File(it) }
    val existsLocally = local != null && local.isFile
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("📦", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                meta?.name ?: m.text.removePrefix("[").removeSuffix("]"),
                fontSize = 13.sp,
                color = if (m.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (existsLocally) "(${meta?.size ?: 0} octets — cliquez pour ouvrir)"
                else "(${meta?.size ?: 0} octets — en attente des fragments…)",
                fontSize = 10.sp,
                color = if (m.outgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.outline
            )
        }
    }
    if (existsLocally && local != null) {
        Text(
            "Ouvrir",
            fontSize = 11.sp,
            color = if (m.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                runCatching {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(local)
                }
            }
        )
    }
}

private fun formatTs(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm").format(java.util.Date(ts * 1000L))
