package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
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

    private val bands = PixelImage(ShaderLibrary.BANDS, 1)
    private val scope = PixelImage(ShaderLibrary.SCOPE, 1)
    private val palette = PixelImage(PALETTE_STEPS, 1)
    private val history = PixelImage(ShaderLibrary.BANDS, ShaderLibrary.HISTORY)
    private var historyRow = 0
    private var historyFresh = true
    private var paletteFor: VizPalette? = null

    /** Redraws the spectrum strip. Values are written into the red channel. */
    fun writeBands(values: FloatArray) {
        writeStrip(bands, ShaderLibrary.BANDS) { at ->
            val read = sample(values, at)
            red(read)
        }
    }

    /** Redraws the waveform strip, with zero sitting at half brightness. */
    fun writeScope(values: FloatArray, gain: Float = 1f) {
        writeStrip(scope, ShaderLibrary.SCOPE) { at ->
            val read = sample(values, at) * gain
            red(read * 0.5f + 0.5f)
        }
    }

    /** Redraws the colour ramp, but only when the palette has actually changed. */
    fun writePalette(from: VizPalette) {
        if (paletteFor == from) return
        paletteFor = from
        writeStrip(palette, PALETTE_STEPS) { at -> from.ramp(at).toArgb() }
    }

    /** Writes this frame's spectrum as the newest row of the history. */
    fun writeHistory(values: FloatArray) {
        // The very first spectrum fills every row, so the past starts as the present rather than as
        // silence, which would show as a hard edge sweeping across anything the history drives.
        val row = historyRow * ShaderLibrary.BANDS
        for (pixel in 0 until ShaderLibrary.BANDS) {
            history.pixels[row + pixel] = red(sample(values, pixel.toFloat() / (ShaderLibrary.BANDS - 1)))
        }
        if (historyFresh) {
            for (other in 0 until ShaderLibrary.HISTORY) {
                if (other == historyRow) continue
                history.pixels.copyInto(history.pixels, other * ShaderLibrary.BANDS, row, row + ShaderLibrary.BANDS)
            }
        }
        historyFresh = false
        history.upload()
        historyRow = (historyRow + 1) % ShaderLibrary.HISTORY
    }

    /** Forgets the history, so a drawing shown again does not start from last time's music. */
    fun clearHistory() {
        history.pixels.fill(0xFF000000.toInt())
        history.upload()
        historyRow = 0
        historyFresh = true
    }

    /** Hands them all to a program under the names the shared header declares. */
    fun bindTo(program: ShaderProgram) {
        program.child("uBandsTex", bands.image)
        program.child("uScopeTex", scope.image)
        program.child("uPaletteTex", palette.image)
        program.child("uHistoryTex", history.image)
        program.uniform("uHistoryRow", historyRow.toFloat())
    }

    private inline fun writeStrip(into: PixelImage, width: Int, colourAt: (Float) -> Int) {
        for (pixel in 0 until width) {
            into.pixels[pixel] = colourAt(if (width == 1) 0f else pixel.toFloat() / (width - 1))
        }
        into.upload()
    }

    private fun red(value: Float): Int = 0xFF000000.toInt() or ((value.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16)

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
