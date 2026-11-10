@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

@JsFun("(a) => a.length")
private external fun floatCount(array: JsAny): Int

@JsFun("(a, i) => a[i]")
private external fun floatAt(array: JsAny, index: Int): Float

@JsFun("(a) => a.buffer.byteLength")
private external fun bufferBytes(array: JsAny): Int

/**
 * A block of sound crosses into JavaScript as one string and comes back as the same floats (#909).
 * It crossed one sample at a time, about 96,000 calls a second for 48 kHz stereo.
 */
class PackedSamplesTest {

    @Test
    fun everyFloatComesBackWithTheSameBits() {
        val samples = floatArrayOf(
            0f, -0f, 1f, -1f, 0.5f, -0.999969f, 1.0e-40f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.1f, 3.0e-3f, -2.5e-7f,
        )
        val array = samplesOf(packSamples(samples, samples.size))
        assertEquals(samples.size, floatCount(array))
        assertEquals(samples.size * 4, bufferBytes(array), "the buffer holds only the block, so it can be transferred whole")
        for (i in samples.indices) {
            assertEquals(samples[i].toRawBits(), floatAt(array, i).toRawBits(), "sample $i, ${samples[i]}")
        }
    }

    /** Every byte value, the high half too, which a text encoding on the way would change. */
    @Test
    fun aBlockWhoseBytesTakeEveryValueSurvives() {
        val samples = FloatArray(512) { i -> Float.fromBits((i * 0x01010101) or 0x3f000000 and 0x7f7fffff) }
        val array = samplesOf(packSamples(samples, samples.size))
        for (i in samples.indices) assertEquals(samples[i].toRawBits(), floatAt(array, i).toRawBits(), "sample $i")
    }

    @Test
    fun onlyTheCountAskedForIsPacked() {
        val samples = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        val array = samplesOf(packSamples(samples, 2))
        assertEquals(2, floatCount(array))
        assertEquals(0.5f, floatAt(array, 1))
    }
}
