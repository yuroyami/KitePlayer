package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A pre-rendered subtitle region placed on the viewport, or null when it covers no viewport pixel.
 *
 * The region's origin and extent both scale from its authored canvas. An [OverlayImage] is drawn at
 * its bitmap's own size, so a bitmap that lands on a different extent is resampled here. Only the
 * part inside the viewport is kept then, which bounds the work by the viewport's size. At the
 * authored size the pixels pass through untouched. Every built-in rasterizer places regions here.
 */
internal fun regionImage(region: BitmapRegion, viewportWidth: Int, viewportHeight: Int): OverlayImage? {
    val scaleX = viewportWidth.toDouble() / region.canvasWidth.coerceAtLeast(1)
    val scaleY = viewportHeight.toDouble() / region.canvasHeight.coerceAtLeast(1)
    // Both edges scale rather than the size, so regions that meet on the canvas still meet.
    val left = floor(region.x * scaleX).toInt()
    val top = floor(region.y * scaleY).toInt()
    val right = floor((region.x + region.width) * scaleX).toInt()
    val bottom = floor((region.y + region.height) * scaleY).toInt()
    if (right <= left || bottom <= top) return null
    val source = region.bitmap
    if (right - left == source.width && bottom - top == source.height) return OverlayImage(left, top, source)

    val keptLeft = maxOf(left, 0)
    val keptTop = maxOf(top, 0)
    val keptWidth = minOf(right, viewportWidth) - keptLeft
    val keptHeight = minOf(bottom, viewportHeight) - keptTop
    if (keptWidth <= 0 || keptHeight <= 0) return null
    val pixels = resample(
        source,
        columns = taps(source.width, right - left, keptLeft - left, keptWidth),
        rows = taps(source.height, bottom - top, keptTop - top, keptHeight),
        width = keptWidth,
        height = keptHeight,
    )
    return OverlayImage(keptLeft, keptTop, RgbaBitmap(keptWidth, keptHeight, pixels))
}

/** For each output pixel along one axis, the source pixels it reads and how much of each. */
private class Taps(val first: IntArray, val count: IntArray, val index: IntArray, val weight: FloatArray) {
    val lowest: Int get() = index.min()
    val highest: Int get() = index.max()
}

/**
 * The taps of a tent filter that stretches [sourceSize] pixels to [scaledSize], for the [outSize]
 * output pixels that start at [offset]. The tent reaches one source pixel when the picture grows,
 * which is bilinear, and widens when it shrinks, so a reduction does not alias.
 */
private fun taps(sourceSize: Int, scaledSize: Int, offset: Int, outSize: Int): Taps {
    val scale = scaledSize.toDouble() / sourceSize
    val reach = if (scale >= 1.0) 1.0 else 1.0 / scale
    val most = floor(2.0 * reach).toInt() + 2
    val first = IntArray(outSize)
    val count = IntArray(outSize)
    val index = IntArray(outSize * most)
    val weight = FloatArray(outSize * most)
    var used = 0
    for (out in 0 until outSize) {
        // The source position of this output pixel's centre.
        val centre = (out + offset + 0.5) / scale - 0.5
        val low = ceil(centre - reach).toInt().coerceAtLeast(0)
        val high = floor(centre + reach).toInt().coerceAtMost(sourceSize - 1)
        first[out] = used
        var total = 0.0
        for (at in low..high) {
            val share = 1.0 - abs(at - centre) / reach
            if (share <= 0.0) continue
            index[used] = at
            weight[used] = share.toFloat()
            total += share
            used++
        }
        if (total <= 0.0) {
            // Past the last source pixel's centre: the edge pixel stands in for what lies beyond.
            index[used] = centre.roundToInt().coerceIn(0, sourceSize - 1)
            weight[used] = 1f
            total = 1.0
            used++
        }
        // Normalised, so the edges keep their value rather than fading towards transparent.
        for (tap in first[out] until used) weight[tap] = (weight[tap] / total).toFloat()
        count[out] = used - first[out]
    }
    return Taps(first, count, index.copyOf(used), weight.copyOf(used))
}

/**
 * [source] filtered through [columns] and [rows]. Premultiplied pixels are filtered as they are,
 * which is the correct way to filter them, and a weighted mean of premultiplied pixels stays
 * premultiplied.
 */
private fun resample(source: RgbaBitmap, columns: Taps, rows: Taps, width: Int, height: Int): ByteArray {
    val input = source.pixels
    val firstRow = rows.lowest
    val rowCount = rows.highest - firstRow + 1
    // Across first, for just the source rows the second pass reads.
    val across = FloatArray(rowCount * width * 4)
    for (row in 0 until rowCount) {
        val sourceRow = (firstRow + row) * source.width * 4
        for (x in 0 until width) {
            val out = (row * width + x) * 4
            for (tap in columns.first[x] until columns.first[x] + columns.count[x]) {
                val share = columns.weight[tap]
                val at = sourceRow + columns.index[tap] * 4
                for (channel in 0 until 4) across[out + channel] += (input[at + channel].toInt() and 0xFF) * share
            }
        }
    }
    val output = ByteArray(width * height * 4)
    val sum = FloatArray(4)
    for (y in 0 until height) {
        for (x in 0 until width) {
            sum.fill(0f)
            for (tap in rows.first[y] until rows.first[y] + rows.count[y]) {
                val share = rows.weight[tap]
                val at = ((rows.index[tap] - firstRow) * width + x) * 4
                for (channel in 0 until 4) sum[channel] += across[at + channel] * share
            }
            val out = (y * width + x) * 4
            for (channel in 0 until 4) output[out + channel] = sum[channel].roundToInt().coerceIn(0, 255).toByte()
        }
    }
    return output
}
