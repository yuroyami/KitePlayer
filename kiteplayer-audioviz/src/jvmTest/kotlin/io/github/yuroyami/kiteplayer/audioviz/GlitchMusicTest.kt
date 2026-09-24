package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Glitch against the real analyser and its event delivery. */
class GlitchMusicTest {
    init { useSkiaGraphics() }

    @Test
    fun theDrumsBreakThePictureAndItSnapsBackToCleanBetweenHits() {
        val seconds = 10f
        val player = SongPlayer(SyntheticSong.drumLoop(seconds + 4f))
        val drawing = Glitch()
        var split = 0
        var sliced = 0
        var melted = 0
        var mostMelting = 0
        val frames = (seconds * 60).toInt()
        RenderHarness.forEachFrameOf(drawing, 160, 100, frames, VizPalette.Prism, { player.next(1f / 60f) }, { player.future }) { _, _ ->
            val scene = drawing.scene
            val melting = scene.melt.count { it >= 0f }
            if (scene.split > 0f) split++
            if (scene.slices > 0) sliced++
            if (melting > 0) melted++
            mostMelting = maxOf(mostMelting, melting)
        }
        println("glitch over $frames frames: split $split, sliced $sliced, melting $melted, most bars melting $mostMelting")
        // Each fault shows and then clears: the picture is broken by the drums, not broken for good.
        assertTrue(split in 1..frames * 6 / 10, "a kick splits the colour layers and they rejoin: $split of $frames frames")
        assertTrue(sliced in 1..frames * 3 / 10, "a snare tears slices and they return: $sliced of $frames frames")
        assertTrue(melted in 1..frames * 9 / 10, "a hat melts bars and they recover: $melted of $frames frames")
        assertTrue(mostMelting <= 16, "only the brightest bars melt, at most $mostMelting at once")
    }

    /**
     * Lists every fault in real songs and pictures the first of each kind, so a viewer can see the
     * moments a still at a fixed second rarely catches: a kick's fringes and the clean edges 117 ms
     * later, a snare's tear, a melt, a channel change, the datamosh and its snap back, a breakdown.
     * `GLITCH_PCM` is a comma list of 48 kHz mono float32 files and `GLITCH_OUT` a directory. Seconds
     * are counted the way the stills tool counts them. It skips unless `GLITCH_PCM` is set.
     */
    @Test
    fun picturesTheFirstOfEachFaultInRealSongs() {
        val inputs = System.getenv("GLITCH_PCM")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        assumeTrue("Set GLITCH_PCM to 48 kHz mono f32le files to picture their faults", inputs.isNotEmpty())
        val out = File(System.getenv("GLITCH_OUT") ?: "build/glitch-preview/faults").apply { mkdirs() }
        for (input in inputs) {
            val name = File(input).nameWithoutExtension
            val samples = readMono(File(input))
            val player = SongPlayer(samples, bandCount = 48)
            val drawing = Glitch()
            val frames = ((samples.size / 48_000f - 4f) * 60f).toInt().coerceIn(0, 60 * 120)
            val lines = ArrayList<String>()
            val wanted = HashMap<Int, String>()
            val seen = HashSet<String>()
            val stills = ArrayList<Pair<String, BufferedImage>>()
            var split = 0f
            var slices = 0
            var channel = 0
            var moshing = false
            var breakdown = false
            fun once(kind: String, step: Int, vararg later: Pair<Int, String>) {
                if (step < 6 * 60 || !seen.add(kind)) return
                wanted[step] = kind
                for ((delay, caption) in later) wanted[step + delay] = caption
            }
            RenderHarness.forEachFrameOf(drawing, 480, 270, frames, VizPalette.Prism, { player.next(1f / 60f) }, { player.future }) { bitmap, step ->
                val scene = drawing.scene
                val at = "%.2f".format(step / 60f)
                val melting = scene.melt.count { it == 0f }
                if (scene.split > split) {
                    lines += "$at kick split ${"%.4f".format(scene.split)}"
                    if (scene.split >= 0.004f) once("kick", step, 7 to "kick +117 ms")
                }
                if (scene.slices > 0 && slices == 0) {
                    lines += "$at snare ${scene.slices} slices"
                    once("snare", step)
                }
                if (melting > 0) {
                    lines += "$at hat $melting bars melt"
                    once("hat", step, 4 to "hat +67 ms")
                }
                if (scene.channel != channel) {
                    lines += "$at channel ${scene.channel}"
                    if (scene.staticOn) once("channel", step, 4 to "channel +67 ms")
                }
                if (scene.moshing != moshing) {
                    lines += "$at datamosh ${if (scene.moshing) "starts" else "ends"}"
                    if (scene.moshing) once("datamosh", step, 30 to "datamosh +0.5 s") else once("snap", step)
                }
                if (scene.breakdown != breakdown) {
                    lines += "$at breakdown ${if (scene.breakdown) "starts" else "ends"}"
                    if (scene.breakdown) once("breakdown", step, 30 to "breakdown +0.5 s")
                }
                wanted.remove(step)?.let { caption -> stills += "$caption  $at s" to bitmap.toBufferedImage() }
                split = scene.split
                slices = scene.slices
                channel = scene.channel
                moshing = scene.moshing
                breakdown = scene.breakdown
            }
            File(out, "$name-faults.txt").writeText(lines.joinToString("\n"))
            if (stills.isNotEmpty()) ImageIO.write(strip(stills), "png", File(out, "$name-moments.png"))
            println("faults of $name: ${lines.size} lines, ${stills.size} moments in ${out.absolutePath}")
        }
    }

    /** The stills side by side, each with its caption under it. */
    private fun strip(stills: List<Pair<String, BufferedImage>>): BufferedImage {
        val width = stills.first().second.width
        val height = stills.first().second.height
        val sheet = BufferedImage(width * stills.size, height + 22, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.color = Color(16, 16, 20)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        stills.forEachIndexed { index, (caption, image) ->
            graphics.drawImage(image, index * width, 0, null)
            graphics.color = Color(210, 210, 215)
            graphics.drawString(caption, index * width + 6, height + 16)
        }
        graphics.dispose()
        return sheet
    }

    private fun readMono(file: File): FloatArray {
        val floats = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}
