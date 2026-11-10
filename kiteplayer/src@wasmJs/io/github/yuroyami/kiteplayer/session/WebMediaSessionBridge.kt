@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.session

import kotlin.js.JsAny
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The whole JavaScript surface of the web media session, over any object shaped like
 * `navigator.mediaSession`. It takes the object rather than reading the global, so a test can hand
 * it a fake and run in node.
 *
 * A page has one media session and may have several players, so one bridge owns the session at a
 * time: the newest that has not closed (#904). Each bridge keeps what it last published, and only
 * the owner writes it to the session. A bridge that closes clears the session only while it owns
 * it, and the session then passes back to the newest bridge left, which publishes what it kept.
 * Before, every close cleared the session whoever had set it, so closing one player took a newer
 * one's buttons and title away, and they did not come back.
 */
internal class WebMediaSessionBridge(private val session: JsAny) {

    private val handlers = mutableMapOf<String, (Double, Double) -> Unit>()
    private val offered = mutableSetOf<String>()
    private var lastMetadata: Pair<MediaSessionMetadata, String?>? = null
    private var lastPlayback: MediaSessionState? = null
    private var closed = false

    init {
        claim()
    }

    /** Whether this bridge owns its session now. */
    private val owns: Boolean get() = !closed && owners(session).lastOrNull() === this

    private fun claim() {
        val stack = owners(session)
        // The bridge that showed until now is no longer drawn: its buttons go before this one's come.
        stack.lastOrNull()?.withdraw()
        stack += this
    }

    /** Takes this bridge's buttons off the session, keeping what it published for when it owns it again. */
    private fun withdraw() {
        for (name in offered) webClearActionHandler(session, name)
        offered.clear()
    }

    /** Writes what this bridge last published, now that it owns the session again. */
    private fun republish() {
        lastMetadata?.let { (metadata, artwork) -> publishMetadata(metadata, artwork) }
        lastPlayback?.let(::publishPlayback)
    }

    /**
     * Remembers what [name] does. The browser is offered it only while the state can honour it.
     * [handler] gets the seek time and the seek offset in seconds, or -1 for one the browser left out.
     */
    fun onAction(name: String, handler: (Double, Double) -> Unit) {
        handlers[name] = handler
    }

    fun publishMetadata(metadata: MediaSessionMetadata, artworkUrl: String?) {
        lastMetadata = metadata to artworkUrl
        if (!owns) return
        webSetMetadata(session, metadata.title ?: "", metadata.artist ?: "", metadata.album ?: "", artworkUrl ?: "")
    }

    fun publishPlayback(state: MediaSessionState) {
        lastPlayback = state
        if (!owns) return
        webSetPlaybackState(
            session,
            when (state.phase) {
                MediaSessionPhase.Playing -> "playing"
                MediaSessionPhase.Paused, MediaSessionPhase.Buffering -> "paused"
                MediaSessionPhase.Stopped -> "none"
            },
        )
        val duration = state.duration
        if (duration == null) {
            webClearPositionState(session)
        } else {
            // The browser refuses a rate of zero and a position past the end. Paused is said by the
            // playback state above, which is what stops the browser walking the position on.
            webSetPositionState(
                session,
                duration.toDouble(DurationUnit.SECONDS),
                state.position.coerceIn(Duration.ZERO, duration).toDouble(DurationUnit.SECONDS),
                if (state.speed > 0.0) state.speed else 1.0,
            )
        }
        offer(webActionsFor(state))
    }

    /**
     * Lets go of the session, once. While this bridge owns it, its buttons, its state and its
     * metadata are cleared, and the newest bridge left takes the session over and publishes what it
     * kept. A bridge that did not own it changes nothing on the session.
     */
    fun clear() {
        if (closed) return
        val stack = owners(session)
        val owned = stack.lastOrNull() === this
        closed = true
        stack.remove(this)
        if (stack.isEmpty()) forgetSession(session)
        if (!owned) return
        for (name in ACTIONS) webClearActionHandler(session, name)
        offered.clear()
        webSetPlaybackState(session, "none")
        webClearPositionState(session)
        webClearMetadata(session)
        stack.lastOrNull()?.republish()
    }

    /** A browser draws a button for every action with a handler, so one that cannot work is withdrawn. */
    private fun offer(actions: Set<String>) {
        for (name in ACTIONS) {
            val handler = handlers[name]?.takeIf { name in actions }
            if ((handler != null) == (name in offered)) continue
            if (handler != null) {
                webSetActionHandler(session, name, handler)
                offered += name
            } else {
                webClearActionHandler(session, name)
                offered -= name
            }
        }
    }

    companion object {
        /** The live bridges of each session object, oldest first. The web runs on one thread. */
        private val sessions = ArrayList<Pair<JsAny, MutableList<WebMediaSessionBridge>>>()

        private fun owners(session: JsAny): MutableList<WebMediaSessionBridge> =
            sessions.firstOrNull { sameObject(it.first, session) }?.second
                ?: ArrayList<WebMediaSessionBridge>().also { sessions += session to it }

        private fun forgetSession(session: JsAny) {
            sessions.removeAll { sameObject(it.first, session) }
        }

        val ACTIONS: List<String> = listOf(
            "play", "pause", "stop", "previoustrack", "nexttrack", "seekbackward", "seekforward", "seekto",
        )
    }
}

/** The browser actions a state can honour. */
internal fun webActionsFor(state: MediaSessionState): Set<String> = buildSet {
    add("play")
    add("pause")
    add("stop")
    if (state.canSeek) {
        add("seekto")
        add("seekforward")
        add("seekbackward")
    }
    if (state.hasNext) add("nexttrack")
    // Previous also starts the item again, so it is there whenever the item can seek (#424).
    if (state.offersPrevious) add("previoustrack")
}

/** Null outside a browser, and in a browser that has no media session. */
@JsFun("() => (typeof navigator !== 'undefined' && navigator.mediaSession) ? navigator.mediaSession : null")
internal external fun navigatorMediaSession(): JsAny?

// MediaMetadata exists only where a media session does. Node has neither, so the plain object stands
// in there, which lets a test read the fields back the same way in both.
@JsFun(
    "(s, title, artist, album, art) => {" +
        " const init = { title: title, artist: artist, album: album, artwork: art ? [{ src: art }] : [] };" +
        " s.metadata = (typeof MediaMetadata !== 'undefined') ? new MediaMetadata(init) : init; }",
)
private external fun webSetMetadata(s: JsAny, title: String, artist: String, album: String, art: String)

@JsFun("(s) => { s.metadata = null; }")
private external fun webClearMetadata(s: JsAny)

@JsFun("(s, state) => { s.playbackState = state; }")
private external fun webSetPlaybackState(s: JsAny, state: String)

@JsFun(
    "(s, duration, position, rate) => { try { s.setPositionState(" +
        "{ duration: duration, position: position, playbackRate: rate }); } catch (e) {} }",
)
private external fun webSetPositionState(s: JsAny, duration: Double, position: Double, rate: Double)

@JsFun("(s) => { try { s.setPositionState(); } catch (e) {} }")
private external fun webClearPositionState(s: JsAny)

// A browser throws for an action it does not know. Unknown means unsupported there, not broken.
@JsFun(
    "(s, name, f) => { try { s.setActionHandler(name, (d) => f(" +
        "(d && typeof d.seekTime === 'number') ? d.seekTime : -1, " +
        "(d && typeof d.seekOffset === 'number') ? d.seekOffset : -1)); } catch (e) {} }",
)
private external fun webSetActionHandler(s: JsAny, name: String, f: (Double, Double) -> Unit)

@JsFun("(s, name) => { try { s.setActionHandler(name, null); } catch (e) {} }")
private external fun webClearActionHandler(s: JsAny, name: String)

@JsFun("(a, b) => a === b")
private external fun sameObject(a: JsAny, b: JsAny): Boolean
