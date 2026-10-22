package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * Two players in one process, proven rather than assumed. A preview beside the main
 * picture, a second stream in a corner, a crossfade someday: nothing in the engine is a
 * singleton except the log, so this should work, and this is the test that says it does.
 */
class TwoPlayersTest {

    private fun media(name: String): File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, name) },
        File("testmedia/$name"),
        File("../testmedia/$name"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun twoPlayersPlayAtOnceAndClosingOneLeavesTheOther() = runBlocking {
        val first = requireTestMedia(media("sync1080p30.mp4"), "no sync1080p30.mp4; run scripts/testmedia.sh")
        val second = requireTestMedia(media("truevfr720.mp4"), "no truevfr720.mp4; run scripts/testmedia.sh")
        assumeTrue("no audio mixer on this host", AudioSystem.getMixerInfo().isNotEmpty())

        val a = KitePlayer()
        val b = KitePlayer()
        try {
            a.open(MediaItem(first.absolutePath))
            b.open(MediaItem(second.absolutePath))
            a.play()
            b.play()

            val bothAdvanced = withTimeoutOrNull(20.seconds) {
                while (
                    a.progress.value.position.inWholeMilliseconds < 1_000 ||
                    b.progress.value.position.inWholeMilliseconds < 1_000
                ) {
                    kotlinx.coroutines.delay(50)
                }
                true
            }
            assertTrue(
                bothAdvanced == true,
                "both players must pass 1s; a=${a.progress.value.position} b=${b.progress.value.position}",
            )

            // Closing one must not stop the other: the survivor's clock keeps moving.
            a.closeAndAwait()
            val before = b.progress.value.position
            val stillAdvancing = withTimeoutOrNull(10.seconds) {
                while (b.progress.value.position <= before) {
                    kotlinx.coroutines.delay(50)
                }
                true
            }
            assertTrue(
                stillAdvancing == true,
                "the second player stalled at $before after the first closed",
            )
        } finally {
            runCatching { a.closeAndAwait() }
            b.closeAndAwait()
        }
    }
}
