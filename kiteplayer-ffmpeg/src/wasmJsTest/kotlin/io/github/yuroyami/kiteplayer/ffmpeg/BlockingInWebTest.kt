package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web backend opens a related address only when the open finishes at once (#546). */
class BlockingInWebTest {

    @Test
    fun aBlockThatFinishesAtOnceAnswers() {
        val lifetime = Job()
        assertEquals(3, blockingIn(lifetime) { 3 })
        assertTrue(lifetime.isActive)
        assertEquals(0, lifetime.children.count(), "the call leaves nothing behind in the lifetime")
    }

    @Test
    fun aBlockThatSuspendsIsRefusedAndCancelled() {
        val lifetime = Job()
        var cancelled = false
        var wentOn = false
        assertFailsWith<UnsupportedOperationException> {
            blockingIn(lifetime) {
                suspendCancellableCoroutine<Unit> { waiting -> waiting.invokeOnCancellation { cancelled = true } }
                wentOn = true
            }
        }
        assertTrue(cancelled, "the wait is cancelled before the refusal")
        assertFalse(wentOn, "nothing of the block runs after its caller has gone")
        assertTrue(lifetime.isActive, "one refused open does not end the source")
        assertEquals(0, lifetime.children.count())
    }

    @Test
    fun aFailureInTheBlockIsTheCallersAndAnInterruptedSourceOpensNothing() {
        val lifetime = Job()
        assertFailsWith<IllegalStateException> { blockingIn<Int>(lifetime) { error("the reader refused") } }
        assertEquals(0, lifetime.children.count())
        lifetime.cancel()
        var ran = false
        assertFailsWith<CancellationException> { blockingIn(lifetime) { ran = true } }
        assertFalse(ran)
    }
}
