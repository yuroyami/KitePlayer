package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlMini
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The XML reader and the MPD parser against real-world manifest shapes. */
class DashManifestParserTest {

    @Test
    fun theXmlReaderHandlesTheShapesManifestsUse() {
        val root = XmlMini.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- a comment before the root -->
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
                <ns:Odd attr="a&amp;b&#33;">text &lt;kept&gt;</ns:Odd>
                <Empty/>
                <Data><![CDATA[raw <bytes> & all]]></Data>
            </MPD>
            """.trimIndent(),
        )
        assertEquals("MPD", root.name)
        assertEquals("static", root.attr("type"))
        assertEquals("a&b!", root.child("Odd")?.attr("attr"), "entities in attributes decode")
        assertEquals("text <kept>", root.child("Odd")?.text, "entities in text decode; the prefix strips")
        assertEquals(0, root.child("Empty")?.children?.size)
        assertEquals("raw <bytes> & all", root.child("Data")?.text, "CDATA passes raw")
    }

    @Test
    fun isoDurationsParse() {
        assertEquals(9_000_000L, DashManifestParser.parseIsoDurationMicros("PT9S"))
        assertEquals(90_500_000L, DashManifestParser.parseIsoDurationMicros("PT1M30.5S"))
        assertEquals(3_723_000_000L, DashManifestParser.parseIsoDurationMicros("PT1H2M3S"))
        assertEquals(86_400_000_000L, DashManifestParser.parseIsoDurationMicros("P1D"))
        assertFailsWith<IllegalArgumentException> { DashManifestParser.parseIsoDurationMicros("tomorrow") }
    }

    private val templateMpd = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
            <Period>
                <AdaptationSet contentType="video" mimeType="video/mp2t">
                    <SegmentTemplate media="seg-${'$'}RepresentationID${'$'}-${'$'}Number%03d${'$'}.ts"
                                     startNumber="1" timescale="1000" duration="2500"/>
                    <Representation id="hi" bandwidth="2000000" width="320" height="240"/>
                    <Representation id="lo" bandwidth="500000" width="160" height="120"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun aSegmentTemplateWithNumberingResolvesItsFullPlan() {
        val manifest = DashManifestParser.parse(templateMpd, "http://cdn.test/vod/movie.mpd")
        assertEquals(false, manifest.isDynamic)
        assertEquals(10_000_000L, manifest.durationMicros)
        val period = manifest.periods.single()
        val set = period.adaptationSets.single()
        assertEquals("video", set.contentType)
        val hi = set.representations.first { it.id == "hi" }

        val plan = DashManifestParser.segmentPlan(manifest, period, hi)
        assertNull(plan.initializationUrl, "no initialization declared, none invented")
        // 10s at 2.5s per segment is exactly 4 segments, width-3 numbering, base-resolved.
        assertEquals(
            listOf(
                "http://cdn.test/vod/seg-hi-001.ts",
                "http://cdn.test/vod/seg-hi-002.ts",
                "http://cdn.test/vod/seg-hi-003.ts",
                "http://cdn.test/vod/seg-hi-004.ts",
            ),
            plan.mediaUrls,
        )
    }

    @Test
    fun aSegmentTimelineDrivesCountAndTimeSubstitution() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT6S">
                <Period>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate media="s-${'$'}Time${'$'}.m4s" initialization="init.m4s" timescale="1000">
                            <SegmentTimeline>
                                <S t="0" d="2000" r="1"/>
                                <S d="1500"/>
                            </SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashManifestParser.parse(mpd, "http://cdn.test/vod/movie.mpd")
        val period = manifest.periods.single()
        val plan = DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single())
        assertEquals("http://cdn.test/vod/init.m4s", plan.initializationUrl)
        assertEquals(
            listOf("http://cdn.test/vod/s-0.m4s", "http://cdn.test/vod/s-2000.m4s", "http://cdn.test/vod/s-4000.m4s"),
            plan.mediaUrls,
            "one repeat plus one entry is three segments at their timeline times",
        )
    }

    @Test
    fun baseUrlsChainAndSegmentListsWin() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <BaseURL>http://mirror.test/content/</BaseURL>
                <Period>
                    <BaseURL>movie/</BaseURL>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate media="ignored-${'$'}Number${'$'}.ts" duration="2" timescale="1"/>
                        <Representation id="v" bandwidth="1">
                            <SegmentList>
                                <Initialization sourceURL="header.mp4"/>
                                <SegmentURL media="a.mp4"/>
                                <SegmentURL media="/absolute/b.mp4"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashManifestParser.parse(mpd, "http://cdn.test/vod/movie.mpd")
        val period = manifest.periods.single()
        val plan = DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single())
        assertEquals("http://mirror.test/content/movie/header.mp4", plan.initializationUrl)
        assertEquals(
            listOf("http://mirror.test/content/movie/a.mp4", "http://mirror.test/absolute/b.mp4"),
            plan.mediaUrls,
            "an explicit SegmentList wins over the template, and path-absolute references join the origin",
        )
    }

    @Test
    fun dynamicManifestsParseButRefuseSegmentResolutionTyped() {
        val mpd = """
            <MPD type="dynamic">
                <Period>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate media="live-${'$'}Number${'$'}.ts" duration="2" timescale="1"/>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashManifestParser.parse(mpd, "http://cdn.test/live.mpd")
        assertTrue(manifest.isDynamic)
        val period = manifest.periods.single()
        assertFailsWith<IllegalArgumentException> {
            DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single())
        }
    }
    // r="-1" is DASH's compact "repeat to the end of the period", and the plan
    // used to expand it to ZERO segments because 0..-1 is an empty range.
    @Test
    fun aNegativeRepeatExpandsToThePeriodEnd() {
        val manifest = DashManifestParser.parse(
            """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT60S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp2t">
                        <SegmentTemplate media="seg-${'$'}Number${'$'}.ts" startNumber="1" timescale="1000">
                            <SegmentTimeline>
                                <S t="0" d="2000" r="-1"/>
                            </SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
            "http://cdn/vod/movie.mpd",
        )
        val period = manifest.periods.single()
        val rep = period.adaptationSets.single().representations.single()
        val plan = DashManifestParser.segmentPlan(manifest, period, rep)
        assertEquals(30, plan.mediaUrls.size, "60 s of 2 s segments is 30 segments, got ${plan.mediaUrls.size}")
        assertEquals("http://cdn/vod/seg-1.ts", plan.mediaUrls.first())
        assertEquals("http://cdn/vod/seg-30.ts", plan.mediaUrls.last())
    }

    // P0Y0M0DT0H9M56.46S is a legal xs:duration several packagers emit, and the
    // year, month and week components used to fail the whole manifest.
    @Test
    fun verboseIsoDurationsParse() {
        assertEquals(596_460_000L, DashManifestParser.parseIsoDurationMicros("P0Y0M0DT0H9M56.46S"))
        assertEquals(3_600_000_000L, DashManifestParser.parseIsoDurationMicros("PT1H"))
        assertEquals(7L * 86_400_000_000L, DashManifestParser.parseIsoDurationMicros("P1W"))
        assertEquals(
            (365L + 30 + 1) * 86_400_000_000L,
            DashManifestParser.parseIsoDurationMicros("P1Y1M1D"),
            "years and months use the 365 and 30 day conventions",
        )
    }

    // Numeric character references above the basic plane must decode to a
    // surrogate pair, not to a truncated toChar().
    @Test
    fun supplementaryCharacterReferencesDecodeWhole() {
        val root = io.github.yuroyami.kiteplayer.network.xml.XmlMini.parse(
            """<a title="&#x1F600;&#128169;">ok</a>""",
        )
        val title = root.attr("title")!!
        assertEquals("\uD83D\uDE00\uD83D\uDCA9", title, "both references decode to pairs, got ${title.length} units")
    }

    @Test
    fun `a zero timescale is refused typed rather than dividing by it`() {
        // SEC-6: `duration * 1_000_000 / timescale` ran BEFORE the guard on the next line, so
        // timescale="0" came out as an uncaught ArithmeticException.
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
                <Period duration="PT4S">
                    <AdaptationSet contentType="video" mimeType="video/mp2t">
                        <SegmentTemplate media="s-${'$'}Number${'$'}.ts" startNumber="1" timescale="0" duration="1"/>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()
        val refusal = assertFailsWith<IllegalArgumentException> {
            DashManifestParser.parse(mpd, "http://cdn.test/vod/movie.mpd")
        }
        assertTrue("timescale must be positive" in refusal.message!!, refusal.message!!)
    }
}
