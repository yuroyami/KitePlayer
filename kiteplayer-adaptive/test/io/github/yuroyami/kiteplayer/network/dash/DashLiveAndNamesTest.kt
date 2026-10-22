package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live manifest's clock and Location, a template's endNumber, and what a track is called (#404). */
class DashLiveAndNamesTest {

    private fun parse(xml: String, policy: DashUrlPolicy = DashUrlPolicy.Default) =
        DashManifestParser.parse(xml, "https://cdn.test/live/movie.mpd", policy)

    @Test
    fun theManifestsLocationAndClocksAreRead() {
        val manifest = parse(
            """<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z">
                <Location>../moved/movie.mpd</Location>
                <UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-xsdate:2014" value="https://time.test/now"/>
                <UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014" value="2026-10-03T10:00:00Z"/>
                <Period/>
            </MPD>""",
        )
        assertEquals("https://cdn.test/moved/movie.mpd", manifest.location)
        assertEquals(
            listOf(
                DashUtcTiming("urn:mpeg:dash:utc:http-xsdate:2014", "https://time.test/now"),
                DashUtcTiming("urn:mpeg:dash:utc:direct:2014", "2026-10-03T10:00:00Z"),
            ),
            manifest.utcTimings,
        )
        val elsewhere = parse(
            """<MPD type="dynamic"><Location>https://other.test/movie.mpd</Location><Period/></MPD>""",
            DashUrlPolicy.SameOrigin,
        )
        assertNull(elsewhere.location, "a Location the policy refuses is not taken, and the manifest still parses")
    }

    @Test
    fun aSetsLabelRolesProtectionAndChannelsAreRead() {
        val set = parse(
            """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                    <Label> Director's commentary </Label>
                    <Role schemeIdUri="urn:mpeg:dash:role:2011" value="commentary"/>
                    <Role schemeIdUri="urn:example:roles" value="ignored"/>
                    <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
                    <Representation id="stereo" bandwidth="96000"/>
                    <Representation id="surround" bandwidth="384000">
                        <AudioChannelConfiguration schemeIdUri="urn:mpeg:mpegB:cicp:ChannelConfiguration" value="6"/>
                        <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                    </Representation>
                </AdaptationSet>
            </Period></MPD>""",
        ).periods.single().adaptationSets.single()
        assertEquals("Director's commentary", set.label)
        assertEquals(listOf("commentary"), set.roles, "only the DASH role scheme names a role")
        assertEquals(listOf("urn:mpeg:dash:mp4protection:2011"), set.contentProtectionSchemes, "a representation's protection counts for its set")
        assertEquals(listOf(2, 6), set.representations.map { it.audioChannels }, "a representation's own configuration wins over its set's")
    }

    @Test
    fun eachChannelSchemeCountsItsOwnWay() {
        fun count(scheme: String, value: String) = DashManifestParser.channelCount(
            io.github.yuroyami.kiteplayer.network.xml.XmlMini.parse("""<AudioChannelConfiguration schemeIdUri="$scheme" value="$value"/>"""),
        )
        assertEquals(8, count("urn:mpeg:mpegB:cicp:ChannelConfiguration", "7"), "CICP 7 is 7.1")
        assertEquals(6, count("tag:dolby.com,2014:dash:audio_channel_configuration:2011", "F801"), "L C R Ls Rs and LFE")
        assertEquals(2, count("urn:dolby:dash:audio_channel_configuration:2011", "A000"), "L and R")
        assertEquals(8, count("urn:dolby:dash:audio_channel_configuration:2011", "FA01"), "L C R Ls Rs, Lrs and Rrs as one bit, and LFE")
        assertNull(count("urn:example:channels", "2"))
        assertNull(count("urn:mpeg:mpegB:cicp:ChannelConfiguration", "8"), "CICP 8 names no fixed layout")
    }

    private fun template(attributes: String, timeline: String = "") = parse(
        """<MPD type="static" mediaPresentationDuration="PT20S"><Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
                <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" timescale="1000" startNumber="5" $attributes>$timeline</SegmentTemplate>
                <Representation id="v" bandwidth="1"/>
            </AdaptationSet>
        </Period></MPD>""",
    )

    private fun numbers(manifest: DashManifest): List<Long> {
        val period = manifest.periods.single()
        val rep = period.adaptationSets.single().representations.single()
        val timed = assertNotNull(DashManifestParser.timedPlan(manifest, period, rep, DashUrlPolicy.Default, null)).segments.map { it.number }
        val streamed = DashManifestParser.segmentPlan(manifest, period, rep).mediaUrls.map { it.substringAfterLast("v-").substringBefore('.').toLong() }
        assertEquals(timed, streamed, "both plans stop at the same segment")
        return timed
    }

    @Test
    fun aTemplateStopsAtItsEndNumber() {
        assertEquals((5L..9L).toList(), numbers(template("""duration="2000"""")).take(5), "without it the duration counts ten")
        assertEquals((5L..8L).toList(), numbers(template("""duration="2000" endNumber="8"""")))
        assertEquals((5L..14L).toList(), numbers(template("""duration="2000" endNumber="30"""")), "the duration still ends it first")
        assertEquals((5L..6L).toList(), numbers(template("endNumber=\"6\"", """<SegmentTimeline><S t="0" d="2000" r="9"/></SegmentTimeline>""")))
    }

    @Test
    fun anEndNumberCountsTheSegmentsWhenNothingElseDoes() {
        val manifest = parse(
            """<MPD type="static"><Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000" endNumber="3"/>
                    <Representation id="v" bandwidth="1"/>
                </AdaptationSet>
            </Period></MPD>""",
        )
        assertEquals(listOf(1L, 2L, 3L), numbers(manifest))
    }

    @Test
    fun theClockFollowsTheFirstTimingThatAnswers() = runTest {
        val device = 1_000_000_000_000L
        val asked = mutableListOf<String>()
        val clock = DashLiveClock(
            listOf(
                DashUtcTiming("urn:mpeg:dash:utc:ntp:2014", "time.test"),
                DashUtcTiming("urn:mpeg:dash:utc:http-xsdate:2014", "https://down.test/now https://time.test/now"),
                DashUtcTiming("urn:mpeg:dash:utc:direct:2014", "1970-01-01T00:00:00Z"),
            ),
            deviceMicros = { device },
            fetchText = { url ->
                asked += url
                if ("down" in url) error("no answer")
                // A minute ahead of the device.
                "1970-01-12T13:47:40Z"
            },
            fetchDate = { error("not asked") },
        )
        assertEquals(device + 60_000_000L, clock.nowMicros())
        assertEquals(listOf("https://down.test/now", "https://time.test/now"), asked, "an address that does not answer gives way to the next")
        clock.nowMicros()
        assertEquals(2, asked.size, "the difference is read once")
    }

    @Test
    fun theHeadSchemeReadsTheDateHeaderAndNothingFallsBackToTheDevice() = runTest {
        val head = DashLiveClock(
            listOf(DashUtcTiming("urn:mpeg:dash:utc:http-head:2014", "https://time.test/")),
            deviceMicros = { 0L },
            fetchText = { error("not asked") },
            fetchDate = { "Thu, 01 Jan 1970 00:01:00 GMT" },
        )
        assertEquals(60_000_000L, head.nowMicros())
        val none = DashLiveClock(emptyList(), { 42L }, { error("not asked") }, { error("not asked") })
        assertEquals(42L, none.nowMicros())
        assertEquals(784_111_777_000_000L, DashLiveClock.parseHttpDateMicros("Sun, 06 Nov 1994 08:49:37 GMT"))
    }

    private val live = """
        <MPD type="dynamic" availabilityStartTime="1970-01-01T00:01:00Z" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT10S">
            <Location>https://cdn.test/live/next.mpd</Location>
            <UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014" value="1970-01-01T00:02:01Z"/>
            <Period start="PT0S">
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" initialization="v-init.mp4" startNumber="1" timescale="1000" duration="2000"/>
                    <Representation id="v" bandwidth="800000"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun aLiveWindowFollowsTheManifestsClockAndARefreshItsLocation() = runTest {
        // The device thinks the presentation, which became available at one minute, has not begun.
        var device = 60_000_000L
        val fetched = mutableListOf<String>()
        val first = parse(live)
        val presentation = DashHls.presentation(first.periods.single(), live = true)
        val io = DashHlsMediaIo(
            presentation, first, "https://cdn.test/live/movie.mpd", DashUrlPolicy.Default, { BytesMediaIo(ByteArray(0)) },
            { url ->
                fetched += url
                parse(live.replace("next.mpd", "after.mpd"))
            },
            { device },
        )
        val address = presentation.tracks.single().address
        val playlist = io.openRelated(address)!!.readAll().decodeToString()
        // By the manifest's clock it is 61 s in: the newest whole segment ends at 60 s.
        assertTrue("v-30.m4s" in playlist && "v-31.m4s" !in playlist, playlist)
        device += 2_000_000L
        io.openRelated(address)!!.readAll()
        device += 2_000_000L
        io.openRelated(address)!!.readAll()
        assertEquals(listOf("https://cdn.test/live/next.mpd", "https://cdn.test/live/after.mpd"), fetched)
    }

    private val named = """
        <MPD type="static" mediaPresentationDuration="PT4S"><Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
                <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000"/>
                <Representation id="v" bandwidth="500000"/>
            </AdaptationSet>
            <AdaptationSet contentType="video" mimeType="video/mp4">
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                <SegmentTemplate media="drm-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000"/>
                <Representation id="drm" bandwidth="900000"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                <Role schemeIdUri="urn:mpeg:dash:role:2011" value="description"/>
                <SegmentTemplate media="ad-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000"/>
                <Representation id="ad" bandwidth="64000"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                <Label>English "5.1"</Label>
                <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="6"/>
                <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000"/>
                <Representation id="a" bandwidth="384000"/>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt" lang="fr">
                <Role schemeIdUri="urn:mpeg:dash:role:2011" value="forced-subtitle"/>
                <Representation id="forced" bandwidth="1"><BaseURL>forced.vtt</BaseURL></Representation>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt" lang="fr">
                <Role schemeIdUri="urn:mpeg:dash:role:2011" value="caption"/>
                <Representation id="sdh" bandwidth="1"><BaseURL>sdh.vtt</BaseURL></Representation>
            </AdaptationSet>
        </Period></MPD>
    """.trimIndent()

    @Test
    fun renditionsCarryTheirSetsNamesRolesAndChannels() {
        val presentation = DashHls.presentation(parse(named).periods.single())
        val media = presentation.master.lines().filter { it.startsWith("#EXT-X-MEDIA:") }
        val (description, main) = media.filter { "TYPE=AUDIO" in it }
        assertTrue("NAME=\"en description\"" in description && "DEFAULT=NO" in description, description)
        assertTrue("CHARACTERISTICS=\"public.accessibility.describes-video\"" in description, description)
        assertTrue("NAME=\"English '5.1'\"" in main, "the label names it, its quotes made safe: $main")
        assertTrue("DEFAULT=YES" in main && "CHANNELS=\"6\"" in main, "the main set is the default and states its channels: $main")
        val (forced, sdh) = media.filter { "TYPE=SUBTITLES" in it }
        assertTrue("FORCED=YES" in forced && "NAME=\"fr forced\"" in forced, forced)
        assertFalse("FORCED" in sdh, sdh)
        assertTrue("NAME=\"fr captions\"" in sdh, sdh)
        assertTrue("CHARACTERISTICS=\"public.accessibility.describes-music-and-sound\"" in sdh, sdh)
    }

    @Test
    fun withNoMainSetTheDefaultSoundIsNotADescription() {
        val unmarked = named.replace("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>""", "")
        val audio = DashHls.presentation(parse(unmarked).periods.single()).master.lines().filter { "TYPE=AUDIO" in it }
        assertEquals(listOf("DEFAULT=NO", "DEFAULT=YES"), audio.map { line -> line.split(',').single { it.startsWith("DEFAULT=") } })
    }

    @Test
    fun namesThatWouldRepeatAreCounted() {
        val twice = named.replace("<Label>English \"5.1\"</Label>", "").replace("""value="main"""", """value="description"""")
        val audio = DashHls.presentation(parse(twice).periods.single()).master.lines().filter { "TYPE=AUDIO" in it }
        assertEquals(listOf("en description 1", "en description 2"), audio.map { it.substringAfter("NAME=\"").substringBefore('"') })
    }

    @Test
    fun anEncryptedSetIsLeftOutAndTheOthersKeepTheirPlaces() {
        val presentation = DashHls.presentation(parse(named).periods.single())
        val video = presentation.tracks.filter { it.role == DashHlsRole.Video }
        assertEquals(listOf("v"), video.map { it.representation.id }, "the encrypted variant is not offered")
        assertEquals(listOf(2, 3), presentation.tracks.filter { it.role == DashHlsRole.Audio }.map { it.setIndex })
        assertFalse("drm-" in presentation.master)
        val onlyEncrypted = parse(named.replace("""<Representation id="v" bandwidth="500000"/>""", """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/><Representation id="v" bandwidth="500000"/>"""))
        val period = onlyEncrypted.periods.single()
        assertTrue(DashHls.carries(period), "clear sound is still there to carry")
    }

    private suspend fun MediaIo.readAll(): ByteArray {
        var out = ByteArray(0)
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            out += buffer.copyOf(count)
        }
        return out
    }

    /** Bytes in memory, as a segment reader serves them. */
    private class BytesMediaIo(private val bytes: ByteArray) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {}
    }
}
