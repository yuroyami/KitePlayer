@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The libass pack leaves C memory in one copy (#207). cinterop's `readBytes` stored one byte per
 * loop turn and measured about 1,300 times slower than `memcpy` on a pack of this size.
 */
class PackCopyTest {

    private fun medianMillis(block: () -> Unit): Double {
        val times = List(RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeNanoseconds / 1e6
        }
        return times.sorted()[RUNS / 2]
    }

    @Test
    fun aPackIsCopiedWholeAtAboutTheCostOfOneMemcpy() {
        val pack = nativeHeap.allocArray<UByteVar>(PACK_BYTES)
        try {
            for (i in 0 until PACK_BYTES) pack[i] = (i * 31).toUByte()
            val copied = copyOut(pack, PACK_BYTES)
            assertTrue((0 until PACK_BYTES).all { copied[it] == (it * 31).toByte() }, "the copy changed the bytes")

            val reference = ByteArray(PACK_BYTES)
            val memcpyMillis = medianMillis {
                reference.usePinned { memcpy(it.addressOf(0), pack, PACK_BYTES.convert()) }
            }
            val copyMillis = medianMillis { copyOut(pack, PACK_BYTES) }
            val ratio = copyMillis / memcpyMillis.coerceAtLeast(0.001)
            println("256 KiB pack, median of $RUNS: copyOut $copyMillis ms, memcpy $memcpyMillis ms, ratio $ratio")
            assertTrue(ratio <= MOST_RATIO, "copyOut took $ratio times one memcpy; readBytes measured about 1,300 times")
        } finally {
            nativeHeap.free(pack)
        }
    }

    private companion object {
        const val PACK_BYTES = 256 * 1024
        const val RUNS = 9

        /**
         * Five times the highest ratio an M2 measured for these copies, which ranged from 4 to 10,
         * because allocating and zeroing the new array varies more than the copy does. The old byte
         * loop sat near 1,300.
         */
        const val MOST_RATIO = 50.0
    }
}
