package io.github.yuroyami.kiteplayer

import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every call of [KitePlayer] that suspends has a `CompletableFuture` in [KitePlayerJava], so a
 * Java app can make it (#650). Six calls added after the facade had none, and nothing noticed.
 */
class JavaFacadeCoverageTest {

    @Test
    fun everySuspendingCallHasAFuture() {
        val suspending = KitePlayer::class.java.methods
            .filter { method -> method.parameterTypes.lastOrNull() == Continuation::class.java }
            // A value class in the signature adds a suffix to the JVM name.
            .map { it.name.substringBefore('-') }
            .toSortedSet()
        val futures = KitePlayerJava::class.java.methods.map { it.name }.toSet()
        val missing = suspending.filter { name ->
            "${name}Async" !in futures && "${name.removeSuffix("AndAwait")}Async" !in futures
        }
        assertTrue(suspending.size > 30, "the reflection found only $suspending")
        assertEquals(emptyList(), missing, "suspending calls with no Async in KitePlayerJava")
    }

    @Test
    fun aMarkerIsMadeInMilliseconds() {
        assertEquals(Marker(1_500.milliseconds, "intro"), KitePlayerJava.marker(1_500, "intro"))
    }
}
