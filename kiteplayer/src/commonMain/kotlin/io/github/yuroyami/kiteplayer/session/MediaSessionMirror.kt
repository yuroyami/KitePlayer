package io.github.yuroyami.kiteplayer.session

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Remembers what a platform session was last told, so each new state writes only the half that
 * changed. [A] is the platform's own artwork type. One per session, fed from one coroutine.
 */
internal class MediaSessionMirror<A : Any>(
    private val writeMetadata: (MediaSessionMetadata, A?) -> Unit,
    private val writePlayback: (MediaSessionState) -> Unit,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var metadata: MediaSessionMetadata? = null
    private var artwork: A? = null
    private var playback: MediaSessionState? = null
    private var playbackAt: TimeMark? = null

    fun update(state: MediaSessionState, artwork: A?) {
        val metadata = state.metadata()
        if (metadata != this.metadata || artwork != this.artwork) {
            this.metadata = metadata
            this.artwork = artwork
            writeMetadata(metadata, artwork)
        }
        if (state.needsPush(playback, playbackAt?.elapsedNow() ?: Duration.ZERO)) {
            playback = state
            playbackAt = clock.markNow()
            writePlayback(state)
        }
    }
}
