package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Listing the desktop JVM's output devices and binding a player to one (#92). */
class DesktopAudioOutputDeviceTest {

    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    @Test
    fun aMachineWithAnyOutputListsExactlyOneDefault() {
        val devices = DesktopOutputBackend.audioOutputDevices()
        if (devices.isEmpty()) return println("skipped: this machine has no audio output")
        assertEquals(1, devices.count { it.isDefault }, "the default among $devices")
    }

    @Test
    fun theDefaultDeviceOpensThroughItsId() = runBlocking {
        val default = DesktopOutputBackend.audioOutputDevices().firstOrNull { it.isDefault }
            ?: return@runBlocking println("skipped: this machine has no audio output")
        val sink = DesktopOutputBackend.withAudioOutputDevice(default.id).audioSink.create()
        try {
            val accepted = sink.open(format) { buffer, frames, _ ->
                buffer.writeSilence(0, frames)
                frames
            }
            assertEquals(48_000, accepted.sampleRate)
        } finally {
            sink.close()
        }
    }

    @Test
    fun aDeviceThatIsNotThereFailsTypedWhenTheSinkOpens() = runBlocking {
        val sink = DesktopOutputBackend.withAudioOutputDevice("no such device").audioSink.create()
        try {
            val failure = assertFailsWith<PlaybackException> { sink.open(format) { _, _, _ -> 0 } }
            val error = assertIs<PlaybackError.AudioDeviceUnavailable>(failure.error)
            assertEquals("no such device", error.device)
        } finally {
            sink.close()
        }
    }
}
