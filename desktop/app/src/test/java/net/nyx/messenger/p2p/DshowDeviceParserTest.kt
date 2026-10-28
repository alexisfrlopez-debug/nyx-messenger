package net.nyx.messenger.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.3.12 — TESTS DU PARSEUR DSHOW — LA CAUSE RACINE TERRAIN 28/10 21:16
 * (export bureau v0.3.11) : le ffmpeg 7.1 EMBARQUÉ liste la webcam au format
 * « [in#0 @ …] "Integrated Camera" (video) » — l'ancien parseur n'acceptait
 * que « [dshow @ …] » et déclarait la caméra ABSENTE alors qu'elle figurait
 * NOIR SUR BLANC dans la sortie brute journalisée → le PC envoyait son ÉCRAN
 * (« le pc fait un partage d'écran plutot que d'envoyer le flux caméra »).
 * Le premier vecteur ci-dessous est la sortie BRUTE du terrain (verbatim,
 * tronquée aux périphériques pertinents).
 */
class DshowDeviceParserTest {

    /** Sortie ffmpeg 7.1 (gyan.dev embarqué) — terrain 28/10, verbatim. */
    private val ffmpeg71 =
        "[in#0 @ 0000025a5116cdc0] \"Integrated Camera\" (video)\n" +
            "[in#0 @ 0000025a5116cdc0]   Alternative name \"@device_pnp_\\\\?\\usb#vid_04f2&pid_b681&mi_00#6&2ae55746&0&0000#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\\global\"\n" +
            "[in#0 @ 0000025a5116cdc0] \"Microphone (Jabra SPEAK 510 USB)\" (audio)\n" +
            "[in#0 @ 0000025a5116cdc0]   Alternative name \"@device_cm_{33D9A762-90C8-11D0-BD43-00A0C911CE86}\\wave_{E02E9DC3-0F5B-423D-80F7-BAD02A5A123A}\"\n" +
            "[in#0 @ 0000025a5116cdc0] \"CABLE Output (VB-Audio Virtual Cable)\" (audio)\n"

    /** Sortie ffmpeg ≤ 6 (format historique avec sections). */
    private val ffmpegLegacy =
        "[dshow @ 0000012345] DirectShow video devices (some may be both video and audio devices)\n" +
            "[dshow @ 0000012345]  \"Integrated Webcam\"\n" +
            "[dshow @ 0000012345]     Alternative name \"@device_pnp_\\\\?\\usb#vid_1&pid_2#3#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\\global\"\n" +
            "[dshow @ 0000012345] DirectShow audio devices\n" +
            "[dshow @ 0000012345]  \"Microphone Array\"\n"

    @Test
    fun `ffmpeg 7 - webcam video avec chemin unique, micro audio exclus`() {
        val devices = DshowDeviceParser.parseDevices(ffmpeg71)
        assertEquals(1, devices.size, "UNE seule entrée VIDÉO (le micro Jabra est (audio))")
        val cam = devices.first()
        assertEquals("Integrated Camera", cam.friendly)
        assertTrue(
            cam.alternative!!.startsWith("@device_pnp_"),
            "le chemin unique du terrain doit être capturé : ${cam.alternative}"
        )
        assertTrue(cam.alternative.contains("vid_04f2&pid_b681"))
    }

    @Test
    fun `candidats - chemin unique AVANT le nom convivial`() {
        val c = DshowDeviceParser.candidates(DshowDeviceParser.parseDevices(ffmpeg71))
        assertEquals(2, c.size)
        assertEquals("@device_pnp_\\\\?\\usb#vid_04f2&pid_b681&mi_00#6&2ae55746&0&0000#{65e8773d-8f56-11d0-a3b9-00a0c9223196}\\global", c[0])
        assertEquals("Integrated Camera", c[1])
    }

    @Test
    fun `format historique ffmpeg 6 - section video respectee`() {
        val devices = DshowDeviceParser.parseDevices(ffmpegLegacy)
        assertEquals(1, devices.size)
        assertEquals("Integrated Webcam", devices.first().friendly)
        assertTrue(devices.first().alternative!!.contains("@device_pnp_"))
    }

    @Test
    fun `sortie vide ou sans video - liste vide sans crash`() {
        assertTrue(DshowDeviceParser.parseDevices("").isEmpty())
        assertTrue(
            DshowDeviceParser.parseDevices("[in#0 @ 1] \"Micro (Jabra)\" (audio)\n").isEmpty()
        )
        assertTrue(DshowDeviceParser.parseDevices("n'importe quoi\nsans crochets").isEmpty())
    }

    @Test
    fun `webcam sans ligne alternative - nom convivial seul`() {
        val out = "[in#0 @ 42] \"Ma Webcam\" (video)\n"
        val devices = DshowDeviceParser.parseDevices(out)
        assertEquals(1, devices.size)
        assertEquals("Ma Webcam", devices.first().friendly)
        assertEquals(null, devices.first().alternative)
        assertEquals(listOf("Ma Webcam"), DshowDeviceParser.candidates(devices))
    }

    @Test
    fun `deux webcams - ordre d apparition preserve, alternatives appariees`() {
        val out = "[in#0 @ 1] \"Cam A\" (video)\n" +
            "[in#0 @ 1]   Alternative name \"@device_pnp_AAA\"\n" +
            "[in#1 @ 2] \"Cam B\" (video)\n" +
            "[in#1 @ 2]   Alternative name \"@device_pnp_BBB\"\n"
        val c = DshowDeviceParser.candidates(DshowDeviceParser.parseDevices(out))
        assertEquals(listOf("@device_pnp_AAA", "Cam A", "@device_pnp_BBB", "Cam B"), c)
    }
}
