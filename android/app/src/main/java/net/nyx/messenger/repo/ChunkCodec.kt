package net.nyx.messenger.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v0.2.17 — CODEC DES FRAGMENTS DE FICHIER (demande opérateur : « partage
 * photo/video, partage de fichier » — toutes les pièces jointes des autres
 * messengers, dans la limite du transport onion).
 *
 * CANAL : événement type « msg » (DÉJÀ whitelisté par le réplica v0.2.0),
 * corps chiffré OPAQUE au serveur (sealed box E2E) — zéro changement serveur.
 * Chaque fragment est un événement signé indépendant (seq propre) :
 *
 *     {"v":1,"kind":"file","fid":"<32 hex>","name":"photo.jpg",
 *      "mime":"image/jpeg","size":523456,"sha256":"<64 hex>",
 *      "i":0,"n":40,"data":"<base64 du fragment>"}
 *
 *  - fid      : identifiant du transfert (aléatoire, 16 octets hex) ;
 *  - name/mime/size/sha256 : méta du fichier COMPLET, répétées sur chaque
 *    fragment (crash-safe — le récepteur reconstitue quel que soit l'ordre
 *    d'arrivée, la inbox livrant néanmoins en ordre de seq) ;
 *  - i/n      : index du fragment / nombre total ;
 *  - data     : base64 du fragment (≤ [CHUNK_PAYLOAD] octets binaires).
 *
 * Budget de taille (limite serveur MaxBodySize = 256 Ko sur le body base64) :
 * 96 Ko binaires → 128 Ko base64 → sealed box + JSON ≈ 171 Ko < 256 Ko.
 *
 * Un corps qui n'est PAS un fragment (texte « bonjour », invitation
 * {"alias":…}, accusé {"alias":…,"rcpt":…}) ne parse PAS — les clients
 * v0.2.16 stockent le JSON tel quel (cosmétique, sans effet de bord) et les
 * v0.2.15/14 l'ignorent de leur côté.
 */
object ChunkCodec {
    const val WIRE_VERSION = 1
    const val KIND = "file"

    /** Taille cible d'un fragment en octets BINAIRES (avant base64). */
    const val CHUNK_PAYLOAD = 96 * 1024

    /** Taille maximale d'un fichier transportable (64 Mo — garde-fou UX). */
    const val MAX_FILE_SIZE = 64L * 1024 * 1024

    /** Nombre max de fragments d'un transfert (64 Mo / 96 Ko ≈ 683). */
    const val MAX_CHUNKS = 700

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    data class FileChunkJson(
        val v: Int,
        val kind: String,
        val fid: String,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
        val i: Int,
        val n: Int,
        val data: String,
        // v0.2.28 — politique d'affichage (style Instagram), répétée sur CHAQUE
        // fragment (crash-safe comme name/mime) : "forever" (défaut — les
        // fragments v0.2.16-27 sans ce champ lisent la valeur par défaut,
        // rétrocompatible via ignoreUnknownKeys) | "once" | "twice".
        val view: String = "forever"
    )

    /** Fragment décodé et VALIDÉ (data déjà décodée en binaire). */
    data class FileChunk(
        val fid: String,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
        val index: Int,
        val total: Int,
        val data: ByteArray,
        /** v0.2.28 — "forever" | "once" | "twice" (vue unique / 1 relecture). */
        val view: String = "forever"
    )

    /** Corps JSON du fragment (chiffré ensuite par le canal « msg »). */
    fun encode(
        fid: String, name: String, mime: String, size: Long,
        sha256: String, index: Int, total: Int, dataB64: String,
        view: String = "forever"
    ): String = json.encodeToString(
        FileChunkJson.serializer(),
        FileChunkJson(WIRE_VERSION, KIND, fid, sanitizeName(name), mime, size, sha256, index, total, dataB64, view)
    )

    /**
     * Décodage d'un corps déchiffré : renvoie le fragment si VALIDE, sinon
     * null (ce n'est PAS un fragment — traiter comme un message texte).
     */
    fun parse(plain: String): FileChunk? {
        val dto = runCatching { json.decodeFromString(FileChunkJson.serializer(), plain) }
            .getOrNull() ?: return null
        if (dto.v != WIRE_VERSION || dto.kind != KIND) return null
        if (!Regex("^[0-9a-f]{32}$").matches(dto.fid)) return null
        if (!Regex("^[0-9a-f]{64}$").matches(dto.sha256)) return null
        if (dto.n < 1 || dto.n > MAX_CHUNKS) return null
        if (dto.i < 0 || dto.i >= dto.n) return null
        if (dto.size < 1 || dto.size > MAX_FILE_SIZE) return null
        val name = sanitizeName(dto.name)
        if (name.isEmpty()) return null
        if (dto.data.length > (CHUNK_PAYLOAD / 3) * 4 + 4) return null // base64 borné
        // java.util.Base64 : disponible dès l'API 26 (minSdk) — testable en JVM.
        val data = runCatching {
            java.util.Base64.getDecoder().decode(dto.data)
        }.getOrNull() ?: return null
        if (data.size > CHUNK_PAYLOAD) return null
        if (dto.i == dto.n - 1 && data.isEmpty()) return null
        // v0.2.28 — politique d'affichage normalisée (valeur inconnue = forever).
        val view = when (dto.view) {
            "once", "twice" -> dto.view
            else -> "forever"
        }
        return FileChunk(dto.fid, name, dto.mime.ifBlank { "application/octet-stream" },
            dto.size, dto.sha256, dto.i, dto.n, data, view)
    }

    /** Découpe un fichier en fragments binaires (base64 par la suite). */
    fun split(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(CHUNK_PAYLOAD, bytes.size - off)
            out.add(bytes.copyOfRange(off, off + len))
            off += len
        }
        return out
    }

    /**
     * Nom sûr : séparateurs de chemin neutralisés (un « ../../x » ne doit
     * jamais sortir du dossier média local), taille bornée à 255.
     */
    fun sanitizeName(raw: String): String {
        val cleaned = raw.replace('/', '_').replace('\\', '_')
            .replace("\u0000", "").trim().take(255)
        return cleaned
    }

    /** Libellé humain d'une taille en octets (affichage bulle/liste — locale FR figée). */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(java.util.Locale.FRENCH, "%.1f Mo", bytes.toDouble() / (1 shl 20))
        bytes >= 1 shl 10 -> "%d Ko".format(bytes / (1 shl 10))
        else -> "$bytes o"
    }
}
