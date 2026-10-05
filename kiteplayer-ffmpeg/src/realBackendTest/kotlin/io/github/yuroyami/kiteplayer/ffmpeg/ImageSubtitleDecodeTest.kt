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

    /** Every cue [bytes] decodes to, read through the real source and decoder. */
    private fun decode(bytes: ByteArray): List<SubtitleCue> = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(bytes), label = "subtitles.sup")
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
            cues
        } finally {
            source.close()
        }
    }

    @Test
    fun aBluRayDisplaySetDecodesToAPositionedImageThenAClear() {
        val cues = decode(bluRaySubtitles(1 to listOf(PgsCaption(100, 200, forced = true)), 2 to emptyList()))

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
        assertTrue(region.forced, "the stream marks its picture forced")
        // Opaque white and half-transparent black from the stream's palette, premultiplied.
        val white = listOf(255, 255, 255, 255)
        val shade = listOf(0, 0, 0, 128)
        assertContentEquals(
            (white + white + shade + shade + shade + shade + white + white).map { it.toByte() }.toByteArray(),
            region.bitmap.pixels.copyOf(4 * 2 * 4),
        )
    }

    /**
     * A disc's forced captions sit among its full subtitles in one track, told apart only by a
     * mark on each picture, and one display set can hold a forced picture beside an ordinary one
     * (#513). Each picture's mark reaches its region.
     */
    @Test
    fun theForcedMarkOfEachPictureReachesItsRegion() {
        val cues = decode(
            bluRaySubtitles(
                1 to listOf(PgsCaption(100, 900, forced = false)),
                2 to listOf(PgsCaption(100, 900, forced = true)),
                3 to listOf(PgsCaption(100, 100, forced = true), PgsCaption(600, 900, forced = false)),
                4 to emptyList(),
            ),
        ).map { it as SubtitleCue.Bitmap }

        assertEquals(listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L), cues.map { it.startMicros - cues[0].startMicros }, "one cue a second")
        assertEquals(listOf(false), cues[0].regions.map { it.forced }, "an ordinary picture read as forced")
        assertEquals(listOf(true), cues[1].regions.map { it.forced }, "a forced picture lost its mark")
        assertEquals(
            mapOf(100 to true, 600 to false),
            cues[2].regions.associate { it.x to it.forced },
            "the two pictures of one display set kept their own marks",
        )
        assertTrue(cues[3].regions.isEmpty())
    }
}
