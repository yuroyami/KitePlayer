package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin/Native turns an exception into a Swift error only where the function declares it with
 * `@Throws`, and ends the process for any other (#916). The JVM writes the same declaration into
 * the class file, so this reads it back there for every constructor and builder that checks its
 * values, and fails when one loses it.
 */
class ThrowsDeclarationTest {
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
