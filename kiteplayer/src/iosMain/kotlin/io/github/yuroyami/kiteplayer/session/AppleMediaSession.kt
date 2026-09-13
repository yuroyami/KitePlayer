package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSNumber
import platform.Foundation.numberWithBool
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithUnsignedLong
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeVideo
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyIsLiveStream
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

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
    private val artwork = MutableStateFlow<MPMediaItemArtwork?>(null)

    /** The dictionary the card reads. Both halves live in it, so every write sends it whole. */
    private val info = mutableMapOf<Any?, Any?>()

    /** The newest state seen, whether or not it was pushed. */
    private var latest: MediaSessionState? = null
    private val mirror = MediaSessionMirror<MPMediaItemArtwork>(::pushMetadata, ::pushPlayback)

    /** Apple has no token to hand out; the card is process-wide. Always null. */
    public val platformToken: Any? = null

    /** Always true here. Other platforms answer false when they have no session, so one check works everywhere. */
    public val isAvailable: Boolean = true

    init {
        wireCommands()
        scope.launch {
            combine(player.state, player.progress, artwork) { snapshot, progress, picture ->
                snapshot.toMediaSessionState(progress) to picture
            }.collect { (state, picture) ->
                latest = state
                mirror.update(state, picture)
            }
        }
    }

    /**
     * The picture the card shows. The application supplies it: the engine reads a file's cover art
     * but does not decode it, so there is nothing here to hand over on its own.
     */
    @OptIn(ExperimentalForeignApi::class)
    public fun setArtwork(image: UIImage?) {
        artwork.value = image?.let { picture -> MPMediaItemArtwork(boundsSize = picture.size) { _ -> picture } }
    }

    /** The slow half: title, artist, album, length, kind and picture. */
    private fun pushMetadata(metadata: MediaSessionMetadata, picture: MPMediaItemArtwork?) {
        info[MPMediaItemPropertyTitle] = metadata.title ?: ""
        info[MPMediaItemPropertyArtist] = metadata.artist
        info[MPMediaItemPropertyAlbumTitle] = metadata.album
        // A live stream has no length. The live flag tells the card to draw no scrub bar at all.
        info[MPMediaItemPropertyPlaybackDuration] =
            metadata.duration?.let { NSNumber.numberWithDouble(it.toDouble(DurationUnit.SECONDS)) }
        info[MPNowPlayingInfoPropertyIsLiveStream] = NSNumber.numberWithBool(metadata.duration == null)
        info[MPNowPlayingInfoPropertyMediaType] = NSNumber.numberWithUnsignedLong(
            if (metadata.hasVideo) MPNowPlayingInfoMediaTypeVideo else MPNowPlayingInfoMediaTypeAudio,
        )
        info[MPMediaItemPropertyArtwork] = picture
        // The card walks the position on from the moment the dictionary is set, so this write must
        // carry the position of now and not the one from the last playback push.
        latest?.let(::putPosition)
        writeInfo()
    }

    /** The fast half: position and rate, and the buttons they decide. */
    private fun pushPlayback(state: MediaSessionState) {
        putPosition(state)
        writeInfo()
        commands.nextTrackCommand.enabled = state.hasNext
        commands.previousTrackCommand.enabled = state.hasPrevious
        commands.changePlaybackPositionCommand.enabled = state.canSeek
        commands.skipForwardCommand.enabled = state.canSeek
        commands.skipBackwardCommand.enabled = state.canSeek
    }

    private fun putPosition(state: MediaSessionState) {
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
            NSNumber.numberWithDouble(state.position.toDouble(DurationUnit.SECONDS))
        // The card walks the position on at this rate, so anything but playing must report zero
        // or its position moves while nothing plays. That includes buffering, which it cannot show.
        info[MPNowPlayingInfoPropertyPlaybackRate] =
            NSNumber.numberWithDouble(if (state.playing) state.speed else 0.0)
    }

    /** Null values are dropped: a missing key is how the card spells "not known". */
    private fun writeInfo() {
        infoCenter.nowPlayingInfo = info.filterValues { it != null }
    }

    private fun wireCommands() {
        commands.skipForwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(SKIP_SECONDS))
        commands.skipBackwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(SKIP_SECONDS))

        handle(commands.playCommand) { player.play() }
        handle(commands.pauseCommand) { player.pause() }
        // Buffering counts as playing here: the listener asked for sound, so a toggle means stop.
        handle(commands.togglePlayPauseCommand) {
            if (player.state.value.status.isActive) player.pause() else player.play()
        }
        handle(commands.stopCommand) { player.pause() }
        handle(commands.nextTrackCommand) { scope.launch { runCatching { player.next() } } }
        handle(commands.previousTrackCommand) { scope.launch { runCatching { player.previous() } } }
        handle(commands.skipForwardCommand) { skipBy(SKIP_SECONDS.seconds) }
        handle(commands.skipBackwardCommand) { skipBy(-SKIP_SECONDS.seconds) }

        val seek = commands.changePlaybackPositionCommand
        val seekHandler: (MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus = { event ->
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
        info.clear()
        infoCenter.nowPlayingInfo = null
    }

    private companion object {
        const val SKIP_SECONDS = 15.0
    }
}
