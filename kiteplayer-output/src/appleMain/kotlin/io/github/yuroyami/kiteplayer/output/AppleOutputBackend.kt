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
 * [videoRenderer] is null, and honestly so. On macOS the available renderer draws into an `NSWindow`
 * that only the application can own, so there is nothing for a factory to create without one. iOS has
 * no renderer yet. An application that has a caller-owned renderer builds and attaches it directly,
 * which is legal at any time, including while playing.
 */
public object AppleOutputBackend : OutputBackend {

    override val clock: MonotonicClock = AppleHostClock

    override val audioSink: AudioSinkFactory = CoreAudioSinkFactory(
        policy = AppleAudioSessionPolicy.ManagedPlayback,
        clock = AppleHostClock,
    )

    override val videoRenderer: VideoRendererFactory? = null

    /** CoreText behind the one seam (S4.c's Apple half, carried by S2.c). */
    override val subtitleRasterizer: io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer =
        AppleSubtitleRasterizer()
}
