package io.github.yuroyami.kiteplayer.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.github.yuroyami.kiteplayer.AudioContent
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Makes [player] behave when something takes the sound away: a call, another app, or the
 * headphones coming out.
 *
 * Android hands this out as audio focus. The handle asks for focus when playback starts, keeps it
 * across a pause so a call can hand it back, and gives it up when the player goes idle. A denied
 * request is handled as a loss, so the player does not play without focus, and a play after a
 * denial or a permanent loss asks again. Close it with the player, or with the Activity that owns
 * it.
 *
 * The application still declares nothing: no permission and no manifest entry is involved.
 */
@Deprecated("Use KitePlayer.attachMediaSession, which owns this part and closes with the player.")
public fun KitePlayerPlatform.attachInterruptionHandling(
    player: KitePlayer,
    context: Context,
    policy: InterruptionPolicy = InterruptionPolicy(),
): AutoCloseable = interruptionHandling(player, context, policy)

/** The audio focus and headphone handle, as `KitePlayer.attachMediaSession` owns it. */
internal fun interruptionHandling(player: KitePlayer, context: Context, policy: InterruptionPolicy): AutoCloseable =
    AndroidInterruptionHandle(player, context.applicationContext, policy)

private class AndroidInterruptionHandle(
    player: KitePlayer,
    private val context: Context,
    private val policy: InterruptionPolicy,
) : AutoCloseable {

    private val applier = InterruptionApplier(PlayerSessionTarget(player), policy)
    private val lifecycle = SessionFocusLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var closed = false

    // The focus callback, the noisy receiver and the status collector run on different threads,
    // and the lifecycle and the applier are not thread safe, so each call holds its monitor. A
    // closed handle reacts to nothing, so a callback already on its way cannot pause the player.
    private fun handle(event: InterruptionEvent) = synchronized(applier) { if (!closed) applier.handle(event) }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A permanent loss ends this hold: the next play asks again (#282).
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(lifecycle) { lifecycle.lost() }
        // The sound a request was told to wait for has arrived (#451).
        if (change == AudioManager.AUDIOFOCUS_GAIN) synchronized(lifecycle) { lifecycle.gained() }
        interruptionEventFor(change)?.let(::handle)
    }

    // Built for the content of the item that asks, so the focus request declares what the audio
    // track declares (#446). Android abandons by the listener, so giving back the last one built
    // gives back whatever is held.
    @Volatile
    private var focusRequest = focusRequestFor(AudioContent.Movie)

    // setWillPauseWhenDucked is left off deliberately: with it on the system converts a duckable
    // loss into a plain one, and the policy is the thing that decides between ducking and pausing here.
    private fun focusRequestFor(content: AudioContent) = AudioFocusRequest.Builder(focusGainFor(policy.focus))
        // Only a permanent request waits through a call: a short sound refused now has no reason
        // to play once the call is over (#451).
        .setAcceptsDelayedFocusGain(policy.focus == AudioFocusKind.Permanent)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(focusContentType(content))
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedFrom: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                handle(InterruptionEvent.BecameNoisy)
            }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        // A protected system broadcast, so nothing else may send it to us.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
        scope.launch {
            // An item with no audio track selected asks for nothing, so a muted preview leaves
            // another app's music playing (#436).
            player.state.map { it.status to (it.tracks.selectedAudio != null) }.distinctUntilChanged().collect { (status, hasSound) ->
                when (synchronized(lifecycle) { lifecycle.on(status, hasSound) }) {
                    true -> {
                        // A blocking platform call, which cancelling the scope does not stop, so the
                        // answer may come back after close (#415).
                        val request = focusRequestFor(player.state.value.audioContent).also { focusRequest = it }
                        // A refusal that only says the media service is not in the foreground yet
                        // is asked again once it is (#454).
                        val result = requestFocusOnceForeground(
                            request = { focusResultFor(audioManager.requestAudioFocus(request)) },
                            mayWaitForForeground = {
                                waitsForForeground(
                                    Build.VERSION.SDK_INT,
                                    MediaServiceForeground.notificationAttached,
                                    MediaServiceForeground.inForeground.value,
                                )
                            },
                            awaitForeground = {
                                withTimeoutOrNull(FOREGROUND_WAIT) { MediaServiceForeground.inForeground.first { it } } != null
                            },
                        )
                        when (synchronized(lifecycle) { lifecycle.answered(result) }) {
                            FocusAnswer.Held -> Unit
                            // "Later", during a call: pause now, and the gain that comes plays (#451).
                            FocusAnswer.Waiting -> synchronized(applier) { if (!closed) applier.focusDelayed() }
                            // No focus, no sound: a denied request is a loss, and the policy pauses.
                            FocusAnswer.Denied -> handle(InterruptionEvent.Lost)
                            // Close gave back only what was held then; this grant, or this wait,
                            // has no other owner.
                            FocusAnswer.AfterClose ->
                                if (result != FocusResult.Failed) audioManager.abandonAudioFocusRequest(request)
                        }
                    }
                    false -> audioManager.abandonAudioFocusRequest(focusRequest)
                    null -> Unit
                }
            }
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        if (synchronized(lifecycle) { lifecycle.release() }) audioManager.abandonAudioFocusRequest(focusRequest)
        synchronized(applier) { applier.release() }
    }
}

/**
 * Whether a refused focus request may be the Android 15 refusal of an application in the background
 * (#454): the rule exists from Android 15, an attached media notification puts its service into the
 * foreground when playback starts, and that service is not there yet. Anything else is a real refusal.
 */
internal fun waitsForForeground(sdk: Int, notificationAttached: Boolean, inForeground: Boolean): Boolean =
    sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM && notificationAttached && !inForeground

/**
 * How long a refused request waits for the media service to enter the foreground before the refusal
 * stands. The service's start takes a few hundred milliseconds; a refusal for another reason costs
 * at most this much sound before the player pauses.
 */
private val FOREGROUND_WAIT = 1.seconds

/** The `AudioAttributes` content type a focus request declares for [content], as the audio track does. */
internal fun focusContentType(content: AudioContent): Int = when (content) {
    AudioContent.Music -> AudioAttributes.CONTENT_TYPE_MUSIC
    AudioContent.Speech -> AudioAttributes.CONTENT_TYPE_SPEECH
    AudioContent.Movie, AudioContent.Automatic -> AudioAttributes.CONTENT_TYPE_MOVIE
}

/** The focus a request for [kind] asks Android for (#451). */
internal fun focusGainFor(kind: AudioFocusKind): Int = when (kind) {
    AudioFocusKind.Permanent -> AudioManager.AUDIOFOCUS_GAIN
    AudioFocusKind.Transient -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    AudioFocusKind.TransientMayDuck -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
}

/** Android's answer to a focus request, as this player's: granted, later, or refused (#451). */
internal fun focusResultFor(code: Int): FocusResult = when (code) {
    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusResult.Granted
    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusResult.Delayed
    else -> FocusResult.Failed
}

/** Android's focus codes, as this player's events. Anything else is not ours to react to. */
internal fun interruptionEventFor(focusChange: Int): InterruptionEvent? = when (focusChange) {
    AudioManager.AUDIOFOCUS_LOSS -> InterruptionEvent.Lost
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> InterruptionEvent.LostTransient
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> InterruptionEvent.LostTransientCanDuck
    AudioManager.AUDIOFOCUS_GAIN -> InterruptionEvent.Gained
    else -> null
}
