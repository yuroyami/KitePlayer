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
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.ROWS_MOST
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.SCAN_HALF
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.TRACE_BAND
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.TRACE_POINTS
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The clean picture of [Glitch] as one batch of shapes: the blocks of the spectrogram and the trace.
 *
 * The blocks keep hard pixel edges and a one pixel gap. When the colour layers split, the batch is drawn
 * three times, once per channel, each on a layer of its own that adds its light, so where the layers meet
 * the colour is the same and only the edges show red, green and blue.
 */
internal class GlitchGeometry {

    /** The picture as last built, in the pixels of the canvas it was built for. */
    val picture = TriangleMesh(maxVertices = MOST_VERTICES, maxIndices = MOST_INDICES)

    private val saved = IntArray(MOST_VERTICES)
    private val adding = Paint().apply { blendMode = BlendMode.Plus }
    private val traceX = FloatArray(TRACE_POINTS)
    private val traceY = FloatArray(TRACE_POINTS)

    // How far the walk turns every swatch hue in this build, in degrees.
    private var turn = 0f

    /** Builds [picture] from [scene] for a canvas of [width] by [height] pixels. */
    fun build(scene: GlitchScene, width: Float, height: Float) {
        picture.clear()
        if (width <= 0f || height <= 0f) return
        val middle = height * 0.5f
        turn = scene.walkTurn
        if (scene.collapse >= MERGE_AT) addLine(scene, scene.set, width, height, middle) else addBlocks(scene, width, height, middle)
        addTrace(scene, width, height, middle, scene.collapse)
    }

    /**
     * The spectrogram: bass in the bottom row, the newest column on the right, a clear band across the middle
     * for the ribbon. A collapse folds the rows toward the middle; the tracking roll moves them and wraps. A
     * hat's slipped row slides sideways and wraps round.
     */
    private fun addBlocks(scene: GlitchScene, width: Float, height: Float, middle: Float) {
        val collapse = scene.collapse
        val columns = scene.columns
        val rows = scene.rows
        val cellWidth = width / columns
        val band = height * TRACE_BAND
        val cellHeight = (height - band) / rows
        val lower = rows / 2
        val fold = 1f - collapse * (1f - LINE_SHARE)
        val roll = scene.trackingY * height * (1f - collapse)
        val scan = scene.scanStrength * (1f - collapse)
        val scanY = scene.scanAt * height
        val scanReach = SCAN_HALF * height
        val slip = (scene.slipShift * width).roundToInt().toFloat()
        for (column in 0 until columns) {
            val left = (column * cellWidth).roundToInt().toFloat()
            val right = ((column + 1) * cellWidth).roundToInt().toFloat() - GAP
            if (right <= left) continue
            for (row in 0 until rows) {
                val shift = if (row == scene.slipRow) slip else 0f
                val floor = if (row < lower) height - row * cellHeight else height - row * cellHeight - band
                val top = (middle + (floor - cellHeight - middle) * fold + roll).roundToInt().toFloat()
                val bottom = (middle + (floor - middle) * fold + roll).roundToInt().toFloat() - GAP
                if (bottom <= top) continue
                var light = scene.blockLight(column, row)
                if (scan >= 0.01f) {
                    val near = (1f - abs((top + bottom) * 0.5f - scanY) / scanReach).coerceIn(0f, 1f)
                    light += (1f - light) * SCAN_LIFT * scan * near
                }
                light += (1f - light) * collapse * LINE_LIGHT
                var white = scene.white[column * ROWS_MOST + row]
                white += (1f - white) * collapse * LINE_WHITE
                val colour = colour(swatchOf(scene.setOfRow(row), scene.blockLevel(column, row)), light, white)
                if (shift == 0f) {
                    addWrapped(left, right, top, bottom, height, colour)
                } else {
                    // A slipped block that leaves one side of the screen comes back on the other.
                    addWrapped(left + shift, right + shift, top, bottom, height, colour)
                    val wrap = if (shift > 0f) -width else width
                    if (left + shift < 0f || right + shift > width) {
                        addWrapped(left + shift + wrap, right + shift + wrap, top, bottom, height, colour)
                    }
                }
            }
        }
    }

    /** One block, the part rolled past an edge drawn at the other edge. */
    private fun addWrapped(left: Float, right: Float, top: Float, bottom: Float, height: Float, colour: Int) {
        when {
            bottom > height -> {
                if (top < height) picture.bar(left, right, top, height, colour, colour)
                picture.bar(left, right, max(0f, top - height), bottom - height, colour, colour)
            }
            top < 0f -> {
                if (bottom > 0f) picture.bar(left, right, 0f, bottom, colour, colour)
                picture.bar(left, right, top + height, min(height, bottom + height), colour, colour)
            }
            else -> picture.bar(left, right, top, bottom, colour, colour)
        }
    }

    /** The line of a breakdown: one bright bar per column, closing its gaps as the picture folds away. */
    private fun addLine(scene: GlitchScene, set: Int, width: Float, height: Float, middle: Float) {
        val collapse = scene.collapse
        val half = max(height * 0.5f * (1f - collapse * (1f - LINE_SHARE)), 1f)
        val top = (middle - half).roundToInt().toFloat()
        val bottom = (middle + half).roundToInt().toFloat().coerceAtLeast(top + 1f)
        val share = BAR_SHARE + (1f - BAR_SHARE) * collapse
        val columns = scene.columns
        for (column in 0 until columns) {
            val left = (column * width / columns).roundToInt().toFloat()
            val slot = ((column + 1) * width / columns).roundToInt().toFloat() - left
            if (slot < 1f) continue
            val right = left + max(1f, (slot * share).roundToInt().toFloat())
            var light = 0f
            var white = 0f
            var level = 0f
            for (row in 0 until scene.rows) {
                light += scene.blockLight(column, row)
                white += scene.white[column * ROWS_MOST + row]
                level += scene.blockLevel(column, row)
            }
            light /= scene.rows
            white /= scene.rows
            level /= scene.rows
            light += (1f - light) * collapse * LINE_LIGHT
            white += (1f - white) * collapse * LINE_WHITE
            val colour = colour(swatchOf(set, level), light, white)
            picture.bar(left, right, top, bottom, colour, colour)
        }
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
     * A swatch at [light], in linear light, moved [white] of the way to white, its hue turned by the walk.
     * The swatches are the idea of this drawing, so no palette changes them.
     */
    private fun colour(hue: Int, light: Float, white: Float): Int {
        val lightness = SWATCH_LIGHTNESS[hue]
        val chroma = SWATCH_CHROMA[hue]
        val towards = white.coerceIn(0f, 1f)
        val lifted = lightness + (1f - lightness) * towards
        val colourful = min(chroma * (1f - towards), mostChroma(lifted, SWATCH_HUE[hue] + turn))
        // The light scales lightness and chroma as the other drawings scale their colours, so a calm song
        // reads as a dimmer screen and not only as a different swatch.
        val scale = light.coerceIn(0f, 1f)
        return colourOf(lifted * scale, colourful * scale, SWATCH_HUE[hue] + turn).toArgb()
    }

    private companion object {
        const val MOST_VERTICES = 6_000
        const val MOST_INDICES = 9_000

        /** The black gap below and to the right of every block, in pixels. */
        const val GAP = 1f

        /** Past this much collapse the rows merge into one bar per column. */
        const val MERGE_AT = 0.5f

        /** How much of its slot a column of the line fills before the line closes. */
        const val BAR_SHARE = 0.7f

        /** The line of a breakdown, as a share of the height, and how bright and white it glows. */
        const val LINE_SHARE = 0.012f
        const val LINE_LIGHT = 0.85f
        const val LINE_WHITE = 0.5f

        /** How far the trace swings for a full-scale wave, as a share of the half height. */
        const val TRACE_REACH = 0.3f
        const val LINE_REACH = 0.08f
        const val TRACE_THICKNESS = 0.011f

        /** How much of the way to full light the scan bar lifts a block. */
        const val SCAN_LIFT = 0.6f

        /** Red, green and blue alone, alpha kept. */
        val CHANNELS = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

        // The swatches as Oklch lightness, chroma and hue: white for the trace, then cyan, magenta,
        // yellow, gold, orchid and orange. Each vivid one sits near its hue's cusp, inside sRGB.
        val SWATCH_LIGHTNESS = floatArrayOf(1f, 0.86f, 0.64f, 0.95f, 0.84f, 0.62f, 0.72f)
        val SWATCH_CHROMA = floatArrayOf(0f, 0.14f, 0.26f, 0.16f, 0.17f, 0.25f, 0.19f)
        val SWATCH_HUE = floatArrayOf(0f, 205f, 350f, 105f, 85f, 320f, 50f)

        /**
         * Three colours at a time: a quiet block takes the first, a loud one the third. A channel change
         * moves to the next set, and every block changes colour with it. No set holds red, green or blue:
         * those appear only as the fringes of a split.
         */
        val SETS = arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 1, 5), intArrayOf(5, 6, 1))

        /** The swatch of [set] for a block at [level]: its strength in thirds. */
        fun swatchOf(set: Int, level: Float): Int = SETS[set][min(2, (GlitchScene.response(level) * 3f).toInt())]
    }
}
