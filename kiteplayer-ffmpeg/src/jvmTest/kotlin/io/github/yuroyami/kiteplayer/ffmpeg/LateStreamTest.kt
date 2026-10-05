package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A transport stream whose sound starts after the open, and then moves to a new stream, plays that
 * sound with real FFmpeg on the JVM (#509). The fixture joins three recordings of one channel end to
 * end, as a tuner recording of a live channel looks: six seconds of picture alone, longer than
 * FFmpeg's five seconds of analysis at the open, then three with a French sound beeping at 600 Hz,
 * then three where the sound has moved to a new stream that beeps at 1000 Hz. The written file has
 * the picture as stream 0, the French sound as stream 1 and the moved sound as stream 2. The fixture
 * is written by the `ffmpeg` command line, and the test skips where there is none, as the live tests
 * do.
 */
class LateStreamTest {

    @Test
    fun aSoundThatStartsAfterTheOpenPlaysAndFollowsItsChannelToANewStream() = runBlocking {
        val ffmpeg = ffmpegCli ?: return@runBlocking println("SKIP: no ffmpeg on PATH")
        withFixture(ffmpeg) { file, pictureAlone ->
            // Read forward only, as from a multicast, and sent live: what follows the picture alone
            // arrives only once playing starts, so the open finds the picture alone.
            val sender = LiveSender(file.readBytes(), pictureAlone)
            playsTheSoundsInTurn(MediaItem("custom://late.ts", io = { sender }), listedAtTheOpen = false) { sender.resume() }
        }
    }

    @Test
    fun aRecordingWhoseSoundIsListedBeforeItsFormatIsKnownPlaysIt() = runBlocking {
        val ffmpeg = ffmpegCli ?: return@runBlocking println("SKIP: no ffmpeg on PATH")
        withFixture(ffmpeg) { file, _ ->
            // A file FFmpeg can seek in is scanned 5 MB ahead for programme tables at the open, so
            // both sounds are listed then, before any of their packets said a rate or a channel count.
            playsTheSoundsInTurn(MediaItem(file.absolutePath), listedAtTheOpen = true)
        }
    }

    private suspend fun CoroutineScope.playsTheSoundsInTurn(
        item: MediaItem,
        listedAtTheOpen: Boolean,
        onPlay: () -> Unit = {},
    ) {
        val output = PacedOutput()
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), output)))
        try {
            player.attachRendererAndAwait(FlashRecorder())
            val seen = CopyOnWriteArrayList<PlayerEvent>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { player.events.collect { seen += it } }
            withTimeout(30.seconds) { player.open(item) }
            val opened = player.state.value.tracks
            if (listedAtTheOpen) {
                assertEquals(listOf(TrackKind.Video, TrackKind.Audio, TrackKind.Audio), opened.all.map { it.kind })
                assertEquals(null, opened.find(TrackId(1))?.sampleRate, "a rate FFmpeg does not know yet is no rate")
            } else {
                assertEquals(listOf(TrackKind.Video), opened.all.map { it.kind }, "the open found the picture alone")
                assertEquals(null, opened.selectedAudio)
            }

            player.play()
            onPlay()
            assertTrue(
                waitFor(12.seconds) { player.state.value.tracks.selectedAudio == TrackId(1) },
                "the French sound plays: ${player.state.value.tracks}",
            )
            assertEquals("fre", player.state.value.tracks.find(TrackId(1))?.language)
            assertTrue(
                waitFor(10.seconds) { player.state.value.tracks.selectedAudio == TrackId(2) },
                "the moved sound plays: ${player.state.value.tracks}",
            )
            assertTrue(
                waitFor(2.seconds) { player.state.value.tracks.programs.singleOrNull()?.tracks == listOf(TrackId(0), TrackId(2)) },
                "the channel's new table: ${player.state.value.tracks.programs}",
            )
            delay(1.seconds)
            assertEquals(PlaybackStatus.Playing, player.state.value.status)

            val heard = output.beeps.map { it.hertz }
            assertTrue(heard.any { abs(it - FRENCH_HZ) < 60 }, "the French sound was heard: $heard")
            assertTrue(heard.any { abs(it - MOVED_HZ) < 60 }, "the moved sound was heard: $heard")
            assertTrue(
                heard.dropWhile { abs(it - MOVED_HZ) >= 60 }.all { abs(it - MOVED_HZ) < 60 },
                "and nothing else after it: $heard",
            )

            listener.cancel()
            val added = seen.filterIsInstance<PlayerEvent.TracksAdded>().flatMap { event -> event.tracks.map { it.id } }
            assertEquals(if (listedAtTheOpen) emptyList() else listOf(TrackId(1), TrackId(2)), added)
            val chosen = seen.filterIsInstance<PlayerEvent.TrackChosenByPlayer>().filter { it.kind == TrackKind.Audio }
            assertEquals(if (listedAtTheOpen) listOf(TrackId(2)) else listOf(TrackId(1), TrackId(2)), chosen.map { it.track })
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(limit) {
            while (!condition()) delay(50.milliseconds)
            true
        } ?: false

    /**
     * Hands out [bytes] forward only, and holds every read past the first [heldFrom] until [resume],
     * as a live sender has not sent what comes later.
     */
    private class LiveSender(private val bytes: ByteArray, private val heldFrom: Int) : MediaIo {
        private val resumed = CompletableDeferred<Unit>()
        private var served = 0

        fun resume() {
            resumed.complete(Unit)
        }

        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (served >= bytes.size) return -1
            if (served >= heldFrom) resumed.await()
            val limit = if (resumed.isCompleted) bytes.size else heldFrom
            val count = minOf(length, limit - served)
            bytes.copyInto(into, offset, served, served + count)
            served += count
            return count
        }

        override suspend fun seek(position: Long) = error("a live sender cannot seek")

        override fun close() = Unit
    }

    /** Writes the fixture and hands [block] the file and the length of its part with the picture alone. */
    private suspend fun withFixture(ffmpeg: String, block: suspend (File, Int) -> Unit) {
        val directory = Files.createTempDirectory("late").toFile()
        try {
            val video = arrayOf("-c:v", "libx264", "-preset", "veryfast", "-g", "25", "-pix_fmt", "yuv420p")
            fun picture(seconds: Int) = arrayOf("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=$seconds")
            fun beep(hertz: Int) = arrayOf("-f", "lavfi", "-i", "aevalsrc='0.8*sin(2*PI*$hertz*t)*lt(mod(t,1),0.1)':s=48000:c=stereo:d=3")
            val parts = listOf(
                arrayOf(*picture(6), *video, "-output_ts_offset", "0"),
                arrayOf(
                    *picture(3), *beep(FRENCH_HZ), "-map", "0:v", "-map", "1:a", *video, "-c:a", "aac",
                    "-metadata:s:a:0", "language=fre", "-output_ts_offset", "6",
                ),
                arrayOf(
                    *picture(3), *beep(MOVED_HZ), "-map", "0:v", "-map", "1:a", *video, "-c:a", "aac",
                    "-metadata:s:a:0", "language=fre", "-streamid", "1:0x102", "-output_ts_offset", "9",
                ),
            )
            val file = File(directory, "late.ts")
            var pictureAlone = 0
            file.outputStream().use { joined ->
                parts.forEachIndexed { index, arguments ->
                    val part = File(directory, "part$index.ts")
                    val process = ProcessBuilder(ffmpeg, "-v", "error", "-y", *arguments, "-f", "mpegts", part.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val log = process.inputStream.readBytes().decodeToString()
                    assertEquals(0, process.waitFor(), "ffmpeg could not write part $index: $log")
                    if (index == 0) pictureAlone = part.length().toInt()
                    part.inputStream().use { it.copyTo(joined) }
                }
            }
            block(file, pictureAlone)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val FRENCH_HZ = 600
        const val MOVED_HZ = 1000
    }
}
