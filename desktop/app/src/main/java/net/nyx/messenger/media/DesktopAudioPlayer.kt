package net.nyx.messenger.media

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * v0.3.4 « parité Android » — lecteur de messages vocaux (miroir AudioPlayer
 * Android). javax.sound décode nativement WAV/AU/AIFF ; les conteneurs AAC
 * (.m4a des vocaux Android) ne sont pas décodables par le JVM → l'appelant
 * replie sur le lecteur système (« Ouvrir »). Zéro dépendance ajoutée.
 */
object DesktopAudioPlayer {

    @Volatile private var current: Clip? = null
    @Volatile private var currentFile: File? = null

    /** Lance la lecture ; true si le conteneur est décodable, false = repli système. */
    fun play(f: File): Boolean {
        stop()
        return runCatching {
            val stream = AudioSystem.getAudioInputStream(f)
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.start()
            current = clip
            currentFile = f
            true
        }.getOrElse { stop(); false }
    }

    fun stop() {
        runCatching { current?.stop() }
        runCatching { current?.close() }
        current = null
        currentFile = null
    }

    fun isPlaying(f: File): Boolean = currentFile?.absolutePath == f.absolutePath
}
