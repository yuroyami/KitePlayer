package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/** A decoder's NaN or infinity reaches the engine as silence, never as itself (#716). */
class FiniteSampleTest {

    @Test
    fun aNonfiniteSampleIsSilence() {
        assertEquals(0f, finiteSample(Float.NaN))
        assertEquals(0f, finiteSample(Float.POSITIVE_INFINITY))
        assertEquals(0f, finiteSample(Float.NEGATIVE_INFINITY))
        assertEquals(0.5f, finiteSample(0.5f))
        assertEquals(-1f, finiteSample(-1f))
        // Past full scale is still a real sample; the ring's limiter is the one that bounds it.
        assertEquals(1.5f, finiteSample(1.5f))
    }
}
