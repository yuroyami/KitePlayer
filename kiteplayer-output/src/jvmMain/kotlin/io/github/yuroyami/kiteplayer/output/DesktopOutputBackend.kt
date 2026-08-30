package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory

/**
 * The desktop JVM output half: [DesktopMonotonicClock] paired with
 * [DesktopAudioSinkFactory], and null for video. One object lights up macOS, Linux and Windows,
 * because `javax.sound.sampled` and `java.awt` are in the JDK on all three.
 *
 * The pairing is the contract, not a convenience: the sink's deadlines are "now plus the audio
 * still queued" measured on `System.nanoTime`, the engine anchors its master clock to them, and
 * this object is what stops a caller assembling that sink with a clock on any other base.
 *
 * Video is null for the reason the SPI KDoc gives for windowing systems: a renderer needs a
 * surface and only the application knows which one. On desktop the Compose window IS the surface,
 * so the application draws frames itself through `:kiteplayer-compose-video` and attaches nothing
 * here, exactly like the Android and Apple backends.
 */
public object DesktopOutputBackend : OutputBackend {
    override val clock: MonotonicClock get() = DesktopMonotonicClock
    override val audioSink: AudioSinkFactory = DesktopAudioSinkFactory()
    override val videoRenderer: VideoRendererFactory? get() = null

    /** AWT does the line breaking, bidi and shaping; see the rasteriser's own KDoc. */
    override val subtitleRasterizer: SubtitleRasterizer = DesktopSubtitleRasterizer()
}
