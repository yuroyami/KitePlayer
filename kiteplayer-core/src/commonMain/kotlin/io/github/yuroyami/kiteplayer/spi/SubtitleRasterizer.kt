package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Turns active text cues into the positioned images a renderer composites.
 *
 * This is a platform seam for one reason: text needs a font engine, and the engine has none.
 * Each platform's output backend supplies its own (StaticLayout on Android, CoreText on Apple)
 * behind this one interface, and the ENGINE decides when to call it: on cue-set changes and
 * viewport changes, never per frame, because cues change about once a second and frames sixty
 * times a second.
 *
 * [viewportWidth] and [viewportHeight] are the size of the area the engine lays text out in: the
 * renderer's output, less the safe area the application set. The engine moves the images into
 * output pixels afterwards, and a renderer composites them over its whole output, never over the
 * picture alone. docs/subtitle-placement.md has the whole rule.
 *
 * Bitmap cues arrive pre-rendered and are POSITIONED, not scaled: an overlay image carries an
 * origin and its pixels, and no implementation resizes them. Giving a bitmap cue a target
 * rectangle would be a public model change, so it is a decision to take deliberately rather than
 * a promise this interface can quietly make.
 *
 * Every implementation keeps to the limits in the companion object. The built-in ones draw nothing
 * for a viewport larger than [MAX_VIEWPORT_SIZE] on a side, lay out only what [limitCues] keeps,
 * and throw [SubtitleOverlayLimitException] rather than let their images pass
 * [overlayPixelBudget]. The engine applies the same limits to any implementation, so a custom one
 * gets only what [limitCues] keeps.
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

    /**
     * The limits of one overlay. Cues past them are not drawn, and the engine warns once with
     * [io.github.yuroyami.kiteplayer.PlaybackWarning.SubtitlesNotDrawn]. Ordinary subtitles stay far
     * below every limit.
     */
    public companion object {
        /** The largest viewport width or height that a rasterizer draws for, in pixels. */
        public const val MAX_VIEWPORT_SIZE: Int = 16_384

        /** The most cues that one overlay draws. Each region of a bitmap cue counts as one cue. */
        public const val MAX_CUES: Int = 256

        /** The most UTF-16 code units of one cue's text that a rasterizer lays out. */
        public const val MAX_CUE_LENGTH: Int = 16_384

        /** The most UTF-16 code units of text that one overlay lays out, over all of its cues. */
        public const val MAX_OVERLAY_TEXT_LENGTH: Int = 65_536

        /** The most styled spans that one overlay lays out, over all of its cues. */
        public const val MAX_OVERLAY_SPANS: Int = 2_048

        /** The most pixels that the images of one overlay hold together, at any viewport size. */
        public const val MAX_OVERLAY_PIXELS: Long = 32L * 1024 * 1024

        /**
         * The most pixels that the images of one overlay hold together for a viewport of
         * [viewportWidth] by [viewportHeight]: four viewports, and at most [MAX_OVERLAY_PIXELS].
         */
        public fun overlayPixelBudget(viewportWidth: Int, viewportHeight: Int): Long {
            val viewport = viewportWidth.coerceAtLeast(0).toLong() * viewportHeight.coerceAtLeast(0).toLong()
            return if (viewport >= MAX_OVERLAY_PIXELS / 4) MAX_OVERLAY_PIXELS else viewport * 4
        }

        /**
         * The part of [cues] that one overlay lays out, in order: at most [MAX_CUES] cues, at most
         * [MAX_CUE_LENGTH] code units of text in each, and at most [MAX_OVERLAY_TEXT_LENGTH] code
         * units and [MAX_OVERLAY_SPANS] spans over all of them. The cue that reaches a limit is cut
         * there, and the cues after it are left out. A cut never splits a surrogate pair.
         *
         * @return [cues] itself when nothing is cut.
         */
        public fun limitCues(cues: List<SubtitleCue>): List<SubtitleCue> {
            var images = 0
            var text = 0
            var spans = 0
            var changed = false
            val kept = ArrayList<SubtitleCue>(minOf(cues.size, MAX_CUES))
            for (cue in cues) {
                if (images >= MAX_CUES) break
                when (cue) {
                    is SubtitleCue.Text -> {
                        val cut = cue.spans.cutTo(
                            length = minOf(MAX_CUE_LENGTH, MAX_OVERLAY_TEXT_LENGTH - text),
                            count = MAX_OVERLAY_SPANS - spans,
                        )
                        // A cue whose text or spans the budget ran out on is where the overlay stops.
                        if (cut.isEmpty() && cue.spans.isNotEmpty()) break
                        images++
                        spans += cut.size
                        for (span in cut) text += span.text.length
                        kept += if (cut === cue.spans) cue else cue.copy(spans = cut)
                        if (cut !== cue.spans) {
                            changed = true
                            break
                        }
                    }
                    is SubtitleCue.Bitmap -> {
                        val room = MAX_CUES - images
                        if (cue.regions.size <= room) {
                            images += cue.regions.size
                            kept += cue
                        } else {
                            images = MAX_CUES
                            kept += cue.copy(regions = cue.regions.subList(0, room).toList())
                            changed = true
                            break
                        }
                    }
                }
            }
            return if (changed || kept.size < cues.size) kept else cues
        }

        /**
         * These spans, cut to at most [length] code units and [count] spans. Returns this list
         * itself when nothing is cut, and an empty list when nothing fits.
         */
        private fun List<StyledSpan>.cutTo(length: Int, count: Int): List<StyledSpan> {
            var used = 0L
            for ((index, span) in withIndex()) {
                if (index >= count) return subList(0, index).toList()
                if (used + span.text.length <= length) {
                    used += span.text.length
                    continue
                }
                var end = (length - used).toInt()
                if (end > 0 && span.text[end - 1].isHighSurrogate()) end--
                val cut = ArrayList<StyledSpan>(index + 1)
                cut.addAll(subList(0, index))
                if (end > 0) cut += span.copy(text = span.text.substring(0, end))
                return cut
            }
            return this
        }
    }
}

/**
 * Thrown by [SubtitleRasterizer.rasterize] when the images of the next cue would take the overlay
 * past [SubtitleRasterizer.overlayPixelBudget]. The engine shows [drawn] and warns once with
 * [io.github.yuroyami.kiteplayer.PlaybackWarning.SubtitlesNotDrawn].
 */
public class SubtitleOverlayLimitException(
    /** The images of the cues before that one, in the order that `rasterize` returns images. */
    public val drawn: List<OverlayImage>,
    /** The cues that were left out, the one that did not fit included. */
    public val skipped: Int,
) : RuntimeException("the subtitle images reached their pixel limit, so $skipped cues were not drawn")
