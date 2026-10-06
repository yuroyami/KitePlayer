package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player on a local file that is still being written (#430), a plain path with no reader
 * of its own, as an application names a recording in progress. The first third of the ten second
 * `tsoffset1400.ts` exists at the open and the rest arrives while it plays: playback passes where
 * the file stood at the open, a seek lands in the part written later, and the item ends.
 */
class GrowingFilePlaybackTest {

    private val clip: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    private val written = File.createTempFile("recording", ".ts")

    @AfterTest
    fun cleanup() {
        written.delete()
    }

    @Test
    fun aRecordingInProgressPlaysOnAsItGrowsAndEnds() = runBlocking {
        val bytes = requireTestMedia(clip, "no $MEDIA to play; run scripts/testmedia.sh").readBytes()
        assumeTrue("no audio mixer on this host", AudioSystem.getMixerInfo().isNotEmpty())
        written.writeBytes(bytes.copyOf(bytes.size / 3 / 188 * 188))
        val player = KitePlayer()
        try {
            player.open(MediaItem(written.path, growth = FileGrowth(endsAfter = 1.seconds)))
            val atOpen = player.state.value.duration ?: error("no length at the open")
            assertTrue(player.state.value.durationIsEstimate)
            player.play()
            val writer = thread {
                var at = written.length().toInt()
                while (at < bytes.size) {
                    Thread.sleep(250)
                    val end = minOf(bytes.size, at + 1_000_000)
                    written.appendBytes(bytes.copyOfRange(at, end))
                    at = end
                }
            }
            withTimeout(20.seconds) { while (player.position() < atOpen + 500.milliseconds) delay(50) }
            writer.join()
            player.seek(8.seconds)
            assertTrue(player.position() >= 7.5.seconds, "the seek to 8 s landed at ${player.position()}")
            withTimeout(15.seconds) { while (player.state.value.status != PlaybackStatus.Ended) delay(50) }
            assertTrue(player.state.value.duration!! > 9.seconds, "the length stayed at ${player.state.value.duration}")
            assertEquals(emptyList(), player.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GrowthUnavailable>())
        } finally {
            withTimeout(15.seconds) { player.closeAndAwait() }
        }
    }

    private companion object {
        const val MEDIA = "tsoffset1400.ts"
    }
}
