package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.CLOSED_CAPTIONS_CODEC
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closed captions inside the video stream (#236). The `cc-base.h264` fixture is three seconds of
 * plain H.264 at 30 pictures a second; this test writes an ATSC A/53 caption message before each
 * picture, as a broadcaster's encoder does, with one CEA-608 pair each: a pop-on HELLO on CC1,
 * shown at the end of caption of the sixth picture and erased at the thirtieth. Each decoded picture
 * carries its captions, in the order the pictures show, for the engine to make a track of, and the
 * decoder for that track's codec shows HELLO from its end of caption until the erase clears it.
 */
class CaptionsInsideVideoTest {

    private val base: File = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile } ?: File("testmedia/$MEDIA")
    private val captioned = File.createTempFile("captioned", ".h264")

    @AfterTest
    fun cleanup() {
        captioned.delete()
    }

    /** A CEA-608 byte with its odd parity bit. */
    private fun odd(byte: Int): Int = if (Integer.bitCount(byte and 0x7F) % 2 == 0) byte or 0x80 else byte

    /** The caption pair picture [index] carries. */
    private fun pairOf(index: Int): Pair<Int, Int> = when (index) {
        0, 1 -> 0x14 to 0x20
        2 -> 'H'.code to 'E'.code
        3 -> 'L'.code to 'L'.code
        4 -> 'O'.code to 0
        5, 6 -> 0x14 to 0x2F
        29, 30 -> 0x14 to 0x2C
        else -> 0 to 0
    }

    /** An SEI NAL with one A/53 caption pair: the GA94 user data, cc_count 1, field 1. */
    private fun captionSei(index: Int): ByteArray {
        val (a, b) = pairOf(index)
        return byteArrayOf(
            0, 0, 0, 1, 0x06, 0x04, 0x0E,
            0xB5.toByte(), 0x00, 0x31, 0x47, 0x41, 0x39, 0x34, 0x03, 0xC1.toByte(), 0xFF.toByte(),
            0xFC.toByte(), odd(a).toByte(), odd(b).toByte(), 0xFF.toByte(),
            0x80.toByte(),
        )
    }

    /** The fixture with a caption message written before the slice of every picture. */
    private fun writeCaptioned(): File {
        requireTestMedia(base.isFile, "no $MEDIA; run scripts/testmedia.sh")
        val bytes = base.readBytes()
        val starts = (0 until bytes.size - 3).filter { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() && bytes[it + 2] == 1.toByte() }
        val out = ByteArrayOutputStream()
        var picture = 0
        for ((n, start) in starts.withIndex()) {
            // A four byte start code begins one byte early.
            val from = if (start > 0 && bytes[start - 1] == 0.toByte()) start - 1 else start
            val end = starts.getOrNull(n + 1)?.let { next -> if (bytes[next - 1] == 0.toByte()) next - 1 else next } ?: bytes.size
            val type = bytes[start + 3].toInt() and 0x1F
            if (type == 1 || type == 5) out.write(captionSei(picture++))
            out.write(bytes, from, end - from)
        }
        assertEquals(90, picture, "the fixture holds three seconds of pictures, one slice each")
        captioned.writeBytes(out.toByteArray())
        return captioned
    }

    /** One decoded picture's time and captions. */
    private class Picture(val pts: Pts, val captions: ByteArray?)

    /** The pictures of [file] in the order the decoder gives them, which is the order they show. */
    private fun decodeAll(file: File): List<Picture> = runBlocking {
        val source = KiteFFmpegSourceFactory().open(MediaItem(file.absolutePath, formatHint = "h264")) as KiteFFmpegSource
        try {
            val video = assertNotNull(source.firstVideo)
            source.selectStreams(setOf(video.index))
            val decoder = source.newVideoDecoder(video)
            val pictures = ArrayList<Picture>()
            try {
                suspend fun drain() {
                    while (true) {
                        val frame = decoder.receive() ?: break
                        frame.use { pictures += Picture(it.pts, it.closedCaptions) }
                    }
                }
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) drain()
                    } finally {
                        packet.close()
                    }
                    drain()
                }
                decoder.send(null)
                drain()
            } finally {
                decoder.close()
            }
            pictures
        } finally {
            source.close()
        }
    }

    /** A packet of the engine's caption track: one picture's captions at its time. */
    private class CaptionPacket(override val pts: Pts, private val bytes: ByteArray) : PlayerPacket {
        override val streamIndex: Int get() = CAPTION_INDEX
        override val dts: Pts get() = pts
        override val duration: Pts? get() = null
        override val isKeyframe: Boolean get() = true
        override val sizeBytes: Int get() = bytes.size
        override fun copyBytes(): ByteArray = bytes.copyOf()
        override val bytePosition: Long? get() = null
        override fun close() = Unit
    }

    @Test
    fun eachPictureCarriesItsCaptionsInTheOrderThePicturesShow() {
        val pictures = decodeAll(writeCaptioned())
        assertEquals(90, pictures.size)
        assertTrue(pictures.all { it.captions?.size == 3 }, "a picture lost its captions")
        assertEquals(pictures.map { it.pts.micros }.sorted(), pictures.map { it.pts.micros })
    }

    @Test
    fun theCaptionCodecsDecoderShowsEachScreenFromThePictureThatChangedIt() = runBlocking {
        val pictures = decodeAll(writeCaptioned())
        val source = KiteFFmpegSourceFactory().open(MediaItem(base.absolutePath, formatHint = "h264")) as KiteFFmpegSource
        val stream = PlayerStreamInfo(CAPTION_INDEX, TrackKind.Subtitle, CLOSED_CAPTIONS_CODEC, title = "CC1")
        val cues = ArrayList<SubtitleCue>()
        try {
            val decoder = assertNotNull(KiteFFmpegCaptionDecoderFactory(source).create(stream))
            try {
                for (picture in pictures) {
                    decoder.send(CaptionPacket(picture.pts, assertNotNull(picture.captions)))
                    cues += decoder.receive()
                }
                decoder.send(null)
                cues += decoder.receive()
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
        val text = cues.filterIsInstance<SubtitleCue.Text>()
        assertEquals(2, text.size, "$text")
        val (hello, cleared) = text
        assertEquals("HELLO", hello.plainText.trim())
        assertEquals(pictures[5].pts.micros, hello.startMicros, "the caption shows from its end of caption")
        assertEquals(SubtitleCue.OPEN_END, hello.endMicros, "and holds until the next screen")
        assertEquals("", cleared.plainText, "the erase clears the screen")
        assertEquals(pictures[29].pts.micros, cleared.startMicros)
    }

    @Test
    fun picturesWithoutCaptionsCarryNone() {
        requireTestMedia(base.isFile, "no $MEDIA; run scripts/testmedia.sh")
        val pictures = decodeAll(base)
        assertEquals(90, pictures.size)
        assertTrue(pictures.all { it.captions == null })
    }

    private companion object {
        const val CAPTION_INDEX = 1 shl 24
        const val MEDIA = "cc-base.h264"
    }
}
