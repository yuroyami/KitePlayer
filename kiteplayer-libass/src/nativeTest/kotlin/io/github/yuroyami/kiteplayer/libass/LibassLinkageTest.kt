package io.github.yuroyami.kiteplayer.libass

import kotlin.test.Test

/**
 * The chain links and libass runs, on every target that has one.
 *
 * Separate from [LibassRendererTest] because that one asserts PIXELS, and pixels need a font.
 * macOS gets fonts from CoreText; the cross-built chain deliberately carries no fontconfig, so on
 * those targets fonts arrive through `ass_add_font` from whatever the host app ships. A renderer
 * with no font is a legitimate state there, and a test that demanded green pixels would be
 * asserting the fixture rather than the module.
 *
 * What it does prove is the part that actually broke while widening the target list: that all four
 * archives resolve, that `ass_library_init` runs on this platform, and that a document can be
 * parsed and a frame asked for without crashing whatever the font situation is.
 */
class LibassLinkageTest {

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
    fun theChainInitialisesAndParsesWithoutCrashing() {
        LibassRenderer().use { renderer ->
            // The return value is deliberately not asserted: with no font provider it is null, with
            // one it is a cue, and BOTH are correct answers on the platform that gave them.
            renderer.renderDocument(script, timeMillis = 2_000, frameWidth = 640, frameHeight = 360)
            renderer.renderDocument(script, timeMillis = 20_000, frameWidth = 640, frameHeight = 360)
        }
    }
}
