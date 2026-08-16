package io.github.yuroyami.kiteplayer

/**
 * How the picture occupies the surface it is drawn into.
 *
 * The pixel aspect ratio and the container rotation are ALWAYS honoured first; these modes only
 * decide what happens to the shape that remains against the viewport's shape. That is why there
 * is no "ignore anamorphic stretch" mode: a mode that draws the pixels wrong is a rendering bug
 * with a setting attached.
 */
public enum class VideoScale {

    /** The whole picture, shape kept, letterboxed or pillarboxed as the shapes demand. The default. */
    Fit,

    /** The whole viewport, shape kept, cropping whichever picture axis overhangs. */
    Fill,

    /** The whole viewport, shape sacrificed: both axes scale independently. */
    Stretch,
}
