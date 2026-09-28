@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import cnames.structs.kprt_sink
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_SINK_DEVICE_REFUSED
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_create_on_device
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_format
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Listing a Mac's output devices and binding a sink to one (#92). */
class AppleAudioOutputDeviceTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun theListHoldsTheSystemDefaultOnce() {
        if (MacOutputDevices.defaultOutputDevice() == 0u) return println("skipped: this Mac has no default output")
        val devices = AppleOutputBackend.audioOutputDevices()
        assertEquals(1, devices.count { it.isDefault }, "the default among $devices")
    }

    @Test
    fun theDefaultDeviceOpensThroughItsUid() = runBlocking {
        val default = AppleOutputBackend.audioOutputDevices().firstOrNull { it.isDefault }
            ?: return@runBlocking println("skipped: this Mac has no default output")
        val sink = CoreAudioSink(AppleAudioSessionPolicy.ManagedPlayback, AppleHostClock, default.id)
        try {
            val handoff = sink.openWithRing(format) { 4_800 }
            assertEquals(48_000, handoff.format.sampleRate)
        } finally {
            sink.close()
        }
    }

    @Test
    fun aUidThatIsNotThereFailsTypedBeforeAnythingOpens() = runBlocking {
        val sink = CoreAudioSink(AppleAudioSessionPolicy.ManagedPlayback, AppleHostClock, "no such device")
        try {
            val failure = assertFailsWith<PlaybackException> { sink.openWithRing(format) { 4_800 } }
            val error = assertIs<PlaybackError.AudioDeviceUnavailable>(failure.error)
            assertEquals("no such device", error.device)
        } finally {
            sink.close()
        }
    }

    @Test
    fun theDeviceBoundOpenRefusesADeviceThatDoesNotExistAndLeavesNothing() = memScoped {
        val out = allocPointerTo<kprt_sink>()
        val accepted = alloc<kprt_sink_format>()
        val status = alloc<IntVar>()
        // No CoreAudio device has this id: ids are small numbers the HAL hands out.
        val verdict = kprt_sink_create_on_device(0x7FFF_FFF0u, 48_000, 2, out.ptr, accepted.ptr, status.ptr)
        assertEquals(KPRT_SINK_DEVICE_REFUSED.toInt(), verdict)
        assertNull(out.value, "a refused open must not hand out a sink")
    }
}
