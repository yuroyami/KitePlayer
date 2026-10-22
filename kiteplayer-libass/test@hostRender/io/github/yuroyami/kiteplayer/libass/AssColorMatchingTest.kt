@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A script's colours matched to the video through its `YCbCr Matrix` header (#499), drawn by the real
 * libass. Each script draws one solid box, and the box's middle pixel is read back. The expected
 * colours are the ones the `ffmpeg` command line gives for the same conversion, which the C suite
 * `test_ass_color` lists with the commands that made them.
 */
class AssColorMatchingTest {

    private val bt709 = ColorSpaceInfo(matrix = ColorMatrix.Bt709)
    private val bt601 = ColorSpaceInfo(matrix = ColorMatrix.Smpte170m)

    /** The colour the box drawn in [rgb] comes out in, under [header] and over [video]. */
    private fun boxColor(rgb: Int, header: String?, video: ColorSpaceInfo?): Int {
        val bgr = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
        val colour = "&H00" + bgr.toString(16).padStart(6, '0').uppercase()
        val script = buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: 640")
            appendLine("PlayResY: 360")
            if (header != null) appendLine("YCbCr Matrix: $header")
            appendLine()
            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, " +
                    "Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                    "Alignment, MarginL, MarginR, MarginV, Encoding",
            )
            appendLine("Style: Default,Arial,20,$colour,$colour,$colour,$colour,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1")
            appendLine()
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            appendLine("Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,{\\pos(100,100)\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}")
        }
        val typesetter = LibassTypesetter()
        try {
            typesetter.openDocument(script.encodeToByteArray())
            val frame = TypesetFrame(width = 1280, height = 720, videoWidth = 640, videoHeight = 360, videoColor = video)
            val images = assertNotNull(typesetter.render(1_000, frame), "the first render answered unchanged")
            val box = assertNotNull(images.maxByOrNull { it.bitmap.width * it.bitmap.height }, "the box drew nothing")
            val middle = ((box.bitmap.height / 2) * box.bitmap.width + box.bitmap.width / 2) * 4
            val px = box.bitmap.pixels
            assertEquals(255, px[middle + 3].toInt() and 0xFF, "the middle of the box is not opaque")
            return ((px[middle].toInt() and 0xFF) shl 16) or ((px[middle + 1].toInt() and 0xFF) shl 8) or
                (px[middle + 2].toInt() and 0xFF)
        } finally {
            typesetter.close()
        }
    }

    private fun hex(rgb: Int) = rgb.toString(16).padStart(6, '0')

    @Test
    fun aScriptWithNoHeaderIsDrawnAsVsFilterDrewItOverBt709() {
        assertEquals(hex(0x287dc4), hex(boxColor(0x3080c0, header = null, video = bt709)))
        assertEquals(hex(0x287dc4), hex(boxColor(0x3080c0, header = "TV.601", video = bt709)))
    }

    @Test
    fun eachHeaderConvertsFromItsOwnMatrixAndRange() {
        assertEquals(hex(0x3087c7), hex(boxColor(0x3080c0, header = "PC.709", video = bt601)))
        assertEquals(hex(0x3081c0), hex(boxColor(0x3080c0, header = "TV.240M", video = bt709)))
        assertEquals(hex(0x307ab9), hex(boxColor(0x3080c0, header = "TV.FCC", video = bt709.copy(fullRange = true))))
    }

    @Test
    fun noneTheVideosOwnMatrixAndNoVideoColourKeepTheAuthoredColour() {
        assertEquals(hex(0x3080c0), hex(boxColor(0x3080c0, header = "None", video = bt709)))
        assertEquals(hex(0x3080c0), hex(boxColor(0x3080c0, header = "TV.709", video = bt709)))
        assertEquals(hex(0x3080c0), hex(boxColor(0x3080c0, header = null, video = null)))
    }

    /**
     * The check #499 names: a typesetter picks a sign's colour from a frame of BT.709 video decoded as
     * BT.601, as VSFilter-era tools did, and writes no header. Shown over that video, the matched box
     * lands within one 8-bit step of the pixels around it, and unmatched it is visibly off.
     */
    @Test
    fun aColourPickedFromTheFrameAsBt601MatchesTheFrameShownAsBt709() {
        // Saturated enough that the two matrices disagree by 8 and 11 steps, and inside the RGB cube
        // under both, since a clipped colour cannot come back to the YCbCr it was picked from.
        listOf(Triple(112, 168, 88), Triple(130, 180, 70)).forEach { (y, cb, cr) ->
            val picked = decode(y, cb, cr, kr = 0.299, kb = 0.114)
            val shown = decode(y, cb, cr, kr = 0.2126, kb = 0.0722)
            val matched = boxColor(picked, header = null, video = bt709)
            assertTrue(stepsApart(matched, shown) <= 1, "matched ${hex(matched)} against the frame's ${hex(shown)}")
            val unmatched = boxColor(picked, header = null, video = null)
            assertTrue(stepsApart(unmatched, shown) >= 6, "unmatched ${hex(unmatched)} is already close to ${hex(shown)}")
        }
    }

    /** Studio range YCbCr as RGB through the matrix with [kr] and [kb]. */
    private fun decode(y: Int, cb: Int, cr: Int, kr: Double, kb: Double): Int {
        val luma = (y - 16) / 219.0
        val blueDiff = (cb - 128) / 224.0
        val redDiff = (cr - 128) / 224.0
        val r = luma + 2 * (1 - kr) * redDiff
        val b = luma + 2 * (1 - kb) * blueDiff
        val g = (luma - kr * r - kb * b) / (1 - kr - kb)
        fun step(v: Double) = (v * 255).roundToInt().coerceIn(0, 255)
        return (step(r) shl 16) or (step(g) shl 8) or step(b)
    }

    private fun stepsApart(a: Int, b: Int): Int =
        listOf(16, 8, 0).maxOf { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) }
}
