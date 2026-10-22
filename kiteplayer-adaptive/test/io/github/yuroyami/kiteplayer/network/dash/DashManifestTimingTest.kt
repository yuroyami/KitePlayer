package io.github.yuroyami.kiteplayer.network.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What the parser keeps for the HLS route (#295): the live clock, byte ranges, segment timing and the set's language. */
class DashManifestTimingTest {

    @Test
    fun aLiveManifestKeepsItsClock() {
        val manifest = DashManifestParser.parse(
            """
            <MPD type="dynamic" availabilityStartTime="2026-10-02T10:00:00Z" minimumUpdatePeriod="PT2S"
                 timeShiftBufferDepth="PT30S" suggestedPresentationDelay="PT6S">
                <Period id="p0" start="PT0S">
                    <AdaptationSet contentType="video" mimeType="video/mp4" frameRate="30000/1001">
                        <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" initialization="v-init.mp4"
                                         timescale="90000" duration="180000" presentationTimeOffset="900"/>
                        <Representation id="v" bandwidth="800000" width="640" height="360"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
            "https://live.test/channel/manifest.mpd",
        )
        assertEquals(true, manifest.isDynamic)
        assertEquals(1_790_935_200_000_000L, manifest.availabilityStartTimeMicros)
        assertEquals(2_000_000L, manifest.minimumUpdatePeriodMicros)
        assertEquals(30_000_000L, manifest.timeShiftBufferDepthMicros)
        assertEquals(6_000_000L, manifest.suggestedPresentationDelayMicros)
        val period = manifest.periods.single()
        assertEquals(0L, period.startMicros)
        val representation = period.adaptationSets.single().representations.single()
        assertEquals(29.97, representation.frameRate!!, 0.001)
        assertEquals(900L, representation.segmentTemplate!!.presentationTimeOffset)
    }

    @Test
    fun dateTimesReadWithAndWithoutAZone() {
        assertEquals(0L, DashManifestParser.parseDateTimeMicros("1970-01-01T00:00:00Z"))
        assertEquals(1_500_000L, DashManifestParser.parseDateTimeMicros("1970-01-01T00:00:01.5"))
        assertEquals(
            DashManifestParser.parseDateTimeMicros("2026-10-02T10:00:00Z"),
            DashManifestParser.parseDateTimeMicros("2026-10-02T12:00:00+02:00"),
        )
        assertEquals(951_782_400_000_000L, DashManifestParser.parseDateTimeMicros("2000-02-29T00:00:00Z"))
        assertFailsWith<IllegalArgumentException> { DashManifestParser.parseDateTimeMicros("yesterday") }
    }

    @Test
    fun aSegmentListKeepsItsRangesItsTimingAndItsBase() {
        val manifest = DashManifestParser.parse(
            """
            <MPD type="static" mediaPresentationDuration="PT6S">
                <Period>
                    <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="fr">
                        <Representation id="a" bandwidth="96000">
                            <BaseURL>audio.mp4</BaseURL>
                            <SegmentList timescale="1000" duration="2000" startNumber="3">
                                <Initialization range="0-799"/>
                                <SegmentURL mediaRange="800-1999"/>
                                <SegmentURL mediaRange="2000-2999"/>
                                <SegmentURL media="tail.m4s"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
            "https://cdn.test/vod/movie.mpd",
        )
        val set = manifest.periods.single().adaptationSets.single()
        assertEquals("fr", set.lang)
        val representation = set.representations.single()
        val list = representation.segmentList!!
        assertEquals(1000L, list.timescale)
        assertEquals(2000L, list.duration)
        assertEquals(3L, list.startNumber)
        assertEquals("https://cdn.test/vod/audio.mp4", list.initializationUrl, "a range alone names the BaseURL")
        assertEquals(0L..799L, list.initializationRange)
        assertEquals(
            listOf(
                DashSegmentUrl("https://cdn.test/vod/audio.mp4", 800L..1999L),
                DashSegmentUrl("https://cdn.test/vod/audio.mp4", 2000L..2999L),
                DashSegmentUrl("https://cdn.test/vod/tail.m4s", null),
            ),
            list.segments,
        )
        // The old field keeps its meaning: the URLs that a SegmentURL names with media.
        assertEquals(listOf("https://cdn.test/vod/tail.m4s"), representation.segmentUrls)
    }

    @Test
    fun aSegmentBaseKeepsItsIndexAndItsInitialization() {
        val manifest = DashManifestParser.parse(
            """
            <MPD type="static" mediaPresentationDuration="PT60S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" bandwidth="1000000">
                            <BaseURL>movie-1000k.mp4</BaseURL>
                            <SegmentBase timescale="90000" indexRange="1200-1599">
                                <Initialization range="0-1199"/>
                            </SegmentBase>
                        </Representation>
                        <Representation id="w" bandwidth="500000">
                            <BaseURL>movie-500k.mp4</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
            "https://cdn.test/vod/movie.mpd",
        )
        val (v, w) = manifest.periods.single().adaptationSets.single().representations
        val base = v.segmentBase!!
        assertEquals(90_000L, base.timescale)
        assertEquals(1200L..1599L, base.indexRange)
        assertEquals("https://cdn.test/vod/movie-1000k.mp4", base.initializationUrl)
        assertEquals(0L..1199L, base.initializationRange)
        assertNull(w.segmentBase, "a representation without a SegmentBase has none")
    }

    @Test
    fun aRangeOrAFrameRateThatIsNotOneIsRefusedOrIgnored() {
        assertEquals(10L..19L, DashManifestParser.parseByteRange("10-19"))
        assertFailsWith<IllegalArgumentException> { DashManifestParser.parseByteRange("19-10") }
        assertFailsWith<IllegalArgumentException> { DashManifestParser.parseByteRange("ten") }
        assertEquals(25.0, DashManifestParser.parseFrameRate("25"))
        assertNull(DashManifestParser.parseFrameRate("fast"))
        assertNull(DashManifestParser.parseFrameRate("30/0"))
    }
}
