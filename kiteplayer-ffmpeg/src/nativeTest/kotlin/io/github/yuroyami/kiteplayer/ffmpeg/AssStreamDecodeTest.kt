@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Kotlin ASS dialogue tier end to end (17.12 M2): a real Matroska ASS track opens, the
 * factory accepts it (it refused ASS until this tier), the header rides the codec extradata,
 * and packets decode into styled text cues over the packet path with no C engine involved.
 */
class AssStreamDecodeTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    @Test
    fun `an embedded ass track decodes into styled cues`() = runBlocking {
        val session = KiteFFmpegMediaBackend().open(MediaItem("$mediaDir/asssubbed.mkv"))
        try {
            val source = session.source
            val assStream = source.streams.firstOrNull {
                it.kind == TrackKind.Subtitle && it.codec == "ass"
            } ?: error("asssubbed.mkv lost its ass stream")

            val decoder = session.subtitleDecoders.firstNotNullOfOrNull { it.create(assStream) }
                ?: error("the factory refused the ass stream: the M2 tier is not wired")

            source.selectStreams(setOf(assStream.index))
            val cues = mutableListOf<SubtitleCue>()
            var packets = 0
            while (packets < 50 && cues.isEmpty()) {
                val packet = source.readPacket() ?: break
                packets++
                if (packet.streamIndex == assStream.index) {
                    decoder.send(packet)
                    cues += decoder.receive()
                }
                (packet as? AutoCloseable)?.close()
            }
            assertTrue(cues.isNotEmpty(), "no cue decoded from $packets ass packets")
            val text = cues.filterIsInstance<SubtitleCue.Text>().firstOrNull()
                ?: error("the ass cue was not a text cue")
            assertTrue(text.plainText.isNotBlank(), "the decoded cue has no visible text")
            assertTrue(text.endMicros > text.startMicros, "the cue has no time window")
            decoder.close()
        } finally {
            session.close()
        }
    }
}
