package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette

/**
 * The readings a shader looks things up in, kept as very small pictures.
 *
 * The obvious way to hand a spectrum to a shader is as a list of numbers, and it does not work:
 * the language will not let a program pick an entry of a list by a value worked out while it runs,
 * only by a number fixed when it was written. Which is no use at all when the whole point is to
 * ask "how loud is the music at this position on screen".
 *
 * A picture has no such restriction, because looking things up in pictures is what the hardware
 * is for. So each reading becomes a strip of pixels one row tall, redrawn every frame. The
 * hardware blends between neighbouring pixels on the way out, which gives smooth interpolation
 * between bars for free.
 *
 * The cost is one small strip per frame. A sixty four pixel strip is 256 bytes.
 *
 * The history is the one taller picture: the last few seconds of spectrum, one row per frame, so a
 * shader can draw a spectrogram or be pushed about by what the music did a moment ago. Its rows are
 * written round and round rather than shifted, and the shader is told which one is newest.
 */
internal class ShaderData {

    private val bands = ImageBitmap(ShaderLibrary.BANDS, 1)
    private val scope = ImageBitmap(ShaderLibrary.SCOPE, 1)
    private val palette = ImageBitmap(PALETTE_STEPS, 1)
    private val history = ImageBitmap(ShaderLibrary.BANDS, ShaderLibrary.HISTORY)
    private var historyRow = 0
    private var historyFresh = true
    private val painter = CanvasDrawScope()
    private var paletteFor: VizPalette? = null

    /** Redraws the spectrum strip. Values are written into the red channel. */
    fun writeBands(values: FloatArray) {
        writeStrip(bands, ShaderLibrary.BANDS) { at ->
            val read = sample(values, at)
            Color(read.coerceIn(0f, 1f), 0f, 0f, 1f)
        }
    }

    /** Redraws the waveform strip, with zero sitting at half brightness. */
    fun writeScope(values: FloatArray, gain: Float = 1f) {
        writeStrip(scope, ShaderLibrary.SCOPE) { at ->
            val read = sample(values, at) * gain
            Color((read * 0.5f + 0.5f).coerceIn(0f, 1f), 0f, 0f, 1f)
        }
    }

    /** Redraws the colour ramp, but only when the palette has actually changed. */
    fun writePalette(from: VizPalette) {
        if (paletteFor == from) return
        paletteFor = from
        writeStrip(palette, PALETTE_STEPS) { at -> from.ramp(at) }
    }

    /** Writes this frame's spectrum as the newest row of the history. */
    fun writeHistory(values: FloatArray) {
        val row = historyRow.toFloat()
        val size = Size(ShaderLibrary.BANDS.toFloat(), ShaderLibrary.HISTORY.toFloat())
        // The very first spectrum fills every row, so the past starts as the present rather than as
        // silence, which would show as a hard edge sweeping across anything the history drives.
        val tall = if (historyFresh) ShaderLibrary.HISTORY.toFloat() else 1f
        val top = if (historyFresh) 0f else row
        historyFresh = false
        painter.draw(Density(1f), LayoutDirection.Ltr, Canvas(history), size) {
            for (pixel in 0 until ShaderLibrary.BANDS) {
                val read = sample(values, pixel.toFloat() / (ShaderLibrary.BANDS - 1))
                drawRect(Color(read.coerceIn(0f, 1f), 0f, 0f, 1f), Offset(pixel.toFloat(), top), Size(1f, tall))
            }
        }
        historyRow = (historyRow + 1) % ShaderLibrary.HISTORY
    }

    /** Forgets the history, so a drawing shown again does not start from last time's music. */
    fun clearHistory() {
        val size = Size(ShaderLibrary.BANDS.toFloat(), ShaderLibrary.HISTORY.toFloat())
        painter.draw(Density(1f), LayoutDirection.Ltr, Canvas(history), size) {
            drawRect(Color.Black)
        }
        historyRow = 0
        historyFresh = true
    }

    /** Hands them all to a program under the names the shared header declares. */
    fun bindTo(program: ShaderProgram) {
        program.child("uBandsTex", bands)
        program.child("uScopeTex", scope)
        program.child("uPaletteTex", palette)
        program.child("uHistoryTex", history)
        program.uniform("uHistoryRow", historyRow.toFloat())
    }

    private inline fun writeStrip(into: ImageBitmap, width: Int, colourAt: (Float) -> Color) {
        val canvas = Canvas(into)
        painter.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(width.toFloat(), 1f)) {
            for (pixel in 0 until width) {
                drawRect(
                    color = colourAt(if (width == 1) 0f else pixel.toFloat() / (width - 1)),
                    topLeft = Offset(pixel.toFloat(), 0f),
                    size = Size(1f, 1f),
                )
            }
        }
    }

    private fun sample(from: FloatArray, at: Float): Float {
        if (from.isEmpty()) return 0f
        if (from.size == 1) return from[0]
        val scaled = at.coerceIn(0f, 1f) * (from.size - 1)
        val lower = scaled.toInt().coerceIn(0, from.size - 2)
        return from[lower] + (from[lower + 1] - from[lower]) * (scaled - lower)
    }

    private companion object {
        /** Enough steps that a cycling colour does not show bands. */
        const val PALETTE_STEPS = 64
    }
}
