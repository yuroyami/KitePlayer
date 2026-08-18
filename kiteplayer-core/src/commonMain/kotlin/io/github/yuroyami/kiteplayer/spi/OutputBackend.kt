package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.MonotonicClock

/**
 * The output half of a platform: one clock, one audio sink factory, and optionally a renderer.
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

    public val audioSink: AudioSinkFactory

    /**
     * The platform's text raster engine for subtitle cues, or null when the platform has none.
     *
     * Null costs drawn subtitles and nothing else: cue timing still runs, and the selection is
     * still reported. The Android and Apple backends supply real ones (S4.c).
     */
    public val subtitleRasterizer: SubtitleRasterizer? get() = null

    /**
     * Where video goes, when the platform can decide that on its own.
     *
     * Null is the normal answer for a windowing system: a renderer needs a surface, and only the
     * application knows which one. The engine plays without a renderer and counts the frames nothing
     * drew, so a null here costs the picture and nothing else. A renderer can also be attached later,
     * at any time, while playing.
     *
     * Nothing creates a renderer from this yet, and the one output backend that exists answers null,
     * which is why: on Apple platforms the picture goes into an `NSWindow` the application owns, so
     * every renderer in this build is built by the application and passed to
     * `KitePlayer.attachRenderer`. This is the hook for a platform that can decide on its own, for
     * example a surfaceless offscreen target.
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    public val videoRenderer: VideoRendererFactory?
}

/**
 * Creates a [VideoRenderer]. The engine closes what it creates.
 *
 * Nothing implements this and nothing calls it. See [OutputBackend.videoRenderer].
 * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
 */
public interface VideoRendererFactory {
    public suspend fun create(): VideoRenderer
    public val name: String
}
