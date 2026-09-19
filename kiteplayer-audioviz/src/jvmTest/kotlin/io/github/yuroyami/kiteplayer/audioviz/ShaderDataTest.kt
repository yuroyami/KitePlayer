package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderData
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderInputs
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaderDataTest {
    init { useSkiaGraphics() }

    @Test
    fun waveformTextureFiltersBeforeReducingItsSampleCount() {
        val data = ShaderData()
        data.writeScope(FloatArray(512) { if (it % 2 == 0) 1f else -1f })
        val program = ShaderProgram(ShaderLibrary.HEADER + "half4 main(float2 p) { return uScopeTex.eval(p); }")
        data.bindTo(program)
        val image = ImageBitmap(128, 1)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 1f)) {
            drawRect(checkNotNull(program.brush()))
        }
        val pixels = image.toPixelMap()
        for (x in 0 until 128) assertEquals(0.5f, pixels[x, 0].red, 0.01f)
    }

    @Test
    fun shaderWaveformsShareThePowerGainAndKeepQuietSamplesQuiet() {
        val inputs = ShaderInputs()
        val program = ShaderProgram(ShaderLibrary.HEADER + "half4 main(float2 p) { return uScopeTex.eval(p); }")
        fun read(amplitude: Float, time: Float): Float {
            val driver = EnergyDriver(0f, 0f, 0f)
            val drivers = EnergyDrivers(driver, driver, driver, driver, emptyArray(), 0.25, 4.0, false, false, 0)
            val frame = SpectrumFrame(0L, FloatArray(40), FloatArray(40), FloatArray(256) { amplitude },
                0f, 0f, 0f, 0f, 0f, 0f, drivers = drivers)
            val state = VizRenderState(frame, time, 1f / 60, VizPalette.Prism)
            inputs.update(state)
            inputs.publish(program, state, 128f, 1f)
            val image = ImageBitmap(128, 1)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(128f, 1f)) {
                drawRect(checkNotNull(program.brush()))
            }
            return image.toPixelMap()[64, 0].red
        }
        assertEquals(0.7f, read(0.2f, 1f), 0.006f)
        assertEquals(0.55f, read(0.05f, 2f), 0.006f)
    }

    @Test
    fun batchedTexturesPreserveRampsAndHistoryAcrossUploads() {
        val data = ShaderData()
        data.writeBands(floatArrayOf(0f, 1f))
        data.writeScope(floatArrayOf(-1f, 1f))
        data.writePalette(VizPalette.Prism)
        data.writeHistory(floatArrayOf(0.25f))
        val program = ShaderProgram(ShaderLibrary.HEADER + """
            half4 main(float2 p) {
                if (p.y < 1.0) return uBandsTex.eval(float2(p.x, 0.5));
                if (p.y < 2.0) return uScopeTex.eval(float2(p.x * 2.0 - 0.5, 0.5));
                return uHistoryTex.eval(float2(p.x, p.y - 2.0));
            }
        """)
        fun pixels() = ImageBitmap(64, 4).also { image ->
            data.bindTo(program)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(64f, 4f)) {
                drawRect(checkNotNull(program.brush()))
            }
        }.toPixelMap()

        var result = pixels()
        assertEquals(0f, result[0, 0].red, 0.01f)
        assertEquals(1f, result[63, 0].red, 0.01f)
        assertEquals(0f, result[0, 1].red, 0.01f)
        assertEquals(1f, result[63, 1].red, 0.02f)
        assertEquals(0.25f, result[20, 2].red, 0.01f)
        assertEquals(0.25f, result[20, 3].red, 0.01f)

        data.writeBands(floatArrayOf(0.75f))
        data.writeHistory(floatArrayOf(0.9f))
        result = pixels()
        assertEquals(0.75f, result[0, 0].red, 0.01f)
        assertEquals(0.25f, result[20, 2].red, 0.01f)
        assertEquals(0.9f, result[20, 3].red, 0.01f)

        data.clearHistory()
        data.writeHistory(floatArrayOf(0.6f))
        result = pixels()
        assertEquals(0.6f, result[20, 2].red, 0.01f)
        assertEquals(0.6f, result[20, 3].red, 0.01f)
    }

    @Test
    fun visualShaderCyclesMoveWhileMusicalMeterAndBeatRemainUnknown() {
        val inputs = ShaderInputs()
        val program = ShaderProgram(ShaderLibrary.HEADER + """
            half4 main(float2 p) { return half4(uCyclePhase, uSlowCyclePhase, uBeatUsable, 1); }
        """)
        var state = VizRenderState(SpectrumFrame.silent(40, 256), 0f, 1f / 60f, VizPalette.Prism)
        repeat(60) { index ->
            state = VizRenderState(state.frame, (index + 1) / 60f, 1f / 60f, VizPalette.Prism)
            inputs.update(state)
        }
        fun pixel(shader: ShaderProgram) = ImageBitmap(1, 1).also { image ->
            inputs.publish(shader, state, 1f, 1f)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1f, 1f)) {
                drawRect(checkNotNull(shader.brush()))
            }
        }.toPixelMap()[0, 0]
        val moving = pixel(program)
        assertEquals(1f / 4.2f, moving.red, 0.006f)
        assertEquals(1f / 16.8f, moving.green, 0.006f)
        assertEquals(0f, moving.blue)
        val legacy = ShaderProgram(ShaderLibrary.HEADER + """
            half4 main(float2 p) { return half4(uBarPhase, uPhrasePhase, uBeatUsable, 1); }
        """)
        assertEquals(0f, pixel(legacy).red)
        assertEquals(0f, pixel(legacy).green)
        inputs.reset()
        assertTrue(pixel(program).red < 0.01f, "reset clears visual phase without a false boundary")
    }
}
