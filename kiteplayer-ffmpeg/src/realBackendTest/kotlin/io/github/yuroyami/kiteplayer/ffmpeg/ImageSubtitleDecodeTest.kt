package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Blu-ray subtitle stream decodes to positioned bitmap cues. The stream is the one KiteFFmpeg's
 * own C suite builds byte by byte: a display set at 1 s with one 4x2 image at (100, 200) on a
 * 1920x1080 canvas, then a display set at 2 s with no image, which clears the screen.
 */
class ImageSubtitleDecodeTest {

    /** The PGS elementary stream, written out as test_subtitle.c in KiteFFmpeg writes it. */
    private fun bluRaySubtitles(): ByteArray {
        val out = ArrayList<Byte>()
        fun put8(v: Int) { out += v.toByte() }
        fun put16(v: Int) { put8(v shr 8); put8(v) }
        fun put24(v: Int) { put8(v shr 16); put16(v and 0xFFFF) }
        fun put32(v: Int) { put16(v ushr 16); put16(v and 0xFFFF) }
        fun segment(pts: Int, type: Int, size: Int) { put8('P'.code); put8('G'.code); put32(pts); put32(0); put8(type); put16(size) }
        val rle = intArrayOf(0x01, 0x01, 0x02, 0x02, 0x00, 0x00, 0x02, 0x02, 0x01, 0x01, 0x00, 0x00)

        segment(90_000, 0x16, 19)
        put16(1920); put16(1080); put8(0x10)
        put16(0); put8(0x80); put8(0x00); put8(0)
        put8(1)
        put16(0); put8(0); put8(0x40); put16(100); put16(200)
        segment(90_000, 0x17, 10)
        put8(1); put8(0); put16(100); put16(200); put16(4); put16(2)
        segment(90_000, 0x14, 12)
        put8(0); put8(0)
        put8(1); put8(235); put8(128); put8(128); put8(255)
        put8(2); put8(16); put8(128); put8(128); put8(128)
        segment(90_000, 0x15, 11 + rle.size)
        put16(0); put8(0); put8(0xC0)
        put24(4 + rle.size)
        put16(4); put16(2)
        rle.forEach(::put8)
        segment(90_000, 0x80, 0)
        segment(180_000, 0x16, 11)
        put16(1920); put16(1080); put8(0x10)
        put16(1); put8(0x00); put8(0x00); put8(0)
        put8(0)
        segment(180_000, 0x80, 0)
        return out.toByteArray()
    }

    @Test
    fun aBluRayDisplaySetDecodesToAPositionedImageThenAClear() = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(bluRaySubtitles()), label = "subtitles.sup")
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Subtitle }, "no subtitle stream")
            assertEquals("hdmv_pgs_subtitle", stream.codec)
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(KiteFFmpegImageSubtitleDecoderFactory(source).create(stream))
            val cues = ArrayList<SubtitleCue>()
            try {
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        assertTrue(decoder.send(packet))
                    } finally {
                        packet.close()
                    }
                    cues += decoder.receive()
                }
            } finally {
                decoder.close()
            }

            assertEquals(2, cues.size, "one cue for the image and one for the clear: $cues")
            val shown = cues[0] as SubtitleCue.Bitmap
            val cleared = cues[1] as SubtitleCue.Bitmap
            assertEquals(1_000_000L, cleared.startMicros - shown.startMicros, "the clear comes one second later")
            assertEquals(SubtitleCue.OPEN_END, shown.endMicros, "a Blu-ray image states no end")
            assertEquals(SubtitleCue.OPEN_END, cleared.endMicros)
            assertTrue(cleared.regions.isEmpty(), "the clear draws nothing")

            val region = shown.regions.single()
            assertEquals(listOf(100, 200, 4, 2), listOf(region.x, region.y, region.width, region.height))
            assertEquals(1920 to 1080, region.canvasWidth to region.canvasHeight)
            // Opaque white and half-transparent black from the stream's palette, premultiplied.
            val white = listOf(255, 255, 255, 255)
            val shade = listOf(0, 0, 0, 128)
            assertContentEquals(
                (white + white + shade + shade + shade + shade + white + white).map { it.toByte() }.toByteArray(),
                region.bitmap.pixels.copyOf(4 * 2 * 4),
            )
        } finally {
            source.close()
        }
    }
}
