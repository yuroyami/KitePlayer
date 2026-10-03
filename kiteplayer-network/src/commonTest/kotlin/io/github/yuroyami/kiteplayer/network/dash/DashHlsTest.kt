package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A DASH manifest written out as the HLS playlists that stand in for it (#295). */
class DashHlsTest {

    private val separateSets = """
        <MPD type="static" mediaPresentationDuration="PT9S">
            <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4" frameRate="30">
                    <SegmentTemplate media="v-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"
                                     initialization="v-${'$'}RepresentationID${'$'}-init.mp4" timescale="1000" duration="4000"/>
                    <Representation id="hi" bandwidth="900000" codecs="avc1.64001e" width="640" height="360"/>
                    <Representation id="lo" bandwidth="300000" codecs="avc1.64000d" width="320" height="180"/>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                    <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" initialization="a-init.mp4" timescale="48000">
                        <SegmentTimeline><S t="0" d="192000" r="1"/><S d="48000"/></SegmentTimeline>
                    </SegmentTemplate>
                    <Representation id="a" bandwidth="96000" codecs="mp4a.40.2"/>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="application/ttml+xml" lang="en">
                    <Representation id="ttml" bandwidth="1000"><BaseURL>subs.ttml</BaseURL></Representation>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
                    <Representation id="vtt" bandwidth="1000"><BaseURL>subs-de.vtt</BaseURL></Representation>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    private fun parse(xml: String, url: String = "https://cdn.test/vod/movie.mpd", policy: DashUrlPolicy = DashUrlPolicy.Default) =
        DashManifestParser.parse(xml, url, policy)

    @Test
    fun separateVideoAndAudioSetsBecomeVariantsAndOneRendition() {
        val period = parse(separateSets).periods.single()
        assertTrue(DashHls.carries(period))
        val presentation = DashHls.presentation(period)
        val lines = presentation.master.lines()
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
        assertEquals(2, variants.size, presentation.master)
        // Ascending bandwidth, each with the sound it needs counted in.
        assertTrue(variants[0].contains("BANDWIDTH=396000"), variants[0])
        assertTrue(variants[0].contains("CODECS=\"avc1.64000d,mp4a.40.2\""), variants[0])
        assertTrue(variants[0].contains("RESOLUTION=320x180"), variants[0])
        assertTrue(variants[0].contains("FRAME-RATE=30.000"), variants[0])
        assertTrue(variants[1].contains("BANDWIDTH=996000"), variants[1])
        assertTrue(variants.all { "AUDIO=\"audio\"" in it && "SUBTITLES=\"subtitles\"" in it })
        val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }
        assertEquals(2, renditions.size, "one sound and the WebVTT set; the TTML set is left out: ${presentation.master}")
        assertTrue(renditions[0].contains("TYPE=AUDIO") && renditions[0].contains("LANGUAGE=\"en\"") && renditions[0].contains("DEFAULT=YES"))
        assertTrue(renditions[1].contains("TYPE=SUBTITLES") && renditions[1].contains("LANGUAGE=\"de\""))
        // Every address the master names is one the presentation answers.
        val named = lines.filter { it.startsWith("https://") } +
            renditions.map { it.substringAfter("URI=\"").substringBefore('"') }
        assertEquals(4, named.size)
        named.forEach { assertNotNull(presentation.track(it), "$it is not answered") }
        assertTrue(named.all { it.startsWith("https://${DashHls.HOST}/") })
    }

    @Test
    fun aTemplateBecomesAFinishedPlaylistWithItsInitialization() {
        val manifest = parse(separateSets)
        val period = manifest.periods.single()
        val hi = period.adaptationSets[0].representations[0]
        val plan = assertNotNull(DashManifestParser.timedPlan(manifest, period, hi, DashUrlPolicy.Default, null))
        val playlist = DashHls.mediaPlaylist(plan, live = false)
        assertTrue("#EXT-X-MAP:URI=\"https://cdn.test/vod/v-hi-init.mp4\"" in playlist, playlist)
        assertTrue("#EXT-X-TARGETDURATION:4" in playlist, playlist)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:1" in playlist, playlist)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"), playlist)
        // 9 s in 4 s segments: three of them, and the last lasts the 1 s that is left, so the
        // playlist is as long as the presentation.
        assertEquals(
            listOf("https://cdn.test/vod/v-hi-1.m4s", "https://cdn.test/vod/v-hi-2.m4s", "https://cdn.test/vod/v-hi-3.m4s"),
            playlist.lines().filter { it.startsWith("https://") },
        )
        assertEquals(listOf("#EXTINF:4.000000,", "#EXTINF:4.000000,", "#EXTINF:1.000000,"), playlist.lines().filter { it.startsWith("#EXTINF") })
    }

    @Test
    fun aTimelineGivesEachSegmentItsOwnLength() {
        val manifest = parse(separateSets)
        val period = manifest.periods.single()
        val audio = period.adaptationSets[1].representations.single()
        val plan = assertNotNull(DashManifestParser.timedPlan(manifest, period, audio, DashUrlPolicy.Default, null))
        assertEquals(listOf(4_000_000L, 4_000_000L, 1_000_000L), plan.segments.map { it.durationMicros })
        assertEquals(listOf(0L, 4_000_000L, 8_000_000L), plan.segments.map { it.startMicros })
        val playlist = DashHls.mediaPlaylist(plan, live = false)
        assertEquals(listOf("#EXTINF:4.000000,", "#EXTINF:4.000000,", "#EXTINF:1.000000,"), playlist.lines().filter { it.startsWith("#EXTINF") })
    }

    @Test
    fun aSegmentListOfByteRangesBecomesByteRanges() {
        val manifest = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" bandwidth="500000">
                            <BaseURL>one.mp4</BaseURL>
                            <SegmentList timescale="1000" duration="2000">
                                <Initialization range="0-999"/>
                                <SegmentURL mediaRange="1000-4999"/>
                                <SegmentURL mediaRange="5000-8999"/>
                            </SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val period = manifest.periods.single()
        assertTrue(DashHls.carries(period))
        val plan = assertNotNull(DashManifestParser.timedPlan(manifest, period, period.adaptationSets[0].representations[0], DashUrlPolicy.Default, null))
        val playlist = DashHls.mediaPlaylist(plan, live = false)
        assertTrue("#EXT-X-MAP:URI=\"https://cdn.test/vod/one.mp4\",BYTERANGE=\"1000@0\"" in playlist, playlist)
        assertEquals(listOf("#EXT-X-BYTERANGE:4000@1000", "#EXT-X-BYTERANGE:4000@5000"), playlist.lines().filter { it.startsWith("#EXT-X-BYTERANGE") })
    }

    @Test
    fun webmSegmentsAreCarriedAsFragmentedMp4Is() {
        val period = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT4S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/webm">
                        <SegmentTemplate media="v-${'$'}Number${'$'}.webm" initialization="v-init.webm" timescale="1" duration="2"/>
                        <Representation id="v" bandwidth="500000" codecs="vp09.00.11.08" width="320" height="180"/>
                    </AdaptationSet>
                    <AdaptationSet contentType="audio" mimeType="audio/webm">
                        <SegmentTemplate media="a-${'$'}Number${'$'}.webm" initialization="a-init.webm" timescale="1" duration="2"/>
                        <Representation id="a" bandwidth="64000" codecs="opus"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        ).periods.single()
        assertTrue(DashHls.carries(period), "WebM rides the HLS path (#401)")
        val presentation = DashHls.presentation(period)
        assertTrue("CODECS=\"vp09.00.11.08,opus\"" in presentation.master, presentation.master)
        assertTrue("#EXT-X-MEDIA:TYPE=AUDIO" in presentation.master, presentation.master)
    }

    @Test
    fun aWebmFileWhoseCuesNameItsClustersBecomesByteRanges() = runTest {
        val file = WebmBytes.file(listOf(0, 2000, 4000), blockBytes = 500, durationMillis = 5000.0)
        val init = file.firstCluster
        for (segmentBase in listOf(
            // As ffmpeg's webm_dash_manifest muxer writes it.
            "<SegmentBase indexRange=\"${file.cues.first}-${file.cues.last}\"><Initialization range=\"0-${init - 1}\"/></SegmentBase>",
            // Nothing given: the start of the file says where the Segment, the scale and the Cues are.
            "<SegmentBase/>",
        )) {
            val manifest = parse(
                """
                <MPD type="static" mediaPresentationDuration="PT5S">
                    <Period>
                        <AdaptationSet contentType="video" mimeType="video/webm" codecs="vp9">
                            <Representation id="v" bandwidth="500000"><BaseURL>single.webm</BaseURL>$segmentBase</Representation>
                        </AdaptationSet>
                    </Period>
                </MPD>
                """.trimIndent(),
            )
            val period = manifest.periods.single()
            assertTrue(DashHls.carries(period), "a single WebM file with an index rides the HLS path")
            val presentation = DashHls.presentation(period)
            val io = DashHlsMediaIo(presentation, manifest, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default, { url ->
                assertEquals("https://cdn.test/vod/single.webm", url)
                BytesMediaIo(file.bytes)
            }, null, { 0L })
            val playlist = io.openRelated(presentation.tracks.single().address)!!.readAll().decodeToString()
            assertTrue("#EXT-X-MAP:URI=\"https://cdn.test/vod/single.webm\",BYTERANGE=\"$init@0\"" in playlist, playlist)
            assertEquals(
                file.clusters.map { "#EXT-X-BYTERANGE:${it.last - it.first + 1}@${it.first}" },
                playlist.lines().filter { it.startsWith("#EXT-X-BYTERANGE") },
                "each cluster is a segment, and the last ends where the Cues begin",
            )
            assertEquals(listOf("#EXTINF:2.000000,", "#EXTINF:2.000000,", "#EXTINF:1.000000,"), playlist.lines().filter { it.startsWith("#EXTINF") })
        }
    }

    private val live = """
        <MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT10S">
            <Period start="PT0S">
                <AdaptationSet contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" initialization="v-init.mp4" startNumber="100" timescale="1000" duration="2000"/>
                    <Representation id="v" bandwidth="800000"/>
                </AdaptationSet>
            </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun aLiveTemplateListsTheSegmentsAvailableNow() {
        val manifest = parse(live)
        val period = manifest.periods.single()
        val rep = period.adaptationSets.single().representations.single()
        // At 61 s the newest whole segment ends at 60 s, and the buffer reaches back 10 s.
        val plan = assertNotNull(DashManifestParser.timedPlan(manifest, period, rep, DashUrlPolicy.Default, 61_000_000L))
        assertEquals((125L..129L).toList(), plan.segments.map { it.number })
        assertEquals(60_000_000L, plan.segments.last().let { it.startMicros + it.durationMicros })
        val playlist = DashHls.mediaPlaylist(plan, live = true)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:125" in playlist, playlist)
        assertFalse("#EXT-X-ENDLIST" in playlist, "a live playlist has no end")
        assertFalse("#EXT-X-PLAYLIST-TYPE" in playlist)
        // Before the first segment has ended, nothing is available yet.
        assertTrue(DashManifestParser.timedPlan(manifest, period, rep, DashUrlPolicy.Default, 1_000_000L)!!.segments.isEmpty())
    }

    @Test
    fun aLiveTimelineThatRepeatsToTheEdgeStopsThere() {
        val manifest = parse(
            """
            <MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" timeShiftBufferDepth="PT6S">
                <Period start="PT0S">
                    <AdaptationSet contentType="audio" mimeType="audio/mp4">
                        <SegmentTemplate media="a-${'$'}Time${'$'}.m4s" timescale="1000" presentationTimeOffset="5000">
                            <SegmentTimeline><S t="5000" d="2000" r="-1"/></SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="a" bandwidth="64000"/>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val period = manifest.periods.single()
        val plan = assertNotNull(
            DashManifestParser.timedPlan(manifest, period, period.adaptationSets[0].representations[0], DashUrlPolicy.Default, 3_600_000_000L),
        )
        // An hour in, the 6 s buffer holds three 2 s segments, the last ending at the edge.
        assertEquals(3, plan.segments.size, plan.segments.map { it.url }.toString())
        assertEquals(3_600_000_000L, plan.segments.last().let { it.startMicros + it.durationMicros })
        assertEquals("https://cdn.test/vod/a-3603000.m4s", plan.segments.last().url, "the time counts from the offset")
    }

    @Test
    fun theReaderAnswersItsPlaylistsAndRefusesWhatThePolicyRefuses() = runTest {
        val manifest = parse(separateSets, policy = DashUrlPolicy.SameOrigin)
        val presentation = DashHls.presentation(manifest.periods.single())
        val opened = mutableListOf<String>()
        val io = DashHlsMediaIo(presentation, manifest, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.SameOrigin, { url ->
            opened += url
            BytesMediaIo(ByteArray(10))
        }, null, { 0L })
        assertEquals(DashHlsMediaIo.HLS_MEDIA_TYPE, io.contentType)
        assertEquals(presentation.master, io.readAll().decodeToString())
        val video = presentation.tracks.first()
        val playlist = assertNotNull(io.openRelated(video.address)).readAll().decodeToString()
        assertTrue(playlist.startsWith("#EXTM3U"), playlist)
        assertNotNull(io.openRelated("https://cdn.test/vod/v-hi-1.m4s"))
        assertEquals(listOf("https://cdn.test/vod/v-hi-1.m4s"), opened)
        assertNull(io.openRelated("https://${DashHls.HOST}/nothing.m3u8"), "an unknown stand-in address opens nothing")
        assertFailsWith<DashUrlRefusedException> { io.openRelated("https://elsewhere.test/x.m4s") }
        assertEquals(1, opened.size, "a refused address is never requested")
    }

    @Test
    fun aLivePlaylistKeepsItsSequenceWhenARefreshedManifestRenumbers() = runTest {
        var now = 61_000_000L
        val first = parse(live)
        // The refreshed manifest counts from 1 for the same segments, as a packager may.
        val renumbered = parse(live.replace("startNumber=\"100\"", "startNumber=\"1\""))
        val presentation = DashHls.presentation(first.periods.single(), live = true)
        val io = DashHlsMediaIo(presentation, first, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default, { BytesMediaIo(ByteArray(0)) }, { renumbered }, { now })
        val address = presentation.tracks.single().address
        val before = io.openRelated(address)!!.readAll().decodeToString()
        assertTrue("#EXT-X-MEDIA-SEQUENCE:125" in before, before)
        now += 4_000_000L
        val after = io.openRelated(address)!!.readAll().decodeToString()
        assertTrue("v-31.m4s" in after, "the refreshed manifest's addresses are used: $after")
        // Two segments later the window starts two further on, whatever the manifest calls it.
        assertTrue("#EXT-X-MEDIA-SEQUENCE:127" in after, after)
    }

    @Test
    fun aSegmentIndexNamesTheSegmentsOfASingleFile() = runTest {
        val file = isoFile(listOf(1000L to 2_000L, 1500L to 2_000L, 700L to 1_000L))
        val manifest = parse(
            """
            <MPD type="static" mediaPresentationDuration="PT5S">
                <Period>
                    <AdaptationSet contentType="video" mimeType="video/mp4">
                        <Representation id="v" bandwidth="500000">
                            <BaseURL>single.mp4</BaseURL>
                            <SegmentBase/>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
            """.trimIndent(),
        )
        val presentation = DashHls.presentation(manifest.periods.single())
        val io = DashHlsMediaIo(presentation, manifest, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default, { url ->
            assertEquals("https://cdn.test/vod/single.mp4", url)
            BytesMediaIo(file.bytes)
        }, null, { 0L })
        val playlist = io.openRelated(presentation.tracks.single().address)!!.readAll().decodeToString()
        assertTrue("#EXT-X-MAP:URI=\"https://cdn.test/vod/single.mp4\",BYTERANGE=\"${file.indexAt}@0\"" in playlist, playlist)
        val anchor = file.indexAt + file.indexSize
        assertEquals(
            listOf("#EXT-X-BYTERANGE:1000@$anchor", "#EXT-X-BYTERANGE:1500@${anchor + 1000}", "#EXT-X-BYTERANGE:700@${anchor + 2500}"),
            playlist.lines().filter { it.startsWith("#EXT-X-BYTERANGE") },
        )
        assertEquals(listOf("#EXTINF:2.000000,", "#EXTINF:2.000000,", "#EXTINF:1.000000,"), playlist.lines().filter { it.startsWith("#EXTINF") })
    }

    @Test
    fun aSegmentIndexThatPointsAtIndexesIsRefused() {
        val box = sidx(listOf(100L to 1000L), hierarchical = true)
        assertFailsWith<DashUnsupportedException> { SegmentIndex.parse(box, 0) }
    }

    /** An ISO file of an `ftyp`, a `moov` and a `sidx` box with [references] of (bytes, milliseconds), then the media. */
    private class IsoFile(val bytes: ByteArray, val indexAt: Long, val indexSize: Long)

    private fun isoFile(references: List<Pair<Long, Long>>): IsoFile {
        val ftyp = box("ftyp", ByteArray(8))
        val moov = box("moov", ByteArray(40))
        val index = sidx(references)
        val media = ByteArray(references.sumOf { it.first }.toInt())
        return IsoFile(ftyp + moov + index + media, (ftyp.size + moov.size).toLong(), index.size.toLong())
    }

    private fun sidx(references: List<Pair<Long, Long>>, hierarchical: Boolean = false): ByteArray {
        val body = ArrayList<Byte>()
        fun u32(value: Long) = repeat(4) { body += (value ushr (24 - 8 * it)).toByte() }
        fun u16(value: Int) = repeat(2) { body += (value ushr (8 - 8 * it)).toByte() }
        u32(0) // version 0, no flags
        u32(1) // reference_ID
        u32(1000) // timescale: milliseconds
        u32(0) // earliest_presentation_time
        u32(0) // first_offset
        u16(0) // reserved
        u16(references.size)
        for ((size, duration) in references) {
            u32(size or if (hierarchical) 0x8000_0000L else 0L)
            u32(duration)
            u32(0x9000_0000L) // starts with SAP type 1
        }
        return box("sidx", body.toByteArray())
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.encodeToByteArray() + payload
    }

    private suspend fun MediaIo.readAll(): ByteArray {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            for (i in 0 until count) out += buffer[i]
        }
        return out.toByteArray()
    }

    /** Bytes in memory, seekable, as a segment reader serves them. */
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
