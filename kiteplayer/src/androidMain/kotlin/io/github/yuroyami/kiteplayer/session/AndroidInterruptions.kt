package io.github.yuroyami.kiteplayer.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Makes [player] behave when something takes the sound away: a call, another app, or the
 * headphones coming out.
 *
 * Android hands this out as audio focus. The handle asks for focus when playback starts, keeps it
 * across a pause so a call can hand it back, and gives it up when the player goes idle. Close it
 * with the player, or with the Activity that owns it.
 *
 * The application still declares nothing: no permission and no manifest entry is involved.
 */
public fun KitePlayerPlatform.attachInterruptionHandling(
    player: KitePlayer,
    context: Context,
    policy: InterruptionPolicy = InterruptionPolicy(),
): AutoCloseable = AndroidInterruptionHandle(player, context.applicationContext, policy)

private class AndroidInterruptionHandle(
    player: KitePlayer,
    private val context: Context,
    policy: InterruptionPolicy,
) : AutoCloseable {

    private val applier = InterruptionApplier(PlayerSessionTarget(player), policy)
    private val lifecycle = SessionFocusLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        interruptionEventFor(change)?.let(applier::handle)
    }

    // Left off deliberately: with it on the system converts a duckable loss into a plain one, and
    // the policy is the thing that decides between ducking and pausing here.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedFrom: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                applier.handle(InterruptionEvent.BecameNoisy)
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
            player.state.map { it.status }.distinctUntilChanged().collect { status ->
                when (lifecycle.on(status)) {
                    true -> audioManager.requestAudioFocus(focusRequest)
                    false -> audioManager.abandonAudioFocusRequest(focusRequest)
                    null -> Unit
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        if (lifecycle.release()) audioManager.abandonAudioFocusRequest(focusRequest)
        applier.release()
    }
}

/** Android's focus codes, as this player's events. Anything else is not ours to react to. */
internal fun interruptionEventFor(focusChange: Int): InterruptionEvent? = when (focusChange) {
    AudioManager.AUDIOFOCUS_LOSS -> InterruptionEvent.Lost
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> InterruptionEvent.LostTransient
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> InterruptionEvent.LostTransientCanDuck
    AudioManager.AUDIOFOCUS_GAIN -> InterruptionEvent.Gained
    else -> null
}
