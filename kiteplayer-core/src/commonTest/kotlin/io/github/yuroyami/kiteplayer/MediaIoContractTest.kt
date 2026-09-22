package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The contract every input door passes, seekable or not. Readers here only read forward.
 * Seekable doors pass [MediaIoContractTest], which adds the seek checks on top.
 */
abstract class ForwardMediaIoContractTest {
    /** A factory whose readers produce exactly [bytes]. */
    protected abstract fun factory(bytes: ByteArray): MediaIoFactory

    /** 0 to 255 repeated, so a byte above 0x7F that was sign-mangled shows. */
    protected val pattern: ByteArray = ByteArray(300_000) { it.toByte() }

    protected suspend fun MediaIo.readAll(): ByteArray {
        var out = ByteArray(64 * 1024)
        var size = 0
        val buffer = ByteArray(7_001)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) return out.copyOf(size)
            check(count > 0 || !seekable) { "A seekable reader returned 0 bytes for a non-empty read" }
            if (size + count > out.size) out = out.copyOf(maxOf(out.size * 2, size + count))
            buffer.copyInto(out, size, 0, count)
            size += count
        }
    }

    protected suspend fun MediaIo.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(out, filled, count - filled)
            check(read >= 0) { "End of stream after $filled of $count bytes" }
            filled += read
        }
        return out
    }

    @Test
    fun fullReadMatchesThePattern() = runTest {
        factory(pattern).open().use { reader ->
            assertContentEquals(pattern, reader.readAll())
        }
    }

    @Test
    fun endOfStreamStaysEndOfStream() = runTest {
        factory(pattern).open().use { reader ->
            reader.readAll()
            val out = ByteArray(16)
            assertEquals(-1, reader.read(out, 0, out.size))
            assertEquals(-1, reader.read(out, 0, out.size))
            assertEquals(0, reader.read(out, 0, 0))
        }
    }

    @Test
    fun invalidSlicesAreRefusedWithoutConsumingBytes() = runTest {
        factory(byteArrayOf(5, 6, 7)).open().use { reader ->
            val out = ByteArray(2)
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 1, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { reader.read(out, offset, length) }
            }
            assertEquals(0, reader.read(out, 0, 0))
            assertContentEquals(byteArrayOf(5, 6), reader.readExactly(2))
        }
    }

    @Test
    fun readAfterCloseThrowsAndCloseTwiceIsHarmless() = runTest {
        val reader = factory(byteArrayOf(1, 2)).open()
        reader.close()
        reader.close()
        assertFailsWith<IllegalStateException> { reader.read(ByteArray(1), 0, 1) }
        assertFailsWith<IllegalStateException> { reader.read(ByteArray(1), 0, 0) }
    }

    @Test
    fun everyOpenStartsAtTheBeginning() = runTest {
        val factory = factory(pattern)
        val first = factory.open()
        val head = try { first.readExactly(10) } finally { first.close() }
        assertContentEquals(pattern.copyOf(10), head)
        factory.open().use { second -> assertContentEquals(head, second.readExactly(10)) }
    }

    @Test
    fun twoOpenReadersKeepTheirOwnPositions() = runTest {
        val factory = factory(pattern)
        factory.open().use { first ->
            factory.open().use { second ->
                assertNotSame(first, second)
                assertContentEquals(pattern.copyOf(1_000), first.readExactly(1_000))
                assertContentEquals(pattern.copyOf(10), second.readExactly(10))
                assertContentEquals(pattern.copyOfRange(1_000, 1_010), first.readExactly(10))
            }
        }
    }
}

/** The contract for seekable doors, on top of [ForwardMediaIoContractTest]. */
abstract class MediaIoContractTest : ForwardMediaIoContractTest() {
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
    fun seekIntoALargeInputLandsExactly() = runTest {
        factory(pattern).open().use { reader ->
            assertEquals(pattern.size.toLong(), reader.size)
            reader.seek(100_003)
            assertContentEquals(pattern.copyOfRange(100_003, 100_008), reader.readExactly(5))
            reader.seek(0)
            assertContentEquals(pattern.copyOf(3), reader.readExactly(3))
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
