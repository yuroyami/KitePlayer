package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every fixture here is a shape that real SubRip files have. The parser promises to accept all of
 * them and to never throw, so each test states what a viewer would see rather than how the parser
 * gets there.
 */
class SubRipParserTest {

    private fun parse(text: String) = SubRipParser.parse(text)

    @Test
    fun `a normal multi cue file parses`() {
        val cues = parse(
            """
            1
            00:00:01,000 --> 00:00:02,000
            The first line

            2
            00:00:02,500 --> 00:00:04,000
            A cue with
            two lines

            3
            00:01:02,500 --> 00:01:05,000
            <i>Whispered</i>
            """.trimIndent(),
        )

        assertEquals(3, cues.size)
        assertEquals(listOf(1_000_000L, 2_500_000L, 62_500_000L), cues.map { it.startMicros })
        assertEquals(listOf(2_000_000L, 4_000_000L, 65_000_000L), cues.map { it.endMicros })
        assertEquals(
            listOf("The first line", "A cue with\ntwo lines", "Whispered"),
            cues.map { it.plainText },
            "line breaks inside a cue are kept, and markup is not part of the text",
        )
        assertTrue(cues[2].spans.single().style.italic, "the italic tag must become a style, not text")
    }

    @Test
    fun `a byte order mark never reaches the cue text`() {
        // Editors on Windows write a UTF-8 byte order mark. Left in place it either kills the first
        // cue or shows as a stray glyph in front of the first word.
        val file = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello
        """.trimIndent()

        val marked = parse("\uFEFF$file")
        assertEquals(parse(file), marked, "a byte order mark must change nothing about the result")
        assertEquals("Hello", marked.single().plainText)
        assertFalse(marked.single().plainText.contains('\uFEFF'))

        // The same file without an index line puts the mark on the timing line itself.
        assertEquals(
            1,
            parse("\uFEFF00:00:01,000 --> 00:00:02,000\nHello").size,
            "the first cue must survive a mark sitting on its timing line",
        )
    }

    @Test
    fun `windows line endings parse the same as unix ones`() {
        val crlf = "1\r\n" +
            "00:00:01,000 --> 00:00:02,000\r\n" +
            "Line one\r\n" +
            "Line two\r\n" +
            "\r\n" +
            "2\r\n" +
            "00:00:03,000 --> 00:00:04,000\r\n" +
            "Second cue\r\n"

        val cues = parse(crlf)
        assertEquals(2, cues.size)
        assertEquals(parse(crlf.replace("\r\n", "\n")), cues)
        assertEquals("Line one\nLine two", cues[0].plainText)
        assertTrue(
            cues.none { '\r' in it.plainText },
            "a carriage return left in the text renders as a box or eats the rest of the line",
        )
    }

    @Test
    fun `line endings may be mixed inside one file`() {
        // Files get concatenated and hand edited on different systems, so one file can carry all
        // three conventions at once.
        val mixed = "1\r\n" +
            "00:00:01,000 --> 00:00:02,000\r\n" +
            "First\r\n" +
            "\r\n" +
            "2\n" +
            "00:00:03,000 --> 00:00:04,000\n" +
            "Second\n" +
            "\r" +
            "3\r" +
            "00:00:05,000 --> 00:00:06,000\r" +
            "Third\r"

        val cues = parse(mixed)
        assertEquals(listOf("First", "Second", "Third"), cues.map { it.plainText })
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L), cues.map { it.startMicros })
    }

    @Test
    fun `cues without sequence numbers still parse`() {
        // Tools that write SRT from a spreadsheet or a translation memory often omit the index
        // entirely. Nothing in the format needs it.
        val none = parse(
            """
            00:00:01,000 --> 00:00:02,000
            First

            00:00:03,000 --> 00:00:04,000
            Second
            """.trimIndent(),
        )
        assertEquals(listOf("First", "Second"), none.map { it.plainText })
        assertEquals(listOf(1_000_000L, 3_000_000L), none.map { it.startMicros })

        // A file where only some cues are numbered, and the one number present is wrong.
        val partial = parse(
            """
            00:00:01,000 --> 00:00:02,000
            First

            7
            00:00:03,000 --> 00:00:04,000
            Second

            00:00:05,000 --> 00:00:06,000
            Third
            """.trimIndent(),
        )
        assertEquals(listOf("First", "Second", "Third"), partial.map { it.plainText })
        assertTrue(
            partial.none { "7" in it.plainText },
            "an index line must be dropped, not glued to the cue it belongs to",
        )
    }

    @Test
    fun `overlapping cues are kept and sorted by start time`() {
        // Two speakers talking over each other, written out of order. Both cues must survive with
        // their windows intact, because deciding what to show at once belongs to the track state.
        val cues = parse(
            """
            1
            00:00:05,000 --> 00:00:10,000
            Later

            2
            00:00:02,000 --> 00:00:07,000
            Earlier
            """.trimIndent(),
        )

        assertEquals(listOf("Earlier", "Later"), cues.map { it.plainText })
        assertEquals(listOf(2_000_000L, 5_000_000L), cues.map { it.startMicros })
        assertEquals(listOf(7_000_000L, 10_000_000L), cues.map { it.endMicros })
        assertTrue(
            cues[0].endMicros > cues[1].startMicros,
            "the overlap is the file's meaning and must not be trimmed away",
        )
    }

    @Test
    fun `a malformed timing line is skipped without losing the rest of the file`() {
        val cues = parse(
            """
            1
            00:00:01,000 --> 00:00:02,000
            Good one

            2
            00:00:03,000 -> 00:00:04,000
            Broken arrow

            3
            0X:00:05,000 --> 00:00:06,000
            Broken start

            4
            00:00:07,000 --> 9Z:00:08,000
            Broken end

            5
            00:00:09,000 --> 00:00:10,000
            Good two
            """.trimIndent(),
        )

        assertEquals(
            listOf("Good one", "Good two"),
            cues.map { it.plainText },
            "one unreadable timing line costs its own cue and nothing else",
        )
        assertEquals(listOf(1_000_000L, 9_000_000L), cues.map { it.startMicros })
        assertTrue(cues.none { "Broken" in it.plainText }, "text with no usable window has no place to go")
    }

    @Test
    fun `an end before its start resolves to the next cue or the documented default`() {
        // Some tools write the two timestamps the wrong way round. Throwing the text away loses
        // dialogue, and an end equal to its start never DISPLAYS either, because the selector
        // requires the time strictly before the end. The open end resolves.
        val followed = parse(
            """
            1
            00:00:10,000 --> 00:00:05,000
            Backwards

            2
            00:00:12,000 --> 00:00:13,000
            Next
            """.trimIndent(),
        )
        assertEquals(10_000_000L, followed.first().startMicros)
        assertEquals(12_000_000L, followed.first().endMicros, "the next cue's start closes it")

        val last = parse(
            """
            1
            00:00:10,000 --> 00:00:05,000
            Backwards
            """.trimIndent(),
        ).single()
        assertEquals(10_000_000L, last.startMicros)
        assertEquals(
            10_000_000L + SubRipParser.OPEN_CUE_DEFAULT_MICROS,
            last.endMicros,
            "with nothing following, the documented default closes it",
        )
        assertEquals("Backwards", last.plainText)
    }

    @Test
    fun `open ends resolve past a run of cues that share one start`() {
        // Three cues start together and none has a length. The next DISTINCT start closes all
        // three, and the one after that closes itself with the default. The resolution is one
        // backward pass; it used to copy the tail of the list once per open cue.
        val cues = parse(
            """
            1
            00:00:01,000 --> 00:00:01,000
            A

            2
            00:00:01,000 --> 00:00:00,500
            B

            3
            00:00:01,000 --> 00:00:01,000
            C

            4
            00:00:04,000 --> 00:00:04,000
            D
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "C", "D"), cues.map { it.plainText })
        assertEquals(
            listOf(4_000_000L, 4_000_000L, 4_000_000L, 4_000_000L + SubRipParser.OPEN_CUE_DEFAULT_MICROS),
            cues.map { it.endMicros },
        )
    }

    @Test
    fun `entities decode after tag handling`() {
        val cue = parse(
            """
            1
            00:00:01,000 --> 00:00:02,000
            Tom &amp; Jerry say &lt;i&gt; is literal&nbsp;here
            """.trimIndent(),
        ).single()
        assertEquals("Tom & Jerry say <i> is literal here", cue.plainText)
    }
}
