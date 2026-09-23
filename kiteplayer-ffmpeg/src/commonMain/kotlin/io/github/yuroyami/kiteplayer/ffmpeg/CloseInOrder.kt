package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * Runs every step, even when an earlier one throws, then throws the first failure with the later
 * ones suppressed on it. A close that stopped at its first failure left the rest open.
 */
internal fun closeInOrder(vararg steps: () -> Unit) {
    var first: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (failure: Throwable) {
            first?.addSuppressed(failure) ?: run { first = failure }
        }
    }
    first?.let { throw it }
}
