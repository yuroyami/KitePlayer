package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every address a DASH reader hands out in a playlist stays resolvable for as long as that playlist
 * is valid (#405), and the initializations it read go once nothing still listed needs them (#407).
 */
class DashRetentionTest {

    private val base = "https://example.test/manifest.mpd"
    private val ttml = "<tt><body><p begin=\"0s\" end=\"1s\">ok</p></body></tt>".encodeToByteArray()

    private fun set(mime: String, id: String, duration: Int = 1) =
        """<AdaptationSet id="$id" mimeType="$mime"><Representation id="$id" bandwidth="1000">""" +
            """<SegmentTemplate initialization="$id-init.mp4" media="$id-${'$'}Number${'$'}.m4s" timescale="1" duration="$duration"/>""" +
            """</Representation></AdaptationSet>"""

    private fun static(seconds: Int, body: String) =
        DashManifestParser.parse("""<MPD type="static" mediaPresentationDuration="PT${seconds}S">$body</MPD>""", base)

    private fun reader(manifest: DashManifest, live: Boolean = false, now: () -> Long = { 0L }, initBudgetBytes: Long? = null, fetched: MutableList<String> = mutableListOf()): Pair<DashHlsPresentation, DashHlsMediaIo> {
        val presentation = DashHls.presentation(manifest.periods.first(), live = live)
        val open: suspend (String) -> MediaIo = { url -> fetched += url; BytesMediaIo(ttml) }
        val io = if (initBudgetBytes == null) {
            DashHlsMediaIo(presentation, manifest, base, DashUrlPolicy.Default, open, null, now)
        } else {
            DashHlsMediaIo(presentation, manifest, base, DashUrlPolicy.Default, open, null, now, initBudgetBytes = initBudgetBytes)
        }
        return presentation to io
    }

    private suspend fun DashHlsMediaIo.listed(address: String): List<String> =
        openRelated(address)!!.readAll().decodeToString().lines().filter { it.startsWith("https://") }

    private suspend fun DashHlsMediaIo.maps(address: String): List<String> =
        openRelated(address)!!.readAll().decodeToString().lines()
            .filter { it.startsWith("#EXT-X-MAP:") }
            .map { it.substringAfter("URI=\"").substringBefore('"') }

    /** The issue's case: a subtitle track one segment longer than the old cap lost its start. */
    @Test
    fun aStaticSubtitleTrackLongerThanTheOldCapKeepsItsFirstSegment() = runTest {
        val manifest = static(5000, "<Period>" + set("video/mp4", "v") + set("application/ttml+xml", "s") + "</Period>")
        val (presentation, io) = reader(manifest)
        val urls = io.listed(presentation.tracks.first { it.role == DashHlsRole.Subtitles }.address)
        assertEquals(5000, urls.size)
        assertNotNull(io.openRelated(urls.last()), "the last segment")
        assertNotNull(io.openRelated(urls.first()), "the first segment, which the trim to 4,096 used to drop")
    }

    /** Two languages read one after the other shared the cap, and the first lost 104 of its segments. */
    @Test
    fun twoSubtitleLanguagesKeepEverySegment() = runTest {
        val manifest = static(
            4200,
            "<Period>" + set("video/mp4", "v", 2) + set("application/ttml+xml", "en", 2) + set("application/ttml+xml", "fr", 2) + "</Period>",
        )
        val (presentation, io) = reader(manifest)
        val tracks = presentation.tracks.filter { it.role == DashHlsRole.Subtitles }
        assertEquals(2, tracks.size)
        val listed = tracks.associateWith { io.listed(it.address) }
        for ((track, urls) in listed) {
            assertEquals(2100, urls.size)
            val missing = urls.count { io.openRelated(it) == null }
            assertEquals(0, missing, "${track.address} lost $missing of its segments")
        }
    }

    @Test
    fun twoJoinedPeriodsKeepEverySegmentAndInitialization() = runTest {
        val period = { start: Int -> """<Period start="PT${start}S" duration="PT4500S">""" + set("video/mp4", "v$start") + "</Period>" }
        val manifest = static(9000, period(0) + period(4500))
        val (presentation, io) = reader(manifest)
        val video = presentation.tracks.single { it.role == DashHlsRole.Video }
        val urls = io.listed(video.address)
        assertEquals(9000, urls.size)
        assertNotNull(io.openRelated(urls.first()), "the first segment of the first Period")
        assertNotNull(io.openRelated(urls.last()), "the last segment of the second Period")
        // Every MP4 segment is written against one initialization, the only one the playlist names (#566).
        val maps = io.maps(video.address)
        assertEquals(1, maps.size, "one initialization for the track")
        assertNotNull(io.openRelated(maps.single()), "the initialization ${maps.single()}")
    }

    /**
     * A live track's window moves, and what it no longer lists goes once neither its current
     * playlist nor the one before lists it. Everything the current playlist lists stays, so a seek
     * back inside the window finds it.
     */
    @Test
    fun aLiveWindowKeepsWhatItListsAndLetsGoOfWhatItNoLongerDoes() = runTest {
        var now = 20_000_000L
        val manifest = DashManifestParser.parse(
            """<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" minimumUpdatePeriod="PT100S" timeShiftBufferDepth="PT10S">""" +
                "<Period>" + set("video/mp4", "v") + set("application/ttml+xml", "s") + "</Period></MPD>",
            base,
        )
        val (presentation, io) = reader(manifest, live = true, now = { now })
        val subtitles = presentation.tracks.first { it.role == DashHlsRole.Subtitles }.address
        val first = io.listed(subtitles)
        assertTrue(first.isNotEmpty())
        now += 30_000_000L
        io.listed(subtitles)
        now += 1_000_000L
        val current = io.listed(subtitles)
        assertTrue(first.none { it in current }, "the window moved past the first playlist")
        assertNull(io.openRelated(first.first()), "an address no current playlist lists is let go")
        current.forEach { assertNotNull(io.openRelated(it), "an address the current playlist lists: $it") }
    }

    /** The issue's case for #407: every initialization stayed until the reader was gone, close included. */
    @Test
    fun theInitializationsOfJoinedPeriodsGoWhenTheReaderCloses() = runTest {
        val periods = (0 until 100).joinToString("") { n -> """<Period duration="PT1S">""" + set("video/mp4", "v$n") + "</Period>" }
        val manifest = static(100, periods)
        val (presentation, io) = reader(manifest)
        // Each Period's own initialization is read to write its segments again.
        val segments = io.listed(presentation.tracks.single().address)
        assertEquals(100, segments.size)
        segments.forEach { assertNotNull(io.openRelated(it)) }
        assertEquals(100, io.retainedInitializations, "a static presentation keeps what it may need again")
        io.close()
        assertEquals(0, io.retainedInitializations, "closing the reader lets its initializations go")
    }

    /**
     * Past the byte budget the oldest initializations go, and a segment the playlist still lists
     * resolves all the same by reading its Period's initialization again.
     */
    @Test
    fun initializationsPastTheBudgetGoAndAreReadAgainWhenAskedFor() = runTest {
        val periods = (0 until 20).joinToString("") { n -> """<Period duration="PT1S">""" + set("video/mp4", "v$n") + "</Period>" }
        val manifest = static(20, periods)
        val fetched = mutableListOf<String>()
        val budget = ttml.size * 5L
        val (presentation, io) = reader(manifest, initBudgetBytes = budget, fetched = fetched)
        val segments = io.listed(presentation.tracks.single().address)
        segments.forEach { assertNotNull(io.openRelated(it)) }
        assertTrue(io.retainedInitializationBytes <= budget, "kept ${io.retainedInitializationBytes} bytes past a budget of $budget")
        assertEquals(5, io.retainedInitializations)
        fetched.clear()
        assertNotNull(io.openRelated(segments.first()), "a listed segment stays resolvable")
        assertEquals(listOf("https://example.test/v0-1.m4s", "https://example.test/v0-init.mp4"), fetched, "the one let go is read again")
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
