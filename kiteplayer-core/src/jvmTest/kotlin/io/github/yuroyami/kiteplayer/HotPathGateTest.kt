package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The harness itself: a block slower than its bound must fail the gate. */
class HotPathGateTest {

    @Test
    fun `the gate fails a block that is slower than its bound`() {
        val failure = assertFailsWith<AssertionError> {
            hotPathGate("a deliberately slow block", boundMillis = 5.0) { Thread.sleep(20) }
        }
        assertTrue("over its bound" in failure.message.orEmpty(), failure.message)
    }
}
