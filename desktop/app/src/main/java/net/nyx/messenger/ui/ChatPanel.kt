package net.nyx.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopStore
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.media.DesktopAudioPlayer
import net.nyx.messenger.media.MediaIngest
import net.nyx.messenger.media.VoiceRecorder
import net.nyx.messenger.notify.DesktopNotifier
import net.nyx.messenger.repo.ChunkCodec
import java.awt.Desktop
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Panneau de conversation — v0.3.4 « parité Android » : MIROIR de
 * ChatScreen Android (mêmes libellés, mêmes boutons) :
 *
 *  - en-tête : avatar + nom + boutons Appel audio / Visio (honnêtes phase 2,
 *    miroir « Nouveau groupe ») + Fiche + fermer ;
 *  - bannière « non mutuel » (invitation pas encore acceptée des 2 côtés) ;
 *  - onglets « Messages » / « Fichiers & médias » ;
 *  - bulles : accusés ✓ Envoyé / ✓✓ Reçu / ✓✓ Lu, éphémère (minuteur par
 *    conversation OFF/1 h/24 h/7 j + compte à rebours), médias (aperçu image
 *    + visionneuse plein écran + Enregistrer sous, lecteur vocal, carte
 *    fichier + Ouvrir/Enregistrer), barres de transfert, vue limitée
 *    (once/twice : badge, verrou « Toucher pour afficher », destruction) ;
 *  - clic DROIT sur un message → « Supprimer pour tous » / « Supprimer pour
 *    moi » (miroir long-press Android) ;
 *  - composeur : feuille 📎 (Photo ou vidéo / Fichier), minuteur ⏱ éphémère,
 *    micro 🎤 (vocal WAV — miroir v0.2.17), champ, Envoyer ;
 *  - politique d'affichage du média avant envoi (permanent / vue unique /
 *    vue unique + 1 relecture — miroir MediaViewPolicyDialog v0.2.28).
 */
@Composable
fun ChatPanel(
    peer: String,
    tick: Long,
    onClose: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = ServiceLocator.store
    val repository = ServiceLocator.repository

    val contact = remember(tick, peer) { store.contact(peer) }
    val messages = remember(tick, peer) { store.messagesWith(peer) }
    val peerProfile = remember(tick, peer) { store.peerProfile(peer) }
    var input by remember(peer) { mutableStateOf("") }
    var error by remember(peer) { mutableStateOf<String?>(null) }
    var tab by remember(peer) { mutableStateOf(0) } // 0 Messages, 1 Fichiers & médias

    // Minuteur éphémère de la conversation (persisté — miroir Android).
    var ttl by remember(peer) { mutableStateOf(ServiceLocator.ephemeralTtl(peer)) }
    var ttlToast by remember(peer) { mutableStateOf<String?>(null) }

    // Horloge 1 s : comptes à rebours éphémères + durées (miroir nowTick Android).
    var clock by remember { mutableStateOf(0) }
    LaunchedEffect(peer) { while (true) { delay(1_000); clock++ } }

    // Enregistrement vocal (miroir startVoice/stopAndSendVoice).
    var recorder by remember(peer) { mutableStateOf<VoiceRecorder.Handle?>(null) }
    var recorderMs by remember(peer) { mutableStateOf(0L) }

    // Suppression (clic droit — miroir long-press Android).
    var deleteTarget by remember { mutableStateOf<DesktopStore.Msg?>(null) }

    // Feuille 📎 + politique de vue.
    var attachSheet by remember { mutableStateOf(false) }
    var pendingMedia by remember { mutableStateOf<File?>(null) }

    // Visionneuse plein écran (miroir ImageViewerDialog Android — reçoit le FICHIER :
    // affichage décodé Skia + « Enregistrer sous… » = copie des octets d'origine).
    var viewerFile by remember { mutableStateOf<File?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
    }
    LaunchedEffect(peer) {
        ServiceLocator.repository.markConversationRead(peer)
        ServiceLocator.pollNow()
        DesktopNotifier.suppress(peer)
    }

    val displayName = peerProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: contact?.alias?.takeIf { it.isNotBlank() }
        ?: peer.take(14) + "…"
    val isAccepted = contact?.status == DesktopStore.ContactStatus.ACCEPTED

    Column(Modifier.fillMaxSize()) {
        // ---------- En-tête (miroir TopAppBar Android) ----------
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(displayName, peerProfile?.avatarB64.orEmpty(), 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    if (contact?.alias?.isNotBlank() == true && displayName != contact.alias)
                        "@${contact.alias} · ${peer.take(12)}…"
                    else peer,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                )
            }
            // Appel audio / Visio — honnête phase 2 (miroir dialogue Groupes).
            TextButton(onClick = { error = "Les appels audio arrivent dans une prochaine version du bureau (phase 2)." }) { Text("📞") }
            TextButton(onClick = { error = "La visio arrive dans une prochaine version du bureau (phase 2)." }) { Text("📹") }
            TextButton(onClick = { onOpenProfile(peer) }) { Text("👤") }
            TextButton(onClick = { onClose() }) { Text("✕") }
        }

        // ---------- Bannière non mutuel (miroir Android) ----------
        if (!isAccepted) {
            Text(
                "L'échange de messages sera possible une fois l'invitation acceptée des deux côtés.",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- Onglets (miroir TabRow Android) ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TabLabel("Messages", tab == 0) { tab = 0 }
            TabLabel("Fichiers & médias", tab == 1) { tab = 1 }
        }

        if (tab == 0) {
            // ---------- Messages ----------
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(
                            m = m,
                            clock = clock,
                            onOpen = { f -> openExternally(f) },
                            onViewer = { file -> viewerFile = file },
                            onDelete = { deleteTarget = m },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // ---------- Barre d'enregistrement vocal (miroir Android) ----------
            if (recorder != null) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Enregistrement… (${recorderMs / 1000} s)",
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = {
                        val rec = recorder ?: return@TextButton
                        recorder = null
                        scope.launch(Dispatchers.IO) {
                            val bytes = rec.stop()
                            if (bytes.size > 44) {
                                val tmp = File.createTempFile("vocal-", ".wav")
                                tmp.writeBytes(bytes)
                                repository.sendFile(peer, tmp).fold(
                                    onSuccess = { tmp.delete() },
                                    onFailure = { tmp.delete() }
                                )
                            }
                            ServiceLocator.pollNow()
                        }
                    }) { Text("Arrêter et envoyer") }
                }
            }

            // ---------- Composeur ----------
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 📎 Feuille de partage (miroir ModalBottomSheet Android).
                TextButton(onClick = { attachSheet = true }) { Text("📎") }
                // ⏱ Minuteur éphémère (miroir Timer Android).
                TextButton(onClick = {
                    ttl = ChatFormat.ttlNext(ttl)
                    ServiceLocator.setEphemeralTtl(peer, ttl)
                    ttlToast = if (ttl > 0)
                        "Messages éphémères : ${ChatFormat.ttlLabel(ttl)} (les deux côtés)"
                    else "Messages éphémères : désactivés"
                }) {
                    Text("⏱", color = if (ttl > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 🎤 Micro (vocal — visible quand le champ est vide, miroir Android).
                if (input.isBlank() && recorder == null) {
                    TextButton(onClick = {
                        val handle = VoiceRecorder.start()
                        if (handle == null) error = "Micro indisponible — impossible d'enregistrer un message vocal."
                        else {
                            recorder = handle
                            scope.launch { while (recorder != null) { recorderMs = handle.durationMs; delay(200) } }
                        }
                    }) { Text("🎤") }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message…", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                TextButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@TextButton
                        input = ""
                        scope.launch {
                            repository.send(peer, text, ttl).fold(
                                onSuccess = { ServiceLocator.pollNow() },
                                onFailure = { e -> input = text; error = "Échec d'envoi : ${e.message}" }
                            )
                        }
                    }
                ) { Text("Envoyer") }
            }
            ttlToast?.let {
                Text(
                    it, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                )
            }
            error?.let {
                Text(
                    it.take(200), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            // ---------- Onglet Fichiers & médias (miroir MediaTab Android) ----------
            MediaTab(peer, tick, onDelete = { fid, forEveryone ->
                scope.launch {
                    repository.deleteAttachment(peer, fid, forEveryone)
                        .onFailure { e -> error = e.message }
                    ServiceLocator.pollNow()
                }
            })
        }
    }

    // ---------- Dialogues ----------

    // Supprimer ce message ? (miroir Android — libellés exacts).
    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (m.kind == DesktopStore.KIND_FILE) "Supprimer ce média ?" else "Supprimer ce message ?") },
            text = {
                Text(
                    if (m.outgoing)
                        "Le message sera également supprimé sur l'appareil de votre contact (à sa prochaine connexion)."
                    else
                        "Seule la copie de CET appareil sera supprimée — celle de votre contact reste en place."
                )
            },
            confirmButton = {
                if (m.outgoing) {
                    TextButton(onClick = {
                        val target = m; deleteTarget = null
                        scope.launch {
                            repository.deleteForEveryone(peer, listOf(target.id))
                                .onFailure { error = "Supprimé ici — la suppression chez votre contact partira dès que le réplica sera joignable." }
                            ServiceLocator.pollNow()
                        }
                    }) { Text("Supprimer pour tous", color = MaterialTheme.colorScheme.error) }
                } else null
            },
            dismissButton = {
                Row {
                    if (m.outgoing) {
                        TextButton(onClick = {
                            val target = m; deleteTarget = null
                            scope.launch { repository.deleteLocally(listOf(target.id)) }
                        }) { Text("Supprimer pour moi (cet appareil)") }
                    } else {
                        TextButton(onClick = {
                            val target = m; deleteTarget = null
                            scope.launch { repository.deleteLocally(listOf(target.id)) }
                        }) { Text("Supprimer pour moi (cet appareil)") }
                    }
                    TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
                }
            }
        )
    }

    // Feuille 📎 (miroir ModalBottomSheet « Partager » Android).
    if (attachSheet) {
        AlertDialog(
            onDismissRequest = { attachSheet = false },
            title = { Text("Partager") },
            text = {
                Column {
                    OutlinedButton(onClick = {
                        attachSheet = false
                        pickFile(arrayOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "webm", "mkv", "mov"))
                            ?.let { pendingMedia = it }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Photo ou vidéo") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        attachSheet = false
                        pickFile(null)?.let { pendingMedia = it }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Fichier") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { attachSheet = false }) { Text("Annuler") } }
        )
    }

    // Politique d'affichage du média (miroir MediaViewPolicyDialog v0.2.28).
    pendingMedia?.let { media ->
        var policy by remember(media) { mutableStateOf(DesktopStore.VIEW_FOREVER) }
        AlertDialog(
            onDismissRequest = { pendingMedia = null },
            title = { Text("Affichage du média") },
            text = {
                Column {
                    PolicyRadio("Affichage permanent (défaut)", DesktopStore.VIEW_FOREVER, policy) { policy = it }
                    PolicyRadio("Vue unique — détruit après affichage", DesktopStore.VIEW_ONCE, policy) { policy = it }
                    PolicyRadio("Vue unique + 1 relecture autorisée", DesktopStore.VIEW_TWICE, policy) { policy = it }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "La politique s'applique chez le destinataire : le média y est détruit après la (ou les) vue(s) autorisée(s). Votre copie reste relisible.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = media; val view = policy
                    pendingMedia = null
                    scope.launch {
                        repository.sendFile(peer, f, view)
                            .onSuccess { ServiceLocator.pollNow() }
                            .onFailure { e -> error = "Échec d'envoi : ${e.message}" }
                    }
                }) { Text("Envoyer") }
            },
            dismissButton = { TextButton(onClick = { pendingMedia = null }) { Text("Annuler") } }
        )
    }

    // Visionneuse plein écran (miroir ImageViewerDialog Android).
    viewerFile?.let { vf ->
        val bmp = remember(vf.absolutePath) { decodeImage(vf) }
        Dialog(onDismissRequest = { viewerFile = null }) {
            Column(Modifier.background(Color.Black).fillMaxSize()) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "image",
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { saveAs(vf)?.let { error = it } }) { Text("Enregistrer sous…") }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { viewerFile = null }) { Text("Fermer") }
                }
            }
        }
    }
}

// ---------------- bulles ----------------

@Composable
private fun TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PolicyRadio(label: String, value: String, current: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(label, fontSize = 13.sp)
    }
}

/** Clic gauche/droit (équivalent bureau du clic Android — Modifier.onClick
 * moderne : un matcher PAR BOUTON, jamais de dépendance dépréciée). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.nyxClickable(onLeft: () -> Unit = {}, onRight: () -> Unit = {}): Modifier =
    this.onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Primary)) { onLeft() }
        .onClick(matcher = PointerMatcher.mouse(androidx.compose.ui.input.pointer.PointerButton.Secondary)) { onRight() }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: DesktopStore.Msg,
    clock: Int,
    onOpen: (File) -> Unit,
    onViewer: (File) -> Unit,
    onDelete: () -> Unit,
) {
    val isMine = m.outgoing
    // Messages d'info système (cycle de vie du lien) : centrés sans bulle
    // (miroir Android — insérés par le repository, readLocal=1).
    val looksLikeInfo = m.kind == DesktopStore.KIND_TEXT && !isMine && m.readLocal == 1 &&
        (m.text.endsWith("comme contact.") || m.text.endsWith("invitation acceptée.") ||
            m.text.endsWith("votre invitation.") || m.text.contains("liste de contacts."))
    if (looksLikeInfo) {
        Text(
            m.text,
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        return
    }

    Box(
        Modifier.fillMaxWidth().padding(start = if (isMine) 64.dp else 0.dp, end = if (isMine) 0.dp else 64.dp)
    ) {
        Column(
            Modifier
                .background(
                    if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(10.dp)
                .nyxClickable(
                    onLeft = { if (m.kind == DesktopStore.KIND_FILE || m.kind == DesktopStore.KIND_TEXT) {} },
                    onRight = { onDelete() }
                )
        ) {
            if (m.kind == DesktopStore.KIND_FILE && m.fid.isNotBlank()) {
                MediaBubble(m, clock, onOpen, onViewer)
            } else {
                Text(m.text, color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(formatTs(m.ts), fontSize = 9.sp, color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline)
                if (isMine) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ChatFormat.outgoingStatusLabel(m),
                        fontSize = 9.sp,
                        color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f) else MaterialTheme.colorScheme.outline
                    )
                }
            }
            // Compte à rebours éphémère (miroir Android).
            if (m.expireAt > 0) {
                val remaining = m.expireAt - System.currentTimeMillis()
                Text(
                    ChatFormat.ephemeralCountdownLabel(remaining),
                    fontSize = 9.sp,
                    color = if (isMine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** Bulle média : image (aperçu + visionneuse), audio (lecteur), fichier (carte) + vues limitées + progression. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaBubble(
    m: DesktopStore.Msg,
    clock: Int,
    onOpen: (File) -> Unit,
    onViewer: (File) -> Unit,
) {
    val store = ServiceLocator.store
    val a = remember(tickKey(m), clock) { store.attachment(m.fid) } ?: run {
        Text("📄 ${m.text.removePrefix("📄 ").removePrefix("🎤 ").removePrefix("🖼 ").removePrefix("🎬 ")}", fontSize = 12.sp)
        return
    }
    val file = a.localPath.takeIf { it.isNotBlank() }?.let(::File)
    var revealed by remember(m.fid) { mutableStateOf(false) }

    // Badge de politique de vue (miroir Android — les deux côtés).
    ChatFormat.viewBadge(a.viewPolicy)?.let { badge ->
        Text(badge, fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
    }

    val limited = !m.outgoing && a.viewPolicy != DesktopStore.VIEW_FOREVER
    when {
        // Vues consommées → carte « média expiré » (miroir Android).
        limited && a.viewsLeft <= 0 -> {
            Text("${a.name} — média expiré (vue(s) consommée(s))", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        // Verrouillé → « Toucher pour afficher » (miroir Android).
        limited && !revealed -> {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .nyxClickable(onLeft = {
                        revealed = true
                        ServiceLocator.consumeMediaView(m.fid)
                    })
            ) {
                Text(
                    if (a.viewPolicy == DesktopStore.VIEW_ONCE) "Toucher pour afficher (1 vue, puis destruction)"
                    else "Toucher pour afficher (${a.viewsLeft} vue(s) restante(s))",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
            return
        }
    }

    when {
        a.state == DesktopStore.AT_FAILED -> {
            Text("Transfert échoué — ${a.name}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        a.state == DesktopStore.AT_PENDING || (a.bytesReceived in 1 until a.size) -> {
            // Réception / envoi en cours (miroir barres Android).
            LinearProgressIndicator(
                progress = { if (a.size > 0) a.bytesReceived.toFloat() / a.size else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (!m.outgoing) "Réception ${a.bytesReceived / ChunkCodec.CHUNK_PAYLOAD}/${(a.size + ChunkCodec.CHUNK_PAYLOAD - 1) / ChunkCodec.CHUNK_PAYLOAD}… — ${a.name}"
                else "Transfert ${ChunkCodec.humanSize(a.bytesReceived)} sur ${ChunkCodec.humanSize(a.size)}… — ${a.name}",
                fontSize = 10.sp
            )
        }
        file != null && a.mime.startsWith("image/") -> {
            val bmp = remember(file.absolutePath) { decodeImage(file) }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = a.name,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp))
                        .nyxClickable(onLeft = { onViewer(file) }),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("🖼 ${a.name}", fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text("${ChunkCodec.humanSize(a.size)} — ${a.name}", fontSize = 9.sp)
        }
        file != null && a.mime.startsWith("audio/") -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎤", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(a.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (DesktopAudioPlayer.isPlaying(file)) DesktopAudioPlayer.stop()
                    else if (!DesktopAudioPlayer.play(file)) onOpen(file) // m4a → lecteur système
                }) { Text(if (DesktopAudioPlayer.isPlaying(file)) "⏸" else "▶") }
            }
        }
        file != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a.mime.startsWith("video/")) "🎬" else "📄", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontSize = 12.sp)
                    Text("${ChunkCodec.humanSize(a.size)} — ${formatTs(m.ts)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { onOpen(file) }) { Text("Ouvrir") }
            }
        }
        else -> Text("📄 ${a.name}", fontSize = 12.sp)
    }
}

// ---------------- onglet Fichiers & médias ----------------

@Composable
private fun MediaTab(peer: String, tick: Long, onDelete: (fid: String, forEveryone: Boolean) -> Unit) {
    val attachments = remember(tick, peer) { ServiceLocator.store.attachmentsOf(peer) }
    var deleteFid by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (attachments.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Aucun fichier ou média échangé dans cette conversation.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
        }
        for ((m, a) in attachments) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .nyxClickable(onRight = { deleteFid = a.fid }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        a.mime.startsWith("image/") -> "🖼"
                        a.mime.startsWith("video/") -> "🎬"
                        a.mime.startsWith("audio/") -> "🎤"
                        else -> "📄"
                    }, fontSize = 16.sp
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontSize = 12.sp)
                    Text(
                        "${ChunkCodec.humanSize(a.size)} — ${if (m.outgoing) "Envoyé" else "Reçu"} — ${formatDay(m.ts)}",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.outline
                    )
                    if (a.state == DesktopStore.AT_PENDING) LinearProgressIndicator(
                        progress = { if (a.size > 0) a.bytesReceived.toFloat() / a.size else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (a.viewsLeft == 0) Text("média expiré (vue(s) consommée(s))", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
                if (a.state == DesktopStore.AT_READY && a.localPath.isNotBlank()) {
                    TextButton(onClick = { openExternally(File(a.localPath)) }) { Text("Ouvrir") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    deleteFid?.let { fid ->
        AlertDialog(
            onDismissRequest = { deleteFid = null },
            title = { Text("Supprimer ce média ?") },
            text = { Text("Seule la copie de CET appareil sera supprimée — celle de votre contact reste en place.") },
            confirmButton = {
                TextButton(onClick = { deleteFid = null; onDelete(fid, false) }) { Text("Supprimer pour moi (cet appareil)") }
            },
            dismissButton = { TextButton(onClick = { deleteFid = null }) { Text("Annuler") } }
        )
    }
}

// ---------------- utilitaires ----------------

private fun tickKey(m: DesktopStore.Msg): Long = m.ts

private fun formatTs(tsSec: Long): String =
    java.time.Instant.ofEpochSecond(tsSec).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDay(tsSec: Long): String =
    java.time.Instant.ofEpochSecond(tsSec).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))

private fun decodeImage(f: File): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).asImageBitmap()
}.getOrNull()

private fun openExternally(f: File) {
    runCatching { Desktop.getDesktop().open(f) }
        .onFailure { DesktopLog.w("nyx-media", "ouverture impossible : ${it.message?.take(60)}") }
}

private fun pickFile(extensions: Array<String>?): File? {
    val chooser = JFileChooser()
    chooser.isMultiSelectionEnabled = false
    extensions?.let {
        chooser.fileFilter = FileNameExtensionFilter(
            if (it.size <= 2) it.joinToString("/").uppercase()
            else "Images et vidéos", *it
        )
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun saveAs(src: File): String? {
    val chooser = JFileChooser()
    chooser.selectedFile = File(src.name)
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return runCatching {
        val target = chooser.selectedFile
        src.copyTo(target, overwrite = true)
        "Enregistré : ${target.name}"
    }.getOrElse { "Enregistrement impossible" }
}
