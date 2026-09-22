package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window arithmetic of the Android asset door, which the host can prove without a device: an
 * asset is a slice of a bigger file, so the pattern sits between 1,000 junk bytes on each side.
 * A read that strays out of the window brings junk into the pattern and fails the contract.
 */
class FileWindowMediaIoTest : MediaIoContractTest() {
    private val channels = mutableListOf<FileChannel>()

    override fun factory(bytes: ByteArray): MediaIoFactory {
        val file = File.createTempFile("kiteplayer-io-window", ".bin").apply {
            deleteOnExit()
            writeBytes(JUNK + bytes + JUNK)
        }
        return MediaIoFactory {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            channels += channel
            FileChannelMediaIo(channel, owner = channel, start = JUNK.size.toLong(), length = bytes.size.toLong())
        }
    }

    @AfterTest
    fun closeTheChannels() = channels.forEach { it.close() }

    @Test
    fun closingTheReaderClosesItsOwner() = runTest {
        val reader = factory(pattern).open()
        val channel = channels.last()
        assertTrue(channel.isOpen)
        reader.close()
        assertFalse(channel.isOpen)
    }

    @Test
    fun aWindowThatRunsPastTheFileEndsWhereTheFileEnds() = runTest {
        val file = File.createTempFile("kiteplayer-io-window", ".bin").apply {
            deleteOnExit()
            writeBytes(JUNK + pattern.copyOf(100))
        }
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        channels += channel
        FileChannelMediaIo(channel, owner = channel, start = JUNK.size.toLong(), length = 1_000).use { reader ->
            assertEquals(pattern.copyOf(100).toList(), reader.readAll().toList())
        }
    }

    private companion object {
        /** What sits around the window. A read that returns it has strayed. */
        val JUNK = ByteArray(1_000) { 0x5A }
    }
}
