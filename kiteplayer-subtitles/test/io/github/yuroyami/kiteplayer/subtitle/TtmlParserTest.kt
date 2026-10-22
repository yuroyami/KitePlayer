package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TTML and DFXP documents read as cues with their regions and styles (#492). */
class TtmlParserTest {

    private fun tt(body: String, parameters: String = "", head: String = "", namespace: String = "http://www.w3.org/ns/ttml") =
        """<?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="$namespace" xmlns:tts="$namespace#styling"
            xmlns:ttp="$namespace#parameter" $parameters><head>$head</head><body>$body</body></tt>"""

    private val SubtitleCue.Text.text: String get() = plainText

    @Test
    fun aDocumentIsKnownByItsRootAndNamespaceNotItsName() {
        assertTrue(TtmlParser.isTtml(tt("")))
        assertTrue(TtmlParser.isTtml("﻿<!-- made by hand --><!DOCTYPE tt [ <!ENTITY a 'b'> ]>\n" + tt("")), "a mark, a comment and a declaration go first")
        assertTrue(TtmlParser.isTtml(tt("", namespace = "http://www.w3.org/2006/10/ttaf1")), "DFXP")
        assertTrue(TtmlParser.isTtml(tt("", namespace = "http://www.w3.org/2006/04/ttaf1")), "the older DFXP drafts")
        assertTrue(TtmlParser.isTtml("""<tt:tt xmlns:tt='http://www.w3.org/ns/ttml'><tt:body/></tt:tt>"""), "a prefixed root")
        assertFalse(TtmlParser.isTtml("""<tt xmlns="urn:something:else"><body/></tt>"""), "another namespace")
        assertFalse(TtmlParser.isTtml("""<tt:tt xmlns="http://www.w3.org/ns/ttml" xmlns:tt="urn:other"/>"""), "the prefix's namespace decides")
        assertFalse(TtmlParser.isTtml("""<MPD xmlns="http://www.w3.org/ns/ttml"/>"""), "another root")
        assertFalse(TtmlParser.isTtml("1\n00:00:01,000 --> 00:00:02,000\n<i>SubRip</i>\n"))
        assertFalse(TtmlParser.isTtml("WEBVTT\n\n00:01.000 --> 00:02.000\n<tt>"))
    }

    @Test
    fun paragraphsAreCuesInTimeOrderWithTheirText() {
        val cues = TtmlParser.parse(
            tt(
                """<div>
                    <p begin="00:00:01.500" end="00:00:03.000">Hello
                       world<br/>second   line </p>
                    <p begin="00:00:00.250" end="00:00:01">First</p>
                </div>""",
            ),
        )
        assertEquals(listOf(250_000L to "First", 1_500_000L to "Hello world\nsecond line"), cues.map { it.startMicros to it.text })
        assertEquals(listOf(1_000_000L, 3_000_000L), cues.map { it.endMicros })
        assertTrue(cues.all { it.layout.region == null && it.layout.alignment == CueAlignment.BottomCenter }, "no region: at the bottom, centred as a SubRip line")
        val left = TtmlParser.parse(tt("""<p begin="0s" end="1s" tts:textAlign="start">x</p>""")).single()
        assertEquals(CueAlignment.BottomLeft, left.layout.alignment, "unless it says where")
    }

    @Test
    fun aSequenceTimesItsChildrenOneAfterAnother() {
        val cues = TtmlParser.parse(
            tt("""<div timeContainer="seq"><p dur="1s">one</p><p dur="2s">two</p><p begin="1s" dur="1s">three</p></div>"""),
        )
        assertEquals(
            listOf(Triple(0L, 1_000_000L, "one"), Triple(1_000_000L, 3_000_000L, "two"), Triple(4_000_000L, 5_000_000L, "three")),
            cues.map { Triple(it.startMicros, it.endMicros, it.text) },
        )
    }

    @Test
    fun stylesReachEachSpanAndSpacesCollapseAcrossThem() {
        val cue = TtmlParser.parse(
            tt(
                """<p begin="0s" end="1s" style="yellow">a <span tts:fontStyle="italic" tts:color="#00ff0080"> b</span>
                   <span tts:textDecoration="underline lineThrough" tts:fontWeight="bold" tts:backgroundColor="rgba(0,0,0,128)">c</span></p>""",
                head = """<styling><style xml:id="yellow" tts:color="yellow" tts:fontFamily="'Noto Sans', sansSerif"/></styling>""",
            ),
        ).single()
        assertEquals(listOf("a ", "b", " ", "c"), cue.spans.map { it.text })
        val (a, b, space, c) = cue.spans.map { it.style }
        assertEquals(0xFFFFFF00.toInt(), a.primaryColor)
        assertEquals("Noto Sans", a.fontFamily)
        assertTrue(b.italic && b.primaryColor == 0x8000FF00.toInt(), "#rrggbbaa is green at half")
        assertEquals(a, space, "the space between the spans is the paragraph's")
        assertTrue(c.bold && c.underline && c.strikeThrough && !c.italic)
        assertEquals(0x80000000.toInt(), c.backgroundColor)
        assertEquals(0, a.backgroundColor, "a background is the span's own, not inherited")
    }

    @Test
    fun aRegionPlacesItsParagraphsInDocumentOrder() {
        val head = """<layout>
            <region xml:id="bottom" tts:origin="10% 70%" tts:extent="80% 20%" tts:padding="5% 10%"
                tts:displayAlign="after" tts:textAlign="center" tts:backgroundColor="#00000080"
                tts:showBackground="whenActive" tts:overflow="visible" tts:zIndex="2"/>
            <region xml:id="top" tts:origin="10% 5%" tts:extent="80% 20%"/>
        </layout>"""
        val cues = TtmlParser.parse(
            tt(
                """<div region="bottom">
                    <p begin="1s" end="3s">second in time, first in the document</p>
                    <p begin="0s" end="3s">first in time</p>
                    <p begin="0s" end="1s" region="top" tts:textAlign="end">on top</p>
                </div>""",
                head = head,
            ),
        )
        val (topCue, firstInTime, firstInDocument) = cues.sortedBy { it.text }.let { listOf(it[1], it[0], it[2]) }
        val bottom = firstInDocument.layout.region!!
        assertEquals(CueRegion("bottom", 0.1f, 0.7f, 0.8f, 0.2f, CueInsets(0.1f, 0.05f, 0.1f, 0.05f), CueDisplayAlign.After, 0x80000000.toInt(), CueShowBackground.WhenActive, clip = false), bottom)
        assertEquals(bottom, firstInTime.layout.region, "one region is one value")
        assertTrue(firstInDocument.layout.regionOrder < firstInTime.layout.regionOrder, "document order, not time order")
        assertEquals(CueAlignment.TopCenter, firstInDocument.layout.alignment, "the region's textAlign reaches its text")
        assertEquals(2, firstInDocument.layer, "zIndex is the layer")
        assertEquals(CueWrap.None, firstInDocument.layout.wrap, "a region's lines fill as its author laid them out")
        assertEquals("top", topCue.layout.region?.id)
        assertEquals(CueAlignment.TopRight, topCue.layout.alignment)
        assertEquals(CueRegion("top", 0.1f, 0.05f, 0.8f, 0.2f), topCue.layout.region)
    }

    @Test
    fun cellsAndPixelsResolveAgainstTheRoot() {
        val head = """<layout>
            <region xml:id="cells" tts:origin="4c 12c" tts:extent="24c 3c"/>
            <region xml:id="pixels" tts:origin="192px 864px" tts:extent="1536px 108px" tts:padding="10.8px 0px"/>
        </layout>"""
        val body = """<p begin="0s" end="1s" region="cells" tts:fontSize="1c">a</p>
            <p begin="0s" end="1s" region="pixels" tts:fontSize="54px">b<span tts:fontSize="50%">c</span></p>"""
        val cues = TtmlParser.parse(tt(body, """ttp:cellResolution="40 20" tts:extent="1920px 1080px"""", head))
        val cells = cues.first { it.text == "a" }
        assertEquals(CueRegion("cells", 0.1f, 0.6f, 0.6f, 0.15f), cells.layout.region)
        val pixels = cues.first { it.text == "bc" }
        val box = pixels.layout.region!!
        assertEquals(listOf(0.1f, 0.8f, 0.8f, 0.1f), listOf(box.left, box.top, box.width, box.height))
        // 10.8 of 1080 pixels is a tenth of the region's 108.
        assertEquals(0.1f, box.padding.top, 1e-5f)
        assertEquals(0.1f, box.padding.bottom, 1e-5f)
        assertEquals(0f, box.padding.left)
        // A size is a fraction of the root's height, carried against an authored height.
        val authored = pixels.layout.authoredHeight!!.toFloat()
        assertEquals(0.05f, cells.spans.single().style.fontSizePx!! / cells.layout.authoredHeight!!, 1e-4f, "1 of 20 rows")
        assertEquals(0.05f, pixels.spans[0].style.fontSizePx!! / authored, 1e-4f, "54 of 1080 pixels")
        assertEquals(0.025f, pixels.spans[1].style.fontSizePx!! / authored, 1e-4f, "half of its parent's size")
    }

    @Test
    fun aRegionThatCannotBePlacedIsNotUsed() {
        val head = """<layout><region xml:id="px" tts:origin="0px 0px" tts:extent="100px 100px"/></layout>"""
        val cue = TtmlParser.parse(tt("""<p begin="0s" end="1s" region="px">x</p>""", head = head)).single()
        assertNull(cue.layout.region, "pixels with no root extent")
        assertEquals(CueAlignment.BottomCenter, cue.layout.alignment, "so it goes to the bottom")
        val unknown = TtmlParser.parse(tt("""<p begin="0s" end="1s" region="nowhere">x</p>""")).single()
        assertNull(unknown.layout.region)
    }

    @Test
    fun aBackgroundThatAlwaysShowsIsActiveForTheRegionsTime() {
        val head = """<layout>
            <region xml:id="box" tts:origin="0% 80%" tts:extent="100% 20%" tts:backgroundColor="black"/>
            <region xml:id="timed" begin="5s" end="8s" tts:backgroundColor="black"/>
            <region xml:id="clear" tts:origin="0% 0%" tts:extent="100% 20%"/>
        </layout>"""
        val cues = TtmlParser.parse(tt("""<p begin="1s" end="2s" region="box">x</p><p begin="3s" end="4s" region="clear">y</p>""", head = head))
        val empty = cues.filter { it.spans.isEmpty() }.associateBy { it.layout.region!!.id }
        assertEquals(setOf("box", "timed"), empty.keys, "a transparent background needs no cue")
        assertEquals(0L to 4_000_000L, empty.getValue("box").let { it.startMicros to it.endMicros }, "the whole document")
        assertEquals(5_000_000L to 8_000_000L, empty.getValue("timed").let { it.startMicros to it.endMicros }, "its own time")
    }

    @Test
    fun aRightToLeftParagraphStartsAtTheRight() {
        val cues = TtmlParser.parse(
            tt("""<p begin="0s" end="1s" tts:direction="rtl" tts:textAlign="start">שלום</p><p begin="1s" end="2s" tts:direction="rtl" tts:textAlign="end">x</p>"""),
        )
        assertEquals(listOf(CueAlignment.BottomRight, CueAlignment.BottomLeft), cues.map { it.layout.alignment })
    }

    @Test
    fun noWrapAndNoOutlineAreKept() {
        val cue = TtmlParser.parse(tt("""<p begin="0s" end="1s" tts:wrapOption="noWrap" tts:textOutline="none">x</p>""")).single()
        assertEquals(CueWrap.Never, cue.layout.wrap)
        assertEquals(0f, cue.spans.single().style.outlineWidthPx)
        val outlined = TtmlParser.parse(tt("""<p begin="0s" end="1s" tts:textOutline="red 2px">x</p>""")).single()
        assertEquals(0xFFFF0000.toInt(), outlined.spans.single().style.outlineColor)
    }

    @Test
    fun aDocumentItCannotReadGivesNoCuesOrARefusal() {
        assertEquals(emptyList(), TtmlParser.parse("<tt><body><p>"))
        assertEquals(emptyList(), TtmlParser.parse(tt("""<p begin="soon" end="1s">x</p>""")))
        assertFailsWith<IllegalArgumentException> { TtmlParser.read("<tt><body><p>") }
        assertFailsWith<IllegalArgumentException> { TtmlParser.read("<MPD/>") }
    }

    /**
     * A style that names itself is a loop, which TTML2 (10.4.1.3) calls an error. It is cut at the
     * first repeat, so the style is read once. Followed nine levels deep instead, a style naming
     * itself ten times cost about 10^9 visits and six seconds for one cue (#408).
     */
    @Test
    fun aStyleThatNamesItselfIsReadOnce() {
        val names = List(10) { "s" }.joinToString(" ")
        val xml = tt(
            """<p begin="0s" end="1s" style="s">ok</p>""",
            head = """<styling><style xml:id="s" style="$names" tts:fontStyle="italic"/></styling>""",
        )
        val work = TtmlWork()
        assertTrue(TtmlParser.read(xml, work).single().spans.single().style.italic)
        assertEquals(1, work.styleVisits, "the style was read more than once")
    }

    /** A loop through two styles keeps what each one sets, in a fixed order, and reads each once. */
    @Test
    fun aLoopThroughTwoStylesIsCutAtItsFirstRepeat() {
        val xml = tt(
            """<p begin="0s" end="1s" style="a">ok</p>""",
            head = """<styling>
                <style xml:id="a" style="b" tts:fontStyle="italic"/>
                <style xml:id="b" style="a" tts:fontWeight="bold"/>
            </styling>""",
        )
        val work = TtmlWork()
        val style = TtmlParser.read(xml, work).single().spans.single().style
        assertTrue(style.italic && style.bold)
        assertEquals(2, work.styleVisits)
    }

    /**
     * Nine layers of four styles, each naming all four of the layer below, is a legal document
     * whose chains fan out to 4^9 paths. Each style is still read once per document, and every cue
     * reuses what the first one resolved.
     */
    @Test
    fun aWideStyleGraphIsReadOncePerStyleForTheWholeDocument() {
        val layers = 9
        val styling = buildString {
            for (layer in 0 until layers) {
                for (i in 0 until 4) {
                    val below = if (layer + 1 < layers) (0 until 4).joinToString(" ") { "s${layer + 1}_$it" } else ""
                    val own = if (layer + 1 == layers && i == 3) """ tts:textDecoration="underline"""" else ""
                    append("""<style xml:id="s${layer}_$i" style="$below"$own/>""")
                }
            }
        }
        val body = (0 until 100).joinToString("") { """<p begin="${it}s" end="${it + 1}s" style="s0_0">c$it</p>""" }
        val work = TtmlWork()
        val cues = TtmlParser.read(tt(body, head = "<styling>$styling</styling>"), work)
        assertEquals(100, cues.size)
        assertTrue(cues.first().spans.single().style.underline, "the deepest style's underline reaches the cue")
        // s0_0, then the four styles of each layer below it.
        assertEquals(1 + (layers - 1) * 4, work.styleVisits, "each style is read once, for every cue together")
    }
}
