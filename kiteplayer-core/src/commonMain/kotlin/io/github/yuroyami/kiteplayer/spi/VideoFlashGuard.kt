package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * The flash guard every renderer runs on the picture it draws (#500): it counts flashes by the
 * general flash rule and answers the factor to draw each frame with, 1 outside a flashing run.
 * The whole rule is in `docs/video-flash-guard.md`.
 *
 * A renderer measures each frame as [CELLS] mean relative luminances, a grid of [COLUMNS] by
 * [ROWS] cells in reading order, in linear light from 0 for black to 1 for white, before any
 * adjustment, and hands them to [factorFor]. It multiplies its adjustment's colour matrix, offsets
 * included, by the answer. [cellsFromRgba] and [cellsFromPackedRgb] measure converted pixels on a
 * sparse lattice.
 *
 * One renderer's, on one thread: it keeps the history of the frames it was given.
 */
@KitePlayerLowLevelApi
public class VideoFlashGuard {

    private val extreme = FloatArray(CELLS)
    /** The way each cell last moved, +1 or -1, or 0 before it has. */
    private val direction = IntArray(CELLS)
    /** When each cell last made a leg, and how far it moved. */
    private val legNanos = LongArray(CELLS) { Long.MIN_VALUE }
    private val legSize = FloatArray(CELLS)
    private var measured = false
    private var previousNanos = Long.MIN_VALUE

    /** The way the picture last made a leg, +1, -1 or 0. */
    private var pictureDirection = 0
    private val pictureLegs = ArrayDeque<Long>()

    private var factor = 1f
    private var running = false
    private var lastRunLegNanos = 0L
    private var releaseFrom = 1f

    /** The factor the last frame was drawn with: 1 outside a flashing run. */
    public val current: Float get() = factor

    /**
     * Takes the frame shown at [nanos], measured as [cells], and answers the factor to draw it with:
     * 1 outside a flashing run, and below it while one lasts or fades back.
     */
    public fun factorFor(cells: FloatArray, nanos: Long): Float {
        require(cells.size == CELLS) { "a frame is $CELLS cells, was ${cells.size}" }
        if (!measured) {
            cells.copyInto(extreme)
            measured = true
            previousNanos = nanos
            return factor
        }
        var risen = 0
        var fallen = 0
        var risenSize = 0f
        var fallenSize = 0f
        for (i in 0 until CELLS) {
            val light = cells[i]
            val way = direction[i]
            // Still going the way it last went: the extreme moves with it.
            if ((way > 0 && light > extreme[i]) || (way < 0 && light < extreme[i])) {
                extreme[i] = light
            } else {
                val change = light - extreme[i]
                // A turn from the last extreme by a step, with the darker side dark enough, is a leg.
                if (abs(change) >= STEP && min(light, extreme[i]) < DARK) {
                    legNanos[i] = nanos
                    legSize[i] = abs(change)
                    direction[i] = if (change > 0) 1 else -1
                    extreme[i] = light
                }
            }
            // A leg on this frame or the one before, so cells a frame apart count together.
            if (legNanos[i] >= previousNanos && legNanos[i] != Long.MIN_VALUE) {
                if (direction[i] > 0) {
                    risen++
                    risenSize += legSize[i]
                } else {
                    fallen++
                    fallenSize += legSize[i]
                }
            }
        }
        previousNanos = nanos
        val leg = when {
            risen >= AREA_CELLS && pictureDirection <= 0 -> 1
            fallen >= AREA_CELLS && pictureDirection >= 0 -> -1
            else -> 0
        }
        if (leg != 0) {
            pictureDirection = leg
            pictureLegs.addLast(nanos)
            while (pictureLegs.first() <= nanos - SECOND_NANOS) pictureLegs.removeFirst()
            val swing = if (leg > 0) risenSize / risen else fallenSize / fallen
            if (running || pictureLegs.size > FLASHES_ALLOWED * 2) {
                running = true
                lastRunLegNanos = nanos
                // The renderer scales encoded values, and light goes as their power of 2.2.
                factor = min(factor, min(1f, (OUTPUT_LEG / swing).pow(1f / ENCODING_POWER)))
                releaseFrom = factor
                return factor
            }
        }
        if (running) {
            val quiet = nanos - lastRunLegNanos - HOLD_NANOS
            if (quiet > 0) {
                val back = quiet.toFloat() / RELEASE_NANOS
                if (back >= 1f) {
                    running = false
                    factor = 1f
                    pictureLegs.clear()
                } else {
                    factor = releaseFrom + (1f - releaseFrom) * back
                }
            }
        }
        return factor
    }

    /** Forgets every frame, as after a clear of the picture: the next frame starts a fresh history. */
    public fun reset() {
        measured = false
        direction.fill(0)
        legNanos.fill(Long.MIN_VALUE)
        pictureDirection = 0
        pictureLegs.clear()
        factor = 1f
        running = false
    }

    public companion object {
        public const val COLUMNS: Int = 16
        public const val ROWS: Int = 9
        public const val CELLS: Int = COLUMNS * ROWS

        /** The change of relative luminance that makes a leg. */
        internal const val STEP = 0.10f

        /** A leg counts only while the darker side is below this. */
        internal const val DARK = 0.80f

        /** The share of the picture, in cells, that must move together: a quarter of it. */
        internal const val AREA_CELLS = CELLS / 4

        /** Flashes allowed in a second. A seventh leg within one starts a run. */
        internal const val FLASHES_ALLOWED = 3

        /** How large a leg may come out once dimmed. */
        internal const val OUTPUT_LEG = 0.08f

        /** How light goes with an encoded value, near enough for every transfer a renderer draws. */
        internal const val ENCODING_POWER = 2.2f

        internal const val SECOND_NANOS = 1_000_000_000L
        internal const val HOLD_NANOS = 1_000_000_000L
        internal const val RELEASE_NANOS = 1_500_000_000L

        /** Points sampled in each cell, across and down. */
        private const val SAMPLES = 4

        private val linear = FloatArray(256) { code ->
            val encoded = code / 255f
            if (encoded <= 0.04045f) encoded / 12.92f else ((encoded + 0.055f) / 1.055f).pow(2.4f)
        }

        /**
         * Measures [rgba], 8-bit RGBA rows of [width] pixels and [rowBytes] bytes each, into
         * [into]: the mean relative luminance of each cell from [SAMPLES] by [SAMPLES] points, in
         * linear light with the BT.709 weights. 2,304 pixels a frame, never the whole picture.
         */
        public fun cellsFromRgba(rgba: ByteArray, width: Int, height: Int, rowBytes: Int = width * 4, into: FloatArray) {
            lattice(width, height, into) { x, y ->
                val at = y * rowBytes + x * 4
                luminance(rgba[at].toInt() and 0xFF, rgba[at + 1].toInt() and 0xFF, rgba[at + 2].toInt() and 0xFF)
            }
        }

        /**
         * Measures [pixels], packed `0xRRGGBB` integers with the top byte ignored, in rows of
         * [width] pixels and [stride] entries each, into [into], as [cellsFromRgba] does.
         */
        public fun cellsFromPackedRgb(pixels: IntArray, width: Int, height: Int, stride: Int = width, into: FloatArray) {
            lattice(width, height, into) { x, y ->
                val pixel = pixels[y * stride + x]
                luminance((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
            }
        }

        /** Fills [into] with each cell's mean of [light] at [SAMPLES] by [SAMPLES] points. */
        private inline fun lattice(width: Int, height: Int, into: FloatArray, light: (x: Int, y: Int) -> Float) {
            require(into.size == CELLS) { "a frame is $CELLS cells, was ${into.size}" }
            require(width > 0 && height > 0) { "the picture is empty: $width x $height" }
            for (row in 0 until ROWS) {
                val top = row * height / ROWS
                val bottom = (row + 1) * height / ROWS
                for (column in 0 until COLUMNS) {
                    val left = column * width / COLUMNS
                    val right = (column + 1) * width / COLUMNS
                    var sum = 0f
                    for (sy in 0 until SAMPLES) {
                        val y = (top + (2 * sy + 1) * (bottom - top) / (2 * SAMPLES)).coerceIn(0, height - 1)
                        for (sx in 0 until SAMPLES) {
                            val x = (left + (2 * sx + 1) * (right - left) / (2 * SAMPLES)).coerceIn(0, width - 1)
                            sum += light(x, y)
                        }
                    }
                    into[row * COLUMNS + column] = sum / (SAMPLES * SAMPLES)
                }
            }
        }

        /** The relative luminance of one 8-bit sRGB colour, in linear light with the BT.709 weights. */
        private fun luminance(red: Int, green: Int, blue: Int): Float =
            0.2126f * linear[red] + 0.7152f * linear[green] + 0.0722f * linear[blue]
    }
}
