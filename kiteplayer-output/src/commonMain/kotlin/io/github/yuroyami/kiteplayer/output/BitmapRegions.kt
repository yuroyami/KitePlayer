package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A pre-rendered subtitle region placed on the viewport, or null when it covers no viewport pixel
 * or does not fit [budget].
 *
 * The region's origin and extent both scale from its authored canvas. An [OverlayImage] is drawn at
 * its bitmap's own size, so a bitmap that lands on a different extent is resampled here. Only the
 * part inside the viewport is kept, which bounds the work by the viewport's size. At the authored
 * size the pixels pass through untouched when the region is wholly inside the viewport, and the
 * part inside is copied out when it is not. Every built-in rasterizer places regions here.
 *
 * A resampled region is charged with the larger of the pixels it reads and the pixels it keeps, on
 * each axis, because that is the work and the memory it costs.
 */
internal fun regionImage(
    region: BitmapRegion,
    viewportWidth: Int,
    viewportHeight: Int,
    budget: OverlayPixels = OverlayPixels(viewportWidth, viewportHeight),
): OverlayImage? {
    val scaleX = viewportWidth.toDouble() / region.canvasWidth.coerceAtLeast(1)
    val scaleY = viewportHeight.toDouble() / region.canvasHeight.coerceAtLeast(1)
    // Both edges scale rather than the size, so regions that meet on the canvas still meet. In
    // Double, because the far edge of a region placed near the end of Int overflows an Int sum.
    val left = floor(region.x * scaleX)
    val top = floor(region.y * scaleY)
    val right = floor((region.x.toDouble() + region.width) * scaleX)
    val bottom = floor((region.y.toDouble() + region.height) * scaleY)
    if (right <= left || bottom <= top) return null
    val source = region.bitmap
    val keptLeft = maxOf(left, 0.0).toInt()
    val keptTop = maxOf(top, 0.0).toInt()
    val keptWidth = minOf(right, viewportWidth.toDouble()).toInt() - keptLeft
    val keptHeight = minOf(bottom, viewportHeight.toDouble()).toInt() - keptTop
    // Nothing of it on the viewport, however far off it lies.
    if (keptWidth <= 0 || keptHeight <= 0) return null

    if (right - left == source.width.toDouble() && bottom - top == source.height.toDouble()) {
        if (!budget.take(keptWidth, keptHeight)) return null
        if (keptWidth == source.width && keptHeight == source.height) return OverlayImage(keptLeft, keptTop, source)
        val cropped = crop(source, (keptLeft - left).toInt(), (keptTop - top).toInt(), keptWidth, keptHeight)
        return OverlayImage(keptLeft, keptTop, RgbaBitmap(keptWidth, keptHeight, cropped))
    }

    val cost = maxOf(source.width, keptWidth).toLong() * maxOf(source.height, keptHeight).toLong()
    if (!budget.take(cost)) return null
    val resampled = resample(
        source,
        columns = taps(source.width, right - left, keptLeft - left, keptWidth),
        rows = taps(source.height, bottom - top, keptTop - top, keptHeight),
        width = keptWidth,
        height = keptHeight,
    )
    return OverlayImage(keptLeft, keptTop, RgbaBitmap(keptWidth, keptHeight, resampled))
}

/** The [width] by [height] block of [source] whose top left pixel is at [fromX], [fromY]. */
private fun crop(source: RgbaBitmap, fromX: Int, fromY: Int, width: Int, height: Int): ByteArray {
    val output = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val from = ((fromY + row) * source.width + fromX) * 4
        source.pixels.copyInto(output, row * width * 4, from, from + width * 4)
    }
    return output
}

/** For each output pixel along one axis, the source pixels it reads and how much of each. */
private class Taps(val first: IntArray, val count: IntArray, val index: IntArray, val weight: FloatArray) {
    val widest: Int get() = count.maxOrNull() ?: 0
}

/**
 * The taps of a tent filter that stretches [sourceSize] pixels to [scaledSize], for the [outSize]
 * output pixels that start at [offset]. The tent reaches one source pixel when the picture grows,
 * which is bilinear, and widens when it shrinks, so a reduction does not alias.
 */
private fun taps(sourceSize: Int, scaledSize: Double, offset: Double, outSize: Int): Taps {
    val scale = scaledSize / sourceSize
    val reach = if (scale >= 1.0) 1.0 else 1.0 / scale
    // Never more than the whole source: a tent wider than the source reads every pixel once.
    val most = minOf(floor(2.0 * reach) + 2, sourceSize.toDouble() + 1).toInt()
    val first = IntArray(outSize)
    val count = IntArray(outSize)
    val index = IntArray(outSize * most)
    val weight = FloatArray(outSize * most)
    var used = 0
    for (out in 0 until outSize) {
        // The source position of this output pixel's centre.
        val centre = (out + offset + 0.5) / scale - 0.5
        val low = ceil(centre - reach).coerceAtLeast(0.0).toInt()
        val high = floor(centre + reach).coerceAtMost(sourceSize - 1.0).toInt()
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
 *
 * The rows are filtered across first. The last filtered rows stay in a small ring, because each
 * output row reads a few source rows and the next output row reads mostly the same ones. A
 * reduction so steep that one output row reads more than [RING_ROWS] source rows filters each
 * source row again for each output row instead, so memory stays at a few rows either way.
 */
private fun resample(source: RgbaBitmap, columns: Taps, rows: Taps, width: Int, height: Int): ByteArray {
    val input = source.pixels
    val rowFloats = width * 4
    val ringRows = rows.widest.takeIf { it in 1..RING_ROWS } ?: 1
    val ring = FloatArray(ringRows * rowFloats)
    val ringHolds = IntArray(ringRows) { -1 }
    val sum = FloatArray(rowFloats)
    val output = ByteArray(width * height * 4)
    for (y in 0 until height) {
        sum.fill(0f)
        for (tap in rows.first[y] until rows.first[y] + rows.count[y]) {
            val sourceRow = rows.index[tap]
            val slot = sourceRow % ringRows
            val at = slot * rowFloats
            if (ringHolds[slot] != sourceRow) {
                filterAcross(input, sourceRow * source.width * 4, columns, ring, at, width)
                ringHolds[slot] = sourceRow
            }
            val share = rows.weight[tap]
            for (i in 0 until rowFloats) sum[i] += ring[at + i] * share
        }
        val out = y * rowFloats
        for (i in 0 until rowFloats) output[out + i] = sum[i].roundToInt().coerceIn(0, 255).toByte()
    }
    return output
}

/** One source row, starting at byte [rowStart] of [input], filtered through [columns] into [into] at [at]. */
private fun filterAcross(input: ByteArray, rowStart: Int, columns: Taps, into: FloatArray, at: Int, width: Int) {
    into.fill(0f, at, at + width * 4)
    for (x in 0 until width) {
        val out = at + x * 4
        for (tap in columns.first[x] until columns.first[x] + columns.count[x]) {
            val share = columns.weight[tap]
            val from = rowStart + columns.index[tap] * 4
            for (channel in 0 until 4) into[out + channel] += (input[from + channel].toInt() and 0xFF) * share
        }
    }
}

/** The most filtered source rows that [resample] keeps. */
private const val RING_ROWS: Int = 64
