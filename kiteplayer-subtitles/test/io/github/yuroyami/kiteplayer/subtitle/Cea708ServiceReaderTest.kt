package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One CEA-708 service read into cues (#678): the windows, where they sit, their text and its pen,
 * and the commands that show, hide, clear and delay them.
 */
class Cea708ServiceReaderTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** DefineWindow, the anchor point numbered 0 to 8 from the top left. */
    private fun define(
        id: Int,
        visible: Boolean = true,
        priority: Int = 0,
        relative: Boolean = true,
        vertical: Int = 90,
        horizontal: Int = 50,
        anchor: Int = 7,
        rows: Int = 2,
        columns: Int = 32,
        windowStyle: Int = 1,
        penStyle: Int = 1,
    ) = bytes(
        0x98 + id,
        (if (visible) 0x20 else 0) or priority,
        (if (relative) 0x80 else 0) or vertical,
        horizontal,
        (anchor shl 4) or (rows - 1),
        columns - 1,
        (windowStyle shl 3) or penStyle,
    )

    private fun text(value: String) = value.encodeToByteArray()

    private val cr = bytes(0x0D)

    private fun display(mask: Int) = bytes(0x89, mask)

    private fun clear(mask: Int) = bytes(0x88, mask)

    /** SetPenColor with each colour as opacity, red, green and blue, two bits each. */
    private fun penColor(foreground: Int, background: Int, edge: Int = 0) = bytes(0x91, foreground, background, edge)

    @Test
    fun aVisibleWindowShowsItsTextAtItsAnchor() {
        val cues = Cea708ServiceReader().read(define(0) + text("HELLO"), 1_000_000)
        val cue = cues.single()
        assertEquals("HELLO", cue.plainText)
        assertEquals(1_000_000, cue.startMicros)
        assertEquals(SubtitleCue.OPEN_END, cue.endMicros)
        assertEquals(CueAlignment.BottomCenter, cue.layout.alignment)
        assertEquals(50 / 99f, cue.layout.positionX)
        assertEquals(90 / 99f, cue.layout.positionY)
    }

    @Test
    fun anAbsoluteAnchorIsReadOnTheWideGrid() {
        val cue = Cea708ServiceReader().read(define(0, relative = false, vertical = 0, horizontal = 0, anchor = 0) + text("TOP"), 0).single()
        assertEquals(CueAlignment.TopLeft, cue.layout.alignment)
        assertEquals(0f, cue.layout.positionX)
        assertEquals(0f, cue.layout.positionY)
        val right = Cea708ServiceReader().read(define(0, relative = false, vertical = 74, horizontal = 209, anchor = 8) + text("END"), 0).single()
        assertEquals(1f, right.layout.positionX)
        assertEquals(1f, right.layout.positionY)
    }

    @Test
    fun aHiddenWindowShowsOnlyOnceDisplayed() {
        val reader = Cea708ServiceReader()
        assertTrue(reader.read(define(1, visible = false) + text("POP ON"), 1_000_000).isEmpty(), "written while hidden")
        val cue = reader.read(display(0b10), 2_000_000).single()
        assertEquals("POP ON", cue.plainText)
        assertEquals(2_000_000, cue.startMicros)
    }

    @Test
    fun twoWindowsShowAsTwoCuesWithOneStart() {
        val cues = Cea708ServiceReader().read(
            define(0, priority = 3, anchor = 1, vertical = 5) + text("TOP") +
                define(1, priority = 0, anchor = 7) + text("BOTTOM"),
            500_000,
        )
        assertEquals(listOf("TOP", "BOTTOM"), cues.map { it.plainText }, "the higher priority comes last, drawn on top")
        assertTrue(cues.all { it.startMicros == 500_000L })
        assertEquals(CueAlignment.TopCenter, cues[0].layout.alignment)
        assertTrue(cues[1].layer > cues[0].layer, "priority 0 is the highest")
    }

    @Test
    fun aCarriageReturnOnTheLastRowScrollsTheWindowUp() {
        val reader = Cea708ServiceReader()
        reader.read(define(0, rows = 2, windowStyle = 4) + text("ONE") + cr + text("TWO"), 0)
        val cue = reader.read(cr + text("THREE"), 1_000_000).single()
        assertEquals("TWO\nTHREE", cue.plainText)
    }

    @Test
    fun clearingTheOnlyWindowEmptiesTheScreen() {
        val reader = Cea708ServiceReader()
        reader.read(define(0) + text("GONE SOON"), 0)
        val cue = reader.read(clear(0b1), 3_000_000).single()
        assertEquals(3_000_000, cue.startMicros)
        assertTrue(cue.spans.isEmpty(), "an empty cue clears the screen")
    }

    @Test
    fun deletingTheOnlyWindowEmptiesTheScreenAndForgetsIt() {
        val reader = Cea708ServiceReader()
        reader.read(define(0) + text("A"), 0)
        assertTrue(reader.read(bytes(0x8C, 0b1), 1_000_000).single().spans.isEmpty())
        assertTrue(reader.read(text("B") + display(0b1), 2_000_000).isEmpty(), "no window is current, and window 0 is gone")
    }

    @Test
    fun bytesThatChangeNothingGiveNoCue() {
        val reader = Cea708ServiceReader()
        reader.read(define(0) + text("SAME"), 0)
        assertTrue(reader.read(display(0b1), 1_000_000).isEmpty())
    }

    @Test
    fun thePenColoursTheTextAndItsBackground() {
        // Solid red text on a transparent pen background in a window with a black fill.
        val cue = Cea708ServiceReader().read(define(0) + penColor(0x30, 0xC0) + text("RED"), 0).single()
        val style = cue.spans.single().style
        assertEquals(0xFFFF0000.toInt(), style.primaryColor)
        assertEquals(0xFF000000.toInt(), style.backgroundColor, "the window's fill shows where the pen's background is transparent")
        val translucent = Cea708ServiceReader().read(define(0, windowStyle = 2) + penColor(0x8C, 0x80) + text("HALF"), 0).single()
        assertEquals(0x8000FF00.toInt(), translucent.spans.single().style.primaryColor)
        assertEquals(0x80000000.toInt(), translucent.spans.single().style.backgroundColor)
    }

    @Test
    fun theTransparentPenStylesDrawAnEdgeAndNoBox() {
        val style = Cea708ServiceReader().read(define(0, windowStyle = 2, penStyle = 6) + text("EDGE"), 0).single().spans.single().style
        assertEquals(0, style.backgroundColor)
        assertEquals(2f, style.outlineWidthPx)
        assertEquals(0xFF000000.toInt(), style.outlineColor)
    }

    @Test
    fun penAttributesItaliciseUnderlineAndSize() {
        val cue = Cea708ServiceReader().read(define(0) + bytes(0x90, 0x02, 0xC0) + text("BIG"), 0).single()
        val style = cue.spans.single().style
        assertTrue(style.italic)
        assertTrue(style.underline)
        assertEquals(1.25f, style.relativeSize)
    }

    @Test
    fun theSpansSplitWhereThePenChanges() {
        val cue = Cea708ServiceReader().read(define(0) + text("WHITE ") + penColor(0x3C, 0x00) + text("YELLOW"), 0).single()
        assertEquals(listOf("WHITE ", "YELLOW"), cue.spans.map { it.text })
        assertEquals(0xFFFFFF00.toInt(), cue.spans[1].style.primaryColor)
    }

    @Test
    fun aRowAndColumnFromThePenLocationKeepTheirPlaceAgainstEachOther() {
        val cue = Cea708ServiceReader().read(
            define(0, rows = 3) + bytes(0x92, 0, 2) + text("A") + bytes(0x92, 2, 4) + text("B"),
            0,
        ).single()
        assertEquals("A\n \n  B", cue.plainText, "the empty middle row keeps its height")
    }

    @Test
    fun theCharacterSetsReadTheirSymbols() {
        val cue = Cea708ServiceReader().read(
            define(0) + bytes(0x7F, 0x20, 0xE9, 0x10, 0x25, 0x10, 0x35, 0x10, 0xA0),
            0,
        ).single()
        assertEquals("♪ é…•㏄", cue.plainText)
    }

    @Test
    fun aBackspaceTakesTheLastCharacterBack() {
        val cue = Cea708ServiceReader().read(define(0) + text("CATS") + bytes(0x08) + text("!"), 0).single()
        assertEquals("CAT!", cue.plainText)
    }

    @Test
    fun aFormFeedClearsTheWindowAndStartsAtItsTopLeft() {
        val reader = Cea708ServiceReader()
        reader.read(define(0, rows = 2) + text("OLD") + cr + text("LINES"), 0)
        assertEquals("NEW", reader.read(bytes(0x0C) + text("NEW"), 1_000_000).single().plainText)
    }

    @Test
    fun wordWrapMovesTheWordToTheNextRow() {
        val cue = Cea708ServiceReader().read(define(0, rows = 2, columns = 10, windowStyle = 4) + text("HELLO THERE"), 0).single()
        assertEquals("HELLO\nTHERE", cue.plainText)
    }

    @Test
    fun aDelayHoldsTheCommandsAfterItUntilItRunsOut() {
        val reader = Cea708ServiceReader()
        reader.read(define(0, visible = false) + text("LATER"), 0)
        // A delay of one second, then the display.
        assertTrue(reader.read(bytes(0x8D, 10) + display(0b1), 1_000_000).isEmpty())
        assertTrue(reader.read(ByteArray(0), 1_500_000).isEmpty(), "still inside the delay")
        val cue = reader.read(ByteArray(0), 2_500_000).single()
        assertEquals("LATER", cue.plainText)
        assertEquals(2_000_000, cue.startMicros, "shown when the delay ran out")
    }

    @Test
    fun aDelayCancelAppliesWhatTheDelayHeldAtOnce() {
        val reader = Cea708ServiceReader()
        reader.read(define(0, visible = false) + text("NOW"), 0)
        assertTrue(reader.read(bytes(0x8D, 50) + display(0b1), 1_000_000).isEmpty())
        val cue = reader.read(bytes(0x8E), 1_200_000).single()
        assertEquals("NOW", cue.plainText)
        assertEquals(1_200_000, cue.startMicros)
    }

    @Test
    fun aCommandSplitBetweenPacketsIsReadWhole() {
        val defined = define(0) + text("SPLIT")
        val reader = Cea708ServiceReader()
        assertTrue(reader.read(defined.copyOfRange(0, 3), 0).isEmpty())
        assertEquals("SPLIT", reader.read(defined.copyOfRange(3, defined.size), 40_000).single().plainText)
    }

    @Test
    fun aResetForgetsEveryWindow() {
        val reader = Cea708ServiceReader()
        reader.read(define(0, visible = false) + text("BEFORE"), 0)
        reader.reset()
        assertTrue(reader.read(display(0b1), 1_000_000).isEmpty(), "the window went with the reset")
        assertEquals("AFTER", reader.read(define(0) + text("AFTER"), 2_000_000).single().plainText)
    }

    @Test
    fun theResetCommandEmptiesTheScreen() {
        val reader = Cea708ServiceReader()
        reader.read(define(0) + text("ON AIR"), 0)
        assertTrue(reader.read(bytes(0x8F), 1_000_000).single().spans.isEmpty())
    }

    @Test
    fun anUnknownExtendedCodeIsReadPast() {
        // A C3 code of variable length, four bytes after its length byte, then text.
        val cue = Cea708ServiceReader().read(define(0) + bytes(0x10, 0x90, 0x04, 1, 2, 3, 4) + text("OK"), 0).single()
        assertEquals("OK", cue.plainText)
    }
}
