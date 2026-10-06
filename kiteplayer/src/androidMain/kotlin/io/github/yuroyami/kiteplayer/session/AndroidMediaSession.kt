package io.github.yuroyami.kiteplayer.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into Android's media session and routes the buttons back.
 *
 * That session is what the lock screen, the headset buttons, the car and the media area of the
 * quick settings panel all read. Building one is what every application was writing by hand.
 *
 * Most apps get one from [KitePlayer.attachMediaSession], which also adds the media notification,
 * background handling and audio focus, and closes it all with the player. Built with this
 * constructor, the session is only the session, and the app closes it with the player.
 *
 * A button press that arrives after the player closed does nothing.
 *
 * @param skipInterval how far the skip back and skip forward buttons move, on the lock screen, the
 *        notification, a headset and a car. Positive.
 */
public class KitePlayerMediaSession(
    internal val player: KitePlayer,
    context: Context,
    tag: String = "KitePlayer",
    private val skipInterval: Duration = 15.seconds,
) : AutoCloseable {

    init {
        require(skipInterval.isPositive()) { "the skip interval must be positive, was $skipInterval" }
    }

    private val session = MediaSession(context.applicationContext, tag)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artwork = MutableStateFlow<Bitmap?>(null)
    private val artworkLoader = MutableStateFlow<(suspend (PlayerSnapshot) -> Bitmap?)?>(null)

    /** The item's own cover, decoded off the main thread at the size the session draws (#425). */
    private val fileArtwork = MutableStateFlow<Bitmap?>(null)

    private val artworkSide = mediaArtworkSidePx(context)

    /**
     * The picture the session and the notification show: the application's own or its loader's,
     * else the item's own cover, held once per picture to the size they draw (#425).
     */
    private val shownArtwork: StateFlow<Bitmap?> =
        combine(artwork, fileArtwork) { own, file -> (own ?: file)?.let { fittedArtwork(it, artworkSide) } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    private val customActions = MutableStateFlow<List<MediaNotificationAction>>(emptyList())
    private val mirror = MediaSessionMirror<Bitmap>(::pushMetadata, ::pushPlaybackState)
    private val callback = Callback()

    @Volatile
    private var customActionHandler: ((String) -> Unit)? = null

    @Volatile
    private var sessionActivity: PendingIntent? = null

    /** The token a `Notification.MediaStyle` needs. */
    public val platformToken: MediaSession.Token get() = session.sessionToken

    /** Always true here. Other platforms answer false when they have no session, so one check works everywhere. */
    public val isAvailable: Boolean = true

    /** The picture the session shows, for the media notification. */
    internal val artworkState: StateFlow<Bitmap?> get() = shownArtwork

    /** The activity set with [setSessionActivity], or null. */
    internal val sessionActivityIntent: PendingIntent? get() = sessionActivity

    /** The buttons set with [setCustomActions], which the media notification shows too. */
    internal val customActionsState: StateFlow<List<MediaNotificationAction>> get() = customActions

    /** The same door the system's controllers use, for the media notification's buttons. */
    internal val controls: MediaSession.Callback get() = callback

    init {
        session.setCallback(callback, Handler(Looper.getMainLooper()))
        session.isActive = true
        // One collector writes both halves in order, so artwork a caller sets cannot be overwritten
        // by a write that started before it.
        scope.launch {
            var publishedActions = customActions.value
            combine(
                player.state,
                player.progress,
                shownArtwork,
                customActions,
            ) { snapshot, progress, image, actions ->
                Triple(snapshot.toMediaSessionState(progress), image, actions)
            }.collect { (state, image, actions) ->
                mirror.update(state, image)
                // The mirror pushes the playback half only when the transport changes, and the
                // custom actions are not part of it, so a change there pushes on its own.
                if (actions != publishedActions) {
                    publishedActions = actions
                    pushPlaybackState(state)
                }
            }
        }
        scope.launch { loadArtwork() }
        scope.launch {
            player.coverArt.collectLatest { cover ->
                fileArtwork.value = cover?.let { runCatching { decodeCover(it.bytes, artworkSide) }.getOrNull() }
            }
        }
    }

    /**
     * The picture the session shows, over the item's own cover, which shows when this is null and
     * no loader gives one (#425). A loader set with [setArtworkLoader] replaces it when the media
     * item changes. A picture larger than the system draws it is scaled down once to that size.
     */
    public fun setArtwork(image: Bitmap?) {
        artwork.value = image
    }

    /**
     * Loads the picture for each media item, so the application does not have to watch the player.
     *
     * [loader] runs on a background thread when the item changes, and again when its tags arrive
     * after the item opens. A newer item cancels an older load, and the picture is cleared while the
     * new one loads. A loader that throws or returns null shows the item's own cover, when it has
     * one. A picture larger than the system draws it is scaled down once to that size. Null stops
     * loading and keeps the picture shown.
     */
    public fun setArtworkLoader(loader: (suspend (PlayerSnapshot) -> Bitmap?)?) {
        artworkLoader.value = loader
    }

    /**
     * The activity to open from the system media controls, usually the player screen.
     * The media notification sets its content intent here too.
     */
    public fun setSessionActivity(intent: PendingIntent?) {
        sessionActivity = intent
        session.setSessionActivity(intent)
    }

    /**
     * Buttons of the application's own, such as a like or a shuffle button, in the order given.
     *
     * The session publishes them to the system media controls, a watch and the car, and the media
     * notification shows them after previous, play or pause and next. A press in any of these
     * calls [onAction] on the main thread with the button's id.
     *
     * @throws IllegalArgumentException when two actions share an id.
     */
    public fun setCustomActions(actions: List<MediaNotificationAction>, onAction: (id: String) -> Unit) {
        require(actions.map { it.id }.distinct().size == actions.size) {
            "each custom action needs its own id"
        }
        customActionHandler = onAction
        customActions.value = actions.toList()
    }

    private suspend fun loadArtwork() {
        var loadedItem: Any? = null
        combine(artworkLoader, player.state.distinctUntilChangedBy(::artworkKey)) { loader, snapshot ->
            loader to snapshot
        }.collectLatest { (loader, snapshot) ->
            if (loader == null) return@collectLatest
            val item = snapshot.media to snapshot.queueIndex
            if (item != loadedItem) {
                loadedItem = item
                artwork.value = null
            }
            if (snapshot.media == null) return@collectLatest
            val image = try {
                loader(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            artwork.value = image
        }
    }

    private fun pushMetadata(metadata: MediaSessionMetadata, image: Bitmap?) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
                // Live media has no length, and -1 is how the platform spells that.
                .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.duration?.inWholeMilliseconds ?: -1L)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, image)
                .build(),
        )
    }

    private fun pushPlaybackState(state: MediaSessionState) {
        val playback = sessionPlaybackFor(state, customActions.value)
        val builder = PlaybackState.Builder()
            .setActions(playback.actions)
            .setState(playback.state, playback.positionMillis, playback.speed)
        for (action in playback.customActions) {
            val custom = PlaybackState.CustomAction.Builder(action.id, action.label, action.icon).build()
            builder.addCustomAction(custom)
        }
        session.setPlaybackState(builder.build())
    }

    /** The notification and handlers that [KitePlayer.attachMediaSession] gave this session. */
    internal val parts: SessionParts = SessionParts()

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Closes what this session owns, newest first, then the session itself. Only the first call does anything. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            parts.closeAll()
        } finally {
            scope.cancel()
            session.isActive = false
            session.release()
        }
    }

    /** Closes this session once [player] is asked to close. */
    internal fun closeWithPlayer() {
        scope.closeWithPlayer(player, ::close)
    }

    private inner class Callback : MediaSession.Callback() {
        override fun onPlay() = player.playFromRemote()
        override fun onPause() = player.pauseFromRemote()
        override fun onStop() = player.pauseFromRemote()

        // The platform holds a play-pause key for the double tap timeout and reads a second one as
        // "next", a rule for a wired headset's one button. A remote, a keyboard or a speaker sends
        // the same key, so play-pause acts at once here, and only the headset's own key keeps the
        // double press (#437).
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = mediaButtonIntent.keyEvent() ?: return super.onMediaButtonEvent(mediaButtonIntent)
            return when (mediaKeyAction(event.keyCode, event.action, event.repeatCount)) {
                MediaKeyAction.Toggle -> {
                    // Buffering counts as playing: the listener asked for sound, so a toggle means stop.
                    if (player.state.value.status.isActive) player.pauseFromRemote() else player.playFromRemote()
                    true
                }
                MediaKeyAction.Consume -> true
                MediaKeyAction.Platform -> super.onMediaButtonEvent(mediaButtonIntent)
            }
        }

        // A refused seek throws, and an exception that leaves this scope ends the process.
        override fun onSeekTo(positionMillis: Long) {
            scope.launch { runCatching { player.seek(positionMillis.milliseconds) } }
        }

        override fun onSkipToNext() {
            scope.launch { runCatching { player.next() } }
        }

        override fun onSkipToPrevious() {
            scope.launch { runCatching { player.pressPrevious() } }
        }

        override fun onFastForward() = skipBy(skipInterval)
        override fun onRewind() = skipBy(-skipInterval)

        override fun onCustomAction(action: String, extras: Bundle?) {
            customActionHandler?.invoke(action)
        }

        private fun skipBy(by: Duration) {
            scope.launch {
                val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
                runCatching { player.seek(target) }
            }
        }
    }

}

/** What the session does with one media key event. */
internal enum class MediaKeyAction {
    /** Plays or pauses, at once. */
    Toggle,

    /** Taken and ignored: the release or the auto-repeat of a key acted on when it went down. */
    Consume,

    /** Left to the platform's own handling. */
    Platform,
}

/**
 * The session's answer to a media key (#437). Play-pause acts the moment it goes down, whatever
 * sent it, and never waits for or reads a second press. The headset hook keeps the platform's
 * double press for next, because a one-button headset has no other way to skip. Every other key is
 * the platform's.
 */
internal fun mediaKeyAction(keyCode: Int, action: Int, repeatCount: Int): MediaKeyAction = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) MediaKeyAction.Toggle else MediaKeyAction.Consume
    else -> MediaKeyAction.Platform
}

/** The key event a media button intent carries, or null. */
private fun Intent.keyEvent(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

/** A new item, or new tags on the same one, is what makes the artwork loader run again. */
private fun artworkKey(snapshot: PlayerSnapshot): Any =
    Triple(snapshot.media, snapshot.queueIndex, snapshot.metadata)

/**
 * Everything the platform's playback state carries, worked out without the platform so a host
 * test can read it. [customActions] keep their order, and each one's id is the action name the
 * platform hands back when it is pressed.
 */
internal data class SessionPlayback(
    val state: Int,
    val positionMillis: Long,
    val speed: Float,
    val actions: Long,
    val customActions: List<MediaNotificationAction>,
)

internal fun sessionPlaybackFor(
    state: MediaSessionState,
    customActions: List<MediaNotificationAction>,
): SessionPlayback = SessionPlayback(
    state = platformStateFor(state.phase),
    positionMillis = state.position.inWholeMilliseconds,
    // The platform walks the position on at this rate, so anything but playing must report zero or
    // the lock screen's position moves while nothing plays.
    speed = if (state.playing) state.speed.toFloat() else 0f,
    actions = actionsFor(state),
    customActions = customActions,
)

/**
 * The buttons a session should offer for a given state.
 *
 * Advertised from what the player can actually do right now, so a greyed-out next button means
 * there really is nothing next rather than the session having forgotten to say.
 */
internal fun actionsFor(state: MediaSessionState): Long {
    var actions = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_STOP
    if (state.canSeek) {
        actions = actions or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
    }
    if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
    // Previous also starts the item again, so it is there whenever the item can seek (#424).
    if (state.offersPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    return actions
}

/** The platform's own word for each phase. Stopped rather than none, so the card stays up. */
internal fun platformStateFor(phase: MediaSessionPhase): Int = when (phase) {
    MediaSessionPhase.Playing -> PlaybackState.STATE_PLAYING
    MediaSessionPhase.Paused -> PlaybackState.STATE_PAUSED
    MediaSessionPhase.Buffering -> PlaybackState.STATE_BUFFERING
    MediaSessionPhase.Stopped -> PlaybackState.STATE_STOPPED
}

/**
 * Creates the media session for this player, with what an app needs for the lock screen and for
 * background playback, and returns it.
 *
 * - The lock screen, headset buttons, car and quick settings controls follow the player.
 * - [notification] adds the media notification, which keeps the app in the foreground while it
 *   plays. It needs the manifest entries that [KitePlayerMediaService] lists. Null adds none.
 * - [background] says what happens when the app leaves the screen. Null leaves that alone.
 * - [interruptions] takes audio focus, so a call or another app pauses or ducks the player, and it
 *   pauses when the headphones come out. Null turns that off.
 *
 * The session owns all of it and closes it in the right order. It also closes itself when the
 * player closes, so most apps never close it by hand.
 *
 * ```kotlin
 * val player = KitePlayer()
 * player.attachMediaSession(context, MediaNotificationOptions(smallIcon = R.drawable.ic_notification))
 * ```
 *
 * @throws IllegalStateException when [notification] is given and the manifest does not declare
 *         [KitePlayerMediaService].
 */
public fun KitePlayer.attachMediaSession(
    context: Context,
    notification: MediaNotificationOptions? = null,
    background: BackgroundPolicy? = BackgroundPolicy.ContinueAudio,
    interruptions: InterruptionPolicy? = InterruptionPolicy(),
    skipInterval: Duration = 15.seconds,
): KitePlayerMediaSession {
    val session = KitePlayerMediaSession(this, context, skipInterval = skipInterval)
    try {
        interruptions?.let { session.parts.add(interruptionHandling(this, context, it)) }
        background?.let { session.parts.add(backgroundHandling(this, context, it)) }
        notification?.let { session.parts.add(mediaNotification(session, context, it)) }
    } catch (failure: Throwable) {
        session.close()
        throw failure
    }
    session.closeWithPlayer()
    return session
}
