package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlMini
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The XML reader and the MPD parser against real-world manifest shapes. */
class DashManifestParserTest {

    /**
     * A repeat to the end of the Period counts that end from presentationTimeOffset, as its own t
     * does (#748). Without the offset a 60 s Period with a 10 s offset listed 25 two-second
     * segments instead of 30, and one when the offset passed the Period's length.
     */
    /**
     * A SegmentList inherits level by level, Period to Representation (#744): the Period's own list
     * was never read, and a Representation's list replaced its set's whole, losing the duration and
     * the Initialization it left to the set.
     */
    /**
     * A live SegmentList with a timeline pairs each segment still in the window with its own address
     * (#743). It paired them by their place among the ones left, so at 8 s, with a 4 s window,
     * the first address in the list took the timing of the first segment still in the window.
     */
    @Test
    fun aLiveSegmentListPairsEachTimedSegmentWithItsOwnAddress() {
        val manifest = DashManifestParser.parse(
            """<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" timeShiftBufferDepth="PT4S">
                <Period start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1"><SegmentList timescale="1">
                    <Initialization sourceURL="init.mp4"/>
                    <SegmentTimeline><S t="0" d="2" r="3"/></SegmentTimeline>
                    <SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/>
                    <SegmentURL media="s2.m4s"/><SegmentURL media="s3.m4s"/>
                  </SegmentList></Representation>
                </AdaptationSet></Period>
              </MPD>""",
            "https://cdn.test/live/movie.mpd",
            DashUrlPolicy.Default,
        )
        val period = manifest.periods.single()
        val plan = assertNotNull(
            DashManifestParser.timedPlan(manifest, period, period.adaptationSets.single().representations.single(), DashUrlPolicy.Default, 8_000_000L),
        )
        val each = plan.segments.map { it.url.substringAfterLast('/') to it.startMicros }
        assertEquals(listOf("s2.m4s" to 4_000_000L, "s3.m4s" to 6_000_000L), each)
    }

    @Test
    fun aSegmentListInheritsFromThePeriodAndTheSetAttributeByAttribute() {
        fun parse(xml: String) = DashManifestParser.parse(xml, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default)
        val fromPeriod = parse(
            """<MPD mediaPresentationDuration="PT4S"><Period>
                <SegmentList timescale="1" duration="2"><Initialization sourceURL="init.mp4"/>
                    <SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/>
                </SegmentList>
                <AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v" bandwidth="1"/></AdaptationSet>
            </Period></MPD>""",
        ).periods.single().adaptationSets.single().representations.single()
        assertEquals(listOf("https://cdn.test/vod/s0.m4s", "https://cdn.test/vod/s1.m4s"), fromPeriod.segmentUrls, "the Period's list was not read")
        assertEquals("https://cdn.test/vod/init.mp4", fromPeriod.initializationUrl)

        val partly = parse(
            """<MPD mediaPresentationDuration="PT4S"><Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <SegmentList timescale="1000" duration="2000"><Initialization sourceURL="init.mp4"/></SegmentList>
                    <Representation id="v" bandwidth="1">
                        <SegmentList><SegmentURL media="a.m4s"/><SegmentURL media="b.m4s"/></SegmentList>
                    </Representation>
                </AdaptationSet>
            </Period></MPD>""",
        ).periods.single().adaptationSets.single().representations.single()
        assertEquals(listOf("https://cdn.test/vod/a.m4s", "https://cdn.test/vod/b.m4s"), partly.segmentUrls)
        assertEquals("https://cdn.test/vod/init.mp4", partly.initializationUrl, "the set's Initialization was lost")
        assertEquals(2000L, partly.segmentList?.duration, "the set's duration was lost")
        assertEquals(1000L, partly.segmentList?.timescale)
    }

    @Test
    fun aRepeatToThePeriodsEndCountsFromThePresentationTimeOffset() {
        fun countFor(offsetSeconds: Int): Int {
            val offset = offsetSeconds * 90_000L
            val manifest = DashManifestParser.parse(
                """<MPD type="static" mediaPresentationDuration="PT60S"><Period duration="PT60S">
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <SegmentTemplate media="v-${'$'}Time${'$'}.m4s" timescale="90000" presentationTimeOffset="$offset">
                            <SegmentTimeline><S t="$offset" d="180000" r="-1"/></SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="v" bandwidth="1"/>
                    </AdaptationSet>
                </Period></MPD>""",
                "https://cdn.test/vod/movie.mpd",
                DashUrlPolicy.Default,
            )
            val period = manifest.periods.single()
            return DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single()).mediaUrls.size
        }
        assertEquals(30, countFor(10), "a 10 s offset")
        assertEquals(30, countFor(600), "a ten minute offset")
        assertEquals(30, countFor(0), "no offset")
    }

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
    fun aMalformedManifestIsRefusedWithThisModulesXmlException() {
        // The reader is the subtitle module's since #492; its refusals still reach a caller as ours.
        val unclosed = assertFailsWith<io.github.yuroyami.kiteplayer.network.xml.XmlException> {
            DashManifestParser.parse("<MPD><Period></MPD>", "https://example.com/a.mpd")
        }
        assertEquals("</MPD> closes <Period> at offset 18", unclosed.message)
        assertEquals(18, unclosed.offset)
        val deep = assertFailsWith<io.github.yuroyami.kiteplayer.network.xml.XmlException> {
            DashManifestParser.parse("<a>".repeat(XmlMini.MAX_DEPTH + 1), "https://example.com/a.mpd")
        }
        assertTrue(deep.message!!.startsWith("nested past ${XmlMini.MAX_DEPTH} elements"), "${deep.message}")
    }

    @Test
    fun isoDurationsParse() {
        assertEquals(9_000_000L, DashManifestParser.parseIsoDurationMicros("PT9S"))
        assertEquals(90_500_000L, DashManifestParser.parseIsoDurationMicros("PT1M30.5S"))
        assertEquals(3_723_000_000L, DashManifestParser.parseIsoDurationMicros("PT1H2M3S"))
        assertEquals(86_400_000_000L, DashManifestParser.parseIsoDurationMicros("P1D"))
        // Exact to the microsecond, where a double read 2,001,999 and 1,000,999 (#757).
        assertEquals(2_002_000L, DashManifestParser.parseIsoDurationMicros("PT2.002S"))
        assertEquals(1_001_000L, DashManifestParser.parseIsoDurationMicros("PT1.001S"))
        assertEquals(3_600_000_001L, DashManifestParser.parseIsoDurationMicros("PT1H0.0000019S"))
        assertEquals(5_400_000_000L, DashManifestParser.parseIsoDurationMicros("PT1.5H"))
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
    fun aManifestThatStartsWithAByteOrderMarkParsesAsWithout() {
        // An origin built on .NET writes EF BB BF before the MPD, which decodes to U+FEFF (#617).
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + templateMpd.encodeToByteArray()
        val url = "http://cdn.test/vod/movie.mpd"
        val manifest = DashDoor.manifest(marked, url, DashUrlPolicy.Default, 8L * 1024 * 1024)
        val plain = DashManifestParser.parse(templateMpd, url)
        val period = manifest.periods.single()
        val hi = period.adaptationSets.single().representations.first { it.id == "hi" }
        val plainPeriod = plain.periods.single()
        val plainHi = plainPeriod.adaptationSets.single().representations.first { it.id == "hi" }
        assertEquals(plain.durationMicros, manifest.durationMicros)
        assertEquals(
            DashManifestParser.segmentPlan(plain, plainPeriod, plainHi).mediaUrls,
            DashManifestParser.segmentPlan(manifest, period, hi).mediaUrls,
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
        // `duration * 1_000_000 / timescale` once ran BEFORE the guard on the next line, so
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

    private fun onlyPlan(mpd: String): DashSegmentPlan {
        val manifest = DashManifestParser.parse(mpd, "http://cdn.test/vod/movie.mpd")
        val period = manifest.periods.single()
        return DashManifestParser.segmentPlan(manifest, period, period.adaptationSets.single().representations.single())
    }

    // ISO/IEC 23009-1, 5.6.4: each level's BaseURL resolves against the level above it.
    @Test
    fun anAdaptationSetBaseUrlSitsBetweenThePeriodAndTheRepresentation() {
        val plan = onlyPlan(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video">
                        <BaseURL>video/</BaseURL>
                        <Representation id="v1" bandwidth="1">
                            <BaseURL>720p.mp4</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        assertEquals(listOf("http://cdn.test/vod/video/720p.mp4"), plan.mediaUrls)
    }

    // ISO/IEC 23009-1, 5.3.9.1: a lower level overrides only the attributes it sets.
    @Test
    fun aRepresentationTemplateOverridesOnlyTheAttributesItSets() {
        val plan = onlyPlan(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate timescale="1000" duration="2000" initialization="init-${'$'}RepresentationID${'$'}.mp4"/>
                        <Representation id="v1" bandwidth="1">
                            <SegmentTemplate media="${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"/>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        assertEquals("http://cdn.test/vod/init-v1.mp4", plan.initializationUrl)
        assertEquals(listOf("http://cdn.test/vod/v1-1.m4s", "http://cdn.test/vod/v1-2.m4s"), plan.mediaUrls)
    }

    @Test
    fun aPeriodTemplateReachesItsRepresentations() {
        val plan = onlyPlan(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <SegmentTemplate media="p-${'$'}Number${'$'}.m4s" timescale="1" duration="2"/>
                    <AdaptationSet contentType="video">
                        <Representation id="v1" bandwidth="1"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        assertEquals(listOf("http://cdn.test/vod/p-1.m4s", "http://cdn.test/vod/p-2.m4s"), plan.mediaUrls)
    }

    @Test
    fun aLowerSegmentTimelineReplacesTheOneAbove() {
        val plan = onlyPlan(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video">
                        <SegmentTemplate media="t-${'$'}Time${'$'}.m4s" timescale="1000">
                            <SegmentTimeline><S t="0" d="1000" r="3"/></SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="v1" bandwidth="1">
                            <SegmentTemplate>
                                <SegmentTimeline><S t="0" d="2000" r="1"/></SegmentTimeline>
                            </SegmentTemplate>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        assertEquals(listOf("http://cdn.test/vod/t-0.m4s", "http://cdn.test/vod/t-2000.m4s"), plan.mediaUrls)
    }

    /**
     * An adaptation set's timeline is read once. Every representation without a template of its
     * own shares the set's template, so the parsed manifest grows with the document rather than
     * with representations times timeline entries.
     */
    @Test
    fun representationsThatInheritATimelineShareOneTemplate() {
        val timeline = "<S d=\"1\"/>".repeat(1_000)
        val representations = (0 until 500).joinToString("") { "<Representation id=\"r$it\" bandwidth=\"1\"/>" }
        val manifest = DashManifestParser.parse(
            "<MPD type=\"static\" mediaPresentationDuration=\"PT1000S\"><Period><AdaptationSet>" +
                "<SegmentTemplate media=\"s-${'$'}Number${'$'}.m4s\" timescale=\"1\">" +
                "<SegmentTimeline>$timeline</SegmentTimeline></SegmentTemplate>" +
                representations +
                "<Representation id=\"own\" bandwidth=\"1\"><SegmentTemplate startNumber=\"7\"/></Representation>" +
                "</AdaptationSet></Period></MPD>",
            "http://cdn.test/vod/manifest.mpd",
        )
        val set = manifest.periods.single().adaptationSets.single()
        val setTemplate = checkNotNull(set.segmentTemplate)
        assertEquals(1_000, setTemplate.timeline.size)
        val inheriting = set.representations.dropLast(1)
        assertEquals(500, inheriting.size)
        for (rep in inheriting) assertSame(setTemplate, rep.segmentTemplate, "representation ${rep.id}")
        val own = checkNotNull(set.representations.last().segmentTemplate)
        assertNotSame(setTemplate, own)
        assertEquals(7L, own.startNumber, "its own attribute wins")
        assertSame(setTemplate.timeline, own.timeline, "the timeline it inherits is the set's, not a copy")
    }
}
