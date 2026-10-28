package net.nyx.messenger.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.nyx.messenger.desktop.DesktopPaths.mediaDir
import net.nyx.messenger.desktop.DesktopPaths.storeDir
import java.io.File

/**
 * Magasin applicatif desktop — remplace Room/SQLCipher (spec §2) : fichiers
 * JSON atomiques par agrégat + état mémoire sous un verrou applicatif.
 * Les DAO Android deviennent des fonctions ; les sémantiques (états message,
 * statuts contact, accusés, fragments) sont les MÊMES que les entités Room
 * (NyxDatabase) pour que le portage du repository reste 1:1.
 */
class DesktopStore {

    // ---------- entités (miroir des entités Room Android) ----------

    // Miroir Android ContactStatus : REMOVED = « supprimé par ce contact »,
    // REMOVED_BY_ME = « contact supprimé » (v0.3.4 — distinction miroir v0.2.19).
    enum class ContactStatus { PENDING, INCOMING, ACCEPTED, DECLINED, REMOVED, REMOVED_BY_ME }

    @Serializable
    data class Contact(
        val onion: String,
        val alias: String,
        val addedAt: Long,
        var status: ContactStatus = ContactStatus.PENDING,
        var notifiedAt: Long = 0L,
        var notifyPending: Int = 0,
        var blockedAt: Long = 0L,
    )

    /** États d'un message (miroir NyxRepository Android). */
    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
        const val KIND_TEXT = 0
        const val KIND_FILE = 1

        const val AT_PENDING = 0
        const val AT_READY = 1
        const val AT_FAILED = 2

        /** v0.3.4 — statut ARCHIVÉ (miroir ContactStatus.archived Android : décliné/supprimé/les deux). */
        fun archivedStatus(s: ContactStatus): Boolean =
            s == ContactStatus.DECLINED || s == ContactStatus.REMOVED || s == ContactStatus.REMOVED_BY_ME

        // v0.3.4 — politiques d'affichage des médias (miroir AttachmentEntity Android v0.2.28).
        const val VIEW_FOREVER = "forever"
        const val VIEW_ONCE = "once"
        const val VIEW_TWICE = "twice"
    }

    @Serializable
    data class Msg(
        val id: String,               // eventId (sha256 canonique) ou uuid local
        val peer: String,
        val outgoing: Boolean,
        val text: String,
        val ts: Long,
        var state: Int = STATE_PENDING,
        var readLocal: Int = 0,
        var rcptDelivered: Long = 0L,
        var rcptRead: Long = 0L,
        var rcptPending: Int = 0,
        var kind: Int = KIND_TEXT,
        var fid: String = "",
        var expireAt: Long = 0L,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class AttachmentMeta(
        val fid: String,
        var name: String,
        var mime: String,
        var size: Long,
        var sha256: String,
        var state: Int = AT_PENDING,
        var localPath: String = "",
        var bytesReceived: Long = 0,
        // v0.3.4 — vue limitée (miroir Android : once→1, twice→2, forever→-1 ;
        // l'expéditeur relit toujours : -1).
        var viewPolicy: String = VIEW_FOREVER,
        var viewsLeft: Int = -1,
    )

    /** v0.3.4 — suppression « pour tous » différée (miroir table del_pending Android v0.2.19). */
    @Serializable
    data class DelPending(
        val peer: String,
        val ids: List<String>,
        val createdAt: Long,
    )

    @Serializable
    data class PeerProfile(
        val onion: String,
        var displayName: String = "",
        var bio: String = "",
        var avatarB64: String = "",
        // v0.3.4 — champs complets de la fiche (miroir ProfileKeys Android).
        var website: String = "",
        var location: String = "",
        var fetchedAt: Long = 0,
    )

    @Serializable
    data class MyProfile(
        var displayName: String = "",
        var bio: String = "",
        var website: String = "",
        var location: String = "",
        var avatarB64: String = "",
        // v0.3.4 — visibilité PAR CHAMP (miroir ProfileField(public) Android —
        // ADR-010 : RIEN n'est public par défaut, les champs privés ne
        // quittent JAMAIS l'appareil).
        var displayNamePublic: Boolean = false,
        var bioPublic: Boolean = false,
        var websitePublic: Boolean = false,
        var locationPublic: Boolean = false,
        var avatarPublic: Boolean = false,
    )

    // ---------- fichiers ----------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fContacts = File(storeDir, "contacts.json")
    private val fMessages = File(storeDir, "conversations.json")
    private val fSeq = File(storeDir, "seq_state.json")
    private val fAttachments = File(storeDir, "attachments.json")
    private val fProfiles = File(storeDir, "profiles.json")
    private val fMyProfile = File(storeDir, "my_profile.json")
    private val fCursors = File(storeDir, "poll_cursors.json")
    private val fDelPending = File(storeDir, "del_pending.json")

    // ---------- état mémoire (verrou unique) ----------

    private val lock = Any()
    private var contacts = ArrayList<Contact>()
    private var messages = ArrayList<Msg>()
    private var seqState = HashMap<String, Long>()
    private var attachments = HashMap<String, AttachmentMeta>()
    private var profiles = HashMap<String, PeerProfile>()
    private var myProfile = MyProfile()
    private var cursors = HashMap<String, Long>()     // réplica → curseur inbox
    private val chunks = HashMap<String, HashMap<Int, ByteArray>>() // fid → (index → data)
    private var delPending = ArrayList<DelPending>()
    private var loaded = false

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            contacts = JsonStore.read(fContacts, ListSerializer(Contact.serializer()))?.toCollection(ArrayList()) ?: ArrayList()
            messages = JsonStore.read(fMessages, ListSerializer(Msg.serializer()))?.toCollection(ArrayList()) ?: ArrayList()
            seqState = HashMap(JsonStore.read(fSeq, MapSerializer(String.serializer(), Long.serializer())) ?: emptyMap())
            attachments = HashMap(JsonStore.read(fAttachments, MapSerializer(String.serializer(), AttachmentMeta.serializer())) ?: emptyMap())
            profiles = HashMap(JsonStore.read(fProfiles, MapSerializer(String.serializer(), PeerProfile.serializer())) ?: emptyMap())
            myProfile = JsonStore.read(fMyProfile, MyProfile.serializer()) ?: MyProfile()
            cursors = HashMap(JsonStore.read(fCursors, MapSerializer(String.serializer(), Long.serializer())) ?: emptyMap())
            delPending = JsonStore.read(fDelPending, ListSerializer(DelPending.serializer()))?.toCollection(ArrayList()) ?: ArrayList()
            // v0.3.6 — GUÉRISON DU BACKLOG DES ACCUSÉS (retour terrain « les
            // accusés de réception ne fonctionnent pas » Tel↔PC) : les messages
            // ENTRANTS DÉJÀ LUS (readLocal=1 — jamais de faux « lu » : un message
            // non ouvert ne doit rien révéler au pair) dont l'accusé n'est jamais
            // parti (rcptRead=0 et rcptPending=0 — reçus sous v0.3.0-v0.3.5 dont
            // l'émission, quand elle partait, était effacée SANS retry et jamais
            // renvoyée) redeviennent « read » à déposer (rcptPending=2 — « read »
            // couvre « delivered » côté expéditeur, miroir Android v0.2.15). Le
            // prochain poll les flushera — le téléphone verra ENFIN ses ✓✓.
            var healed = 0
            for (m in messages) {
                if (!m.outgoing && m.readLocal == 1 && m.rcptRead == 0L && m.rcptPending == 0) {
                    m.rcptPending = 2; healed++
                }
            }
            if (healed > 0) {
                persistMessagesLocked()
                DesktopLog.i(
                    "nyx-boot",
                    "migration accusés : $healed message(s) entrant(s) jamais acquitté(s) → accusé « lu » à déposer"
                )
            }
            loaded = true
        }
    }

    // ---------- contacts ----------

    fun contact(onion: String): Contact? = synchronized(lock) {
        ensureLoadedLocked(); contacts.firstOrNull { it.onion == onion }
    }

    fun contacts(): List<Contact> = synchronized(lock) {
        ensureLoadedLocked(); contacts.toList()
    }

    fun upsertContact(c: Contact) = synchronized(lock) {
        ensureLoadedLocked()
        val i = contacts.indexOfFirst { it.onion == c.onion }
        if (i >= 0) contacts[i] = c else contacts.add(c)
        persistContactsLocked()
    }

    fun updateContact(onion: String, mutate: (Contact) -> Unit) = synchronized(lock) {
        ensureLoadedLocked()
        val i = contacts.indexOfFirst { it.onion == onion }
        if (i >= 0) { mutate(contacts[i]); persistContactsLocked() }
    }

    // ---------- messages ----------

    fun messages(): List<Msg> = synchronized(lock) {
        ensureLoadedLocked(); messages.toList()
    }

    fun messagesWith(peer: String): List<Msg> = synchronized(lock) {
        ensureLoadedLocked(); messages.filter { it.peer == peer }.sortedBy { it.ts }
    }

    fun message(id: String): Msg? = synchronized(lock) {
        ensureLoadedLocked(); messages.firstOrNull { it.id == id }
    }

    fun insertMessage(m: Msg) = synchronized(lock) {
        ensureLoadedLocked()
        if (messages.any { it.id == m.id }) return
        messages.add(m)
        persistMessagesLocked()
    }

    fun updateMessage(id: String, mutate: (Msg) -> Unit) = synchronized(lock) {
        ensureLoadedLocked()
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) { mutate(messages[i]); persistMessagesLocked() }
    }

    /**
     * v0.3.7 — DÉRIVE D'ID TUEE À LA RACINE (retour terrain session 77) :
     * quand un dépôt subit un resync de séquence (400 → réessai avec le seq
     * attendu par le journal), l'événement DÉPOSÉ porte un NOUVEL id
     * (canonical contient le seq) alors que la ligne locale gardait l'ancien —
     * l'accusé du pair référençait alors un id introuvable et la bulle restait
     * « ✓ Envoyé » à vie. Renomme la ligne locale vers l'id réellement déposé.
     */
    fun rekeyMessage(oldId: String, newId: String) = synchronized(lock) {
        if (oldId == newId) return
        ensureLoadedLocked()
        val i = messages.indexOfFirst { it.id == oldId }
        if (i >= 0 && messages.none { it.id == newId }) {
            messages[i] = messages[i].copy(id = newId)
            persistMessagesLocked()
            DesktopLog.i("nyx-send", "   id local re-aligné sur le dépôt resync (${oldId.take(8)}… → ${newId.take(8)}…)")
        }
    }

    fun messagesByIds(ids: List<String>): List<Msg> = synchronized(lock) {
        ensureLoadedLocked()
        val set = ids.toHashSet()
        messages.filter { it.id in set }
    }

    fun deleteMessages(ids: List<String>) = synchronized(lock) {
        ensureLoadedLocked()
        val set = ids.toHashSet()
        val removed = messages.filter { it.id in set }
        messages.removeAll { it.id in set }
        persistMessagesLocked()
        removed
    }

    /** v0.3.4 — suppression LOCALE d'une conversation (miroir deleteConversation Android :
     * messages + fichiers joints, le contact est conservé). */
    fun deleteConversation(peer: String): Int = synchronized(lock) {
        ensureLoadedLocked()
        val removed = messages.filter { it.peer == peer }
        messages.removeAll { it.peer == peer }
        removed.filter { it.kind == KIND_FILE && it.fid.isNotBlank() }.forEach {
            attachments.remove(it.fid)
            deleteAttachmentFile(it.fid)
        }
        persistMessagesLocked()
        persistAttachmentsLocked()
        removed.size
    }

    // ---------- suppressions différées « pour tous » (v0.3.4 — miroir del_pending) ----------

    fun addDelPending(peer: String, ids: List<String>) = synchronized(lock) {
        ensureLoadedLocked()
        delPending.removeAll { it.peer == peer }
        if (ids.isNotEmpty()) delPending.add(DelPending(peer, ids, System.currentTimeMillis()))
        persistDelPendingLocked()
    }

    fun delPendingList(): List<DelPending> = synchronized(lock) {
        ensureLoadedLocked(); delPending.toList()
    }

    fun removeDelPending(peer: String) = synchronized(lock) {
        ensureLoadedLocked()
        delPending.removeAll { it.peer == peer }
        persistDelPendingLocked()
    }

    /** v0.3.4 — cartes par conversation (miroir unreadByPeer() Android) : non lus par pair. */
    fun unreadByPeer(): Map<String, Int> = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { !it.outgoing && it.readLocal == 0 && it.state == STATE_RECEIVED }
            .groupingBy { it.peer }.eachCount()
    }

    /** v0.3.4 — dernier message d'un pair (tri chrono des cartes, miroir Android). */
    fun lastMessageOf(peer: String): Msg? = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { it.peer == peer }.maxByOrNull { it.ts }
    }

    fun markConversationRead(peer: String) = synchronized(lock) {
        ensureLoadedLocked()
        var changed = false
        for (m in messages) {
            if (m.peer == peer && !m.outgoing && m.readLocal == 0) { m.readLocal = 1; changed = true }
        }
        if (changed) persistMessagesLocked()
    }

    /**
     * v0.3.6 — PROMOTION « delivered » → « read » (miroir promoteDeliveredToRead
     * Android v0.2.15) : la conversation VIENT de s'ouvrir — tout accusé
     * « delivered » en attente (rcptPending=1) devient « read » (2), qui COUVRE
     * le delivered côté expéditeur ; les messages dont le delivered seul est
     * parti (rcptPending=0 mais jamais accusés LUS — rcptRead=0, modèle
     * v0.3.5 ou delivered déjà flushé) montent aussi à 2. Un seul état à
     * vider par message.
     */
    fun promoteDeliveredToRead(peer: String): Int = synchronized(lock) {
        ensureLoadedLocked()
        var n = 0
        for (m in messages) {
            if (m.peer == peer && !m.outgoing &&
                (m.rcptPending == 1 || (m.rcptPending == 0 && m.rcptRead == 0L))
            ) { m.rcptPending = 2; n++ }
        }
        if (n > 0) persistMessagesLocked()
        n
    }

    /** v0.3.6 — horodate rcptRead sur les messages ENTRANTS d'un accusé « lu » PARTI (anti re-émission au redémarrage : la migration ne re-promut que rcptRead=0). */
    fun markReceiptsRead(ids: List<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        ensureLoadedLocked()
        val set = ids.toHashSet()
        val now = System.currentTimeMillis()
        var changed = false
        for (m in messages) {
            if (m.id in set && !m.outgoing && m.rcptRead == 0L) { m.rcptRead = now; changed = true }
        }
        if (changed) persistMessagesLocked()
    }

    /** v0.3.6 — ids des messages d'un pair avec accusé « read » en attente (miroir readPendingIds). */
    fun readPendingIds(peer: String): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { it.peer == peer && !it.outgoing && it.readLocal == 1 && it.rcptPending > 0 }.map { it.id }
    }

    /** v0.3.6 — ids des messages d'un pair avec accusé « delivered » SEUL en attente (miroir deliveredPendingIds). */
    fun deliveredPendingIds(peer: String): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { it.peer == peer && !it.outgoing && it.rcptPending == 1 }.map { it.id }
    }

    /** v0.3.6 — pairs ayant au moins un accusé en attente (miroir peersWithPendingReceipts). */
    fun peersWithPendingReceipts(): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { !it.outgoing && it.rcptPending > 0 }.map { it.peer }.distinct()
    }

    /** v0.3.6 — règle rcptPending=0 pour des ids donnés — UNIQUEMENT si fournis (l'appelant ne vide que ce qu'il a déposé). */
    fun clearRcptPending(ids: List<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        ensureLoadedLocked()
        val set = ids.toHashSet()
        var changed = false
        for (m in messages) {
            if (m.id in set && m.rcptPending != 0) { m.rcptPending = 0; changed = true }
        }
        if (changed) persistMessagesLocked()
    }

    // ---------- séquence par auteur (miroir seq_state Room v2) ----------

    fun seqOf(author: String): Long = synchronized(lock) {
        ensureLoadedLocked(); seqState[author] ?: 0L
    }

    fun upsertSeq(author: String, seq: Long) = synchronized(lock) {
        ensureLoadedLocked()
        seqState[author] = seq
        persistSeqLocked()
    }

    // ---------- pièces jointes ----------

    fun attachment(fid: String): AttachmentMeta? = synchronized(lock) {
        ensureLoadedLocked(); attachments[fid]
    }

    fun upsertAttachment(meta: AttachmentMeta) = synchronized(lock) {
        ensureLoadedLocked()
        attachments[meta.fid] = meta
        persistAttachmentsLocked()
    }

    /** v0.3.4 — mutation d'une pièce jointe existante (progression, vues…). */
    fun updateAttachment(fid: String, mutate: (AttachmentMeta) -> Unit) = synchronized(lock) {
        ensureLoadedLocked()
        val a = attachments[fid] ?: return
        mutate(a)
        persistAttachmentsLocked()
    }

    /** v0.3.4 — consommation d'une vue (miroir decrementViews + garde « jamais < 0 » Android) :
     * retourne le nombre de vues restantes APRÈS décrément (l'appelant détruit le
     * fichier local quand ça tombe à 0). */
    fun consumeView(fid: String): Int = synchronized(lock) {
        ensureLoadedLocked()
        val a = attachments[fid] ?: return -1
        if (a.viewPolicy == VIEW_FOREVER || a.viewsLeft <= 0) return a.viewsLeft
        a.viewsLeft -= 1
        persistAttachmentsLocked()
        a.viewsLeft
    }

    fun putChunk(fid: String, index: Int, data: ByteArray) = synchronized(lock) {
        ensureLoadedLocked()
        chunks.getOrPut(fid) { HashMap() }[index] = data
    }

    fun chunkCount(fid: String): Int = synchronized(lock) { chunks[fid]?.size ?: 0 }

    /** Reconstitue le fichier une fois tous les fragments présents. */
    fun assembleAttachment(fid: String, total: Int, expectedSha256: String): File? = synchronized(lock) {
        ensureLoadedLocked()
        val parts = chunks[fid] ?: return null
        if (parts.size < total) return null
        val out = ByteArray(parts.entries.sumOf { it.value.size })
        var off = 0
        for (i in 0 until total) {
            val d = parts[i] ?: return null
            System.arraycopy(d, 0, out, off, d.size)
            off += d.size
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(out)
            .joinToString("") { "%02x".format(it) }
        if (expectedSha256.isNotBlank() && digest != expectedSha256) {
            return null
        }
        val f = File(mediaDir, "$fid.bin")
        f.parentFile?.mkdirs()
        f.writeBytes(out)
        chunks.remove(fid)
        f
    }

    // ---------- profils ----------

    fun peerProfile(onion: String): PeerProfile? = synchronized(lock) {
        ensureLoadedLocked(); profiles[onion]
    }

    fun upsertPeerProfile(p: PeerProfile) = synchronized(lock) {
        ensureLoadedLocked()
        profiles[p.onion] = p
        persistProfilesLocked()
    }

    fun myProfile(): MyProfile = synchronized(lock) {
        ensureLoadedLocked(); myProfile
    }

    fun saveMyProfile(p: MyProfile) = synchronized(lock) {
        ensureLoadedLocked(); myProfile = p
        JsonStore.write(fMyProfile, MyProfile.serializer(), p)
    }

    // ---------- curseurs d'inbox par réplica (v0.2.42 Android) ----------

    fun cursor(replica: String): Long = synchronized(lock) {
        ensureLoadedLocked(); cursors[replica] ?: 0L
    }

    fun hasCursor(replica: String): Boolean = synchronized(lock) {
        ensureLoadedLocked(); cursors.containsKey(replica)
    }

    fun setCursor(replica: String, value: Long) = synchronized(lock) {
        ensureLoadedLocked()
        cursors[replica] = value
        JsonStore.write(fCursors, MapSerializer(String.serializer(), Long.serializer()), cursors)
    }

    /** Instantané complet (v0.3.2 — export de sauvegarde, miroir pollStateDao.all()). */
    fun cursors(): Map<String, Long> = synchronized(lock) {
        ensureLoadedLocked(); cursors.toMap()
    }

    // ---------- fichiers média ----------

    fun deleteAttachmentFile(fid: String) {
        runCatching { File(mediaDir, "$fid.bin").delete() }
    }

    /** Purge locale des messages expirés (TTL côté réception). */
    fun purgeExpired(now: Long): Int = synchronized(lock) {
        ensureLoadedLocked()
        val expired = messages.filter { it.expireAt in 1..now }
        if (expired.isEmpty()) return 0
        messages.removeAll { it.expireAt in 1..now }
        persistMessagesLocked()
        expired.size
    }

    /** v0.3.4 — toutes les pièces jointes d'un pair (« Fichiers & médias », miroir MediaTab) :
     * jointure messages(fid) × attachments. */
    fun attachmentsOf(peer: String): List<Pair<Msg, AttachmentMeta>> = synchronized(lock) {
        ensureLoadedLocked()
        messages.filter { it.peer == peer && it.kind == KIND_FILE && it.fid.isNotBlank() }
            .sortedByDescending { it.ts }
            .mapNotNull { m -> attachments[m.fid]?.let { m to it } }
    }

    /** Wipe : tout effacer (moteur de retour à l'onboarding). */
    fun wipe() = synchronized(lock) {
        contacts.clear(); messages.clear(); seqState.clear()
        attachments.clear(); profiles.clear(); chunks.clear(); cursors.clear()
        delPending.clear(); myProfile = MyProfile()
        persistContactsLocked(); persistMessagesLocked(); persistSeqLocked()
        persistAttachmentsLocked(); persistProfilesLocked(); persistDelPendingLocked()
        JsonStore.write(fMyProfile, MyProfile.serializer(), myProfile)
        JsonStore.write(fCursors, MapSerializer(String.serializer(), Long.serializer()), cursors)
        runCatching { mediaDir.listFiles()?.forEach { it.delete() } }
    }

    // ---------- persistance ----------

    private fun ensureLoadedLocked() { if (!loaded) ensureLoaded() }

    private fun persistContactsLocked() =
        JsonStore.write(fContacts, ListSerializer(Contact.serializer()), contacts)
    private fun persistMessagesLocked() =
        JsonStore.write(fMessages, ListSerializer(Msg.serializer()), messages)
    private fun persistSeqLocked() =
        JsonStore.write(fSeq, MapSerializer(String.serializer(), Long.serializer()), seqState)
    private fun persistAttachmentsLocked() =
        JsonStore.write(fAttachments, MapSerializer(String.serializer(), AttachmentMeta.serializer()), attachments)
    private fun persistProfilesLocked() =
        JsonStore.write(fProfiles, MapSerializer(String.serializer(), PeerProfile.serializer()), profiles)
    private fun persistDelPendingLocked() =
        JsonStore.write(fDelPending, ListSerializer(DelPending.serializer()), delPending)
}
