package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The engine wraps an item's reader in its stall watch and its byte cache. An HLS stream needs what
 * the reader knows to reach the backend through both: its address, its content type and the
 * readers it opens for related addresses, whose bytes count as traffic (#209).
 */
class RelatedReadersTest {

    private class PlaylistReader : MediaIo {
        private val bytes = "#EXTM3U\n".encodeToByteArray()
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override val location: String get() = "https://edge.test/moved/index.m3u8"
        override val contentType: String get() = "application/vnd.apple.mpegurl"

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }

        override suspend fun openRelated(uri: String): MediaIo? =
            if (uri.startsWith("https://edge.test/")) MediaIo.ofBytes(ByteArray(SEGMENT_BYTES)).open() else null

        override fun close() = Unit
    }

    @Test
    fun theBackendSeesTheReadersAddressTypeAndRelatedReaders() = runTest {
        val harness = CoreHarness(
            this,
            config = PlayerConfig(network = NetworkConfig(ioResolver = MediaIoResolver { PlaylistReader() })),
        )
        harness.openWithRenderer()
        val io = assertNotNull(harness.backend.lastOpenedItem?.io).open()
        assertEquals("https://edge.test/moved/index.m3u8", io.location)
        assertEquals("application/vnd.apple.mpegurl", io.contentType)
        assertNull(io.openRelated("https://elsewhere.test/seg.ts"), "the reader's refusal reaches the backend")

        val segment = assertNotNull(io.openRelated("https://edge.test/moved/seg-0.ts"))
        val buffer = ByteArray(SEGMENT_BYTES)
        var read = 0
        while (read < SEGMENT_BYTES) read += segment.read(buffer, read, SEGMENT_BYTES - read).also { check(it > 0) }
        segment.close()

        harness.run(2.seconds)
        val total = harness.core.stats.value.ioBytesTotal
        assertTrue(total >= SEGMENT_BYTES, "the segment's $SEGMENT_BYTES bytes are missing from the traffic total $total")
        assertTrue(harness.core.progress.value.bufferedRanges.isEmpty(), "a segment stream has no byte cache range")
        harness.close()
    }

    private companion object {
        const val SEGMENT_BYTES = 4_096
    }
}
