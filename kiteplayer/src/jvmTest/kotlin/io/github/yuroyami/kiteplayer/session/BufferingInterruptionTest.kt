package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaIoFactory
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PipedMediaIo
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.from
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A player waiting for data still counts as playing for the session guards, because the engine
 * starts the sound by itself the moment the data arrives (#226).
 */
class BufferingInterruptionTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun aNoisyRoutePausesABufferingPlayer() = runBlocking {
        val file = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")
        val player = KitePlayerPlatform.createOrNull() ?: return@runBlocking println("SKIP: no desktop player")
        try {
            val bytes = file.readBytes()
            val pipe = PipedMediaIo()
            // About two seconds of the file and then nothing: the player plays it, then waits.
            pipe.write(bytes, 0, minOf(32 * 1024, bytes.size))
            player.open(MediaItem.from(MediaIoFactory { pipe }, MEDIA))
            player.play()
            val buffering = withTimeoutOrNull(20.seconds) {
                player.state.first { it.status == PlaybackStatus.Buffering }
            }
            assertNotNull(buffering, "the player never ran out of data, so this proves nothing")

            val target = PlayerSessionTarget(player)
            assertTrue(target.playing, "a buffering player is about to make sound, so it counts as playing")
            InterruptionApplier(target, InterruptionPolicy()).handle(InterruptionEvent.BecameNoisy)
            val paused = withTimeoutOrNull(5.seconds) {
                player.state.first { it.status == PlaybackStatus.Paused }
            }
            assertNotNull(paused, "headphones coming out did not pause a buffering player")
            pipe.finish()
        } finally {
            player.closeAndAwait()
        }
    }

    private companion object {
        const val MEDIA = "audio-mp3.mp3"
    }
}
