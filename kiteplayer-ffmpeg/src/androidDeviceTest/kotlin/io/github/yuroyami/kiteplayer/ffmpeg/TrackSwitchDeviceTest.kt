package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scratch repro: full player on a real device, switch audio and subtitles on multitrack.mkv. */
internal class TrackSwitchDeviceTest {

    private suspend fun step(player: KitePlayer, name: String, act: suspend () -> TrackChange) {
        val change = withTimeout(30_000) { act() }
        val t = player.state.value.tracks
        println("STEP $name: $change audio=${t.selectedAudio} sub=${t.selectedSubtitle} status=${player.state.value.status}")
        assertTrue(change is TrackChange.Applied, "$name was $change")
        delay(300)
    }

    @Test
    fun switchingTracksOnDeviceWorks() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("no media dir on this device")
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(
                    backend = KiteFFmpegMediaBackend(onWarning = { println("BACKEND WARN ${it.message}") }),
                    output = AndroidOutputBackend,
                ),
            ),
        )
        try {
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/multitrack.mkv")) }
            val tracks = player.state.value.tracks
            println("TRACKS: ${tracks.all.map { "${it.id} ${it.kind} ${it.language}" }}")
            player.play()
            delay(500)

            val otherAudio = tracks.audio.first { it.id != tracks.selectedAudio }
            step(player, "audio->jpn") { player.selectTrack(TrackKind.Audio, otherAudio.id) }
            val subs = player.state.value.tracks.subtitles
            val otherSub = subs.first { it.id != player.state.value.tracks.selectedSubtitle }
            step(player, "sub->other") { player.selectTrack(TrackKind.Subtitle, otherSub.id) }
            step(player, "sub->off") { player.selectTrack(TrackKind.Subtitle, null) }
            step(player, "audio->eng") { player.selectTrack(TrackKind.Audio, tracks.audio.first().id) }

            delay(500)
            val status = player.state.value.status
            println("FINAL status=$status audio=${player.state.value.tracks.selectedAudio} warnings=${player.warningHistory().map { it.warning.message }}")
            assertTrue(
                status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering,
                "player should still be playing after switches, is $status",
            )
        } finally {
            player.closeAndAwait()
        }
    }
}
