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
        assertTrue(picture.width == 80, "the drawing should still render")

        assertNull(live.replace("half4 main(float2 p) { return half4(1.0); }"), "a fixed edit should compile")
        assertNull(live.error, "and clear the message")
    }
}
