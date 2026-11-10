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
        // An interface Java cannot implement: one that answers or takes a boxed Duration, as
        // ExternalClock answers, or one whose abstract method a value class such as Pts mangles,
        // as AudioTap's does (#970).
        val javaCannotImplement = { type: Class<*> ->
            type.isInterface && type.methods.any { m ->
                m.returnType == kotlin.time.Duration::class.java ||
                    m.parameterTypes.any { it == kotlin.time.Duration::class.java } ||
                    ('-' in m.name && java.lang.reflect.Modifier.isAbstract(m.modifiers))
            }
        }
        val throughInterface = members.filter { method -> method.parameterTypes.any(javaCannotImplement) }
        // Its Java form has a twin's name and takes no such interface itself.
        val javaForms = KitePlayerJava::class.java.methods.filter { form -> form.parameterTypes.none(javaCannotImplement) }.map { it.name }.toSet()
        val unserved = throughInterface.map { it.name.substringBefore('-') }.toSortedSet().filter { name ->
            name !in javaForms && "${name}Millis" !in javaForms && "${name}Async" !in javaForms
        }
        assertTrue("position" in mangled, "the reflection found only $mangled")
        assertTrue(throughInterface.any { it.name == "setExternalClock" }, "the reflection found only $throughInterface")
        assertTrue(throughInterface.any { it.name == "attachAudioTap" }, "the reflection found only $throughInterface")
        assertEquals(emptyList(), mangled.filterNot(::hasTwin), "calls in Durations with no Java form")
        assertEquals(emptyList(), unserved, "calls taking an interface Java cannot implement, with no Java form")
    }

    @Test
    fun aMarkerIsMadeInMilliseconds() {
        assertEquals(Marker(1_500.milliseconds, "intro"), KitePlayerJava.marker(1_500, "intro"))
    }
}
