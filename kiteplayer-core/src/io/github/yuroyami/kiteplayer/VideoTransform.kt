package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.VideoFrame

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
 *
 * It also turns and mirrors the picture (#428), on top of what the file asks for, as mpv's
 * `video-rotate` and VLC's transform do: for a phone clip filmed sideways with no rotation tag, a
 * webcam that recorded mirrored, or a screen mounted on its side. The mirrors apply to the picture
 * as the file shows it, and the turn after them. Every renderer folds the result into the turn it
 * already draws, through [orient], so it costs nothing per frame and keeps hardware decoding, and
 * everything that follows the picture's shape follows it.
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
    /** A clockwise turn of the picture, after the file's own: 0, 90, 180 or 270 degrees. */
    val rotationDegrees: Int = 0,
    /** Mirrors the picture left to right, as the file shows it. */
    val mirrorHorizontal: Boolean = false,
    /** Mirrors the picture top to bottom, as the file shows it. */
    val mirrorVertical: Boolean = false,
) {
    /** True for the neutral value, which keeps every geometry on its untouched fast path. */
    public val isIdentity: Boolean
        get() = aspectOverride == null && zoom == 1f && panX == 0f && panY == 0f && !turnsOrMirrors

    /** True when this turns or mirrors the picture at all. */
    public val turnsOrMirrors: Boolean
        get() = rotationDegrees != 0 || mirrorHorizontal || mirrorVertical

    /**
     * How a picture whose file asks for [rotationDegrees] and [mirrored] is drawn under this
     * transform: as one mirror and one turn, the form every renderer draws a frame's own in (#428).
     * A turn that is not a quarter turn counts as none, as a renderer draws it.
     */
    public fun orient(rotationDegrees: Int, mirrored: Boolean): PictureOrientation {
        val own = ((rotationDegrees % 360) + 360) % 360
        var turn = if (own % 90 == 0) own else 0
        var mirror = mirrored
        // The picture is mirrored first and turned second; a mirror applied after a turn is the
        // same mirror before the opposite turn, which is what the two steps below fold in.
        if (mirrorHorizontal) {
            turn = -turn
            mirror = !mirror
        }
        if (mirrorVertical) {
            // Top to bottom is left to right and a half turn.
            turn = 180 - turn
            mirror = !mirror
        }
        turn += this.rotationDegrees
        return PictureOrientation((((turn % 360) + 360) % 360), mirror)
    }

    /** [orient] for [frame]'s own turn and mirror. */
    public fun orient(frame: VideoFrame): PictureOrientation = orient(frame.rotationDegrees, frame.mirrored)

    public companion object {
        /** The neutral value every player starts at. */
        public val Identity: VideoTransform = VideoTransform()
    }
}

/**
 * How a picture is drawn (#428): mirrored left to right first when [mirrored], then turned
 * clockwise by [rotationDegrees], 0, 90, 180 or 270, as [VideoFrame.mirrored] and
 * [VideoFrame.rotationDegrees] describe a frame's own. See [VideoTransform.orient].
 */
public data class PictureOrientation(val rotationDegrees: Int, val mirrored: Boolean) {
    /** True when the turn swaps the picture's width and height. */
    public val isQuarterTurn: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
}
