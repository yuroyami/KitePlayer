package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Metal renderer publishes `RendererEvent.ToneMapEngaged` from [willToneMap], and the shader
 * decides from [packToneUniforms]. Two answers to one question is how a renderer starts claiming
 * a tone map it did not do, so this pins them to each other.
 */
class MetalToneMapRuleTest {

    private fun uniformsFor(transfer: ColorTransfer): FloatArray =
        packToneUniforms(ColorSpaceInfo(transfer = transfer, primaries = ColorPrimaries.Bt2020))

    @Test
    fun theRuleAndTheShaderAgreeOnEveryTransfer() {
        for (transfer in ColorTransfer.entries) {
            val color = ColorSpaceInfo(transfer = transfer, primaries = ColorPrimaries.Bt2020)
            val shaderEnabled = uniformsFor(transfer) !== DISABLED_TONE_UNIFORMS
            assertEquals(
                shaderEnabled,
                color.willToneMap(),
                "$transfer: the shader says $shaderEnabled and the event rule disagrees",
            )
        }
    }

    @Test
    fun onlyPqAndHlgToneMap() {
        assertTrue(ColorSpaceInfo(transfer = ColorTransfer.Pq).willToneMap())
        assertTrue(ColorSpaceInfo(transfer = ColorTransfer.Hlg).willToneMap())
        assertFalse(ColorSpaceInfo(transfer = ColorTransfer.Bt709).willToneMap())
        assertFalse(ColorSpaceInfo().willToneMap(), "an undeclared transfer is not HDR")
    }
}
