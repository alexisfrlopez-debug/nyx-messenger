package net.nyx.messenger.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de [BootstrapReplicas] — le parseur des réplicas d'amorçage embarqués.
 *
 * Cas n°2 = LA régression v0.2.6 : l'adresse embarquée avec son port
 * (« ….onion:80 ») était rejetée par le filtre « endsWith(".onion") » →
 * liste vide → sonde réplica jamais lancée → écran Connexions bloqué.
 */
class BootstrapReplicasTest {

    private val onion = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"

    @Test
    fun `adresse nue acceptée`() {
        assertEquals(listOf(onion), BootstrapReplicas.parse(onion))
    }

    @Test
    fun `adresse avec port acceptée — régression v0_2_6`() {
        // Le port est CONSERVÉ (un réplica peut écouter ailleurs que sur 80 ;
        // RelayClient construit l'URL de base avec l'entrée telle quelle).
        assertEquals(listOf("$onion:80"), BootstrapReplicas.parse("$onion:80"))
    }

    @Test
    fun `port non standard accepté`() {
        assertEquals(listOf("$onion:8443"), BootstrapReplicas.parse("$onion:8443"))
    }

    @Test
    fun `liste séparée par virgules`() {
        val other = "a".repeat(56) + ".onion"
        assertEquals(listOf(onion, other), BootstrapReplicas.parse("$onion,$other"))
    }

    @Test
    fun `séparateurs mixtes — espaces point-virgules retours ligne`() {
        val other = "b".repeat(56) + ".onion"
        assertEquals(
            listOf(onion, other),
            BootstrapReplicas.parse(" $onion ;\n$other\t;  ")
        )
    }

    @Test
    fun `casse normalisée et doublons éliminés`() {
        assertEquals(
            listOf("$onion:80"),
            BootstrapReplicas.parse("${onion.uppercase()}:80, $onion:80")
        )
    }

    @Test
    fun `formes avec et sans port coexistent`() {
        assertEquals(
            listOf("$onion:80", onion),
            BootstrapReplicas.parse("$onion:80, $onion")
        )
    }

    @Test
    fun `chaîne vide → liste vide`() {
        assertEquals(emptyList<String>(), BootstrapReplicas.parse(""))
    }

    @Test
    fun `ordures écartées — identifiants tronqués ou domaines étrangers`() {
        assertEquals(
            emptyList<String>(),
            BootstrapReplicas.parse("foo bar 5myvqk.onion example.com:80 ${onion.dropLast(2)}")
        )
    }

    @Test
    fun `port non numérique rejeté`() {
        assertEquals(emptyList<String>(), BootstrapReplicas.parse("$onion:quatre-vingts"))
    }
}
