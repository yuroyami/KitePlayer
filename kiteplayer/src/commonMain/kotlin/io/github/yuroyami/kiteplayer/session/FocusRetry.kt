package io.github.yuroyami.kiteplayer.session

/**
 * Asks for the sound, and once more after the application's media service has entered the
 * foreground when the first request was refused only because it had not yet (#454).
 *
 * From Android 15 a request from an application that is neither the top app nor running a foreground
 * service fails. A press of play on the lock screen after a long pause asks for focus as the player
 * starts, and the media notification moves its service back into the foreground at the same moment,
 * on its own path. When the request loses that race it is refused for a reason that is gone a moment
 * later. Counted as a loss, it paused the player again.
 *
 * [mayWaitForForeground] says whether a refusal can be that one: a platform with the rule, and a
 * service that may enter the foreground and is not there yet. [awaitForeground] waits a bounded time
 * for it and answers whether it came. A second refusal is a real one, and so is a first refusal
 * that cannot be the race. A grant or a "later" answer is never asked again.
 */
internal suspend fun requestFocusOnceForeground(
    request: () -> FocusResult,
    mayWaitForForeground: () -> Boolean,
    awaitForeground: suspend () -> Boolean,
): FocusResult {
    val first = request()
    if (first != FocusResult.Failed || !mayWaitForForeground()) return first
    return if (awaitForeground()) request() else first
}
