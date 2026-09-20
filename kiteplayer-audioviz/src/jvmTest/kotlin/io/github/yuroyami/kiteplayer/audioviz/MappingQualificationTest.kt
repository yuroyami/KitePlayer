package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Four renders of every drawing, with real analysis, that answer what a still cannot.
 *
 * Silence against music says whether the audio drives the picture at all. Loud against quiet says
 * whether a loud passage looks louder. A held tone says whether a drawing invents a beat. The same
 * loop a quarter of a second late says whether the picture follows this audio or merely looks
 * musical. None of them establishes that a viewer sees the music cause the picture; that review is
 * done by people.
 */
class MappingQualificationTest {

    init { useSkiaGraphics() }

    private val delta = 1f / 60f

    /** A shader draws every pixel on the processor here, so it gets a smaller canvas. */
    private fun sizeOf(drawing: Visualization): Pair<Int, Int> =
        if (drawing is ShaderPreset) 64 to 40 else 128 to 80

    private class Run(
        val change: Float, val brightness: Float, val ink: Float,
        /** The steps whose change is a spike, and how big each one is. */
        val spikes: List<Pair<Int, Float>>,
    )

    /** Renders [drawing] against [samples] and measures the settled part of the run. */
    private fun run(drawing: Visualization, samples: FloatArray, settleSeconds: Float, frames: Int): Run {
        val (width, height) = sizeOf(drawing)
        val player = SongPlayer(samples, referencePower = REFERENCE)
        val from = (settleSeconds * 60f).toInt()
        var previous: IntArray? = null
        val changes = ArrayList<Float>()
        var luma = 0f
        var ink = 0f
        var counted = 0
        RenderHarness.forEachFrameOf(
            drawing, width, height, frames, VizPalette.Prism,
            source = { player.next(delta) },
        ) { bitmap, step ->
            if (step >= from) {
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                previous?.let { changes += difference(it, pixels) }
                luma += meanLuma(pixels)
                ink += ink(pixels)
                counted++
                previous = pixels
            }
        }
        val mean = if (changes.isEmpty()) 0f else changes.sum() / changes.size
        val sorted = changes.sorted()
        val middle = if (sorted.isEmpty()) 0f else sorted[sorted.size / 2]
        val limit = maxOf(middle * 3f, 0.004f)
        val spikes = changes.indices.filter { changes[it] > limit }.map { from + it + 1 to changes[it] }
        return Run(mean, luma / counted.coerceAtLeast(1), ink / counted.coerceAtLeast(1), spikes)
    }

    @Test
    fun silenceIsFarQuieterThanMusic() {
        val rows = eachDrawing { index, drawing ->
            val mapping = checkNotNull(drawing.mapping)
            val music = run(DriverProbe.drawing(index), SyntheticSong.drumLoop(SECONDS), 2f, 360)
            val quiet = run(DriverProbe.drawing(index), SyntheticSong.silence(SECONDS), mapping.silenceSettleSeconds, 360)
            val share = if (music.change <= 0f) 1f else quiet.change / music.change
            val problems = ArrayList<String>()
            if (music.change < MINIMUM_MUSIC) problems += "the music render barely moves: ${round(music.change)}"
            if (share > 0.2f) problems += "silence is ${round(share)} of the music render"
            if (mapping.silence == VizSilence.Still && share > 0.05f) problems += "declared still, but ${round(share)}"
            Triple(drawing.name, "${round(music.change)} ${round(quiet.change)} ${round(share)}", problems)
        }
        println("silence against music: music change, silence change, share")
        assertTrue(report(rows).isEmpty(), "drawings that do not answer the audio:\n" + report(rows).joinToString("\n"))
    }

    @Test
    fun aLoudPassageLooksLouderThanAQuietOne() {
        val loudSong = SyntheticSong.drumLoop(SECONDS)
        val quietSong = FloatArray(loudSong.size) { loudSong[it] * 0.25f }
        val rows = eachDrawing { index, drawing ->
            val loud = run(DriverProbe.drawing(index), loudSong, 2f, 300)
            val quiet = run(DriverProbe.drawing(index), quietSong, 2f, 300)
            val best = maxOf(fall(loud.brightness, quiet.brightness), fall(loud.ink, quiet.ink))
            Triple(
                drawing.name,
                "light ${round(loud.brightness)} to ${round(quiet.brightness)}" +
                    " size ${round(loud.ink)} to ${round(quiet.ink)}  fall ${round(best)}",
                if (best >= LOUDER) emptyList() else listOf("fell only ${round(best)}"),
            )
        }
        println("loud against quiet, 12 dB apart with one fixed reference: light and size")
        assertTrue(
            report(rows).isEmpty(),
            "drawings that hide how loud the music is:\n" + report(rows).joinToString("\n"),
        )
    }

    @Test
    fun aHeldToneHasNoBeatTrain() {
        val tone = tone(SECONDS)
        val rows = eachDrawing { index, drawing ->
            val held = run(DriverProbe.drawing(index), tone, 2f, 360)
            val where = held.spikes.joinToString(" ") { "${it.first}:${round(it.second).trim()}" }
            Triple(
                drawing.name,
                "${held.spikes.size} spikes, change ${round(held.change)}  $where",
                if (held.spikes.size <= MOST_SPIKES) emptyList()
                else listOf("${held.spikes.size} spikes at $where"),
            )
        }
        println("held tone: spikes in the change between frames after the attack")
        assertTrue(report(rows).isEmpty(), "drawings that invent a beat:\n" + report(rows).joinToString("\n"))
    }

    @Test
    fun theSameMusicALittleLateDrawsADifferentPicture() {
        // A drawing whose motion merely looks musical passes every other check here. This one asks
        // whether the picture follows this audio: the same loop a quarter of a second late must
        // not draw the same frames. Both runs start from the same reset, so the only difference
        // between them is when the drums land.
        val song = SyntheticSong.drumLoop(SECONDS)
        val late = (0.25f * 48_000).toInt()
        val shifted = FloatArray(song.size) { if (it < late) 0f else song[it - late] }
        val rows = eachDrawing { index, drawing ->
            val aligned = frames(DriverProbe.drawing(index), song, 300)
            val behind = frames(DriverProbe.drawing(index), shifted, 300)
            var sum = 0f
            for (step in aligned.indices) sum += difference(aligned[step], behind[step])
            val moved = sum / aligned.size
            Triple(
                drawing.name,
                round(moved),
                if (moved >= SHIFTED) emptyList() else listOf("the same picture whenever the drums land: ${round(moved)}"),
            )
        }
        println("the same loop a quarter of a second late: mean difference between the two renders")
        assertTrue(report(rows).isEmpty(), "drawings that do not follow this audio:\n" + report(rows).joinToString("\n"))
    }

    /** Every settled frame of a run, as pixels. */
    private fun frames(drawing: Visualization, samples: FloatArray, count: Int): List<IntArray> {
        val (width, height) = sizeOf(drawing)
        val player = SongPlayer(samples, referencePower = REFERENCE)
        val from = 120
        val out = ArrayList<IntArray>(count - from)
        RenderHarness.forEachFrameOf(
            drawing, width, height, count, VizPalette.Prism,
            source = { player.next(delta) },
        ) { bitmap, step ->
            if (step >= from) {
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                out += pixels
            }
        }
        return out
    }

    /** A steady tone with a single attack and no rhythm in it at all. */
    private fun tone(seconds: Float): FloatArray {
        val rate = 48_000
        val out = FloatArray((seconds * rate).toInt())
        for (index in out.indices) {
            val time = index.toFloat() / rate
            val rise = (time / 0.01f).coerceAtMost(1f)
            out[index] = 0.35f * rise * (sin(2f * PI.toFloat() * 220f * time) + 0.5f * sin(2f * PI.toFloat() * 440f * time))
        }
        return out
    }

    /**
     * Renders every declaring drawing in parallel, the way the other rendering suites do.
     *
     * AUDIOVIZ_SURVEY names the drawings to render, comma separated, so one drawing can be checked
     * in seconds rather than the whole catalogue in minutes. A run that names drawings proves
     * nothing about the rest.
     */
    private fun eachDrawing(work: (Int, Visualization) -> Triple<String, String, List<String>>):
        List<Triple<String, String, List<String>>> {
        val catalogue = VizCatalog.create()
        val chosen = System.getenv("AUDIOVIZ_SURVEY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val indices = catalogue.indices.filter { chosen == null || catalogue[it].name in chosen }
        return RenderHarness.inParallel(indices) { index ->
            val drawing = DriverProbe.drawing(index)
            if (drawing.mapping == null) null else work(index, drawing)
        }.filterNotNull()
    }

    /** Prints one line a drawing and answers the failures. */
    private fun report(rows: List<Triple<String, String, List<String>>>): List<String> {
        val failures = ArrayList<String>()
        for ((name, numbers, problems) in rows) {
            println("  ${name.padEnd(18)} $numbers" + if (problems.isEmpty()) "" else "   << ${problems.joinToString()}")
            if (problems.isNotEmpty()) failures += "$name: ${problems.joinToString()}"
        }
        return failures
    }

    private fun fall(loud: Float, quiet: Float): Float = if (loud <= 1e-6f) 0f else (loud - quiet) / loud

    private fun round(value: Float): String = ((value * 1000).roundToInt() / 1000f).toString().padStart(6)

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f +
            0.0722f * (pixel and 0xFF) / 255f

    private fun meanLuma(pixels: IntArray): Float {
        var sum = 0f
        for (pixel in pixels) sum += luma(pixel)
        return sum / pixels.size
    }

    private fun ink(pixels: IntArray): Float {
        var count = 0
        for (pixel in pixels) if (luma(pixel) > 0.12f) count++
        return count.toFloat() / pixels.size
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private companion object {
        /**
         * How long each fixture runs.
         *
         * The player hears three seconds before the first frame and a run draws six seconds, so a
         * shorter fixture ends inside the run. The picture then answers the music stopping, which
         * reads as a beat the music does not have.
         */
        const val SECONDS = 12f

        /**
         * The reference a song map would fix, so both levels are drawn on the same scale.
         *
         * It is the drum fixture's own programme power, which is what a song map measures. A
         * reference far below it puts the loud run above the top of the height curve, where it
         * clips: the loud and the quiet run then read almost the same and the test measures the
         * curve rather than the drawing.
         */
        const val REFERENCE = 0.046

        /** A music render below this is a frozen picture, whatever its silence share says. */
        const val MINIMUM_MUSIC = 0.004f

        /** How much lower a 12 dB quieter passage must read. *Judgement.* */
        const val LOUDER = 0.1f

        /** Spikes allowed after the attack of a held tone: the attack itself and one settling frame. */
        const val MOST_SPIKES = 2

        /** How far apart the same music, a quarter of a second late, must draw. *Judgement.* */
        const val SHIFTED = 0.004f
    }
}
