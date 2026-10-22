package io.github.yuroyami.kiteplayer.io

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The slice rule every door checks before it reads. It is also the one test that runs on every
 * target, because a test task that finds only the abstract contract classes fails the build.
 */
class ReadSliceTest {

    @Test
    fun aSliceInsideTheArrayIsAccepted() {
        val into = ByteArray(8)
        requireReadSlice(into, offset = 0, length = 8)
        requireReadSlice(into, offset = 3, length = 5)
        requireReadSlice(into, offset = 8, length = 0)
    }

    @Test
    fun aSliceOutsideTheArrayIsRefused() {
        val into = ByteArray(8)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 9 to 0, 4 to 5, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("offset $offset, length $length") {
                requireReadSlice(into, offset, length)
            }
        }
    }
}
