package net.nyx.messenger.ui.conversations

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.messenger.R
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.media.MediaIngest
import net.nyx.messenger.repo.ChunkCodec
import net.nyx.messenger.repo.NyxRepository
import java.io.File

/**
 * Écran de chat — l'envoi et la réception passent par [NyxRepository]
 * (chiffrement E2E + dépôt via Tor). Poll inbox toutes les 10 s (v0.1).
 * v0.2.15 : clavier (imePadding), accusés ✓/✓✓, marquage « lu ».
 * v0.2.17 (demande opérateur : « toutes les features des autres messengers ») :
 *  - PIÈCES JOINTES : bouton 📎 (photo/vidéo via le picker système, tout
 *    fichier via le sélecteur de documents) — transport fragmenté E2E
 *    (ChunkCodec, zéro changement serveur), progression dans la bulle ;
 *  - NOTES VOCALES : bouton micro (MediaRecorder → .m4a → même canal fichier),
 *    lecture intégrée côté destinataire ;
 *  - IMAGES : aperçu dans la bulle + visionneuse plein écran ;
 *  - VIDÉOS/FICHIERS/AUDIO : carte nom + taille, enregistrement dans
 *    Téléchargements en un appui.
 */
class ChatViewModel(peerOnion: String) : ViewModel() {
    private val db = ServiceLocator.database
    val repo = ServiceLocator.repository

    /** Conversation observable — re-émet à chaque écriture dans `messages`. */
    val messages: Flow<List<MessageEntity>> = db.messageDao().conversation(peerOnion)

    /** v0.2.11 : dernier échec d'envoi (null = rien à afficher). */
    val sendError = MutableStateFlow<String?>(null)

    /** v0.2.14 : ligne contact du pair (alias + statut — bannière non mutuel). */
    val contact = db.contactDao().byOnion(peerOnion.trim().lowercase())

    /** v0.2.17 : progression des dépôts de fichiers (fid → (déposés, total)). */
    val sendProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())

    /** v0.2.17 : enregistrement vocal en cours. */
    val isRecording = MutableStateFlow(false)

    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { repo.pollInbox() }
                delay(10_000)
            }
        }
        // v0.2.15 — MARQUAGE « LU » à l'ouverture (les accusés « read » suivent).
        viewModelScope.launch {
            messages.collect { list ->
                if (list.any { !it.outgoing && it.readLocal == 0 }) {
                    runCatching { repo.markConversationRead(peerOnion) }
                }
            }
        }
    }

    fun send(peer: String, text: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repo.send(peer, text).fold(
                onSuccess = { sendError.value = null; onDone(true) },
                onFailure = { e ->
                    sendError.value = e.message ?: "envoi impossible"
                    onDone(false)
                }
            )
        }
    }

    /** Flux méta d'une pièce jointe (bulle média — progression/état en direct). */
    fun attachmentFlow(fid: String): Flow<net.nyx.messenger.data.db.AttachmentEntity?> =
        db.attachmentDao().byFid(fid)

    /** Flux du nombre de fragments reçus (progression de réception). */
    fun chunksFlow(fid: String): Flow<Int> = db.attachmentDao().chunksFlow(fid)

    /**
     * Envoi d'une pièce jointe choisie via un Uri (picker galerie/documents) :
     * lecture bornée, compression image (≤ 1600 px JPEG — divise le temps de
     * transport par ~10), puis dépôt fragmenté avec progression.
     */
    fun sendUri(peer: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val ctx = ServiceLocator.appContext()
                var (name, mime) = uriMeta(ctx, uri)
                var bytes = MediaIngest.readBytes(ctx, uri)
                if (MediaIngest.isCompressibleImage(mime)) {
                    MediaIngest.compressImage(ctx, uri, name)?.let { c ->
                        if (c.bytes.size < bytes.size) {
                            bytes = c.bytes; mime = c.mime; name = c.name
                        }
                    }
                }
                deposit(peer, bytes, name, mime)
            }.onFailure { e ->
                sendError.value = e.message ?: "envoi impossible"
            }
        }
    }

    private suspend fun deposit(peer: String, bytes: ByteArray, name: String, mime: String) {
        var currentFid = ""
        repo.sendFile(
            peer, bytes, name, mime,
            onStart = { fid ->
                currentFid = fid
                sendProgress.value = sendProgress.value + (fid to (0 to 1))
            },
            onProgress = { done, total ->
                if (currentFid.isNotEmpty()) {
                    sendProgress.value = sendProgress.value + (currentFid to (done to total))
                }
            }
        ).fold(
            onSuccess = { fid ->
                sendError.value = null
                sendProgress.value = sendProgress.value - fid
            },
            onFailure = { e ->
                sendError.value = e.message ?: "envoi impossible"
                if (currentFid.isNotEmpty()) sendProgress.value = sendProgress.value - currentFid
            }
        )
    }

    /* ---------- notes vocales (v0.2.17) ---------- */

    fun startVoice() {
        runCatching {
            val ctx = ServiceLocator.appContext()
            val dir = File(ctx.filesDir, "media").apply { mkdirs() }
            val f = File(dir, "voice-${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
            else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            voiceFile = f
            isRecording.value = true
        }.onFailure { e ->
            sendError.value = e.message ?: "enregistrement impossible"
        }
    }

    /** Arrêt + envoi du vocal (même canal fichier — .m4a). */
    fun stopAndSendVoice(peer: String) {
        val rec = recorder
        val f = voiceFile
        isRecording.value = false
        recorder = null
        voiceFile = null
        if (rec == null || f == null) return
        runCatching { rec.stop() }
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                deposit(peer, bytes, "vocal-${f.nameWithoutExtension}.m4a", "audio/mp4")
            }.onFailure { e ->
                sendError.value = e.message ?: "envoi impossible"
            }
        }
    }

    /** Nom + MIME d'un Uri (OpenableColumns, repli générique). */
    private fun uriMeta(ctx: android.content.Context, uri: Uri): Pair<String, String> {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        var name = "fichier"
        runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) name = it }
            }
        }
        return name to mime
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(peerOnion: String, onOpenProfile: (String) -> Unit = {}) {
    val vm: ChatViewModel = viewModel(key = peerOnion, factory = viewModelFactory {
        ChatViewModel(peerOnion)
    })
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sendError by vm.sendError.collectAsState()
    val contact by vm.contact.collectAsState(initial = null)
    val sendProgress by vm.sendProgress.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val peer = peerOnion.trim().lowercase()
    val ctx = LocalContext.current

    // v0.2.17 : pickers système — galerie (photos + vidéos) et documents.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.sendUri(peer, it) } }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.sendUri(peer, it) } }

    // v0.2.17 : permission micro → enregistrement.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startVoice()
        else vm.sendError.value = ctx.getString(R.string.record_denied)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        contact?.alias?.ifBlank { null }
                            ?: (peerOnion.take(12) + "…")
                    )
                },
                actions = {
                    IconButton(onClick = { onOpenProfile(peerOnion) }) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.contact_profile_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (contact?.status != net.nyx.messenger.data.db.ContactStatus.ACCEPTED) {
                Text(
                    stringResource(R.string.chat_not_mutual),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                items(messages, key = { it.eventId }) { m ->
                    MessageRow(
                        m, vm, sendProgress,
                        onOpenImage = { viewerPath = it }
                    )
                }
            }
            // v0.2.17 : barre d'enregistrement vocal (appui = arrêt + envoi).
            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { vm.stopAndSendVoice(peer) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.record_stop),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        stringResource(R.string.voice_recording),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                IconButton(onClick = { showAttach = true }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_desc))
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_hint)) }
                )
                if (input.isBlank()) {
                    IconButton(onClick = {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.record_start))
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                input = ""
                                vm.send(peer, text) { ok ->
                                    if (!ok) android.util.Log.w("nyx-send", "envoi signalé en échec à l'UI")
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
            sendError?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.send_error_prefix) + " " + it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }

    // v0.2.17 : feuille de partage (galerie / documents).
    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }) {
            Text(
                stringResource(R.string.attach_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.attach_photo_video)) },
                leadingContent = { Icon(Icons.Filled.Image, null) },
                modifier = Modifier.clickable {
                    showAttach = false
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.attach_file)) },
                leadingContent = { Icon(Icons.Filled.Folder, null) },
                modifier = Modifier.clickable {
                    showAttach = false
                    pickFile.launch(arrayOf("*/*"))
                }
            )
        }
    }

    // v0.2.17 : visionneuse plein écran des images.
    viewerPath?.let { path ->
        ImageViewerDialog(
            path = path,
            onDismiss = { viewerPath = null }
        )
    }
}

/** Une ligne de message : bulle texte OU carte média (v0.2.17) + accusés. */
@Composable
private fun MessageRow(
    m: MessageEntity,
    vm: ChatViewModel,
    sendProgress: Map<String, Pair<Int, Int>>,
    onOpenImage: (String) -> Unit,
) {
    val outgoing = m.outgoing
    Column(
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        if (m.kind == 1) {
            MediaBubble(m, vm, sendProgress, onOpenImage)
        } else {
            Text(
                m.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(4.dp)
            )
        }
        outgoingStatusLabel(m)?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (m.readAt > 0L) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Carte d'une pièce jointe (photo/vidéo/audio/fichier) avec progression. */
@Composable
private fun MediaBubble(
    m: MessageEntity,
    vm: ChatViewModel,
    sendProgress: Map<String, Pair<Int, Int>>,
    onOpenImage: (String) -> Unit,
) {
    val att by vm.attachmentFlow(m.fid).collectAsState(initial = null)
    val receivedChunks by vm.chunksFlow(m.fid).collectAsState(initial = 0)
    val a = att
    if (a == null) {
        Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp))
        return
    }
    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        when {
            a.state == NyxRepository.AT_FAILED -> {
                Text(
                    "${stringResource(R.string.media_failed)} — ${a.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            a.state == NyxRepository.AT_READY -> when {
                a.mime.startsWith("image/") -> ReadyImage(a.localPath, onOpenImage)
                a.mime.startsWith("audio/") -> ReadyAudio(a.localPath, a.name)
                else -> ReadyFileCard(a.localPath, a.name, a.mime, a.size, a.mime.startsWith("video/"))
            }
            else -> {
                // Transfert en cours — progression dépôt (sortant) ou réception (entrant).
                Text(a.name, style = MaterialTheme.typography.bodyMedium)
                val fraction: Float? = if (m.outgoing) {
                    sendProgress[m.fid]?.let { (done, total) -> if (total > 0) done / total.toFloat() else null }
                } else {
                    if (a.totalChunks > 0) receivedChunks / a.totalChunks.toFloat() else null
                }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Text(
                    if (m.outgoing) stringResource(R.string.media_transfer_progress,
                        ChunkCodec.humanSize((a.size * ((fraction ?: 0f)).toLong())),
                        ChunkCodec.humanSize(a.size))
                    else stringResource(R.string.media_receiving, receivedChunks, a.totalChunks),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Image prête : aperçu décodé (échantillonné ~1024 px) → visionneuse. */
@Composable
private fun ReadyImage(path: String, onOpen: (String) -> Unit) {
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    (bitmap.width.toFloat() / bitmap.height).coerceIn(0.6f, 1.8f)
                )
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpen(path) }
        )
    } else {
        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Lecteur audio minimal (play/pause — MediaPlayer local, libéré à la sortie). */
private class AudioPlayer {
    private var player: MediaPlayer? = null
    var playing = mutableStateOf(false)
        private set

    fun toggle(path: String) {
        if (playing.value) {
            stop()
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    playing.value = false
                    releasePlayer()
                }
                start()
            }
            playing.value = true
        }.onFailure { stop() }
    }

    private fun releasePlayer() {
        player?.runCatching { release() }
        player = null
    }

    fun stop() {
        releasePlayer()
        playing.value = false
    }
}

/** Vocal prêt : bouton lecture/pause + nom. */
@Composable
private fun ReadyAudio(path: String, name: String) {
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = { player.toggle(path) }) {
            Icon(
                if (player.playing.value) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = name
            )
        }
        Text(
            stringResource(R.string.record_start),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Vidéo / document prêt : carte nom + taille, appui = enregistrement Téléchargements. */
@Composable
private fun ReadyFileCard(path: String, name: String, mime: String, size: Long, isVideo: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        MediaIngest.saveToDownloads(ctx, path, mime)
                    }
                    android.widget.Toast.makeText(
                        ctx,
                        saved?.let { ctx.getString(R.string.media_saving, it) }
                            ?: ctx.getString(R.string.media_save_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .padding(vertical = 4.dp)
    ) {
        Icon(
            if (isVideo) Icons.Filled.Videocam else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                ChunkCodec.humanSize(size) + " — " + stringResource(R.string.media_save),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Visionneuse plein écran (image locale décodée en taille écran). */
@Composable
private fun ImageViewerDialog(path: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
        ) {
            val bitmap = remember(path) {
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                IconButton(onClick = {
                    runCatching {
                        val saved = MediaIngest.saveToDownloads(
                            ctx, path, "image/jpeg"
                        )
                        android.widget.Toast.makeText(
                            ctx,
                            saved?.let { ctx.getString(R.string.media_saving, it) }
                                ?: ctx.getString(R.string.media_save_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.media_save),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** Petite fabrique locale (évite une dépendance viewmodel-compose supplémentaire). */
private inline fun <VM : ViewModel> viewModelFactory(crossinline create: () -> VM):
        androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
