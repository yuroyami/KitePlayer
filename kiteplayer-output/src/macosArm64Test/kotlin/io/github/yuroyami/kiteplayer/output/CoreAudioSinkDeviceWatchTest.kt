@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_destroy
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS sink tells the application when the system default output changes.
 *
 * The DefaultOutput unit follows the new default by itself, so the sink keeps playing and only
 * reports. These cases replace the CoreAudio notice with a fake one to drive it on demand.
 * `DefaultOutputSwitchTest` makes the real change, and runs only when asked to.
 */
class CoreAudioSinkDeviceWatchTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun defaultOutputChangeReachesTheSinkEventsAsDeviceChanged() = runBlocking {
        val devices = FakeOutputDevices()
        val sink = sinkWith(devices)
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            assertEquals(1, devices.registrations, "an open sink watches the default output once")
            // Started undispatched, so the collector is subscribed before the notice fires.
            val received = async(start = CoroutineStart.UNDISPATCHED) { sink.events.first() }
            devices.fire("the default output changed to Test Speakers")
            assertEquals(
                AudioSinkEvent.DeviceChanged("the default output changed to Test Speakers"),
                withTimeout(2.seconds) { received.await() },
            )
        } finally {
            sink.close()
        }
        assertEquals(1, devices.releases, "close releases the watch")
    }

    @Test
    fun closeReleasesTheWatchBeforeTheDevice() = runBlocking {
        val order = mutableListOf<String>()
        val devices = FakeOutputDevices(onRelease = { order += "watch" })
        val sink = CoreAudioSink(
            policy = AppleAudioSessionPolicy.ApplicationManaged,
            leaseManager = sharedAppleAudioSessionLeaseManager,
            destroyer = CoreAudioSinkDestroyer { handle ->
                order += "device"
                kprt_sink_destroy(handle)
            },
            outputDevices = devices,
        )
        sink.openWithRing(format) { it.sampleRate / 4 }
        sink.close()
        sink.close()
        assertEquals(listOf("watch", "device"), order, "no notice may reach a sink whose device is going")
    }

    @Test
    fun aRefusedOpenLeavesNoWatchBehind() = runBlocking {
        val devices = FakeOutputDevices()
        val sink = sinkWith(devices)
        assertFailsWith<IllegalStateException> {
            sink.openWithRing(AudioFormat(sampleRate = -1, channels = 2, sampleFormat = SampleFormat.F32)) { 4_800 }
        }
        assertEquals(0, devices.registrations, "a refused open must not watch anything")
        sink.close()
        assertEquals(0, devices.releases)
    }

    @Test
    fun thePlatformWatchRegistersWithCoreAudioAndCloseReleasesIt() {
        val before = HardwareListener.liveCount
        val watch = assertNotNull(
            platformAppleOutputDevices().watchDefaultOutput { },
            "CoreAudio must accept a listener on the default output",
        )
        assertEquals(before + 1, HardwareListener.liveCount)
        watch.close()
        watch.close()
        assertEquals(before, HardwareListener.liveCount, "close must remove the listener exactly once")
    }

    private fun sinkWith(devices: AppleOutputDevices) = CoreAudioSink(
        policy = AppleAudioSessionPolicy.ApplicationManaged,
        leaseManager = sharedAppleAudioSessionLeaseManager,
        outputDevices = devices,
    )

    /** Records registrations and releases, and fires the notice when a test says so. */
    private class FakeOutputDevices(private val onRelease: () -> Unit = {}) : AppleOutputDevices {
        var registrations = 0
        var releases = 0
        private var listener: ((String) -> Unit)? = null

        override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable {
            registrations++
            listener = onChange
            return AutoCloseable {
                releases++
                listener = null
                onRelease()
            }
        }

        fun fire(detail: String) {
            val current = listener ?: error("nothing is watching the default output")
            current(detail)
        }
    }
}
