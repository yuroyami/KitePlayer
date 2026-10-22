package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Serves [prefix], then blocks every read until it is closed, like a pipe whose writer stays open. */
internal class StallingStream(private val prefix: ByteArray) : InputStream() {
    private var position = 0
    private val closedSignal = CountDownLatch(1)
    val closes = AtomicInteger()

    @Volatile
    var blocked = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position < prefix.size) {
            val count = minOf(len, prefix.size - position)
            prefix.copyInto(b, off, position, position + count)
            position += count
            return count
        }
        blocked = true
        closedSignal.await()
        throw IOException("Stream closed")
    }

    override fun close() {
        closes.incrementAndGet()
        closedSignal.countDown()
    }
}

class StalledStreamTest {

    @Test
    fun aCancelledReadOnAStalledStreamEndsAndClosesTheStreamOnce() = runBlocking {
        val stream = StallingStream(ByteArray(100) { it.toByte() })
        val reader = MediaIo.ofStream { stream }.open()
        val buffer = ByteArray(64)
        assertEquals(64, reader.read(buffer, 0, 64))
        assertEquals(36, reader.read(buffer, 0, 64))

        val read = launch(Dispatchers.Default) { reader.read(buffer, 0, 64) }
        withTimeout(5.seconds) { while (!stream.blocked) delay(10) }
        assertTrue(read.isActive, "the read waits for bytes that never come")
        withTimeout(2.seconds) { read.cancelAndJoin() }

        assertEquals(1, stream.closes.get(), "the cancelled read closes the stream")
        assertFailsWith<IllegalStateException> { reader.read(buffer, 0, 64) }
        reader.close()
        assertEquals(1, stream.closes.get(), "the stream is closed once")
    }
}
