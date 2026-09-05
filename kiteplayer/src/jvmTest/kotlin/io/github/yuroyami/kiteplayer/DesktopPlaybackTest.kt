package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The end-to-end desktop proof: one dependency line, a real file, real audio out, real progress.
 *
 * Everything else in this phase tests a layer. This tests the assembly the way a consumer meets it:
 * `KitePlayerPlatform.createOrNull()`, `open`, `play`, and the clock moving. It opens a real device,
 * so it skips itself when the machine has no audio mixer rather than failing for the wrong reason.
 */
class DesktopPlaybackTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun theDefaultDesktopStackPlaysARealFileAndTheClockMoves() = runBlocking {
        val file = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")

        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        try {
            player.open(MediaItem(file.absolutePath))
            player.play()

            // Two seconds of wall clock is enough to prove the pipeline turns; the assertion is on
            // the engine's own position, not on a sleep, so a stalled pipeline fails rather than
            // passing slowly.
            val advanced = withTimeoutOrNull(20.seconds) {
                while (player.progress.value.position.inWholeMilliseconds < 1_000) {
                    kotlinx.coroutines.delay(50)
                }
                true
            }
            assertTrue(advanced == true, "the position never reached 1s: ${player.progress.value}")

            val snapshot = player.state.value
            assertTrue(
                snapshot.tracks.all.any { it.kind == TrackKind.Video },
                "no video track was reported: ${snapshot.tracks.all}",
            )
            assertNotNull(snapshot.tracks.selectedAudio, "no audio track was selected")
            println(
                "desktop played to ${player.progress.value.position}, " +
                    "tracks=${snapshot.tracks.all.size}, size=${snapshot.videoSize}",
            )
        } finally {
            player.closeAndAwait()
        }
    }

    private companion object {
        const val MEDIA = "sync1080p30.mp4"
    }
}
