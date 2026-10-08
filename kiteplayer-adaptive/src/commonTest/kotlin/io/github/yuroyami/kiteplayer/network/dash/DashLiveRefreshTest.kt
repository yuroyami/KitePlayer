package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A refresh of a live manifest that adds, removes or reorders sets or representations keeps each
 * track on its own set and representation, by their `id`, and a track whose set or representation
 * is gone is refused with a warning rather than served another one's segments (#406).
 */
class DashLiveRefreshTest {

    private val base = "https://example.test/manifest.mpd"

    private fun representation(id: String, bandwidth: Int = 1000, codecs: String? = null) =
        """<Representation id="$id" bandwidth="$bandwidth"${codecs?.let { " codecs=\"$it\"" } ?: ""}>""" +
            """<SegmentTemplate initialization="$id-init.mp4" media="$id-${'$'}Number${'$'}.m4s" timescale="1" duration="1"/>""" +
            "</Representation>"

    private fun set(mime: String, id: String, vararg representations: String = arrayOf(representation(id))) =
        """<AdaptationSet id="$id" mimeType="$mime">${representations.joinToString("")}</AdaptationSet>"""

    private fun mpd(body: String, periodId: String? = null) =
        """<MPD type="dynamic" mediaPresentationDuration="PT5000S" """ +
            """availabilityStartTime="1970-01-01T00:00:00Z" minimumUpdatePeriod="PT1S" timeShiftBufferDepth="PT10S">""" +
            "<Period${periodId?.let { " id=\"$it\"" } ?: ""}>$body</Period></MPD>"

    private val video = set("video/mp4", "v")
    private val english = set("audio/mp4", "en")
    private val french = set("audio/mp4", "fr")

    /** What a track's playlist lists before a refresh, and after it, or the failure after it. */
    private class Refresh(val before: String, val after: Result<String>, val warnings: List<PlaybackWarning>)

    private suspend fun refresh(first: String, fresh: String, pick: (List<DashHlsTrack>) -> DashHlsTrack): Refresh {
        val firstManifest = DashManifestParser.parse(first, base)
        val freshManifest = DashManifestParser.parse(fresh, base)
        var now = 20_000_000L
        val presentation = DashHls.presentation(firstManifest.periods.single(), live = true)
        val io = DashHlsMediaIo(presentation, firstManifest, base, DashUrlPolicy.Default, { BytesMediaIo(ByteArray(0)) }, { freshManifest }, { now })
        val warnings = mutableListOf<PlaybackWarning>()
        io.setWarningSink { warnings += it }
        val track = pick(presentation.tracks)
        val before = io.openRelated(track.address)!!.readAll().decodeToString()
        now += 2_000_000L
        val after = runCatching { io.openRelated(track.address)!!.readAll().decodeToString() }
        return Refresh(before, after, warnings)
    }

    /** The prefixes of the segment names a playlist lists. */
    private fun segments(playlist: String): Set<String> =
        playlist.lines().filter { it.endsWith(".m4s") && !it.startsWith("#") }.map { it.substringAfterLast('/').substringBefore('-') }.toSet()

    private fun List<DashHlsTrack>.video() = first { it.role == DashHlsRole.Video }

    private fun List<DashHlsTrack>.audio(id: String) = first { it.role == DashHlsRole.Audio && it.set.id == id }

    @Test
    fun reorderedSetsKeepEachTrackOnItsOwnSet() = runTest {
        val first = mpd(video + english + french)
        val fresh = mpd(french + english + video)
        val pictures = refresh(first, fresh) { it.video() }
        assertEquals(setOf("v"), segments(pictures.before))
        assertEquals(setOf("v"), segments(pictures.after.getOrThrow()))
        val sound = refresh(first, fresh) { it.audio("en") }
        assertEquals(setOf("en"), segments(sound.after.getOrThrow()))
    }

    @Test
    fun anAddedSetMovesNoTrack() = runTest {
        val thumbnails = set("image/jpeg", "thumb")
        val refreshed = refresh(mpd(video + english), mpd(thumbnails + video + english)) { it.video() }
        assertEquals(setOf("v"), segments(refreshed.after.getOrThrow()))
        assertTrue(refreshed.warnings.isEmpty())
    }

    @Test
    fun reorderedRepresentationsKeepEachTrackOnItsOwn() = runTest {
        val low = representation("low", 500_000)
        val high = representation("high", 2_000_000)
        val first = mpd(set("video/mp4", "v", low, high) + english)
        val fresh = mpd(set("video/mp4", "v", high, low) + english)
        val refreshed = refresh(first, fresh) { tracks -> tracks.first { it.role == DashHlsRole.Video && it.representation.id == "low" } }
        assertEquals(setOf("low"), segments(refreshed.before))
        assertEquals(setOf("low"), segments(refreshed.after.getOrThrow()))
    }

    @Test
    fun aRemovedSetIsRefusedWithAWarningAndNotServedAnother() = runTest {
        val refreshed = refresh(mpd(video + english + french), mpd(video + french)) { it.audio("en") }
        assertIs<DashTrackGoneException>(refreshed.after.exceptionOrNull())
        val warning = assertIs<PlaybackWarning.SegmentSkipped>(refreshed.warnings.single())
        assertTrue("en" in warning.detail, warning.detail)
    }

    @Test
    fun aRemovedRepresentationIsRefused() = runTest {
        val low = representation("low", 500_000)
        val high = representation("high", 2_000_000)
        val refreshed = refresh(mpd(set("video/mp4", "v", low, high)), mpd(set("video/mp4", "v", high))) { tracks ->
            tracks.first { it.representation.id == "low" }
        }
        assertIs<DashTrackGoneException>(refreshed.after.exceptionOrNull())
    }

    @Test
    fun aReplacedIdOrAChangedCodecIsNoLongerTheTrack() = runTest {
        val renamed = refresh(mpd(video + english), mpd(video + set("audio/mp4", "en2", representation("en")))) { it.audio("en") }
        assertIs<DashTrackGoneException>(renamed.after.exceptionOrNull())
        val recoded = refresh(
            mpd(set("video/mp4", "v", representation("v", codecs = "avc1.64001f"))),
            mpd(set("video/mp4", "v", representation("v", codecs = "hvc1.1.6.L93.B0"))),
        ) { it.video() }
        assertIs<DashTrackGoneException>(recoded.after.exceptionOrNull())
    }

    @Test
    fun aRefreshThatChangesNothingServesTheSameTracks() = runTest {
        val body = mpd(video + english + french)
        for (pick in listOf<(List<DashHlsTrack>) -> DashHlsTrack>({ it.video() }, { it.audio("en") }, { it.audio("fr") })) {
            val refreshed = refresh(body, body, pick)
            assertEquals(segments(refreshed.before), segments(refreshed.after.getOrThrow()))
            assertTrue(refreshed.warnings.isEmpty())
        }
    }

    @Test
    fun theBindingNeverTakesAnotherSetsPlace() {
        val first = DashManifestParser.parse(mpd(video + english + french), base).periods.single()
        val tracks = DashHls.presentation(first, live = true).tracks
        val reordered = DashManifestParser.parse(mpd(french + english + video), base).periods.single()
        assertEquals(2 to 0, DashPeriods.bind(tracks.video(), reordered))
        assertEquals(1 to 0, DashPeriods.bind(tracks.audio("en"), reordered))
        assertEquals(0 to 0, DashPeriods.bind(tracks.audio("fr"), reordered))
        val noEnglish = DashManifestParser.parse(mpd(video + french), base).periods.single()
        assertEquals(null, DashPeriods.bind(tracks.audio("en"), noEnglish))
        // A set of another kind that took the picture's id is not the picture.
        val imageTookTheId = DashManifestParser.parse(mpd(set("image/jpeg", "v") + english), base).periods.single()
        assertEquals(null, DashPeriods.bind(tracks.video(), imageTookTheId))
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
