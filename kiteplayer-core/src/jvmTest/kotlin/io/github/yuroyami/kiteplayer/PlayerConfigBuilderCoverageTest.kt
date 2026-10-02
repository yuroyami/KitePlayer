package io.github.yuroyami.kiteplayer

import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each config builder has a property for every field of its data class, so a field added to the
 * data class cannot be left out of the `PlayerConfig { }` block without this failing.
 */
class PlayerConfigBuilderCoverageTest {

    private fun fields(type: KClass<*>): Set<String> =
        type.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    @Test
    fun everyBuilderCoversItsDataClass() {
        val pairs = listOf(
            PlayerConfig::class to PlayerConfigBuilder::class,
            AudioConfig::class to AudioConfigBuilder::class,
            SubtitleConfig::class to SubtitleConfigBuilder::class,
            NetworkConfig::class to NetworkConfigBuilder::class,
            IoCachePolicy::class to IoCachePolicyBuilder::class,
            BufferPolicy::class to BufferPolicyBuilder::class,
            QueueConfig::class to QueueConfigBuilder::class,
        )
        // Two empty sets would also be equal, so first prove that the fields are really seen.
        assertTrue("hdrPolicy" in fields(PlayerConfig::class), "no fields read from PlayerConfig: ${fields(PlayerConfig::class)}")
        for ((data, builder) in pairs) {
            assertEquals(fields(data), fields(builder), "${builder.simpleName} against ${data.simpleName}")
        }
    }
}
