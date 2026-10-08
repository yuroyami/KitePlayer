package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KiteLog
import kotlinx.coroutines.CancellationException

/**
 * The time of day a live presentation counts its window against (#404): the device's clock,
 * moved by how far it is from the time the manifest's `UTCTiming` elements name (ISO/IEC 23009-1,
 * 5.8.4.11). A device clock a few seconds fast asks for segments that do not exist yet, and one a
 * few seconds slow starts behind the window, so the packager's time is the one to follow.
 *
 * The first element in the manifest's order that answers wins: `direct`, whose value is the time
 * itself, `http-xsdate` and `http-iso`, whose address answers with the time as text, and
 * `http-head`, whose address answers with the time in its `Date` header, to the second. The
 * schemes that need a socket of their own, NTP among them, are passed over. The difference is
 * read once, on the first call, and kept. When nothing answers, the device's clock stands, and the
 * log says so once. A browser hides a response's `Date` header from the page unless the server
 * exposes it, so there `http-head` answers only from a server that does.
 */
internal class DashLiveClock(
    private val timings: List<DashUtcTiming>,
    /** The device's time of day, in microseconds since 1970 UTC. */
    private val deviceMicros: () -> Long,
    /** The body of a response, as text. */
    private val fetchText: suspend (url: String) -> String,
    /** The `Date` header of a response, or null when it has none. */
    private val fetchDate: suspend (url: String) -> String?,
) {
    private var offsetMicros: Long? = null

    /** The time of day now, in microseconds since 1970 UTC, on the packager's clock when one answered. */
    suspend fun nowMicros(): Long {
        val offset = offsetMicros ?: offset().also { offsetMicros = it }
        return deviceMicros() + offset
    }

    /** How far the time the manifest names is ahead of the device's, or 0 when nothing names it. */
    private suspend fun offset(): Long {
        for (timing in timings) {
            val scheme = timing.schemeIdUri.lowercase()
            val kind = SCHEMES.entries.firstOrNull { (prefix, _) -> scheme == "$prefix:2014" || scheme == "$prefix:2012" }?.value ?: continue
            if (kind == Kind.Direct) {
                runCatching { DashManifestParser.parseDateTimeMicros(timing.value) }.getOrNull()?.let { return it - deviceMicros() }
                continue
            }
            // An http scheme's value may list several addresses, any of which may answer.
            for (url in timing.value.split(' ', '\t', '\n').filter { it.isNotBlank() }) {
                val before = deviceMicros()
                val server = try {
                    when (kind) {
                        Kind.Head -> fetchDate(url)?.let(::parseHttpDateMicros)
                        else -> DashManifestParser.parseDateTimeMicros(fetchText(url).trim())
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    KiteLog.log("KiteDash", "the live clock at ${shownUrl(url)} did not answer: ${failure.message}")
                    null
                } ?: continue
                // The answer was true somewhere between the request and the response.
                return server - (before + deviceMicros()) / 2
            }
        }
        KiteLog.log(
            "KiteDash",
            if (timings.isEmpty()) {
                "the live manifest names no UTCTiming; its window follows the device's clock"
            } else {
                "no UTCTiming of the live manifest answered; its window follows the device's clock"
            },
        )
        return 0L
    }

    private fun shownUrl(url: String): String = url.substringBefore('?')

    private enum class Kind { Direct, Text, Head }

    internal companion object {
        private val SCHEMES = mapOf(
            "urn:mpeg:dash:utc:direct" to Kind.Direct,
            "urn:mpeg:dash:utc:http-xsdate" to Kind.Text,
            "urn:mpeg:dash:utc:http-iso" to Kind.Text,
            "urn:mpeg:dash:utc:http-head" to Kind.Head,
        )

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** An HTTP date, `Sun, 06 Nov 1994 08:49:37 GMT` (RFC 9110, 5.6.7), in microseconds since 1970 UTC. */
        fun parseHttpDateMicros(raw: String): Long {
            val parts = raw.trim().substringAfter(", ").split(' ').filter { it.isNotEmpty() }
            require(parts.size >= 4) { "not an HTTP date: $raw" }
            val month = MONTHS.indexOf(parts[1].lowercase()) + 1
            require(month > 0) { "not an HTTP date: $raw" }
            val day = parts[0].padStart(2, '0')
            return DashManifestParser.parseDateTimeMicros("${parts[2]}-${month.toString().padStart(2, '0')}-${day}T${parts[3]}Z")
        }
    }
}
