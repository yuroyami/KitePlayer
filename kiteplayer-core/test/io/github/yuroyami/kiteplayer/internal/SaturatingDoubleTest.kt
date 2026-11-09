package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The live delay bound holds an infinite total buffer at its largest value rather than wrapping (#808). */
class SaturatingDoubleTest {
    @Test
    fun anInfiniteTotalDurationDoublesToTheLargestValue() {
        assertEquals(Long.MAX_VALUE, saturatingDouble(Duration.INFINITE.inWholeMicroseconds))
        assertEquals(Long.MAX_VALUE, saturatingDouble(Long.MAX_VALUE / 2 + 1))
        assertEquals(Long.MAX_VALUE - 1, saturatingDouble(Long.MAX_VALUE / 2))
        assertEquals(60_000_000L, saturatingDouble(30_000_000L))
    }
}
