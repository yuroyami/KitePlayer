package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a media session owns besides itself: the notification, the background handling and the
 * interruption handling, in the order they were attached (#385).
 *
 * They close newest first and once, so the notification goes before the handlers, as apps used to
 * be told to close them by hand. A part added after the close is closed at once.
 */
internal class SessionParts {
    // Null once closed. A state flow only for its atomic update, which works on every target.
    private val parts = MutableStateFlow<List<AutoCloseable>?>(emptyList())

    fun add(part: AutoCloseable) {
        var kept = false
        parts.update { held ->
            kept = held != null
            held?.plus(part)
        }
        if (!kept) part.close()
    }

    /** Closes every part, newest first. A part that throws does not stop the rest, and the first failure is rethrown. */
    fun closeAll() {
        val held = parts.getAndUpdate { null } ?: return
        var failure: Throwable? = null
        for (part in held.asReversed()) {
            try {
                part.close()
            } catch (thrown: Throwable) {
                failure?.addSuppressed(thrown) ?: run { failure = thrown }
            }
        }
        failure?.let { throw it }
    }
}

/**
 * Runs [close] once [player] is asked to close. It runs in this scope, so a session that closes
 * first takes the wait with it. A failure of [close] is dropped, because nothing is left to tell.
 */
internal fun CoroutineScope.closeWithPlayer(player: KitePlayer, close: () -> Unit) {
    launch {
        player.awaitClose()
        runCatching { close() }
    }
}
