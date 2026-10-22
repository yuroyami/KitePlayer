package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two descriptions of the same stream are equal, extradata included.
 *
 * A data class holding a `ByteArray` gets an `equals` that compares that field by REFERENCE, so
 * before this the same stream read twice came out unequal and a `Set` of streams held duplicates.
 * The sibling's own `StreamInfo` has compared extradata by content from the start.
 */
class StreamInfoEqualityTest {

    private fun stream(extradata: ByteArray?) = PlayerStreamInfo(
        index = 0,
        kind = TrackKind.Video,
        codec = "h264",
        codecExtradata = extradata,
    )

    @Test
    fun `equal extradata in different arrays compares equal`() {
        val a = stream(byteArrayOf(1, 2, 3))
        val b = stream(byteArrayOf(1, 2, 3))
        assertEquals(a, b, "the same bytes in two arrays describe the same stream")
        assertEquals(a.hashCode(), b.hashCode(), "or a Set holds both")
        // The set is the case that actually bites an application listing tracks.
        assertEquals(1, setOf(a, b).size)
    }

    @Test
    fun `different extradata still compares unequal`() {
        assertNotEquals(stream(byteArrayOf(1, 2, 3)), stream(byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `absent extradata is not the same as empty extradata`() {
        // Null means the container declared none; empty means it declared an empty one. A decoder
        // configured from the second is not configured from the first.
        assertNotEquals(stream(null), stream(byteArrayOf()))
        assertEquals(stream(null), stream(null))
    }

    @Test
    fun `a difference outside the extradata is still seen`() {
        val a = stream(byteArrayOf(1))
        val b = a.copy(language = "eng")
        assertNotEquals(a, b, "the hand-written equals must still compare every other field")
        assertTrue(a.copy() == a, "and copy() of an unchanged value stays equal")
    }
}
