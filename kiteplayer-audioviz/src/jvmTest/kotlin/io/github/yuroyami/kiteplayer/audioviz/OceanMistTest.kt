package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OceanMist
import io.github.yuroyami.kiteplayer.audioviz.viz.toOklab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ocean Mist on its own, outside the catalogue: the sea is drawn from the first frame, it holds
 * still in a silence apart from the glitter, a held note keeps the front row's shape, the rows
 * shade from deep blue to cyan, a wide stereo mix widens the glitter, and a drop sends a wave.
 */
class OceanMistTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsInkWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(OceanMist(), 320, 200, 60, VizPalette.Classic, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixelsOf(bitmap.width, bitmap.height) { bitmap.readPixels(it) }
            ink = maxOf(ink, inkFraction(pixels))
            blown = maxOf(blown, blownFraction(pixels))
        }
        println("ink ${percent(ink)}, blown ${percent(blown)}")
        assertTrue(ink > 0.004f, "the sea should put ink down, had ${percent(ink)}")
        assertTrue(blown <= 0.3f, "the picture should not saturate to white, had ${percent(blown)}")
    }

    @Test
    fun theFirstFrameShowsTheSeaDimlyLit() {
        val first = IntArray(WIDTH * HEIGHT)
        val loud = IntArray(WIDTH * HEIGHT)
        RenderHarness.forEachFrame(OceanMist(), WIDTH, HEIGHT, 150, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 0) bitmap.readPixels(first)
            if (step == 149) bitmap.readPixels(loud)
        }
        val sea = rows(first, 0.45f, 0.97f)
        assertTrue(inkFraction(sea) > 0.05f, "the first frame should already show the sea, had ${percent(inkFraction(sea))}")
        val moon = rows(first, 0.1f, 0.36f).count { luma(it) > 0.7f }
        assertTrue(moon > 50, "the first frame should show the moon, had $moon bright pixels")
        // The lines of the first frame are the lines of loud music at about a third of the light.
        val dim = brightest(rows(first, 0.6f, 0.97f))
        val bright = brightest(rows(loud, 0.6f, 0.97f))
        val share = dim / bright
        println("first frame light ${percent(share)} of loud music")
        assertTrue(share in 0.25f..0.5f, "the first frame should be lit at about a third, was ${percent(share)}")
    }

    @Test
    fun silenceMovesFarLessThanMusic() {
        val music = meanChange(RenderHarness.Song.Lively)
        val quiet = meanChange(RenderHarness.Song.Silence)
        println("change between frames: music $music, silence $quiet")
        assertTrue(music > 0.004f, "the music render should move, moved $music")
        assertTrue(quiet <= 0.2f * music, "silence should move at most a fifth as much as music: $quiet against $music")
    }

    @Test
    fun inSilenceOnlyTheGlitterChanges() {
        val before = IntArray(WIDTH * HEIGHT)
        val after = IntArray(WIDTH * HEIGHT)
        RenderHarness.forEachFrame(OceanMist(), WIDTH, HEIGHT, 200, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            if (step == 170) bitmap.readPixels(before)
            if (step == 199) bitmap.readPixels(after)
        }
        var changed = 0
        var outside = 0
        for (index in before.indices) {
            if (abs(luma(before[index]) - luma(after[index])) < 0.03f) continue
            changed++
            val x = (index % WIDTH).toFloat() / WIDTH
            // The glitter column hangs under the moon, which starts at 0.64 of the width.
            if (x !in 0.44f..0.84f) outside++
        }
        println("silence: $changed pixels changed, $outside outside the glitter column")
        assertTrue(changed > 0, "the glitter should still shimmer in a silence")
        assertTrue(outside <= changed / 20, "only the glitter should change in a silence: $outside of $changed outside it")
    }

    @Test
    fun aHeldToneKeepsTheFrontRowShape() {
        val steady = frontRowChange(tone(8f))
        val busy = frontRowChange(SyntheticSong.drumLoop(8f))
        println("front row change between frames: held tone $steady, drum loop $busy")
        assertTrue(steady < 0.25f * busy, "a held tone should hold the front row still: $steady against $busy")
        assertTrue(steady < 0.05f, "a held tone should give the same front row every frame, changed $steady")
    }

    @Test
    fun rowsShadeFromDeepBlueAtTheHorizonToCyanInFront() {
        val pixels = IntArray(WIDTH * HEIGHT)
        RenderHarness.forEachFrame(OceanMist(), WIDTH, HEIGHT, 120, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 119) bitmap.readPixels(pixels)
        }
        val far = meanHue(rows(pixels, 0.39f, 0.47f))
        val near = meanHue(rows(pixels, 0.72f, 0.97f))
        println("row hue: far $far, near $near")
        assertTrue(far in 235f..275f, "the far rows should be deep blue, hue $far")
        assertTrue(near in 185f..225f, "the near rows should be cyan, hue $near")
        val white = rows(pixels, 0.6f, 1f).count { isWhite(it) }
        assertTrue(white > 0, "loud music should turn some crests in front white")
    }

    @Test
    fun aWideStereoMixWidensTheGlitterColumn() {
        val mono = glitterSpread(null)
        val wide = glitterSpread(SyntheticSong.drumLoopSide(8f))
        println("glitter spread: mono $mono, stereo $wide")
        assertTrue(wide > 1.3f * mono, "a wide stereo mix should widen the glitter column: $wide against $mono")
    }

    @Test
    fun aDropSendsAWaveThatBreaksInSpray() {
        val calm = whiteCounts(null, drop = false)
        val wave = whiteCounts(null, drop = true)
        val landed = InjectedFrames.STRUCTURE_STEP
        val answered = (landed until FRAMES).firstOrNull { wave.second[it] > 0.002f } ?: -1
        println("drop answered at step $answered")
        assertTrue(answered in landed..landed + 2, "the wave should start on the drop, started at $answered")
        val rolling = (landed + 10 until landed + 150).sumOf { wave.first[it] }.toFloat()
        val still = (landed + 10 until landed + 150).sumOf { calm.first[it] }.toFloat()
        println("white along the wave's crest: $rolling against $still without the drop")
        assertTrue(rolling > 2f * still + 100f, "the rolling wave should be white along its crest")
        val broken = (landed + 150 until FRAMES).maxOf { wave.third[it] }
        val quiet = (landed + 150 until FRAMES).maxOf { calm.third[it] }
        println("spray after the break: $broken against $quiet")
        assertTrue(broken > quiet + 40, "the wave should break in white spray, had $broken against $quiet")
    }

    @Test
    fun aForeseenDropStartsTheWaveEarly() {
        val plain = frames(drop = true, future = null)
        val warned = frames(drop = true, future = dropAhead())
        var first = -1
        for (step in 0 until InjectedFrames.STRUCTURE_STEP) {
            if (difference(plain[step], warned[step]) > 0.002f) {
                first = step
                break
            }
        }
        println("the foreseen wave started at step $first, the drop lands at ${InjectedFrames.STRUCTURE_STEP}")
        assertTrue(first in 0 until InjectedFrames.STRUCTURE_STEP, "a drop in the queued audio should start the wave early")
    }

    // Helpers.

    private fun frames(drop: Boolean, future: VizFuture?): List<IntArray> {
        val out = ArrayList<IntArray>()
        RenderHarness.forEachFrameOf(
            OceanMist(), SMALL_WIDTH, SMALL_HEIGHT, FRAMES, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (drop) VizDriver.Drop else null, step) },
            future = { future },
        ) { bitmap, _ ->
            val pixels = IntArray(SMALL_WIDTH * SMALL_HEIGHT)
            bitmap.readPixels(pixels)
            out += pixels
        }
        return out
    }

    /** Per step: white pixels in the sea, the change from the calm run, and white specks above the front rows. */
    private fun whiteCounts(future: VizFuture?, drop: Boolean): Triple<IntArray, FloatArray, IntArray> {
        val base = frames(drop = false, future = null)
        val run = if (drop) frames(drop = true, future = future) else base
        val white = IntArray(FRAMES)
        val change = FloatArray(FRAMES)
        val specks = IntArray(FRAMES)
        for (step in 0 until FRAMES) {
            val pixels = run[step]
            change[step] = difference(base[step], pixels)
            for (index in pixels.indices) {
                val y = (index / SMALL_WIDTH).toFloat() / SMALL_HEIGHT
                if (y < 0.4f) continue
                if (isWhite(pixels[index])) {
                    white[step]++
                    if (y < 0.8f) specks[step]++
                }
            }
        }
        return Triple(white, change, specks)
    }

    private fun dropAhead(): VizFuture = object : VizFuture {
        private val event = AudioEvent(
            Generation.Initial, 0L, InjectedFrames.STRUCTURE_STEP.toLong(),
            AudioDetection(AudioEventKind.Drop, 1_100_000L, 1_100_000L, 0.7f, 0.8f, 0.5f),
            AudioEventSource.LiveStructure,
        )
        override fun at(secondsAhead: Float): SpectrumFrame? = null
        override val nextOnsetSeconds: Float get() = -1f
        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
            if (kind == AudioEventKind.Drop) UpcomingAudioEvent(event, 0.08f) else null
    }

    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        RenderHarness.forEachFrame(OceanMist(), SMALL_WIDTH, SMALL_HEIGHT, 300, VizPalette.Prism, song) { bitmap, step ->
            if (step < 120) return@forEachFrame
            val pixels = IntArray(SMALL_WIDTH * SMALL_HEIGHT)
            bitmap.readPixels(pixels)
            previous?.let { sum += difference(it, pixels); count++ }
            previous = pixels
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun frontRowChange(samples: FloatArray): Float {
        val drawing = OceanMist()
        val player = SongPlayer(samples, bandCount = 48)
        var previous: FloatArray? = null
        var most = 0f
        RenderHarness.forEachFrameOf(drawing, SMALL_WIDTH, SMALL_HEIGHT, 240, VizPalette.Prism, source = { player.next(1f / 60f) }) { _, step ->
            if (step < 120) return@forEachFrameOf
            val row = drawing.frontRow.copyOf()
            previous?.let { before ->
                var change = 0f
                for (index in row.indices) change = maxOf(change, abs(row[index] - before[index]))
                most = maxOf(most, change)
            }
            previous = row
        }
        return most
    }

    /** How far the glitter spreads either side of its middle, as the spread of its pixels across the width. */
    private fun glitterSpread(side: FloatArray?): Float {
        val player = SongPlayer(SyntheticSong.drumLoop(8f), bandCount = 48, side = side)
        val xs = ArrayList<Float>()
        var widest = 0f
        val source = { _: Int -> player.next(1f / 60f).also { widest = maxOf(widest, it.width) } }
        RenderHarness.forEachFrameOf(OceanMist(), WIDTH, HEIGHT, 240, VizPalette.Prism, source = source) { bitmap, step ->
            if (step < 150 || step % 10 != 0) return@forEachFrameOf
            val pixels = IntArray(WIDTH * HEIGHT)
            bitmap.readPixels(pixels)
            for (index in pixels.indices) {
                val y = (index / WIDTH).toFloat() / HEIGHT
                if (y < 0.5f) continue
                if (isGlitter(pixels[index])) xs += (index % WIDTH).toFloat() / WIDTH
            }
        }
        println("stereo width reached $widest")
        if (xs.size < 2) return 0f
        val mean = xs.sum() / xs.size
        return sqrt(xs.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / xs.size)
    }

    private fun tone(seconds: Float): FloatArray {
        val rate = 48_000
        return FloatArray((seconds * rate).toInt()) { index ->
            val time = index.toFloat() / rate
            0.3f * sin(2f * PI.toFloat() * 330f * time)
        }
    }

    /** Bright and without colour: a white crest or spray at the light the music gives it. */
    private fun isWhite(pixel: Int): Boolean {
        val (r, g, b) = channels(pixel)
        return minOf(r, g, b) > 150 && maxOf(r, g, b) - minOf(r, g, b) < 40
    }

    private fun isGlitter(pixel: Int): Boolean {
        val (r, g, b) = channels(pixel)
        return r > 90 && r >= g && g > b + 25
    }

    private fun meanHue(pixels: IntArray): Float {
        var x = 0.0
        var y = 0.0
        for (pixel in pixels) {
            val (r, g, b) = channels(pixel)
            if (maxOf(r, g, b) < 60 || r > b) continue
            val lab = Color(r / 255f, g / 255f, b / 255f).toOklab()
            if (lab.chroma < 0.05f) continue
            x += cos(lab.hue * PI / 180.0)
            y += sin(lab.hue * PI / 180.0)
        }
        val degrees = (atan2(y, x) * 180.0 / PI).toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    private fun brightest(pixels: IntArray): Float {
        val sorted = pixels.filter { pixel -> channels(pixel).let { it[2] > it[0] } }.map { luma(it) }.sorted()
        if (sorted.isEmpty()) return 0f
        return sorted[(sorted.size * 0.98f).toInt().coerceAtMost(sorted.size - 1)]
    }

    private fun rows(pixels: IntArray, from: Float, to: Float): IntArray {
        val top = (from * HEIGHT).toInt()
        val bottom = (to * HEIGHT).toInt().coerceAtMost(HEIGHT)
        return pixels.copyOfRange(top * WIDTH, bottom * WIDTH)
    }

    private inline fun pixelsOf(width: Int, height: Int, read: (IntArray) -> Unit): IntArray =
        IntArray(width * height).also(read)

    private fun inkFraction(pixels: IntArray): Float = pixels.count { luma(it) > 0.07f }.toFloat() / pixels.size

    private fun blownFraction(pixels: IntArray): Float = pixels.count { pixel -> channels(pixel).all { it > 245 } }.toFloat() / pixels.size

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun channels(pixel: Int): IntArray = intArrayOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f + 0.0722f * (pixel and 0xFF) / 255f

    private fun percent(value: Float): String = "${(value * 1000).toInt() / 10f}%"

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SMALL_WIDTH = 240
        const val SMALL_HEIGHT = 135
        const val FRAMES = 330
    }
}
