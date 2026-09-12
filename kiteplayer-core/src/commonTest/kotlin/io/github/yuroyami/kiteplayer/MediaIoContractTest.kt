package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Reusable contract for seekable input factories. Later input doors supply their own factory. */
abstract class MediaIoContractTest {
    protected abstract fun factory(bytes: ByteArray): MediaIoFactory

    @Test
    fun readsPartialSlicesSeeksAndEof() = runTest {
        val bytes = ByteArray(19) { (it * 13).toByte() }
        factory(bytes).open().use { reader ->
            assertEquals(19L, reader.size)
            assertTrue(reader.seekable)
            val out = ByteArray(8) { -1 }
            assertEquals(4, reader.read(out, 2, 4))
            assertContentEquals(byteArrayOf(-1, -1) + bytes.copyOfRange(0, 4) + byteArrayOf(-1, -1), out)
            reader.seek(17)
            assertEquals(2, reader.read(out, 0, out.size))
            assertContentEquals(bytes.copyOfRange(17, 19), out.copyOf(2))
            assertEquals(-1, reader.read(out, 0, 1))
            assertEquals(0, reader.read(out, out.size, 0))
            reader.seek(0)
            assertEquals(8, reader.read(out, 0, 8))
            assertContentEquals(bytes.copyOf(8), out)
            reader.seek(19)
            assertEquals(-1, reader.read(out, 0, 1))
        }
    }

    @Test
    fun invalidArgumentsDoNotMoveTheCursor() = runTest {
        factory(byteArrayOf(5, 6, 7)).open().use { reader ->
            val out = ByteArray(2)
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 1, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { reader.read(out, offset, length) }
            }
            for (position in listOf(-1L, 4L, Long.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { reader.seek(position) }
            }
            assertEquals(2, reader.read(out, 0, 2))
            assertContentEquals(byteArrayOf(5, 6), out)
        }
    }

    @Test
    fun reopenOwnsItsCursorAndCloseState() = runTest {
        val factory = factory(byteArrayOf(7, 8))
        val first = factory.open()
        try {
            factory.open().use { second ->
                assertNotSame(first, second)
                val out = ByteArray(1)
                first.seek(2)
                assertEquals(1, second.read(out, 0, 1))
                assertEquals(7, out[0].toInt())
                first.close()
                first.close()
                assertFailsWith<IllegalStateException> { first.read(out, 0, 0) }
                assertFailsWith<IllegalStateException> { first.seek(0) }
                assertEquals(1, second.read(out, 0, 1))
                assertEquals(8, out[0].toInt())
            }
            factory.open().use { reopened ->
                val out = ByteArray(1)
                assertEquals(1, reopened.read(out, 0, 1))
                assertEquals(7, out[0].toInt())
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun emptyInputHasKnownSizeAndEof() = runTest {
        factory(byteArrayOf()).open().use { reader ->
            assertEquals(0L, reader.size)
            reader.seek(0)
            assertEquals(0, reader.read(byteArrayOf(), 0, 0))
            assertEquals(-1, reader.read(ByteArray(1), 0, 1))
        }
    }
}

class ByteArrayMediaIoTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofBytes(bytes)

    @Test
    fun itemUsesTheSuppliedFactoryAndLabel() = runTest {
        val factory = MediaIo.ofBytes(byteArrayOf(3))
        val item = MediaItem.from(factory, "track.mkv")
        assertEquals("track.mkv", item.uri)
        assertEquals("track.mkv", item.label)
        assertTrue(factory === item.io)
    }
}
