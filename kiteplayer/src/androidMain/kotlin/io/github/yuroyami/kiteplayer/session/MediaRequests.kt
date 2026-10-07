package io.github.yuroyami.kiteplayer.session

import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle

/** The kinds of request for specific media that an application can answer (#431). */
public enum class MediaRequestKind {
    /** "Play some jazz": a search, by voice or by text. */
    Search,

    /** An item by the id the application gave it, as a car or a watch asks. */
    MediaId,

    /** An item by its address. */
    Address,

    /** Get ready to play, with nothing named, so that a play right after it starts at once. */
    Prepare,
}

/**
 * A request from the system to play or prepare specific media, as
 * [KitePlayerMediaSession.setMediaRequestHandler] hands it to the application (#431).
 *
 * The session opens and plays nothing by itself: only the application knows its catalogue, so it
 * decides what each request means and opens it on its player.
 */
public sealed class MediaRequest {
    /** True to play, false to prepare only. */
    public abstract val play: Boolean

    /** The request's extras as the system sent them, empty when it sent none. */
    public abstract val extras: Bundle

    /** A search. [query] is what the listener said or typed, empty for "play something". */
    public class Search internal constructor(
        public val query: String,
        override val play: Boolean,
        override val extras: Bundle,
    ) : MediaRequest() {
        override fun toString(): String = "MediaRequest.Search(query=$query, play=$play)"
    }

    /** An item by [mediaId], the id the application gave it. */
    public class MediaId internal constructor(
        public val mediaId: String,
        override val play: Boolean,
        override val extras: Bundle,
    ) : MediaRequest() {
        override fun toString(): String = "MediaRequest.MediaId(mediaId=$mediaId, play=$play)"
    }

    /** An item by its address, [uri]. */
    public class Address internal constructor(
        public val uri: Uri,
        override val play: Boolean,
        override val extras: Bundle,
    ) : MediaRequest() {
        override fun toString(): String = "MediaRequest.Address(uri=$uri, play=$play)"
    }

    /** Get ready to play whatever plays next. Always a prepare. */
    public class Prepare internal constructor(override val extras: Bundle) : MediaRequest() {
        override val play: Boolean get() = false

        override fun toString(): String = "MediaRequest.Prepare"
    }
}

/**
 * Which requests the application answers, and its handler. Generic in the request, so a host test
 * can route without the platform's types. Read from the session's callback thread, set from any.
 */
internal class MediaRequestRouter<R : Any> {
    private class Route<R>(val kinds: Set<MediaRequestKind>, val handler: (R) -> Unit)

    @Volatile
    private var route: Route<R>? = null

    /** The kinds the session offers: none without a handler. */
    val offered: Set<MediaRequestKind> get() = route?.kinds.orEmpty()

    fun set(kinds: Set<MediaRequestKind>, handler: ((R) -> Unit)?) {
        route = if (handler == null || kinds.isEmpty()) null else Route(kinds.toSet(), handler)
    }

    /**
     * Hands the request [build] makes to the handler, when the application answers [kind]. A kind
     * it does not answer, which a stale controller can still send, builds nothing and is ignored.
     */
    fun deliver(kind: MediaRequestKind, build: () -> R?): Boolean {
        val current = route ?: return false
        if (kind !in current.kinds) return false
        val request = build() ?: return false
        current.handler(request)
        return true
    }
}

/** The platform's actions for the request kinds the application answers. */
internal fun requestActionsFor(kinds: Set<MediaRequestKind>): Long {
    var actions = 0L
    if (MediaRequestKind.Search in kinds) {
        actions = actions or PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PREPARE_FROM_SEARCH
    }
    if (MediaRequestKind.MediaId in kinds) {
        actions = actions or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID
    }
    if (MediaRequestKind.Address in kinds) {
        actions = actions or PlaybackState.ACTION_PLAY_FROM_URI or PlaybackState.ACTION_PREPARE_FROM_URI
    }
    if (MediaRequestKind.Prepare in kinds) actions = actions or PlaybackState.ACTION_PREPARE
    return actions
}
