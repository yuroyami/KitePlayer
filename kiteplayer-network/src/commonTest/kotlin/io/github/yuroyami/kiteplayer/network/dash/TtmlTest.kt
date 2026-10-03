package io.github.yuroyami.kiteplayer.network.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** TTML documents read as cues, and cues written as WebVTT (#402). */
class TtmlTest {

    private fun tt(body: String, parameters: String = "", head: String = "") =
        """<?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling"
            xmlns:ttp="http://www.w3.org/ns/ttml#parameter" $parameters><head>$head</head><body>$body</body></tt>"""

    private fun cues(xml: String, offsetMicros: Long = 0) = Ttml.cues(xml, offsetMicros).map { Triple(it.startMicros, it.endMicros, it.text) }

    @Test
    fun clockTimesLineBreaksAndWhitespace() {
        val xml = tt(
            """<div>
                <p begin="00:00:01.500" end="00:00:03.000">Hello
                   world<br/>second   line </p>
                <p begin="00:00:00.250" end="00:00:01">First</p>
            </div>""",
        )
        assertEquals(
            listOf(Triple(250_000L, 1_000_000L, "First"), Triple(1_500_000L, 3_000_000L, "Hello world\nsecond line")),
            cues(xml),
        )
    }

    @Test
    fun offsetTimesInEveryUnit() {
        val xml = tt(
            """<div>
                <p begin="2s" end="2500ms">a</p>
                <p begin="0.001h" dur="0.05m">b</p>
                <p begin="50f" end="00:00:02:12">c</p>
                <p begin="30000000t" end="35000000t">d</p>
            </div>""",
            parameters = """ttp:frameRate="25" ttp:tickRate="10000000"""",
        )
        assertEquals(
            listOf(
                Triple(2_000_000L, 2_500_000L, "a"),
                Triple(2_000_000L, 2_480_000L, "c"),
                Triple(3_000_000L, 3_500_000L, "d"),
                Triple(3_600_000L, 6_600_000L, "b"),
            ),
            cues(xml),
        )
    }

    @Test
    fun aDropFrameRateCountsFramesAtItsTrueRate() {
        val xml = tt("""<div><p begin="00:00:00:00" end="00:00:01:00">x</p></div>""", """ttp:frameRate="30" ttp:frameRateMultiplier="1000 1001"""")
        assertEquals(listOf(Triple(0L, 1_000_000L, "x")), cues(xml), "a whole second is a second whatever the frame rate")
        val frames = tt("""<div><p begin="0f" end="30f">x</p></div>""", """ttp:frameRate="30" ttp:frameRateMultiplier="1000 1001"""")
        assertEquals(1_001_000L, cues(frames).single().second)
    }

    @Test
    fun aParagraphsTimeNestsInItsParentsAndEndsWithThem() {
        val xml = tt(
            """<div begin="10s"><p begin="1s" end="2s">inside</p></div>
               <div begin="20s" end="25s"><p begin="4s" end="9s">clipped</p><p begin="1s">until the div ends</p></div>""",
        )
        assertEquals(
            listOf(
                Triple(11_000_000L, 12_000_000L, "inside"),
                Triple(21_000_000L, 25_000_000L, "until the div ends"),
                Triple(24_000_000L, 25_000_000L, "clipped"),
            ),
            cues(xml),
        )
    }

    @Test
    fun aParagraphWithNoEndAnywhereIsSkipped() {
        assertEquals(emptyList(), cues(tt("""<div><p begin="1s">for ever</p></div>""")))
    }

    @Test
    fun italicBoldAndUnderlineComeFromAttributesAndNamedStyles() {
        val xml = tt(
            """<div>
                <p begin="0s" end="1s">a <span style="it">b</span> <span tts:fontWeight="bold" tts:textDecoration="underline">c</span></p>
                <p begin="1s" end="2s" style="both">whole <span tts:fontStyle="normal">plain</span></p>
            </div>""",
            head = """<styling>
                <style xml:id="it" tts:fontStyle="italic"/>
                <style xml:id="bd" tts:fontWeight="bold"/>
                <style xml:id="both" style="it bd"/>
            </styling>""",
        )
        // WebVTT cannot turn italic off inside <i>, so each run of text carries its own tags. The
        // span turns only italic off, so its text stays bold.
        assertEquals(listOf("a <i>b</i> <b><u>c</u></b>", "<i><b>whole </b></i><b>plain</b>"), cues(xml).map { it.third })
    }

    @Test
    fun textThatLooksLikeMarkupIsEscaped() {
        val xml = tt("""<div><p begin="0s" end="1s">a &lt; b &amp; c &gt; d</p></div>""")
        assertEquals("a &lt; b &amp; c &gt; d", cues(xml).single().third)
    }

    @Test
    fun anOffsetMovesEveryCue() {
        val xml = tt("""<div><p begin="1s" end="2s">x</p></div>""")
        assertEquals(listOf(Triple(61_000_000L, 62_000_000L, "x")), cues(xml, offsetMicros = 60_000_000L))
    }

    @Test
    fun aDocumentThatIsNotTtmlIsRefused() {
        assertFailsWith<IllegalArgumentException> { Ttml.cues("<MPD/>") }
        assertFailsWith<IllegalArgumentException> { Ttml.cues(tt("""<div><p begin="soon" end="1s">x</p></div>""")) }
    }

    @Test
    fun cuesAreWrittenAsOneWebVttDocument() {
        val vtt = webVtt(listOf(TimedCue(0, 1_500_000, "one"), TimedCue(3_723_004_000, 3_724_000_000, "two\n<i>three</i>")))
        assertEquals(
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\none\n\n01:02:03.004 --> 01:02:04.000\ntwo\n<i>three</i>\n\n",
            vtt,
        )
    }
}
