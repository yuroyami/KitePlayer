package io.github.yuroyami.kiteplayer

/**
 * One trace event as one line of JSON, in the Chrome trace event format that Chrome's trace viewer
 * and Perfetto open.
 *
 * A file of them is a JSON array: write `[`, then the lines joined by commas, then `]`. The
 * process and thread ids are the caller's to choose, because [KiteTrace.Sink] carries neither.
 *
 * ```kotlin
 * KiteTrace.install(object : KiteTrace.Sink {
 *     override fun span(category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>) {
 *         lines += ChromeTraceFormat.span(1, 1, category, name, beginNanos, endNanos, args)
 *     }
 *     override fun instant(category: String, name: String, atNanos: Long, args: Map<String, String>) {
 *         lines += ChromeTraceFormat.instant(1, 1, category, name, atNanos, args)
 *     }
 * })
 * ```
 */
public object ChromeTraceFormat {

    /** A complete event (phase `X`): a begin time and a duration, both in microseconds. */
    public fun span(
        pid: Int,
        tid: Int,
        category: String,
        name: String,
        beginNanos: Long,
        endNanos: Long,
        args: Map<String, String>,
    ): String = buildString {
        append("{\"name\":").appendQuoted(name)
        append(",\"cat\":").appendQuoted(category)
        append(",\"ph\":\"X\",\"ts\":").appendMicros(beginNanos)
        append(",\"dur\":").appendMicros(endNanos - beginNanos)
        append(",\"pid\":").append(pid).append(",\"tid\":").append(tid)
        append(",\"args\":").appendArgs(args).append('}')
    }

    /** An instant event (phase `i`) on its thread's track, at a time in microseconds. */
    public fun instant(
        pid: Int,
        tid: Int,
        category: String,
        name: String,
        atNanos: Long,
        args: Map<String, String>,
    ): String = buildString {
        append("{\"name\":").appendQuoted(name)
        append(",\"cat\":").appendQuoted(category)
        append(",\"ph\":\"i\",\"s\":\"t\",\"ts\":").appendMicros(atNanos)
        append(",\"pid\":").append(pid).append(",\"tid\":").append(tid)
        append(",\"args\":").appendArgs(args).append('}')
    }

    /** Nanoseconds as microseconds with three decimals, so no precision is lost. */
    private fun StringBuilder.appendMicros(nanos: Long): StringBuilder {
        if (nanos < 0) append('-')
        val magnitude = if (nanos < 0) -nanos else nanos
        return append(magnitude / 1_000).append('.').append((magnitude % 1_000).toString().padStart(3, '0'))
    }

    private fun StringBuilder.appendArgs(args: Map<String, String>): StringBuilder {
        append('{')
        args.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendQuoted(key).append(':').appendQuoted(value)
        }
        return append('}')
    }

    private fun StringBuilder.appendQuoted(text: String): StringBuilder {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        return append('"')
    }
}
