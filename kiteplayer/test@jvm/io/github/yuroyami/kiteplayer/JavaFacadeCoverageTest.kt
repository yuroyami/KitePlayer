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

    /**
     * Every member of [KitePlayer] whose JVM name a value class such as `Duration` mangles has a
     * form in [KitePlayerJava] under its own name, with `Millis` or with `Async`, and every member
     * that takes an interface speaking in `Duration` has a `Millis` form that takes one Java can
     * implement (#918). `setExternalClock` was not mangled, so only the second rule finds it.
     */
    @Test
    fun everyCallInDurationsHasAJavaForm() {
        val facade = KitePlayerJava::class.java.methods.map { it.name }.toSet()
        fun hasTwin(name: String) = name in facade || "${name}Millis" in facade || "${name}Async" in facade
        val members = KitePlayer::class.java.methods.filter { !it.isAnnotationPresent(Deprecated::class.java) && !it.name.endsWith("\$default") }
        val mangled = members.filter { '-' in it.name }.map { it.name.substringBefore('-') }.toSortedSet()
        // A Duration Java must make or read appears as the boxed class: a nullable one, as
        // ExternalClock answers. Other value classes in such an interface, such as Pts, are not
        // what this test covers.
        val speaksDuration = { type: Class<*> ->
            type.isInterface && type.methods.any { m ->
                m.returnType == kotlin.time.Duration::class.java || m.parameterTypes.any { it == kotlin.time.Duration::class.java }
            }
        }
        val throughInterface = members.filter { method -> method.parameterTypes.any(speaksDuration) }.map { it.name }.toSortedSet()
        assertTrue("position" in mangled, "the reflection found only $mangled")
        assertTrue("setExternalClock" in throughInterface, "the reflection found only $throughInterface")
        assertEquals(emptyList(), mangled.filterNot(::hasTwin), "calls in Durations with no Java form")
        assertEquals(emptyList(), throughInterface.filter { "${it}Millis" !in facade }, "calls taking a Duration interface with no Millis form")
    }

    @Test
    fun aMarkerIsMadeInMilliseconds() {
        assertEquals(Marker(1_500.milliseconds, "intro"), KitePlayerJava.marker(1_500, "intro"))
    }
}
