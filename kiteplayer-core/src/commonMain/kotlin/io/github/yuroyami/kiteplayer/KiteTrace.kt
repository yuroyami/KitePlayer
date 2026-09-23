package io.github.yuroyami.kiteplayer

import kotlinx.atomicfu.atomic

/**
 * The trace seam: a timeline of what the player did, for a profiler to draw.
 *
 * Process wide, like [KiteLog], and silent until an application installs a [Sink]. With no sink,
 * every place in the engine that could trace costs one volatile read and does nothing else.
 * [ChromeTraceFormat] turns what a sink receives into lines that Chrome's trace viewer and
 * Perfetto open.
 *
 * What the engine traces:
 *
 * - Spans: `session`/`open`, the phases of a seek (`seek`/`keyframe`, `seek`/`refine`,
 *   `seek`/`precise`), and `track`/`switch`.
 * - Spans per frame, only when installed with `perFrame`: `video`/`decode` and `video`/`present`,
 *   each with the frame's timestamp in microseconds as `pts`.
 * - Instants: `audio`/`underrun`, when the engine sees the underrun count rise, and `video`/`drop`,
 *   when the schedule drops a late frame.
 *
 * Times are nanoseconds on the engine's monotonic clock. Values are strings, and a URI arrives only
 * as its basename, so a trace cannot leak a token from a query string.
 */
public object KiteTrace {

    /** Receives the engine's spans and instants while installed. Called from engine threads. */
    public interface Sink {
        public fun span(category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>)
        public fun instant(category: String, name: String, atNanos: Long, args: Map<String, String>)
    }

    private class Installed(val sink: Sink, val perFrame: Boolean)

    private val installed = atomic<Installed?>(null)

    /**
     * Installs [sink], replacing any previous one. Null returns the player to silence. [perFrame]
     * adds a span for every decoded and every presented frame, which is many per second.
     */
    public fun install(sink: Sink?, perFrame: Boolean = false) {
        installed.value = sink?.let { Installed(it, perFrame) }
    }

    /** True while a sink is installed. */
    public val enabled: Boolean get() = installed.value != null

    /** True while a sink is installed that wants a span per frame. */
    internal val perFrame: Boolean get() = installed.value?.perFrame == true

    /** How many times the engine entered an emit site. Tests read it to prove the guards hold. */
    internal val entered = atomic(0L)

    /** Call only behind [enabled] or [perFrame]. A sink that throws loses that one event, not playback. */
    internal fun span(category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>) {
        entered.incrementAndGet()
        val sink = installed.value?.sink ?: return
        try {
            sink.span(category, name, beginNanos, endNanos, args)
        } catch (_: Exception) {
        }
    }

    /** Call only behind [enabled]. A sink that throws loses that one event, not playback. */
    internal fun instant(category: String, name: String, atNanos: Long, args: Map<String, String>) {
        entered.incrementAndGet()
        val sink = installed.value?.sink ?: return
        try {
            sink.instant(category, name, atNanos, args)
        } catch (_: Exception) {
        }
    }
}
