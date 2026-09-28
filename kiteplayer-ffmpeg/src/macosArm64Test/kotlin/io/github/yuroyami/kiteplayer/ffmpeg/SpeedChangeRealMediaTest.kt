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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a live speed change does to the real CoreAudio device (#16). The change rides a precise seek,
 * which stops the device, flushes the ring and starts the device again once the ring refills. So the
 * device plays silence between the stop and the start, and this test measures that gap on each
 * change. It requires only that playback comes back after every change.
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
    fun eachLiveSpeedChangeStopsTheDeviceAndPlaybackComesBack() = runBlocking {
        val log = DeviceLog()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = LoggingOutput(AppleOutputBackend, log)),
                progressInterval = 50.milliseconds,
            ),
        )
        try {
            player.open(MediaItem("$mediaDir/audio-flac.flac"))
            player.play()
            waitFor(10.seconds) { player.position() >= 500.milliseconds }

            val gaps = mutableListOf<Duration>()
            for (speed in listOf(1.25, 1.5, 0.75, 1.0, 2.0)) {
                val before = log.snapshot().size
                player.setSpeed(speed)
                // Back when the device started again after the stop this change caused. The limit is
                // wide for a loaded runner; the gap measured on an Apple M2 is about 60 ms.
                waitFor(5.seconds) {
                    val after = log.snapshot().drop(before)
                    after.any { it.first == "stop" } && after.last().first == "start"
                }
                val after = log.snapshot().drop(before)
                gaps += after.last { it.first == "start" }.second - after.first { it.first == "stop" }.second
                waitFor(5.seconds) { player.state.value.status == PlaybackStatus.Playing }
                delay(300.milliseconds)
            }
            val sorted = gaps.sorted()
            println(
                "speed change: the device was stopped for ${gaps.map { it.inWholeMicroseconds / 1000.0 }} ms, " +
                    "median ${sorted[sorted.size / 2].inWholeMicroseconds / 1000.0} ms, " +
                    "underruns ${player.stats.value.audioUnderruns}",
            )
            assertEquals(PlaybackStatus.Playing, player.state.value.status, "playback came back after every change")
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
