package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.mutableStateOf
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration

/**
 * A player that writes down every command the controls give, and answers as a player would: a
 * setting it is given shows in its state at once.
 */
internal class ScriptedTarget(snapshot: PlayerSnapshot = PlayerSnapshot(), progress: Progress = Progress()) : ControlsTarget {
    val snapshot = mutableStateOf(snapshot)
    val progress = mutableStateOf(progress)
    val calls = mutableListOf<String>()
    var thumbnails: (Duration) -> StreamThumbnail? = { null }
    var refuseEverything = false

    fun source(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)): ControlsSource =
        ControlsSource(this, snapshot, progress, scope)

    private fun record(call: String) {
        if (refuseEverything) throw IllegalStateException("the player is closed")
        synchronized(calls) { calls += call }
    }

    fun callsSoFar(): List<String> = synchronized(calls) { calls.toList() }

    override fun position(): Duration = progress.value.position
    override fun play() = record("play")
    override fun pause() = record("pause")
    override fun requestSeek(to: Duration, mode: SeekMode) = record("seek $to $mode")

    override fun setSpeed(value: Double) {
        record("speed $value")
        snapshot.value = snapshot.value.copy(speed = value)
    }

    override fun setVolume(value: Float) {
        record("volume $value")
        snapshot.value = snapshot.value.copy(volume = value)
    }

    override fun setMuted(value: Boolean) {
        record("muted $value")
        snapshot.value = snapshot.value.copy(muted = value)
    }

    override fun setSubtitlePosition(value: Float) {
        record("subtitles $value")
        snapshot.value = snapshot.value.copy(subtitlePosition = value)
    }

    override suspend fun next() = record("next")
    override suspend fun previous() = record("previous")
    override suspend fun selectTrack(kind: TrackKind, track: TrackId?) = record("select $kind $track")
    override suspend fun selectVariant(index: Int?) = record("variant $index")

    override suspend fun thumbnailAt(position: Duration): StreamThumbnail? {
        record("thumbnail $position")
        return thumbnails(position)
    }
}
