package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
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
}
