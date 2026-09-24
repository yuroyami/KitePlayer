package io.github.yuroyami.kiteplayer.subtitle

/**
 * The part of the output that subtitles must stay inside, as an inset from each edge.
 *
 * Each inset is a fraction of the output: [left] and [right] of its width, [top] and [bottom] of
 * its height. Use it to keep text out of a display cutout, rounded corners, the overscan of a
 * television, or a control bar drawn over the video. Fractions mean the same on every renderer,
 * whatever the pixel density of its output. Set a new value when the output changes shape, for
 * example after a rotation.
 *
 * The built-in text drawing lays cues out inside what is left. A typeset ASS track keeps its
 * author's placement. docs/subtitle-placement.md has the whole rule.
 */
public data class SubtitleSafeArea(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        // At most 0.45 from each edge, so at least a tenth of the output always stays for text.
        require(left.isFinite() && left in 0f..MAX_INSET) { "left inset must be within 0..$MAX_INSET, was $left" }
        require(top.isFinite() && top in 0f..MAX_INSET) { "top inset must be within 0..$MAX_INSET, was $top" }
        require(right.isFinite() && right in 0f..MAX_INSET) { "right inset must be within 0..$MAX_INSET, was $right" }
        require(bottom.isFinite() && bottom in 0f..MAX_INSET) { "bottom inset must be within 0..$MAX_INSET, was $bottom" }
    }

    public companion object {
        /** No inset: subtitles may use the whole output. The value every player starts at. */
        public val None: SubtitleSafeArea = SubtitleSafeArea()

        private const val MAX_INSET: Float = 0.45f
    }
}
