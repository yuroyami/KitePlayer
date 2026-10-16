package io.github.yuroyami.kiteplayer.sample.android

import android.content.Context
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerMemento
import io.github.yuroyami.kiteplayer.session.KitePlayerResumption
import io.github.yuroyami.kiteplayer.session.KitePlayerResumptionService
import io.github.yuroyami.kiteplayer.session.MediaNotificationOptions
import io.github.yuroyami.kiteplayer.session.attachMediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Plays the last song again from Android's media resume card, after the app was closed (#431).
 *
 * The resumed player has no screen. It plays under the library's media notification until the app
 * opens again, which closes it.
 */
internal class SampleResumptionService : KitePlayerResumptionService() {
    override fun onResume(memento: PlayerMemento) {
        ResumedPlayer.close()
        val player = KitePlayer()
        ResumedPlayer.player = player
        player.attachMediaSession(applicationContext, MediaNotificationOptions(smallIcon = android.R.drawable.ic_media_play))
        ResumedPlayer.scope.launch {
            player.restore(memento)
            player.play()
        }
    }
}

/** The player that the resume card started, kept here because no screen owns it. */
internal object ResumedPlayer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var player: KitePlayer? = null

    fun close() {
        player?.close()
        player = null
    }
}

/** Saves where [player] is for the resume card, named after the file it plays. */
internal fun saveForResumeCard(context: Context, player: KitePlayer) {
    val memento = player.memento()
    val item = memento.queue.getOrNull(memento.queueIndex) ?: return
    val title = item.uri.substringAfterLast('/').substringBeforeLast('.')
    KitePlayerResumption.save(context, memento, title, context.getString(R.string.app_name))
}
