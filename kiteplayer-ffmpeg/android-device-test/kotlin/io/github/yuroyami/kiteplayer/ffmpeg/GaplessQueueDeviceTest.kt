package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gapless handoff on Android's own audio output: the same lossless file twice in a queue plays
 * on one AudioTrack, which is never stopped, paused or drained between the two items. See
 * `docs/gapless-queue.md`.
 */
@NeedsPushedMedia
internal class GaplessQueueDeviceTest {

    /** Every call the engine made on the device. Atomic, because the engine calls from its own threads. */
    private class Counts {
        val opens = AtomicInteger()
        val stops = AtomicInteger()
        val pauses = AtomicInteger()
        val drains = AtomicInteger()
    }

    private class CountingSink(private val real: AudioSink, private val counts: Counts) : AudioSink by real {
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

    private class CountingOutput(counts: Counts) : OutputBackend by AndroidOutputBackend {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "counting(${AndroidOutputBackend.audioSink.name})"
            override suspend fun create(): AudioSink = CountingSink(AndroidOutputBackend.audioSink.create(), counts)
        }
    }

    @Test
    fun theSameLosslessFileTwiceJoinsOnOneAudioTrackWithNoStop() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("no media dir on this device")
        val counts = Counts()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = CountingOutput(counts)),
                progressInterval = 50.milliseconds,
                statsInterval = 100.milliseconds,
            ),
        )
        try {
            val flac = MediaItem("$mediaDir/audio-flac.flac")
            withTimeout(30_000) { player.openQueue(listOf(flac, flac)) }
            player.play()
            // Seven seconds of playback, with room for the emulator's graphics host, which freezes
            // the whole system for 15 to 17 seconds now and then (#301).
            waitFor(40.seconds) { player.state.value.queueIndex == 1 && player.position() >= 1.seconds }
            // One stats interval, so the reading below covers the join.
            delay(150)
            println("GAPLESS DEVICE underruns=${player.stats.value.audioUnderruns} warnings=${player.warningHistory().map { it.warning }}")

            assertEquals(PlaybackStatus.Playing, player.state.value.status, "the second item plays")
            assertEquals(1, counts.opens.get(), "one AudioTrack for both items")
            assertEquals(0, counts.stops.get(), "the AudioTrack never stopped between the items")
            assertEquals(0, counts.pauses.get(), "the AudioTrack never paused between the items")
            assertEquals(0, counts.drains.get(), "the AudioTrack never drained between the items")
            val fallbacks = player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()
            assertEquals(emptyList(), fallbacks, "the second item followed without a gap")
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean) {
        val met = withTimeoutOrNull(limit) {
            while (!condition()) delay(10)
            true
        }
        assertNotNull(met, "the condition did not hold within $limit")
    }
}
