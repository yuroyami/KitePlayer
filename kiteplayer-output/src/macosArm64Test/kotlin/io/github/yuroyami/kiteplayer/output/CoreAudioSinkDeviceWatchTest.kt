@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import io.github.yuroyami.kiteplayer.PlaybackError
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS sink tells the application about its output device.
 *
 * An unbound sink reports a change of the system default output, which its DefaultOutput unit
 * follows by itself, so it keeps playing. A bound sink watches its own device, and the loss of that
 * device fails it. These cases replace the CoreAudio notices with fake ones to drive them on demand.
 * `AppleAudioOutputDeviceTest` removes a real device from under a bound sink.
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
            assertEquals(0, devices.deviceWatches, "an unbound sink watches no device of its own")
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

    @Test
    fun aBoundSinkWatchesItsDeviceAndNotTheDefaultOutput() = runBlocking {
        val real = MacOutputDevices.defaultOutputDevice()
        if (real == 0u) return@runBlocking println("skipped: this Mac has no default output")
        val devices = FakeOutputDevices(boundDevice = real)
        val sink = sinkWith(devices, device = "test-uid")
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            assertEquals(1, devices.deviceWatches, "a bound sink watches its device once")
            assertEquals(real, devices.watchedDevice)
            assertEquals(0, devices.registrations, "a bound sink does not follow the default, so it does not report it")
        } finally {
            sink.close()
        }
        assertEquals(1, devices.releases, "close releases the device watch")
    }

    @Test
    fun theLossOfTheBoundDeviceReachesTheSinkEventsAsAFailure() = runBlocking {
        val real = MacOutputDevices.defaultOutputDevice()
        if (real == 0u) return@runBlocking println("skipped: this Mac has no default output")
        val devices = FakeOutputDevices(boundDevice = real)
        val sink = sinkWith(devices, device = "test-uid")
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) { sink.events.first() }
            devices.lose("the output device Test DAC is gone")
            assertEquals(
                AudioSinkEvent.Failed(PlaybackError.AudioDeviceUnavailable("test-uid", "the output device Test DAC is gone")),
                withTimeout(2.seconds) { received.await() },
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun aLossBeforeAnyoneCollectsStillReachesALateCollector() = runBlocking {
        val real = MacOutputDevices.defaultOutputDevice()
        if (real == 0u) return@runBlocking println("skipped: this Mac has no default output")
        val devices = FakeOutputDevices(boundDevice = real)
        val sink = sinkWith(devices, device = "test-uid")
        sink.openWithRing(format) { it.sampleRate / 4 }
        try {
            // The engine subscribes after the open returns, so a loss in between must not be dropped.
            devices.lose("the output device Test DAC is gone")
            val event = withTimeout(2.seconds) { sink.events.first() }
            assertEquals("test-uid", assertIs<PlaybackError.AudioDeviceUnavailable>(assertIs<AudioSinkEvent.Failed>(event).error).device)
        } finally {
            sink.close()
        }
    }

    private fun sinkWith(devices: AppleOutputDevices, device: String? = null) = CoreAudioSink(
        policy = AppleAudioSessionPolicy.ApplicationManaged,
        leaseManager = sharedAppleAudioSessionLeaseManager,
        outputDevices = devices,
        device = device,
    )

    /**
     * Records registrations and releases, and fires the notices when a test says so. [boundDevice]
     * is what every id resolves to: a real CoreAudio device, because the sink opens a real unit on it.
     */
    private class FakeOutputDevices(
        private val onRelease: () -> Unit = {},
        private val boundDevice: UInt? = null,
    ) : AppleOutputDevices {
        var registrations = 0
        var deviceWatches = 0
        var watchedDevice: UInt? = null
        var releases = 0
        private var listener: ((String) -> Unit)? = null
        private var lossListener: ((String) -> Unit)? = null

        override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable {
            registrations++
            listener = onChange
            return AutoCloseable {
                releases++
                listener = null
                onRelease()
            }
        }

        override fun watchDevice(device: UInt, onLost: (detail: String) -> Unit): AutoCloseable {
            deviceWatches++
            watchedDevice = device
            lossListener = onLost
            return AutoCloseable {
                releases++
                lossListener = null
                onRelease()
            }
        }

        fun fire(detail: String) {
            val current = listener ?: error("nothing is watching the default output")
            current(detail)
        }

        fun lose(detail: String) {
            val current = lossListener ?: error("nothing is watching a device")
            current(detail)
        }

        override fun devices(): List<AudioOutputDevice> = emptyList()

        override fun deviceFor(id: String): UInt? = boundDevice
    }
}
