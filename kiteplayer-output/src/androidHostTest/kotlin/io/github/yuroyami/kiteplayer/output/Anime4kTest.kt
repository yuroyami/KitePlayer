package io.github.yuroyami.kiteplayer.output

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The animation upscaler's networks on the host (#67): the weights, the shaders generated from them
 * and the CPU reference the GL passes are tested against on a device.
 */
class Anime4kTest {

    private val fixture = Anime4kFixture()

    /**
     * Pixels of the doubled fixture as Anime4K's ORIGINAL mpv shaders drew them, run in WebGL 2 with
     * float textures through mpv's hook macros: x, y, then red, green and blue. They tie the
     * reference, and through it the device test, to the original rather than to this port's reading
     * of it. In that run the shaders generated here wrote exactly the same floats as the original.
     */
    private val originalSmall = listOf(
        floatArrayOf(0f, 0f, 0.5494486f, 0.7494486f, 0.9494486f),
        floatArrayOf(127f, 95f, 0.4003475f, 0.6983867f, 0.3023083f),
        floatArrayOf(66f, 40f, 0.1160617f, 0.1474342f, 0.2160617f),
        floatArrayOf(95f, 45f, -0.02213778f, 0.03055832f, 0.1234505f),
        floatArrayOf(79f, 49f, 0.2735843f, 0.3772608f, 0.515251f),
        floatArrayOf(20f, 68f, 0.4240448f, 0.6983095f, 0.4446331f),
        floatArrayOf(44f, 40f, 0.9807975f, 0.8513857f, 0.6984445f),
        floatArrayOf(127f, 0f, 0.5491434f, 0.7491434f, 0.9491434f),
        floatArrayOf(62f, 41f, 1.005848f, 0.8764363f, 0.7234951f),
        floatArrayOf(96f, 44f, 0.5174267f, 0.6544366f, 0.8076228f),
    )
    private val originalMedium = listOf(
        floatArrayOf(0f, 0f, 0.5490373f, 0.7490373f, 0.9490373f),
        floatArrayOf(127f, 95f, 0.399881f, 0.6979202f, 0.3018417f),
        floatArrayOf(66f, 40f, 0.1249048f, 0.1562774f, 0.2249048f),
        floatArrayOf(95f, 45f, -0.06308495f, -0.01038885f, 0.08250329f),
        floatArrayOf(79f, 49f, 0.2820633f, 0.3857398f, 0.52373f),
        floatArrayOf(20f, 68f, 0.4057129f, 0.6799775f, 0.4263011f),
        floatArrayOf(44f, 40f, 0.9806416f, 0.8512298f, 0.6982886f),
        floatArrayOf(127f, 0f, 0.549354f, 0.7493539f, 0.949354f),
        floatArrayOf(62f, 41f, 0.988915f, 0.8595032f, 0.706562f),
        floatArrayOf(96f, 44f, 0.5562693f, 0.6932791f, 0.8464654f),
    )

    @Test
    fun theReferenceDrawsWhatTheOriginalShadersDraw() {
        for ((network, pins) in listOf(Anime4kNetworks.small to originalSmall, Anime4kNetworks.medium to originalMedium)) {
            val doubled = Anime4kReference.upscale(network, fixture.picture, fixture.width, fixture.height)
            for (pin in pins) {
                val at = (pin[1].toInt() * fixture.width * 2 + pin[0].toInt()) * 4
                for (c in 0 until 3) {
                    val got = doubled[at + c]
                    assertTrue(
                        abs(got - pin[2 + c]) < 1e-5f,
                        "${network.name} at (${pin[0].toInt()}, ${pin[1].toInt()}) channel $c: ${pin[2 + c]} in the original, $got here",
                    )
                }
                assertEquals(1f, doubled[at + 3])
            }
        }
    }

    /**
     * What the networks are for. The fixture is a drawing halved, so a good upscaler gets close to
     * the drawing again. Measured in mean 8-bit levels: bilinear misses by 5.5, the small network by
     * 3.1 and the medium one by 2.8. A pass that runs and adds nothing would read like bilinear.
     */
    @Test
    fun bothNetworksDrawAHalvedDrawingBackCloserThanBilinear() {
        val bilinear = Anime4kNetwork(
            "bilinear",
            listOf(Anime4kLayer(listOf(Anime4kTerm(Anime4kTerm.SOURCE, Anime4kPart.Raw, 0, 0, zeros(16))), zeros(4))),
        )
        val plain = levelsFromTheDrawing(bilinear)
        val small = levelsFromTheDrawing(Anime4kNetworks.small)
        val medium = levelsFromTheDrawing(Anime4kNetworks.medium)
        assertTrue(small < plain * 0.7, "the small network misses by $small levels and bilinear by $plain")
        assertTrue(medium < small, "the medium network misses by $medium levels and the small one by $small")
    }

    @Test
    fun theNetworksHaveTheOriginalsShape() {
        assertEquals(4, Anime4kNetworks.small.layers.size)
        assertEquals(8, Anime4kNetworks.medium.layers.size)
        assertEquals(63, Anime4kNetworks.small.layers.sumOf { it.terms.size })
        assertEquals(131, Anime4kNetworks.medium.layers.sumOf { it.terms.size })
        assertEquals(List(7) { it }, Anime4kNetworks.medium.layers.last().inputs, "the last medium layer merges all seven")
    }

    /** A layer's texture can be reused once its last reader has run, and the last one feeds the doubling. */
    @Test
    fun eachLayerIsReadUntilItsLastReader() {
        assertContentEquals(intArrayOf(1, 2, 3, 4), Anime4kNetworks.small.lastReaders)
        assertContentEquals(intArrayOf(7, 7, 7, 7, 7, 7, 7, 8), Anime4kNetworks.medium.lastReaders)
    }

    @Test
    fun theGeneratedShadersCarryTheOriginalsNumbers() {
        for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
            for (layer in network.layers) {
                val glsl = Anime4kGlsl.convolution(layer)
                assertEquals(layer.terms.size, Regex("""mat4\(""").findAll(glsl).count())
                assertEquals(layer.taps.size, Regex("""texture2D\(""").findAll(glsl).count(), "each tap is fetched once")
                assertEquals(layer.inputs.size, Regex("""uniform sampler2D""").findAll(glsl).count())
                layer.terms.forEach { term -> assertTrue("mat4(${term.matrix})" in glsl) }
                assertTrue("result += vec4(${layer.bias});\n    gl_FragColor = result;" in glsl, "the bias is added last")
            }
        }
    }

    /** The original's own rule: more than 1.2 times on both axes, never on one alone. */
    @Test
    fun theNetworksRunOnlyPastTheOriginalsRatio() {
        assertFalse(Anime4kNetwork.runsAt(1280, 720, 1536, 864), "exactly 1.2 times")
        assertTrue(Anime4kNetwork.runsAt(1280, 720, 1537, 865))
        assertTrue(Anime4kNetwork.runsAt(1280, 720, 2560, 1440))
        assertFalse(Anime4kNetwork.runsAt(1280, 720, 2560, 800), "only the width grows enough")
        assertFalse(Anime4kNetwork.runsAt(1920, 1080, 1920, 1080), "drawn at its own size")
        assertFalse(Anime4kNetwork.runsAt(0, 720, 2560, 1440))
    }

    private fun levelsFromTheDrawing(network: Anime4kNetwork): Double {
        val doubled = Anime4kReference.upscale(network, fixture.picture, fixture.width, fixture.height)
        var sum = 0.0
        var count = 0
        for (i in doubled.indices) {
            if (i % 4 == 3) continue
            sum += abs(doubled[i].coerceIn(0f, 1f) - fixture.drawing[i])
            count++
        }
        return sum / count * 255
    }

    private fun zeros(count: Int): String = List(count) { "0.0" }.joinToString(", ")
}
