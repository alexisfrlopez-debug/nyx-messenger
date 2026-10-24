package net.nyx.messenger.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.50 — DÉCISIONS DE ROUTAGE JOURNAL (DepositRouting) : vecteurs purs du
 * terrain 24/10 11:47 (« appels ne sonnent plus »). Les onions RÉELS de la
 * flotte de test : kxcw… = Blackview, vxb5… = Xiaomi, 5myv… = flotte.
 */
class DepositRoutingTest {

    private val blackview = "kxcwahchrto32cp3annifd5ndflgw4z4qalnhtprehrjoqy6774qklid.onion"
    private val xiaomi = "vxb5tsk44ikklazo2spqpng33w4wjfwewcylnw4kiheit25u6ive7zyd.onion"
    private val fleet = "5myvqkcmmigkibiuy7glzpkx4rt3ewqdzx4uydnbsrp65b23mc2jisqd.onion"

    /** Le carnet du Xiaomi : la Blackview est contact, la flotte non. */
    private val xiaomiCarnet = setOf(blackview)

    private fun isPeerPhoneFor(carnet: Set<String>): (String) -> Boolean = { addr ->
        val key = LinkFallback.hostKey(addr)
        carnet.any { c -> LinkFallback.hostKey(c) == key }
    }

    @Test
    fun `incident 24-10 - le poll ne lit PLUS le journal du pair`() = runBlocking {
        // Liste du Xiaomi ce jour-là : [onion:80 de la Blackview, flotte:80].
        val replicas = listOf("$blackview:80", "$fleet:80")
        // v0.2.49 (le bug) : l'index 0 = la Blackview → poll sur SON journal.
        // v0.2.50 : le poll ne retient QUE la flotte.
        assertEquals(
            listOf("$fleet:80"),
            DepositRouting.pollTargets(replicas, isPeerPhoneFor(xiaomiCarnet))
        )
    }

    @Test
    fun `incident 24-10 - le dépôt cible la boîte du pair`() = runBlocking {
        // La Blackview appelle le Xiaomi : cible = le réplica DU Xiaomi
        // (sa boîte — il la lit en procédé), PAS la flotte au hasard d'index.
        val replicas = listOf("$xiaomi:80", "$fleet:80")
        assertEquals(
            "$xiaomi:80",
            DepositRouting.depositTarget(xiaomi, replicas, isPeerPhoneFor(setOf(xiaomi)))
        )
    }

    @Test
    fun `boîte du pair absente du carnet - repli flotte`() = runBlocking {
        // Le pair n'a jamais annoncé son réplica : rendez-vous flotte (que le
        // pair lit aussi — jamais le journal d'un tiers).
        val replicas = listOf("$fleet:80", "$blackview:80")
        assertEquals(
            "$fleet:80",
            DepositRouting.depositTarget(xiaomi, replicas, isPeerPhoneFor(xiaomiCarnet))
        )
    }

    @Test
    fun `trois pairs sans flotte connue - dernier recours premier réplica`() = runBlocking {
        // Champ dégradé : aucun réplica de flotte — le premier connu (mieux
        // que rien : le repli lien de postSigned exécutera chez le pair si
        // sa boîte est visée et injoignable).
        val replicas = listOf("$xiaomi:80", "$blackview:80")
        val carnetXiaomi = setOf(xiaomi, blackview)
        assertEquals(
            "$xiaomi:80",
            DepositRouting.depositTarget(xiaomi, replicas, isPeerPhoneFor(carnetXiaomi))
        )
    }

    @Test
    fun `aucun réplica - null, l'appel échoue proprement`() = runBlocking {
        assertEquals(null, DepositRouting.depositTarget(xiaomi, emptyList()) { _ -> false })
        assertTrue(DepositRouting.pollTargets(emptyList()) { _ -> false }.isEmpty())
    }

    @Test
    fun `formats défensifs - nu, onion et onion-80 désignent la même boîte`() = runBlocking {
        // 3 formats réels du terrain : nu 56 car. / .onion / .onion:80
        // (les constantes finissent DÉJÀ par .onion — le 3e format s'écrit
        // "<onion-de-base32>.onion:80", jamais ".onion.onion:80").
        val replicas = listOf(xiaomi, "$fleet:80", blackview)
        assertEquals(
            xiaomi,
            DepositRouting.depositTarget("$xiaomi:80", replicas, isPeerPhoneFor(setOf(xiaomi)))
        )
    }
}
