package io.github.yuroyami.kiteplayer

/**
 * The viewer's framing controls, on top of [VideoScale]: force a display aspect the container
 * did not declare (mpv's `video-aspect-override`), magnify (mpv's `video-zoom`), and pan the
 * magnified picture (mpv's `video-pan-x`/`-y`). The engine owns the value and every renderer is
 * told it, the same delivery law as [VideoScale] and [VideoAdjustments]; the renderers fold it
 * into the same one-pass geometry that letterboxes, so it costs nothing per frame.
 *
 * Order, applied by the geometry: the aspect override reshapes the content, the scale mode fits
 * that shape to the viewport, the zoom scales the fitted rectangle about its centre, and the pan
 * then moves it by a fraction of its own drawn size. Pixel aspect and rotation are folded in
 * before all four, as always.
 */
public data class VideoTransform(
    /**
     * The display aspect (width over height) to present the picture at, replacing the
     * container's own, or null to trust the container. The classic use is a DVD rip that
     * stored 4:3 pixels and lost its 16:9 flag.
     */
    val aspectOverride: Float? = null,
    /** Magnification of the fitted picture about its centre. 1 is none. */
    val zoom: Float = 1f,
    /** Horizontal shift as a fraction of the drawn width, positive right. 0 is centred. */
    val panX: Float = 0f,
    /** Vertical shift as a fraction of the drawn height, positive down. 0 is centred. */
    val panY: Float = 0f,
) {
    /** True for the neutral value, which keeps every geometry on its untouched fast path. */
    public val isIdentity: Boolean
        get() = aspectOverride == null && zoom == 1f && panX == 0f && panY == 0f

    public companion object {
        /** The neutral value every player starts at. */
        public val Identity: VideoTransform = VideoTransform()
    }
}
