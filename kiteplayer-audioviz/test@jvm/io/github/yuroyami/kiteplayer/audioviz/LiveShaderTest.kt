package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.LiveShader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writing a shader while it runs: a good edit takes over, a broken one leaves the last one alone. */
class LiveShaderTest {

    init { useSkiaGraphics() }

    @Test
    fun aBrokenEditKeepsTheLastWorkingProgram() {
        val live = LiveShader("Test", "half4 main(float2 p) { return half4(half3(band(0.5)), 1.0); }")
        assertNull(live.error, "a working program should compile")

        val message = live.replace("half4 main(float2 p) { return oops; }")
        assertNotNull(message, "a broken edit should come back with the compiler's message")
        assertEquals(message, live.error)
        println("compiler said: ${message.lineSequence().first()}")

        // Still draws, on the program from before the broken edit.
        val picture = RenderHarness.render(live, 80, 50, frames = 3, palette = VizPalette.Classic)
        var lit = 0
        for (y in 0 until picture.height) for (x in 0 until picture.width) {
            val pixel = picture.getRGB(x, y)
            if (((pixel shr 16) and 255) + ((pixel shr 8) and 255) + (pixel and 255) > 30) lit++
        }
        assertTrue(lit > 0, "the drawing should still put light down on the earlier program")

        assertNull(live.replace("half4 main(float2 p) { return half4(1.0); }"), "a fixed edit should compile")
        assertNull(live.error, "and clear the message")
    }
}
