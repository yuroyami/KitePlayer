package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Metal passes generated from the animation upscaler's weights (#421), as text. */
class Anime4kMslTest {

    @Test
    fun theGeneratedShadersCarryTheOriginalsNumbers() {
        for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
            network.layers.forEachIndexed { index, layer ->
                val name = Anime4kMsl.convolutionName(network, index)
                val msl = Anime4kMsl.convolution(name, layer)
                assertTrue("fragment float4 $name(" in msl)
                assertEquals(layer.terms.size, Regex("""float4x4\(""").findAll(msl).count())
                assertEquals(layer.taps.size, Regex("""\.sample\(""").findAll(msl).count(), "each tap is fetched once")
                assertEquals(layer.inputs.size, Regex("""texture2d<float>""").findAll(msl).count())
                layer.terms.forEach { term ->
                    val columns = Anime4kMsl.matrix(term)
                    assertTrue(columns in msl)
                    // The same sixteen numbers in the same order, only grouped into columns.
                    assertEquals(term.matrix, columns.replace("float4x4(", "").replace("float4(", "").replace(")", ""))
                }
                assertTrue("result += float4(${layer.bias});\n    return result;" in msl, "the bias is added last")
            }
        }
    }

    @Test
    fun everyPassOfANetworkIsInItsLibrarySource() {
        for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
            val source = Anime4kMsl.source(network)
            assertEquals(network.layers.size + 1, Regex("""fragment float4 kp_upscale_""").findAll(source).count())
            for (index in network.layers.indices) {
                assertTrue("fragment float4 ${Anime4kMsl.convolutionName(network, index)}(" in source)
            }
            assertTrue("fragment float4 ${Anime4kMsl.DEPTH_TO_SPACE_NAME}(" in source)
        }
    }

    /** The passes are drawn by the renderer's own vertex function, so both sources declare one struct. */
    @Test
    fun thePassesReadWhatTheRenderersVertexFunctionWrites() {
        assertTrue(Anime4kMsl.VERTEX_OUT in METAL_SHADER_SOURCE)
        assertTrue(Anime4kMsl.VERTEX_OUT in Anime4kMsl.source(Anime4kNetworks.small))
    }
}
