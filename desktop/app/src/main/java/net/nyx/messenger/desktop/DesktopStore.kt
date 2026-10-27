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

    enum class ContactStatus { PENDING, INCOMING, ACCEPTED, DECLINED, REMOVED }

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
    )

    @Serializable
    data class PeerProfile(
        val onion: String,
        var displayName: String = "",
        var bio: String = "",
        var avatarB64: String = "",
        var fetchedAt: Long = 0,
    )

    @Serializable
    data class MyProfile(
        var displayName: String = "",
        var bio: String = "",
        var website: String = "",
        var location: String = "",
        var avatarB64: String = "",
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

    fun unreadCount(peer: String): Int = synchronized(lock) {
        ensureLoadedLocked()
        messages.count { it.peer == peer && !it.outgoing && it.readLocal == 0 && it.state == STATE_RECEIVED }
    }

    fun markConversationRead(peer: String) = synchronized(lock) {
        ensureLoadedLocked()
        var changed = false
        for (m in messages) {
            if (m.peer == peer && !m.outgoing && m.readLocal == 0) { m.readLocal = 1; changed = true }
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

    /** Wipe : tout effacer (moteur de retour à l'onboarding). */
    fun wipe() = synchronized(lock) {
        contacts.clear(); messages.clear(); seqState.clear()
        attachments.clear(); profiles.clear(); chunks.clear(); cursors.clear()
        myProfile = MyProfile()
        persistContactsLocked(); persistMessagesLocked(); persistSeqLocked()
        persistAttachmentsLocked(); persistProfilesLocked()
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
}
