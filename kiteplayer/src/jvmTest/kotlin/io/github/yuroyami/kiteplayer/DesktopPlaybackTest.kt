package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.io.ofFile
import io.github.yuroyami.kiteplayer.output.DesktopOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * The end-to-end desktop proof: one dependency line, a real file, real audio out, real progress.
 *
 * Everything else in this phase tests a layer. This tests the assembly the way a consumer meets it:
 * `KitePlayer()`, `open`, `play`, and the clock moving. It opens a real device,
 * so it skips itself when the machine has no audio mixer rather than failing for the wrong reason.
 */
class DesktopPlaybackTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun theDefaultDesktopStackPlaysARealFileAndTheClockMoves() = runBlocking {
        val file = requireTestMedia(media, "no $MEDIA to play; run scripts/testmedia.sh")
        playsAndTheClockMoves(MediaItem(file.absolutePath))
    }

    @Test
    fun theSameFilePlaysThroughTheFileDoor() = runBlocking {
        val file = requireTestMedia(media, "no $MEDIA to play; run scripts/testmedia.sh")
        playsAndTheClockMoves(MediaItem.from(MediaIo.ofFile(file), file.name))
    }

    @Test
    fun aPlayerBoundToAnOutputDeviceThatIsNotThereFailsTheOpenTyped() = runBlocking {
        val file = requireTestMedia(media, "no $MEDIA to play; run scripts/testmedia.sh")
        val defaults = assertNotNull(KitePlayerPlatform.backendsOrNull(), "no default desktop backends")
        val player = KitePlayer.create(
            PlayerConfig(backends = defaults.copy(output = DesktopOutputBackend.withAudioOutputDevice("no such device"))),
        )
        try {
            val failure = assertFailsWith<PlaybackException> { player.open(MediaItem(file.absolutePath)) }
            val error = assertIs<PlaybackError.AudioDeviceUnavailable>(failure.error)
            assertEquals("no such device", error.device)
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun playsAndTheClockMoves(item: MediaItem) {
        assumeTrue("no audio mixer on this host", AudioSystem.getMixerInfo().isNotEmpty())

        val player = KitePlayer()
        try {
            player.open(item)
            player.play()

            // Two seconds of wall clock is enough to prove the pipeline turns; the assertion is on
            // the engine's own position, not on a sleep, so a stalled pipeline fails rather than
            // passing slowly.
            val advanced = withTimeoutOrNull(20.seconds) {
                while (player.progress.value.position.inWholeMilliseconds < 1_000) {
                    kotlinx.coroutines.delay(50)
                }
                true
            }
            assertTrue(advanced == true, "the position never reached 1s: ${player.progress.value}")

            val snapshot = player.state.value
            assertTrue(
                snapshot.tracks.all.any { it.kind == TrackKind.Video },
                "no video track was reported: ${snapshot.tracks.all}",
            )
            assertNotNull(snapshot.tracks.selectedAudio, "no audio track was selected")

            // On macOS the default desktop stack decodes this H.264 clip with VideoToolbox (#237).
            if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
                val decode = withTimeoutOrNull(5.seconds) {
                    player.stats.first { it.hardwareDecode != HwdecStatus.Software }.hardwareDecode
                } ?: player.stats.value.hardwareDecode
                assertEquals(HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox), decode)
            }
            println(
                "desktop played to ${player.progress.value.position}, " +
                    "tracks=${snapshot.tracks.all.size}, size=${snapshot.videoSize}, " +
                    "decode=${player.stats.value.hardwareDecode}",
            )
        } finally {
            player.closeAndAwait()
        }
    }

    /**
     * The gapless queue on the desktop output, which is a writer thread feeding a Java Sound line
     * rather than a device callback: the same lossless file twice plays on one line, which is never
     * stopped, paused or drained between the two items. See `docs/gapless-queue.md`.
     */
    @Test
    fun theSameLosslessFileTwiceJoinsOnOneLineWithNoStop() = runBlocking {
        val flac = sequenceOf(
            System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, QUEUE_MEDIA) },
            File("testmedia/$QUEUE_MEDIA"),
            File("../testmedia/$QUEUE_MEDIA"),
        ).filterNotNull().firstOrNull { it.isFile }.let { requireTestMedia(it, "no $QUEUE_MEDIA to play; run scripts/testmedia.sh") }
        assumeTrue("no audio mixer on this host", AudioSystem.getMixerInfo().isNotEmpty())

        val defaults = assertNotNull(KitePlayerPlatform.backendsOrNull(), "no default desktop backends")
        val counts = LineCounts()
        val player = KitePlayer.create(
            PlayerConfig(backends = defaults.copy(output = CountingOutput(defaults.output ?: DesktopOutputBackend, counts))),
        )
        try {
            val item = MediaItem(flac.absolutePath)
            player.openQueue(listOf(item, item))
            player.play()
            // Seven seconds of playback: the whole first item and one second of the second.
            val joined = withTimeoutOrNull(30.seconds) {
                while (!(player.state.value.queueIndex == 1 && player.position() >= 1.seconds)) kotlinx.coroutines.delay(10)
                true
            }
            assertTrue(joined == true, "the second item did not play: ${player.state.value.queueIndex}, ${player.position()}")
            val warnings = player.warningHistory().map { it.warning }
            println("desktop gapless join: underruns=${player.stats.value.audioUnderruns} warnings=$warnings")

            assertEquals(PlaybackStatus.Playing, player.state.value.status, "the second item plays")
            assertEquals(emptyList(), warnings.filterIsInstance<PlaybackWarning.GaplessFallback>(), "no fallback")
            assertEquals(1, counts.opens.get(), "one line for both items")
            assertEquals(0, counts.stops.get(), "the line never stopped between the items")
            assertEquals(0, counts.pauses.get(), "the line never paused between the items")
            assertEquals(0, counts.drains.get(), "the line never drained between the items")
        } finally {
            player.closeAndAwait()
        }
    }

    /** Every call the engine made on the line. Atomic, because the engine calls from its own threads. */
    private class LineCounts {
        val opens = AtomicInteger()
        val stops = AtomicInteger()
        val pauses = AtomicInteger()
        val drains = AtomicInteger()
    }

    private class CountingSink(private val real: AudioSink, private val counts: LineCounts) : AudioSink by real {
        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            counts.opens.incrementAndGet()
            return real.open(request, render)
        }

        override suspend fun stop() {
            counts.stops.incrementAndGet()
            real.stop()
        }

        override suspend fun setPaused(paused: Boolean): Boolean {
            if (paused) counts.pauses.incrementAndGet()
            return real.setPaused(paused)
        }

        override suspend fun drain() {
            counts.drains.incrementAndGet()
            real.drain()
        }
    }

    private class CountingOutput(private val real: OutputBackend, counts: LineCounts) : OutputBackend by real {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = real.audioSink.name
            override suspend fun create(): AudioSink = CountingSink(real.audioSink.create(), counts)
        }
    }

    private companion object {
        const val MEDIA = "sync1080p30.mp4"
        const val QUEUE_MEDIA = "audio-flac.flac"
    }
}
