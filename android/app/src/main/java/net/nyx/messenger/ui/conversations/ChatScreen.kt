package net.nyx.messenger.ui.conversations

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.nyx.messenger.data.db.AttachmentEntity
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.p2p.CallManager
import net.nyx.messenger.media.MediaIngest
import net.nyx.messenger.repo.ChunkCodec
import net.nyx.messenger.repo.NyxRepository
import java.io.File
import java.text.DateFormat
import java.util.Date

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

    /** v0.2.19 : adresse normalisée du pair — les méthodes ne peuvent pas
     *  relire un paramètre de constructeur ; capturée une fois pour toutes. */
    private val peerLower = peerOnion.trim().lowercase()

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
        // v0.2.26 : la conversation est OUVERTE — pas de notification pour ce pair
        // (sinon marquage « lu » et notification se battent à chaque poll).
        net.nyx.messenger.notify.MessageNotifier.suppressedPeer = peerLower
        viewModelScope.launch {
            while (true) {
                // v0.2.18 : mode panique = AUCUN poll (ne pas consommer la
                // boîte du VRAI compte dans la base leurre).
                if (ServiceLocator.mode != ServiceLocator.SessionMode.PANIC) {
                    runCatching { repo.pollInbox() }
                }
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

    /** v0.2.26 : sortie de l'écran — les notifications reprennent. */
    override fun onCleared() {
        net.nyx.messenger.notify.MessageNotifier.suppressedPeer = null
        super.onCleared()
    }

    fun send(peer: String, text: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            // v0.2.26 : ttl éphémère de la conversation (0 = permanent) —
            // voyage signé dans l'événement, purge locale des deux côtés.
            val ttl = ephemeralTtl.value
            repo.send(peer, text, ttlSeconds = ttl).fold(
                onSuccess = { sendError.value = null; onDone(true) },
                onFailure = { e ->
                    sendError.value = e.message ?: "envoi impossible"
                    onDone(false)
                }
            )
        }
    }

    /* ---------- éphémère (v0.2.26) ---------- */

    /** TTL éphémère de cette conversation (0 = désactivé) — réactif pour l'UI. */
    val ephemeralTtl = MutableStateFlow(
        net.nyx.messenger.settings.SettingsManager.get(ServiceLocator.appContext()).ephemeralTtl(peerLower)
    )

    /** Cycle OFF → 1 h → 24 h → 7 j → OFF (persisté par conversation). */
    fun cycleEphemeralTtl(): Long {
        val next = when (ephemeralTtl.value) {
            0L -> 3_600L
            3_600L -> 86_400L
            86_400L -> 604_800L
            else -> 0L
        }
        ephemeralTtl.value = next
        net.nyx.messenger.settings.SettingsManager.get(ServiceLocator.appContext())
            .setEphemeralTtl(peerLower, next)
        return next
    }

    /** Flux méta d'une pièce jointe (bulle média — progression/état en direct). */
    fun attachmentFlow(fid: String): Flow<net.nyx.messenger.data.db.AttachmentEntity?> =
        db.attachmentDao().byFid(fid)

    /** v0.2.19 — tous les médias échangés avec ce pair (onglet dédié). */
    val attachments: Flow<List<AttachmentEntity>> = repo.attachmentsByPeer(peerOnion)

    /* ---------- suppression unitaire (v0.2.19) ---------- */

    /** Suppression LOCALE d'un message (cet appareil uniquement). */
    fun deleteLocally(m: MessageEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteMessagesLocally(listOf(m.eventId)).fold(
                onSuccess = { onDone() },
                onFailure = { e -> sendError.value = e.message ?: "suppression impossible" }
            )
        }
    }

    /** Suppression « POUR TOUS » d'un message SORTANT (ici + chez le pair). */
    fun deleteForEveryone(m: MessageEntity, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteForEveryone(peerLower, listOf(m.eventId)).fold(
                onSuccess = { onDone() },
                onFailure = { e -> sendError.value = e.message ?: "suppression impossible" }
            )
        }
    }

    /** Suppression d'un MÉDIA (onglet médias) — locale, ou « pour tous » si
     *  nous en sommes l'expéditeur (l'asymétrie est appliquée par le dépôt). */
    fun deleteMedia(a: AttachmentEntity, forEveryone: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.deleteAttachment(peerLower, a.fid, forEveryone).fold(
                onSuccess = { onDone() },
                onFailure = { e -> sendError.value = e.message ?: "suppression impossible" }
            )
        }
    }

    /** Flux du nombre de fragments reçus (progression de réception). */
    fun chunksFlow(fid: String): Flow<Int> = db.attachmentDao().chunksFlow(fid)

    /**
     * Envoi d'une pièce jointe choisie via un Uri (picker galerie/documents) :
     * lecture bornée, compression image (≤ 1600 px JPEG — divise le temps de
     * transport par ~10), puis dépôt fragmenté avec progression.
     * v0.2.28 : [viewPolicy] = politique d'affichage choisie dans le dialogue
     * ("forever" défaut | "once" | "twice").
     */
    fun sendUri(peer: String, uri: Uri, viewPolicy: String = net.nyx.messenger.data.db.AttachmentEntity.VIEW_FOREVER) {
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
                deposit(peer, bytes, name, mime, viewPolicy)
            }.onFailure { e ->
                sendError.value = e.message ?: "envoi impossible"
            }
        }
    }

    private suspend fun deposit(
        peer: String, bytes: ByteArray, name: String, mime: String,
        viewPolicy: String = net.nyx.messenger.data.db.AttachmentEntity.VIEW_FOREVER,
    ) {
        var currentFid = ""
        repo.sendFile(
            peer, bytes, name, mime,
            viewPolicy = viewPolicy,
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

    /**
     * v0.2.28 — consommation d'une vue d'un média à affichage limité (récep-
     * teur) : décrémente le compteur et détruit le fichier à 0 vue restante.
     */
    fun consumeView(fid: String) {
        viewModelScope.launch {
            runCatching { repo.consumeMediaView(fid) }
                .onFailure { e -> android.util.Log.w("nyx-media", "vue non consommée : ${e.message}") }
        }
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // v0.2.26 : éphémère de la conversation (0 = désactivé).
    val ephemeralTtl by vm.ephemeralTtl.collectAsState()
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val peer = peerOnion.trim().lowercase()
    val ctx = LocalContext.current
    // v0.2.19 : onglets (Messages / Fichiers & médias) + cible de suppression.
    var tab by remember { mutableStateOf(0) }
    var deleteMsg by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteAtt by remember { mutableStateOf<AttachmentEntity?>(null) }
    val scope = rememberCoroutineScope()
    // v0.2.28 — média en attente de choix de politique d'affichage.
    var pendingMediaUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // v0.2.28 — horloge du compte à rebours éphémère (tick 30 s).
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    // v0.2.17 : pickers système — galerie (photos + vidéos) et documents.
    // v0.2.28 : l'URI passe d'abord par le dialogue « politique d'affichage »
    // (permanent / vue unique / 1 relecture) AVANT l'envoi.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { pendingMediaUri = it } }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { pendingMediaUri = it } }

    // v0.2.17 : permission micro → enregistrement.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startVoice()
        else vm.sendError.value = ctx.getString(R.string.record_denied)
    }

    // v0.2.20 : permission caméra → appel visio.
    val videoPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CallManager.call(
                peer, contact?.alias?.ifBlank { peer.take(10) + "…" } ?: (peer.take(10) + "…"),
                withVideo = true
            )
        } else vm.sendError.value = ctx.getString(R.string.camera_denied)
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
                    // v0.2.18 — APPEL AUDIO (ADR-015) : signalisation journal +
                    // média direct onion-to-onion. Réservé aux contacts mutuels
                    // (même verrou que les messages).
                    IconButton(
                        onClick = {
                            CallManager.call(peer, contact?.alias?.ifBlank { peer.take(10) + "…" } ?: (peer.take(10) + "…"))
                        },
                        enabled = contact?.status == net.nyx.messenger.data.db.ContactStatus.ACCEPTED &&
                            CallManager.ui.value.phase == CallManager.Phase.IDLE
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_start))
                    }
                    // v0.2.20 — VISIO (demande opérateur : « l'appel visio ») :
                    // H.264 sur le même canal direct onion-to-onion que l'audio
                    // (ADR-015 prolongé). Permission caméra demandée à l'appui.
                    IconButton(onClick = {
                        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            CallManager.call(
                                peer,
                                contact?.alias?.ifBlank { peer.take(10) + "…" } ?: (peer.take(10) + "…"),
                                withVideo = true
                            )
                        } else {
                            videoPerm.launch(android.Manifest.permission.CAMERA)
                        }
                    }) {
                        Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.call_start_video))
                    }
                    IconButton(onClick = { onOpenProfile(peerOnion) }) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.contact_profile_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // v0.2.19 : onglets « Messages » / « Fichiers & médias » (demande
            // opérateur : « un onglet qui liste les fichiers et medias échangés »).
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.chat_tab_messages)) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.chat_tab_media)) })
            }
            if (contact?.status != net.nyx.messenger.data.db.ContactStatus.ACCEPTED) {
                Text(
                    stringResource(R.string.chat_not_mutual),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (tab == 0) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    items(messages, key = { it.eventId }) { m ->
                        MessageRow(
                            m, vm, sendProgress,
                            onOpenImage = { viewerPath = it },
                            onDelete = { deleteMsg = m },
                            nowMs = nowTick
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
                    // v0.2.26 — MESSAGES ÉPHÉMÈRES : cycle OFF → 1 h → 24 h → 7 j.
                    // Le TTL s'applique aux MESSAGES TEXTE (voyage signé, purge
                    // locale des deux côtés) ; les pièces jointes restent permanentes.
                    IconButton(onClick = {
                        val next = vm.cycleEphemeralTtl()
                        val label = when (next) {
                            3_600L -> "1 h"
                            86_400L -> "24 h"
                            604_800L -> "7 j"
                            else -> null
                        }
                        android.widget.Toast.makeText(
                            ctx,
                            if (label == null) ctx.getString(R.string.chat_ephemeral_off)
                            else ctx.getString(R.string.chat_ephemeral_on, label),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = stringResource(R.string.chat_ephemeral_desc),
                            tint = if (ephemeralTtl > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            } else {
                // Onglet FICHIERS & MÉDIAS (v0.2.19) — liste complète des
                // échanges de cette conversation, suppression par appui long.
                MediaTab(
                    vm = vm,
                    onOpenImage = { viewerPath = it },
                    onDelete = { deleteAtt = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
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

    // v0.2.19 : dialogue de SUPPRESSION (asymétrie exigée — l'expéditeur peut
    // supprimer chez le destinataire, le destinataire ne supprime que SA copie).
    val targetMsg = deleteMsg
    val targetAtt = deleteAtt
    if (targetMsg != null || targetAtt != null) {
        val isMedia = targetMsg == null
        val outgoing = targetMsg?.outgoing ?: targetAtt!!.outgoing
        fun close() { deleteMsg = null; deleteAtt = null }
        fun toastDone() = android.widget.Toast.makeText(
            ctx, ctx.getString(R.string.delete_done), android.widget.Toast.LENGTH_SHORT
        ).show()
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(stringResource(if (isMedia) R.string.delete_media_title else R.string.delete_msg_title)) },
            text = {
                Text(
                    stringResource(
                        if (outgoing) R.string.delete_for_everyone_desc
                        else R.string.delete_local_only_note
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Column {
                    if (outgoing) {
                        TextButton(onClick = {
                            val m = targetMsg; val a = targetAtt
                            close()
                            scope.launch {
                                if (m != null) vm.deleteForEveryone(m) {
                                    android.widget.Toast.makeText(
                                        ctx, ctx.getString(R.string.delete_queued),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else if (a != null) vm.deleteMedia(a, forEveryone = true) {
                                    android.widget.Toast.makeText(
                                        ctx, ctx.getString(R.string.delete_queued),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.delete_for_everyone),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = {
                        val m = targetMsg; val a = targetAtt
                        close()
                        scope.launch {
                            if (m != null) vm.deleteLocally(m) { toastDone() }
                            else if (a != null) vm.deleteMedia(a, forEveryone = false) { toastDone() }
                        }
                    }) { Text(stringResource(R.string.delete_for_me)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel)) }
            }
        )
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

    // v0.2.28 — choix de la politique d'affichage du média AVANT l'envoi
    // (demande opérateur : « afficher le média 1 fois puis destruction du
    // média, relecture autorisée 1 fois, ou affichage permanent »).
    pendingMediaUri?.let { uri ->
        MediaViewPolicyDialog(
            onPick = { policy ->
                pendingMediaUri = null
                vm.sendUri(peer, uri, policy)
            },
            onDismiss = { pendingMediaUri = null }
        )
    }
}

/** Une ligne de message : bulle texte OU carte média (v0.2.17) + accusés.
 *  v0.2.19 : APPUI LONG = suppression (locale, ou « pour tous » si sortant).
 *  v0.2.28 : compte à rebours éphémère sous la bulle ([nowMs] tick 30 s). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: MessageEntity,
    vm: ChatViewModel,
    sendProgress: Map<String, Pair<Int, Int>>,
    onOpenImage: (String) -> Unit,
    onDelete: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) {
    val outgoing = m.outgoing
    Column(
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = onDelete)
    ) {
        if (m.kind == 1) {
            MediaBubble(m, vm, sendProgress, onOpenImage, onDelete)
        } else {
            Text(
                m.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(4.dp)
            )
        }
        // v0.2.28 — COMPTE À REBOURS ÉPHÉMÈRE (demande opérateur : « un
        // compteur à côté du message qui indique le temps restant »).
        if (m.expireAt > 0L) {
            Text(
                ephemeralCountdownLabel(m.expireAt - nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
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

/** v0.2.28 — libellé humain du temps restant d'un message éphémère. */
private fun ephemeralCountdownLabel(remainingMs: Long): String = when {
    remainingMs <= 0L -> "éphémère : expiration imminente"
    remainingMs < 60_000L -> "éphémère : moins d'1 min"
    remainingMs < 3_600_000L -> "éphémère : ${remainingMs / 60_000L} min restantes"
    remainingMs < 86_400_000L -> "éphémère : ${remainingMs / 3_600_000L} h restantes"
    else -> "éphémère : ${remainingMs / 86_400_000L} j restants"
}

/**
 * v0.2.28 — dialogue de politique d'affichage d'un média AVANT l'envoi
 * (style Instagram) : affichage permanent / vue unique (destruction après
 * affichage) / vue unique + 1 relecture autorisée.
 */
@Composable
private fun MediaViewPolicyDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember {
        mutableStateOf(net.nyx.messenger.data.db.AttachmentEntity.VIEW_FOREVER)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Affichage du média") },
        text = {
            Column {
                listOf(
                    net.nyx.messenger.data.db.AttachmentEntity.VIEW_FOREVER to
                        "Affichage permanent (défaut)",
                    net.nyx.messenger.data.db.AttachmentEntity.VIEW_ONCE to
                        "Vue unique — détruit après affichage",
                    net.nyx.messenger.data.db.AttachmentEntity.VIEW_TWICE to
                        "Vue unique + 1 relecture autorisée",
                ).forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = choice == value, onClick = { choice = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "La politique s'applique chez le destinataire : le média y est " +
                        "détruit après la (ou les) vue(s) autorisée(s). Votre copie reste relisible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPick(choice) }) { Text("Envoyer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

/** Carte d'une pièce jointe (photo/vidéo/audio/fichier) avec progression.
 *  v0.2.19 : appui long = suppression (même dialogue que les bulles texte). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaBubble(
    m: MessageEntity,
    vm: ChatViewModel,
    sendProgress: Map<String, Pair<Int, Int>>,
    onOpenImage: (String) -> Unit,
    onDelete: () -> Unit = {},
) {
    val att by vm.attachmentFlow(m.fid).collectAsState(initial = null)
    val receivedChunks by vm.chunksFlow(m.fid).collectAsState(initial = 0)
    val a = att
    if (a == null) {
        Text(m.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp))
        return
    }
    // v0.2.28 — VUE LIMITÉE (récepteur) : un média « once »/« twice » pas
    // encore révélé s'affiche verrouillé ; chaque révélation consomme une vue
    // (compteur DB) ; à 0 vues le fichier est DÉTRUIT ([NyxRepository
    // .consumeMediaView]) et la bulle affiche « média expiré ».
    var revealed by remember(m.fid) { mutableStateOf(false) }
    val limited = !m.outgoing &&
        a.viewPolicy != net.nyx.messenger.data.db.AttachmentEntity.VIEW_FOREVER
    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = {}, onLongClick = onDelete)
            .padding(8.dp)
    ) {
        // Badge politique (les deux côtés) : la vue limitée est explicite.
        if (a.viewPolicy == net.nyx.messenger.data.db.AttachmentEntity.VIEW_ONCE ||
            a.viewPolicy == net.nyx.messenger.data.db.AttachmentEntity.VIEW_TWICE
        ) {
            Text(
                if (a.viewPolicy == net.nyx.messenger.data.db.AttachmentEntity.VIEW_ONCE)
                    "vue unique"
                else "vue unique + 1 relecture",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        when {
            a.state == NyxRepository.AT_FAILED -> {
                Text(
                    "${stringResource(R.string.media_failed)} — ${a.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            limited && a.viewsLeft <= 0 -> {
                // v0.2.28 — vues épuisées : le média a été détruit localement.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${a.name} — média expiré (vue(s) consommée(s))",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            limited && !revealed -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            revealed = true
                            vm.consumeView(m.fid)
                        }
                        .padding(10.dp)
                ) {
                    Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (a.viewPolicy == net.nyx.messenger.data.db.AttachmentEntity.VIEW_ONCE)
                            "Toucher pour afficher (1 vue, puis destruction)"
                        else "Toucher pour afficher (${a.viewsLeft} vue(s) restante(s))",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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

/**
 * v0.2.19 — ONGLET « FICHIERS & MÉDIAS » (demande opérateur : « un onglet qui
 * liste les fichiers et medias échangés ») : tous les transferts de la
 * conversation (photos, vidéos, vocaux, documents), du plus récent au plus
 * ancien. Appui = ouvrir (image) ou enregistrer dans Téléchargements ; appui
 * long = suppression (locale, ou « pour tous » si nous en sommes l'expéditeur).
 */
@Composable
private fun MediaTab(
    vm: ChatViewModel,
    onOpenImage: (String) -> Unit,
    onDelete: (AttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val atts by vm.attachments.collectAsState(initial = emptyList())
    if (atts.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.media_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }
    LazyColumn(modifier.padding(horizontal = 12.dp)) {
        items(atts, key = { it.fid }) { a ->
            MediaListItem(a, onOpenImage, onDelete)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaListItem(
    a: AttachmentEntity,
    onOpenImage: (String) -> Unit,
    onDelete: (AttachmentEntity) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (a.state == NyxRepository.AT_READY) {
                        if (a.mime.startsWith("image/")) {
                            onOpenImage(a.localPath)
                        } else {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    MediaIngest.saveToDownloads(ctx, a.localPath, a.mime)
                                }
                                android.widget.Toast.makeText(
                                    ctx,
                                    saved?.let { ctx.getString(R.string.media_saving, it) }
                                        ?: ctx.getString(R.string.media_save_failed),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                onLongClick = { onDelete(a) }
            )
            .padding(vertical = 6.dp)
    ) {
        if (a.state == NyxRepository.AT_READY && a.mime.startsWith("image/")) {
            MediaThumbnail(a.localPath)
        } else {
            Icon(
                when {
                    a.mime.startsWith("image/") -> Icons.Filled.Image
                    a.mime.startsWith("video/") -> Icons.Filled.Videocam
                    a.mime.startsWith("audio/") -> Icons.Filled.Mic
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                ChunkCodec.humanSize(a.size) + " — " +
                    stringResource(if (a.outgoing) R.string.media_direction_out else R.string.media_direction_in) +
                    " — " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(a.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (a.state) {
            NyxRepository.AT_TRANSFER -> {
                Text(
                    stringResource(R.string.media_receiving, 0, a.totalChunks)
                        .substringBefore(" ") + "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NyxRepository.AT_FAILED -> {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.media_failed),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Vignette d'une image prête (décode échantillonné ~256 px — média chiffré au repos). */
@Composable
private fun MediaThumbnail(path: String) {
    val ctx = LocalContext.current
    val bitmap = remember(path) {
        runCatching {
            val bytes = net.nyx.messenger.media.MediaVault.readBytes(ctx, path)
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
        )
    } else {
        Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Image prête : aperçu décodé (échantillonné ~1024 px) → visionneuse. */
@Composable
private fun ReadyImage(path: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val bitmap = remember(path) {
        runCatching {
            // v0.2.18 : fichier CHIFFRÉ au repos — déchiffrement mémoire avant décodage.
            val bytes = net.nyx.messenger.media.MediaVault.readBytes(ctx, path)
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            )
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

    fun toggle(path: String, name: String, context: android.content.Context) {
        if (playing.value) {
            stop()
            return
        }
        runCatching {
            // v0.2.18 : le fichier est chiffré au repos — copie TEMPORAIRE
            // déchiffrée pour MediaPlayer (purgee à la sortie du chat).
            val tmp = net.nyx.messenger.media.MediaVault.tempPlain(context, path, name)
                ?: error("média illisible")
            player = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                prepare()
                setOnCompletionListener {
                    playing.value = false
                    releasePlayer()
                    runCatching { tmp.delete() }
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
    val ctx = LocalContext.current
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            // v0.2.18 : supprime la copie temporaire déchiffrée du lecteur.
            net.nyx.messenger.media.MediaVault.clearTemp(ctx)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = { player.toggle(path, name, ctx) }) {
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
                runCatching {
                    // v0.2.18 : déchiffrement mémoire (fichier chiffré au repos).
                    val bytes = net.nyx.messenger.media.MediaVault.readBytes(ctx, path)
                    val decoded = if (bytes != null) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        BitmapFactory.decodeFile(path) // rélictuel clair v0.2.17
                    }
                    decoded ?: return@runCatching null
                }.getOrNull()
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
