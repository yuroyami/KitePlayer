@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scratch repro: full player, real backend, switch audio then subtitle on multitrack.mkv. */
class TrackSwitchReproTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private suspend fun step(player: KitePlayer, name: String, act: suspend () -> TrackChange) {
        val change = withTimeout(30_000) { act() }
        val t = player.state.value.tracks
        println("STEP $name: $change audio=${t.selectedAudio} sub=${t.selectedSubtitle} status=${player.state.value.status}")
        assertTrue(change is TrackChange.Applied, "$name was $change")
        delay(300)
    }

    @Test
    fun switchingAudioAndSubtitleTracksWorks() = runBlocking {
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(
                    backend = KiteCodecMediaBackend(onWarning = { println("BACKEND WARN ${it.message}") }),
                    output = AppleOutputBackend,
                ),
            ),
        )
        try {
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/multitrack.mkv")) }
            val tracks = player.state.value.tracks
            println("TRACKS: ${tracks.all.map { "${it.id} ${it.kind} ${it.language}" }}")
            println("SELECTED audio=${tracks.selectedAudio} sub=${tracks.selectedSubtitle}")
            player.play()
            delay(500)

            val otherAudio = tracks.audio.first { it.id != tracks.selectedAudio }
            val audioChange = withTimeout(30_000) { player.selectTrack(TrackKind.Audio, otherAudio.id) }
            println("AUDIO CHANGE: $audioChange -> selected=${player.state.value.tracks.selectedAudio} status=${player.state.value.status}")
            assertTrue(audioChange is TrackChange.Applied, "audio switch was $audioChange")
            assertTrue(
                player.state.value.tracks.selectedAudio == otherAudio.id,
                "selectedAudio is ${player.state.value.tracks.selectedAudio}, wanted ${otherAudio.id}",
            )

            delay(500)
            // Real switch: the OTHER subtitle stream, then off, then back, then audio off/on,
            // then a paused switch, then a rapid pair. The wedge in the field is somewhere in here.
            val subs = player.state.value.tracks.subtitles
            val otherSub = subs.first { it.id != player.state.value.tracks.selectedSubtitle }
            step(player, "sub->other") { player.selectTrack(TrackKind.Subtitle, otherSub.id) }
            step(player, "sub->off") { player.selectTrack(TrackKind.Subtitle, null) }
            step(player, "sub->back") { player.selectTrack(TrackKind.Subtitle, subs.first().id) }
            step(player, "audio->off") { player.selectTrack(TrackKind.Audio, null) }
            step(player, "audio->eng") { player.selectTrack(TrackKind.Audio, tracks.audio.first().id) }

            player.pause()
            delay(200)
            step(player, "paused audio->jpn") { player.selectTrack(TrackKind.Audio, otherAudio.id) }
            player.play()
            delay(300)

            // Rapid pair: the second must supersede or apply, never wedge.
            coroutineScope {
                val rapid1 = async {
                    player.selectTrack(TrackKind.Audio, tracks.audio.first().id)
                }
                val rapid2 = withTimeout(30_000) { player.selectTrack(TrackKind.Audio, otherAudio.id) }
                val rapid1Result = withTimeout(30_000) { rapid1.await() }
                println("RAPID: first=$rapid1Result second=$rapid2")
            }

            delay(500)
            val status = player.state.value.status
            println("FINAL status=$status selectedAudio=${player.state.value.tracks.selectedAudio} warnings=${player.warningHistory().map { it.warning.message }}")
            assertTrue(
                status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering,
                "player should still be playing after switches, is $status",
            )
        } finally {
            player.closeAndAwait()
        }
    }
}
