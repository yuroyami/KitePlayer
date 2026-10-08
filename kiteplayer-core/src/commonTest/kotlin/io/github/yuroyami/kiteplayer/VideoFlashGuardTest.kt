@file:OptIn(KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The flash guard's rule (#500). See `docs/video-flash-guard.md`. */
class VideoFlashGuardTest {

    /** When frame [index] is shown at 30 frames a second, exact to the nanosecond at each whole second. */
    private fun shownAt(index: Int) = index * 1_000_000_000L / 30

    /** A frame whose first [cells] cells have [light] and the rest [rest]. */
    private fun frame(light: Float, cells: Int = VideoFlashGuard.CELLS, rest: Float = 0f) =
        FloatArray(VideoFlashGuard.CELLS) { if (it < cells) light else rest }

    /** A strobe at 5 Hz at 30 frames a second: three frames of [high], three of [low]. */
    private fun strobe(frames: Int, high: Float = 1f, low: Float = 0f, cells: Int = VideoFlashGuard.CELLS) =
        List(frames) { frame(if ((it / 3) % 2 == 1) high else low, cells) }

    /** Each frame's factor, frame [first] being shown at its index times a 30th of a second. */
    private fun factors(frames: List<FloatArray>, guard: VideoFlashGuard = VideoFlashGuard()): List<Float> =
        frames.mapIndexed { index, cells -> guard.factorFor(cells, shownAt(index)) }

    /** The mean light the frame is drawn with: the renderer scales encoded values by the factor. */
    private fun drawn(cells: FloatArray, factor: Float) = cells.average().toFloat() * factor.pow(2.2f)

    @Test
    fun aFullPictureStrobeRunsUndimmedForThreeFlashesThenStaysUnderTheThreshold() {
        val frames = strobe(120)
        val factors = factors(frames)
        val first = factors.indexOfFirst { it < 1f }
        // Legs on frames 3, 6, 9, ...: the seventh is on frame 21.
        assertEquals(21, first, "the seventh leg starts the run")
        assertTrue(factors.take(first).all { it == 1f }, "three flashes go untouched")
        for (i in first + 1 until frames.size) {
            val leg = abs(drawn(frames[i], factors[i]) - drawn(frames[i - 1], factors[i - 1]))
            assertTrue(leg < 0.1f, "frame $i changed the light by $leg")
        }
        assertTrue(factors[first] > 0.3f, "and dims no more than it must, to ${factors[first]}")
    }

    @Test
    fun threeFlashesInASecondAreNeverTouched() {
        val frames = strobe(18) + List(90) { frame(0f) } + strobe(18)
        assertTrue(factors(frames).all { it == 1f })
    }

    @Test
    fun aFadeOutAndInIsOneFlash() {
        // Out over half a second and in over the next, four times: two flashes a second.
        val one = List(15) { frame(1f - it / 15f) } + List(15) { frame(it / 15f) }
        assertTrue(factors(one + one + one + one).all { it == 1f })
    }

    @Test
    fun aStrobeOverAFifthOfThePictureIsNotARun() {
        assertTrue(factors(strobe(120, cells = VideoFlashGuard.CELLS / 5)).all { it == 1f })
    }

    @Test
    fun aStrobeOverAQuarterOfThePictureIsARun() {
        assertTrue(factors(strobe(120, cells = VideoFlashGuard.CELLS / 4)).any { it < 1f })
    }

    @Test
    fun cellsThatTurnAFrameApartCountTogether() {
        // Two groups of 30 cells, each under a quarter, the second a frame behind the first.
        val frames = List(120) { index ->
            FloatArray(VideoFlashGuard.CELLS) { cell ->
                val lag = if (cell < 30) 0 else if (cell < 60) 1 else return@FloatArray 0f
                if (((index - lag).coerceAtLeast(0) / 3) % 2 == 1) 1f else 0f
            }
        }
        assertTrue(factors(frames).any { it < 1f })
    }

    @Test
    fun aResetForgetsARun() {
        val guard = VideoFlashGuard()
        assertTrue(factors(strobe(60), guard).last() < 1f)
        guard.reset()
        assertEquals(1f, guard.current)
        assertTrue(List(30) { guard.factorFor(frame(0f), shownAt(100 + it)) }.all { it == 1f })
    }

    @Test
    fun aStrobeBetweenBrightGreysIsNotARun() {
        assertTrue(factors(strobe(120, high = 1f, low = 0.85f)).all { it == 1f })
    }

    @Test
    fun aLargerLegInARunLowersTheFactorAtOnce() {
        val guard = VideoFlashGuard()
        val faint = factors(strobe(60, high = 0.3f), guard)
        val faintFactor = faint.last()
        assertTrue(faintFactor < 1f, "a strobe of 0.3 is a run")
        val full = strobe(60).mapIndexed { index, cells -> guard.factorFor(cells, shownAt(60 + index)) }
        // The first full leg after the faint run, at frame 63, lowers it on that frame.
        assertTrue(full[3] < faintFactor, "${full[3]} after $faintFactor")
    }

    @Test
    fun afterARunTheFactorReturnsToExactlyOne() {
        val frames = strobe(60) + List(150) { frame(0f) }
        val factors = factors(frames)
        // The fall into the black at frame 60 is the last leg.
        val lastLeg = 60
        assertTrue(factors[lastLeg] < 1f)
        // Held for a second after the last leg, then back over a second and a half.
        assertEquals(factors[lastLeg], factors[lastLeg + 30], "held")
        val back = factors.subList(lastLeg + 30, lastLeg + 76)
        assertTrue(back.zipWithNext().all { (a, b) -> b > a }, "it rises")
        assertTrue(back.zipWithNext().all { (a, b) -> b - a < 0.1f }, "slowly")
        assertEquals(1f, factors[lastLeg + 75])
        assertTrue(factors.drop(lastLeg + 75).all { it == 1f }, "and then frames are untouched")
    }

    @Test
    fun theCellsOfAConvertedPictureAreItsMeanLightInLinearTerms() {
        val width = 320
        val height = 180
        val rgba = ByteArray(width * height * 4) { index ->
            val x = (index / 4) % width
            when {
                index % 4 == 3 -> -1
                x < width / 2 -> -1
                else -> 0x80.toByte()
            }
        }
        val cells = FloatArray(VideoFlashGuard.CELLS)
        VideoFlashGuard.cellsFromRgba(rgba, width, height, into = cells)
        assertEquals(1f, cells[0], 1e-4f, "white is 1")
        assertEquals(0.2158f, cells[15], 1e-3f, "and code 128 is 0.2158 in linear light")
        assertTrue(cells.withIndex().all { (i, v) -> if (i % 16 < 8) abs(v - 1f) < 1e-4f else abs(v - 0.2158f) < 1e-3f })
    }

    @Test
    fun packedPixelsMeasureAsTheirBytesDo() {
        val width = 200
        val height = 90
        val stride = width + 6
        fun red(x: Int, y: Int) = (x * 7 + y) and 0xFF
        fun green(x: Int, y: Int) = (y * 13) and 0xFF
        fun blue(x: Int, y: Int) = ((x + y) * 3) and 0xFF
        val rgba = ByteArray(width * height * 4)
        // The padding past each row is white, and the top byte is not alpha, so neither may count.
        val packed = IntArray(stride * height) { 0xFFFFFF }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = (y * width + x) * 4
                rgba[at] = red(x, y).toByte()
                rgba[at + 1] = green(x, y).toByte()
                rgba[at + 2] = blue(x, y).toByte()
                rgba[at + 3] = -1
                packed[y * stride + x] = (0x5A shl 24) or (red(x, y) shl 16) or (green(x, y) shl 8) or blue(x, y)
            }
        }
        val fromBytes = FloatArray(VideoFlashGuard.CELLS)
        val fromInts = FloatArray(VideoFlashGuard.CELLS)
        VideoFlashGuard.cellsFromRgba(rgba, width, height, into = fromBytes)
        VideoFlashGuard.cellsFromPackedRgb(packed, width, height, stride, into = fromInts)
        assertTrue(fromBytes.toSet().size > 20, "the picture varies from cell to cell")
        assertTrue(fromBytes.indices.all { fromBytes[it] == fromInts[it] }, "${fromBytes.toList()} against ${fromInts.toList()}")
    }

    @Test
    fun theSettingIsPublishedAndReachesTheRendererOnAttachAndOnChange() = runTest {
        val harness = CoreHarness(this, config = PlayerConfig(flashGuard = FlashGuard.On))
        val player = KitePlayer(harness.core)
        harness.attachRenderer()
        player.open(MediaItem("scripted://flash"))
        harness.run(100.milliseconds)
        assertEquals(FlashGuard.On, player.state.value.flashGuard, "the configured value")
        assertEquals(FlashGuard.On, harness.renderer?.flashGuard, "told on attach")
        player.setFlashGuard(FlashGuard.Off)
        harness.run(100.milliseconds)
        assertEquals(FlashGuard.Off, player.state.value.flashGuard)
        assertEquals(FlashGuard.Off, harness.renderer?.flashGuard, "and on change")
        harness.close()
    }
}
