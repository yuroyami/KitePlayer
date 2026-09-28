@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.NativeRingAudioSink
import io.github.yuroyami.kiteplayer.spi.NativeRingHandoff
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.darwin.sysctlbyname
import platform.posix.size_tVar
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gapless handoff on real media and the real CoreAudio sink: the same lossless file twice in a
 * queue joins with no underrun, and the device is never stopped, paused or drained between the two
 * items. See `docs/gapless-queue.md`.
 */
class GaplessRealMediaTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    /** Every call the engine made on the device. Atomic, because the engine calls from its own threads. */
    private class Counts {
        val opens = AtomicInt(0)
        val starts = AtomicInt(0)
        val stops = AtomicInt(0)
        val pauses = AtomicInt(0)
        val drains = AtomicInt(0)
    }

    /** The CoreAudio sink with its calls counted. It stays a ring sink, so the engine keeps the C ring. */
    private class CountingSink(private val real: NativeRingAudioSink, private val counts: Counts) : NativeRingAudioSink by real {
        override suspend fun openWithRing(request: AudioFormat, capacityFrames: (AudioFormat) -> Int): NativeRingHandoff {
            counts.opens.incrementAndGet()
            return real.openWithRing(request, capacityFrames)
        }

        override suspend fun start() {
            counts.starts.incrementAndGet()
            real.start()
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

    private class CountingOutput(private val real: OutputBackend, counts: Counts) : OutputBackend by real {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = real.audioSink.name

            override suspend fun create(): AudioSink {
                val sink = real.audioSink.create()
                return if (sink is NativeRingAudioSink) CountingSink(sink, counts) else sink
            }
        }
    }

    @Test
    fun theSameLosslessFileTwiceJoinsWithNoUnderrunAndNoDeviceStop() = runBlocking {
        val counts = Counts()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(
                    backend = KiteFFmpegMediaBackend(),
                    output = CountingOutput(AppleOutputBackend, counts),
                ),
                progressInterval = 50.milliseconds,
                statsInterval = 100.milliseconds,
            ),
        )
        val openings = AtomicInt(0)
        val watcher = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val flac = MediaItem("$mediaDir/audio-flac.flac")
            player.openQueue(listOf(flac, flac))
            watcher.launch(start = CoroutineStart.UNDISPATCHED) {
                player.state.collect { if (it.status == PlaybackStatus.Opening) openings.incrementAndGet() }
            }
            player.play()

            // A window inside the first item says whether this device underruns with no join at all,
            // which a runner with no audio hardware can.
            waitFor(10.seconds) { player.position() >= 1.seconds }
            val controlStart = player.stats.value.audioUnderruns
            waitFor(10.seconds) { player.position() >= 2500.milliseconds }
            val control = player.stats.value.audioUnderruns - controlStart

            waitFor(10.seconds) { player.position() >= 4500.milliseconds }
            assertEquals(1, player.state.value.preloadedIndex, "the second item is open before the join")
            val joinStart = player.stats.value.audioUnderruns
            waitFor(10.seconds) { player.state.value.queueIndex == 1 && player.position() >= 1.seconds }
            // One stats interval, so the reading covers the whole window.
            delay(150.milliseconds)
            val join = player.stats.value.audioUnderruns - joinStart

            assertEquals(1, counts.opens.value, "the device opened once for both items")
            assertEquals(0, counts.stops.value, "the device never stopped between the items")
            assertEquals(0, counts.pauses.value, "the device never paused between the items")
            assertEquals(0, counts.drains.value, "the device never drained between the items")
            assertEquals(0, openings.value, "the second item did not open from scratch")
            // A hosted runner is a virtual machine with no audio hardware, where a busy host starves
            // the feeder thread at any moment. Underruns are judged on real hardware only.
            when {
                inVirtualMachine() -> println("gapless join underruns not judged in a virtual machine: $join")
                control > 0 -> println("gapless join underruns not judged: this device underran $control times with no join")
                else -> assertEquals(0L, join, "no underrun across the join")
            }
        } finally {
            watcher.cancel()
            player.closeAndAwait()
        }
    }

    /** True inside a virtual machine, where the kernel reports a hypervisor. */
    private fun inVirtualMachine(): Boolean = memScoped {
        val present = alloc<IntVar>()
        val size = alloc<size_tVar>()
        size.value = sizeOf<IntVar>().convert()
        sysctlbyname("kern.hv_vmm_present", present.ptr, size.ptr, null, 0u) == 0 && present.value == 1
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean) {
        val met = withTimeoutOrNull(limit) {
            while (!condition()) delay(10.milliseconds)
            true
        }
        assertNotNull(met, "the condition did not hold within $limit")
    }
}
