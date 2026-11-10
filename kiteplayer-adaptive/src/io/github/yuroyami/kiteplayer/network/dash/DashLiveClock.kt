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
 * schemes that need a socket of their own, NTP among them, are passed over. When none answers, or
 * the manifest names none, the [manifestDate] of the manifest's own response gives the time, to
 * the second, before the device's clock does (#966).
 *
 * The difference is read on the first call, and read again every [RESYNC_MICROS], so a device
 * clock that drifts, or that the system sets during a long session, does not move the window for
 * good (#966); a measurement that fails then keeps the last one. A refreshed manifest hands its
 * elements over through [update], and new ones are read on the next call. When nothing has ever
 * answered, the device's clock stands for now, the log says so once, and a later call asks again,
 * five seconds on and then at twice the wait each time up to a minute (#749): a timeout or a
 * refusal at the open used to be kept like a measurement, and a slow device then sat behind the
 * live edge for the whole session. A browser hides a response's `Date` header from the page unless
 * the server exposes it, so there `http-head` and [manifestDate] answer only from a server that does.
 */
internal class DashLiveClock(
    private var timings: List<DashUtcTiming>,
    /** The device's time of day, in microseconds since 1970 UTC. */
    private val deviceMicros: () -> Long,
    /** The body of a response, as text. */
    private val fetchText: suspend (url: String) -> String,
    /** The `Date` header of a response, or null when it has none. */
    private val fetchDate: suspend (url: String) -> String?,
    /** The `Date` header of the manifest's own response, or null when it has none or there is none to ask. */
    private val manifestDate: (suspend () -> String?)? = null,
) {
    private var offsetMicros: Long? = null
    private var resyncAtMicros = Long.MIN_VALUE
    private var retryAtMicros = Long.MIN_VALUE
    private var retryWaitMicros = FIRST_RETRY_MICROS
    private var failureLogged = false

    /**
     * Takes the `UTCTiming` elements of a refreshed manifest. Elements that differ from the ones
     * held are read on the next call, so a manifest that names a new source, or mends a broken
     * one, is followed (#966).
     */
    fun update(fresh: List<DashUtcTiming>) {
        if (fresh == timings) return
        timings = fresh
        resyncAtMicros = Long.MIN_VALUE
        retryAtMicros = Long.MIN_VALUE
        retryWaitMicros = FIRST_RETRY_MICROS
    }

    /** The time of day now, in microseconds since 1970 UTC, on the packager's clock when one answered. */
    suspend fun nowMicros(): Long {
        val known = offsetMicros
        if (known != null && deviceMicros() < resyncAtMicros) return deviceMicros() + known
        if (known == null && deviceMicros() < retryAtMicros) return deviceMicros()
        val measured = offset() ?: dateOffset()
        if (measured != null) {
            offsetMicros = measured
            resyncAtMicros = deviceMicros() + RESYNC_MICROS
            retryWaitMicros = FIRST_RETRY_MICROS
            return deviceMicros() + measured
        }
        if (known != null) {
            // Nothing answered this time: the last measurement is still the best one there is.
            resyncAtMicros = deviceMicros() + RESYNC_MICROS
            return deviceMicros() + known
        }
        if (!failureLogged) {
            failureLogged = true
            KiteLog.log(
                "KiteDash",
                if (timings.isEmpty()) "the live manifest names no UTCTiming; its window follows the device's clock"
                else "no UTCTiming of the live manifest answered; its window follows the device's clock until one does",
            )
        }
        retryAtMicros = deviceMicros() + retryWaitMicros
        retryWaitMicros = minOf(retryWaitMicros * 2, MAX_RETRY_MICROS)
        return deviceMicros()
    }

    /** The manifest response's `Date`, to the second, as how far it is ahead of the device, or null (#966). */
    private suspend fun dateOffset(): Long? {
        val ask = manifestDate ?: return null
        val before = deviceMicros()
        val server = try {
            ask()?.let(::parseHttpDateMicros)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            null
        } ?: return null
        val ahead = server - (before + deviceMicros()) / 2
        // The header counts whole seconds, so a device within a second of it is as right as the
        // header can tell, and its own finer time is the better one.
        return if (ahead in -DATE_RESOLUTION_MICROS..DATE_RESOLUTION_MICROS) 0L else ahead
    }

    /** How far the time the manifest names is ahead of the device's, or null when nothing answered. */
    private suspend fun offset(): Long? {
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
        return null
    }

    private fun shownUrl(url: String): String = url.substringBefore('?')

    private enum class Kind { Direct, Text, Head }

    internal companion object {
        /** The wait before the clock asks again after nothing answered, doubled each time. */
        private const val FIRST_RETRY_MICROS = 5_000_000L
        private const val MAX_RETRY_MICROS = 60_000_000L

        /** How long a measured difference stands before the clock measures again (#966). */
        const val RESYNC_MICROS = 300_000_000L

        /** The resolution of an HTTP `Date`: one second. */
        private const val DATE_RESOLUTION_MICROS = 1_000_000L

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
