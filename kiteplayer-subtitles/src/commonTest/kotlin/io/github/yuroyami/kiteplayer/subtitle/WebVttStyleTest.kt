package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WebVTT keeps its colours and styles: the standard's colour classes and a file's `::cue` rules (#498). */
class WebVttStyleTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val yellow = 0xFFFFFF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val lime = 0xFF00FF00.toInt()

    private fun file(style: String?, vararg cues: String): String = buildString {
        append("WEBVTT\n\n")
        if (style != null) append("STYLE\n").append(style).append("\n\n")
        cues.forEachIndexed { n, cue -> append("00:00:0$n.000 --> 00:00:0$n.900\n").append(cue).append("\n\n") }
    }

    /** The one cue of [vtt], as its spans' text and style. */
    private fun spans(vtt: String): List<StyledSpan> = WebVttParser.parse(vtt).single().spans

    private fun colourOf(spans: List<StyledSpan>, text: String): Int = spans.first { it.text == text }.style.primaryColor

    @Test
    fun theStandardColourClassesColourTextAndBackground() {
        val spans = spans(file(null, "plain <c.yellow>yellow</c> <c.bg_blue>boxed</c> <c.lime.bg_black>both</c>"))
        assertEquals(white, colourOf(spans, "plain "))
        assertEquals(yellow, colourOf(spans, "yellow"))
        assertEquals(blue, spans.first { it.text == "boxed" }.style.backgroundColor)
        val both = spans.first { it.text == "both" }.style
        assertEquals(lime, both.primaryColor)
        assertEquals(0xFF000000.toInt(), both.backgroundColor)
    }

    @Test
    fun aStyleBlockColoursAClassAVoiceAndEveryCue() {
        val style = """
            ::cue { background-color: rgba(0, 0, 0, 0.5); }
            ::cue(.loud) { color: red; font-weight: bold; }
            ::cue(v[voice="Esme"]) { color: #0f0; font-style: italic; }
        """.trimIndent()
        val spans = spans(file(style, "<c.loud>Stop!</c> <v Esme>It's me</v> <v Fred>not me</v>"))
        val loud = spans.first { it.text == "Stop!" }.style
        assertEquals(red, loud.primaryColor)
        assertTrue(loud.bold)
        val esme = spans.first { it.text == "It's me" }.style
        assertEquals(lime, esme.primaryColor)
        assertTrue(esme.italic)
        assertEquals(white, colourOf(spans, "not me"))
        // Every span has the half black box of ::cue.
        assertTrue(spans.all { it.style.backgroundColor == 0x80000000.toInt() }, "spans: $spans")
    }

    @Test
    fun aRuleForACueIdentifierStylesOnlyThatCue() {
        val vtt = "WEBVTT\n\nSTYLE\n::cue(#intro) { color: yellow }\n\nintro\n00:00:00.000 --> 00:00:01.000\nFirst\n\n" +
            "00:00:01.000 --> 00:00:02.000\nSecond\n"
        val cues = WebVttParser.parse(vtt)
        assertEquals(listOf(yellow, white), cues.map { it.spans.single().style.primaryColor })
    }

    @Test
    fun theFilesRulesWinOverTheStandardClassesAndTheMoreSpecificRuleWins() {
        val style = """
            ::cue(.yellow) { color: red }
            ::cue(.a.b) { color: blue }
            ::cue(.a) { color: lime }
            ::cue(c.a) { text-decoration: underline }
        """.trimIndent()
        val spans = spans(file(style, "<c.yellow>y</c> <c.a.b>ab</c> <c.a>a</c> <v.a>v</v>"))
        assertEquals(red, colourOf(spans, "y"))
        assertEquals(blue, colourOf(spans, "ab"))
        assertEquals(lime, colourOf(spans, "a"))
        assertTrue(spans.first { it.text == "a" }.style.underline)
        // A rule for `c.a` does not reach a `v` with the same class.
        assertEquals(false, spans.first { it.text == "v" }.style.underline)
    }

    @Test
    fun aRuleWithAnythingItCannotCarryIsIgnoredWhole() {
        val style = """
            ::cue(.shadow) { color: red; text-shadow: 1px 1px black }
            ::cue(.past), ::cue(:past) { color: red }
            ::cue(.odd) { color: notacolour }
            ::cue(.px) { color: red; font-size: 16px }
            @media (min-width: 100px) { ::cue(.media) { color: red } }
            /* ::cue(.comment) { color: red } */
            ::cue(.ok) { color: red }
        """.trimIndent()
        val spans = spans(file(style, "<c.shadow>s</c><c.past>p</c><c.odd>o</c><c.px>x</c><c.media>m</c><c.comment>c</c><c.ok>k</c>"))
        for (text in listOf("s", "p", "o", "x", "m", "c")) assertEquals(white, colourOf(spans, text), "the rule for $text applied")
        assertEquals(red, colourOf(spans, "k"))
    }

    @Test
    fun aFontSizeIsAFactorOnTheSizeAroundIt() {
        val style = "::cue(.big) { font-size: 150% }\n::cue(.bigger) { font-size: 2em }"
        val spans = spans(file(style, "n <c.big>b <c.bigger>bb</c></c>"))
        assertEquals(1f, spans.first { it.text == "n " }.style.relativeSize)
        assertEquals(1.5f, spans.first { it.text == "b " }.style.relativeSize)
        assertEquals(3f, spans.first { it.text == "bb" }.style.relativeSize)
    }

    @Test
    fun coloursReadInEveryFormCssWritesThem() {
        for ((value, argb) in listOf(
            "#ff0000" to red, "#F00" to red, "#ff000080" to 0x80FF0000.toInt(), "#f008" to 0x88FF0000.toInt(),
            "rgb(255, 0, 0)" to red, "rgb(255 0 0)" to red, "rgba(255,0,0,0.5)" to 0x80FF0000.toInt(),
            "rgb(100%, 0%, 0%)" to red, "Red" to red, "transparent" to 0,
        )) {
            assertEquals(argb, VttStyleSheet.cssColor(value), value)
        }
        for (bad in listOf("#ff0000f", "#ff", "#gg0000", "rgb(1,2)", "rgba(1,2,3,x)", "hsl(0, 100%, 50%)", "notacolour")) {
            assertEquals(null, VttStyleSheet.cssColor(bad), bad)
        }
    }

    @Test
    fun aTrackHeaderStylesTheCuesOfAContainerTrack() {
        val track = WebVttParser.trackParser("WEBVTT\n\nSTYLE\n::cue(.loud) { color: red }\n\nNOTE a note\n")
        val spans = track.parseCueBody("<c.loud>Stop!</c> and go")
        assertEquals(red, colourOf(spans, "Stop!"))
        assertEquals(white, colourOf(spans, " and go"))
        assertEquals(white, WebVttParser.trackParser("").parseCueBody("<c.loud>x</c>").single().style.primaryColor)
    }

    @Test
    fun rubyAndLanguageSpansKeepTheirStyles() {
        val spans = spans(file("::cue(rt) { color: yellow }", "<c.red><ruby>漢字<rt>かんじ</rt></ruby></c>"))
        assertEquals("漢字(かんじ)", spans.joinToString("") { it.text })
        assertEquals(red, colourOf(spans, "漢字"))
        assertEquals(yellow, colourOf(spans, "(かんじ)"))
    }
}
