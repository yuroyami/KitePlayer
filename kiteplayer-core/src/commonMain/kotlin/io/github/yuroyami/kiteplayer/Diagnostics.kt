package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.redactUrisIn
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

    /**
     * Receives every line with the FIELDS behind it, rather than flattened into prose.
     *
     * A warning knows its own type, and often a stream index or a URI, and the plain [Sink] loses
     * all of that into a sentence that a structured logging backend then has to parse back out
     * with a regular expression. This hands them over instead: `warning` is always present, and
     * whatever else the warning carries is beside it.
     *
     * Installing either kind of sink replaces the other; there is one seam, not two.
     */
    public fun interface StructuredSink {
        public fun event(tag: String, message: String, fields: Map<String, String>)
    }

    private val installed = atomic<Sink?>(null)
    private val installedStructured = atomic<StructuredSink?>(null)

    /**
     * Whether URIs in messages and field values are reduced to a bare filename. On by default.
     *
     * A log line quoting a URI quotes its query string too, and that is where tokens and signatures
     * live. Logs get pasted into issue trackers, so the safe default is the one that cannot leak a
     * credential; an application logging only local paths can turn it off.
     */
    public var redactUris: Boolean = true

    /** Installs [sink], replacing any previous sink of either kind. Null returns the player to silence. */
    public fun install(sink: Sink?) {
        installed.value = sink
        if (sink != null) installedStructured.value = null
    }

    /**
     * Installs [sink], replacing any previous sink of either kind. Null returns the player to silence.
     *
     * A separate NAME rather than an overload of [install]: two nullable overloads make a bare
     * `install(null)` ambiguous, which would have broken every existing caller that used it to go
     * back to silence.
     */
    public fun installStructured(sink: StructuredSink?) {
        installedStructured.value = sink
        if (sink != null) installed.value = null
    }

    internal fun log(tag: String, message: String, fields: Map<String, String> = emptyMap()) {
        val structured = installedStructured.value
        val plain = installed.value
        if (structured == null && plain == null) return

        val safeMessage = if (redactUris) redactUrisIn(message) else message
        val safeFields = when {
            fields.isEmpty() || !redactUris -> fields
            else -> fields.mapValues { (_, value) -> redactUrisIn(value) }
        }
        structured?.event(tag, safeMessage, safeFields)
        plain?.let { sink ->
            // A plain sink still gets the fields, appended, rather than losing them: it is the
            // simpler seam, not the less informed one.
            val suffix = if (safeFields.isEmpty()) {
                ""
            } else {
                safeFields.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            }
            sink.log(tag, safeMessage + suffix)
        }
    }
}
