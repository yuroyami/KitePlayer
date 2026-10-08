@file:OptIn(RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sink dates its sound at the ear, not at the device (#495).
 *
 * The render callback's timestamp ends at the device. The route from there to the ear takes a few
 * milliseconds on a built-in speaker and far more on a Bluetooth route, and the system reports
 * how long. These cases replace that report with a fake one, to drive a route change on demand,
 * and then read the real one from this Mac.
 */
class CoreAudioSinkOutputLatencyTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun theReportedRouteLatencyIsInTheSinksDeadline() = runBlocking {
        val devices = FakeLatency(reported = BLUETOOTH)
        val sink = sinkWith(devices)
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            assertEquals(BLUETOOTH, sink.outputLatencyNanos)
            assertEquals(LatencyQuality.Exact, sink.latencyQuality, "the system described the route")
            sink.start()
            awaitDevice { sink.callbacks > 0 }
            if (sink.callbacks == 0L) return@runBlocking println("skipped: this machine's output never called back")
            val latency = sink.latencyNanos()
            assertTrue(latency >= BLUETOOTH - SLACK, "the route's 180 ms must be in the figure, was $latency ns")
            assertTrue(latency < BLUETOOTH + ONE_BUFFER, "and nothing but a device buffer beside it, was $latency ns")
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun aRouteChangeMovesTheDeadlineWithoutReopening() = runBlocking {
        val devices = FakeLatency(reported = BLUETOOTH)
        val sink = sinkWith(devices)
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            sink.start()
            awaitDevice { sink.callbacks > 0 }
            if (sink.callbacks == 0L) return@runBlocking println("skipped: this machine's output never called back")

            // The headphones leave and the speaker reports 4 ms.
            devices.reported = SPEAKER
            devices.fire()
            assertEquals(SPEAKER, sink.outputLatencyNanos)
            val before = sink.callbacks
            awaitDevice { sink.callbacks > before + 1 }
            val latency = sink.latencyNanos()
            assertTrue(latency < SPEAKER + ONE_BUFFER, "the next buffer must be dated with the new route, was $latency ns")

            // A route the system does not describe adds nothing, and the quality says so.
            devices.reported = null
            devices.fire()
            assertEquals(0L, sink.outputLatencyNanos)
            assertEquals(LatencyQuality.Estimated, sink.latencyQuality)
        } finally {
            sink.stop()
            sink.close()
        }
        assertEquals(1, devices.releases, "close releases the latency watch")
    }

    @Test
    fun aNoticeAfterCloseDoesNothing() = runBlocking {
        val devices = FakeLatency(reported = BLUETOOTH)
        val sink = sinkWith(devices)
        sink.openWithRing(format) { it.sampleRate / 4 }
        val late = assertNotNull(devices.listener)
        sink.close()
        devices.reported = SPEAKER
        late()
        assertEquals(0L, sink.outputLatencyNanos, "a closed sink keeps no figure and follows no notice")
        assertEquals(LatencyQuality.Estimated, sink.latencyQuality)
    }

    @Test
    fun thisMacReportsALatencyForItsDefaultOutput() {
        if (MacOutputDevices.defaultOutputDevice() == 0u) return println("skipped: this Mac has no default output")
        val latency = assertNotNull(MacOutputDevices.outputLatencyNanos(0u), "the default output must answer its latency")
        println("the default output reports $latency ns from the device to the ear")
        assertTrue(latency in 0..10_000_000_000L, "was $latency ns")
    }

    @Test
    fun thePlatformLatencyWatchRegistersAndCloseReleasesIt() {
        if (MacOutputDevices.defaultOutputDevice() == 0u) return println("skipped: this Mac has no default output")
        val before = HardwareListener.liveCount
        val watch = assertNotNull(MacOutputDevices.watchOutputLatency(0u) { })
        assertTrue(HardwareListener.liveCount > before + 1, "the default output and its device are both watched")
        watch.close()
        assertEquals(before, HardwareListener.liveCount, "close must remove every listener")
    }

    @Test
    fun framesAndSecondsBecomeNanoseconds() {
        assertEquals(10_000_000L, latencyFramesToNanos(480, 48_000.0))
        assertEquals(0L, latencyFramesToNanos(0, 44_100.0))
        assertNull(latencyFramesToNanos(480, 0.0), "a device with no rate has no latency to give")
        assertNull(latencyFramesToNanos(-1, 48_000.0))
        assertEquals(180_000_000L, latencySecondsToNanos(0.18))
        assertNull(latencySecondsToNanos(Double.NaN))
        assertNull(latencySecondsToNanos(-0.01))
    }

    private fun sinkWith(devices: AppleOutputDevices) = CoreAudioSink(
        policy = AppleAudioSessionPolicy.ApplicationManaged,
        leaseManager = sharedAppleAudioSessionLeaseManager,
        outputDevices = devices,
    )

    /** Answers the latency a test sets, and fires the route notice when the test says so. */
    private class FakeLatency(var reported: Long?) : AppleOutputDevices {
        var listener: (() -> Unit)? = null
        var releases = 0

        override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable? = null
        override fun watchDevice(device: UInt, onLost: (detail: String) -> Unit): AutoCloseable? = null
        override fun devices(): List<AudioOutputDevice> = emptyList()
        override fun deviceFor(id: String): UInt? = null
        override fun outputLatencyNanos(device: UInt): Long? = reported

        override fun watchOutputLatency(device: UInt, onChange: () -> Unit): AutoCloseable {
            listener = onChange
            return AutoCloseable {
                releases++
                listener = null
            }
        }

        fun fire() {
            (listener ?: error("nothing is watching the latency"))()
        }
    }

    private companion object {
        const val BLUETOOTH = 180_000_000L
        const val SPEAKER = 4_000_000L

        /** The deadline is read a moment after the callback that set it. */
        const val SLACK = 60_000_000L

        /** A device buffer and the time to read it: far below the 176 ms between the two routes. */
        const val ONE_BUFFER = 100_000_000L
    }
}
