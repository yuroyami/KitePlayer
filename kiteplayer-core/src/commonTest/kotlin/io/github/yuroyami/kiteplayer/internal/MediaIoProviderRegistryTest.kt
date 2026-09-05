@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MediaIoProviderRegistryTest {
    private class Provider(override val id: String, private val trace: MutableList<String>) : MediaIoResolverProvider {
        var creates = 0
        override fun create(): MediaIoResolver {
            creates++
            return MediaIoResolver { trace += id; null }
        }
    }

    @Test
    fun registrationIsLazyAndSelectionIsSortedRegardlessOfRegistrationOrder() = runTest {
        val trace = mutableListOf<String>()
        val z = Provider("z", trace)
        val a = Provider("a", trace)
        val registry = MediaIoProviderRegistry()
        registry.register(z)
        registry.register(a)
        assertEquals(0, z.creates + a.creates)
        assertNull(registry.resolve("file:///media", emptyMap()))
        assertEquals(listOf("a", "z"), trace)
        registry.resolve("file:///other", emptyMap())
        assertEquals(1, a.creates)
        assertEquals(1, z.creates)
    }

    @Test
    fun duplicateIdentifiersRefuseInsteadOfDependingOnInitializationOrder() {
        val registry = MediaIoProviderRegistry()
        val first = Provider("same", mutableListOf())
        registry.register(first)
        registry.register(first)
        assertFailsWith<IllegalArgumentException> { registry.register(Provider("same", mutableListOf())) }
        assertFailsWith<IllegalArgumentException> { registry.register(Provider(" ", mutableListOf())) }
    }

    @Test
    fun concurrentRegistrationAndResolutionCreateOneSharedStatelessResolver() = runTest {
        val registry = MediaIoProviderRegistry()
        val creates = atomic(0)
        val opens = atomic(0)
        val provider = object : MediaIoResolverProvider {
            override val id: String = "one"
            override fun create(): MediaIoResolver {
                creates.incrementAndGet()
                return MediaIoResolver { opens.incrementAndGet(); null }
            }
        }
        (1..64).map {
            async(Dispatchers.Default) {
                registry.register(provider)
                registry.resolve("https://host/media", emptyMap())
            }
        }.awaitAll()
        assertEquals(1, creates.value)
        assertEquals(64, opens.value)
    }

    @Test
    fun aProviderFailureDoesNotSilentlySelectAnotherTransport() = runTest {
        val registry = MediaIoProviderRegistry()
        registry.register(object : MediaIoResolverProvider {
            override val id: String = "a"
            override fun create(): MediaIoResolver = MediaIoResolver { error("authentication refused") }
        })
        val trace = mutableListOf<String>()
        registry.register(Provider("z", trace))
        assertFailsWith<IllegalStateException> { registry.resolve("https://host/media", emptyMap()) }
        assertEquals(emptyList(), trace)
    }
}
