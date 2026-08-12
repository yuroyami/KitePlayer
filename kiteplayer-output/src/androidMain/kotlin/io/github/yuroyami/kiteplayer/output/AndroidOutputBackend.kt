package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory

/**
 * The Android output half: [AndroidMonotonicClock] paired with [AudioTrackSinkFactory], and null
 * for video (S1.c.4 step 2).
 *
 * The pairing is the contract, not a convenience: `AudioTimestamp.nanoTime` reports on the
 * `elapsedRealtimeNanos` base, the sink anchors the master clock to deadlines computed from it,
 * and this object is what stops a caller assembling that sink with a clock on any other base.
 *
 * Video is null for the reason the SPI KDoc gives for windowing systems: a renderer needs a
 * Surface and only the application knows which one. The application builds an
 * [AndroidSurfaceVideoRenderer] over its own Surface and attaches it through the player facade,
 * exactly like the Apple samples attach theirs.
 */
public object AndroidOutputBackend : OutputBackend {
    override val clock: MonotonicClock get() = AndroidMonotonicClock
    override val audioSink: AudioSinkFactory = AudioTrackSinkFactory()
    override val videoRenderer: VideoRendererFactory? get() = null
}
