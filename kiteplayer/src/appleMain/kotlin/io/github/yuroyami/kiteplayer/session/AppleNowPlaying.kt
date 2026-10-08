package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.CoverArt
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The now playing card and its buttons, for iOS and macOS alike: both use the MediaPlayer
 * framework. Each platform's `KitePlayerMediaSession` owns one and adds what only it has, which
 * is the picture type on both and the playing state on macOS.
 *
 * The card and the buttons are process-wide, so one of these owns them at a time. Building a
 * second one takes them over: the first one's buttons are withdrawn and it stops writing, and
 * closing it later leaves the new owner's card alone. Its player keeps playing.
 *
 * Every call belongs to the main thread, the constructor included.
 *
 * @param coverArtwork decodes an item's own cover into the platform's picture, or answers null.
 * @param onPlayback runs with each playback state the card is given. macOS tells the info centre
 *        whether it plays there; iOS has no such property.
 * @param onRelease runs when the owner closes and the card is cleared.
 */
internal class AppleNowPlaying(
    private val player: KitePlayer,
    private val skipInterval: Duration,
    private val coverArtwork: (CoverArt) -> MPMediaItemArtwork?,
    private val onPlayback: (MediaSessionState) -> Unit = {},
    private val onRelease: () -> Unit = {},
) {

    init {
        require(skipInterval.isPositive()) { "the skip interval must be positive, was $skipInterval" }
    }

    /** Lives until [close], so a session whose card was taken over still closes with its player. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The two collectors that feed the card. They stop when the card is taken over. */
    private val feeds = mutableListOf<Job>()
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val handlers = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private val artwork = MutableStateFlow<MPMediaItemArtwork?>(null)

    /** The item's own cover, decoded, shown while the application gives no picture of its own (#425). */
    private val fileArtwork = MutableStateFlow<MPMediaItemArtwork?>(null)

    /** The dictionary the card reads. Both halves live in it, so every write sends it whole. */
    private val info = mutableMapOf<Any?, Any?>()

    /** The newest state seen, whether or not it was pushed. */
    private var latest: MediaSessionState? = null
    private val mirror = MediaSessionMirror<MPMediaItemArtwork>(::pushMetadata, ::pushPlayback)

    /** True once another session took the card over, or this one closed. Nothing is written after. */
    private var retired = false

    init {
        owner?.retire()
        owner = this
        wireCommands()
        feeds += scope.launch {
            combine(player.state, player.progress, artwork, fileArtwork) { snapshot, progress, own, file ->
                snapshot.toMediaSessionState(progress) to (own ?: file)
            }.collect { (state, picture) -> show(state, picture) }
        }
        feeds += scope.launch {
            player.coverArt.collect { cover -> fileArtwork.value = cover?.let(coverArtwork) }
        }
    }

    /** Whether this session owns the card and the buttons now. */
    val ownsCard: Boolean get() = owner === this

    /** The picture the application chose, or null to go back to the item's own cover. */
    fun setArtwork(picture: MPMediaItemArtwork?) {
        artwork.value = picture
    }

    /** Gives the card one state. The session's own collector calls this; a test calls it directly. */
    internal fun show(state: MediaSessionState, picture: MPMediaItemArtwork?) {
        if (retired) return
        latest = state
        mirror.update(state, picture)
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
        // Previous also starts the item again, so it is there whenever the item can seek (#424).
        commands.previousTrackCommand.enabled = state.offersPrevious
        commands.changePlaybackPositionCommand.enabled = state.canSeek
        commands.skipForwardCommand.enabled = state.canSeek
        commands.skipBackwardCommand.enabled = state.canSeek
        onPlayback(state)
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
        commands.skipForwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(skipInterval.toDouble(DurationUnit.SECONDS)))
        commands.skipBackwardCommand.preferredIntervals = listOf(NSNumber.numberWithDouble(skipInterval.toDouble(DurationUnit.SECONDS)))

        handle(commands.playCommand) { player.playFromRemote() }
        handle(commands.pauseCommand) { player.pauseFromRemote() }
        // Buffering counts as playing here: the listener asked for sound, so a toggle means stop.
        handle(commands.togglePlayPauseCommand) {
            if (player.state.value.status.isActive) player.pauseFromRemote() else player.playFromRemote()
        }
        handle(commands.stopCommand) { player.pauseFromRemote() }
        handle(commands.nextTrackCommand) { scope.launch { runCatching { player.next() } } }
        handle(commands.previousTrackCommand) { scope.launch { runCatching { player.pressPrevious() } } }
        handle(commands.skipForwardCommand) { skipBy(skipInterval) }
        handle(commands.skipBackwardCommand) { skipBy(-skipInterval) }

        val seek = commands.changePlaybackPositionCommand
        val seekHandler: (MPRemoteCommandEvent?) -> MPRemoteCommandHandlerStatus = { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (at == null || retired) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                scope.launch { runCatching { player.seek(at.seconds) } }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        handlers += seek to seek.addTargetWithHandler(seekHandler)
    }

    /** A press that arrives after the buttons were withdrawn is refused, so a late one controls nothing. */
    private fun handle(command: MPRemoteCommand, action: () -> Unit) {
        handlers += command to command.addTargetWithHandler {
            if (retired) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                action()
                MPRemoteCommandHandlerStatusSuccess
            }
        }
    }

    private fun skipBy(by: Duration) {
        scope.launch {
            val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
            runCatching { player.seek(target) }
        }
    }

    /** Withdraws the buttons and stops writing. The card is left to whoever owns it next. */
    private fun retire() {
        if (retired) return
        retired = true
        feeds.forEach { it.cancel() }
        feeds.clear()
        handlers.forEach { (command, target) -> command.removeTarget(target) }
        handlers.clear()
        info.clear()
    }

    /** Withdraws the buttons, and clears the card when this session still owns it. */
    fun close() {
        val owned = ownsCard
        retire()
        scope.cancel()
        if (!owned) return
        owner = null
        infoCenter.nowPlayingInfo = null
        onRelease()
    }

    private companion object {
        /** The session that owns the process-wide card. Main thread only. */
        var owner: AppleNowPlaying? = null
    }
}
