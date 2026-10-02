@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.NativeRingAudioSink
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * What a live speed change does to the real CoreAudio device (#16). A change applies to the next
 * audio the feeder converts, so the device must keep running through every change: no stop, no
 * underrun, no Buffering, and the rate the listener hears reaches the new speed once the audio
 * already in the ring has played.
 */
class SpeedChangeRealMediaTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    /** When the engine stopped and started the device. The engine calls from its own threads. */
    private class DeviceLog {
        private val lock = SynchronizedObject()
        private val clock = TimeSource.Monotonic.markNow()
        private val events = mutableListOf<Pair<String, Duration>>()

        fun add(name: String) = synchronized(lock) { events += name to clock.elapsedNow() }

        fun snapshot(): List<Pair<String, Duration>> = synchronized(lock) { events.toList() }
    }

    /** The CoreAudio sink with its stops and starts logged. It stays a ring sink, so the engine keeps the C ring. */
    private class LoggingSink(private val real: NativeRingAudioSink, private val log: DeviceLog) : NativeRingAudioSink by real {
        override suspend fun start() {
            real.start()
            log.add("start")
        }

        override suspend fun stop() {
            log.add("stop")
            real.stop()
        }
    }

    private class LoggingOutput(private val real: OutputBackend, log: DeviceLog) : OutputBackend by real {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = real.audioSink.name

            override suspend fun create(): AudioSink {
                val sink = real.audioSink.create()
                return if (sink is NativeRingAudioSink) LoggingSink(sink, log) else sink
            }
        }
    }

    @Test
    fun liveSpeedChangesNeverStopTheDevice() = runBlocking {
        val log = DeviceLog()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = LoggingOutput(AppleOutputBackend, log)),
                progressInterval = 50.milliseconds,
                videoEnabled = false,
            ),
        )
        try {
            player.open(MediaItem("$mediaDir/soak30min.mp4"))
            player.play()
            waitFor(10.seconds) { player.position() >= 500.milliseconds }
            val eventsAtStart = log.snapshot().size
            val underrunsAtStart = player.stats.value.audioUnderruns

            // The nudges a watch-together room makes, then the speeds a viewer picks.
            val plan = listOf(0.995, 1.0, 1.005, 1.0, 1.25, 1.5, 0.75, 2.0, 0.5, 1.0)
            val reachedAfter = mutableListOf<Duration>()
            var lastPosition = player.position()
            for (speed in plan) {
                val asked = TimeSource.Monotonic.markNow()
                player.setSpeed(speed)
                var reached: Duration? = null
                while (asked.elapsedNow() < 1500.milliseconds) {
                    assertEquals(PlaybackStatus.Playing, player.state.value.status, "a change to $speed left Playing")
                    val position = player.position()
                    assertTrue(position >= lastPosition, "the position went back from $lastPosition to $position at $speed")
                    lastPosition = position
                    if (reached == null && player.audioClock().rate == speed) reached = asked.elapsedNow()
                    delay(20.milliseconds)
                }
                reachedAfter += assertNotNull(reached, "the audible rate never reached $speed")
            }
            val deviceEvents = log.snapshot().drop(eventsAtStart).map { it.first }
            println(
                "live speed change: the audible rate followed after ${reachedAfter.map { it.inWholeMilliseconds }} ms, " +
                    "device events during the changes $deviceEvents, " +
                    "underruns ${player.stats.value.audioUnderruns - underrunsAtStart}",
            )
            assertEquals(emptyList(), deviceEvents, "a speed change stopped or restarted the device")
            assertEquals(underrunsAtStart, player.stats.value.audioUnderruns, "a speed change ran the ring dry")
            assertTrue(reachedAfter.all { it < 1.seconds }, "the new rate was heard after ${reachedAfter.max()}")
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean) {
        val met = withTimeoutOrNull(limit) {
            while (!condition()) delay(5.milliseconds)
            true
        }
        assertNotNull(met, "the condition did not hold within $limit")
    }
}
