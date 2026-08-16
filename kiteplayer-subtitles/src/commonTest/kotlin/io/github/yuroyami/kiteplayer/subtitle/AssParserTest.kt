package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssParserTest {

    private val header = """
        [Script Info]
        Title: Test
        PlayResX: 1280
        PlayResY: 720

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Open Sans,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,20,20,30,1
        Style: Top,Arial,36,&H0000FFFF,&H000000FF,&H00101010,&H80000000,-1,-1,0,0,100,100,0,0,1,2,0,8,10,10,12,1
    """.trimIndent()

    private fun document(vararg events: String): String =
        header + "\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            events.joinToString("\n")

    @Test
    fun stylesTimingAndPlainTextComeThroughADocument() {
        val cues = AssParser.parse(
            document("""Dialogue: 0,0:00:01.50,0:00:04.00,Default,,0,0,0,,Hello there"""),
        )
        assertEquals(1, cues.size)
        val cue = cues[0] as SubtitleCue.Text
        assertEquals(1_500_000L, cue.startMicros)
        assertEquals(4_000_000L, cue.endMicros)
        assertEquals("Hello there", cue.plainText)
        val style = cue.spans.single().style
        assertEquals("Open Sans", style.fontFamily)
        assertEquals(48f, style.fontSizePx)
        assertEquals(0xFFFFFFFF.toInt(), style.primaryColor)
        assertEquals(3f, style.outlineWidthPx)
        assertEquals(720, cue.layout.authoredHeight)
        assertEquals(CueAlignment.BottomCenter, cue.layout.alignment)
        // Style margins normalised by PlayRes: 20/1280 horizontally, 30/720 vertically.
        assertEquals(20f / 1280f, cue.layout.marginLeft)
        assertEquals(30f / 720f, cue.layout.marginVertical)
    }

    @Test
    fun aStyledTopLineCarriesItsStyleBoldItalicAndAlignment() {
        val cues = AssParser.parse(
            document("""Dialogue: 0,0:00:00.00,0:00:02.00,Top,,0,0,0,,Sign text"""),
        )
        val cue = cues.single() as SubtitleCue.Text
        val style = cue.spans.single().style
        assertTrue(style.bold, "the Top style declares Bold -1")
        assertTrue(style.italic, "the Top style declares Italic -1")
        assertEquals("Arial", style.fontFamily)
        // &H0000FFFF is blue=00 green=FF red=FF with opaque alpha: yellow in ARGB.
        assertEquals(0xFFFFFF00.toInt(), style.primaryColor)
        assertEquals(CueAlignment.TopCenter, cue.layout.alignment)
    }

    @Test
    fun overrideTagsSplitSpansAndResetRestoresTheBaseStyle() {
        val cues = AssParser.parse(
            document("""Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,plain {\b1\i1}loud{\r} calm"""),
        )
        val cue = cues.single() as SubtitleCue.Text
        assertEquals(listOf("plain ", "loud", " calm"), cue.spans.map { it.text })
        assertTrue(!cue.spans[0].style.bold)
        assertTrue(cue.spans[1].style.bold && cue.spans[1].style.italic)
        assertTrue(!cue.spans[2].style.bold, "\\r must restore the base style")
    }

    @Test
    fun positionAlignmentColourAndFadeOverridesReachTheLayout() {
        val cues = AssParser.parse(
            document(
                """Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\an7\pos(640,100)\c&H0000FF&\fad(200,300)}Sign""",
            ),
        )
        val cue = cues.single() as SubtitleCue.Text
        assertEquals(CueAlignment.TopLeft, cue.layout.alignment)
        assertEquals(0.5f, cue.layout.positionX)
        assertEquals(100f / 720f, cue.layout.positionY)
        // &H0000FF& is blue=00 green=00 red=FF: pure red.
        assertEquals(0xFFFF0000.toInt(), cue.spans.single().style.primaryColor)
        assertEquals(200_000L, cue.layout.fadeInMicros)
        assertEquals(300_000L, cue.layout.fadeOutMicros)
    }

    @Test
    fun lineBreaksBreakAndDrawingsAndBlankEventsAreDropped() {
        val cues = AssParser.parse(
            document(
                """Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,line one\Nline two""",
                """Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}""",
                """Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,""",
            ),
        )
        assertEquals(1, cues.size, "the drawing and the empty event must not become cues")
        assertEquals("line one\nline two", (cues[0] as SubtitleCue.Text).plainText)
    }

    @Test
    fun karaokeTagsAreStrippedWithTheirTextKept() {
        val cues = AssParser.parse(
            document("""Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\k20}la{\k30}la{\k50}la"""),
        )
        assertEquals("lalala", (cues.single() as SubtitleCue.Text).plainText)
    }

    @Test
    fun eventMarginsBeatStyleMarginsOnlyWhenPositive() {
        val cues = AssParser.parse(
            document("""Dialogue: 0,0:00:00.00,0:00:02.00,Default,,128,0,72,,Margined"""),
        )
        val layout = (cues.single() as SubtitleCue.Text).layout
        assertEquals(0.1f, layout.marginLeft)
        assertEquals(0.1f, layout.marginVertical)
        // MarginR was 0, so the style's 20/1280 stands.
        assertEquals(20f / 1280f, layout.marginRight)
    }

    @Test
    fun theEmbeddedEventFormParsesAgainstTheHeaderStyles() {
        val track = AssParser.trackParser(header)
        val cue = track.parseEvent(
            "1,0,Top,,0,0,0,,{\\b0}embedded",
            startMicros = 7_000_000L,
            endMicros = 9_500_000L,
        )
        assertNotNull(cue)
        assertEquals(7_000_000L, cue.startMicros)
        assertEquals(9_500_000L, cue.endMicros)
        assertEquals("embedded", cue.plainText)
        assertTrue(!cue.spans.single().style.bold, "the \\b0 override must beat the Top style's bold")
        assertTrue(cue.spans.single().style.italic, "untouched style properties must survive")
        assertEquals(CueAlignment.TopCenter, cue.layout.alignment)

        assertNull(track.parseEvent("garbage", 0, 1), "a malformed payload must be dropped, not thrown")
    }

    @Test
    fun aHeaderlessTrackStillYieldsUnstyledCues() {
        val track = AssParser.trackParser("")
        val cue = track.parseEvent("0,0,Missing,,0,0,0,,bare text", 0L, 2_000_000L)
        assertNotNull(cue, "a missing header must degrade to defaults, not to nothing")
        assertEquals("bare text", cue.plainText)
        assertEquals(CueAlignment.BottomCenter, cue.layout.alignment)
    }

    @Test
    fun ssaLegacyStylesAndAlignmentParse() {
        val ssa = """
            [Script Info]
            PlayResX: 640
            PlayResY: 480

            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Default,Tahoma,24,16777215,255,0,0,0,0,1,1,0,6,10,10,10,0,0

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Old school
        """.trimIndent()
        val cues = AssParser.parse(ssa)
        val cue = cues.single() as SubtitleCue.Text
        assertEquals("Old school", cue.plainText)
        // Legacy alignment 6 is 2+4: top centre.
        assertEquals(CueAlignment.TopCenter, cue.layout.alignment)
        // Decimal 16777215 is white.
        assertEquals(0xFFFFFFFF.toInt(), cue.spans.single().style.primaryColor)
    }
}
