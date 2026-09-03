package io.github.yuroyami.kiteplayer.subtitle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Subtitle files from the internet are not well formed. Every inline fixture the parser tests use
 * is a seed here; each seed is mutated two thousand times with a fixed random seed, so a failure
 * is reproducible and the mutation that found it can be pinned as a named test.
 *
 * Three invariants, the same for every parser: parsing never throws, every cue ends at or after
 * it starts, and one parse takes under a second of wall time (a spin is a hang; virtual time
 * would hide it).
 */
class ParserRobustnessTest {

    @Test
    fun `subrip survives two thousand mutations of every fixture`() =
        fuzz("SubRip", SUBRIP_SEEDS) { SubRipParser.parse(it) }

    @Test
    fun `webvtt survives two thousand mutations of every fixture`() =
        fuzz("WebVTT", WEBVTT_SEEDS) { WebVttParser.parse(it) }

    @Test
    fun `ass documents survive two thousand mutations of every fixture`() =
        fuzz("ASS", ASS_SEEDS) { AssParser.parse(it) }

    @Test
    fun `ass embedded events survive two thousand mutations of every fixture`() {
        val track = AssParser.trackParser(ASS_HEADER)
        fuzz("ASS event", ASS_EVENT_LINES) { listOfNotNull(track.parseEvent(it, 0L, 2_000_000L)) }
    }

    @Test
    fun `an ass header survives two thousand mutations before it parses any event`() =
        fuzz("ASS header", listOf(ASS_HEADER, SSA_DOCUMENT)) {
            listOfNotNull(AssParser.trackParser(it).parseEvent("0,0,Default,,0,0,0,,text", 0L, 1L))
        }

    private fun fuzz(name: String, seeds: List<String>, parse: (String) -> List<SubtitleCue>) {
        val random = Random(1)
        for ((seedIndex, seed) in seeds.withIndex()) {
            repeat(MUTATIONS_PER_SEED) { round ->
                val (op, mutated) = mutate(seed, random)
                val where = "$name seed $seedIndex round $round op $op"
                val mark = TimeSource.Monotonic.markNow()
                val cues = try {
                    parse(mutated)
                } catch (t: Throwable) {
                    fail("$where threw $t\n--- input ---\n${mutated.escaped()}\n---", t)
                }
                val elapsed = mark.elapsedNow()
                assertTrue(elapsed < 1.seconds, "$where took $elapsed\n--- input ---\n${mutated.escaped()}\n---")
                for (cue in cues) {
                    assertTrue(
                        cue.endMicros >= cue.startMicros,
                        "$where produced a cue ending before it starts: ${cue.startMicros}..${cue.endMicros}" +
                            "\n--- input ---\n${mutated.escaped()}\n---",
                    )
                }
            }
        }
    }

    private enum class Op { FlipBit, Truncate, DuplicateLine, SwapLineEndings, InsertBom, InsertCodePoint, DeleteLine }

    private fun mutate(seed: String, random: Random): Pair<Op, String> {
        val op = Op.entries[random.nextInt(Op.entries.size)]
        val out = when (op) {
            Op.FlipBit -> if (seed.isEmpty()) seed else {
                val at = random.nextInt(seed.length)
                val flipped = (seed[at].code xor (1 shl random.nextInt(8))).toChar()
                seed.substring(0, at) + flipped + seed.substring(at + 1)
            }
            Op.Truncate -> seed.take(random.nextInt(seed.length + 1))
            Op.DuplicateLine -> {
                val lines = seed.lines()
                val at = random.nextInt(lines.size)
                (lines.take(at + 1) + lines[at] + lines.drop(at + 1)).joinToString("\n")
            }
            Op.SwapLineEndings -> if ("\r\n" in seed) seed.replace("\r\n", "\n") else seed.replace("\n", "\r\n")
            Op.InsertBom -> seed.insertAt(random.nextInt(seed.length + 1), "\uFEFF")
            Op.InsertCodePoint -> {
                var codePoint: Int
                do codePoint = random.nextInt(0x80, 0x110000) while (codePoint in 0xD800..0xDFFF)
                seed.insertAt(random.nextInt(seed.length + 1), codePointToString(codePoint))
            }
            Op.DeleteLine -> {
                val lines = seed.lines()
                if (lines.size <= 1) "" else {
                    val at = random.nextInt(lines.size)
                    (lines.take(at) + lines.drop(at + 1)).joinToString("\n")
                }
            }
        }
        return op to out
    }

    private fun String.insertAt(at: Int, piece: String): String = substring(0, at) + piece + substring(at)

    private fun codePointToString(codePoint: Int): String =
        if (codePoint < 0x10000) codePoint.toChar().toString() else {
            val v = codePoint - 0x10000
            "${(0xD800 + (v shr 10)).toChar()}${(0xDC00 + (v and 0x3FF)).toChar()}"
        }

    /** Every char that would not survive a copy and paste into a regression test is spelled out. */
    private fun String.escaped(): String = buildString {
        for (c in this@escaped) {
            when {
                c == '\n' -> append("\\n\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\\' -> append("\\\\")
                c.code < 0x20 || c.code > 0x7E -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }

    private companion object {
        const val MUTATIONS_PER_SEED = 2_000

        val SUBRIP_SEEDS: List<String> = listOf(
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
            "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nHello",
            "\uFEFF00:00:01,000 --> 00:00:02,000\nHello",
            "1\r\n00:00:01,000 --> 00:00:02,000\r\nLine one\r\nLine two\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond cue\r\n",
            "1\r\n00:00:01,000 --> 00:00:02,000\r\nFirst\r\n\r\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n\r3\r00:00:05,000 --> 00:00:06,000\rThird\r",
            "00:00:01,000 --> 00:00:02,000\nFirst\n\n00:00:03,000 --> 00:00:04,000\nSecond",
            "00:00:01,000 --> 00:00:02,000\nFirst\n\n7\n00:00:03,000 --> 00:00:04,000\nSecond\n\n00:00:05,000 --> 00:00:06,000\nThird",
            "1\n00:00:05,000 --> 00:00:10,000\nLater\n\n2\n00:00:02,000 --> 00:00:07,000\nEarlier",
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
            "1\n00:00:10,000 --> 00:00:05,000\nBackwards\n\n2\n00:00:12,000 --> 00:00:13,000\nNext",
            "1\n00:00:10,000 --> 00:00:05,000\nBackwards",
            "1\n00:00:01,000 --> 00:00:02,000\nTom &amp; Jerry say &lt;i&gt; is literal&nbsp;here",
            "1\n00:00:01,000 --> 00:00:02,000\n<font color=\"#FF0000\"><b>Bold <i>and</b> red</i></font> <u>under</u> <s>gone</s> <unknown>kept",
        )

        val WEBVTT_SEEDS: List<String> = listOf(
            "WEBVTT\n\n00:00:00.500 --> 00:00:03.000\nHello from KitePlayer\n\n00:00:03.500 --> 00:00:06.000\nSecond cue",
            "\uFEFFWEBVTT - a description\n\nchapter-1\n00:07.000 --> 00:09.500\nShort form\n",
            """
            WEBVTT

            NOTE this looks like a cue
            00:00:01.000 --> 00:00:02.000 inside a note

            STYLE
            ::cue { color: red }

            00:00:04.000 --> 00:00:05.000
            The real cue
            """.trimIndent(),
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Fred>Hi <00:00:01.500>there <c.yellow>friend</c></v>\n",
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nplain <b>bold</b> <i>italic</i>\n",
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:end position:90%\nRight side\n",
            "",
            "not a subtitle file at all",
            "WEBVTT\n\n00:00:05.000 --> 00:00:01.000\nBackwards\n",
            "WEBVTT\n\nNOTEWORTHY\n00:00:01.000 --> 00:00:02.000\nVisible\n\nNOTE this really is a comment\nit runs to the blank line\n\n00:00:03.000 --> 00:00:04.000\nSecond",
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Tom>Tom &amp; Jerry &lt;3</v>",
            "WEBVTT\n\nREGION\nid:fred width:40% lines:3\n\n00:00:01.000 --> 00:00:02.000 region:fred line:0 align:start\n<c.red.bg_blue>Coloured</c>",
        )

        val ASS_HEADER: String = """
            [Script Info]
            Title: Test
            PlayResX: 1280
            PlayResY: 720

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Open Sans,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,20,20,30,1
            Style: Top,Arial,36,&H0000FFFF,&H000000FF,&H00101010,&H80000000,-1,-1,0,0,100,100,0,0,1,2,0,8,10,10,12,1
        """.trimIndent()

        val SSA_DOCUMENT: String = """
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

        val ASS_EVENT_LINES: List<String> = listOf(
            "1,0,Top,,0,0,0,,{\\b0}embedded",
            "0,0,Missing,,0,0,0,,bare text",
            "2,0,Default,,128,0,72,,{\\an7\\pos(640,100)\\c&H0000FF&\\fad(200,300)}Sign",
            "3,0,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100{\\p0}",
            "4,0,Default,,0,0,0,,{\\k20}la{\\k30}la{\\k50}la",
            "5,0,Default,,0,0,0,,line one\\Nline two\\hthree",
            "garbage",
        )

        private fun document(vararg events: String): String =
            ASS_HEADER + "\n\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                events.joinToString("\n")

        val ASS_SEEDS: List<String> = listOf(
            document("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi"),
            ASS_HEADER.replace("Title: Test", "Title: Test\nCollisions: Reverse") +
                "\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi",
            document("Dialogue: 0,0:00:01.50,0:00:04.00,Default,,0,0,0,,Hello there"),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Top,,0,0,0,,Sign text"),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,plain {\\b1\\i1}loud{\\r} calm"),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\\an7\\pos(640,100)\\c&H0000FF&\\fad(200,300)}Sign"),
            document(
                "Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,line one\\Nline two",
                "Dialogue: 0,0:00:02.00,0:00:04.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100{\\p0}",
                "Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,",
            ),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\\k20}la{\\k30}la{\\k50}la"),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Default,,128,0,72,,Margined"),
            document("Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,{\\fnArial\\fs20\\3c&H0000FF&\\4c&H00FF00&\\bord2\\shad1\\q2\\u1\\s1\\rTop}styled{\\move(1,2,3,4)}moved"),
            SSA_DOCUMENT,
        )
    }
}
