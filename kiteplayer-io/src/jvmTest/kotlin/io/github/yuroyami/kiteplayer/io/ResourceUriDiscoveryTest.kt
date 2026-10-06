@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import java.util.ServiceLoader
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A Compose Multiplatform resource's `jar:` address plays as it is (#457): the service lookup the
 * player's resolver discovery makes, for every item with no reader of its own, finds this module's
 * resolver, and it reads the entry.
 */
class ResourceUriDiscoveryTest {

    private val resolver = ServiceLoader.load(MediaIoResolverProvider::class.java, MediaIoResolverProvider::class.java.classLoader)
        .single { it.id == RESOURCE_RESOLVER_ID }
        .create()

    @Test
    fun aJarAddressResolvesWithNoDoorAtAll() = runTest {
        val bytes = ByteArray(10_000) { (it * 7).toByte() }
        val jar = File.createTempFile("kiteplayer-app", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("composeResources/files/clip.bin"))
            out.write(bytes)
            out.closeEntry()
        }
        val reader = assertNotNull(resolver.resolve("jar:${jar.toURI()}!/composeResources/files/clip.bin"))
        reader.use {
            val out = ByteArray(bytes.size)
            var filled = 0
            while (filled < out.size) {
                val count = it.read(out, filled, out.size - filled)
                if (count < 0) break
                filled += count
            }
            assertContentEquals(bytes, out)
        }
        assertNull(resolver.resolve("file:/nowhere/clip.bin"), "a plain file address was taken")
    }
}
