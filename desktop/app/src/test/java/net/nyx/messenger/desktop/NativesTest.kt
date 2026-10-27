package net.nyx.messenger.desktop

import com.sun.jna.NativeLibrary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Régression v0.3.1 — crash Windows v0.3.0 (retour terrain opérateur :
 * UnsatisfiedLinkError « Unable to load library 'sodium' », la dll MinGW
 * libsodium dépend de libgcc_s_seh-1.dll introuvable sur un Windows vierge).
 *
 * Sur ce bac à sable il n'existe AUCUNE libsodium.so système (seule la
 * version .23, sans lien .so) NI de ressource aux conventions JNA sur le
 * classpath de test (lazysodium groupe ses natifs sous linux64/, que JNA
 * n'inspecte pas) → la résolution par nom « sodium » ne peut aboutir QUE par
 * le répertoire extrait par Natives via jna.library.path. C'est la
 * reproduction exacte de la situation Windows du terrain.
 */
class NativesTest {

    @Test
    fun `la variante native de la plateforme est désignée`() {
        val res = Natives.platformResource()
        assertNotNull(res)
        assertTrue(
            res.startsWith("linux") || res.startsWith("windows") || res.startsWith("mac/"),
            "chemin de ressource inattendu : $res"
        )
    }

    @Test
    fun `libsodium se charge via le répertoire extrait (jna library path)`() {
        Natives.ensureSodium()

        val path = System.getProperty("jna.library.path")
        assertNotNull(path) { "jna.library.path non posé par Natives" }

        val dir = File(File(System.getProperty("java.io.tmpdir"), "nyx-natives"), "libsodium")
        val mapped = Natives.mappedLibraryName()
        assertTrue(File(dir, mapped).isFile, "fichier extrait absent : ${File(dir, mapped)}")

        // Résolution PAR NOM — ne peut PAS passer par le système (aucun
        // libsodium.so) ni par les ressources JNA (aucune entrée
        // linux-x86-64/) : seul le répertoire extrait peut satisfaire l'appel.
        val lib = NativeLibrary.getInstance("sodium")
        assertNotNull(lib)
        assertNotNull(lib.getFunction("sodium_init")) { "symbole sodium_init absent de la bibliothèque chargée" }
    }
}
