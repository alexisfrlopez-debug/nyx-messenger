package net.nyx.messenger.desktop

import java.io.File
import java.io.IOException

/**
 * Chargement fiable de la bibliothèque native libsodium EMBARQUÉE — correctif
 * v0.3.1 (retour terrain Windows v0.3.0 : UnsatisfiedLinkError « Unable to
 * load library 'sodium' » au premier lancement).
 *
 * Cause racine : la dll libsodium de lazysodium-java est une build MinGW-w64
 * qui dépend de libgcc_s_seh-1.dll (elle-même dépendante de
 * libwinpthread-1.dll) — deux dll du monde MinGW ABSENTES de tout Windows
 * vierge. Le chargeur groupé de lazysodium (PREFER_BUNDLED) extrait la dll
 * SEULE vers un répertoire temporaire : la résolution des dépendances par
 * Windows échoue (« Le module spécifié est introuvable »), le repli système
 * ne trouve rien non plus → crash au démarrage.
 *
 * Correctif — avant TOUTE initialisation JNA :
 *   1. extraire soi-même la variante libsodium de la plateforme (chemin de
 *      ressource en '/', valide sur tous les OS) vers
 *      <tmp>/nyx-natives/libsodium/ sous le nom exact attendu par JNA
 *      (sodium.dll / libsodium.so / libsodium.dylib) ;
 *   2. extraire AU MÊME ENDROIT les deux compagnes MinGW
 *      (libgcc_s_seh-1.dll, libwinpthread-1.dll — Windows seulement) :
 *      la résolution des dépendances depuis le répertoire de la dll chargée
 *      (LOAD_WITH_ALTERED_SEARCH_PATH) les y trouve ;
 *   3. déclarer ce répertoire via jna.library.path (exploré EN PREMIER par
 *      JNA lors de la résolution par nom) → le repli « système » de
 *      LazySodium charge NOTRE copie extraite.
 *
 * En outre, l'installateur copie les deux compagnes dans runtime\bin
 * (répertoire de java.exe — l'autre ordre de recherche des dépendances),
 * et le fat jar expose aussi les natifs aux conventions de ressources JNA
 * (win32-x86-64/sodium.dll…) en filet de sécurité.
 *
 * CONTRAINTE : aucune classe com.sun.jna n'est référencée ici —
 * jna.library.path doit être posé AVANT la première initialisation JNA.
 */
object Natives {

    @Volatile private var done = false

    /**
     * Idempotent — appelé par [net.nyx.messenger.crypto.Sodium] juste avant
     * la construction de SodiumJava. Sans effet si la plateforme n'a pas de
     * variante embarquée (repli lazysodium/JNA inchangé).
     */
    fun ensureSodium() {
        if (done) return
        synchronized(this) {
            if (done) return
            val resource = platformResource()
            if (resource == null) {
                DesktopLog.w(
                    "nyx-natives",
                    "aucune variante libsodium embarquée pour cette plateforme — chargement lazysodium/système"
                )
            } else {
                try {
                    val dir = File(File(System.getProperty("java.io.tmpdir"), "nyx-natives"), "libsodium")
                    if (!dir.isDirectory) dir.mkdirs()
                    val mapped = mappedLibraryName()
                    extract(resource, File(dir, mapped))
                    // Compagnes MinGW (Windows) dans LE MÊME répertoire que la
                    // dll : résolution des dépendances garantie.
                    if (DesktopPaths.isWindows) {
                        extract("nyx-native/libgcc_s_seh-1.dll", File(dir, "libgcc_s_seh-1.dll"))
                        extract("nyx-native/libwinpthread-1.dll", File(dir, "libwinpthread-1.dll"))
                    }
                    declareJnaLibraryPath(dir)
                    DesktopLog.i("nyx-natives", "libsodium extraite vers ${dir.absolutePath} ($resource → $mapped)")
                } catch (e: IOException) {
                    DesktopLog.w("nyx-natives", "extraction impossible (${e.message}) — repli lazysodium/JNA")
                }
            }
            done = true
        }
    }

    /** Chemin de ressource (classpath) de la variante libsodium de la plateforme. */
    internal fun platformResource(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val aarch64 = arch == "aarch64" || arch == "arm64"
        val bits64 = arch.contains("64") || aarch64
        return when {
            os.contains("windows") ->
                if (bits64) "windows64/libsodium.dll" else "windows/libsodium.dll"
            os.contains("mac") || os.contains("darwin") ->
                if (aarch64) "mac/aarch64/libsodium.dylib" else "mac/intel/libsodium.dylib"
            else -> when {
                aarch64 -> null // pas de variante linux-aarch64 dans l'artefact lazysodium
                bits64 -> "linux64/libsodium.so"
                else -> "linux/libsodium.so"
            }
        }
    }

    /** Nom de fichier recherché par JNA pour « sodium » (équivalent mapLibraryName). */
    internal fun mappedLibraryName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("windows") -> "sodium.dll"
            os.contains("mac") || os.contains("darwin") -> "libsodium.dylib"
            else -> "libsodium.so"
        }
    }

    /** Copie d'une ressource classpath vers [target] (taille identique → rien à faire). */
    internal fun extract(resource: String, target: File) {
        val bytes = Natives::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw IOException("ressource absente : $resource")
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists() && !target.delete()) throw IOException("fichier verrouillé : ${target.path}")
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("renommage impossible : ${target.path}")
        }
        target.setReadable(true, false)
        target.setExecutable(true, false)
    }

    /** Ajoute [dir] en tête de jna.library.path (idempotent). */
    private fun declareJnaLibraryPath(dir: File) {
        val abs = dir.absolutePath
        val current = System.getProperty("jna.library.path")
        if (current.isNullOrBlank()) {
            System.setProperty("jna.library.path", abs)
        } else if (current.split(File.pathSeparator).none { it == abs }) {
            System.setProperty("jna.library.path", abs + File.pathSeparator + current)
        }
    }
}
