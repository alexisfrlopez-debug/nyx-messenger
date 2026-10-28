package net.nyx.messenger.desktop

import java.io.File

/**
 * Résolution du répertoire de données desktop (spec desktop-v1.md §2) :
 *   Windows : %USERPROFILE%\.nyx-messenger
 *   autres   : ~/.nyx-messenger (dev/test Linux du bac à sable)
 * Override : -Dnyx.data.dir=/chemin (tests + développement).
 * Aucun fichier applicatif ne sort de ce répertoire (équivalent
 * allowBackup=false — règle §9.2.7 adaptée desktop).
 */
object DesktopPaths {
    /** Résolution PARESSEUSE à chaque accès (les tests redirigent le répertoire entre scénarios). */
    val dataDir: File
        get() = File(
            System.getProperty("nyx.data.dir")
                ?: File(System.getProperty("user.home"), ".nyx-messenger").absolutePath
        )

    val storeDir: File get() = File(dataDir, "store")
    val mediaDir: File get() = File(storeDir, "media")
    val replicaDir: File get() = File(dataDir, "replica")
    val torDir: File get() = File(dataDir, "tor")

    fun ensure() {
        dataDir.mkdirs()
        storeDir.mkdirs()
        mediaDir.mkdirs()
        replicaDir.mkdirs()
        torDir.mkdirs()
    }

    /** Emplacement de tor.exe : <cwd>/tor/tor.exe puis tor/ à côté du jar. */
    fun resolveTorBinary(): File? {
        val candidates = ArrayList<File>()
        // 1. -Dnyx.tor.bin=... (installateur / dev)
        System.getProperty("nyx.tor.bin")?.let { candidates.add(File(it)) }
        // 2. tor/ à côté du répertoire de travail (zip portable : tout est à plat)
        candidates.add(File("tor", if (isWindows) "tor.exe" else "tor"))
        // 3. tor/ à côté du jar (installateur : %LOCALAPPDATA%\NyxMessenger)
        runCatching {
            File(DesktopPaths::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()?.let { jarDir ->
            candidates.add(File(jarDir, "tor/${if (isWindows) "tor.exe" else "tor"}"))
            candidates.add(File(jarDir.parentFile, "tor/${if (isWindows) "tor.exe" else "tor"}"))
        }
        for (c in candidates) if (c.isFile && c.canExecute()) return c
        return null
    }

    /**
     * v0.3.10 — Emplacement de ffmpeg.exe (CAMÉRA PAR DÉFAUT en visio,
     * demande opérateur session 80) : miroir exact de [resolveTorBinary] —
     * -Dnyx.ffmpeg.bin, puis ffmpeg/ à côté du répertoire de travail (zip
     * portable), puis ffmpeg/ à côté du jar (installateur). La capture
     * dshow ne se lance QUE sur Windows ; ailleurs (dev/test) la résolution
     * rend naturellement null et la visio replie sur l'écran.
     */
    fun resolveFfmpeg(): File? {
        val candidates = ArrayList<File>()
        // 1. -Dnyx.ffmpeg.bin=... (tests / dev)
        System.getProperty("nyx.ffmpeg.bin")?.let { candidates.add(File(it)) }
        val exe = if (isWindows) "ffmpeg.exe" else "ffmpeg"
        // 2. ffmpeg/ à côté du répertoire de travail (zip portable à plat).
        candidates.add(File("ffmpeg", exe))
        // 3. ffmpeg/ à côté du jar (installateur : %LOCALAPPDATA%\NyxMessenger).
        runCatching {
            File(DesktopPaths::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()?.let { jarDir ->
            candidates.add(File(jarDir, "ffmpeg/$exe"))
            candidates.add(File(jarDir.parentFile, "ffmpeg/$exe"))
        }
        for (c in candidates) if (c.isFile) return c
        return null
    }

    val isWindows: Boolean =
        System.getProperty("os.name").lowercase().contains("windows")
}
