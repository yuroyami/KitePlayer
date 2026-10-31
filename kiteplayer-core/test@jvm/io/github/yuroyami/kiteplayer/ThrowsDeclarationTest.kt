package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calls that fail by their own contract declare it, because Kotlin/Native ends a Swift app on any
 * exception a function does not declare (#652). `@Throws` is the same annotation on the JVM, where
 * it becomes the method's throws clause, which this reads.
 */
class ThrowsDeclarationTest {

    private fun declared(type: Class<*>, name: String): List<Class<*>> {
        val method = type.methods.firstOrNull { it.name == name || it.name.startsWith("$name-") }
            ?: error("${type.simpleName} has no method $name")
        return method.exceptionTypes.toList()
    }

    @Test
    fun aMementoThatCannotBeReadDeclaresItsRefusal() {
        val thrown = declared(PlayerMemento.Companion::class.java, "fromProperties")
        assertTrue(IllegalArgumentException::class.java in thrown, "fromProperties declares $thrown")
    }

    @Test
    fun aThumbnailAskedOfAClosedPlayerDeclaresItsRefusal() {
        val thrown = declared(KitePlayer::class.java, "thumbnailAt")
        assertTrue(thrown.any { it.isAssignableFrom(IllegalStateException::class.java) }, "thumbnailAt declares $thrown")
    }
}
