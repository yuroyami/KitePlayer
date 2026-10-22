package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun tempFileOf(bytes: ByteArray): File =
    File.createTempFile("kiteplayer-io", ".bin").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

class FileMediaIoTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofFile(tempFileOf(bytes))

    @Test
    fun aMissingFileFailsAtOpen() = runTest {
        val factory = MediaIo.ofFile(File("/nonexistent/kiteplayer-io/missing.bin"))
        assertFailsWith<java.io.IOException> { factory.open() }
    }
}

class PathMediaIoTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofPath(tempFileOf(bytes).toPath())
}

class ChannelMediaIoTest : MediaIoContractTest() {
    private val channels = mutableListOf<FileChannel>()

    override fun factory(bytes: ByteArray): MediaIoFactory {
        val channel = FileChannel.open(tempFileOf(bytes).toPath(), StandardOpenOption.READ)
        channels += channel
        return MediaIo.ofChannel(channel)
    }

    @AfterTest
    fun closeTheCallersChannels() = channels.forEach { it.close() }

    @Test
    fun theCallersChannelKeepsItsPositionAndStaysOpen() = runTest {
        val channel = FileChannel.open(tempFileOf(pattern).toPath(), StandardOpenOption.READ)
        channels += channel
        channel.position(5)
        val factory = MediaIo.ofChannel(channel)
        factory.open().use { reader ->
            reader.seek(200_000)
            assertContentEquals(pattern.copyOfRange(200_000, 200_004), reader.readExactly(4))
        }
        factory.open().use { reader -> reader.readAll() }
        assertTrue(channel.isOpen)
        assertEquals(5L, channel.position())
    }
}

class StreamMediaIoTest : ForwardMediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofStream { ByteArrayInputStream(bytes) }

    @Test
    fun aStreamHasNoSizeAndCannotSeek() = runTest {
        MediaIo.ofStream { ByteArrayInputStream(byteArrayOf(1)) }.open().use { reader ->
            assertNull(reader.size)
            assertFalse(reader.seekable)
            assertFailsWith<UnsupportedOperationException> { reader.seek(0) }
        }
    }

    @Test
    fun eachOpenGetsItsOwnStreamAndClosesIt() = runTest {
        val opened = mutableListOf<ClosingStream>()
        val factory = MediaIo.ofStream { ClosingStream(byteArrayOf(1, 2)).also { opened += it } }
        factory.open().close()
        factory.open().close()
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.closed })
    }

    private class ClosingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
