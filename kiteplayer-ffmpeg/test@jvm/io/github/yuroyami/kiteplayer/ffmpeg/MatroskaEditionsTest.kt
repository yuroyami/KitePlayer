package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LinkedFile
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The editions of a Matroska file, as the FFmpeg backend reports them and as the player lists them
 * (#501), on the fixtures `scripts/testmedia.sh` makes in `testmedia/editions`.
 */
class MatroskaEditionsTest {

    private fun fixture(name: String): File {
        val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
        return File(dir, "editions/$name").takeIf { it.isFile }
            ?: error("testmedia is missing editions/$name; run scripts/testmedia.sh")
    }

    @Test
    fun theBackendReportsTheSegmentAndTheRangesAnOrderedEditionPlays() = runBlocking {
        KiteFFmpegMediaBackend().open(MediaItem(fixture("film.mkv").absolutePath)).use { session ->
            val film = session.source
            assertEquals("f11f0000000000000000000000000004", film.segmentUid)
            assertEquals(listOf("Theatrical", "Extended"), film.editions.map { it.title })
            val theatrical = film.editions[0]
            assertEquals(listOf(200L, true, true), listOf(theatrical.uid, theatrical.isDefault, theatrical.isOrdered))
            // The disabled chapter is not played, and the hidden one is.
            assertEquals(
                listOf(Triple("Act one", 0L, 3_000_000L), Triple("Act three", 5_000_000L, 8_000_000L)),
                theatrical.parts!!.map { Triple(it.title, it.start.micros, it.end.micros) },
            )
            assertEquals(listOf(false, true), theatrical.parts!!.map { it.isHidden })
            // As marks, the edition shows neither the disabled chapter nor the hidden one.
            assertEquals(listOf("Act one"), theatrical.chapters.map { it.title })
            assertEquals(3, film.editions[1].parts!!.size)
            assertEquals(Pts(5_000_000L), film.fromSegmentTime(Pts(5_000_000L)))
        }
        KiteFFmpegMediaBackend().open(MediaItem(fixture("season/episode.mkv").absolutePath)).use { session ->
            val episode = session.source
            val parts = episode.editions.single().parts!!
            assertEquals(
                listOf("0e0e0e00000000000000000000000002", null, "ed0ed000000000000000000000000003"),
                parts.map { it.segmentUid },
            )
            assertEquals(listOf(500_000L, 0L, 1_000_000L), parts.map { it.start.micros })
        }
        KiteFFmpegMediaBackend().open(MediaItem(fixture("recap.mkv").absolutePath)).use { session ->
            val recap = session.source
            val linked = recap.editions.single().parts!!.last()
            assertEquals("f11f0000000000000000000000000004" to 200L, linked.segmentUid to linked.segmentEditionUid)
        }
    }

    @Test
    fun theSnapshotListsTheEditionsWithTheDefaultPlaying() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath)) }
            val snapshot = player.state.value
            assertEquals(listOf("Theatrical", "Extended"), snapshot.editions.map { it.title })
            assertEquals(listOf(6.seconds, 8.seconds), snapshot.editions.map { it.duration })
            assertEquals(listOf(true, false), snapshot.editions.map { it.isDefault })
            assertEquals(0, snapshot.edition)
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun mediaWithNoEditionsListsNone() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
            withTimeout(30.seconds) { player.open(MediaItem(File(dir, "sync1080p30.mp4").absolutePath)) }
            assertEquals(emptyList(), player.state.value.editions)
            assertEquals(null, player.state.value.edition)
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun theTheatricalCutPlaysItsTwoPartsAsOneTimeline() = runBlocking {
        val pictures = PictureTimes()
        val sound = SoundTimes()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(pictures)
            player.attachAudioTap(sound)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath)) }
            val opened = player.state.value
            // 0 s to 3 s and 5 s to 8 s of the file, with the hidden last part played and not listed.
            assertEquals(6.seconds, opened.duration)
            assertEquals(listOf(Triple("Act one", 0.seconds, 3.seconds)), opened.chapters.map { Triple(it.title, it.start, it.end) })
            val events = CopyOnWriteArrayList<PlayerEvent>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { player.events.collect { events += it } }
            val seen = CopyOnWriteArrayList<PlaybackStatus>()
            player.play()
            withTimeout(20.seconds) {
                while (player.state.value.status != PlaybackStatus.Ended) {
                    seen += player.state.value.status
                    delay(10)
                }
            }
            delay(200)
            listener.cancel()
            // The second part joins the first in the ring, so the player never leaves Playing.
            val between = seen.dropWhile { it != PlaybackStatus.Playing }.toSet()
            assertEquals(setOf(PlaybackStatus.Playing), between, "the player left Playing between the parts")
            assertEquals(1, events.count { it is PlayerEvent.Ended }, "the item ended once, at the end of its timeline")
            assertEquals(0, events.count { it is PlayerEvent.Opened }, "no part opened as an item")
            val position = player.position()
            assertTrue(position in 5_900.milliseconds..6_100.milliseconds, "the position ended at $position")

            val shown = pictures.times.toList()
            assertTrue(shown.none { it in 3_000_000L until 5_000_000L }, "a picture of the skipped scene was shown: ${shown.filter { it in 3_000_000L until 5_000_000L }}")
            assertTrue(shown.any { it < 3_000_000L } && shown.any { it >= 7_900_000L }, "pictures of both parts were shown: ${shown.first()} to ${shown.last()}")
            val heard = sound.blocks.toList()
            // A block is a few tens of milliseconds, so one may start just short of either edge.
            val skipped = heard.map { it.first.micros }.filter { it in 3_000_000L until 4_950_000L }
            assertTrue(skipped.isEmpty(), "sound of the skipped scene was heard at $skipped")
            val total = heard.sumOf { it.second.inWholeMicroseconds }
            assertTrue(total in 5_900_000L..6_100_000L, "the sound heard lasts $total us")
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aPositionOnTheTimelineIsFoundInThePartThatPlaysIt() = runBlocking {
        val pictures = PictureTimes()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(pictures)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath)) }
            // Four seconds into the timeline is one second into its second part, at 6 s of the file.
            pictures.shown.clear()
            player.seek(4.seconds, SeekMode.Precise)
            val seen = firstPicture(pictures)
            assertEquals(6_000_000L, seen, "the seek showed $seen")
            assertNear(4.seconds, player.position())
            // And back into the first part.
            pictures.shown.clear()
            player.seek(1.seconds, SeekMode.Precise)
            val back = firstPicture(pictures)
            assertEquals(1_000_000L, back, "the seek back showed $back")
            assertNear(1.seconds, player.position())
        } finally {
            player.closeAndAwait()
        }
        // A start position is a position on the timeline too.
        val started = PictureTimes()
        val again = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            again.attachRendererAndAwait(started)
            withTimeout(30.seconds) {
                again.open(MediaItem(fixture("film.mkv").absolutePath, startPosition = 4_500.milliseconds))
            }
            val opened = firstPicture(started)
            assertEquals(6_500_000L, opened, "the open showed $opened")
            assertNear(4_500.milliseconds, again.position())
        } finally {
            again.closeAndAwait()
        }
    }

    @Test
    fun theEpisodePlaysTheSeasonsOpeningItselfAndTheSeasonsEnding() = runBlocking {
        val pictures = PictureTimes()
        val sound = SoundTimes()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(pictures)
            player.attachAudioTap(sound)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("season/episode.mkv").absolutePath)) }
            val opened = player.state.value
            // 0.5 s to 3.5 s of the opening, the episode's own 4 s, and 1 s to 3 s of the ending.
            assertEquals(9.seconds, opened.duration)
            assertEquals(
                listOf(Triple("Opening", 0.seconds, 3.seconds), Triple("Part A", 3.seconds, 7.seconds), Triple("Ending", 7.seconds, 9.seconds)),
                opened.chapters.map { Triple(it.title, it.start, it.end) },
            )
            // The episode's own tracks are the item's, whichever file plays.
            assertEquals(listOf(0, 1), opened.tracks.all.map { it.id.value })
            assertTrue(player.warningHistory().none { it.warning is PlaybackWarning.EditionPartLeftOut })
            val events = CopyOnWriteArrayList<PlayerEvent>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { player.events.collect { events += it } }
            val seen = CopyOnWriteArrayList<PlaybackStatus>()
            player.play()
            withTimeout(25.seconds) {
                while (player.state.value.status != PlaybackStatus.Ended) {
                    seen += player.state.value.status
                    delay(10)
                }
            }
            delay(200)
            listener.cancel()
            val between = seen.dropWhile { it != PlaybackStatus.Playing }.toSet()
            assertEquals(setOf(PlaybackStatus.Playing), between, "the player left Playing between the files")
            assertEquals(1, events.count { it is PlayerEvent.Ended })
            assertEquals(0, events.count { it is PlayerEvent.Opened })
            assertNear(9.seconds, player.position(), within = 100.milliseconds)

            // Each file's pictures, in its order: the opening's, the episode's, then the ending's,
            // each from its own range and each after the one before it.
            val shown = pictures.shown.toList()
            val runs = shown.fold(mutableListOf<MutableList<Pair<Long, VideoSize>>>()) { acc, picture ->
                if (acc.isEmpty() || acc.last().last().second != picture.second) acc += mutableListOf(picture) else acc.last() += picture
                acc
            }
            assertEquals(listOf(256 to 144, 320 to 180, 384 to 216), runs.map { it.first().second.width to it.first().second.height })
            val ranges = listOf(500_000L until 3_500_000L, 0L until 4_000_000L, 1_000_000L until 3_000_000L)
            runs.zip(ranges).forEach { (run, range) ->
                val times = run.map { it.first }
                assertTrue(times.all { it in range }, "a picture outside $range: ${times.filter { it !in range }}")
                assertEquals(times.sorted(), times, "the pictures of one file went back")
                // A part's last picture, when it runs late, goes with its part at the join, as at
                // any gapless join, so up to two pictures of 30 a second may be missing at an edge.
                assertTrue(times.first() - range.first < 70_000L && range.last - times.last() < 100_000L, "the pictures span ${times.first()} to ${times.last()} of $range")
            }
            // The sound follows the same three ranges, and adds up to the timeline.
            val heard = sound.blocks.toList()
            val pieces = heard.fold(mutableListOf<MutableList<Pair<Pts, Duration>>>()) { acc, block ->
                if (acc.isEmpty() || block.first < acc.last().last().first) acc += mutableListOf(block) else acc.last() += block
                acc
            }
            assertEquals(3, pieces.size, "the sound went back ${pieces.size - 1} times")
            pieces.zip(ranges).forEach { (piece, range) ->
                val start = piece.first().first.micros
                val end = piece.last().let { it.first.micros + it.second.inWholeMicroseconds }
                assertTrue(kotlin.math.abs(start - range.first) < 30_000L, "the sound of $range started at $start")
                assertTrue(kotlin.math.abs(end - (range.last + 1)) < 30_000L, "the sound of $range ended at $end")
            }
            val total = heard.sumOf { it.second.inWholeMicroseconds }
            assertTrue(total in 8_900_000L..9_100_000L, "the sound heard lasts $total us")

            // A seek into the ending shows a picture of the ending's file. The play intent outlives
            // the end, so the player pauses first to keep the position where the seek put it.
            player.pause()
            pictures.shown.clear()
            player.seek(8.seconds, SeekMode.Precise)
            delay(500)
            val landed = pictures.shown.firstOrNull()
            assertEquals(2_000_000L to VideoSize(384, 216), landed?.let { it.first to VideoSize(it.second.width, it.second.height) })
            assertNear(8.seconds, player.position())
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aTrackChangeWhileALinkedFilePlaysNamesTheMainFilesTrack() = runBlocking {
        val sound = SoundTimes()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachAudioTap(sound)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("season/episode.mkv").absolutePath)) }
            player.play()
            withTimeout(10.seconds) { while (player.position() < 1.seconds) delay(10) }
            // In the opening, which is another file: the sound goes, and the main file's table says so.
            assertEquals(TrackChange.Applied(TrackKind.Audio, null), player.selectTrack(TrackKind.Audio, null))
            assertEquals(null, player.state.value.tracks.selectedAudio)
            assertEquals(listOf(0, 1), player.state.value.tracks.all.map { it.id.value })
            sound.blocks.clear()
            delay(500)
            assertTrue(sound.blocks.isEmpty(), "sound was heard with no audio track: ${sound.blocks.size} blocks")
            // And comes back as the opening's own sound, matched to the episode's track.
            assertEquals(TrackChange.Applied(TrackKind.Audio, TrackId(1)), player.selectTrack(TrackKind.Audio, TrackId(1)))
            assertEquals(TrackId(1), player.state.value.tracks.selectedAudio)
            withTimeout(5.seconds) { while (sound.blocks.isEmpty()) delay(10) }
            val back = sound.blocks.first().first.micros
            assertTrue(back in 500_000L until 3_500_000L, "the sound came back at $back us, outside the opening's range")
            withTimeout(20.seconds) { while (player.state.value.status != PlaybackStatus.Ended) delay(20) }
            assertEquals(9.seconds, player.state.value.duration)
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun anotherCutOfTheFilmPlaysFromTheSameScene() = runBlocking<Unit> {
        val pictures = PictureTimes()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(pictures)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath)) }
            // Four seconds into the theatrical cut is 6 s of the film, which the extended cut plays at 6 s.
            player.seek(4.seconds, SeekMode.Precise)
            pictures.shown.clear()
            withTimeout(30.seconds) { player.selectEdition(1) }
            val extended = player.state.value
            assertEquals(1, extended.edition)
            assertEquals(1, extended.media?.demux?.edition)
            assertEquals(8.seconds, extended.duration)
            assertEquals(listOf("Act one", "Deleted scene", "Act three"), extended.chapters.map { it.title })
            assertEquals(PlaybackStatus.Paused, extended.status)
            assertNear(6.seconds, player.position())
            // The switch lands with a precise seek after selectEdition returns, so its picture comes
            // later, by as long as the machine takes: a loaded runner took more than 300 ms.
            val switched = firstPicture(pictures)
            assertEquals(6_000_000L, switched, "the switch showed $switched")
            // The deleted scene is not in the theatrical cut, which then starts from its beginning.
            player.seek(4.seconds, SeekMode.Precise)
            pictures.shown.clear()
            withTimeout(30.seconds) { player.selectEdition(0) }
            assertEquals(0, player.state.value.edition)
            assertEquals(6.seconds, player.state.value.duration)
            assertNear(0.seconds, player.position())
            val restarted = firstPicture(pictures)
            assertEquals(0L, restarted, "the switch showed $restarted")
            // A player that plays keeps playing.
            player.play()
            withTimeout(10.seconds) { while (player.position() < 500.milliseconds) delay(10) }
            withTimeout(30.seconds) { player.selectEdition(1) }
            withTimeout(10.seconds) { while (player.state.value.status != PlaybackStatus.Playing) delay(10) }
            assertEquals(1, player.state.value.edition)
            // An edition the media does not have is refused.
            assertFailsWith<IllegalArgumentException> { player.selectEdition(2) }
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun theItemCanAskForAnEditionAndForNoTimelineAtAll() = runBlocking<Unit> {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath, demux = DemuxPolicy(edition = 1))) }
            assertEquals(1, player.state.value.edition)
            assertEquals(8.seconds, player.state.value.duration)
            player.stop()
            // With no timeline, the episode is its own file, five seconds long, and nothing is looked for.
            val plain = MediaItem(fixture("season/episode.mkv").absolutePath, demux = DemuxPolicy(orderedEditions = false))
            withTimeout(30.seconds) { player.open(plain) }
            val opened = player.state.value
            assertNear(5.seconds, opened.duration ?: Duration.ZERO, within = 50.milliseconds)
            assertEquals(listOf("Episode"), opened.editions.map { it.title })
            assertEquals(0, opened.edition)
            assertTrue(player.warningHistory().none { it.warning is PlaybackWarning.EditionPartLeftOut })
            player.stop()
            // Media with no editions has none to select.
            val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
            withTimeout(30.seconds) { player.open(MediaItem(File(dir, "sync1080p30.mp4").absolutePath)) }
            assertFailsWith<IllegalArgumentException> { player.selectEdition(0) }
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aPartWhoseFileIsMissingIsLeftOut() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(fixture("season-no-op/episode.mkv").absolutePath)) }
            val opened = player.state.value
            assertEquals(6.seconds, opened.duration)
            assertEquals(listOf("Part A", "Ending"), opened.chapters.map { it.title })
            val leftOut = player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.EditionPartLeftOut>()
            assertEquals(listOf("0e0e0e00000000000000000000000002" to "Opening"), leftOut.map { it.segmentUid to it.chapter })
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun theItemsLinkedFilesAreFoundWhereTheFolderIsNot() = runBlocking {
        val alone = java.nio.file.Files.createTempDirectory("kiteplayer-editions").toFile()
        try {
            val episode = fixture("season/episode.mkv").copyTo(File(alone, "episode.mkv"))
            val linked = listOf("op.mkv", "ed.mkv").map { LinkedFile(fixture("season/$it").absolutePath) }
            val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
            try {
                // Alone in its folder, the episode finds neither the opening nor the ending.
                withTimeout(30.seconds) { player.open(MediaItem(episode.absolutePath)) }
                assertEquals(4.seconds, player.state.value.duration)
                assertEquals(2, player.warningHistory().count { it.warning is PlaybackWarning.EditionPartLeftOut })
                player.stop()
                withTimeout(30.seconds) { player.open(MediaItem(episode.absolutePath, linkedFiles = linked)) }
                assertEquals(9.seconds, player.state.value.duration)
            } finally {
                player.closeAndAwait()
            }
        } finally {
            alone.deleteRecursively()
        }
    }

    @Test
    fun aChapterPlaysAnotherFilesEditionWhole() = runBlocking {
        val pictures = PictureTimes()
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            player.attachRendererAndAwait(pictures)
            withTimeout(30.seconds) { player.open(MediaItem(fixture("recap.mkv").absolutePath)) }
            val opened = player.state.value
            // The recap's own two seconds, then the film's theatrical cut, six seconds, as one chapter.
            assertEquals(8.seconds, opened.duration)
            assertEquals(
                listOf(Triple("Previously", 0.seconds, 2.seconds), Triple("The film", 2.seconds, 8.seconds)),
                opened.chapters.map { Triple(it.title, it.start, it.end) },
            )
            // Six seconds in is one second into the film's second part, at 6 s of the film.
            pictures.shown.clear()
            player.seek(6.seconds, SeekMode.Precise)
            delay(500)
            assertEquals(6_000_000L to VideoSize(320, 180), pictures.shown.firstOrNull()?.let { it.first to VideoSize(it.second.width, it.second.height) })
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aLoopOfLinksIsCutWhereItComesBack() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(fixture("loop-a.mkv").absolutePath)) }
            val opened = player.state.value
            // A's two seconds, then B's edition: one second of B, and its link back to A left out.
            assertEquals(3.seconds, opened.duration)
            assertEquals(listOf("A", "B"), opened.chapters.map { it.title })
            val leftOut = player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.EditionPartLeftOut>()
            assertEquals(listOf("a0000000000000000000000000000006" to "Back to A"), leftOut.map { it.segmentUid to it.chapter })
        } finally {
            player.closeAndAwait()
        }
    }

    /** A position as the sound lands it, within a millisecond of [expected]. */
    /**
     * The time of the first picture [pictures] is handed after its list was last cleared, waiting up
     * to five seconds for it, or null when none comes.
     */
    private suspend fun firstPicture(pictures: PictureTimes): Long? =
        withTimeoutOrNull(5.seconds) {
            while (pictures.shown.isEmpty()) delay(10)
            pictures.times.first()
        }

    private fun assertNear(expected: Duration, actual: Duration, within: Duration = 1.milliseconds) {
        assertTrue((actual - expected).absoluteValue <= within, "expected $expected, the position is $actual")
    }

    /** A screen with no window that keeps the time and the size of each picture it is handed. */
    private class PictureTimes : VideoRenderer {
        val shown: MutableList<Pair<Long, VideoSize>> = CopyOnWriteArrayList()
        val times: List<Long> get() = shown.map { it.first }

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            shown += frame.pts.micros to frame.size
            true
        }

        override fun vsyncIntervalNanos(): Long? = null

        override fun setViewport(width: Int, height: Int, scale: Float) = Unit

        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

        override val events: Flow<RendererEvent> = emptyFlow()

        override val outputSize: VideoSize? get() = null

        override fun close() = Unit
    }

    /** Keeps the time and the length of each block of sound on its way to the speaker. */
    private class SoundTimes : AudioTap {
        val blocks: MutableList<Pair<Pts, Duration>> = CopyOnWriteArrayList()

        override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
            blocks += pts to format.durationOf(frames).asDuration
        }
    }
}
