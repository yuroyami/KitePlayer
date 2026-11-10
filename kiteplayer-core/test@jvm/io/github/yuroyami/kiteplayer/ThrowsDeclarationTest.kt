package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calls that fail by their own contract declare it, because Kotlin/Native ends a Swift app on any
 * exception a function does not declare (#652, #916). `@Throws` is the same annotation on the JVM,
 * where it becomes the method's or constructor's throws clause, which this reads.
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
    private val validating = listOf(
        PlayerConfig::class.java, NetworkRecovery::class.java, IoCachePolicy::class.java,
        BufferPolicy::class.java, QueueConfig::class.java, AudioConfig::class.java,
        EqualizerSettings::class.java, SubtitleConfig::class.java, MediaItem::class.java,
        MediaClip::class.java, FileGrowth::class.java, SubtitleSource::class.java,
        VideoAdjustments::class.java,
    )

    @Test
    fun everyValidatingConstructorDeclaresItsRefusal() {
        for (type in validating) {
            // A Duration parameter makes the callable constructors synthetic ones, so those count too.
            val constructors = type.declaredConstructors.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            assertTrue(constructors.isNotEmpty(), "${type.simpleName} has no public constructor")
            for (constructor in constructors) {
                assertTrue(
                    IllegalArgumentException::class.java in constructor.exceptionTypes,
                    "${type.simpleName}(${constructor.parameterTypes.joinToString { it.simpleName }}) does not declare IllegalArgumentException",
                )
            }
        }
    }

    @Test
    fun everyBuilderDeclaresItsRefusal() {
        val builders = listOf(
            MediaItemBuilder::class.java, PlayerConfigBuilder::class.java, AudioConfigBuilder::class.java,
            SubtitleConfigBuilder::class.java, NetworkConfigBuilder::class.java, IoCachePolicyBuilder::class.java,
            BufferPolicyBuilder::class.java, QueueConfigBuilder::class.java,
        )
        for (builder in builders) {
            val build = builder.getMethod("build")
            assertTrue(IllegalArgumentException::class.java in build.exceptionTypes, "${builder.simpleName}.build() does not declare IllegalArgumentException")
        }
    }

    @Test
    fun aRefusedValueStillThrowsTheDeclaredType() {
        val refused = runCatching { VideoAdjustments(gamma = 5f) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException, "VideoAdjustments(gamma = 5) threw $refused")
    }
}
