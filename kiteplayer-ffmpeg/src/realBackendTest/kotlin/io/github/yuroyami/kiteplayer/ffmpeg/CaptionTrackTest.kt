package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A caption track of its own decodes to text cues. The MOV's only track is EIA-608 (c608), made
 * with ffmpeg from Scenarist SCC lines that pop up HELLO at 0.33 s and erase it at 1.33 s; a last
 * line of padding keeps the erase in the file, because the MOV muxer drops a final packet that
 * has no duration.
 */
class CaptionTrackTest {

    @Test
    fun anEia608TrackDecodesToATextCueForItsCaption() = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(CAPTION_TRACK), label = "captions.mov")
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Subtitle }, "no caption stream")
            assertEquals("eia_608", stream.codec)
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(KiteFFmpegCaptionDecoderFactory(source).create(stream))
            val cues = ArrayList<SubtitleCue>()
            try {
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        decoder.send(packet)
                    } finally {
                        packet.close()
                    }
                    cues += decoder.receive()
                }
            } finally {
                decoder.close()
            }
            val caption = cues.single() as SubtitleCue.Text
            assertEquals("HELLO", caption.plainText)
            assertEquals(1_000_000L, caption.endMicros - caption.startMicros, "the caption lasts until the erase a second later")
        } finally {
            source.close()
        }
    }

    @Test
    fun theLastCaptionComesOutOfTheDrainAtTheEndOfTheTrack() = runBlocking {
        // HELLO pops up at one second and nothing erases it. FFmpeg's caption decoder gives a
        // caption when the screen next changes, so no packet completes this one and only the
        // null packet the engine sends at the end of the stream does (#480).
        val item = MediaItem.from(MediaIo.ofBytes(HELD_CAPTION.encodeToByteArray()), label = "held.scc")
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Subtitle }, "no caption stream")
            assertEquals("eia_608", stream.codec)
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(KiteFFmpegCaptionDecoderFactory(source).create(stream))
            try {
                val decoded = ArrayList<SubtitleCue>()
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        decoder.send(packet)
                    } finally {
                        packet.close()
                    }
                    decoded += decoder.receive()
                }
                assertEquals(emptyList(), decoded, "a packet completed the caption nothing erases")
                assertTrue(decoder.send(null))
                val caption = decoder.receive().single() as SubtitleCue.Text
                assertEquals("HELLO", caption.plainText)
                assertTrue(caption.endMicros > caption.startMicros, "the drained caption has no length")
                assertEquals(emptyList(), decoder.receive())
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }

    private companion object {
        /** One pop-on caption, HELLO at one second, that nothing erases, as Scenarist SCC text. */
        const val HELD_CAPTION =
            "Scenarist_SCC V1.0\n\n00:00:01:00\t9420 9420 94ae 94ae 9440 9440 c845 4c4c 4f80 942f 942f\n\n"

        val CAPTION_TRACK: ByteArray = (
            "00000014667479707174202000000200717420200000000877696465000000466d64617400000026636461749420" +
            "942094ae94ae9452945297a197a1c8454c4c4f80942c942c942f942f0000000c63646174942c942c0000000c6364" +
            "617480808080000002756d6f6f760000006c6d766864000000000000000000000000000003e80000091a00010000" +
            "01000000000000000000000000010000000000000000000000000000000100000000000000000000000000004000" +
            "000000000000000000000000000000000000000000000000000000000002000002017472616b0000005c746b6864" +
            "00000003000000000000000000000001000000000000091a00000000000000000000000300000000000100000000" +
            "00000000000000000000000100000000000000000000000000004000000000000000000000000000003065647473" +
            "00000028656c737400000000000000020000014affffffff00010000000007d000000000000100000000016d6d64" +
            "6961000000206d646864000000000000000000000000000003e8000007d07fff00000000003568646c7200000000" +
            "6d686c72636c637000000000000000000000000014436c6f73656443617074696f6e48616e646c6572000001106d" +
            "696e6600000020676d686400000018676d696e000000000040800080008000000000000000002c68646c72000000" +
            "0064686c7275726c200000000000000000000000000b4461746148616e646c65720000002464696e660000001c64" +
            "72656600000000000000010000000c75726c2000000001000000987374626c000000207374736400000000000000" +
            "01000000106336303800000000000000010000002073747473000000000000000200000002000003e80000000100" +
            "0000000000001c737473630000000000000001000000010000000300000001000000207374737a00000000000000" +
            "0000000003000000260000000c0000000c000000147374636f000000000000000100000024"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
