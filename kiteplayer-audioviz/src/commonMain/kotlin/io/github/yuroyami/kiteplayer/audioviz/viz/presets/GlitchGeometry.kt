package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.BARS
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.SCAN_HALF
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.TRACE_POINTS
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The clean picture of [Glitch] as one batch of shapes: the bars, the streaks of a melt and the trace.
 *
 * The bars keep hard pixel edges. When the colour layers split, the batch is drawn three times, once
 * per channel, each on a layer of its own that adds its light, so where the layers meet the colour is
 * the same and only the edges show red, green and blue.
 */
internal class GlitchGeometry {

    /** The picture as last built, in the pixels of the canvas it was built for. */
    val picture = TriangleMesh(maxVertices = MOST_VERTICES, maxIndices = MOST_INDICES)

    private val saved = IntArray(MOST_VERTICES)
    private val adding = Paint().apply { blendMode = BlendMode.Plus }
    private val rowY = FloatArray(5)
    private val rowColour = IntArray(5)
    private val traceX = FloatArray(TRACE_POINTS)
    private val traceY = FloatArray(TRACE_POINTS)

    /** Builds [picture] from [scene] for a canvas of [width] by [height] pixels. */
    fun build(scene: GlitchScene, width: Float, height: Float) {
        picture.clear()
        if (width <= 0f || height <= 0f) return
        val collapse = scene.collapse
        val middle = height * 0.5f
        val half = max(height * 0.5f * (1f - collapse * (1f - LINE_SHARE)), 1f)
        val top = (middle - half).roundToInt().toFloat()
        val bottom = (middle + half).roundToInt().toFloat().coerceAtLeast(top + 1f)
        val set = scene.channel % SETS.size
        // Collapsing, the bars also close their gaps, so the line of a breakdown is unbroken.
        val share = BAR_SHARE + (1f - BAR_SHARE) * collapse
        for (bar in 0 until BARS) {
            val left = (scene.edges[bar] * width).roundToInt().toFloat()
            val slot = (scene.edges[bar + 1] * width).roundToInt().toFloat() - left
            if (slot < 1f) continue
            val right = left + max(1f, (slot * share).roundToInt().toFloat())
            val hue = SETS[set][bar % 3]
            val light = scene.exposure[bar] + (1f - scene.exposure[bar]) * collapse * LINE_LIGHT
            val white = scene.white[bar] + (1f - scene.white[bar]) * collapse * LINE_WHITE
            addBar(scene, hue, left, right, top, bottom, light, white, collapse)
            if (scene.melt[bar] >= 0f && collapse < 0.5f) {
                addMelt(scene, bar, hue, left, right, top, bottom, light, white)
            }
        }
        addTrace(scene, width, height, middle, collapse)
    }

    /**
     * Draws [picture] on a black ground, with its red layer [split] pixels to the left and its blue
     * layer as far to the right. Below a pixel the layers stand together and it draws once.
     */
    fun DrawScope.drawPicture(split: Float) {
        if (split < 1f) {
            drawMesh(picture)
            return
        }
        val count = picture.vertexCount
        picture.colors.copyInto(saved, 0, 0, count)
        for (channel in CHANNELS.indices) {
            val mask = CHANNELS[channel]
            for (vertex in 0 until count) picture.colors[vertex] = saved[vertex] and mask
            translate((channel - 1) * split, 0f) {
                drawIntoCanvas { canvas ->
                    // The layer adds to what is under it, while the shapes inside cover each other.
                    canvas.saveLayer(Rect(-split, 0f, size.width + split, size.height), adding)
                    drawMesh(picture)
                    canvas.restore()
                }
            }
        }
        saved.copyInto(picture.colors, 0, 0, count)
    }

    /** One bar, from [top] to [bottom], brighter where the scan bar crosses it. */
    private fun addBar(scene: GlitchScene, hue: Int, left: Float, right: Float, top: Float, bottom: Float,
        light: Float, white: Float, collapse: Float) {
        val scan = scene.scanStrength * (1f - collapse)
        val base = colour(hue, light, white)
        if (scan < 0.01f) {
            picture.bar(left, right, top, bottom, base, base)
            return
        }
        val tall = bottom - top
        val centre = top + scene.scanAt * tall
        val reach = SCAN_HALF * tall
        rowY[0] = top
        rowY[1] = (centre - reach).coerceIn(top, bottom)
        rowY[2] = centre.coerceIn(top, bottom)
        rowY[3] = (centre + reach).coerceIn(top, bottom)
        rowY[4] = bottom
        for (row in rowY.indices) {
            val near = (1f - kotlin.math.abs(rowY[row] - centre) / reach).coerceIn(0f, 1f)
            rowColour[row] = if (near <= 0f) base else colour(hue, light + (1f - light) * SCAN_LIFT * scan * near, white)
        }
        for (row in 0 until 4) {
            if (rowY[row + 1] <= rowY[row]) continue
            picture.bar(left, right, rowY[row], rowY[row + 1], rowColour[row], rowColour[row + 1])
        }
    }

    /**
     * A melting bar, the way a column looks once it is sorted by brightness: its light pours down,
     * dark at the top and bright at the foot, and in each thin strip a short white streak falls.
     * Each strip starts and falls on its own, so the bar breaks into streaks, and as the melt runs
     * out the bar returns to its own colour.
     */
    private fun addMelt(scene: GlitchScene, bar: Int, hue: Int, left: Float, right: Float, top: Float, bottom: Float,
        light: Float, white: Float) {
        val run = scene.melt[bar]
        val strength = 1f - run * run
        val tall = bottom - top
        val drained = colour(hue, light * (1f - 0.7f * strength), white)
        val pooled = colour(hue, light + (1f - light) * 0.8f * strength, white + (1f - white) * 0.3f * strength)
        picture.bar(left, right, top, bottom, drained, pooled)
        val hot = colour(hue, light + (1f - light) * strength, white + (1f - white) * 0.85f * strength)
        val wide = right - left
        val strips = (wide / MELT_STRIP).toInt().coerceIn(1, MOST_STRIPS)
        val seed = scene.meltSeed[bar]
        for (strip in 0 until strips) {
            val from = left + (wide * strip / strips).roundToInt()
            val to = left + (wide * (strip + 1) / strips).roundToInt()
            if (to <= from) continue
            val start = 0.06f + 0.44f * hash(seed, strip * 3)
            val length = 0.07f + 0.13f * hash(seed, strip * 3 + 1)
            val fallen = (0.25f + 0.35f * hash(seed, strip * 3 + 2)) * run.pow(0.7f) * scene.fall
            val head = (top + (start + length + fallen) * tall).coerceAtMost(bottom).roundToInt().toFloat()
            val tail = (top + (start + fallen * 0.5f) * tall).coerceAtMost(head).roundToInt().toFloat()
            if (head - tail < 1f) continue
            // The streak leaves the sorted bar at the colour the bar has there, so only its head shows.
            picture.bar(from, to, tail, head, blend(drained, pooled, (tail - top) / tall), hot)
        }
    }

    /** The colour [amount] of the way from [from] to [to], channel by channel, as the mesh blends them. */
    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        var out = 0
        for (shift in 0..24 step 8) {
            val a = from ushr shift and 0xFF
            val b = to ushr shift and 0xFF
            out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /** The waveform as a thick white ribbon across the middle, or along the line of a breakdown. */
    private fun addTrace(scene: GlitchScene, width: Float, height: Float, middle: Float, collapse: Float) {
        val reach = height * 0.5f * (TRACE_REACH + (LINE_REACH - TRACE_REACH) * collapse)
        for (point in 0 until TRACE_POINTS) {
            traceX[point] = width * point / (TRACE_POINTS - 1)
            traceY[point] = middle - scene.trace[point] * reach
        }
        val thick = max(2f, min(width, height) * TRACE_THICKNESS) * 0.5f
        val grey = colour(0, scene.traceExposure, 1f)
        var previous = -1
        for (point in 0 until TRACE_POINTS) {
            val before = max(0, point - 1)
            val after = min(TRACE_POINTS - 1, point + 1)
            val dx = traceX[after] - traceX[before]
            val dy = traceY[after] - traceY[before]
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val nx = -dy / length * thick
            val ny = dx / length * thick
            val upper = picture.vertex(traceX[point] + nx, traceY[point] + ny, grey)
            picture.vertex(traceX[point] - nx, traceY[point] - ny, grey)
            if (upper < 0) return
            if (previous >= 0) picture.quad(previous, upper, upper + 1, previous + 1)
            previous = upper
        }
    }

    /**
     * A swatch at [light], in linear light, moved [white] of the way to white. The swatches are the
     * idea of this drawing, so no palette and no key changes them.
     */
    private fun colour(hue: Int, light: Float, white: Float): Int {
        val lightness = SWATCH_LIGHTNESS[hue]
        val chroma = SWATCH_CHROMA[hue]
        val towards = white.coerceIn(0f, 1f)
        val lifted = lightness + (1f - lightness) * towards
        val colourful = min(chroma * (1f - towards), mostChroma(lifted, SWATCH_HUE[hue]))
        // Scaling linear light by k scales Oklab's lightness and chroma by its cube root.
        val scale = light.coerceIn(0f, 1f).pow(1f / 3f)
        return colourOf(lifted * scale, colourful * scale, SWATCH_HUE[hue]).toArgb()
    }

    private companion object {
        const val MOST_VERTICES = 2_800
        const val MOST_INDICES = 4_800

        /** How much of its slot a bar fills. The rest is black, like the gaps between subpixels. */
        const val BAR_SHARE = 0.7f

        /** The line of a breakdown, as a share of the height, and how bright and white it glows. */
        const val LINE_SHARE = 0.012f
        const val LINE_LIGHT = 0.85f
        const val LINE_WHITE = 0.5f

        /** How far the trace swings for a full-scale wave, as a share of the half height. */
        const val TRACE_REACH = 0.3f
        const val LINE_REACH = 0.08f
        const val TRACE_THICKNESS = 0.011f

        /** How much of the way to full light the scan bar lifts a bar. */
        const val SCAN_LIFT = 0.6f

        /** A melt's strips are about this many pixels wide, and a bar has at most this many. */
        const val MELT_STRIP = 3f
        const val MOST_STRIPS = 5

        /** Red, green and blue alone, alpha kept. */
        val CHANNELS = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

        // The swatches as Oklch lightness, chroma and hue: white for the trace, then cyan, magenta,
        // yellow, gold, orchid and orange. Each vivid one sits near its hue's cusp, inside sRGB.
        val SWATCH_LIGHTNESS = floatArrayOf(1f, 0.86f, 0.64f, 0.95f, 0.84f, 0.62f, 0.72f)
        val SWATCH_CHROMA = floatArrayOf(0f, 0.14f, 0.26f, 0.16f, 0.17f, 0.25f, 0.19f)
        val SWATCH_HUE = floatArrayOf(0f, 205f, 350f, 105f, 85f, 320f, 50f)

        /**
         * Three colours at a time, in turn across the bars like the subpixels of a screen seen up
         * close. A channel change moves to the next set, and every bar changes colour with it. No set
         * holds red, green or blue: those appear only as the fringes of a split.
         */
        val SETS = arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 1, 5), intArrayOf(5, 6, 1))

        /** A repeatable number from 0 to 1 for [seed] and [index]. */
        fun hash(seed: Int, index: Int): Float {
            var h = seed * 374_761_393 + index * 668_265_263
            h = (h xor (h ushr 13)) * 1_274_126_177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 65_535f
        }
    }
}
