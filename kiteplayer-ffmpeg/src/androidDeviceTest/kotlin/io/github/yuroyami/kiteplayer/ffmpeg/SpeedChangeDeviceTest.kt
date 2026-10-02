package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Live speed changes on Android's own audio output: the AudioTrack is never stopped, paused,
 * drained or reopened, the ring never runs dry, and the rate the listener hears reaches each new
 * speed once the audio already buffered has played (#373).
 */
@NeedsPushedMedia
internal class SpeedChangeDeviceTest {

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
    fun liveSpeedChangesNeverStopTheAudioTrack() = runBlocking {
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
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/soak30min.mp4")) }
            player.play()
            waitFor(20.seconds) { player.position() >= 500.milliseconds }
            val underrunsAtStart = player.stats.value.audioUnderruns
            val opensAtStart = counts.opens.get()

            // The nudges a watch-together room makes, then the speeds a viewer picks.
            val plan = listOf(0.995, 1.0, 1.005, 1.0, 1.25, 1.5, 0.75, 2.0, 0.5, 1.0)
            val reachedAfter = mutableListOf<Duration>()
            var lastPosition = player.position()
            for (speed in plan) {
                val asked = TimeSource.Monotonic.markNow()
                player.setSpeed(speed)
                var reached: Duration? = null
                // An AudioTrack buffer is deeper than a Mac's, so the new rate is heard later.
                while (asked.elapsedNow() < 2.seconds) {
                    assertEquals(PlaybackStatus.Playing, player.state.value.status, "a change to $speed left Playing")
                    val position = player.position()
                    assertTrue(position >= lastPosition, "the position went back from $lastPosition to $position at $speed")
                    lastPosition = position
                    if (reached == null && player.audioClock().rate == speed) reached = asked.elapsedNow()
                    delay(20)
                }
                reachedAfter += assertNotNull(reached, "the audible rate never reached $speed")
            }
            println(
                "SPEED DEVICE heard after ${reachedAfter.map { it.inWholeMilliseconds }} ms, opens=${counts.opens.get()} " +
                    "stops=${counts.stops.get()} pauses=${counts.pauses.get()} drains=${counts.drains.get()} " +
                    "underruns=${player.stats.value.audioUnderruns - underrunsAtStart}",
            )
            assertEquals(opensAtStart, counts.opens.get(), "a speed change reopened the AudioTrack")
            assertEquals(0, counts.stops.get(), "a speed change stopped the AudioTrack")
            assertEquals(0, counts.pauses.get(), "a speed change paused the AudioTrack")
            assertEquals(0, counts.drains.get(), "a speed change drained the AudioTrack")
            assertEquals(underrunsAtStart, player.stats.value.audioUnderruns, "a speed change ran the ring dry")
            assertTrue(reachedAfter.all { it < 1500.milliseconds }, "the new rate was heard after ${reachedAfter.max()}")
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
