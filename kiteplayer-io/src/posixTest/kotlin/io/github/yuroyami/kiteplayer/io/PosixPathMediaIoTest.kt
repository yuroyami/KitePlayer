// size_t differs in width between these targets, and every use below converts it.
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.posix.F_GETFD
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fcntl
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.strerror
import platform.posix.unlink
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Files under the temporary directory. [deleteAll] removes every path this handed out. */
internal class TempFiles {
    private val paths = ArrayList<String>()

    /** A new path with no file behind it yet. */
    fun path(): String {
        val directory = getenv("TMPDIR")?.toKString()?.trimEnd('/')?.ifEmpty { null } ?: "/tmp"
        return "$directory/kiteplayer-io-${getpid()}-${Random.nextLong().toULong()}.bin".also { paths += it }
    }

    /** A new file that holds [bytes]. */
    fun create(bytes: ByteArray): String {
        val path = path()
        val file = fopen(path, "wb") ?: error("Cannot create $path: ${strerror(errno)?.toKString()}")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
                check(written.convert<Long>() == bytes.size.toLong()) { "Short write to $path" }
            }
        } finally {
            fclose(file)
        }
        return path
    }

    fun deleteAll() {
        paths.forEach { unlink(it) }
        paths.clear()
    }
}

class PosixPathMediaIoTest : MediaIoContractTest() {
    private val files = TempFiles()

    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofPath(files.create(bytes))

    @AfterTest
    fun deleteFiles() = files.deleteAll()

    @Test
    fun aMissingFileFailsAtOpenAndNamesThePath() = runTest {
        val path = files.path()
        val failure = assertFailsWith<MediaIoException> { MediaIo.ofPath(path).open() }
        assertTrue(path in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aDirectoryIsRefusedAtOpen() = runTest {
        val failure = assertFailsWith<MediaIoException> { MediaIo.ofPath("/").open() }
        assertTrue("Not a regular file" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun closeReleasesTheDescriptor() = runTest {
        val reader = MediaIo.ofPath(files.create(pattern)).open() as PosixFileMediaIo
        assertNotEquals(-1, fcntl(reader.descriptor, F_GETFD))
        reader.close()
        assertEquals(-1, fcntl(reader.descriptor, F_GETFD))
    }
}
