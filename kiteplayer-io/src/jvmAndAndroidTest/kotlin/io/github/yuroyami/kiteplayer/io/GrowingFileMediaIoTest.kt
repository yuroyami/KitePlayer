package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file still being written reads on as it grows, and its reader says how large it is now
 * (#430), as FFmpeg's own file reader does. A window of a file keeps its size.
 */
class GrowingFileMediaIoTest {
    private val file = File.createTempFile("kiteplayer-growing", ".bin").apply { writeBytes(ByteArray(100) { 1 }) }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    @Test
    fun aFileThatGrowsReadsOnAndSaysHowLargeItIsNow() = runTest {
        MediaIo.ofFile(file).open().use { reader ->
            assertEquals(100, reader.read(ByteArray(200), 0, 200))
            assertEquals(-1, reader.read(ByteArray(200), 0, 200))
            file.appendBytes(ByteArray(50) { 2 })
            assertEquals(150L, reader.size, "the size stayed where it stood at the open")
            val more = ByteArray(200)
            assertEquals(50, reader.read(more, 0, 200), "the bytes written after the open were not read")
            assertEquals(2, more[0].toInt())
            reader.seek(120)
            assertEquals(30, reader.read(more, 0, 200))
        }
    }

    @Test
    fun aWindowKeepsItsSizeWhileTheFileGrows() = runTest {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        FileChannelMediaIo(channel, owner = channel, start = 10, length = 50).use { reader ->
            file.appendBytes(ByteArray(50))
            assertEquals(50L, reader.size)
            assertEquals(50, reader.read(ByteArray(200), 0, 200))
            assertEquals(-1, reader.read(ByteArray(200), 0, 200))
        }
    }
}
