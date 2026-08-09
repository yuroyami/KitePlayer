package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory

/**
 * Apple output: CoreAudio, on CoreAudio's own clock.
 *
 * The pairing is the whole reason this object exists. [CoreAudioSink] reports when a buffer becomes
 * audible as a host time and requires [AppleHostClock], and the engine anchors its master clock to that
 * instant, so both sides have to read the same time base. Handing the engine one object rather than a
 * clock and a factory separately makes the mismatch unassemblable instead of merely checked.
 *
 * [videoRenderer] is null, and honestly so. The renderer this platform has draws into an `NSWindow`
 * that only the application can own, so there is nothing for a factory to create without one. An
 * application that wants a picture builds its renderer and attaches it, which is legal at any time,
 * including while playing.
 */
public object AppleOutputBackend : OutputBackend {

    override val clock: MonotonicClock = AppleHostClock

    override val audioSink: AudioSinkFactory = CoreAudioSinkFactory(AppleHostClock)

    override val videoRenderer: VideoRendererFactory? = null
}
