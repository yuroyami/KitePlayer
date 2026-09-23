package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloseInOrderTest {

    @Test
    fun everyStepRunsWhenAnEarlierStepThrows() {
        val ran = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            closeInOrder(
                { ran += 1; error("first") },
                { ran += 2 },
                { ran += 3; error("third") },
            )
        }
        assertEquals(listOf(1, 2, 3), ran, "a failed step must not stop the ones after it")
        assertEquals("first", failure.message, "the first failure is the one thrown")
        assertEquals(listOf("third"), failure.suppressedExceptions.map { it.message })
    }
}
