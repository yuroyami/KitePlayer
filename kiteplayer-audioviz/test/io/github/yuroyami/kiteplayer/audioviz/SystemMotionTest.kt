package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android's Remove animations, read as reduced motion (#964). */
class SystemMotionTest {
    @Test
    fun onlyAZeroAnimatorScaleMeansAnimationsWereRemoved() {
        assertTrue(animationsRemoved(0f))
        assertFalse(animationsRemoved(1f))
        assertFalse(animationsRemoved(0.5f), "a shorter animation is not a request for none")
    }
}
