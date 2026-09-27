package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlMini
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The ceilings of a segment plan and of the URLs a parse builds, and the arithmetic behind them. */
class DashSegmentPlanLimitsTest {

    private val manifestUrl = "http://cdn.test/vod/manifest.mpd"

    private val oneRepresentation = "<Representation id=\"v\" bandwidth=\"1\"/>"

    private fun manifest(
        template: String,
        duration: String = "PT2S",
        representations: String = oneRepresentation,
        limits: DashManifestParser.UrlLimits = DashManifestParser.UrlLimits(),
    ): DashManifest = DashManifestParser.parse(
        "<MPD type=\"static\" mediaPresentationDuration=\"$duration\"><Period><AdaptationSet>" +
            template + representations + "</AdaptationSet></Period></MPD>",
        manifestUrl,
        DashUrlPolicy.Default,
        XmlMini.Limits(),
        limits,
    )

    private fun plan(
        template: String,
        duration: String = "PT2S",
        representations: String = oneRepresentation,
        limits: DashManifestParser.UrlLimits = DashManifestParser.UrlLimits(),
    ): DashSegmentPlan {
        val parsed = manifest(template, duration, representations, limits)
        val period = parsed.periods.single()
        return DashManifestParser.segmentPlan(
            parsed,
            period,
            period.adaptationSets.single().representations.single(),
            DashUrlPolicy.Default,
            limits,
        )
    }

    private fun assertRefused(expected: String, block: () -> Unit) {
        val refusal = assertFailsWith<IllegalArgumentException> { block() }
        assertTrue(expected in refusal.message.orEmpty(), "expected '$expected' in: ${refusal.message}")
    }

    private val d = "$"

    /** Shared by a text and every piece cut from it. */
    private class Counter {
        var reads = 0L
    }

    /** Counts every character read through [get], and every character copied out by [subSequence]. */
    private class CountingText(private val text: String, val counter: Counter = Counter()) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            counter.reads++
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            counter.reads += endIndex - startIndex
            return CountingText(text.substring(startIndex, endIndex), counter)
        }

        override fun toString(): String {
            counter.reads += text.length
            return text
        }
    }

    @Test
    fun aMillionHoursOfOneMicrosecondSegmentsIsRefused() {
        assertRefused("needs more than 100000 URLs") {
            plan(
                "<SegmentTemplate media=\"s${d}Number$d.m4s\" duration=\"1\" timescale=\"1000000\"/>",
                duration = "PT1000000H",
            )
        }
    }

    @Test
    fun aTimelineEntryThatRepeatsNearlyForeverIsRefused() {
        assertRefused("needs more than 100000 URLs") {
            plan(
                "<SegmentTemplate media=\"s${d}Time$d.m4s\" timescale=\"1\"><SegmentTimeline>" +
                    "<S t=\"0\" d=\"1\" r=\"9223372036854775806\"/></SegmentTimeline></SegmentTemplate>",
            )
        }
    }

    @Test
    fun aPadWidthPastTheLimitIsRefused() {
        assertRefused("pads to 900000000 digits, and the limit is 32") {
            plan("<SegmentTemplate media=\"s${d}Number%0900000000d$d.m4s\" duration=\"1\" timescale=\"1\"/>")
        }
        assertRefused("pads to -5 digits") {
            plan("<SegmentTemplate media=\"s${d}Number%0-5d$d.m4s\" duration=\"1\" timescale=\"1\"/>")
        }
        // A width past the range of an Int is refused too, and shown cut short.
        assertRefused("pads to 999999999999... digits") {
            plan("<SegmentTemplate media=\"s${d}Time%0${"9".repeat(40)}d$d.m4s\" duration=\"1\" timescale=\"1\"/>")
        }
        val widest = plan("<SegmentTemplate media=\"s${d}Number%032d$d.m4s\" duration=\"1\" timescale=\"1\"/>")
        assertEquals("http://cdn.test/vod/s" + "1".padStart(32, '0') + ".m4s", widest.mediaUrls.first())
        // A format that is not a number pads nothing, as before.
        val unpadded = plan("<SegmentTemplate media=\"s${d}Number%0xd$d.m4s\" duration=\"1\" timescale=\"1\"/>")
        assertEquals("http://cdn.test/vod/s1.m4s", unpadded.mediaUrls.first())
    }

    @Test
    fun aPlanAtItsUrlLimitIsKeptAndOneMoreIsRefused() {
        val limits = DashManifestParser.UrlLimits(planUrls = 10)
        val numbered = "<SegmentTemplate media=\"s${d}Number$d.m4s\" duration=\"1\" timescale=\"1\"/>"
        assertEquals(10, plan(numbered, duration = "PT10S", limits = limits).mediaUrls.size)
        assertRefused("needs more than 10 URLs") { plan(numbered, duration = "PT11S", limits = limits) }
        fun timeline(r: Int) = "<SegmentTemplate media=\"s${d}Time$d.m4s\" timescale=\"1\"><SegmentTimeline>" +
            "<S t=\"0\" d=\"1\" r=\"$r\"/></SegmentTimeline></SegmentTemplate>"
        assertEquals(10, plan(timeline(9), duration = "PT100S", limits = limits).mediaUrls.size)
        assertRefused("needs more than 10 URLs") { plan(timeline(10), duration = "PT100S", limits = limits) }
        // The initialization URL counts too.
        val initialized =
            "<SegmentTemplate initialization=\"init.mp4\" media=\"s${d}Number$d.m4s\" duration=\"1\" timescale=\"1\"/>"
        val nine = plan(initialized, duration = "PT9S", limits = limits)
        assertEquals("http://cdn.test/vod/init.mp4", nine.initializationUrl)
        assertEquals(9, nine.mediaUrls.size)
        assertRefused("needs more than 10 URLs") { plan(initialized, duration = "PT10S", limits = limits) }
    }

    @Test
    fun aPlanPastItsCharacterLimitIsRefused() {
        // Each URL reads its base, http://cdn.test/vod/manifest.mpd (32 characters), and its
        // reference, s-N.m4s (7), so three URLs read 117.
        val limits = DashManifestParser.UrlLimits(planChars = 117)
        val numbered = "<SegmentTemplate media=\"s-${d}Number$d.m4s\" duration=\"1\" timescale=\"1\"/>"
        assertEquals(3, plan(numbered, duration = "PT3S", limits = limits).mediaUrls.size)
        assertRefused("the segment plan reads more than 117 characters") {
            plan(numbered, duration = "PT4S", limits = limits)
        }
    }

    @Test
    fun eachUrlCostsItsBaseAndItsReferenceEvenWhenItComesOutShort() {
        // The representation's base is one directory of 400 characters and every reference climbs
        // back out of it, so each URL is short. Each one still reads 421 + 5 characters.
        val limits = DashManifestParser.UrlLimits(planChars = 2_000)
        val representation =
            "<Representation id=\"v\" bandwidth=\"1\"><BaseURL>${"d".repeat(400)}/</BaseURL></Representation>"
        fun list(count: Int) =
            "<SegmentList>" + (1..count).joinToString("") { "<SegmentURL media=\"../s$it\"/>" } + "</SegmentList>"
        val four = manifest(list(4), representations = representation, limits = limits)
        assertEquals(
            (1..4).map { "http://cdn.test/vod/s$it" },
            four.periods.single().adaptationSets.single().representations.single().segmentUrls,
        )
        assertRefused("a representation's SegmentList reads more than 2000 characters") {
            manifest(list(5), representations = representation, limits = limits)
        }
    }

    @Test
    fun aCountThatCanNeverFitIsRefusedBeforeAnyUrlIsBuilt() {
        // The first URL of each plan below is too long, so a refusal that names the count shows
        // that no URL was built.
        val limits = DashManifestParser.UrlLimits(planUrls = 10)
        val tooLong = "<SegmentTemplate media=\"${"a".repeat(8_180)}${d}Number$d\" duration=\"1\" timescale=\"1\"/>"
        assertRefused("has a URL longer than 8192 characters") { plan(tooLong, duration = "PT10S", limits = limits) }
        assertRefused("the segment plan needs more than 10 URLs") { plan(tooLong, duration = "PT11S", limits = limits) }
        fun list(count: Int) = "<SegmentList><SegmentURL media=\"${"a".repeat(9_000)}\"/>" +
            (2..count).joinToString("") { "<SegmentURL media=\"s$it\"/>" } + "</SegmentList>"
        val listLimits = DashManifestParser.UrlLimits(planUrls = 5)
        assertRefused("has a URL longer than 8192 characters") { manifest(list(5), limits = listLimits) }
        assertRefused("a representation's SegmentList needs more than 5 URLs") { manifest(list(6), limits = listLimits) }
    }

    @Test
    fun aUrlOrATemplateLongerThanTheLimitIsRefused() {
        val limit = DashManifestParser.MAX_URL_LENGTH
        assertRefused("a SegmentTemplate is longer than 8192 characters") {
            plan("<SegmentTemplate media=\"${"a".repeat(limit)}${d}Number$d\" duration=\"1\" timescale=\"1\"/>")
        }
        assertRefused("a SegmentTemplate is longer than 8192 characters") {
            plan(
                "<SegmentTemplate initialization=\"${"a".repeat(limit + 1)}\" media=\"s${d}Number$d\" " +
                    "duration=\"1\" timescale=\"1\"/>",
            )
        }
        // A short template can still write a long representation id into every URL.
        assertRefused("a SegmentTemplate puts more than 8192 characters into every URL") {
            plan(
                "<SegmentTemplate media=\"${d}RepresentationID$d/${d}Number$d\" duration=\"1\" timescale=\"1\"/>",
                representations = "<Representation id=\"${"r".repeat(limit + 1)}\" bandwidth=\"1\"/>",
            )
        }
        assertRefused("the segment plan has a URL longer than 8192 characters") {
            plan("<SegmentTemplate media=\"${"a".repeat(limit - 12)}${d}Number$d\" duration=\"1\" timescale=\"1\"/>")
        }
        assertRefused("a representation's SegmentList has a URL longer than 8192 characters") {
            manifest("<SegmentList><SegmentURL media=\"${"a".repeat(limit)}\"/></SegmentList>")
        }
        assertRefused("the manifest has a URL longer than 8192 characters") {
            manifest(
                "",
                representations = "<Representation id=\"v\" bandwidth=\"1\"><BaseURL>${"b".repeat(limit)}/</BaseURL>" +
                    "</Representation>",
            )
        }
    }

    @Test
    fun segmentNumbersAndTimesPastTheRangeOfALongAreRefused() {
        assertRefused("a segment number passes the range of a Long") {
            plan(
                "<SegmentTemplate media=\"s${d}Number$d.m4s\" startNumber=\"9223372036854775806\" " +
                    "duration=\"1\" timescale=\"1\"/>",
                duration = "PT3S",
            )
        }
        assertRefused("a segment time passes the range of a Long") {
            plan(
                "<SegmentTemplate media=\"s${d}Time$d.m4s\" timescale=\"1\"><SegmentTimeline>" +
                    "<S t=\"9223372036854775000\" d=\"1000\" r=\"1\"/></SegmentTimeline></SegmentTemplate>",
            )
        }
        assertRefused("the segment duration passes the range of a Long") {
            plan("<SegmentTemplate media=\"s${d}Number$d.m4s\" duration=\"9223372036854775\" timescale=\"1\"/>")
        }
    }

    @Test
    fun timelineEntriesWithoutAPositiveDurationAreRefused() {
        for (entry in listOf("<S t=\"0\" r=\"3\"/>", "<S t=\"0\" d=\"0\"/>", "<S t=\"0\" d=\"-2\" r=\"1\"/>")) {
            assertRefused("degenerate segment duration") {
                plan(
                    "<SegmentTemplate media=\"s${d}Time$d.m4s\" timescale=\"1\"><SegmentTimeline>$entry" +
                        "</SegmentTimeline></SegmentTemplate>",
                )
            }
        }
    }

    @Test
    fun aRepresentationsSegmentListPastThePlanLimitIsRefused() {
        val limits = DashManifestParser.UrlLimits(planUrls = 5)
        fun list(count: Int) =
            "<SegmentList>" + (1..count).joinToString("") { "<SegmentURL media=\"s$it.m4s\"/>" } + "</SegmentList>"
        assertEquals(
            5,
            manifest(list(5), limits = limits).periods.single().adaptationSets.single().representations.single()
                .segmentUrls.size,
        )
        assertRefused("a representation's SegmentList needs more than 5 URLs") { manifest(list(6), limits = limits) }
    }

    @Test
    fun anInheritedSegmentListCountsAgainstTheManifestForEveryRepresentation() {
        val limits = DashManifestParser.UrlLimits(parseUrls = 25)
        val list = "<SegmentList>" + (1..10).joinToString("") { "<SegmentURL media=\"s$it.m4s\"/>" } + "</SegmentList>"
        fun representations(count: Int) = (1..count).joinToString("") {
            "<Representation id=\"r$it\" bandwidth=\"1\"><BaseURL>r$it/</BaseURL></Representation>"
        }
        // Two representations: 2 BaseURLs and 2 times 10 segments, 22 URLs.
        val two = manifest(list, representations = representations(2), limits = limits)
        val urls = two.periods.single().adaptationSets.single().representations.map { it.segmentUrls }
        assertEquals("http://cdn.test/vod/r2/s10.m4s", urls[1].last())
        // A third one needs 33.
        assertRefused("the manifest needs more than 25 URLs") {
            manifest(list, representations = representations(3), limits = limits)
        }
    }

    @Test
    fun modelObjectsBuiltWithoutTheParserGetTheSameChecks() {
        val base = "http://cdn.test/vod/"
        fun template(
            media: String = "s${d}Number$d.m4s",
            timescale: Long = 1,
            duration: Long? = 2,
            timeline: List<DashTimelineEntry> = emptyList(),
        ) = DashSegmentTemplate(
            initialization = null,
            media = media,
            startNumber = 1,
            timescale = timescale,
            duration = duration,
            timeline = timeline,
        )
        fun planOf(template: DashSegmentTemplate, durationMicros: Long = 10_000_000): DashSegmentPlan {
            val representation = DashRepresentation(
                id = "v", bandwidth = 1, codecs = null, mimeType = null, width = null, height = null,
                baseUrl = base, segmentTemplate = template, segmentUrls = emptyList(), initializationUrl = null,
            )
            val period = DashPeriod(base, durationMicros, listOf(DashAdaptationSet(null, null, null, listOf(representation))))
            return DashManifestParser.segmentPlan(DashManifest(false, durationMicros, listOf(period), base), period, representation)
        }
        assertEquals(5, planOf(template()).mediaUrls.size)
        assertRefused("timescale must be positive, not 0") { planOf(template(timescale = 0)) }
        assertRefused("degenerate segment duration") { planOf(template(duration = 0)) }
        assertRefused("degenerate segment duration") {
            planOf(template(duration = null, timeline = listOf(DashTimelineEntry(t = 0, d = 0, r = 3))))
        }
        assertRefused("needs more than 100000 URLs") {
            planOf(template(duration = null, timeline = listOf(DashTimelineEntry(t = 0, d = 1, r = Long.MAX_VALUE))))
        }
        assertRefused("pads to 900000000 digits") { planOf(template(media = "s${d}Number%0900000000d$d")) }
        assertRefused("is negative") { planOf(template(), durationMicros = -1) }
    }

    @Test
    fun longPathsWithManyDotSegmentsStillResolve() {
        val path = "a/".repeat(4_000) + "../".repeat(3_999) + "x.m4s"
        assertEquals("http://cdn.test/a/x.m4s", DashManifestParser.resolveUrl("http://cdn.test/", path))
        assertEquals("http://cdn.test/y/", DashManifestParser.resolveUrl("http://cdn.test/a/b", "../y/./z/.."))
        assertEquals("http://cdn.test/", DashManifestParser.resolveUrl("http://cdn.test/a/b/c", "/./a/../b/.."))
    }

    @Test
    fun dotSegmentsAreReadAFewTimesPerCharacter() {
        val paths = listOf(
            "/" + "a/".repeat(20_000) + "../".repeat(19_999) + "x",
            "/" + "./".repeat(30_000) + "x",
            "/" + "a/../".repeat(12_000) + "x",
            "../".repeat(20_000) + "x",
        )
        for (path in paths) {
            val text = CountingText(path)
            DashManifestParser.removeDotSegments(text)
            val reads = text.counter.reads
            assertTrue(reads <= 8L * path.length, "${path.take(8)}...: read $reads characters of ${path.length}")
        }
        assertEquals("/a/x", DashManifestParser.removeDotSegments(paths[0]))
        assertEquals("/x", DashManifestParser.removeDotSegments(paths[1]))
        assertEquals("/x", DashManifestParser.removeDotSegments(paths[2]))
        assertEquals("x", DashManifestParser.removeDotSegments(paths[3]))
    }

    @Test
    fun theDefaultsAreTheDocumentedCeilingsAndARaisedDocumentLimitRaisesTheParseOnes() {
        assertEquals(100_000, DashManifestParser.MAX_PLAN_URLS)
        assertEquals(16L * 1024 * 1024, DashManifestParser.MAX_PLAN_CHARS)
        assertEquals(8 * 1024, DashManifestParser.MAX_URL_LENGTH)
        assertEquals(32, DashManifestParser.MAX_PAD_WIDTH)
        assertEquals(1024 * 1024, DashManifestParser.MAX_PARSE_URLS)
        assertEquals(32L * 1024 * 1024, DashManifestParser.MAX_PARSE_CHARS)
        val atDefault = DashManifestParser.UrlLimits.forDocument(XmlMini.MAX_LENGTH)
        assertEquals(DashManifestParser.MAX_PARSE_URLS, atDefault.parseUrls)
        assertEquals(DashManifestParser.MAX_PARSE_CHARS, atDefault.parseChars)
        assertEquals(DashManifestParser.MAX_PARSE_URLS, DashManifestParser.UrlLimits.forDocument(1_000).parseUrls)
        val raised = DashManifestParser.UrlLimits.forDocument(64 * 1024 * 1024)
        assertEquals(8 * 1024 * 1024, raised.parseUrls)
        assertEquals(256L * 1024 * 1024, raised.parseChars)
        assertEquals(DashManifestParser.MAX_PLAN_URLS, raised.planUrls, "a plan's ceilings do not follow the document")
    }
}
