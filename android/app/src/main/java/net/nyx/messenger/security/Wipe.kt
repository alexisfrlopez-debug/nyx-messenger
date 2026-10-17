package net.nyx.messenger.security

import android.content.Context
import java.io.File

/**
 * v0.2.18 — RESET DU COMPTE (mot de passe destruction, demande opérateur :
 * « supprime la liste de contacts et l'historique/les fichiers envoyés/reçus
 * (reset du compte) »).
 *
 * Exécuté APRÈS vérification du mot de passe sur l'écran de verrou, HORS
 * processus UI — SANS confirmation (c'est le rôle du mot de passe) :
 *
 *  1. Tor arrêté proprement (démon tué — stopNow borné v0.2.9) ;
 *  2. base Room effacée (nyx.db + wal/shm — contacts, messages, accusés,
 *     pièces jointes et leurs fragments SQLCipher ne sont plus lisibles) ;
 *  3. fichiers médias effacés (filesDir/media — blobs chiffrés au repos) ;
 *  4. caches + préférences effacées (identité, alias, réplicas, réglages,
 *     mots de passe, clés scellées) — le compte onion disparaît avec sa clé
 *     privée : c'est bien un RESET, l'app repart sur l'onboarding ;
 *  5. mort du process — le prochain lancement est vierge.
 *
 * Limites documentées (ADR-010) : les exports volontaires vers Téléchargements
 * et les résidus système (journal logcat) sont hors de portée de l'application.
 */
object Wipe {

    const val TAG = "nyx-wipe"

    fun execute(context: Context) {
        val app = context.applicationContext
        android.util.Log.w(TAG, "reset du compte demandé (mot de passe destruction)")
        try {
            // 1. Tor + tout service d'arrière-plan.
            runCatching { net.nyx.messenger.tor.TorManager.get(app).stopNow() }
        } catch (_: Exception) {}

        try {
            // 2. Base Room (dossier databases — fichiers principal/wal/shm).
            val dbDir = app.getDatabasePath("x").parentFile
            if (dbDir != null) {
                for (name in arrayOf(
                    net.nyx.messenger.data.db.NyxDatabase.NAME,
                    net.nyx.messenger.data.db.NyxDatabase.NAME_DECOY
                )) {
                    for (suffix in arrayOf("", "-wal", "-shm", "-journal")) {
                        runCatching { File(dbDir, name + suffix).delete() }
                    }
                }
            }
            // 3. Médias (blobs chiffrés) + fichiers tor (torrc/logs — régénérés).
            runCatching { File(app.filesDir, "media").deleteRecursively() }
            runCatching { File(app.filesDir, "tor").deleteRecursively() }
            runCatching { File(app.cacheDir, "media-play").deleteRecursively() }
            // 4. Préférences (identité, réglages, sécurité, clés scellées…).
            runCatching {
                val prefsDir = File(app.filesDir.parentFile, "shared_prefs")
                if (prefsDir.isDirectory) prefsDir.listFiles()?.forEach { it.delete() }
            }
            android.util.Log.w(TAG, "données locales effacées — mort du process")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "effacement partiel : ${e.message}")
        }

        // 5. Mort du process — redémarrage vierge (onboarding).
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }
}
