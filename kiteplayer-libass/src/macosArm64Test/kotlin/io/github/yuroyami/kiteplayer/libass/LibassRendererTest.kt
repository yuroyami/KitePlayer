package io.github.yuroyami.kiteplayer.libass

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The optional libass module proved with the REAL library on the host: a styled ASS document
 * renders to bitmap regions with visible pixels of the requested colour, and a time with no
 * event renders to nothing. This is the module's whole contract: typesetting in, the engine's
 * existing bitmap-cue vocabulary out.
 */
class LibassRendererTest {

    private val script = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Helvetica,40,&H0000FF00,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Full throttle
    """.trimIndent()

    @Test
    fun aStyledEventRendersGreenPixelsAndSilenceRendersNothing() {
        LibassRenderer().use { renderer ->
            val cue = renderer.renderDocument(script, timeMillis = 2_000, frameWidth = 640, frameHeight = 360)
            assertNotNull(cue, "libass rendered nothing for a visible event")
            assertTrue(cue.regions.isNotEmpty(), "the cue carries no regions")

            var visible = 0
            var greenish = 0
            cue.regions.forEach { region ->
                val px = region.bitmap.pixels
                var at = 0
                while (at < px.size) {
                    val alpha = px[at + 3].toInt() and 0xFF
                    if (alpha > 32) {
                        visible++
                        val red = px[at].toInt() and 0xFF
                        val green = px[at + 1].toInt() and 0xFF
                        if (green > 200 && red < 64) greenish++
                    }
                    at += 4
                }
            }
            assertTrue(visible > 100, "a 40px line must cover more than 100 visible pixels, got $visible")
            // The outline renders as its own black images, so green is a large minority of the
            // visible pixels, never the majority. Five hundred is far above noise for 40px text.
            assertTrue(
                greenish > 500,
                "the style's &H0000FF00 primary is green; only $greenish of $visible visible pixels were",
            )

            assertNull(
                renderer.renderDocument(script, timeMillis = 20_000, frameWidth = 640, frameHeight = 360),
                "a time past the event's end must render nothing",
            )
        }
    }
}
