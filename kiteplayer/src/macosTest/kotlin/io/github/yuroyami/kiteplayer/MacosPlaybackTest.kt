@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS native proof, the way a consumer meets it: one dependency on `kiteplayer`,
 * `KitePlayerPlatform.createOrNull()`, a real file, real audio out and the clock moving.
 */
class MacosPlaybackTest {

    private val media: String? = listOfNotNull(getenv("KITEPLAYER_TESTMEDIA")?.toKString(), "testmedia")
        .map { "$it/$MEDIA" }
        .firstOrNull { access(it, F_OK) == 0 }

    @Test
    fun theDefaultMacosStackPlaysARealFileAndTheClockMoves() = runBlocking<Unit> {
        val path = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default macOS player: ${KitePlayerPlatform.availability}")
        try {
            player.open(MediaItem(path))
            player.play()
            val playing = withTimeoutOrNull(20.seconds) { player.state.first { it.status == PlaybackStatus.Playing } }
            assertNotNull(playing, "the player never reached Playing: ${player.state.value.status}")

            // The assertion is on the engine's own position, so a stalled pipeline fails rather
            // than passing slowly.
            val advanced = withTimeoutOrNull(20.seconds) {
                while (player.progress.value.position.inWholeMilliseconds < 1_000) delay(50)
                true
            }
            assertTrue(advanced == true, "the position never reached 1s: ${player.progress.value}")

            val snapshot = player.state.value
            assertTrue(snapshot.tracks.all.any { it.kind == TrackKind.Video }, "no video track: ${snapshot.tracks.all}")
            assertNotNull(snapshot.tracks.selectedAudio, "no audio track was selected")
        } finally {
            player.closeAndAwait()
        }
    }

    private companion object {
        const val MEDIA = "sync1080p30.mp4"
    }
}
