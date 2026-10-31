package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PictureCrop

/**
 * A stored picture as it is shown (#608): an FFmpeg filter chain that takes a [width] by [height]
 * frame to the picture a renderer draws, and the size that picture has. [filter] is null when the
 * stored picture already is what is shown, and nothing needs to run.
 */
internal class ShownGeometry(val filter: String?, val width: Int, val height: Int)

/**
 * The steps from a stored picture to the one a renderer shows, in the order a renderer takes them:
 * the container's [crop] first, then square pixels from the sample aspect [sarNum]:[sarDen], then
 * the mirror and the clockwise [rotationDegrees] turn of the display matrix, as
 * `VideoFrame.mirrored` orders them, and last a scale down to [maxWidth] with the aspect kept.
 * Every size is even, as an image encoder of 4:2:0 pictures needs. A crop that does not fit, and
 * an unknown sample aspect, are left out, as a renderer leaves them out.
 */
internal fun shownGeometry(
    width: Int,
    height: Int,
    sarNum: Int,
    sarDen: Int,
    crop: PictureCrop?,
    rotationDegrees: Int,
    mirrored: Boolean,
    maxWidth: Int? = null,
): ShownGeometry {
    val steps = ArrayList<String>()
    var w = width
    var h = height
    if (crop != null && !crop.isEmpty && crop.fits(width, height)) {
        w = width - crop.left - crop.right
        h = height - crop.top - crop.bottom
        steps += "crop=$w:$h:${crop.left}:${crop.top}"
    }
    if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
        w = even(w.toLong() * sarNum / sarDen)
        steps += "scale=$w:$h"
        steps += "setsar=1"
    }
    if (mirrored) steps += "hflip"
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> {
            steps += "transpose=clock"
            w = h.also { h = w }
        }
        180 -> {
            steps += "hflip"
            steps += "vflip"
        }
        270 -> {
            steps += "transpose=cclock"
            w = h.also { h = w }
        }
    }
    if (maxWidth != null && w > maxWidth) {
        h = even(h.toLong() * maxWidth / w)
        w = maxWidth
        steps += "scale=$w:$h"
    }
    return ShownGeometry(steps.joinToString(",").ifEmpty { null }, w, h)
}

/** [value] rounded to the nearest even number, and never below 2. */
private fun even(value: Long): Int = (((value + 1) / 2) * 2).coerceAtLeast(2).toInt()
