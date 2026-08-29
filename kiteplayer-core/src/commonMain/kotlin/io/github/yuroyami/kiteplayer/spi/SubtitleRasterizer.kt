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
 * [viewportWidth] and [viewportHeight] are the RENDERER'S SURFACE size, which is what the engine
 * has passed since 2026-08-23. Overlay images are produced against that surface and a renderer
 * composites them in output space, so subtitles stay glued to the picture through letterboxing
 * and rotation.
 *
 * Bitmap cues arrive pre-rendered and are POSITIONED, not scaled: an overlay image carries an
 * origin and its pixels, and no implementation resizes them. Giving a bitmap cue a target
 * rectangle would be a public model change, so it is a decision to take deliberately rather than
 * a promise this interface can quietly make.
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
