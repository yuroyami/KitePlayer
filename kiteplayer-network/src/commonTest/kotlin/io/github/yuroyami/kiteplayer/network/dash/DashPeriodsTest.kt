package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A manifest of several Periods played as one presentation (#403). */
class DashPeriodsTest {

    private fun parse(xml: String) = DashManifestParser.parse(xml, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default)

    private fun set(kind: String, id: String? = null, lang: String? = null, bandwidths: List<Int> = listOf(500_000)) =
        """<AdaptationSet contentType="$kind" mimeType="$kind/mp4"${id?.let { " id=\"$it\"" } ?: ""}${lang?.let { " lang=\"$it\"" } ?: ""}>""" +
            """<SegmentTemplate media="${kind}-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s" timescale="1000" duration="2000"/>""" +
            bandwidths.joinToString("") { """<Representation id="r$it" bandwidth="$it"/>""" } + "</AdaptationSet>"

    @Test
    fun aPeriodWithoutAStartOrALengthTakesThemFromItsNeighbours() {
        val manifest = parse(
            """<MPD type="static" mediaPresentationDuration="PT60S">
                <Period start="PT0S">${set("video")}</Period>
                <Period start="PT30S" duration="PT10S">${set("video")}</Period>
                <Period>${set("video")}</Period>
            </MPD>""",
        )
        val timings = manifest.periodTimings()
        assertEquals(listOf(0L, 30_000_000L, 40_000_000L), timings.map { it.startMicros })
        assertEquals(listOf(30_000_000L, 10_000_000L, 20_000_000L), timings.map { it.durationMicros })
    }

    @Test
    fun aPeriodThatFollowsOneOfUnknownLengthNeedsItsOwnStart() {
        val manifest = parse(
            """<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z">
                <Period start="PT0S">${set("video")}</Period>
                <Period>${set("video")}</Period>
            </MPD>""",
        )
        assertFailsWith<DashUnsupportedException> { manifest.periodTimings() }
    }

    @Test
    fun aTrackFindsItsSetByIdThenByPlaceThenByLanguage() {
        val manifest = parse(
            """<MPD type="static" mediaPresentationDuration="PT30S">
                <Period duration="PT10S">${set("video", id = "v")}${set("audio", id = "en", lang = "en")}${set("audio", id = "de", lang = "de")}</Period>
                <Period duration="PT10S">${set("audio", id = "de", lang = "de")}${set("video", id = "v")}${set("audio", id = "en", lang = "en")}</Period>
                <Period duration="PT10S">${set("audio", lang = "fr")}${set("audio", lang = "de")}${set("video", bandwidths = listOf(200_000, 450_000, 2_000_000))}</Period>
            </MPD>""",
        )
        val (first, second, third) = manifest.periods
        val tracks = DashHls.presentation(first).tracks
        val german = tracks.single { it.set.lang == "de" }
        val video = tracks.single { it.role == DashHlsRole.Video }
        assertEquals(0 to 0, DashPeriods.match(german, first, second), "the same id")
        assertEquals(1 to 0, DashPeriods.match(video, first, second), "the same id")
        assertEquals(1 to 0, DashPeriods.match(german, first, third), "the same place among as many sets of its kind")
        assertEquals(2 to 1, DashPeriods.match(video, first, third), "the representation of the nearest bandwidth")
        val pictureOnly = parse("""<MPD type="static" mediaPresentationDuration="PT9S"><Period>${set("video")}</Period></MPD>""").periods.single()
        assertNull(DashPeriods.match(german, first, pictureOnly), "a Period with no sound gives the sound track nothing")
    }

    /** A five second main Period, then a four second one in another timescale, track and configuration, whose media time starts at 90 s. */
    private val joined = parse(
        """
        <MPD type="static" mediaPresentationDuration="PT9S">
            <Period id="main" start="PT0S" duration="PT5S">
                <AdaptationSet id="1" contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" initialization="a-init.mp4" timescale="1000" duration="2000"/>
                    <Representation id="v" bandwidth="500000"/>
                </AdaptationSet>
                <AdaptationSet id="2" contentType="text" mimeType="text/vtt" lang="de">
                    <Representation id="de" bandwidth="1000"><BaseURL>a.vtt</BaseURL></Representation>
                </AdaptationSet>
            </Period>
            <Period id="ad" duration="PT4S">
                <AdaptationSet id="1" contentType="video" mimeType="video/mp4">
                    <SegmentTemplate media="b-${'$'}Number${'$'}.m4s" initialization="b-init.mp4" timescale="90000" duration="180000"
                                     startNumber="45" presentationTimeOffset="8100000"/>
                    <Representation id="v" bandwidth="400000"/>
                </AdaptationSet>
                <AdaptationSet id="2" contentType="text" mimeType="text/vtt" lang="de">
                    <Representation id="de" bandwidth="1000"><BaseURL>b.vtt</BaseURL></Representation>
                </AdaptationSet>
            </Period>
        </MPD>
        """.trimIndent(),
    )

    private val avcA = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 1), pps = byteArrayOf(0x68, 1))
    private val avcB = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 2), pps = byteArrayOf(0x68, 2))

    private val files = mapOf(
        "a-init.mp4" to Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA),
        "a-1.m4s" to Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(1), 0), Sample(1000, byteArrayOf(2), Fmp4.NON_SYNC))),
        "b-init.mp4" to Mp4Bytes.init(2, 90_000, "vide", "avc1", entry = avcB),
        "b-45.m4s" to Mp4Bytes.segment(2, 8_100_000, listOf(Sample(90_000, byteArrayOf(7), 0), Sample(90_000, byteArrayOf(8), Fmp4.NON_SYNC))),
        "a.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHaupt\n".encodeToByteArray(),
        "b.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nWerbung\n".encodeToByteArray(),
    )

    private fun reader(asked: MutableList<String> = mutableListOf()): Pair<DashHlsPresentation, DashHlsMediaIo> {
        val presentation = DashHls.presentation(joined.periods.first())
        return presentation to DashHlsMediaIo(presentation, joined, "https://cdn.test/vod/movie.mpd", DashUrlPolicy.Default, { url ->
            val name = url.substringAfterLast('/')
            asked += name
            BytesMediaIo(checkNotNull(files[name]) { "no $url" })
        }, null, { 0L })
    }

    @Test
    fun eachPeriodsSegmentsFollowTheLastOnesInOnePlaylist() = runTest {
        val (presentation, io) = reader()
        val video = presentation.tracks.single { it.role == DashHlsRole.Video }
        val playlist = io.openRelated(video.address)!!.readAll().decodeToString().lines()
        val root = "https://${DashHls.HOST}/0-0"
        assertEquals(
            listOf(
                "#EXT-X-MAP:URI=\"$root/p0/init\"",
                "#EXTINF:2.000000,", "$root/p0/1",
                "#EXTINF:2.000000,", "$root/p0/2",
                // The Period ends one second into its third segment.
                "#EXTINF:1.000000,", "$root/p0/3",
                "#EXT-X-DISCONTINUITY",
                "#EXT-X-MAP:URI=\"$root/p5000000/init\"",
                "#EXTINF:2.000000,", "$root/p5000000/45",
                "#EXTINF:2.000000,", "$root/p5000000/46",
                "#EXT-X-ENDLIST",
            ),
            playlist.dropWhile { !it.startsWith("#EXT-X-MAP") }.filter { it.isNotBlank() },
        )
    }

    @Test
    fun aLaterPeriodIsServedOnThePresentationsTimelineAgainstTheFirstInitialization() = runTest {
        val asked = mutableListOf<String>()
        val (presentation, io) = reader(asked)
        val video = presentation.tracks.single { it.role == DashHlsRole.Video }
        io.openRelated(video.address)!!.readAll()
        val root = "https://${DashHls.HOST}/0-0"
        val base = Fmp4.tracks(io.openRelated("$root/p0/init")!!.readAll()).single()
        assertEquals(1L to 1000L, base.id to base.timescale)
        io.openRelated("$root/p0/1")!!.readAll()
        io.openRelated("$root/p5000000/init")!!.readAll()
        val moved = Fmp4.samples(io.openRelated("$root/p5000000/45")!!.readAll(), base)
        // Its media time of 90 s is where the Period begins, five seconds in.
        assertEquals(listOf(5000L, 6000L), moved.map { it.decodeTime })
        assertEquals(listOf(true, false), moved.map { it.isSync })
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 0x67, 2, 0, 0, 0, 2, 0x68, 2, 7), moved[0].data, "its own parameter sets lead its sync sample")
        assertEquals(1, asked.count { it == "b-init.mp4" }, "an initialization is read once: $asked")
    }

    @Test
    fun aLaterPeriodsSubtitlesMoveToWhereThePeriodBegins() = runTest {
        val (presentation, io) = reader()
        val german = presentation.tracks.single { it.role == DashHlsRole.Subtitles }
        val addresses = io.openRelated(german.address)!!.readAll().decodeToString().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, addresses.size, "one subtitle file in each Period")
        assertTrue("00:00:01.000 --> 00:00:02.000" in io.openRelated(addresses[0])!!.readAll().decodeToString())
        assertTrue("00:00:06.000 --> 00:00:07.500\nWerbung" in io.openRelated(addresses[1])!!.readAll().decodeToString())
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
