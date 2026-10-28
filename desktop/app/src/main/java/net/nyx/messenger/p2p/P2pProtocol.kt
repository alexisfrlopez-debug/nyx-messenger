package net.nyx.messenger.p2p

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * v0.3.6 — PORTAGE MIRROIR de P2pProtocol Android v0.2.18-0.2.53 (ADR-015),
 * + EXTENSION v0.3.6 : champ [Hello.ac] (codec audio négocié).
 *
 * Les DEUX clients publient déjà un service onion (leur identité). Ce canal
 * ajoute un SECOND port à ce service (47471) mappé sur un écouteur local :
 *  - l'appelant ouvre une connexion SOCKS → tor → onion du pair ;
 *  - le destinataire reçoit la connexion sur son écouteur local.
 *
 * Tor fournit le chiffrement du transport (onion v3 = E2E jusqu'à la clé de
 * l'identité du pair) ; l'authentification applicative repose sur une
 * signature ed25519 du HELLO par la CLÉ D'IDENTITÉ de l'expéditeur — le
 * destinataire vérifie onion ↔ signature ↔ contact accepté.
 *
 * Trame : [type:1][longueur:4 BE][payload].
 *
 * CODEC AUDIO (v0.3.6 — interop bureau↔téléphone) : l'Android encode l'audio
 * en AAC via MediaCodec (matériel — inexistant sur JVM). Le bureau ne peut NI
 * encoder NI décoder l'AAC en pur Java sans binaires natifs ; les DEUX côtés
 * portent donc un codec logiciel commun ADPCM IMA 4-bit (16 kHz mono ≈ 64 kbps
 * — très en dessous de la capacité d'un circuit onion). Négociation :
 *  - le HELLO/ACCEPT du bureau porte "ac":"adpcm" ;
 *  - un Android ≥ v0.2.54 lit le champ et branche son codec logiciel ADPCM ;
 *  - un Android ≤ v0.2.53 l'ignore (MiniJson = extraction par clé) — l'appel
 *    est alors REFUSÉ proprement par le bureau (audio incompatible), avec un
 *    message demandant la mise à jour (auto-update du canal test).
 * Le champ absent/"" = AAC (téléphone↔téléphone, inchangé).
 *
 * CODEC VIDÉO (v0.3.7 — interop visio bureau↔téléphone) : même mécanique que
 * « ac » sur le champ [Hello.vc] / [AcceptInfo.vc] :
 *  - le bureau ne peut NI encoder NI décoder le H.264 MediaCodec d'Android en
 *    pur Java → codec vidéo logiciel commun MJPEG (JPEG auto-contenu par
 *    trame : ImageIO au bureau, YuvImage.compressToJpeg sur Android — zéro
 *    dépendance des deux côtés) ;
 *  - HELLO.vc = codecs vidéo SUPPORTÉS par l'appelant (« mjpeg » bureau ;
 *    « h264,mjpeg » téléphone ≥ v0.2.55 ; ABSENT = H.264 seul ≤ v0.2.54) ;
 *  - ACCEPT.vc = codec CHOISI par l'appelé (« mjpeg » SEULEMENT quand le
 *    mjpeg est choisi — téléphone↔téléphone reste OCTET POUR OCTET le
 *    format v0.2.20 : champ absent) ;
 *  - en MJPEG les trames [F_VIDEO] portent un JPEG COMPLET (vcsd vide — pas
 *    de paramètres de décodeur), [F_VIDEO_ROT] s'applique tel quel.
 */
object P2pProtocol {

    /** Port onion dédié au canal direct (2ᵉ directive Port= de ADD_ONION). */
    const val MEDIA_ONION_PORT = 47471

    const val F_HELLO = 1        // authentification + intention (call|file|events)
    const val F_FILE = 0x10      // fragment de fichier (corps chiffré E2E, b64)
    const val F_EVENT = 0x18     // v0.2.41 — événement journal signé (JSON WireEvent)
    const val F_AUDIO = 0x14     // trame audio (AAC access unit brute — ou ADPCM bureau)
    const val F_VIDEO = 0x16     // v0.2.20 — trame vidéo (access unit H.264 brute)
    const val F_VIDEO_ROT = 0x17 // v0.2.53 — rotation d'écran de l'émetteur (payload « 0/90/180/270 »)
    const val F_CALL_ACCEPT = 0x12  // payload = csd/codec de l'appelé (voir AcceptPayload)
    const val F_CALL_REJECT = 0x13
    const val F_BYE = 0x15       // raccroché
    const val F_ACK = 0x7F       // payload = [reçus:4 BE]

    /** Codec audio logiciel du bureau (négocié via [Hello.ac]). */
    const val AC_AAC = "aac"
    const val AC_ADPCM = "adpcm"

    /** v0.3.7 — codecs vidéo négociés via [Hello.vc]/[AcceptInfo.vc]. */
    const val VC_H264 = "h264"
    const val VC_MJPEG = "mjpeg"

    /**
     * v0.3.10 — MJPEG PRÉ-TOURNÉ accordé par l'APPELÉ (bureau ≥ v0.3.10) :
     * valeur du champ ACCEPT « vpr ». Un téléphone appelant ≥ v0.2.58 qui
     * lit "1" pré-tourne ses trames à l'encodage (portrait 240x320 ↔ paysage
     * 320x240 — l'aspect vit dans le JPEG lui-même) et n'émet PLUS de
     * F_VIDEO_ROT. Un pair plus ancien ignore la clé : comportement
     * historique inchangé. Miroir Android v0.2.58.
     */
    const val VPR_PREROTATED = "1"

    /** v0.3.7 — le champ vc de l'appelant annonce-t-il le MJPEG ? */
    fun supportsMjpeg(vc: String): Boolean =
        vc.split(',').map { it.trim() }.filter { it.isNotEmpty() }.contains(VC_MJPEG)

    data class Frame(val type: Int, val payload: ByteArray)

    fun write(out: OutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        val d = DataOutputStream(out)
        d.writeByte(type)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    /** Lit une trame — null = flux fermé proprement (EOF avant en-tête). */
    fun read(input: InputStream): Frame? {
        val d = DataInputStream(input)
        val type = try { d.readUnsignedByte() } catch (_: EOFException) { return null }
        val len = d.readInt()
        require(len >= 0 && len <= 4 * 1024 * 1024) { "trame P2P aberrante ($len octets)" }
        val payload = ByteArray(len)
        d.readFully(payload)
        return Frame(type, payload)
    }

    /**
     * v0.2.48 — LECTURE DU PREMIER HELLO avec distinction de la connexion
     * MUETTE (sonde de joignabilité sans trame). [HelloFirst.mute] = EOF
     * avant le premier octet ; [HelloFirst.bad] = octets reçus mais trame
     * cassée ; [HelloFirst.hello] = Hello parsé (null = trame valide mais pas
     * un HELLO). Format figé depuis v0.2.18 (verrouillé par tests des deux
     * côtés).
     */
    data class HelloFirst(val mute: Boolean, val bad: Boolean, val hello: Hello?)

    fun readHello(input: InputStream): HelloFirst {
        val d = DataInputStream(input)
        val type = try { d.read() } catch (_: Exception) { return HelloFirst(false, true, null) }
        if (type == -1) return HelloFirst(true, false, null)   // EOF : rien envoyé
        if (type != F_HELLO) return HelloFirst(false, false, null)
        return try {
            val len = d.readInt()
            if (len < 0 || len > 4 * 1024 * 1024) return HelloFirst(false, true, null)
            val payload = ByteArray(len)
            d.readFully(payload)
            HelloFirst(false, false, parseHello(payload))
        } catch (_: Exception) {
            HelloFirst(false, true, null)
        }
    }

    // ---------- HELLO ----------

    /**
     * Corps du HELLO (JSON) — `sig` couvre la chaîne
     * « nyx-p2p|<t>|<onion>|<sel> » (sel = cid pour un appel, count pour un
     * fichier) ; vérification : onionToPubKey(onion) + contact accepté.
     */
    data class Hello(
        val kind: String,      // "call" | "file" | "events"
        val onion: String,     // onion de l'EXPÉDITEUR (authentification)
        val alias: String,     // alias déclaré (cosmétique)
        val cid: String,       // appel : identifiant de session
        val csd: String,       // appel : csd-0 AAC de l'appelant (b64) — calc AAC si AC_ADPCM (un pair ≤ v0.2.53 configure son décodeur sans rien décoder d'utile)
        val count: Int,        // fichier/events : nombre d'unités annoncées
        val sig: String,       // signature ed25519 (b64) de [helloSignedString]
        val vcsd: String = "", // v0.2.20 : csd H.264 (SPS+PPS b64) si visio, "" sinon
        // v0.2.53 — présentation vidéo (interop : absents chez un pair v0.2.52 → 0)
        val vrot: Int = 0,     // rotation horaire qui rend l'image droite (0/90/180/270)
        val vw: Int = 0,       // largeur d'encodage de l'émetteur
        val vh: Int = 0,       // hauteur d'encodage de l'émetteur
        // v0.3.6 — CODEC AUDIO de l'émetteur ("adpcm" bureau ; "" = AAC Android
        // ≤ v0.2.53). Un appelé Android ≥ v0.2.54 lit le champ ; un ancien
        // l'ignore (il ne décodera du silence — l'appel sera refusé avant).
        val ac: String = "",
        // v0.3.7 — CODECS VIDÉO de l'émetteur ("mjpeg" bureau ; "h264,mjpeg"
        // téléphone ≥ v0.2.55 ; "" = H.264 seul ≤ v0.2.54). L'appelé y choisit
        // (ACCEPT.vc). Un pair ancien ignore le champ (MiniJson par clé).
        val vc: String = "",
    )

    fun encodeHello(h: Hello, sigB64: String): ByteArray {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val sb = StringBuilder("{\"v\":1,\"t\":\"").append(esc(h.kind)).append('"')
            .append(",\"onion\":\"").append(esc(h.onion)).append('"')
            .append(",\"alias\":\"").append(esc(h.alias)).append('"')
            .append(",\"cid\":\"").append(esc(h.cid)).append('"')
            .append(",\"csd\":\"").append(esc(h.csd)).append('"')
            .append(",\"count\":").append(h.count)
        // v0.3.7 — présentation vidéo aussi en MJPEG (vcsd vide mais vw/vh
        // porteurs de l'aspect — un pair ancien ignore ces clés connues).
        if (h.vcsd.isNotEmpty() || h.vc.isNotEmpty()) {
            if (h.vcsd.isNotEmpty()) {
                sb.append(",\"vcsd\":\"").append(esc(h.vcsd)).append('"')
            }
            sb.append(",\"vrot\":").append(h.vrot.coerceIn(0, 359))
                .append(",\"vw\":").append(h.vw.coerceIn(0, 4096))
                .append(",\"vh\":").append(h.vh.coerceIn(0, 4096))
        }
        if (h.ac.isNotEmpty()) {
            sb.append(",\"ac\":\"").append(esc(h.ac)).append('"')
        }
        if (h.vc.isNotEmpty()) {
            sb.append(",\"vc\":\"").append(esc(h.vc)).append('"')
        }
        sb.append(",\"sig\":\"").append(esc(sigB64)).append("\"}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseHello(payload: ByteArray): Hello? {
        val s = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return null
        val kind = MiniJson.str(s, "t")
        if (kind != "call" && kind != "file" && kind != "events") return null
        val onion = MiniJson.str(s, "onion")?.lowercase()
        if (onion == null || !onion.endsWith(".onion") || onion.length < 50) return null
        return Hello(
            kind, onion, MiniJson.str(s, "alias") ?: "", MiniJson.str(s, "cid") ?: "",
            MiniJson.str(s, "csd") ?: "", MiniJson.num(s, "count")?.toInt() ?: 0,
            MiniJson.str(s, "sig") ?: "",
            MiniJson.str(s, "vcsd") ?: "",
            MiniJson.num(s, "vrot")?.toInt() ?: 0,
            MiniJson.num(s, "vw")?.toInt() ?: 0,
            MiniJson.num(s, "vh")?.toInt() ?: 0,
            MiniJson.str(s, "ac") ?: "",
            MiniJson.str(s, "vc") ?: "",
        )
    }

    /**
     * v0.2.53 — résultat du [AcceptPayload.parse] : csd audio + csd vidéo +
     * présentation vidéo de l'APPELÉ (0/0/0 chez un pair ≤ v0.2.52).
     * v0.3.6 : + [ac] — codec annoncé par l'appelé ("" = AAC).
     * v0.3.10 : + [vpr] — l'appelé bureau accepte le MJPEG PRÉ-TOURNÉ
     * ("1" : un téléphone appelant ≥ v0.2.58 pré-tourne ses trames à
     * l'encodage — portrait 240x320 ↔ paysage 320x240 — et n'émet PLUS de
     * métadonnée de rotation ; "" = comportement historique ≤ v0.2.57).
     */
    data class AcceptInfo(
        val audioCsd: String,
        val videoCsd: String,
        val vrot: Int = 0,
        val vw: Int = 0,
        val vh: Int = 0,
        val ac: String = "",
        // v0.3.7 — codec vidéo CHOISI par l'appelé ("mjpeg" seulement quand
        // le mjpeg est choisi ; "" = H.264, format historique inchangé).
        val vc: String = "",
        // v0.3.10 — MJPEG pré-tourné accepté (miroir Android v0.2.58).
        val vpr: String = "",
    )

    /**
     * v0.2.20 — PAYLOAD du F_CALL_ACCEPT : JSON {csd, vcsd, vrot, vw, vh}
     * quand de la vidéo est négociée, sinon le b64 AAC brut de v0.2.18/19.
     * v0.3.6 : le bureau répond TOUJOURS en JSON avec "ac":"adpcm" (un appelant
     * Android ≤ v0.2.53 lit le champ "csd" par MiniJson… NON — le format
     * historique BRUT reste le seul lu par v0.2.19 ; v0.2.20+ parse le JSON.
     * Un téléphone v0.2.53 appelant reçoit donc un JSON inattendu pour lui MAIS
     * AcceptPayload.parse LE gère : « { » → branche JSON. Le bureau refuse de
     * toute façon l'appel AAC avant d'émettre l'ACCEPT.)
     */
    object AcceptPayload {
        fun encode(
            audioCsdB64: String, videoCsdB64: String,
            vrot: Int = 0, vw: Int = 0, vh: Int = 0,
            ac: String = "",
            vc: String = "",
            vpr: String = "",
        ): String {
            if (videoCsdB64.isEmpty() && ac.isEmpty() && vc.isEmpty() && vpr.isEmpty()) return audioCsdB64 // format historique
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            val acField = if (ac.isNotEmpty()) ",\"ac\":\"${esc(ac)}\"" else ""
            val vcField = if (vc.isNotEmpty()) ",\"vc\":\"${esc(vc)}\"" else ""
            val vprField = if (vpr.isNotEmpty()) ",\"vpr\":\"${esc(vpr)}\"" else ""
            return "{\"csd\":\"${esc(audioCsdB64)}\",\"vcsd\":\"${esc(videoCsdB64)}\"" +
                ",\"vrot\":${vrot.coerceIn(0, 359)},\"vw\":${vw.coerceIn(0, 4096)}" +
                ",\"vh\":${vh.coerceIn(0, 4096)}$acField$vcField$vprField}"
        }

        /** Infos d'acceptation — tolère l'ancien format brut ET le JSON v0.2.20. */
        fun parse(payload: ByteArray): AcceptInfo {
            val s = runCatching { String(payload, Charsets.UTF_8) }.getOrDefault("")
            if (s.startsWith("{")) {
                val audio = MiniJson.str(s, "csd") ?: ""
                val video = MiniJson.str(s, "vcsd") ?: ""
                if (audio.isNotEmpty()) return AcceptInfo(
                    audio, video,
                    MiniJson.num(s, "vrot")?.toInt() ?: 0,
                    MiniJson.num(s, "vw")?.toInt() ?: 0,
                    MiniJson.num(s, "vh")?.toInt() ?: 0,
                    MiniJson.str(s, "ac") ?: "",
                    MiniJson.str(s, "vc") ?: "",
                    MiniJson.str(s, "vpr") ?: "",
                )
            }
            return AcceptInfo(s, "")
        }
    }

    /** Chaîne signée du HELLO — miroir exact chez le vérificateur. */
    fun helloSignedString(kind: String, onion: String, selector: String): String =
        "nyx-p2p|$kind|$onion|$selector"

    /**
     * v0.3.8 — LECTURE DE LA RÉPONSE D'APPEL (miroir Android v0.2.56).
     *
     * CAUSE RACINE TERRAIN 28/10 (5ᵉ export bureau) : un appelé ≤ v0.2.55
     * expédiait une trame F_VIDEO_ROT AVANT son F_CALL_ACCEPT — et ce
     * gestionnaire lisait UNE SEULE trame : la rotation était prise pour
     * « pas de réponse » → BYE silencieux → l'appel visio PC↔téléphone
     * s'effondrait à l'INSTANT du décroché (le téléphone avait accepté à
     * 07:19:13, le bureau n'a JAMAIS affiché « appel actif »).
     *
     * La boucle lit jusqu'à la VRAIE réponse en ROUTANT les trames précoces
     * (rotation → présentation UI ; audio/vidéo → ignorées). Null = EOF
     * (pas de réponse) ; [deadlineMs] borne contre un pair qui spamerait.
     */
    fun readCallAnswer(input: java.io.InputStream, deadlineMs: Long, onRotation: (ByteArray) -> Unit): Frame? {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            val f = runCatching { read(input) }.getOrNull() ?: return null
            when (f.type) {
                F_CALL_ACCEPT, F_CALL_REJECT, F_BYE -> return f
                F_VIDEO_ROT -> onRotation(f.payload)   // pair ≤ v0.2.55 : AVANT l'ACCEPT
                else -> {}                              // F_AUDIO/F_VIDEO précoces : ignorés
            }
        }
        return null
    }
}
