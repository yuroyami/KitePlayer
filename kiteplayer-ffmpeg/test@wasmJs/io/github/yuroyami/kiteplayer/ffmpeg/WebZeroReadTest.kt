package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A web reader's 0, "nothing yet", is asked again a few times and then refused by name, where it
 * used to reach FFmpeg as a failed read with no cause (#899).
 */
class WebZeroReadTest {

    /** Answers 0 [emptyReads] times, then a byte at a time. */
    private class SlowReader(private val emptyReads: Int) : MediaIo {
        var reads = 0
        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (reads <= emptyReads) return 0
            into[offset] = 7
            return 1
        }

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() = Unit
    }

    @Test
    fun aReaderThatAnswersZeroOnceIsAskedAgain() {
        val reader = SlowReader(emptyReads = 2)
        assertEquals(1, BlockingMediaIo(reader, Job()).read(ByteArray(8), 0, 8))
        assertEquals(3, reader.reads)
    }

    @Test
    fun aReaderThatKeepsAnsweringZeroIsRefusedByName() {
        val failure = assertFailsWith<UnsupportedOperationException> {
            BlockingMediaIo(SlowReader(emptyReads = Int.MAX_VALUE), Job()).read(ByteArray(8), 0, 8)
        }
        assertTrue("no bytes" in failure.message.orEmpty(), "the refusal names the rule: ${failure.message}")
    }
}
