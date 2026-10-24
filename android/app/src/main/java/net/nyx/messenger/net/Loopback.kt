package net.nyx.messenger.net

import java.net.InetAddress
import java.net.ServerSocket

/**
 * v0.2.47 — BIND LOOPBACK IPv4 EXPLICITE (cause racine des sessions 41 → 68,
 * PROUVÉE par le source AOSP + les logs terrain du 24/10).
 *
 * **LE PIÈGE ANDROID** : `InetAddress.getLoopbackAddress()` ne retourne PAS
 * 127.0.0.1 sur Android — le code source AOSP (libcore/ojluni
 * java/net/InetAddress.java) le dit noir sur blanc :
 *
 * ```java
 * public static InetAddress getLoopbackAddress() {
 *     // Android-changed: Always returns IPv6 loopback address in Android.
 *     return impl.loopbackAddresses()[0];   // ← ::1, JAMAIS 127.0.0.1
 * }
 * ```
 *
 * Sur un JVM desktop (Linux/macOS/Windows — le bac à sable des sessions
 * précédentes), la même méthode retourne 127.0.0.1 : c'est pourquoi les
 * tests et le rejeu sandbox (tor_deadbackend.py) ont TOUJOURS marché alors
 * que les téléphones n'ont JAMAIS accepté une seule connexion entrante.
 *
 * **LA CONSÉQUENCE TERRAIN** (logs 23-24/10, v0.2.45, les DEUX téléphones) :
 * les écouteurs (réplica embarqué port 80 onion + média :47471) bindaient
 * [::1]:<port> alors que l'ADD_ONION publie `Port=…,127.0.0.1:<port>` —
 * chaque flux entrant de tor recevait « Connection refused » (errno 115 →
 * END misc → « SOCKS server general failure » chez tous les pairs), les
 * chiens de garde jugeaient le backend mort à CHAQUE sonde (45 s puis 60 s)
 * et le relançaient sur un port neuf → republication → churn de descripteur
 * en boucle toute la nuit (« écouteur interrompu : Socket closed » ×45 dans
 * un seul export). C'est la cause première de « oignon entrant injoignable »
 * (console) ET du « décrochage impossible » (le média exige l'onion du pair).
 *
 * **LA RÈGLE** : tout écouteur local destiné à un mapping ADD_ONION DOIT
 * binder [cette adresse] — NE JAMAIS utiliser `getLoopbackAddress()` sur ce
 * dépôt (ajoutez un test à [LoopbackBindTest] si un nouvel écouteur naît).
 */
object Loopback {

    /** Adresse de bind des écouteurs locaux mappés sur l'onion — IPv4 explicite. */
    val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1")

    /**
     * Ouvre un ServerSocket loopback IPv4 (port éphémère, backlog donné) —
     * le seul constructeur autorisé pour les backends d'onion. Littéral IP :
     * aucune résolution DNS, aucun blocage possible.
     */
    fun serverSocket(backlog: Int = 16): ServerSocket =
        ServerSocket(0, backlog, bindAddress)
}
