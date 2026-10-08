package io.github.yuroyami.kiteplayer.session

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

/** A focus request refused only because the media service was not in the foreground yet (#454). */
class FocusRetryTest {

    private class Platform(vararg answers: FocusResult, val mayWait: Boolean = true, val foregroundComes: Boolean = true) {
        private val answers = ArrayDeque(answers.toList())
        var requests = 0
        var waits = 0

        fun ask(): FocusResult = immediately {
            requestFocusOnceForeground(
                request = { requests++; answers.removeFirst() },
                mayWaitForForeground = { mayWait },
                awaitForeground = { waits++; foregroundComes },
            )
        }
    }

    private companion object {
        /** Runs [block], which never suspends here, without a coroutine library: the web has no runBlocking. */
        fun <T> immediately(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            return checkNotNull(outcome) { "the block suspended" }.getOrThrow()
        }
    }

    @Test
    fun aRefusalBeforeTheServiceIsInTheForegroundIsAskedAgainOnceItIs() {
        val platform = Platform(FocusResult.Failed, FocusResult.Granted)
        assertEquals(FocusResult.Granted, platform.ask(), "the second request, in the foreground, holds the sound")
        assertEquals(2, platform.requests)
        assertEquals(1, platform.waits)
    }

    @Test
    fun aSecondRefusalInTheForegroundIsARealOne() {
        val platform = Platform(FocusResult.Failed, FocusResult.Failed)
        assertEquals(FocusResult.Failed, platform.ask())
        assertEquals(2, platform.requests)
    }

    @Test
    fun aForegroundThatNeverComesLeavesTheRefusal() {
        val platform = Platform(FocusResult.Failed, foregroundComes = false)
        assertEquals(FocusResult.Failed, platform.ask())
        assertEquals(1, platform.requests, "nothing changed, so nothing is asked again")
        assertEquals(1, platform.waits)
    }

    @Test
    fun aRefusalThatCannotBeTheRaceIsNotWaitedFor() {
        val platform = Platform(FocusResult.Failed, mayWait = false)
        assertEquals(FocusResult.Failed, platform.ask())
        assertEquals(1, platform.requests)
        assertEquals(0, platform.waits, "a refusal in the foreground, or before Android 15, pauses at once")
    }

    @Test
    fun aGrantOrALaterAnswerIsNeverAskedAgain() {
        listOf(FocusResult.Granted, FocusResult.Delayed).forEach { answer ->
            val platform = Platform(answer)
            assertEquals(answer, platform.ask())
            assertEquals(1, platform.requests)
            assertEquals(0, platform.waits)
        }
    }
}
