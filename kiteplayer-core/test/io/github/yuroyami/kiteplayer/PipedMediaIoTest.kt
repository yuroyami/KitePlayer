package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PipedMediaIoTest : ForwardMediaIoContractTest() {
    /** A new pipe per open, filled by its own producer in 1,000-byte slices. A pipe never reopens. */
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIoFactory {
        val pipe = PipedMediaIo()
        CoroutineScope(currentCoroutineContext()).launch {
            var start = 0
            while (start < bytes.size) {
                val count = minOf(1_000, bytes.size - start)
                pipe.write(bytes, start, count)
                start += count
            }
            pipe.finish()
        }
        pipe
    }

    @Test
    fun aPipeHasNoSizeAndCannotSeek() = runTest {
        PipedMediaIo().use { pipe ->
            assertNull(pipe.size)
            assertFalse(pipe.seekable)
            assertFailsWith<UnsupportedOperationException> { pipe.seek(0) }
        }
    }

    @Test
    fun writeSuspendsWhileThePipeIsFull() = runTest {
        PipedMediaIo(capacityChunks = 2).use { pipe ->
            pipe.write(byteArrayOf(1))
            pipe.write(byteArrayOf(2))
            assertNull(withTimeoutOrNull(100.milliseconds) { pipe.write(byteArrayOf(3)) })
            val out = ByteArray(1)
            assertEquals(1, pipe.read(out, 0, 1))
            assertEquals(1, out[0].toInt())
            assertNotNull(withTimeoutOrNull(100.milliseconds) { pipe.write(byteArrayOf(4)) })
        }
    }

    @Test
    fun oneWriteLargerThanTheCapacityArrivesWhole() = runTest {
        val bytes = ByteArray(700_000) { (it * 7).toByte() }
        PipedMediaIo(capacityChunks = 1).use { pipe ->
            launch {
                pipe.write(bytes)
                pipe.finish()
            }
            assertContentEquals(bytes, pipe.readAll())
        }
    }

    @Test
    fun failMakesTheNextReadThrowItsCause() = runTest {
        PipedMediaIo().use { pipe ->
            pipe.write(byteArrayOf(1, 2, 3))
            val cause = ProducerFailed()
            pipe.fail(cause)
            assertSame(cause, assertFailsWith<ProducerFailed> { pipe.read(ByteArray(3), 0, 3) })
        }
    }

    @Test
    fun writeAfterFinishOrFailIsRefused() = runTest {
        val finished = PipedMediaIo()
        finished.finish()
        assertFailsWith<IllegalStateException> { finished.write(byteArrayOf(1)) }
        val failed = PipedMediaIo()
        failed.fail(ProducerFailed())
        assertFailsWith<IllegalStateException> { failed.write(byteArrayOf(1)) }
    }

    @Test
    fun closeReleasesASuspendedWrite() = runTest {
        val pipe = PipedMediaIo(capacityChunks = 1)
        pipe.write(byteArrayOf(1))
        val writer = async { runCatching { pipe.write(byteArrayOf(2)) } }
        runCurrent()
        assertTrue(writer.isActive, "The second write should wait for room")
        pipe.close()
        val outcome = withTimeout(1.seconds) { writer.await() }
        assertIs<CancellationException>(outcome.exceptionOrNull())
    }

    @Test
    fun aCapacityBelowOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { PipedMediaIo(capacityChunks = 0) }
        assertFailsWith<IllegalArgumentException> { PipedMediaIo(capacityChunks = -1) }
    }

    private class ProducerFailed : Exception("producer failed")
}
