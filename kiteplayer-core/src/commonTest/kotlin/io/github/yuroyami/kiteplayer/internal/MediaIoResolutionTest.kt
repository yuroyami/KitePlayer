package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NetworkConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MediaIoResolutionTest {
    private val reader = object : MediaIo {
        override val size: Long = 0
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
        override suspend fun seek(position: Long) = Unit
        override fun close() = Unit
    }

    @Test
    fun defaultSelectionPassesTheUriAndHeadersToAutomaticTransport() = runTest {
        val headers = mapOf("Authorization" to "Bearer item")
        val result = resolveMediaIo(MediaItem("https://host/media", headers), NetworkConfig()) { uri, sent ->
            assertEquals("https://host/media", uri)
            assertEquals(headers, sent)
            reader
        }
        assertSame(reader, result)
    }

    @Test
    fun itemReaderBypassesBothExplicitAndAutomaticResolvers() = runTest {
        val config = NetworkConfig(ioResolver = MediaIoResolver { error("explicit resolver called") })
        val result = resolveMediaIo(MediaItem("https://host/media", io = { reader }), config) { _, _ ->
            error("automatic resolver called")
        }
        assertSame(reader, result)
    }

    @Test
    fun explicitNullResultChoosesTheBackendAndNeverFallsThroughToAutomaticTransport() = runTest {
        val config = NetworkConfig(ioResolver = MediaIoResolver { null })
        assertNull(resolveMediaIo(MediaItem("https://host/media"), config) { _, _ ->
            error("explicit null was replaced by automatic transport")
        })
    }

    @Test
    fun explicitResolverReceivesHeadersEvenWhenAutomaticSelectionIsDisabled() = runTest {
        val headers = mapOf("Authorization" to "Bearer item")
        val resolver = object : MediaIoResolver {
            override suspend fun resolve(uri: String): MediaIo? = error("headers were discarded")
            override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo {
                assertEquals("Bearer item", headers["Authorization"])
                return reader
            }
        }
        val config = NetworkConfig(ioResolver = resolver, autoResolve = false)
        assertSame(reader, resolveMediaIo(MediaItem("https://host/media", headers), config) { _, _ ->
            error("automatic resolver called")
        })
    }

    @Test
    fun disabledAutomaticSelectionDoesNotDiscoverOrConstructAProvider() = runTest {
        assertNull(resolveMediaIo(MediaItem("https://host/media"), NetworkConfig(autoResolve = false)) { _, _ ->
            error("disabled automatic transport was consulted")
        })
    }

    @Test
    fun existingSingleArgumentResolverStillWorksWithItemHeaders() = runTest {
        val resolver = MediaIoResolver { uri ->
            assertEquals("custom://media", uri)
            reader
        }
        assertSame(reader, resolver.resolve("custom://media", mapOf("X-Item" to "present")))
    }

    @Test
    fun noInstalledProviderPreservesBackendUriHandling() = runTest {
        // Core's test runtime has no network dependency and no manually registered provider.
        assertNull(resolveMediaIo(MediaItem("https://host/media"), NetworkConfig()))
    }
}
