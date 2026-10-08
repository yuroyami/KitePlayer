package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * The flash guard every renderer runs on the picture it draws (#500): it counts flashes by the
 * general flash rule and the red flash rule, and answers the factor to draw each frame with, 1
 * outside a flashing run. The whole rule is in `docs/video-flash-guard.md`.
 *
 * A renderer measures each frame on a grid of [COLUMNS] by [ROWS] cells in reading order, before
 * any adjustment, and hands the measures to [factorFor]. It multiplies its adjustment's colour
 * matrix, offsets included, by the answer. [cellsFromRgba] and [cellsFromPackedRgb] measure
 * converted pixels on a sparse lattice.
 *
 * A frame is [MEASURES] values, three runs of [CELLS], all in linear light:
 * - the mean relative luminance of each cell, from 0 for black to 1 for white;
 * - the mean red measure of each cell, which is `R - G - B` with a negative taken as zero (#561);
 * - the share of each cell that is a saturated red, where `R / (R + G + B)` is 0.8 or more.
 *
 * A frame of only the first [CELLS] values is taken too, and then red flashes are not counted.
 *
 * One renderer's, on one thread: it keeps the history of the frames it was given.
 */
@KitePlayerLowLevelApi
public class VideoFlashGuard {

    private val light = Track(red = false)
    private val red = Track(red = true)
    private var previousNanos = Long.MIN_VALUE

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
        require(cells.size == MEASURES || cells.size == CELLS) { "a frame is $MEASURES or $CELLS values, was ${cells.size}" }
        val lightSwing = light.step(cells, nanos, previousNanos)
        val redSwing = if (cells.size == MEASURES) red.step(cells, nanos, previousNanos) else 0f
        previousNanos = nanos
        // The renderer scales encoded values, and light goes as their power of 2.2.
        var wanted = 1f
        var runLeg = false
        if (lightSwing > 0f && (running || light.flashing)) {
            runLeg = true
            wanted = min(wanted, (OUTPUT_LEG / lightSwing).pow(1f / ENCODING_POWER))
        }
        if (redSwing > 0f && (running || red.flashing)) {
            runLeg = true
            wanted = min(wanted, (RED_OUTPUT_LEG / redSwing).pow(1f / ENCODING_POWER))
        }
        if (runLeg) {
            running = true
            lastRunLegNanos = nanos
            factor = min(factor, wanted)
            releaseFrom = factor
            return factor
        }
        if (running) {
            val quiet = nanos - lastRunLegNanos - HOLD_NANOS
            // Compared as whole nanoseconds: a Float rounds them, and not the same way on Kotlin/JS.
            if (quiet >= RELEASE_NANOS) {
                running = false
                factor = 1f
                light.forgetLegs()
                red.forgetLegs()
            } else if (quiet > 0) {
                factor = releaseFrom + (1f - releaseFrom) * (quiet.toFloat() / RELEASE_NANOS)
            }
        }
        return factor
    }

    /** Forgets every frame, as after a clear of the picture: the next frame starts a fresh history. */
    public fun reset() {
        light.reset()
        red.reset()
        factor = 1f
        running = false
    }

    /**
     * One rule's history: the luminance of each cell, or its red measure when [red]. Both count a
     * leg the same way, over the same quarter of the picture.
     */
    private class Track(private val red: Boolean) {
        private val extreme = FloatArray(CELLS)
        /** How much of each cell was a saturated red at its extreme. Read by the red rule only. */
        private val extremeShare = FloatArray(CELLS)
        /** The way each cell last moved, +1 or -1, or 0 before it has. */
        private val direction = IntArray(CELLS)
        /** When each cell last made a leg, and how far it moved. */
        private val legNanos = LongArray(CELLS) { Long.MIN_VALUE }
        private val legSize = FloatArray(CELLS)
        private var measured = false

        /** The way the picture last made a leg, +1, -1 or 0. */
        private var pictureDirection = 0
        private val pictureLegs = ArrayDeque<Long>()

        /** True while the legs of the last second are more than the rule allows. */
        val flashing: Boolean get() = pictureLegs.size > FLASHES_ALLOWED * 2

        /** Takes one frame and answers the mean swing of the picture's leg on it, or 0 without one. */
        fun step(cells: FloatArray, nanos: Long, previousNanos: Long): Float {
            val from = if (red) CELLS else 0
            if (!measured) {
                cells.copyInto(extreme, 0, from, from + CELLS)
                if (red) cells.copyInto(extremeShare, 0, 2 * CELLS, 3 * CELLS)
                measured = true
                return 0f
            }
            var risen = 0
            var fallen = 0
            var risenSize = 0f
            var fallenSize = 0f
            for (i in 0 until CELLS) {
                val value = cells[from + i]
                val way = direction[i]
                // Still going the way it last went: the extreme moves with it.
                if ((way > 0 && value > extreme[i]) || (way < 0 && value < extreme[i])) {
                    extreme[i] = value
                    if (red) extremeShare[i] = cells[2 * CELLS + i]
                } else {
                    val change = value - extreme[i]
                    // A turn from the last extreme by a step is a leg: with the darker side dark
                    // enough for luminance, and with a saturated red on either side for red.
                    val counts = if (red) {
                        abs(change) >= RED_STEP && (cells[2 * CELLS + i] >= RED_SHARE || extremeShare[i] >= RED_SHARE)
                    } else {
                        abs(change) >= STEP && min(value, extreme[i]) < DARK
                    }
                    if (counts) {
                        legNanos[i] = nanos
                        legSize[i] = abs(change)
                        direction[i] = if (change > 0) 1 else -1
                        extreme[i] = value
                        if (red) extremeShare[i] = cells[2 * CELLS + i]
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
            val leg = when {
                risen >= AREA_CELLS && pictureDirection <= 0 -> 1
                fallen >= AREA_CELLS && pictureDirection >= 0 -> -1
                else -> return 0f
            }
            pictureDirection = leg
            pictureLegs.addLast(nanos)
            while (pictureLegs.first() <= nanos - SECOND_NANOS) pictureLegs.removeFirst()
            return if (leg > 0) risenSize / risen else fallenSize / fallen
        }

        fun forgetLegs() {
            pictureLegs.clear()
        }

        fun reset() {
            measured = false
            direction.fill(0)
            legNanos.fill(Long.MIN_VALUE)
            pictureDirection = 0
            pictureLegs.clear()
        }
    }

    public companion object {
        public const val COLUMNS: Int = 16
        public const val ROWS: Int = 9
        public const val CELLS: Int = COLUMNS * ROWS

        /** The length of a whole frame: luminance, red measure and saturated red share for each cell. */
        public const val MEASURES: Int = CELLS * 3

        /** The change of relative luminance that makes a leg. */
        internal const val STEP = 0.10f

        /** A leg counts only while the darker side is below this. */
        internal const val DARK = 0.80f

        /** The change of the red measure that makes a leg: 20 of the 320 that WCAG 2.2 scales it to. */
        internal const val RED_STEP = 20f / 320f

        /** A red leg counts only when this much of the cell is a saturated red on one side of it. */
        internal const val RED_SHARE = 0.5f

        /** A colour is a saturated red when red is this share of its three values. */
        internal const val SATURATED_RED = 0.8f

        /** How large a red leg may come out once dimmed: 16 of 320. */
        internal const val RED_OUTPUT_LEG = 16f / 320f

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
         * [into] from [SAMPLES] by [SAMPLES] points in each cell, in linear light: 2,304 pixels a
         * frame, never the whole picture. An [into] of [MEASURES] values takes the whole frame, and
         * one of [CELLS] takes the relative luminance alone, with the BT.709 weights.
         */
        public fun cellsFromRgba(rgba: ByteArray, width: Int, height: Int, rowBytes: Int = width * 4, into: FloatArray) {
            lattice(width, height, into) { x, y ->
                val at = y * rowBytes + x * 4
                ((rgba[at].toInt() and 0xFF) shl 16) or ((rgba[at + 1].toInt() and 0xFF) shl 8) or (rgba[at + 2].toInt() and 0xFF)
            }
        }

        /**
         * Measures [pixels], packed `0xRRGGBB` integers with the top byte ignored, in rows of
         * [width] pixels and [stride] entries each, into [into], as [cellsFromRgba] does.
         */
        public fun cellsFromPackedRgb(pixels: IntArray, width: Int, height: Int, stride: Int = width, into: FloatArray) {
            lattice(width, height, into) { x, y -> pixels[y * stride + x] }
        }

        /** Fills [into] from the packed `0xRRGGBB` colour [pixel] answers at [SAMPLES] by [SAMPLES] points of each cell. */
        private inline fun lattice(width: Int, height: Int, into: FloatArray, pixel: (x: Int, y: Int) -> Int) {
            require(into.size == MEASURES || into.size == CELLS) { "a frame is $MEASURES or $CELLS values, was ${into.size}" }
            require(width > 0 && height > 0) { "the picture is empty: $width x $height" }
            val whole = into.size == MEASURES
            for (row in 0 until ROWS) {
                val top = row * height / ROWS
                val bottom = (row + 1) * height / ROWS
                for (column in 0 until COLUMNS) {
                    val left = column * width / COLUMNS
                    val right = (column + 1) * width / COLUMNS
                    var sum = 0f
                    var redSum = 0f
                    var saturated = 0
                    for (sy in 0 until SAMPLES) {
                        val y = (top + (2 * sy + 1) * (bottom - top) / (2 * SAMPLES)).coerceIn(0, height - 1)
                        for (sx in 0 until SAMPLES) {
                            val x = (left + (2 * sx + 1) * (right - left) / (2 * SAMPLES)).coerceIn(0, width - 1)
                            val colour = pixel(x, y)
                            val r = linear[(colour shr 16) and 0xFF]
                            val g = linear[(colour shr 8) and 0xFF]
                            val b = linear[colour and 0xFF]
                            sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
                            if (whole) {
                                val measure = r - g - b
                                if (measure > 0f) redSum += measure
                                // Black has no share of anything, so it is not a red.
                                if (r > 0f && r >= SATURATED_RED * (r + g + b)) saturated++
                            }
                        }
                    }
                    val cell = row * COLUMNS + column
                    into[cell] = sum / (SAMPLES * SAMPLES)
                    if (whole) {
                        into[CELLS + cell] = redSum / (SAMPLES * SAMPLES)
                        into[2 * CELLS + cell] = saturated.toFloat() / (SAMPLES * SAMPLES)
                    }
                }
            }
        }
    }
}
