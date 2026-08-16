package io.github.yuroyami.kiteplayer

import kotlinx.atomicfu.atomic

/**
 * One warning, with the engine clock's reading when it was emitted (S4.d).
 *
 * The nanoseconds are the engine's own monotonic clock, comparable across one player's history and
 * meaningless across processes, which is all a bug report needs: the ORDER and the spacing.
 */
public data class TimedWarning(
    val atNanos: Long,
    val warning: PlaybackWarning,
)

/**
 * The logging policy, as a contract rather than a framework (S4.d).
 *
 * KitePlayer never prints on its own. Every line the engine would say goes through this one seam,
 * which is SILENT until an application installs a sink, and becomes silent again when the sink is
 * removed. There are no log levels to configure inside the player, no files, no timestamps and no
 * formatting: the sink gets the tag and the message and owns everything else, because the
 * application, not the library, knows where its logs belong.
 *
 * The player's own record-keeping does not depend on this: warnings are always kept in the bounded
 * history [KitePlayer.warningHistory] and printed by [KitePlayer.diagnosticsDump], sink or no sink.
 */
public object KiteLog {

    /** Receives every line the engine says while installed. Called from engine threads. */
    public fun interface Sink {
        public fun log(tag: String, message: String)
    }

    private val installed = atomic<Sink?>(null)

    /** Installs [sink], replacing any previous one. Null returns the player to silence. */
    public fun install(sink: Sink?) {
        installed.value = sink
    }

    internal fun log(tag: String, message: String) {
        installed.value?.log(tag, message)
    }
}
