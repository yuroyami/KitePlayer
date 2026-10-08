package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** The player commands the controls give, so the state holders can be tested against a script. */
internal interface ControlsTarget {
    fun position(): Duration
    fun play()
    fun pause()
    fun requestSeek(to: Duration, mode: SeekMode)
    fun setSpeed(value: Double)
    fun setVolume(value: Float)
    fun setMuted(value: Boolean)
    fun setSubtitlePosition(value: Float)
    suspend fun next()
    suspend fun previous()
    suspend fun selectTrack(kind: TrackKind, track: TrackId?)
    suspend fun selectVariant(index: Int?)
    suspend fun thumbnailAt(position: Duration): StreamThumbnail?
}

private class PlayerTarget(private val player: KitePlayer) : ControlsTarget {
    override fun position(): Duration = player.position()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun requestSeek(to: Duration, mode: SeekMode) = player.requestSeek(to, mode)
    override fun setSpeed(value: Double) = player.setSpeed(value)
    override fun setVolume(value: Float) = player.setVolume(value)
    override fun setMuted(value: Boolean) = player.setMuted(value)
    override fun setSubtitlePosition(value: Float) = player.setSubtitlePosition(value)
    override suspend fun next() = player.next()
    override suspend fun previous() = player.previous()
    override suspend fun selectTrack(kind: TrackKind, track: TrackId?) {
        player.selectTrack(kind, track)
    }
    override suspend fun selectVariant(index: Int?) = player.selectVariant(index)
    override suspend fun thumbnailAt(position: Duration): StreamThumbnail? = player.thumbnailAt(position)
}

/**
 * What every state holder reads and calls: the player's state as Compose state, and its commands.
 *
 * A command the player refuses, because nothing is open, the queue has no next item or the player
 * has closed, is logged through `KiteLog` and otherwise ignored: a control has nobody to throw to.
 */
internal class ControlsSource(
    val target: ControlsTarget,
    private val snapshotState: State<PlayerSnapshot>,
    private val progressState: State<Progress>,
    /**
     * Runs the commands that wait for the player, such as a move to the next item. It is not the
     * composition's scope on purpose: leaving the screen while the next item opens would cancel the
     * wait, and the player stops an open whose caller was cancelled.
     */
    private val commands: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val snapshot: PlayerSnapshot get() = snapshotState.value
    val progress: Progress get() = progressState.value

    fun send(name: String, command: ControlsTarget.() -> Unit) {
        try {
            target.command()
        } catch (refused: IllegalStateException) {
            refusedCommand(name, refused)
        } catch (refused: IllegalArgumentException) {
            refusedCommand(name, refused)
        }
    }

    fun launch(name: String, command: suspend ControlsTarget.() -> Unit) {
        commands.launch {
            try {
                target.command()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                refusedCommand(name, refused)
            }
        }
    }

    private fun refusedCommand(name: String, error: Exception) {
        KiteLog.log(LOG_TAG, "the player refused $name from the controls: ${error.message}")
    }
}

private const val LOG_TAG = "KitePlayerControls"

@Composable
internal fun rememberControlsSource(player: KitePlayer): ControlsSource {
    val snapshot = player.state.collectAsState()
    val progress = player.progress.collectAsState()
    return remember(player, snapshot, progress) { ControlsSource(PlayerTarget(player), snapshot, progress) }
}
