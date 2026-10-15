package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
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
            pictures.times.clear()
            player.seek(4.seconds, SeekMode.Precise)
            delay(300)
            assertEquals(6_000_000L, pictures.times.firstOrNull(), "the seek showed ${pictures.times.firstOrNull()}")
            assertNear(4.seconds, player.position())
            // And back into the first part.
            pictures.times.clear()
            player.seek(1.seconds, SeekMode.Precise)
            delay(300)
            assertEquals(1_000_000L, pictures.times.firstOrNull(), "the seek back showed ${pictures.times.firstOrNull()}")
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
            delay(300)
            assertEquals(6_500_000L, started.times.firstOrNull(), "the open showed ${started.times.firstOrNull()}")
            assertNear(4_500.milliseconds, again.position())
        } finally {
            again.closeAndAwait()
        }
    }

    /** A position as the sound lands it, within a millisecond of [expected]. */
    private fun assertNear(expected: Duration, actual: Duration) {
        assertTrue((actual - expected).absoluteValue <= 1.milliseconds, "expected $expected, the position is $actual")
    }

    /** A screen with no window that keeps the time of each picture it is handed. */
    private class PictureTimes : VideoRenderer {
        val times: MutableList<Long> = CopyOnWriteArrayList()

        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

        override fun supports(format: PlayerPixelFormat): Boolean = true

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = frame.use {
            times += frame.pts.micros
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
