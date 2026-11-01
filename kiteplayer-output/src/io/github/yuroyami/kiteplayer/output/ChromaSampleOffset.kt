package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.ChromaLocation

/**
 * Where the chroma sample sits relative to the luma one, in normalized texture coordinates.
 *
 * A subsampled chroma plane has half as many texels, so sampling it at the luma's own coordinate
 * lands on a texel centre that sits BETWEEN two luma columns. Left-sited 4:2:0, which is MPEG-2's
 * rule and what almost every file this player opens carries, puts the chroma sample on the even
 * luma column instead, half a luma texel left of its texel's centre. A read at luma position p
 * must therefore fetch the texture half a luma texel to the RIGHT of p, which is where the sample
 * that sits at p is stored; libplacebo and mpv move it the same way. The first version moved the
 * read left, and doubled the fringe it was meant to remove (#604). Centre siting is already
 * where the texel centre is and moves nothing.
 *
 * Vertically only the top and bottom sitings move anything, by the same rule: a top-sited sample
 * reads half a luma row down and a bottom-sited one half a row up. An axis moves only when the
 * format subsamples it: 4:2:2 is horizontal-only and 4:4:4 is neither, so both come back zero on
 * the axes they do not subsample.
 *
 * Unspecified reads as left, which is the MPEG-2 convention and the one this player meets.
 */
internal fun chromaSampleOffset(
    location: ChromaLocation,
    chromaShiftX: Int,
    chromaShiftY: Int,
    width: Int,
    height: Int,
): Pair<Float, Float> {
    val x = if (chromaShiftX == 0 || width <= 0) {
        0f
    } else {
        when (location) {
            ChromaLocation.Unspecified, ChromaLocation.Left,
            ChromaLocation.TopLeft, ChromaLocation.BottomLeft,
            -> 0.5f / width.toFloat()
            ChromaLocation.Center, ChromaLocation.Top, ChromaLocation.Bottom -> 0f
        }
    }
    val y = if (chromaShiftY == 0 || height <= 0) {
        0f
    } else {
        when (location) {
            ChromaLocation.TopLeft, ChromaLocation.Top -> 0.5f / height.toFloat()
            ChromaLocation.BottomLeft, ChromaLocation.Bottom -> -0.5f / height.toFloat()
            ChromaLocation.Unspecified, ChromaLocation.Left, ChromaLocation.Center -> 0f
        }
    }
    return x to y
}
