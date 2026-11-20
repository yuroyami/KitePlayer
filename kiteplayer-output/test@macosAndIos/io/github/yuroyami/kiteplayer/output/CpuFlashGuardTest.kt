package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.VideoAdjustments
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The flash guard of the Core Graphics renderers dims a flashing picture and no other (#500). */
class CpuFlashGuardTest {

    private var clock = 0L
    private var setting = false
    private val guard = CpuFlashGuard(systemSetting = { setting }, nanos = { clock })

    private fun grey(shade: Int) = ByteArray(SIZE * SIZE * 4) { if (it % 4 == 3) -1 else shade.toByte() }

    private fun light(code: Int): Float {
        val encoded = code / 255f
        return if (encoded <= 0.04045f) encoded / 12.92f else ((encoded + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Black and white, three pictures each at 30 a second: five flashes a second. */
    private fun strobe(pictures: Int) = List(pictures) { if (it / 3 % 2 == 0) 0 else 255 }

    /** Each picture's factor, and its first byte as the renderer would draw it. */
    private fun drawn(shades: List<Int>, adjustments: VideoAdjustments = VideoAdjustments.Identity) =
        shades.mapIndexed { index, shade ->
            clock = index * 1_000_000_000L / 30
            val rgba = grey(shade)
            val factor = guard.factorFor(rgba, SIZE, SIZE)
            factor to (adjustRgba(rgba, adjustments, factor)[0].toInt() and 0xFF)
        }

    @Test
    fun aStrobeIsDimmedFromThePictureThatMakesItsRun() {
        guard.setMode(FlashGuard.On)
        val out = drawn(strobe(90))
        // The seventh leg is on picture 21. It is measured before it is drawn, so it is dimmed itself.
        assertEquals(21, out.indexOfFirst { it.first < 1f })
        assertEquals(List(21) { if (it / 3 % 2 == 0) 0 else 255 }, out.take(21).map { it.second }, "whole before the run")
        val lights = out.drop(21).map { light(it.second) }
        val largest = lights.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue(largest < 0.10f, "a leg of $largest came out after the run started")
        assertTrue(lights.max() > 0.05f, "the picture is dimmed, not blacked out")
    }

    @Test
    fun aRedAndBlueStrobeOfOneLuminanceIsDimmedByTheRedRule() {
        guard.setMode(FlashGuard.On)
        fun flat(red: Int, green: Int, blue: Int) = ByteArray(SIZE * SIZE * 4) {
            when (it % 4) { 0 -> red; 1 -> green; 2 -> blue; else -> 255 }.toByte()
        }
        val factors = List(60) { index ->
            clock = index * 1_000_000_000L / 30
            guard.factorFor(if (index / 3 % 2 == 0) flat(0, 124, 255) else flat(255, 0, 0), SIZE, SIZE)
        }
        assertEquals(21, factors.indexOfFirst { it < 1f }, "the seventh red leg starts the run (#561)")
        // A full red leg is 320 on the rule's scale and must come out under 20.
        assertTrue(factors.last().pow(2.2f) * 320f < 20f, "the factor was ${factors.last()}")
    }

    @Test
    fun aPictureOutsideARunIsTheSameBytes() {
        guard.setMode(FlashGuard.On)
        val rgba = grey(200)
        repeat(30) { index ->
            clock = index * 1_000_000_000L / 30
            val factor = guard.factorFor(rgba, SIZE, SIZE)
            assertEquals(1f, factor)
            assertSame(rgba, adjustRgba(rgba, VideoAdjustments.Identity, factor), "an untouched picture copies nothing")
        }
    }

    @Test
    fun theFactorMultipliesThePictureControlsOffsetsIncluded() {
        val rgba = grey(100)
        val brighter = VideoAdjustments(brightness = 0.2f)
        val plain = adjustRgba(rgba, brighter)[0].toInt() and 0xFF
        val dimmed = adjustRgba(rgba, brighter, 0.5f)[0].toInt() and 0xFF
        assertEquals(151, plain, "100 and a fifth of 255")
        assertTrue(abs(dimmed - plain / 2f) <= 1f, "half of $plain came out as $dimmed")
        assertEquals(50, adjustRgba(rgba, VideoAdjustments.Identity, 0.5f)[0].toInt() and 0xFF)
    }

    @Test
    fun followSystemGuardsOnlyWhileDimFlashingLightsIsOn() {
        assertTrue(drawn(strobe(60)).all { it.first == 1f }, "the setting is off and the mode is the default")
        setting = true
        assertTrue(drawn(strobe(60)).any { it.first < 1f }, "the setting is on")
        setting = false
        assertEquals(1f, guard.factorFor(grey(255), SIZE, SIZE), "the setting went off in the middle of a run")
    }

    @Test
    fun offNeverDimsAndAChangeOfModeStartsAfresh() {
        setting = true
        guard.setMode(FlashGuard.Off)
        assertTrue(drawn(strobe(60)).all { it.first == 1f })
        guard.setMode(FlashGuard.On)
        val out = drawn(strobe(60))
        assertEquals(21, out.indexOfFirst { it.first < 1f }, "the history starts at the change of mode")
    }

    @Test
    fun aHeldPictureKeepsItsFactorUntilTheGuardGoesOff() {
        guard.setMode(FlashGuard.On)
        val shown = drawn(strobe(30)).last().first
        assertTrue(shown < 1f)
        assertEquals(shown, guard.held(shown))
        guard.setMode(FlashGuard.Off)
        assertEquals(1f, guard.held(shown), "a paused picture undims when the guard is turned off")
    }

    private companion object {
        const val SIZE = 32
    }
}
