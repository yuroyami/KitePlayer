package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Percentages divided by a hundred do not land on exact binary fractions. */
private const val TOLERANCE = 1e-5f

/**
 * Where a WebVTT cue asked to be put.
 *
 * The parser read the settings after the timing line and kept only the alignment, so a cue
 * authored at a quarter of the way across and a tenth of the way down was drawn bottom centre
 * like every other one. Captions that point at a speaker were the visible loss.
 */
class WebVttCueSettingsTest {

    private fun parse(settings: String, text: String = "A speaker"): SubtitleCue.Text {
        val source = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000 $settings\n$text\n"
        val cues = WebVttParser.parse(source)
        assertEquals(1, cues.size, "expected exactly one cue from: $settings")
        return cues.first()
    }

    @Test
    fun `position and line become the cue's own place`() {
        val layout = parse("line:10% position:25% size:40%").layout
        assertEquals(0.25f, layout.positionX)
        assertEquals(0.1f, layout.positionY)
    }

    @Test
    fun `a cue that says nothing keeps the default place`() {
        val layout = parse("").layout
        assertNull(layout.positionX)
        assertNull(layout.positionY)
        assertEquals(CueAlignment.BottomCenter, layout.alignment)
    }

    @Test
    fun `size becomes the space left clear on each side`() {
        // A box 40 per cent wide centred at 25 per cent starts at 5 per cent and ends at 45.
        val layout = parse("position:25% size:40%").layout
        assertEquals(0.05f, layout.marginLeft, TOLERANCE)
        assertEquals(0.55f, layout.marginRight, TOLERANCE)
    }

    @Test
    fun `the alignment says which edge the position anchors`() {
        val fromLeft = parse("align:start position:25% size:40%").layout
        assertEquals(0.25f, fromLeft.marginLeft, TOLERANCE)
        assertEquals(0.35f, fromLeft.marginRight, TOLERANCE)

        val fromRight = parse("align:end position:60% size:40%").layout
        assertEquals(0.2f, fromRight.marginLeft, TOLERANCE)
        assertEquals(0.4f, fromRight.marginRight, TOLERANCE)
    }

    @Test
    fun `a box wider than the picture is not pushed off it`() {
        val layout = parse("position:10% size:100%").layout
        assertEquals(0f, layout.marginLeft, TOLERANCE)
        assertEquals(0f, layout.marginRight, TOLERANCE)
    }

    @Test
    fun `a size with no position centres the box`() {
        val layout = parse("size:50%").layout
        assertEquals(0.25f, layout.marginLeft, TOLERANCE)
        assertEquals(0.25f, layout.marginRight, TOLERANCE)
    }

    @Test
    fun `the alignment still works on its own`() {
        assertEquals(CueAlignment.BottomLeft, parse("align:start").layout.alignment)
        assertEquals(CueAlignment.BottomRight, parse("align:right").layout.alignment)
        assertEquals(CueAlignment.BottomCenter, parse("align:middle").layout.alignment)
    }

    @Test
    fun `a line given as a row number is not read as a fraction`() {
        // A line number counts text rows and means nothing until the text is measured, so turning
        // it into a fraction of the picture would put the cue somewhere nobody asked for.
        assertNull(parse("line:5").layout.positionY)
    }

    @Test
    fun `a percentage outside the picture is ignored rather than clamped`() {
        assertNull(parse("position:150%").layout.positionX)
        assertNull(parse("line:-20%").layout.positionY)
    }
}
