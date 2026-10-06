package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.internal.matchAssColor
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.ScriptColorMatrix
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Kotlin tier matches an ASS cue's colours to the video through its script's header (#499), to
 * the same 8-bit steps as the libass driver. The expected colours are the `ffmpeg` command line's
 * for the same conversion, the rows the C suite `test_ass_color` lists with the commands that made
 * them, so the two tiers agree with one oracle rather than with each other.
 */
class AssColorMatchingTest {

    private val bt709 = ColorSpaceInfo(ColorMatrix.Bt709, ColorPrimaries.Bt709, ColorTransfer.Bt709)
    private val bt601 = ColorSpaceInfo(ColorMatrix.Smpte170m, ColorPrimaries.Smpte170m, ColorTransfer.Bt709)

    private fun hex(argb: Int) = (argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    @Test
    fun eachHeaderMatchesAsTheCommandLineDoes() {
        val rows = listOf(
            Triple(0x3080c0, ScriptColorMatrix.Default, bt709) to 0x287dc4,
            Triple(0xff0000, ScriptColorMatrix.Default, bt709) to 0xff1800,
            Triple(0x00ff00, ScriptColorMatrix.Default, bt709) to 0x00d800,
            Triple(0x0000ff, ScriptColorMatrix.Default, bt709) to 0x000fff,
            Triple(0x808080, ScriptColorMatrix.Default, bt709) to 0x808080,
            Triple(0xc06030, ScriptColorMatrix.Default, bt709) to 0xc9662d,
            Triple(0x102030, ScriptColorMatrix.Default, bt709) to 0x0f1f30,
            Triple(0xf0e0d0, ScriptColorMatrix.Default, bt709) to 0xf1e1d0,
            Triple(0x3080c0, ScriptColorMatrix.Bt601Tv, bt709) to 0x287dc4,
            Triple(0x3080c0, ScriptColorMatrix.Bt709Pc, bt601) to 0x3087c7,
            Triple(0x3080c0, ScriptColorMatrix.Smpte240mTv, bt709) to 0x3081c0,
            Triple(0x3080c0, ScriptColorMatrix.FccTv, bt709.copy(fullRange = true)) to 0x307ab9,
            Triple(0x3080c0, ScriptColorMatrix.Bt601Tv, ColorSpaceInfo(ColorMatrix.Bt2020Ncl)) to 0x2d82c5,
            Triple(0x3080c0, ScriptColorMatrix.None, bt709) to 0x3080c0,
            Triple(0x3080c0, ScriptColorMatrix.Unknown, bt709) to 0x3080c0,
            Triple(0x3080c0, ScriptColorMatrix.Bt601Tv, bt601) to 0x3080c0,
            Triple(0x3080c0, ScriptColorMatrix.Bt709Pc, bt709.copy(fullRange = true)) to 0x3080c0,
        )
        rows.forEach { (input, expected) ->
            val (rgb, header, video) = input
            // The alpha passes through untouched.
            val got = matchAssColor(0x5A000000 or rgb, header, video)
            assertEquals(hex(0x5A000000 or expected), hex(got), "${hex(rgb)} under $header over ${video.matrix}")
        }
    }

    private fun assCue(header: ScriptColorMatrix?) = SubtitleCue.Text(
        200_000,
        5_500_000,
        listOf(StyledSpan("a sign", CueStyle(primaryColor = 0xFF3080C0.toInt(), outlineColor = 0xFFFF0000.toInt()))),
        layout = CueLayout(scriptColorMatrix = header),
    )

    /** The first span's style the rasterizer receives for [cue] over a picture in [videoColor]. */
    private suspend fun TestScope.drawn(
        cue: SubtitleCue.Text,
        videoColor: ColorSpaceInfo?,
        matching: Boolean = true,
        override: SubtitleStyleOverride? = null,
    ): CueStyle {
        val script = MediaScript(durationUs = 6_000_000, subtitleCues = listOf(cue), videoColor = videoColor)
        val config = PlayerConfig(subtitles = SubtitleConfig(assColorMatching = matching))
        val harness = CoreHarness(this, script = script, config = config)
        harness.openWithRenderer()
        if (override != null) {
            harness.core.post(CoreCommand.SetSubtitleStyle(override, CompletableDeferred()))
        }
        harness.core.play()
        harness.run(600.milliseconds)
        val style = harness.output.rasterizedCueStyles.last { it.isNotEmpty() }.first()
        harness.core.close()
        return style
    }

    @Test
    fun anAssCueIsDrawnInItsMatchedColours() = runTest {
        val style = drawn(assCue(ScriptColorMatrix.Default), bt709)
        assertEquals(hex(0xFF287DC4.toInt()), hex(style.primaryColor))
        assertEquals(hex(0xFFFF1800.toInt()), hex(style.outlineColor))
    }

    @Test
    fun theAuthoredColoursStayWithTheSettingOffOverHdrAndForOtherFormats() = runTest {
        assertEquals(hex(0xFF3080C0.toInt()), hex(drawn(assCue(ScriptColorMatrix.Default), bt709, matching = false).primaryColor))
        val pq = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Pq)
        assertEquals(hex(0xFF3080C0.toInt()), hex(drawn(assCue(ScriptColorMatrix.Default), pq).primaryColor))
        // A SubRip or WebVTT cue names no header, and is drawn as it came.
        assertEquals(hex(0xFF3080C0.toInt()), hex(drawn(assCue(header = null), bt709).primaryColor))
    }

    @Test
    fun theViewersOwnColourIsNeverConverted() = runTest {
        val style = drawn(assCue(ScriptColorMatrix.Default), bt709, override = SubtitleStyleOverride(primaryColor = 0xFF123456.toInt()))
        assertEquals(hex(0xFF123456.toInt()), hex(style.primaryColor))
        // What the override leaves alone is still matched.
        assertEquals(hex(0xFFFF1800.toInt()), hex(style.outlineColor))
    }
}
