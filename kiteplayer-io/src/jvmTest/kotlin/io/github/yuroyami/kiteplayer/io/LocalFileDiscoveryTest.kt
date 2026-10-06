@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The provider that reads a local file through Kotlin for a file still being written (#430) is
 * found the way the player finds providers, and says it serves local files, which keeps it out of
 * automatic resolution.
 */
class LocalFileDiscoveryTest {
    private val file = File.createTempFile("kiteplayer-local", ".bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    @Test
    fun theLocalFileProviderIsFoundAndReadsTheFile() = runTest {
        val provider = ServiceLoader.load(MediaIoResolverProvider::class.java, MediaIoResolverProvider::class.java.classLoader)
            .single { it.id == LOCAL_FILE_RESOLVER_ID }
        assertTrue(provider.servesLocalFiles)
        val reader = assertNotNull(provider.create().resolve(file.path))
        reader.use {
            assertEquals(64L, it.size)
            val out = ByteArray(64)
            assertEquals(64, it.read(out, 0, 64))
            assertEquals(63, out[63].toInt())
        }
    }
}
