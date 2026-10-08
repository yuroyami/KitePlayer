@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.SegmentStore
import io.github.yuroyami.kiteplayer.SegmentStoreEntry
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

    @Test
    fun aProviderThatServesLocalFilesIsAskedOnlyForALocalFile() = runTest {
        val trace = mutableListOf<String>()
        val files = object : MediaIoResolverProvider {
            override val id: String = "files"
            override val servesLocalFiles: Boolean = true
            override fun create(): MediaIoResolver = MediaIoResolver { uri -> trace += "files:$uri"; null }
        }
        val registry = MediaIoProviderRegistry()
        registry.register(Provider("network", trace))
        registry.register(files)
        assertNull(registry.resolve("/media/a.ts", emptyMap()))
        assertEquals(listOf("network"), trace, "automatic resolution asked a provider of local files")
        trace.clear()
        assertNull(registry.resolveLocalFile("/media/a.ts"))
        assertEquals(listOf("files:/media/a.ts"), trace)
    }

    @Test
    fun aPlayersSegmentStoreGoesToAProviderThatKeepsSegmentsAndToNoOther() = runTest {
        val store = object : SegmentStore {
            override val namespace: String = ""
            override val privateToOneAccount: Boolean = false
            override val sizeBytes: Long = 0
            override fun open(name: String): SegmentStoreEntry = error("unused")
            override fun remove(name: String) = Unit
            override fun clear() = Unit
            override fun close() = Unit
        }
        val trace = mutableListOf<String>()
        val given = mutableListOf<SegmentStore>()
        val keeping = object : MediaIoResolverProvider {
            override val id: String = "b"
            override fun create(): MediaIoResolver = MediaIoResolver { trace += "b"; null }
            override fun createWith(store: SegmentStore): MediaIoResolver {
                given += store
                return MediaIoResolver { trace += "b with a store"; null }
            }
        }
        val registry = MediaIoProviderRegistry()
        registry.register(Provider("a", trace))
        registry.register(keeping)
        registry.resolve("https://host/media", emptyMap(), store)
        assertEquals(listOf("a", "b with a store"), trace)
        assertEquals(listOf<SegmentStore>(store), given)
        // A player with no store gets the provider's plain resolver.
        registry.resolve("https://host/media", emptyMap())
        assertEquals(listOf("a", "b with a store", "a", "b"), trace)
    }
}
