package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player on a video whose pictures carry closed captions (#236): the track CC1 appears
 * once the first captioned pictures are decoded, and choosing it shows the caption. The stream is
 * `cc-base.h264` with an A/53 caption message written before every picture, a pop-on HELLO.
 */
class CaptionsInsideVideoPlaybackTest {

    private val base: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    private val captioned = File.createTempFile("captioned", ".h264")

    @AfterTest
    fun cleanup() {
        captioned.delete()
    }

    private fun odd(byte: Int): Int = if (Integer.bitCount(byte and 0x7F) % 2 == 0) byte or 0x80 else byte

    private fun captionSei(index: Int): ByteArray {
        val (a, b) = when (index) {
            0, 1 -> 0x14 to 0x20
            2 -> 'H'.code to 'E'.code
            3 -> 'L'.code to 'L'.code
            4 -> 'O'.code to 0
            5, 6 -> 0x14 to 0x2F
            in 80..81 -> 0x14 to 0x2C
            else -> 0 to 0
        }
        return byteArrayOf(
            0, 0, 0, 1, 0x06, 0x04, 0x0E,
            0xB5.toByte(), 0x00, 0x31, 0x47, 0x41, 0x39, 0x34, 0x03, 0xC1.toByte(), 0xFF.toByte(),
            0xFC.toByte(), odd(a).toByte(), odd(b).toByte(), 0xFF.toByte(),
            0x80.toByte(),
        )
    }

    private fun writeCaptioned(bytes: ByteArray): File {
        val starts = (0 until bytes.size - 3).filter { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() && bytes[it + 2] == 1.toByte() }
        val out = ByteArrayOutputStream()
        var picture = 0
        for ((n, start) in starts.withIndex()) {
            val from = if (start > 0 && bytes[start - 1] == 0.toByte()) start - 1 else start
            val end = starts.getOrNull(n + 1)?.let { next -> if (bytes[next - 1] == 0.toByte()) next - 1 else next } ?: bytes.size
            val type = bytes[start + 3].toInt() and 0x1F
            if (type == 1 || type == 5) out.write(captionSei(picture++))
            out.write(bytes, from, end - from)
        }
        captioned.writeBytes(out.toByteArray())
        return captioned
    }

    @Test
    fun theCaptionTrackAppearsAndChoosingItShowsTheCaption() = runBlocking {
        val file = writeCaptioned(requireTestMedia(base, "no $MEDIA to play; run scripts/testmedia.sh").readBytes())
        val player = KitePlayer()
        try {
            player.open(MediaItem(file.path, formatHint = "h264"))
            player.play()
            val track = withTimeout(10.seconds) {
                while (true) {
                    player.state.value.tracks.all.firstOrNull { it.kind == TrackKind.Subtitle && it.title == "CC1" }?.let { return@withTimeout it }
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertIs<TrackChange.Applied>(player.selectTrack(TrackKind.Subtitle, track.id))
            val shown = withTimeout(10.seconds) {
                while (true) {
                    player.subtitleCues.value.filterIsInstance<SubtitleCue.Text>()
                        .firstOrNull { cue -> cue.spans.joinToString("") { it.text }.contains("HELLO") }
                        ?.let { return@withTimeout it }
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertEquals("HELLO", shown.spans.joinToString("") { it.text }.trim())
        } finally {
            withTimeout(15.seconds) { player.closeAndAwait() }
        }
    }

    private companion object {
        const val MEDIA = "cc-base.h264"
    }
}
