package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.WebmBytes.element
import io.github.yuroyami.kiteplayer.network.dash.WebmBytes.unsigned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The WebM index that names a single file's clusters, read from EBML bytes written here (#401). */
class WebmIndexTest {

    @Test
    fun theLayoutFindsTheSegmentTheScaleAndTheCuesThatTheSeekHeadNames() {
        val file = WebmBytes.file(listOf(0, 2000, 4000), blockBytes = 100, durationMillis = 5000.0)
        val layout = WebmIndex.layout(file.bytes.copyOf(file.firstCluster.toInt() + 20))
        assertEquals(file.segmentDataStart, layout.segmentDataStart)
        assertEquals(file.bytes.size.toLong(), layout.segmentEnd)
        assertEquals(1_000_000L, layout.timestampScaleNanos)
        assertEquals(5000.0 * 1_000_000, layout.durationNanos)
        assertEquals(file.cues.first, layout.cuesStart)
        assertEquals(file.firstCluster, layout.firstCluster)
    }

    @Test
    fun aSegmentOfUnknownSizeAndAScaleOfItsOwnAreRead() {
        val ebml = element(0x1A45DFA3, element(0x4282, "webm".encodeToByteArray()))
        val info = element(0x1549A966, element(0x2AD7B1, unsigned(100_000)))
        val bytes = ebml + WebmBytes.id(WebmBytes.SEGMENT) + WebmBytes.UNKNOWN_SIZE + info + element(WebmBytes.CLUSTER, ByteArray(4))
        val layout = WebmIndex.layout(bytes)
        assertNull(layout.segmentEnd, "the Segment's size is unknown")
        assertEquals(100_000L, layout.timestampScaleNanos)
        assertNull(layout.durationNanos)
        assertNull(layout.cuesStart, "nothing names the Cues")
        assertEquals((bytes.size - 16).toLong(), layout.firstCluster)
    }

    @Test
    fun cuePointsGiveEachClusterOnceInOrder() {
        val cues = element(
            WebmBytes.CUES,
            WebmBytes.cuePoint(time = 0, track = 1, cluster = 400) +
                WebmBytes.cuePoint(time = 0, track = 2, cluster = 400) +
                WebmBytes.cuePoint(time = 4000, track = 1, cluster = 99_000) +
                WebmBytes.cuePoint(time = 2000, track = 1, cluster = 51_000),
        )
        val points = WebmIndex.cuePoints(cues)
        assertEquals(listOf(0L, 2000L, 4000L), points.map { it.time })
        assertEquals(listOf(400L, 51_000L, 99_000L), points.map { it.clusterPosition })
    }

    @Test
    fun theSizeOfAnElementComesFromItsHeader() {
        val cues = element(WebmBytes.CUES, ByteArray(300))
        assertEquals(cues.size.toLong(), WebmIndex.elementSize(cues.copyOf(12)))
        assertNull(WebmIndex.elementSize(cues.copyOf(6)), "six bytes do not hold the whole header")
    }

    @Test
    fun aFileThatIsNotWebmIsRefusedTyped() {
        assertFailsWith<DashUnsupportedException> { WebmIndex.layout(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)) }
        assertFailsWith<DashUnsupportedException> { WebmIndex.cuePoints(element(WebmBytes.CLUSTER, ByteArray(4))) }
    }
}
