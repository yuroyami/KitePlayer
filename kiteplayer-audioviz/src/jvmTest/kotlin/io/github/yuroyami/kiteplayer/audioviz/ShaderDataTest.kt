package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderData
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class ShaderDataTest {
    init { useSkiaGraphics() }

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
}
