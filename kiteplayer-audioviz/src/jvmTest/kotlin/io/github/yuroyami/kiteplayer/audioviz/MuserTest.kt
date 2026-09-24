package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Muser
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.MuserChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.MuserColour
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.MuserGenres
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Muser by Jon Shamir: it draws, it stands still in silence, and its "bass" is the page's 0 to 5.5 kHz. */
class MuserTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        val muser = Muser()
        assertEquals(PostSpec.Off, muser.post)
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(muser, 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixels(bitmap)
            peakInk = maxOf(peakInk, inkShare(pixels, 320))
            peakBlown = maxOf(peakBlown, blownShare(pixels))
        }
        assertTrue(peakInk > 0.004f, "the discs and lines should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        println("Muser frame-to-frame change: silence $silence, lively $music")
        assertTrue(silence < 0.0005f, "silence should hold the picture still, changed $silence")
        assertTrue(music > 0.001f, "music should move the picture, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theBassIsTheFirstQuarterOfA128PointAnalyserFrom0To5500Hz() {
        val muser = Muser()
        val analyser = muser.analyser
        assertEquals(128, analyser.fftSize)
        assertEquals(64, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        assertEquals(16, Muser.RANGE_SIZE)
        assertEquals(5_512.5, Muser.BASS_TOP_HZ)
        run(muser, player(RenderHarness.Song.Lively), rate = 60, seconds = 2f)
        assertEquals(analyser.frequencyBytes.take(16).sum() / 16.0, muser.rangeAverage[0], 1e-9)
        assertEquals(analyser.frequencyBytes.drop(16).take(16).sum() / 16.0, muser.rangeAverage[1], 1e-9)
        assertEquals(analyser.frequencyBytes.drop(32).take(16).sum() / 16.0, muser.rangeAverage[2], 1e-9)
        assertEquals(0.3 + 0.35 * muser.rangeAverage[0] / 255.0, muser.bass.size, 1e-12)
        assertEquals(0.1 + 0.1 * muser.rangeAverage[1] / 255.0, muser.mid.size, 1e-12)
        assertEquals(0.01 + 0.05 * muser.rangeAverage[2] / 255.0, muser.treble.size, 1e-12)
    }

    @Test
    fun aThreeKilohertzToneSwellsTheBigDiscsThoughTheLibraryCallsItTreble() {
        val high = Muser()
        val lastFrame = run(high, tone(3_000.0), rate = 60, seconds = 2f)
        println("3 kHz: ranges ${high.rangeAverage.toList()}, library bass ${lastFrame.bass}")
        // The library's own bass band, below 250 Hz, hears nothing of it.
        assertTrue(lastFrame.bass < 0.05f, "a 3 kHz tone is not bass to the library, read ${lastFrame.bass}")
        assertTrue(high.rangeAverage[0] > 60.0, "the page's bass range should hear a 3 kHz tone, read ${high.rangeAverage[0]}")
        assertTrue(high.rangeAverage[1] < 10.0, "the mid range should not, read ${high.rangeAverage[1]}")
        assertTrue(high.bass.size > 0.38, "the big discs should swell, uSize ${high.bass.size}")

        val upper = Muser()
        run(upper, tone(7_000.0), rate = 60, seconds = 2f)
        println("7 kHz: ranges ${upper.rangeAverage.toList()}")
        assertTrue(upper.rangeAverage[1] > 60.0, "a 7 kHz tone sits in the page's mid range, read ${upper.rangeAverage[1]}")
        assertTrue(upper.rangeAverage[0] < 10.0, "and not in its bass range, read ${upper.rangeAverage[0]}")
    }

    @Test
    fun theReadsRunSixtyTimesAHeardSecondAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val muser = Muser()
            run(muser, player(RenderHarness.Song.Lively), rate = rate, seconds = 2f)
            assertTrue(muser.reads in 118L..120L, "$rate Hz gave ${muser.reads} reads in two seconds")
        }
        val silent = Muser()
        run(silent, player(RenderHarness.Song.Silence), rate = 60, seconds = 2f)
        assertEquals(0L, silent.reads, "silence is not heard, so nothing is read")
    }

    @Test
    fun theGroupTurnsATenthOfARadianAHeardSecondScaledByReducedMotion() {
        val muser = Muser()
        run(muser, player(RenderHarness.Song.Lively), rate = 60, seconds = 2f)
        assertEquals(0.2, muser.rotation, 0.005)
        assertEquals(2.0, muser.particleTime, 0.05)

        val reduced = Muser()
        run(reduced, player(RenderHarness.Song.Lively), rate = 60, seconds = 2f, motionScale = 0.25f)
        assertEquals(0.05, reduced.rotation, 0.002)
        assertEquals(2.0, reduced.particleTime, 0.05, "reduced motion slows the turn only")

        val silent = Muser()
        run(silent, player(RenderHarness.Song.Silence), rate = 60, seconds = 2f)
        assertEquals(0.0, silent.rotation)
        assertEquals(0.0, silent.particleTime)
    }

    @Test
    fun theDiscColoursMatchTheAuthorsScreenshot() {
        // The README's preview shows a field of #466464. Its near-black discs read (21, 30, 30): the field
        // darkened by 2.5 is black, drawn at 0.7 over the field.
        val field = doubleArrayOf(70.0, 100.0, 100.0)
        val darker = DoubleArray(3)
        MuserChroma.darken(field, 2.5, darker)
        assertEquals(listOf(0, 0, 0), darker.map { it.roundToInt() })
        assertEquals(listOf(21, 30, 30), darker.indices.map { (0.7 * darker[it] + 0.3 * field[it]).roundToInt() })

        // Its dots read (164, 198, 194) and (148, 179, 178): inside the spread of the brightened field at 0.9,
        // with the shader's hue and value jitter.
        val muser = Muser()
        val brighter = DoubleArray(3)
        MuserChroma.darken(field, -2.0, brighter)
        muser.treble.setColour(brighter, 0.9f)
        val low = IntArray(3) { 255 }
        val high = IntArray(3)
        for (index in 0 until muser.treble.count) {
            val colour = muser.treble.colourOf(index, 1f)
            val over = listOf(colour.red, colour.green, colour.blue).mapIndexed { channel, value ->
                (255 * (0.9 * value) + 0.1 * field[channel]).roundToInt()
            }
            for (channel in 0 until 3) {
                low[channel] = minOf(low[channel], over[channel])
                high[channel] = maxOf(high[channel], over[channel])
            }
        }
        for (sample in listOf(intArrayOf(164, 198, 194), intArrayOf(148, 179, 178))) {
            for (channel in 0 until 3) {
                assertTrue(sample[channel] in low[channel] - 2..high[channel] + 2,
                    "screenshot dot ${sample.toList()} outside ${low.toList()} to ${high.toList()}")
            }
        }
    }

    @Test
    fun theGenreTableIsTheOriginalsInItsOrder() {
        val table = MuserGenres.FAMILIES.flatMap { family -> family.titles.map { it to family.colour } }
        assertEquals(GENRES_JSON, table.map { (title, colour) -> title to "#%06x".format(colour) })
    }

    @Test
    fun theColourRuleTakesTheTopFiveAboveTheConfidenceThreshold() {
        val families = MuserGenres.FAMILIES
        val electronic = families.indexOfFirst { "electronic" in it.titles }
        val rock = families.indexOfFirst { "rock" in it.titles }
        // One family above 0.05: its own colour.
        val alone = DoubleArray(families.size) { 0.01 }.also { it[rock] = 0.4 }
        assertEquals(0xf1c232, MuserGenres.topGenresColour(alone))
        // Two equal families above it, and a value of exactly 0.05, which weighs nothing: their Lab mean.
        val pair = DoubleArray(families.size) { 0.0 }.also {
            it[electronic] = 0.3
            it[rock] = 0.3
            it[0] = 0.05
        }
        val expected = DoubleArray(3)
        MuserChroma.average(intArrayOf(0x990000, 0xf1c232), doubleArrayOf(1.0, 1.0), expected)
        assertEquals(MuserChroma.hex(expected), MuserGenres.topGenresColour(pair))
        // A sixth family is left out however high it is, as long as five are higher.
        val six = DoubleArray(families.size) { 0.0 }.also { values ->
            for (rank in 0 until 6) values[rank] = 0.9 - rank * 0.1
        }
        val five = six.copyOf().also { it[5] = 0.0 }
        assertEquals(MuserGenres.topGenresColour(five), MuserGenres.topGenresColour(six))
    }

    @Test
    fun aDenseNoisySecondLeansRedAndASparseTonalOneGrey() {
        val dense = MuserGenres.colourOf(centroid = 0.52, flatness = 0.42, density = 0.95)
        val sparse = MuserGenres.colourOf(centroid = 0.45, flatness = 0.08, density = 0.25)
        println("dense #%06x, sparse #%06x".format(dense, sparse))
        assertTrue(red(dense) > green(dense) + 60, "a dense noisy second should lean red, was #%06x".format(dense))
        assertTrue(abs(red(sparse) - green(sparse)) < 40 && abs(green(sparse) - blue(sparse)) < 40,
            "a sparse tonal second should lean grey, was #%06x".format(sparse))
    }

    @Test
    fun theFieldFadesInLabFromTheColourBeforeAcrossEachHeardSecond() {
        val colour = MuserColour()
        val frame = featureFrame(centroid = 0.52f, flatness = 0.42f, density = 0.95f)
        val out = DoubleArray(3)
        colour.advance(0.5, frame)
        colour.genre(out)
        assertEquals(Muser.GREY, MuserChroma.hex(out), "the first second is the page's grey")
        colour.advance(0.5, frame)
        val picked = colour.current
        assertEquals(Muser.GREY, colour.previous)
        colour.genre(out)
        assertEquals(Muser.GREY, MuserChroma.hex(out), "a new second starts from the colour before")
        colour.advance(0.5, frame)
        colour.genre(out)
        val half = DoubleArray(3)
        MuserChroma.average(intArrayOf(Muser.GREY, picked), doubleArrayOf(0.5, 0.5), half)
        assertEquals(MuserChroma.hex(half), MuserChroma.hex(out), "halfway through, the Lab mean")
        colour.advance(0.5, frame)
        colour.advance(0.25, frame)
        colour.genre(out)
        assertEquals(picked, MuserChroma.hex(out), "a second with the same colour holds it")
    }

    /** Draws [muser] for [seconds] at [rate] frames a second, the way a window does, and answers the last frame. */
    private fun run(
        muser: Muser,
        player: SongPlayer,
        rate: Int,
        seconds: Float,
        motionScale: Float = 1f,
    ): SpectrumFrame {
        val bitmap = ImageBitmap(64, 40)
        val scope = CanvasDrawScope()
        val size = Size(64f, 40f)
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        var last: SpectrumFrame? = null
        muser.reset()
        repeat((seconds * rate).roundToInt().coerceAtLeast(1)) {
            elapsed += delta
            val frame = player.next(delta)
            last = frame
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            state.motionScale = motionScale
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(muser) {
                    draw(state)
                    drawFront(state)
                }
            }
        }
        return checkNotNull(last)
    }

    private fun player(song: RenderHarness.Song): SongPlayer = RenderHarness.player(song, 6f)

    /** A steady sine at a quarter of full scale, which a browser reads about 20 dB below full. */
    private fun tone(hertz: Double): SongPlayer {
        val samples = FloatArray(48_000 * 6) { (0.25 * sin(2 * PI * hertz * it / 48_000.0)).toFloat() }
        return SongPlayer(samples, bandCount = 48)
    }

    private fun featureFrame(centroid: Float, flatness: Float, density: Float): SpectrumFrame = SpectrumFrame(
        ptsMicros = 0L,
        bands = FloatArray(48),
        peaks = FloatArray(48),
        scope = FloatArray(0),
        level = 0.5f,
        bass = 0f,
        mid = 0f,
        treble = 0f,
        beat = 0f,
        pulse = 0f,
        density = density,
        centroid = centroid,
        flatness = flatness,
    )

    /** The mean frame-to-frame change over the last second of three. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(Muser(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = pixels(bitmap)
            if (step >= 120) {
                previous?.let {
                    change += difference(it, pixels)
                    counted++
                }
            }
            previous = pixels
        }
        return change / counted.coerceAtLeast(1)
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (index in a.indices) {
            sum += abs(red(a[index]) - red(b[index])) + abs(green(a[index]) - green(b[index])) + abs(blue(a[index]) - blue(b[index]))
        }
        return sum / (a.size * 3f * 255f)
    }

    private fun pixels(bitmap: ImageBitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    /** How much of the image stopped being the corner colour, as the contact sheet measures it. */
    private fun inkShare(pixels: IntArray, width: Int): Float {
        val background = pixels[0]
        var different = 0
        var counted = 0
        for (y in 0 until pixels.size / width step 2) {
            for (x in 0 until width step 2) {
                val pixel = pixels[y * width + x]
                val distance = abs(red(pixel) - red(background)) + abs(green(pixel) - green(background)) + abs(blue(pixel) - blue(background))
                if (distance > 18) different++
                counted++
            }
        }
        return different.toFloat() / counted
    }

    private fun blownShare(pixels: IntArray): Float =
        pixels.count { red(it) > 245 && green(it) > 245 && blue(it) > 245 }.toFloat() / pixels.size

    private fun red(rgb: Int): Int = rgb shr 16 and 0xFF
    private fun green(rgb: Int): Int = rgb shr 8 and 0xFF
    private fun blue(rgb: Int): Int = rgb and 0xFF

    private companion object {
        /** `src/data/genres.json` of the original, entry by entry. */
        val GENRES_JSON = listOf(
            "classical" to "#888888", "classic" to "#888888", "opera" to "#888888",
            "ambient" to "#741b47", "chill" to "#741b47", "chillout" to "#741b47", "Mellow" to "#741b47",
            "electro" to "#990000", "electronic" to "#990000", "electronica" to "#990000", "dance" to "#990000",
            "party" to "#990000", "House" to "#990000", "Hip-Hop" to "#741b47", "blues" to "#1155cc",
            "jazz" to "#0a5394", "rnb" to "#134f5c", "soul" to "#134f5c", "funk" to "#134f5c", "folk" to "#124f5c",
            "country" to "#b3c376", "pop" to "#b3c376", "indie pop" to "#b3c376", "rock" to "#f1c232",
            "oldies" to "#f1c232", "classic rock" to "#f1c232", "hard rock" to "#f1c232", "indie" to "#f1c232",
            "indie rock" to "#f1c232", "alternative rock" to "#f1c232", "alternative" to "#f1c232", "punk" to "#f1c232",
            "metal" to "#e69238", "heavy metal" to "#e69238", "Progressive rock" to "#e66c38",
        )
    }
}
