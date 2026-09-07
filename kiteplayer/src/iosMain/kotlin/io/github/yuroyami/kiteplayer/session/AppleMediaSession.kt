package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into the iOS now playing card and routes its buttons back.
 *
 * That card is what the lock screen, the control centre and CarPlay show, and the buttons are what
 * a headset, a steering wheel and a watch send. Both are process-wide on iOS, so build one of
 * these for the player the listener is hearing, and close it before building another.
 *
 * The audio session category the card needs is already the one the player's own output sets, so
 * nothing has to change there. The application still declares the background audio capability
 * itself if it wants the card to survive locking the screen.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val handlers = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var artwork: MPMediaItemArtwork? = null
    private var last: MediaSessionState? = null

    /** Apple has no token to hand out; the card is process-wide. Always null. */
    public val platformToken: Any? = null

    init {
        wireCommands()
        scope.launch {
            combine(player.state, player.progress) { snapshot, progress ->
                snapshot.toMediaSessionState(progress)
            }.distinctUntilChanged().collect(::push)
        }
    }

    /**
     * The picture the card shows. The application supplies it: the engine reads a file's cover art
     * but does not decode it, so there is nothing here to hand over on its own.
     */
    public fun setArtwork(image: UIImage?) {
        artwork = image?.let { MPMediaItemArtwork(it) }
        last?.let(::push)
    }

    private fun push(state: MediaSessionState) {
        last = state
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to (state.title ?: ""),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to
                NSNumber.numberWithDouble(state.position.toDouble(kotlin.time.DurationUnit.SECONDS)),
            // The card extrapolates from this rate, so a paused player must report zero or its
            // position walks on while nothing is playing.
            MPNowPlayingInfoPropertyPlaybackRate to
                NSNumber.numberWithDouble(if (state.playing) state.speed else 0.0),
        )
        state.artist?.let { info[MPMediaItemPropertyArtist] = it }
        state.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        // A live stream has no length. Sending zero would draw a scrub bar with nowhere to go.
        state.duration?.let {
            info[MPMediaItemPropertyPlaybackDuration] =
                NSNumber.numberWithDouble(it.toDouble(kotlin.time.DurationUnit.SECONDS))
        }
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        infoCenter.nowPlayingInfo = info

        commands.nextTrackCommand.enabled = state.hasNext
        commands.previousTrackCommand.enabled = state.hasPrevious
        commands.changePlaybackPositionCommand.enabled = state.canSeek
        commands.skipForwardCommand.enabled = state.canSeek
        commands.skipBackwardCommand.enabled = state.canSeek
    }

    private fun wireCommands() {
        commands.skipForwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(SKIP_SECONDS))
        commands.skipBackwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(SKIP_SECONDS))

        handle(commands.playCommand) { player.play() }
        handle(commands.pauseCommand) { player.pause() }
        handle(commands.togglePlayPauseCommand) {
            if (player.state.value.status == io.github.yuroyami.kiteplayer.PlaybackStatus.Playing) {
                player.pause()
            } else {
                player.play()
            }
        }
        handle(commands.stopCommand) { player.pause() }
        handle(commands.nextTrackCommand) { scope.launch { runCatching { player.next() } } }
        handle(commands.previousTrackCommand) { scope.launch { runCatching { player.previous() } } }
        handle(commands.skipForwardCommand) { skipBy(SKIP_SECONDS.seconds) }
        handle(commands.skipBackwardCommand) { skipBy(-SKIP_SECONDS.seconds) }

        val seek = commands.changePlaybackPositionCommand
        val seekHandler: (platform.MediaPlayer.MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus = { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (at == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                scope.launch { runCatching { player.seek(at.seconds) } }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        handlers += seek to seek.addTargetWithHandler(seekHandler)
    }

    private fun handle(command: MPRemoteCommand, action: () -> Unit) {
        handlers += command to command.addTargetWithHandler {
            action()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun skipBy(by: Duration) {
        scope.launch {
            val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
            runCatching { player.seek(target) }
        }
    }

    override fun close() {
        scope.cancel()
        handlers.forEach { (command, target) -> command.removeTarget(target) }
        handlers.clear()
        infoCenter.nowPlayingInfo = null
    }

    private companion object {
        const val SKIP_SECONDS = 15.0
    }
}
