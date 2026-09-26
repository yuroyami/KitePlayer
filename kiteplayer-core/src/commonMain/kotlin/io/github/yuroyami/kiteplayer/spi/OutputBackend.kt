package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.MonotonicClock

/**
 * The output half of a platform: one clock, one audio sink factory, and optionally a subtitle
 * rasterizer. It supplies no video renderer: a renderer needs a surface that only the application
 * owns, so the application builds one and passes it to `KitePlayer.attachRenderer`.
 *
 * The clock and the sink travel together because they cannot be chosen independently. An audio sink
 * reports when a buffer becomes audible on the platform's own time base, and the engine anchors its
 * master clock to that instant. Measure time from a different source and the two agree only by luck:
 * audio and video then sit at a constant offset that no correction can find, because both sides believe
 * they are right. The Apple sink asserts the pairing at construction, and this interface is what stops
 * the mismatch from being assemblable in the first place.
 *
 * There is no discovery here either. A platform provides one object that wires its own pair together,
 * and whoever creates the player passes it in.
 */
public interface OutputBackend {
    /** The time base the sink reports on, and the one every engine timing rule reads. */
    public val clock: MonotonicClock

    /** Creates the sink that reports on [clock]. */
    public val audioSink: AudioSinkFactory

    /**
     * The platform's text raster engine for subtitle cues, or null when the platform has none.
     *
     * Null costs drawn subtitles and nothing else: cue timing still runs, and the selection is
     * still reported. The Android and Apple backends supply real ones.
     */
    public val subtitleRasterizer: SubtitleRasterizer? get() = null
}

/**
 * Creates a [VideoRenderer] for an application to pass to `KitePlayer.attachRenderer`.
 *
 * The engine never calls it. The web canvas factories implement it, and the application that calls
 * [create] owns the renderer and closes it.
 */
public interface VideoRendererFactory {
    /** A new renderer. The caller owns it and closes it. */
    public suspend fun create(): VideoRenderer
    /** For logs and diagnostics. */
    public val name: String
}
