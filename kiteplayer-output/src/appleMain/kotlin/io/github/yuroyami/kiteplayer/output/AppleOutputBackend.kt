package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioOutputDevice
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend

/**
 * Apple output: CoreAudio, on CoreAudio's own clock.
 *
 * The pairing is the whole reason this object exists. [CoreAudioSink] reports when a buffer becomes
 * audible as a host time and requires [AppleHostClock], and the engine anchors its master clock to that
 * instant, so both sides have to read the same time base. Handing the engine one object rather than a
 * clock and a factory separately makes the mismatch unassemblable instead of merely checked.
 *
 * It supplies no renderer. A renderer draws into a view or a window that only the application owns,
 * so the application builds one and attaches it, which is legal at any time, including while playing.
 */
public object AppleOutputBackend : OutputBackend {

    override val clock: MonotonicClock = AppleHostClock

    override val audioSink: AudioSinkFactory = CoreAudioSinkFactory(
        policy = AppleAudioSessionPolicy.ManagedPlayback,
        clock = AppleHostClock,
    )

    /** CoreText behind the one seam. */
    override val subtitleRasterizer: io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer =
        AppleSubtitleRasterizer()

    /**
     * The output devices this machine can play through.
     *
     * On macOS these are CoreAudio's devices with an output, and each [AudioOutputDevice.id] is the
     * device's UID, which survives a restart. On iOS the audio session owns the route, so the list
     * holds the outputs of the current route.
     */
    public fun audioOutputDevices(): List<AudioOutputDevice> = platformAppleOutputDevices().devices()

    /**
     * This backend, with its audio bound to the device that [id] names, one of [audioOutputDevices].
     *
     * On macOS the player then plays through that device, whatever the system default does. When
     * the device has gone away, opening media fails with [PlaybackError.AudioDeviceUnavailable]
     * rather than falling back to the default. On iOS only an output of the current route is
     * accepted, and it changes nothing, because the audio session chooses the route.
     */
    public fun withAudioOutputDevice(id: String): OutputBackend = object : OutputBackend {
        override val clock: MonotonicClock = AppleHostClock
        override val audioSink: AudioSinkFactory = CoreAudioSinkFactory(id)
        override val subtitleRasterizer: io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer =
            this@AppleOutputBackend.subtitleRasterizer
    }
}
