package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The address a Compose Multiplatform resource has in a packaged desktop app (#457): an entry of
 * the app's jar, stored compressed, read through [MediaIo.ofResourceUri] and held to every reader's
 * contract, seeks included. Any other address is left to play as it is.
 */
class ResourceUriJvmTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory {
        val jar = File.createTempFile("kiteplayer-app", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("composeResources/app.generated.resources/files/clip.bin"))
            out.write(bytes)
            out.closeEntry()
        }
        return checkNotNull(MediaIo.ofResourceUri("jar:${jar.toURI()}!/composeResources/app.generated.resources/files/clip.bin"))
    }

    @Test
    fun anAddressThatIsNoJarEntryPlaysAsItIs() {
        assertNull(MediaIo.ofResourceUri("file:/home/me/app/composeResources/files/clip.mp4"))
        assertNull(MediaIo.ofResourceUri("https://cdn.test/clip.mp4"))
    }
}
