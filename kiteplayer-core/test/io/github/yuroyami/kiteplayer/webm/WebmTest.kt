package io.github.yuroyami.kiteplayer.webm

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The WebM header reader, and clusters written for the track numbers of another header (#566). */
@OptIn(KitePlayerInternalApi::class)
class WebmTest {

    private fun element(id: Long, data: ByteArray, unknownSize: Boolean = false): ByteArray {
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it != 0L }.map { it.toByte() }.toList().reversed().toByteArray()
        // A size of eight bytes, which any length fits, or the all-ones size that says "unknown".
        val size = if (unknownSize) byteArrayOf(0xFF.toByte()) else byteArrayOf(0x01) + ByteArray(7) { (data.size.toLong() ushr (8 * (6 - it))).toByte() }
        return idBytes + size + data
    }

    private fun number(value: Long, bytes: Int = 1) = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }

    private fun video(number: Long, codec: String, setup: ByteArray? = null) = element(
        0xAE,
        element(0xD7, number(number)) + element(0x83, number(1)) + element(0x86, codec.encodeToByteArray()) +
            (setup?.let { element(0x63A2, it) } ?: ByteArray(0)),
    )

    private fun sound(number: Long, codec: String, setup: ByteArray, channels: Long = 2) = element(
        0xAE,
        element(0xD7, number(number)) + element(0x83, number(2)) + element(0x86, codec.encodeToByteArray()) + element(0x63A2, setup) +
            element(0xE1, element(0xB5, number(48000f.toRawBits().toLong(), 4)) + element(0x9F, number(channels))),
    )

    /** An initialization: the EBML header, then a Segment of unknown size with its Info and Tracks. */
    private fun header(vararg tracks: ByteArray, scale: Long? = null): ByteArray {
        val info = element(0x1549A966, scale?.let { element(0x2AD7B1, number(it, 4)) } ?: ByteArray(0))
        return element(0x1A45DFA3, element(0x4282, "webm".encodeToByteArray())) +
            element(0x18538067, info + element(0x1654AE6B, tracks.fold(ByteArray(0)) { all, track -> all + track }), unknownSize = true)
    }

    /** A block's data: its track number in one byte, a time, flags, and [payload]. */
    private fun block(track: Int, payload: Byte) = byteArrayOf((0x80 or track).toByte(), 0, 0, 0x80.toByte(), payload)

    @Test
    fun aHeaderGivesItsScaleAndItsTracksInOrder() {
        val read = Webm.header(header(video(1, "V_VP9", byteArrayOf(1, 1, 0)), sound(2, "A_OPUS", byteArrayOf(9)), scale = 500_000))
        assertEquals(500_000, read.timestampScaleNanos)
        assertEquals(listOf(1L to "V_VP9", 2L to "A_OPUS"), read.tracks.map { it.number to it.codecId })
        assertEquals(listOf(1L, 2L), read.tracks.map { it.type })
        assertContentEquals(byteArrayOf(1, 1, 0), read.tracks[0].codecPrivate)
        assertEquals(48000.0, read.tracks[1].samplingFrequency)
        assertEquals(2, read.tracks[1].channels)
        assertEquals(1_000_000, Webm.header(header(video(1, "V_VP8"))).timestampScaleNanos, "a header that states no scale counts in milliseconds")
        assertFailsWith<WebmUnsupportedException> { Webm.header(byteArrayOf(0, 0, 0, 0x1C, 0x66, 0x74, 0x79, 0x70)) }
    }

    @Test
    fun clustersFollowAnotherHeaderWhenScaleAndCodecAgree() {
        val base = Webm.header(header(video(1, "V_VP9"), sound(2, "A_OPUS", byteArrayOf(1))))
        fun numbers(bytes: ByteArray) = Webm.numbersFor(Webm.header(bytes), base)
        assertEquals(mapOf(1L to 1L, 2L to 2L), numbers(header(video(1, "V_VP9"), sound(2, "A_OPUS", byteArrayOf(7)))), "an Opus header may differ")
        assertEquals(mapOf(5L to 1L, 3L to 2L), numbers(header(sound(3, "A_OPUS", byteArrayOf(1)), video(5, "V_VP9"))), "tracks pair by kind")
        assertNull(numbers(header(video(1, "V_VP9"), sound(2, "A_OPUS", byteArrayOf(1)), scale = 100_000)), "another timestamp scale")
        assertNull(numbers(header(video(1, "V_VP8"), sound(2, "A_OPUS", byteArrayOf(1)))), "another picture codec")
        assertNull(numbers(header(video(1, "V_VP9", byteArrayOf(1, 1, 2)), sound(2, "A_OPUS", byteArrayOf(1)))), "another VP9 profile")
        assertNull(numbers(header(video(1, "V_VP9"), sound(2, "A_VORBIS", byteArrayOf(1)))), "another sound codec")
        assertNull(numbers(header(video(1, "V_VP9"), sound(2, "A_OPUS", byteArrayOf(1), channels = 6))), "other channels")
        assertNull(numbers(header(video(1, "V_VP9"))), "a track fewer")
        assertNull(numbers(header(video(200, "V_VP9"), sound(2, "A_OPUS", byteArrayOf(1)))), "a number that is two bytes in a block")
        val vorbis = Webm.header(header(sound(1, "A_VORBIS", byteArrayOf(1, 2))))
        assertNotNull(Webm.numbersFor(Webm.header(header(sound(1, "A_VORBIS", byteArrayOf(1, 2)))), vorbis))
        assertNull(Webm.numbersFor(Webm.header(header(sound(1, "A_VORBIS", byteArrayOf(1, 3)))), vorbis), "other Vorbis codebooks")
    }

    @Test
    fun blocksTakeTheNumbersOfTheOtherHeader() {
        val group = element(0xA0, element(0xA1, block(3, 22)) + element(0x9B, number(20)))
        val cluster = element(0x1F43B675, element(0xE7, number(4000, 2)) + element(0xA3, block(5, 11)) + group + element(0xA3, block(9, 33)))
        // The second cluster has no size, as a live packager writes one.
        val open = element(0x1F43B675, element(0xE7, number(6000, 2)) + element(0xA3, block(3, 44)), unknownSize = true)
        val segment = cluster + open
        assertSame(segment, Webm.retrack(segment, mapOf(5L to 5L, 3L to 3L)), "nothing to write")

        val written = Webm.retrack(segment, mapOf(5L to 1L, 3L to 2L))
        assertEquals(segment.size, written.size)
        val changed = segment.indices.filter { segment[it] != written[it] }
        assertEquals(3, changed.size, "only the three blocks' track numbers change")
        assertEquals(listOf(0x81, 0x82, 0x82), changed.map { written[it].toInt() and 0xFF })
        assertEquals(listOf(11, 22, 44), changed.map { written[it + 4].toInt() }, "each number is the one before its own block's payload")
        assertTrue(written.indexOf(0x89.toByte()) >= 0, "a block of a track with no number to take stays")
    }
}
