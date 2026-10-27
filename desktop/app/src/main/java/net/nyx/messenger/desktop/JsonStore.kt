package net.nyx.messenger.desktop

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Stockage JSON atomique desktop (spec §2) — remplace Room/SQLCipher côté
 * desktop : un fichier par agrégat, écriture tmp+rename (crash-safe), verrou
 * par appel (les magasins applicatients sérialisent leurs mutations au-dessus).
 */
object JsonStore {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /** Lecteurs tolérants : fichier absent/classe obsolète → null (et non un crash). */
    fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    /** Écriture atomique : tmp dans le MÊME répertoire (rename atomique) puis perms restrictives. */
    fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        // 0600 quand le système de fichiers le supporte (POSIX) ; Windows : ACL
        // profil utilisateur par défaut (répertoire privé).
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
