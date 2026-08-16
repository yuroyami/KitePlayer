package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Turns active text cues into the positioned images a renderer composites (S4.c).
 *
 * This is a platform seam for one reason: text needs a font engine, and the engine has none.
 * Each platform's output backend supplies its own (StaticLayout on Android, CoreText on Apple)
 * behind this one interface, and the ENGINE decides when to call it: on cue-set changes and
 * viewport changes, never per frame, because cues change about once a second and frames sixty
 * times a second.
 *
 * [viewportWidth] and [viewportHeight] are the video's own display size: overlay images live in
 * video coordinates, and a renderer maps them with the same transform it draws the picture
 * with, so subtitles stay glued to the video through every letterbox and rotation.
 *
 * Bitmap cues arrive pre-rendered and are placed, not rasterised; implementations pass their
 * pixels through scaled to the cue's declared canvas.
 */
public interface SubtitleRasterizer {
    /**
     * @param position where the implicit bottom stack anchors, as a fraction of the viewport
     *        height: 1.0 is the ordinary bottom edge, 0.5 mid-screen, mpv's `sub-pos` over 100.
     *        Explicitly positioned cues are the author's word and do not move with it.
     */
    public fun rasterize(
        cues: List<SubtitleCue>,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        position: Float = 1f,
    ): List<OverlayImage>
}
