package net.nyx.messenger.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.nyx.messenger.crypto.B64
import net.nyx.messenger.crypto.IdentityManager
import net.nyx.messenger.crypto.MessageCipher
import net.nyx.messenger.crypto.OnionCodec
import net.nyx.messenger.data.db.AttachmentChunkEntity
import net.nyx.messenger.data.db.AttachmentDao
import net.nyx.messenger.data.db.AttachmentEntity
import net.nyx.messenger.data.db.ContactDao
import net.nyx.messenger.data.db.ContactEntity
import net.nyx.messenger.data.db.ContactStatus
import net.nyx.messenger.data.db.MessageDao
import net.nyx.messenger.data.db.MessageEntity
import net.nyx.messenger.data.db.PeerProfileDao
import net.nyx.messenger.data.db.PeerProfileEntity
import net.nyx.messenger.data.db.PollStateDao
import net.nyx.messenger.data.db.PollStateEntity
import net.nyx.messenger.data.db.SeqStateDao
import net.nyx.messenger.data.db.SeqStateEntity
import net.nyx.messenger.di.ServiceLocator
import net.nyx.messenger.media.MediaIngest
import net.nyx.messenger.net.RelayClient
import net.nyx.messenger.net.WireEvent

/**
 * Logique messagerie : envoi (chiffrement → signature → dépôt sur un réplica)
 * et réception (poll inbox → vérification → déchiffrement → stockage local).
 *
 * v0.1 : chiffrement sealed box ; v0.2 : Signal Protocol — seul ce fichier change.
 */
class NyxRepository(
    private val identity: IdentityManager,
    private val cipher: MessageCipher,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val pollStateDao: PollStateDao,
    private val seqStateDao: SeqStateDao,
    private val peerProfileDao: PeerProfileDao,
    // v0.2.17 : méta + fragments des pièces jointes (photos/vidéos/fichiers).
    private val attachmentDao: AttachmentDao,
) {
    /** Réplicas de rattachement — v0.1 : le premier configuré ; TODO : rotation.
     *  @JvmField : évite le clash JVM du setter généré avec [setReplicas]. */
    @JvmField
    var replicas: List<String> = emptyList()

    // Bug v0.2.2 n°2 : setReplicas n'était JAMAIS appelé (aucune liste de
    // rendezvous embarquée — ADR-009) et le client figeait le port SOCKS de
    // tor À SA CRÉATION, alors que les ports tor sont DYNAMIQUES (NyxTorConfig
    // sonde un port libre à chaque démarrage). Le relais est donc reconstruit
    // dès que le port courant change.
    private var relayPort: Int = -1
    private var relayClient: RelayClient? = null

    fun setReplicas(onions: List<String>) {
        replicas = onions.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        relayPort = -1
        relayClient = null
    }

    /** Relais courant (reconstruit si le port SOCKS de tor a changé). */
    private fun relay(): RelayClient? {
        val port = net.nyx.messenger.tor.TorManager.activeSocksPort
        val c = relayClient
        if (c != null && port == relayPort) return c
        val fresh = replicas.firstOrNull()?.let {
            RelayClient.forReplica(it, socksPort = port).also { _ ->
                android.util.Log.i("nyx-repo", "relais réplica : ${it.take(16)}… (socks $port)")
            }
        }
        relayClient = fresh
        relayPort = port
        return fresh
    }

    /** Sonde de joignabilité du réplica rattaché (GET /health via Tor).
     *
     *  v0.2.5 : chaque sonde alimente [lastProbe] (durée + cause d'échec) —
     *  l'écran Connexions affiche le détail au lieu d'un « injoignable » muet,
     *  et l'export de logs contient les tentatives (tag nyx-conn). */
    data class ReplicaProbeReport(val ok: Boolean, val ms: Long, val detail: String?, val at: Long = 0L)

    private val _lastProbe = MutableStateFlow<ReplicaProbeReport?>(null)
    val lastProbe: StateFlow<ReplicaProbeReport?> = _lastProbe.asStateFlow()

    suspend fun replicaHealth(): Boolean {
        // v0.2.17 — SINGLE-FLIGHT (terrain 04:28 : 4 sondes /health simultanées
        // ont saturé le SOCKS tor pendant son bootstrap — « Malformed reply
        // from SOCKS server » ×4 à la même seconde) : une seule sonde à la
        // fois ; lesappelants concurrents repartent avec le dernier rapport.
        if (!probeMutex.tryLock()) {
            return _lastProbe.value?.ok ?: false
        }
        try {
            val rep = relay()?.healthDetailed()
            if (rep == null) {
                _lastProbe.value = ReplicaProbeReport(false, 0, "aucun réplica configuré", System.currentTimeMillis())
                return false
            }
            _lastProbe.value = ReplicaProbeReport(rep.ok, rep.ms, rep.detail, System.currentTimeMillis())
            return rep.ok
        } finally {
            probeMutex.unlock()
        }
    }

    private val probeMutex = kotlinx.coroutines.sync.Mutex()

    /* ---------------- envoi ---------------- */

    suspend fun send(peerOnion: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // v0.2.12 : le log d'entrée passe EN PREMIER — le terrain a montré un
            // échec SANS aucune trace du cycle (04:26:43, export Xiaomi v0.2.11) :
            // tout ce qui échoue ici (réplica/identité/clé) était invisible.
            android.util.Log.i("nyx-send", "→ msg peer=${peerOnion.trim().take(16)}… len=${text.length}")
            // v0.2.11 : normalisation DÉFENSIVE de l'adresse — un onion collé avec
            // espace/retour de ligne rendait le champ `to` illisible pour la inbox
            // du pair (accepté HTTP 200 par le réplica, jamais délivré — e2e S3).
            val peer = peerOnion.trim().lowercase()
            // v0.2.14 — VERROU MUTUEL (demande opérateur : « les contacts ne
            // peuvent s'envoyer de message s'ils ne figurent pas dans les listes
            // de contact mutuelles ») : l'envoi n'est possible que si le pair est
            // un contact ACCEPTÉ chez nous. L'échec est affiché dans l'UI (v0.2.11).
            val link = contactDao.get(peer)
            if (link?.status != ContactStatus.ACCEPTED) {
                error(
                    if (link == null) "ce destinataire n'est pas dans vos contacts"
                    else "échange possible uniquement une fois l'invitation acceptée des deux côtés"
                )
            }
            val relay = relay() ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peer)

            // 1. Chiffrement E2E (sealed box, v0.1)
            val body = cipher.encryptFor(peerPub, text.toByteArray(Charsets.UTF_8))

            // 2. Événement signé
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "msg", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, ttl = 0,
                to = peer, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val signed = ev.copy(sig = cipher.signEvent(canonical, priv))

            // 3. Stockage local (en attente) puis dépôt (auto-réparation seq v0.2.10)
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(id, peer, outgoing = true, text, ev.ts, ev.ts, STATE_PENDING)
            )
            android.util.Log.i("nyx-send", "   stocké local id=${id.take(12)}… seq=$seq → dépôt réplica")
            postSigned(relay, me, signed, canonical, priv).fold(
                onSuccess = {
                    messageDao.updateState(id, STATE_SENT)
                    android.util.Log.i("nyx-send", "   ✓ déposé (seq=$seq)")
                    Unit
                },
                onFailure = { e ->
                    messageDao.updateState(id, STATE_FAILED)
                    android.util.Log.w("nyx-send", "   ✗ dépôt ÉCHOUÉ (seq=$seq) : ${e.message}")
                    throw e
                }
            )
        }
    }

    /**
     * v0.2.10 — réservation de séquence PERSISTÉE (table seq_state, v2).
     * Le compteur en mémoire de v0.1 repartait à 1 à chaque relance alors que le
     * journal exige seq = dernier+1 : les premiers événements après un
     * redémarrage étaient rejetés et les messages ne partaient jamais.
     * Le mutex sérialise les réservations concurrentes (envoi + claim alias…).
     */
    private val seqMutex = Mutex()

    private suspend fun nextSeqFor(author: String): Long = seqMutex.withLock {
        val next = (seqStateDao.get(author)?.seq ?: 0L) + 1
        seqStateDao.upsert(SeqStateEntity(author, next))
        next
    }

    /**
     * Dépôt d'un événement signé avec auto-réparation de séquence :
     *  - 409 « déjà connu » = l'événement était déjà dans le journal → succès ;
     *  - rejet de seq (« en attente (attendu N) » / « déjà dépassée (dernière N) »)
     *    → la table seq_state est recadrée sur le réplica puis l'événement est
     *    re-signé et redéposé UNE fois (dérive après restauration de base…).
     */
    private suspend fun postSigned(
        relay: RelayClient,
        author: String,
        ev: WireEvent,
        canonical: String,
        priv: ByteArray,
    ): Result<Unit> {
        val first = relay.postEvent(ev)
        if (first.isSuccess) return first
        val msg = first.exceptionOrNull()?.message ?: return first
        if (msg.contains("HTTP 409")) return Result.success(Unit) // idempotent
        val expected = Regex("attendu (\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull()
            ?: (Regex("dernière (\\d+)").find(msg)?.groupValues?.get(1)?.toLongOrNull()?.plus(1))
            ?: return first
        seqMutex.withLock { seqStateDao.upsert(SeqStateEntity(author, expected)) }
        val retried = ev.copy(seq = expected)
        val canonical2 = MessageCipher.canonical(
            retried.type, retried.author, retried.seq, retried.ts, retried.ttl, retried.to ?: "", retried.body
        )
        return relay.postEvent(retried.copy(sig = cipher.signEvent(canonical2, priv)))
    }

    /* ---------------- réception ---------------- */

    /** Poll de la boîte de réception sur le réplica rattaché. */
    suspend fun pollInbox(): Int = withContext(Dispatchers.IO) {
        val relay = relay() ?: return@withContext 0
        val me = identity.onionAddress ?: return@withContext 0
        val priv = identity.secretKey()

        val cursor = pollStateDao.get(replicas.first())?.cursor ?: 0L
        val resp = relay.inbox(me, cursor)
        if (resp.events.isNotEmpty()) {
            android.util.Log.i("nyx-recv", "← inbox ${resp.events.size} évts (after=$cursor)")
        }
        var inserted = 0
        for (ev in resp.events) {
            // v0.2.11 : l'auteur est normalisé (trim+minuscules) — la clé locale
            // (contacts/messages) doit matcher la clé utilisée à l'envoi.
            val author = ev.author.trim().lowercase()
            // 1. Vérification de la signature de l'auteur
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            val authorPub = runCatching { OnionCodec.onionToPubKey(author) }.getOrNull()
            val okSig = authorPub != null && verifySignature(canonical, ev.sig, authorPub)
            if (!okSig) {
                android.util.Log.w("nyx-recv", "   signature invalide type=${ev.type} author=${author.take(16)}…")
                continue
            }

            // 2. Déchiffrement (sealed box v0.1)
            val plain = cipher.decryptForSelf(priv, ev.body)?.toString(Charsets.UTF_8)
            if (plain == null) {
                android.util.Log.w("nyx-recv", "   déchiffrement impossible type=${ev.type} author=${author.take(16)}…")
                continue
            }

            // 3. Demande de contact (v0.2.10) + ACK (v0.2.12) : body chiffré
            //    {"alias":…} = invitation ; {"alias":…,"ack":"accepted|declined"}
            //    = réponse du destinataire à NOTRE invitation.
            //    v0.2.14 : ack « removed » = le pair nous a supprimés — le contact
            //    est ARCHIVÉ côté invitant avec notification (demande opérateur :
            //    « le contact qui refuse ou supprime doit être supprimé du côté du
            //    téléphone qui invite, avec une notification selon le cas »).
            //    v0.2.15 : {"alias":…,"rcpt":"delivered|read","ids":[…]} = accusé
            //    de réception/lecture (même type whitelisté — corps opaque, zéro
            //    changement serveur). TRAITÉ EN PREMIER : un accusé n'est NI une
            //    invitation NI un ack de cycle de vie — et un client v0.2.14 qui
            //    en reçoit un tombe simplement dans la branche « déjà traitée ».
            if (ev.type == "contact") {
                // v0.2.15 : ACCUSÉ (« delivered »/« read ») — TRAITÉ EN PREMIER :
                // un accusé n'est NI une invitation NI un ack de cycle de vie.
                // Garde anti-usurpation : les ids ne s'appliquent qu'aux messages
                // QUE NOUS AVONS ENVOYÉS à cet auteur (WHERE outgoing=1 + peer).
                val receipt = ReceiptCodec.parse(plain)
                if (receipt != null) {
                    val (kind, ids) = receipt
                    if (ids.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (kind == ReceiptCodec.KIND_DELIVERED) {
                            messageDao.markDelivered(author, ids, now)
                        } else {
                            messageDao.markRead(author, ids, now)
                        }
                        android.util.Log.i(
                            "nyx-recv",
                            "   ✓ accusé « $kind » de ${author.take(16)}… (${ids.size} msg)"
                        )
                    }
                    continue
                }
                val declared = Regex("\"alias\"\\s*:\\s*\"([^\"]*)\"").find(plain)
                    ?.groupValues?.get(1).orEmpty()
                val ack = Regex("\"ack\"\\s*:\\s*\"([^\"]*)\"").find(plain)
                    ?.groupValues?.get(1).orEmpty()
                val label = declared.ifBlank { author.take(10) + "…" }
                val already = contactDao.get(author)
                when {
                    ack == "accepted" || ack == "declined" || ack == "removed" -> {
                        // — RÉPONSE du pair à notre invitation (ou avis de suppression) —
                        // v0.2.14 : un refus/suppression d'un pair INCONNU (aucune
                        // ligne locale — ex. base effacée) ne crée RIEN : sans lien,
                        // il n'y a rien à archiver ni à notifier.
                        if (already == null && ack != "accepted") {
                            android.util.Log.i(
                                "nyx-recv",
                                "   · ack « $ack » d'un pair inconnu ${author.take(16)}… — ignoré"
                            )
                            continue
                        }
                        // v0.2.14 : lien ARCHIVÉ chez moi (je l'ai supprimé, refusé,
                        // ou il m'a supprimé) — un ack arrivé APRÈS (en vol au moment
                        // de la suppression) ne doit PAS ressusciter le lien : la
                        // suppression la plus récente l'emporte. La reprise ne passe
                        // QUE par la re-invitation (annonce requalifiée incoming).
                        if (already != null && ContactStatus.archived(already.status)) {
                            android.util.Log.i(
                                "nyx-recv",
                                "   · ack « $ack » ignoré — lien archivé (${already.status}) pour ${author.take(16)}…"
                            )
                            continue
                        }
                        val newStatus = when (ack) {
                            "accepted" -> ContactStatus.ACCEPTED
                            "declined" -> ContactStatus.DECLINED
                            else -> ContactStatus.REMOVED
                        }
                        if (already != null) {
                            contactDao.setStatus(author, newStatus)
                            contactDao.setNotifyPending(author, 0)
                        } else {
                            contactDao.upsert(
                                ContactEntity(
                                    author, label, System.currentTimeMillis(),
                                    notifiedAt = System.currentTimeMillis(),
                                    status = newStatus
                                )
                            )
                        }
                        val phrase = when (ack) {
                            "accepted" -> "$label a accepté votre invitation."
                            "declined" -> "$label a décliné votre invitation."
                            else -> "$label vous a supprimé de sa liste de contacts."
                        }
                        messageDao.insert(
                            MessageEntity(
                                eventIdOf(canonical), author, outgoing = false,
                                phrase, ev.ts, System.currentTimeMillis(), STATE_RECEIVED,
                                readLocal = 1 // message d'information — jamais un « non lu »
                            )
                        )
                        // v0.2.14 : NOTIFICATION système selon le cas (refus ou
                        // suppression — l'acceptation n'en demande pas).
                        when (ack) {
                            "declined" -> net.nyx.messenger.notify.ContactNotifier.declined(author, label)
                            "removed" -> net.nyx.messenger.notify.ContactNotifier.removed(author, label)
                        }
                        android.util.Log.i("nyx-recv", "   ✓ ack « $ack » de ${author.take(16)}… ($label)")
                        inserted++
                    }
                    already == null -> {
                        // — INVITATION reçue (v0.2.10) : à accepter/refuser par
                        //   l'opérateur (v0.2.12 : statut « incoming » + boutons ;
                        //   v0.2.14 : NOTIFICATION système avec actions). —
                        contactDao.upsert(
                            ContactEntity(
                                author, label, System.currentTimeMillis(),
                                notifiedAt = System.currentTimeMillis(), // l'annonce du PAIR — pas de contre-annonce
                                status = ContactStatus.INCOMING
                            )
                        )
                        messageDao.insert(
                            MessageEntity(
                                eventIdOf(canonical), author, outgoing = false,
                                "$label vous a ajouté comme contact.",
                                ev.ts, System.currentTimeMillis(), STATE_RECEIVED,
                                readLocal = 1 // l'invitation a SES propres signaux (carte + notif)
                            )
                        )
                        net.nyx.messenger.notify.ContactNotifier.invitation(author, label)
                        android.util.Log.i("nyx-recv", "   ✓ demande de contact de ${author.take(16)}… ($label)")
                        inserted++
                    }
                    already.status == ContactStatus.PENDING -> {
                    // — v0.2.12 : AJOUT MUTUEL — nous avions invité ce pair (statut
                    //   « pending ») et il s'annonce à son tour : équivalent d'un
                    //   accept, on qualifie directement « accepted » + ACK explicite
                    //   au pair (sinon il resterait « pending » à vie — l'e2e S3
                    //   a attrapé l'asymétrie).
                    contactDao.setStatus(author, ContactStatus.ACCEPTED)
                    contactDao.setAckPending(author, 1)
                    messageDao.insert(
                        MessageEntity(
                            eventIdOf(canonical), author, outgoing = false,
                            "$label vous a ajouté en retour : invitation acceptée.",
                            ev.ts, System.currentTimeMillis(), STATE_RECEIVED,
                            readLocal = 1 // message d'information — jamais un « non lu »
                        )
                    )
                    android.util.Log.i("nyx-recv", "   ✓ ajout mutuel de ${author.take(16)}… ($label) → ack reparti")
                    inserted++
                    }
                    else -> {
                        // Doublon d'annonce (relances) ou contact reçu sous v0.2.10/11
                        // (status='' issu de migration) : requalifie en « incoming »
                        // pour que l'invitation devienne enfin actionnable.
                        // v0.2.14 : une annonce reçue d'un pair ARCHIVÉ (declined/
                        // removed/removed_by_me) = RE-INVITATION du pair — on repart
                        // d'une invitation fraîche (incoming).
                        if (already.status == ContactStatus.NONE ||
                            net.nyx.messenger.data.db.ContactStatus.archived(already.status)
                        ) {
                            contactDao.setStatus(author, ContactStatus.INCOMING)
                            net.nyx.messenger.notify.ContactNotifier.invitation(author, label)
                            android.util.Log.i("nyx-recv", "   ✓ annonce connue de ${author.take(16)}… → invitation reçue")
                            inserted++
                        } else {
                            android.util.Log.i("nyx-recv", "   · annonce déjà traitée de ${author.take(16)}… (${already.status})")
                        }
                    }
                }
                continue
            }

            // 3bis. v0.2.14 — MESSAGE : échange RÉSERVÉ aux contacts MUTUELS
            // (demande opérateur : « les contacts ne peuvent s'envoyer de message
            // s'ils ne figurent pas dans les listes de contact mutuelles »). Un
            // message d'un pair sans lien accepté est DÉTRUIT (jamais stocké,
            // jamais affiché) — l'expéditeur légitime est de toute façon bloqué
            // par le même verrou chez lui.
            // EXCEPTION « pending » : mon invitation a été acceptée par le pair
            // (il m'a ACKé de son côté et peut m'écrire) mais son ack est encore
            // en vol (~30 s de poll) — détruire ce message reproduirait le
            // « rien reçu » du terrain v0.2.13 ; la poignée de main en cours
            // prouve la relation, on stocke.
            val linkStatus = contactDao.get(author)?.status
            if (ev.type == "msg" && linkStatus != ContactStatus.ACCEPTED &&
                linkStatus != ContactStatus.PENDING
            ) {
                android.util.Log.w(
                    "nyx-recv",
                    "   ✗ msg de ${author.take(16)}… ignoré (pas un contact mutuel — statut: ${linkStatus ?: "aucun"})"
                )
                continue
            }

            // 3ter. v0.2.17 — FRAGMENT DE FICHIER ? (corps « msg » déchiffré
            // reconnu par le ChunkCodec — méta créée au 1er fragment vu, bulle
            // à l'index 0, assemblage + vérification sha256 au dernier). Un
            // texte ordinaire ne parse PAS — branche suivante inchangée.
            val chunk = ChunkCodec.parse(plain)
            if (chunk != null) {
                handleIncomingChunk(author, canonical, ev.ts, chunk)
                if (chunk.index == 0) inserted++
                continue
            }

            // 4. Stockage local — v0.2.15 : rcptPending=1 = accusé « delivered »
            //    à déposer chez le pair (tentative immédiate en fin de poll,
            //    repartie par la boucle sinon).
            val id = eventIdOf(canonical)
            messageDao.insert(
                MessageEntity(
                    id, author, outgoing = false, plain, ev.ts, System.currentTimeMillis(),
                    STATE_RECEIVED, rcptPending = 1
                )
            )
            if (contactDao.get(author) == null) {
                contactDao.upsert(
                    ContactEntity(author, author.take(10) + "…", System.currentTimeMillis(),
                        notifiedAt = System.currentTimeMillis())
                )
            }
            android.util.Log.i("nyx-recv", "   ✓ ${ev.type} de ${author.take(16)}… stocké")
            inserted++
        }
        pollStateDao.upsert(PollStateEntity(replicas.first(), resp.cursor))
        // v0.2.15 : accusés en attente — tentative IMMÉDIATE (ce poll vient de
        // prouver le réplica joignable, Tor chaud) ; en cas d'échec la boucle
        // périodique repartira les accusés comme les annonces/acks.
        runCatching { ensureReceiptsSent() }
        inserted
    }

    private fun verifySignature(canonical: String, sigB64: String, pub: ByteArray): Boolean {
        // Vérification ed25519 réelle via libsodium — protège contre un réplica
        // mensonger (miroir de journal.Validate côté serveur Go).
        return ServiceLocator.cipher.verifyEvent(canonical, sigB64, pub)
    }

    private fun eventIdOf(canonical: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /* ---------------- contacts ---------------- */

    fun contacts(): Flow<List<ContactEntity>> = contactDao.all()
    fun conversation(peer: String): Flow<List<MessageEntity>> = messageDao.conversation(peer)

    /* ---------------- alias (v0.1.2 — wire-format.md §6) ---------------- */

    /** État de disponibilité d'un pseudonyme sur le réplica rattaché. */
    data class AliasInfo(val name: String, val taken: Boolean, val by: String? = null)

    /**
     * Vérifie la disponibilité d'un alias sur le réplica rattaché.
     * Échoue proprement si aucun réplica n'est configuré/joignable — l'UI
     * affiche alors que la confirmation se fera à la première connexion.
     */
    suspend fun checkAlias(name: String): Result<AliasInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = relay() ?: error("aucun réplica configuré — vérification à la première connexion")
            val res = relay.resolveAlias(name)
            AliasInfo(name, res.taken, res.by)
        }
    }

    /**
     * Réserve le pseudonyme : événement signé type "alias" (claim) déposé sur le
     * réplica rattaché — la convergence inter-réplicas suit la règle du plus petit
     * id (déterministe, wire-format.md §6.3).
     */
    suspend fun claimAlias(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = relay() ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val bodyJson = "{\"alias\":\"$name\",\"action\":\"claim\"}"
            val body = B64.encode(bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "alias", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, ttl = 0,
                to = null, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
        }
    }

    /**
     * Garantit que l'alias choisi (s'il existe) est réservé : appelé à l'entrée
     * sur la liste des conversations — si le claim avait différé (aucun réplica
     * joignable à l'onboarding), il part dès qu'un réplica répond.
     */
    suspend fun ensureAliasClaimed(): Unit = withContext(Dispatchers.IO) {
        val name = identity.alias ?: return@withContext
        if (identity.aliasClaimed || replicas.isEmpty()) return@withContext
        claimAlias(name).onSuccess { identity.markAliasClaimed() }
    }

    /**
     * v0.2.10 — ANNONCE DE CONTACT : l'ajout d'un contact n'est plus purement
     * local, un événement signé type « contact » (type standard du journal,
     * body chiffré {"alias":…} pour le destinataire) est déposé sur le réplica :
     * le pair voit la demande apparaître chez lui (carte + message d'info).
     * Si le réplica est injoignable, notifyPending=1 mémorise le devoir —
     * [ensureContactNotified] le repartage à la prochaine entrée sur la liste.
     */
    private suspend fun sendContactNotification(onionLower: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = relay() ?: error("aucun réplica configuré")
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()
                val peerPub = OnionCodec.onionToPubKey(onionLower)
                val bodyJson = "{\"alias\":\"${identity.alias ?: ""}\"}"
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "contact", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = onionLower, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            }
        }

    /** Repartage des annonces de contact restées en attente OU jamais déposées
     *  (contacts migrés de v0.2.9 : notifiedAt=0 — l'annonce n'a jamais pu partir,
     *  la fonctionnalité n'existait pas ; le pair n'avait donc JAMAIS d'invitation).
     *
     *  v0.2.12 — deux correctifs de LIVRAISON (retour terrain v0.2.11 : l'invitation
     *  v0.2.9 n'est TOUJOURS pas arrivée) :
     *   1. l'échec n'est plus MUET — il était avalé sans log ni statut, la seule
     *      tentative partant à +2 s en pleine course avec le bootstrap Tor
     *      (22–47 s) : elle échouait systématiquement, invisiblement ;
     *   2. les ACK d'acceptation/refus en attente (ackPending=1) repartent ici
     *      aussi — même mécanique que les annonces. */
    suspend fun ensureContactNotified(): Unit = withContext(Dispatchers.IO) {
        if (replicas.isEmpty()) return@withContext
        for (c in contactDao.pendingNotify()) {
            sendContactNotification(c.onion).fold(
                onSuccess = {
                    contactDao.markNotified(c.onion, System.currentTimeMillis())
                    android.util.Log.i("nyx-send", "✓ annonce contact déposée pour ${c.onion.take(16)}…")
                },
                onFailure = { e ->
                    // v0.2.12 : ÉCHEC VISIBLE — le statut local reste « annonce en
                    // attente d'envoi » et la relance périodique (boucle 30 s du
                    // ServiceLocator, cf. pollAnnouncements) repartira l'annonce.
                    android.util.Log.w("nyx-send", "✗ annonce contact ÉCHOUÉE pour ${c.onion.take(16)}… : ${e.message}")
                }
            )
        }
        for (c in contactDao.pendingAcks()) {
            sendContactAck(c.onion, ackOf(c.status)).fold(
                onSuccess = {
                    contactDao.setAckPending(c.onion, 0)
                    android.util.Log.i("nyx-send", "✓ ack « ${ackOf(c.status)} » déposé pour ${c.onion.take(16)}…")
                },
                onFailure = { e ->
                    android.util.Log.w("nyx-send", "✗ ack « ${ackOf(c.status)} » ÉCHOUÉ pour ${c.onion.take(16)}… : ${e.message}")
                }
            )
        }
    }

    /** v0.2.12 — ACK d'invitation : le destinataire accepte ou refuse, un événement
     *  signé type « contact » (type standard déjà accepté par le réplica v0.2.0 —
     *  AUCUN changement serveur) avec body chiffré {"alias":…,"ack":"accepted"} est
     *  déposé à l'intention de l'invitant, qui voit son statut passer à
     *  « acceptée » ou « déclinée » (demande opérateur).
     *  v0.2.14 : « removed » = avis de suppression de contact (même canal). */
    private fun ackOf(status: String): String =
        when (status) {
            ContactStatus.DECLINED -> "declined"
            ContactStatus.REMOVED_BY_ME -> "removed"
            else -> "accepted"
        }

    private suspend fun sendContactAck(onionLower: String, ack: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = relay() ?: error("aucun réplica configuré")
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()
                val peerPub = OnionCodec.onionToPubKey(onionLower)
                val bodyJson = "{\"alias\":\"${identity.alias ?: ""}\",\"ack\":\"$ack\"}"
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "contact", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = onionLower, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            }
        }

    /* ---------------- pièces jointes (v0.2.17) ---------------- */

    /** États d'un transfert (attachments.state) — voir companion. */

    /**
     * ENVOI D'UN FICHIER (photo/vidéo/audio/document) — demande opérateur :
     * « partage photo/video, partage de fichier ». TRANSPORT : le fichier est
     * découpé en fragments de [ChunkCodec.CHUNK_PAYLOAD] octets, chaque
     * fragment part comme un événement « msg » indépendant (type DÉJÀ
     * whitelisté serveur, corps chiffré E2E opaque) — zéro changement serveur,
     * zéro déploiement. Le récepteur réassemble ([handleIncomingChunk]) et
     * vérifie le sha256 avant d'afficher le média.
     *
     * SÉQUENTIEL obligatoire : le journal du réplica exige seq = dernier+1 par
     * auteur (les événements hors séquence sont mis en attente côté serveur,
     * mais un dépôt ordonné reste le chemin le plus court). Progression
     * rapportée par [onProgress] (fragments déposés / total) pour l'UI.
     */
    suspend fun sendFile(
        peerOnion: String,
        data: ByteArray,
        name: String,
        mime: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onStart: (fid: String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            android.util.Log.i("nyx-send", "→ fichier peer=${peerOnion.trim().take(16)}… " +
                "name=$name mime=$mime taille=${ChunkCodec.humanSize(data.size.toLong())}")
            if (data.isEmpty()) error("fichier vide")
            if (data.size > ChunkCodec.MAX_FILE_SIZE) {
                error("fichier trop volumineux (max ${ChunkCodec.humanSize(ChunkCodec.MAX_FILE_SIZE)})")
            }
            val peer = peerOnion.trim().lowercase()
            val link = contactDao.get(peer)
            if (link?.status != ContactStatus.ACCEPTED) {
                error(
                    if (link == null) "ce destinataire n'est pas dans vos contacts"
                    else "échange possible uniquement une fois l'invitation acceptée des deux côtés"
                )
            }
            val relay = relay() ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peer)

            val sha = sha256Hex(data)
            val fid = newFid()
            val safeName = ChunkCodec.sanitizeName(name).ifBlank { "fichier" }
            val chunks = ChunkCodec.split(data)
            val n = chunks.size
            onStart(fid)

            // Le fichier est écrit localement DÈS L'ENVOI — l'expéditeur relit
            // son propre média (aperçu/plein écran) sans attendre quoi que ce soit.
            val localPath = MediaIngest.persistMedia(ServiceLocator.appContext(), fid, safeName, data)
            attachmentDao.upsertMeta(
                AttachmentEntity(
                    fid, peer, outgoing = true, safeName, mime.ifBlank { "application/octet-stream" },
                    data.size.toLong(), sha, n, AT_TRANSFER, localPath, System.currentTimeMillis()
                )
            )

            var firstEventId = ""
            for ((idx, chunk) in chunks.withIndex()) {
                val seq = nextSeqFor(me)
                val ts = System.currentTimeMillis() / 1000
                val bodyJson = ChunkCodec.encode(
                    fid, safeName, mime, data.size.toLong(), sha, idx, n,
                    B64.encode(chunk)
                )
                val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
                val ev = WireEvent("msg", me, seq, ts, 0, peer, body, "")
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                val signed = ev.copy(sig = cipher.signEvent(canonical, priv))
                if (idx == 0) {
                    // La bulle locale est créée dès le premier fragment déposé
                    // (état « en cours » — passe « envoyé » à la fin du dépôt).
                    firstEventId = eventIdOf(canonical)
                    messageDao.insert(
                        MessageEntity(
                            firstEventId, peer, outgoing = true,
                            mediaPlaceholder(mime, safeName), ts, ts, STATE_PENDING,
                            kind = 1, fid = fid
                        )
                    )
                }
                postSigned(relay, me, signed, canonical, priv).fold(
                    onSuccess = {
                        onProgress(idx + 1, n)
                        android.util.Log.i("nyx-send", "   ✓ fragment ${idx + 1}/$n déposé (${ChunkCodec.humanSize(chunk.size.toLong())})")
                    },
                    onFailure = { e ->
                        messageDao.updateState(firstEventId, STATE_FAILED)
                        attachmentDao.setState(fid, AT_FAILED)
                        android.util.Log.w("nyx-send", "   ✗ fragment ${idx + 1}/$n ÉCHOUÉ : ${e.message}")
                        throw e
                    }
                )
            }
            messageDao.updateState(firstEventId, STATE_SENT)
            android.util.Log.i("nyx-send", "   ✓ fichier déposé ($n fragments, id=${fid.take(8)}…)")
            fid
        }
    }

    /** Identifiant de transfert : 16 octets aléatoires en hex (32 caractères). */
    private fun newFid(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** sha256 hex d'un tableau d'octets (vérification d'intégrité du fichier complet). */
    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** Libellé d'un message pièce jointe (aperçu de liste / notification). */
    fun mediaPlaceholder(mime: String, name: String): String = when {
        mime.startsWith("image/") -> "🖼 $name"
        mime.startsWith("video/") -> "🎬 $name"
        mime.startsWith("audio/") -> "🎤 $name"
        else -> "📄 $name"
    }

    /**
     * RÉCEPTION d'un fragment (corps « msg » déchiffré reconnu comme fichier) :
     * méta créée au premier fragment vu, fragment stocké (REPLACE idempotent),
     * bulle créée à l'index 0 (accusés delivered/read de v0.2.15 s'appliquent
     * aussi aux médias), ASSEMBLAGE dès que les n fragments sont là — sha256
     * vérifié sinon état échec (aucun média corrompu affiché), fichier écrit
     * sous filesDir/media/<fid>/ puis fragments effacés de la base.
     */
    private suspend fun handleIncomingChunk(
        author: String,
        canonical: String,
        ts: Long,
        chunk: ChunkCodec.FileChunk,
    ) {
        val existing = attachmentDao.get(chunk.fid)
        if (existing == null) {
            attachmentDao.upsertMeta(
                AttachmentEntity(
                    chunk.fid, author, outgoing = false, chunk.name,
                    chunk.mime.ifBlank { "application/octet-stream" },
                    chunk.size, chunk.sha256, chunk.total, AT_TRANSFER,
                    "", System.currentTimeMillis()
                )
            )
        }
        attachmentDao.insertChunk(AttachmentChunkEntity(chunk.fid, chunk.index, chunk.data))

        if (chunk.index == 0) {
            messageDao.insert(
                MessageEntity(
                    eventIdOf(canonical), author, outgoing = false,
                    mediaPlaceholder(chunk.mime, chunk.name),
                    ts, System.currentTimeMillis(), STATE_RECEIVED,
                    rcptPending = 1, kind = 1, fid = chunk.fid
                )
            )
        }

        val count = attachmentDao.countChunks(chunk.fid)
        val meta = attachmentDao.get(chunk.fid)
        if (meta != null && count >= meta.totalChunks && meta.state == AT_TRANSFER) {
            val assembled = attachmentDao.chunks(chunk.fid)
                .sortedBy { it.idx }
                .fold(ByteArray(0)) { acc, c -> acc + c.data }
            if (assembled.size.toLong() != meta.size || sha256Hex(assembled) != meta.sha256) {
                attachmentDao.setState(chunk.fid, AT_FAILED)
                android.util.Log.w(
                    "nyx-recv",
                    "   ✗ fichier ${chunk.fid.take(8)}… CORROMPU (taille ${assembled.size}/${meta.size}, sha en défaut) — abandonné"
                )
                return
            }
            val path = MediaIngest.persistMedia(ServiceLocator.appContext(), chunk.fid, meta.name, assembled)
            attachmentDao.setState(chunk.fid, AT_READY, path)
            attachmentDao.clearChunks(chunk.fid)
            android.util.Log.i(
                "nyx-recv",
                "   ✓ fichier assemblé : ${meta.name} (${ChunkCodec.humanSize(meta.size)}) de ${author.take(16)}…"
            )
        }
    }

    /* ---------------- accusés de réception/lecture (v0.2.15) ---------------- */

    /**
     * v0.2.15 — ACCUSÉS DE RÉCEPTION/LECTURE (demande opérateur : « un accusé
     * de réception et un accusé de lecture côté expéditeur »).
     *
     * CANAL : événement signé type « contact » — type DÉJÀ whitelisté par le
     * réplica v0.2.0, corps chiffré OPAQUE au serveur — donc AUCUN changement
     * serveur ni de déploiement. Body : {"alias":…,"rcpt":"delivered|read",
     * "ids":[eventId,…]}. Un client v0.2.14 qui en reçoit un l'ignore sans
     * effet de bord (branche « annonce déjà traitée »).
     *
     * SÉMANTIQUE (chaque appareil joue les DEUX rôles) :
     *  - destinataire : msg stocké → rcptPending=1 (« delivered » à déposer) ;
     *    conversation ouverte → readLocal=1 + rcptPending=2 (« read » à déposer,
     *    couvre le delivered) ; [ensureReceiptsSent] dépose les lots.
     *  - expéditeur : accusé reçu → deliveredAt/readAt horodatés sur les
     *    messages sortants (✓ Envoyé / ✓✓ Reçu / ✓✓ Lu).
     */
    private val RECEIPT_BATCH = 50 // ids max par événement (body raisonnable)

    private suspend fun sendReceipt(
        peerLower: String,
        kind: String,
        ids: List<String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val relay = relay() ?: error("aucun réplica configuré")
            val me = identity.onionAddress ?: error("identité absente")
            val priv = identity.secretKey()
            val peerPub = OnionCodec.onionToPubKey(peerLower)
            val bodyJson = ReceiptCodec.encode(identity.alias ?: "", kind, ids)
            val body = cipher.encryptFor(peerPub, bodyJson.toByteArray(Charsets.UTF_8))
            val seq = nextSeqFor(me)
            val ev = WireEvent(
                type = "contact", author = me, seq = seq,
                ts = System.currentTimeMillis() / 1000, ttl = 0,
                to = peerLower, body = body, sig = ""
            )
            val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
            postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
        }
    }

    /**
     * Dépose les accusés en attente, par pair, « delivered » puis « read ».
     * Appelé en fin de pollInbox (tentative immédiate) ET par la boucle
     * périodique (retry) — même mécanique que les annonces/acks.
     * Seuls les liens ACCEPTÉS reçoivent des accusés (pas d'accusé vers un
     * pair archivé — même verrou que l'envoi de messages).
     */
    suspend fun ensureReceiptsSent(): Unit = withContext(Dispatchers.IO) {
        if (replicas.isEmpty()) return@withContext
        for (peer in messageDao.peersWithPendingReceipts()) {
            if (contactDao.get(peer)?.status != ContactStatus.ACCEPTED) continue
            // « read » d'abord — il couvre le « delivered » (impliqué côté
            // expéditeur), et libère le plus d'état d'un coup.
            val readIds = messageDao.readPendingIds(peer)
            for (batch in readIds.chunked(RECEIPT_BATCH)) {
                sendReceipt(peer, "read", batch).fold(
                    onSuccess = {
                        messageDao.clearRcptPending(batch)
                        android.util.Log.i("nyx-send", "✓ accusé « read » déposé pour ${peer.take(16)}… (${batch.size} msg)")
                    },
                    onFailure = { e ->
                        android.util.Log.w("nyx-send", "✗ accusé « read » DIFFÉRÉ pour ${peer.take(16)}… : ${e.message}")
                    }
                )
            }
            val deliveredIds = messageDao.deliveredPendingIds(peer)
            for (batch in deliveredIds.chunked(RECEIPT_BATCH)) {
                sendReceipt(peer, "delivered", batch).fold(
                    onSuccess = {
                        messageDao.clearRcptPending(batch)
                        android.util.Log.i("nyx-send", "✓ accusé « delivered » déposé pour ${peer.take(16)}… (${batch.size} msg)")
                    },
                    onFailure = { e ->
                        android.util.Log.w("nyx-send", "✗ accusé « delivered » DIFFÉRÉ pour ${peer.take(16)}… : ${e.message}")
                    }
                )
            }
        }
    }

    /**
     * v0.2.15 — la conversation EST OUVERTE (écran chat visible) : tout ce qui
     * vient d'être reçu devient « lu » (indicateur destinataire de la liste)
     * et les accusés « read » correspondants partent au pair (tentative
     * immédiate, différé si le réplica ne répond pas).
     */
    suspend fun markConversationRead(peerOnion: String): Unit = withContext(Dispatchers.IO) {
        val peer = peerOnion.trim().lowercase()
        val n = messageDao.markReadLocally(peer)
        if (n > 0) {
            messageDao.promoteDeliveredToRead(peer)
            runCatching { ensureReceiptsSent() }
            android.util.Log.i("nyx-recv", "   · conversation $peer marquée lue ($n msg) → accusés « read » en route")
        }
    }

    /* ---------------- cache des profils des pairs (v0.2.15) ---------------- */

    /**
     * v0.2.15 — AVATARS/noms publics des contacts dans la liste (demande
     * opérateur : « à côté de chaque alias doit apparaître sa photo si elle
     * est publique, une icône générique si elle est privée »).
     *
     * Les événements `profile` en diffusion ne passent PAS par l'inbox (le
     * serveur ne livre que les événements adressés — journal.Inbox) : la liste
     * rafraîchit donc le cache local au passage (TTL 1 h par pair, fetch
     * GET /client/profile via Tor, échec silencieux — icône générique en
     * attendant). Un pair SANS profil public est re-testé à chaque passage :
     * quelques requêtes courantes seulement (nombre de contacts faible), et
     * le cas « a rendu son profil public depuis » est rattrapé vite.
     */
    suspend fun refreshPeerProfiles(onions: List<String>, ttlMs: Long = 3_600_000L): Unit =
        withContext(Dispatchers.IO) {
            if (replicas.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            for (onion in onions) {
                val cached = peerProfileDao.get(onion)
                if (cached != null && now - cached.fetchedAt < ttlMs) continue
                runCatching {
                    val card = relay()?.profile(onion) ?: return@runCatching
                    peerProfileDao.upsert(
                        PeerProfileEntity(
                            onion = onion,
                            displayName = card.fields["display_name"]?.value.orEmpty(),
                            avatar = card.fields["avatar"]?.value.orEmpty(),
                            fetchedAt = System.currentTimeMillis()
                        )
                    )
                    android.util.Log.i("nyx-repo", "profil pair en cache : ${onion.take(16)}… (avatar=${card.fields.containsKey("avatar")})")
                }
            }
        }

    /** v0.2.15 — cache observable pour une carte de contact (avatar/nom). */
    fun peerProfileFlow(onion: String): Flow<PeerProfileEntity?> =
        peerProfileDao.byOnion(onion.trim().lowercase())

    /** v0.2.12 — le destinataire ACCEPTE l'invitation reçue : statut local
     *  « accepted » immédiat + ack au pair (différé si le réplica est injoignable —
     *  reparti par [ensureContactNotified] via la boucle 30 s). */
    suspend fun acceptContact(onion: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = onion.trim().lowercase()
            contactDao.setStatus(clean, ContactStatus.ACCEPTED)
            contactDao.setAckPending(clean, 1)
            sendContactAck(clean, "accepted").onSuccess {
                contactDao.setAckPending(clean, 0)
            }
            Unit
        }
    }

    /** v0.2.12 — le destinataire REFUSE l'invitation reçue : statut « declined »
     *  + ack ; le contact reste visible (supprimable par pression longue). */
    suspend fun declineContact(onion: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = onion.trim().lowercase()
            contactDao.setStatus(clean, ContactStatus.DECLINED)
            contactDao.setAckPending(clean, 1)
            sendContactAck(clean, "declined").onSuccess {
                contactDao.setAckPending(clean, 0)
            }
            Unit
        }
    }

    suspend fun addContact(onion: String, alias: String): Result<Boolean> = runCatching {
        val clean = onion.trim().lowercase() // v0.2.11 : normalisation AVANT validation/stockage
        OnionCodec.onionToPubKey(clean) // validation de l'adresse
        val existing = contactDao.get(clean)
        // v0.2.12 : ré-ajouter quelqu'un qui nous a déjà invités (statut
        // « incoming ») = ACCEPTER son invitation — ack au pair, pas d'annonce.
        if (existing?.status == ContactStatus.INCOMING) {
            contactDao.setStatus(clean, ContactStatus.ACCEPTED)
            contactDao.setAckPending(clean, 1)
            val ackOk = sendContactAck(clean, "accepted").isSuccess
            if (ackOk) contactDao.setAckPending(clean, 0)
            return@runCatching ackOk
        }
        // v0.2.14 : ré-inviter un contact ARCHIVÉ (refusé/supprimé — demande
        // opérateur : « un contact supprimé peut être re-invité ») = NOUVELLE
        // annonce : remise à zéro du lien wire, puis branche annonce normale.
        val wasArchived = existing != null && net.nyx.messenger.data.db.ContactStatus.archived(existing.status)
        if (wasArchived) contactDao.resetForReinvite(clean)
        if (existing == null) {
            contactDao.upsert(ContactEntity(clean, alias.ifBlank { clean.take(10) + "…" }, System.currentTimeMillis()))
        } else if (alias.isNotBlank() && !wasArchived) {
            // rafraîchit l'alias local SANS écraser l'état d'invitation (REPLACE)
            contactDao.upsert(existing.copy(alias = alias))
        }
        // Annonce au pair — UNIQUEMENT si aucun lien wire n'existe encore
        // (status='' : jamais annoncée ni reçue ; pending/accepted = le pair nous
        // connaît déjà, re-annoncer ne ferait que consommer des seq).
        // v0.2.14 : un lien ARCHIVÉ redevient une annonce fraîche.
        // L'échec n'annule PAS l'ajout local : reparti par la boucle 30 s.
        val transmise = if (existing == null || existing.status == ContactStatus.NONE || wasArchived) {
            val ok = sendContactNotification(clean).isSuccess
            if (ok) contactDao.markNotified(clean, System.currentTimeMillis())
            else contactDao.setNotifyPending(clean, 1)
            ok
        } else true
        transmise
    }

    /**
     * v0.2.14 — SUPPRESSION D'UN CONTACT PROPAGÉE AU PAIR (demande opérateur :
     * « si un contact est supprimé… il doit être supprimé du côté du téléphone
     * qui invite, avec une notification »).
     *
     * Sémantique :
     *  - si un LIEN wire existe (contact annoncé ou accepté) : événement signé
     *    type « contact » body chiffré {"alias":…,"ack":"removed"} au pair (type
     *    DÉJÀ whitelisté serveur — zéro changement) ; le pair archivera le
     *    contact et sera notifié. Échec réplica = différé (ackPending), reparti
     *    par la boucle 30 s comme les acks.
     *  - la ligne locale est ARCHIVÉE (status « removed_by_me ») et NON détruite :
     *    « les anciennes conversations doivent toujours être accessibles » — la
     *    conversation reste lisible (section Anciennes conversations), le contact
     *    disparaît de la liste active, et la re-invitation est possible.
     *  - pas de lien wire (ajout local jamais déposé, invitation jamais traitée)
     *    : archivage pur et simple, rien à signaler au pair.
     *  - la CONVERSATION n'est PAS touchée ici : sa suppression est une action
     *    distincte et strictement locale ([deleteConversation]).
     */
    suspend fun deleteContact(onion: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = onion.trim().lowercase()
            val c = contactDao.get(clean)
            if (c == null) {
                android.util.Log.i("nyx-repo", "contact absent (déjà supprimé) : ${clean.take(16)}…")
                return@runCatching
            }
            val lie = when (c.status) {
                ContactStatus.ACCEPTED, ContactStatus.PENDING -> true
                // v0.2.14 : une annonce JAMAIS déposée (notifyPending, announcedAt=0)
                // = le pair ne nous connaît pas — rien à lui signaler, archivage pur.
                else -> false
            }
            if (lie) {
                contactDao.setStatus(clean, ContactStatus.REMOVED_BY_ME)
                contactDao.setAckPending(clean, 1)
                // Dépôt immédiat ; en cas d'échec, la boucle 30 s repartira l'avis
                // (ensureContactNotified → pendingAcks → ackOf(REMOVED_BY_ME)).
                sendContactAck(clean, "removed").onSuccess {
                    contactDao.setAckPending(clean, 0)
                }.onFailure { e ->
                    android.util.Log.w("nyx-send", "✗ avis suppression DIFFÉRÉ pour ${clean.take(16)}… : ${e.message}")
                }
            } else {
                contactDao.setStatus(clean, ContactStatus.REMOVED_BY_ME)
            }
            net.nyx.messenger.notify.ContactNotifier.cancelInvitation(clean)
            android.util.Log.i("nyx-repo", "contact supprimé (archivé, conversation conservée) : ${clean.take(16)}…")
            Unit
        }
    }

    /** v0.2.14 — SUPPRESSION LOCALE D'UNE CONVERSATION (demande opérateur :
     *  « uniquement le téléphone qui supprime une conversation n'y a plus accès »).
     *  Strictement locale — aucun événement wire, le pair conserve SA copie. */
    suspend fun deleteConversation(onion: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = onion.trim().lowercase()
            messageDao.deleteConversation(clean)
            // v0.2.17 : les médias de la conversation partent aussi (méta +
            // fragments en vol ; les fichiers sur disque restent — purgés par
            // le système si l'app est désinstallée, sans risque d'effacer un
            // média partagé par ailleurs).
            attachmentDao.deleteChunksByPeer(clean)
            attachmentDao.deleteByPeer(clean)
            android.util.Log.i("nyx-repo", "conversation supprimée (local) : ${clean.take(16)}…")
            Unit
        }
    }

    /* ---------------- profil (v0.2.0 — wire-format.md §7) ---------------- */

    /**
     * Publie la carte PUBLIQUE du profil (seule la partie publique part —
     * invariant §7.2 : un champ privé ne quitte jamais l'appareil). Dépose un
     * événement `profile` en diffusion (to vide) signé, type standard du journal.
     */
    suspend fun publishProfile(store: net.nyx.messenger.profile.ProfileStore): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (bodyCard, err) = store.publicCard()
                err?.let { error(it) }
                val card = bodyCard ?: return@runCatching // rien de public : no-op
                val relay = relay() ?: error("aucun réplica configuré")
                val me = identity.onionAddress ?: error("identité absente")
                val priv = identity.secretKey()

                val body = B64.encode(
                    net.nyx.messenger.profile.ProfileCodec.encode(card).toByteArray(Charsets.UTF_8)
                )
                val seq = nextSeqFor(me)
                val ev = WireEvent(
                    type = "profile", author = me, seq = seq,
                    ts = System.currentTimeMillis() / 1000, ttl = 0,
                    to = null, body = body, sig = ""
                )
                val canonical = MessageCipher.canonical(ev.type, ev.author, ev.seq, ev.ts, ev.ttl, ev.to ?: "", ev.body)
                postSigned(relay, me, ev.copy(sig = cipher.signEvent(canonical, priv)), canonical, priv).getOrThrow()
            }
        }

    /**
     * Carte publique d'un contact (GET /client/profile — dernier seq gagne,
     * §7.3). null si le contact n'a pas de profil public ou réseau indisponible.
     * v0.2.15 : chaque fetch réussi alimente le CACHE des profils de pairs —
     * la liste des conversations affiche l'avatar/nom sans nouvelle requête.
     */
    suspend fun contactProfile(onion: String): Result<net.nyx.messenger.net.ProfileResponse?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relay = relay() ?: error("aucun réplica configuré")
                val lower = onion.lowercase()
                val card = relay.profile(lower)
                if (card != null) {
                    peerProfileDao.upsert(
                        PeerProfileEntity(
                            onion = lower,
                            displayName = card.fields["display_name"]?.value.orEmpty(),
                            avatar = card.fields["avatar"]?.value.orEmpty(),
                            fetchedAt = System.currentTimeMillis()
                        )
                    )
                }
                card
            }
        }

    /**
     * v0.2.14 — PUBLICATION DE PROFIL FIABLE (demande opérateur : « vérifie que
     * les infos publiques soient bien partagées entre les contacts »).
     * Jusqu'ici la carte publique ne partait qu'AU MOMENT du save : un profil
     * rendu public avant que le réplica ne réponde restait invisible pour les
     * contacts pour toujours. Désormais : si une publication est en attente
     * (échec au save), la boucle 30 s la redépose dès que possible — même
     * mécanique que les annonces/acks de contact.
     */
    suspend fun ensureProfilePublished(): Unit = withContext(Dispatchers.IO) {
        val store = net.nyx.messenger.profile.ProfileStore.get(ServiceLocator.appContext())
        if (!store.isPublishPending()) return@withContext
        publishProfile(store).fold(
            onSuccess = {
                store.clearPublishPending()
                android.util.Log.i("nyx-send", "✓ profil public (re)publié (différé rattrapé)")
            },
            onFailure = { e ->
                android.util.Log.w("nyx-send", "✗ publication profil différée : ${e.message}")
            }
        )
    }

    /* ---------------- contacts par alias (v0.2.0) ---------------- */

    /** Résultat d'un ajout de contact par pseudonyme unique. */
    sealed class AliasAddResult {
        /** Contact ajouté (alias résolu → [onion]). [notified] : annonce transmise au pair. */
        data class Added(val onion: String, val alias: String, val notified: Boolean) : AliasAddResult()
        /** Alias libre sur le réplica : personne ne le détenteur. */
        data object Free : AliasAddResult()
    }

    /**
     * Ajoute un contact à partir de son ALIAS UNIQUE (invitations « @pseudo ») :
     * résolution via GET /client/alias (wire-format.md §6.4) puis enregistrement
     * local de l'adresse onion résolue. Aucun listing global : résolution
     * point à point uniquement.
     */
    suspend fun addContactByAlias(alias: String): Result<AliasAddResult> = withContext(Dispatchers.IO) {
        runCatching {
            val name = net.nyx.messenger.crypto.AliasValidator.normalize(alias)
            check(net.nyx.messenger.crypto.AliasValidator.isValid(name)) { "format d'alias invalide" }
            val relay = relay() ?: error("aucun réplica configuré")
            val res = relay.resolveAlias(name)
            if (!res.taken) return@runCatching AliasAddResult.Free
            val onion = res.by ?: error("résolution incomplète (alias pris sans détenteur)")
            OnionCodec.onionToPubKey(onion) // cohérence (l'adresse est la clé publique)
            val lower = onion.trim().lowercase() // v0.2.11 : normalisation défensive
            // v0.2.12 : mêmes gardes que addContact — accepter l'invitation
            // « incoming » existante, sinon préserver l'état (REPLACE = perte).
            val existing = contactDao.get(lower)
            if (existing?.status == ContactStatus.INCOMING) {
                contactDao.setStatus(lower, ContactStatus.ACCEPTED)
                contactDao.setAckPending(lower, 1)
                val ackOk = sendContactAck(lower, "accepted").isSuccess
                if (ackOk) contactDao.setAckPending(lower, 0)
                return@runCatching AliasAddResult.Added(lower, name, ackOk)
            }
            // v0.2.14 : re-invitation d'un lien archivé — remise à zéro + annonce.
            val wasArchived = existing != null &&
                net.nyx.messenger.data.db.ContactStatus.archived(existing.status)
            if (wasArchived) contactDao.resetForReinvite(lower)
            if (existing == null) {
                contactDao.upsert(ContactEntity(lower, name, System.currentTimeMillis()))
            }
            // Annonce au pair (v0.2.10) — uniquement si aucun lien wire n'existe ;
            // différée si le réplica ne répond pas (repartie par la boucle 30 s).
            // v0.2.14 : un lien archivé repart comme une annonce fraîche.
            val notified = if (existing == null || existing.status == ContactStatus.NONE || wasArchived) {
                val ok = sendContactNotification(lower).isSuccess
                if (ok) contactDao.markNotified(lower, System.currentTimeMillis())
                else contactDao.setNotifyPending(lower, 1)
                ok
            } else true
            AliasAddResult.Added(lower, name, notified)
        }
    }

    companion object {
        const val STATE_PENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
        // v0.2.17 — états d'un transfert de pièce jointe (attachments.state).
        const val AT_TRANSFER = 0   // fragments en vol (dans un sens ou l'autre)
        const val AT_READY = 1      // fichier assemblé et vérifié (localPath valide)
        const val AT_FAILED = 2     // transfert abandonné (dépôt impossible)
    }
}
