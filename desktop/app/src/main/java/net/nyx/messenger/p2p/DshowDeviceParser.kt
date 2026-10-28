package net.nyx.messenger.p2p

/**
 * v0.3.12 — ANALYSE DE LA SORTIE « ffmpeg -list_devices true -f dshow -i dummy ».
 *
 * CAUSE RACINE TERRAIN 28/10 21:16 (export bureau v0.3.11) : le ffmpeg 7.1
 * EMBARQUÉ (gyan.dev) liste les périphériques au NOUVEAU format d'entrée —
 *
 *   [in#0 @ 0000025a5116cdc0] "Integrated Camera" (video)
 *   [in#0 @ 0000025a5116cdc0]   Alternative name "@device_pnp_\\?\usb#vid_04f2&pid_b681&mi_00#6&2ae55746&0&0000#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\global"
 *   [in#0 @ 0000025a5116cdc0] "Microphone (Jabra SPEAK 510 USB)" (audio)
 *
 * — alors que l'ancien parseur n'acceptait que « [dshow @ …] "Nom" » : la
 * webcam ÉTAIT listée (« Integrated Camera » visible dans la sortie brute
 * journalisée) mais « aucune webcam dshow détectée — repli écran » la
 * déclarait absente → LE PC ENVOYAIT SON ÉCRAN au lieu de la caméra
 * (retour session 80 toujours vivant). Le format ANCIEN reste accepté :
 *
 *   [dshow @ 0000…] "Integrated Camera" (video)
 *   [dshow @ 0000…]   Alternative name "@device_pnp_…"
 *
 * PRÉFÉRENCE DE NOM : le « Alternative name » (chemin unique @device_pnp… /
 * @device_cm…) est retenu EN PREMIER — insensible aux homonymes de nom
 * convivial et aux caractères spéciaux du « video= » de dshow ; le nom
 * convivial reste candidat de repli.
 */
object DshowDeviceParser {

    /** Un périphérique vidéo dshow : nom convivial + chemin unique (si exposé). */
    data class DshowDevice(val friendly: String, val alternative: String?)

    /**
     * Ligne « "Nom" (video) » — préfixe [dshow @ …] (ffmpeg ≤ 6) OU [in#N @ …]
     * (ffmpeg ≥ 7) ; le suffixe « (video) » tranche les entrées multimode.
     */
    private val NAME_REGEX = Regex("\\[(?:dshow|in#\\d+)[^\\]]*\\]\\s+\"([^\"]+)\"")

    /** Ligne « Alternative name "@device_…" » (valeur ENTRE GUILLIMETS). */
    private val ALT_REGEX = Regex("Alternative name\\s+\"([^\"]+)\"")

    /** En-tête de section historique (ffmpeg ≤ 6, parfois absent en 7). */
    private const val SECTION_VIDEO = "DirectShow video devices"
    private const val SECTION_AUDIO = "DirectShow audio devices"

    /**
     * Extrait les périphériques VIDÉO dans l'ordre d'apparition :
     *  1. analyse par section « DirectShow video devices » (format historique) ;
     *  2. repli TOUTE ligne « "Nom" (video) » sans exiger l'en-tête (ffmpeg 7) ;
     *  3. associe chaque ligne à l'« Alternative name » qui la SUIT immédiatement.
     * Les périphériques audio (« (audio) ») ne sont JAMAIS retenus.
     */
    fun parseDevices(err: String): List<DshowDevice> {
        val out = ArrayList<DshowDevice>()
        var inVideoSection = false
        var sawSectionHeader = false
        var friendly: String? = null
        var alternative: String? = null

        fun flush() {
            val f = friendly
            if (f != null && f.isNotBlank()) out.add(DshowDevice(f, alternative))
            friendly = null
            alternative = null
        }

        for (raw in err.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.contains(SECTION_VIDEO)) {
                flush()
                inVideoSection = true
                sawSectionHeader = true
                continue
            }
            if (line.contains(SECTION_AUDIO)) {
                flush()
                inVideoSection = false
                continue
            }
            val alt = ALT_REGEX.find(line)?.groupValues?.get(1)?.trim()
            if (alt != null) {
                // Chemin unique de la ligne « "Nom" » précédente.
                if (friendly != null && alternative == null) alternative = alt
                continue
            }
            val m = NAME_REGEX.find(line) ?: continue
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val isVideo = line.contains("(video)") ||
                (inVideoSection && !line.contains("(audio)"))
            flush()
            if (isVideo) {
                friendly = name
                alternative = null
            }
        }
        flush()
        return out
    }

    /**
     * Candidats d'ouverture pour « -i video=… », dans l'ordre de préférence :
     * chemin unique d'abord, nom convivial ensuite (homonymes, repli honnête).
     */
    fun candidates(devices: List<DshowDevice>): List<String> {
        val out = ArrayList<String>(devices.size * 2)
        for (d in devices) {
            d.alternative?.let { if (it !in out) out.add(it) }
            if (d.friendly !in out) out.add(d.friendly)
        }
        return out
    }
}
