package io.github.yuroyami.kiteplayer.session

import android.content.Context
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService
import io.github.yuroyami.kiteplayer.PlayerMemento

/**
 * The state that Android's media resume card plays again (#431).
 *
 * From Android 11 the media controls keep a card for a media application after a reboot or after
 * the application closed, when it declares a [KitePlayerResumptionService]. That service shows and
 * plays what was saved here last. The application decides when to save, for example at a pause and
 * when its player closes, and [clear] removes the card's item. It is kept in the application's own
 * private preferences, as the text of [PlayerMemento.asProperties].
 */
public object KitePlayerResumption {
    /** Keeps [memento] for the resume card, which shows [title] and [subtitle]. Replaces what was saved. */
    public fun save(context: Context, memento: PlayerMemento, title: String, subtitle: String? = null) {
        val editor = preferences(context).edit().clear()
        resumptionEntries(memento, title, subtitle).forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    /** Forgets what was saved, so the card offers nothing. */
    public fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    /** What was saved, or null. Something saved that cannot be read back is cleared. */
    internal fun load(context: Context): SavedResumption? {
        val stored = preferences(context).all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        if (stored.isEmpty()) return null
        return resumptionFrom(stored) ?: null.also { clear(context) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val PREFERENCES = "io.github.yuroyami.kiteplayer.resumption"
}

/**
 * Plays again what [KitePlayerResumption] saved, from Android's media resume card (#431).
 *
 * The application subclasses it, builds its player in [onResume], and declares the subclass in its
 * `AndroidManifest.xml`, exported, because the system's media controls connect to it:
 *
 * ```xml
 * <service android:name=".PlaybackResumption" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.media.browse.MediaBrowserService" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * It answers only the recent root that the resume card asks for, only when something was saved, and
 * only to the application itself or to a caller Android trusts for media control, which is the
 * system's media controls and holders of the media control permission. Any other application that
 * connects gets nothing. Its one item is the saved one. Pressing the card plays this service's own
 * session, which calls [onResume] and then gives the stage to the application's session.
 *
 * [KitePlayerMediaService] stays unexported; this is a separate opt-in.
 */
public abstract class KitePlayerResumptionService : MediaBrowserService() {

    private var session: MediaSession? = null

    /**
     * The resume card was pressed. On the main thread: build the player, `restore` [memento] on it,
     * attach its media session and play. This service's own session closes right after.
     */
    public abstract fun onResume(memento: PlayerMemento)

    override fun onCreate() {
        super.onCreate()
        val own = MediaSession(this, "KitePlayerResumption")
        own.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = resume()

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == RESUME_ID) resume()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        own.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build(),
        )
        sessionToken = own.sessionToken
        session = own
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val recent = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true
        val trusted = clientUid == Process.myUid() || callerIsTrusted()
        if (!answersRecentRoot(recent, trusted, saved = recent && trusted && KitePlayerResumption.load(this) != null)) return null
        return BrowserRoot(RECENT_ROOT, Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) })
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val saved = if (parentId == RECENT_ROOT) KitePlayerResumption.load(this) else null
        val items = mutableListOf<MediaBrowser.MediaItem>()
        if (saved != null) {
            val description = MediaDescription.Builder()
                .setMediaId(RESUME_ID)
                .setTitle(saved.title)
                .setSubtitle(saved.subtitle)
                .build()
            items += MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)
        }
        result.sendResult(items)
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun resume() {
        val saved = KitePlayerResumption.load(this) ?: return
        try {
            onResume(saved.memento)
        } finally {
            releaseSession()
        }
    }

    private fun releaseSession() {
        session?.let { own ->
            own.isActive = false
            own.release()
        }
        session = null
    }

    /** Whether the caller of this browser call is one Android trusts for media control. */
    private fun callerIsTrusted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val caller = currentBrowserInfo
        val manager = getSystemService(MediaSessionManager::class.java) ?: return false
        return runCatching { manager.isTrustedForMediaControl(caller) }.getOrDefault(false)
    }

    private companion object {
        const val RECENT_ROOT = "io.github.yuroyami.kiteplayer.recent"
        const val RESUME_ID = "io.github.yuroyami.kiteplayer.resume"
    }
}

/** What [KitePlayerResumption] saved: the state and the card's words. */
internal class SavedResumption(val memento: PlayerMemento, val title: String, val subtitle: String?)

/** The flat strings [KitePlayerResumption] keeps for [memento], [title] and [subtitle]. */
internal fun resumptionEntries(memento: PlayerMemento, title: String, subtitle: String?): Map<String, String> = buildMap {
    put(TITLE, title)
    subtitle?.let { put(SUBTITLE, it) }
    memento.asProperties().forEach { (key, value) -> put(MEMENTO + key, value) }
}

/** What [entries] hold, or null when they hold nothing readable: no title, or a memento that does not read back. */
internal fun resumptionFrom(entries: Map<String, String>): SavedResumption? {
    val title = entries[TITLE] ?: return null
    val memento = runCatching {
        PlayerMemento.fromProperties(entries.filterKeys { it.startsWith(MEMENTO) }.mapKeys { (key, _) -> key.removePrefix(MEMENTO) })
    }.getOrNull() ?: return null
    return SavedResumption(memento, title, entries[SUBTITLE])
}

/** The resume card's root goes only to the recent hint, a trusted caller, and something saved. */
internal fun answersRecentRoot(recent: Boolean, trusted: Boolean, saved: Boolean): Boolean = recent && trusted && saved

private const val TITLE = "title"
private const val SUBTITLE = "subtitle"
private const val MEMENTO = "memento."
