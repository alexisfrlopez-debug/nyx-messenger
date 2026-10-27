package net.nyx.messenger.diag

import net.nyx.messenger.desktop.DesktopLog
import net.nyx.messenger.desktop.DesktopPaths
import net.nyx.messenger.desktop.ServiceLocator
import net.nyx.messenger.desktop.ServiceLocator.TorPhase

/**
 * v0.3.3 (session 73) — PORTAGE DESKTOP de l'Android `diag/LogCollector.kt`
 * (v0.2.5/v0.2.33) : collecte de diagnostic du bouton rouge « ENVOYER LES
 * LOGS » (miroir du build test Android — demande opérateur).
 *
 * Contenu collecté (aucune donnée de conversation, aucune clé) :
 *  - en-tête : version, OS, version Java, horodatage UTC, état Tor + ports +
 *    onion publié, réplicas actifs/découverts/rendezvous, réplique
 *    embarquée, binaire tor résolu (chemin + existe) ;
 *  - la FIN de tor.log (progression RÉELLE du bootstrap — leçon Android
 *    v0.2.5 : un Tor bloqué à 0 % est indémontrable sans CE fichier) ;
 *  - la queue de la console du démon tor v0.3.3 (dll manquante, antivirus,
 *    verrou DataDirectory…) ;
 *  - le JOURNAL APPLICATIF persistant v0.3.3 (survit aux redémarrages —
 *    leçon Android v0.2.33 : les événements d'avant le crash comptent).
 *
 * La base de données (messages, contacts) n'est JAMAIS lue ici : le fichier
 * produit ne contient que des journaux système. Il est déposé par
 * [DriveUploader] dans le dossier Drive dédié au débogage (logs-terrain).
 */
object LogCollector {

    fun collect(): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("=== NYX DESKTOP EXPORT LOGS (build test) ===\n")
        sb.append("version  : ").append(ServiceLocator.VERSION).append(" (desktop)\n")
        sb.append("os       : ").append(osDescription()).append("\n")
        sb.append("java     : ").append(System.getProperty("java.version"))
            .append(" (").append(System.getProperty("java.vendor")).append(")\n")
        sb.append("date     : ").append(java.time.Instant.now()).append("\n")

        // — état Tor vivant —
        runCatching {
            val st = ServiceLocator.torState.value
            sb.append("tor      : phase=").append(st.phase)
                .append(" percent=").append(st.percent)
                .append(" onion=").append(st.onion ?: "(aucun)").append("\n")
            if (st.phase == TorPhase.FAILED) {
                sb.append("tor msg  : ").append(st.message ?: "?").append("\n")
            }
        }.onFailure { sb.append("tor      : (état indisponible)\n") }

        // — binaire tor résolu (antivirus ? install cassé ?) —
        runCatching {
            val bin = DesktopPaths.resolveTorBinary()
            sb.append("tor bin  : ")
                .append(bin?.absolutePath ?: "(INTROUVABLE — tor.exe supprimé/bloqué ?)")
                .append(bin?.let { if (it.isFile) " (présent)" else " (CHEMIN INVALIDE)" } ?: "")
                .append("\n")
        }

        // — réplicas (miroir leçon Android v0.2.7 : la VÉRITÉ = liste ACTIVE) —
        runCatching {
            val active = ServiceLocator.repository.replicas
            sb.append("réplicas actifs    : ")
                .append(if (active.isEmpty()) "(AUCUN — sonde impossible)" else active.joinToString(", "))
                .append("\n")
            val discovered = net.nyx.messenger.net.ReplicaRegistry.list()
            sb.append("réplicas découverts: ")
                .append(if (discovered.isEmpty()) "(aucun)" else discovered.joinToString(", "))
                .append("\n")
            val custom = ServiceLocator.settings.get().customReplica.trim()
            sb.append("réplica personnalisé: ").append(custom.ifBlank { "(aucun)" }).append("\n")
            sb.append("rendezvous signé   : ")
                .append(rendezvousCount())
                .append(" entrée(s) embarquée(s)\n")
        }.onFailure { sb.append("réplicas : (état indisponible)\n") }

        sb.append("réplique embarquée : ")
            .append(if (net.nyx.messenger.replica.EmbeddedReplica.alive())
                "EN LIGNE" else "inactive").append("\n")
        sb.append("données  : ").append(DesktopPaths.dataDir.absolutePath).append("\n")

        // — sections de journaux —
        sb.append("\n===== TOR.LOG (fin — progression réelle du bootstrap) =====\n")
        sb.append(runCatching { ServiceLocator.tor.torLogTail() }.getOrDefault("(indisponible)"))
        sb.append("\n\n===== TOR CONSOLE (queue stdout/stderr du démon — v0.3.3) =====\n")
        sb.append(runCatching { ServiceLocator.tor.stdoutTail(80) }.getOrDefault("(indisponible)"))
        sb.append("\n\n===== JOURNAL PERSISTANT (survit aux redémarrages — v0.3.3) =====\n")
        sb.append(DesktopLog.fileTail())
        sb.append("\n===== JOURNAL MEMOIRE (session courante) =====\n")
        sb.append(DesktopLog.dump())
        return sb.toString()
    }

    /** Nom de fichier normalisé : nyx-logs-desktop-v<version>-<stamp>.txt (miroir Android). */
    fun fileName(): String {
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "nyx-logs-desktop-v${ServiceLocator.VERSION}-$stamp.txt"
    }

    private fun osDescription(): String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")})"

    private fun rendezvousCount(): Int = runCatching {
        val raw = LogCollector::class.java.getResourceAsStream("/rendezvous.json")?.readBytes()
            ?: return 0
        val sig = LogCollector::class.java.getResourceAsStream("/rendezvous.json.sig")?.readBytes()
            ?: return 0
        net.nyx.messenger.net.RendezvousList.load(raw, sig, ServiceLocator.RENDEZVOUS_PUBKEY)
            ?.entries?.size ?: 0
    }.getOrDefault(0)
}
