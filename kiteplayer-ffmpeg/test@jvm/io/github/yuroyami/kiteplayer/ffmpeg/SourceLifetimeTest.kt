package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** One failed read of a source fails that read alone, and the interrupt still ends them all (#718). */
class SourceLifetimeTest {

    /** A reader whose next read throws when [failNext] is set, and answers one byte otherwise. */
    private class Flaky : MediaIo {
        var failNext = false
        override val size: Long? = null
        override val seekable: Boolean = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (failNext) {
                failNext = false
                throw java.io.IOException("the segment's server went away")
            }
            into[offset] = 7
            return 1
        }
        override suspend fun seek(position: Long) = Unit
        override fun close() = Unit
    }

    @Test
    fun aReadThatThrowsLeavesTheNextReadsOfTheSourceWorking() {
        val lifetime = sourceLifetime()
        val io = Flaky()
        val bridge = BlockingMediaIo(io, lifetime)
        val into = ByteArray(1)
        io.failNext = true
        assertFailsWith<java.io.IOException> { bridge.read(into, 0, 1) }
        assertEquals(1, bridge.read(into, 0, 1), "the next read of the same source")
        // A nested open on the same lifetime, as the next segment's is.
        assertEquals(42, blockingIn(lifetime) { 42 })
        assertFailsWith<IllegalStateException> { blockingIn<Int>(lifetime) { error("one segment") } }
        assertEquals(43, blockingIn(lifetime) { 43 })
    }

    @Test
    fun theInterruptStillEndsEveryReadAfterIt() {
        val lifetime = sourceLifetime()
        val bridge = BlockingMediaIo(Flaky(), lifetime)
        bridge.interrupt()
        assertFailsWith<kotlinx.coroutines.CancellationException> { bridge.read(ByteArray(1), 0, 1) }
        assertFailsWith<kotlinx.coroutines.CancellationException> { blockingIn(lifetime) { 1 } }
    }
}
