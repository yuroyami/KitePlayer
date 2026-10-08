package io.github.yuroyami.kiteplayer.network

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * The part of RFC 9111 that the segment store follows (#547): which responses may be kept, for how
 * long one is fresh, and what its record holds. Nothing here sends a request or touches a store.
 */

/** The `Cache-Control` directives of a response that decide whether and how long it is kept. */
internal class CacheDirectives(
    /** `no-store`: the response is never kept. */
    val noStore: Boolean,
    /** `no-cache`: the response is kept, and asked about before every use. */
    val noCache: Boolean,
    /** `max-age` in seconds, or null when the response names none. */
    val maxAgeSeconds: Long?,
)

/**
 * Reads the `Cache-Control` header [values] of a response. `private` is not read, because the
 * store is on one user's device, and `s-maxage` is for shared caches only. A `max-age` that is not
 * a number counts as zero, and the smallest of several wins.
 */
internal fun cacheDirectives(values: List<String>): CacheDirectives {
    var noStore = false
    var noCache = false
    var maxAge: Long? = null
    for (directive in values.flatMap(::splitList)) {
        val name = directive.substringBefore('=').trim().lowercase()
        when (name) {
            "no-store" -> noStore = true
            "no-cache" -> noCache = true
            "max-age" -> {
                val seconds = directive.substringAfter('=', "").trim().trim('"').toLongOrNull()?.coerceAtLeast(0) ?: 0L
                maxAge = minOf(maxAge ?: seconds, seconds)
            }
        }
    }
    return CacheDirectives(noStore, noCache, maxAge)
}

/**
 * The request header names that the `Vary` header [values] list, lower case, sorted and without
 * repeats, or null for `Vary: *`, which no later request can match.
 */
internal fun varyNames(values: List<String>): List<String>? {
    val names = values.flatMap(::splitList).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if ("*" in names) return null
    return names.distinct().sorted()
}

/** The members of one comma-separated header value. A comma inside a quoted string does not split. */
private fun splitList(value: String): List<String> {
    val members = ArrayList<String>()
    val member = StringBuilder()
    var quoted = false
    for (char in value) {
        when {
            char == '"' -> {
                quoted = !quoted
                member.append(char)
            }
            char == ',' && !quoted -> {
                members += member.toString()
                member.clear()
            }
            else -> member.append(char)
        }
    }
    members += member.toString()
    return members.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * The seconds since 1970 UTC that the HTTP date [text] names, or null when it is not one. All
 * three forms of RFC 9110, 5.6.7 are read: `Sun, 06 Nov 1994 08:49:37 GMT`,
 * `Sunday, 06-Nov-94 08:49:37 GMT` and `Sun Nov  6 08:49:37 1994`.
 */
internal fun httpDateSeconds(text: String?): Long? {
    val value = text?.trim() ?: return null
    val comma = value.indexOf(',')
    val day: Int
    val month: Int
    val year: Int
    val time: String
    if (comma >= 0) {
        val parts = value.substring(comma + 1).replace('-', ' ').split(' ').filter { it.isNotEmpty() }
        if (parts.size < 4) return null
        day = parts[0].toIntOrNull() ?: return null
        month = monthOf(parts[1]) ?: return null
        val stated = parts[2].toIntOrNull() ?: return null
        // A year of two digits is of the last century from 70 on, as the form's own era wrote it.
        year = if (parts[2].length <= 2) (if (stated >= 70) 1900 + stated else 2000 + stated) else stated
        time = parts[3]
    } else {
        val parts = value.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 5) return null
        month = monthOf(parts[1]) ?: return null
        day = parts[2].toIntOrNull() ?: return null
        time = parts[3]
        year = parts[4].toIntOrNull() ?: return null
    }
    val clock = time.split(':')
    if (clock.size != 3) return null
    val hour = clock[0].toIntOrNull() ?: return null
    val minute = clock[1].toIntOrNull() ?: return null
    val second = clock[2].toIntOrNull() ?: return null
    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60 || year !in 1..9999) return null
    return daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private fun monthOf(name: String): Int? = MONTHS.indexOf(name.lowercase()).takeIf { it >= 0 }?.plus(1)

/** The days from 1970-01-01 to the given day of the Gregorian calendar. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1L else year.toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

/** The time of day in seconds since 1970 UTC, as the device tells it. */
@OptIn(ExperimentalTime::class)
internal fun wallClockSeconds(): Long = Clock.System.now().epochSeconds

/** The headers of one response that the store reads. Each is null when the response has none. */
internal class ResponseFacts(
    val cacheControl: List<String> = emptyList(),
    val vary: List<String> = emptyList(),
    val entityTag: String? = null,
    val lastModified: String? = null,
    val date: String? = null,
    val expires: String? = null,
    val age: String? = null,
)

/**
 * What the store keeps about one resource beside its bytes: where it came from, what validates
 * it, and how long it is fresh. This is the record of its entry.
 */
internal class StoredResponse(
    /** The address that answered, after every redirect. */
    val location: String,
    /** The size of the whole resource in bytes. */
    val size: Long,
    /** True when the server answered with a range, so a reader can ask for the bytes it lacks. */
    val ranged: Boolean,
    val contentType: String?,
    /** The strong entity tag, or null. */
    val entityTag: String?,
    val lastModified: String?,
    /** The `Date` header as the server wrote it, or null. */
    val date: String?,
    /** The device's time when the response arrived, in seconds since 1970. */
    val responseSeconds: Long,
    /** How old the response already was when it arrived, in seconds (RFC 9111, 4.2.3). */
    val initialAgeSeconds: Long,
    /** The lifetime the server stated with `max-age` or `Expires`, in seconds, or null for none. */
    val statedLifetimeSeconds: Long?,
    /** True for `no-cache`: every use asks the server first. */
    val noCache: Boolean,
) {
    /**
     * How long the response is fresh, in seconds. Without a stated lifetime it is a tenth of the
     * time from `Last-Modified` to the response, at most one day (RFC 9111, 4.2.2). A response with
     * neither is never fresh.
     */
    fun lifetimeSeconds(): Long {
        if (noCache) return 0
        statedLifetimeSeconds?.let { return it.coerceAtLeast(0) }
        val modified = httpDateSeconds(lastModified) ?: return 0
        val answered = httpDateSeconds(date) ?: responseSeconds
        return ((answered - modified) / HEURISTIC_FRACTION).coerceIn(0, MAX_HEURISTIC_SECONDS)
    }

    /** True when the response may be used at [nowSeconds] without asking the server. */
    fun isFresh(nowSeconds: Long): Boolean {
        // A clock that went back says nothing about the age, so the server is asked.
        if (nowSeconds < responseSeconds) return false
        return initialAgeSeconds + (nowSeconds - responseSeconds) < lifetimeSeconds()
    }

    /** The request header that asks the server whether the resource changed, or null when nothing validates it. */
    fun condition(): Pair<String, String>? = when {
        entityTag != null -> "If-None-Match" to entityTag
        lastModified != null -> "If-Modified-Since" to lastModified
        else -> null
    }

    /** This record after the server answered 304 with [facts] at [nowSeconds]: the same bytes, fresh again. */
    fun revalidated(facts: ResponseFacts, requestSeconds: Long, nowSeconds: Long): StoredResponse {
        val directives = cacheDirectives(facts.cacheControl)
        val newDate = facts.date ?: date
        return StoredResponse(
            location = location,
            size = size,
            ranged = ranged,
            contentType = contentType,
            entityTag = strongTag(facts.entityTag) ?: entityTag,
            lastModified = facts.lastModified ?: lastModified,
            date = newDate,
            responseSeconds = nowSeconds,
            initialAgeSeconds = initialAge(facts.date, facts.age, requestSeconds, nowSeconds),
            // A 304 that names no lifetime leaves the one the resource had.
            statedLifetimeSeconds = statedLifetime(directives, facts.expires, facts.date, nowSeconds) ?: statedLifetimeSeconds,
            noCache = directives.noCache || (facts.cacheControl.isEmpty() && noCache),
        )
    }

    fun encode(): ByteArray = buildString {
        append(RECORD_HEADER).append('\n')
        fun line(key: String, value: Any?) {
            if (value != null) append(key).append('=').append(value.toString().replace('\n', ' ')).append('\n')
        }
        line("location", location)
        line("size", size)
        line("ranged", ranged)
        line("type", contentType)
        line("etag", entityTag)
        line("modified", lastModified)
        line("date", date)
        line("response", responseSeconds)
        line("age", initialAgeSeconds)
        line("lifetime", statedLifetimeSeconds)
        line("nocache", noCache)
    }.encodeToByteArray()

    companion object {
        private const val RECORD_HEADER = "kite-segment 1"
        private const val HEURISTIC_FRACTION = 10
        private const val MAX_HEURISTIC_SECONDS = 86_400L

        /** The record in [bytes], or null when they are not a record this version wrote. */
        fun decode(bytes: ByteArray): StoredResponse? {
            val lines = bytes.decodeToString().split('\n')
            if (lines.firstOrNull() != RECORD_HEADER) return null
            val fields = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val equals = line.indexOf('=')
                if (equals > 0) fields[line.substring(0, equals)] = line.substring(equals + 1)
            }
            return StoredResponse(
                location = fields["location"] ?: return null,
                size = fields["size"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null,
                ranged = fields["ranged"] == "true",
                contentType = fields["type"],
                entityTag = fields["etag"],
                lastModified = fields["modified"],
                date = fields["date"],
                responseSeconds = fields["response"]?.toLongOrNull() ?: return null,
                initialAgeSeconds = fields["age"]?.toLongOrNull() ?: return null,
                statedLifetimeSeconds = fields["lifetime"]?.toLongOrNull(),
                noCache = fields["nocache"] == "true",
            )
        }

        /**
         * The record of a 200 or 206 response to a GET, or null when the store must not keep it:
         * `no-store`, `Vary: *`, or nothing to judge it by later, which is a strong entity tag, a
         * `Last-Modified` or a stated lifetime.
         */
        fun of(
            facts: ResponseFacts,
            location: String,
            size: Long,
            ranged: Boolean,
            contentType: String?,
            requestSeconds: Long,
            nowSeconds: Long,
        ): StoredResponse? {
            val directives = cacheDirectives(facts.cacheControl)
            if (directives.noStore) return null
            if (varyNames(facts.vary) == null) return null
            val tag = strongTag(facts.entityTag)
            val modified = facts.lastModified?.takeIf { httpDateSeconds(it) != null }
            val lifetime = statedLifetime(directives, facts.expires, facts.date, nowSeconds)
            if (tag == null && modified == null && lifetime == null) return null
            return StoredResponse(
                location = location,
                size = size,
                ranged = ranged,
                contentType = contentType,
                entityTag = tag,
                lastModified = modified,
                date = facts.date,
                responseSeconds = nowSeconds,
                initialAgeSeconds = initialAge(facts.date, facts.age, requestSeconds, nowSeconds),
                statedLifetimeSeconds = lifetime,
                noCache = directives.noCache,
            )
        }

        /** [tag] when it is a strong entity tag, which alone can prove two ranges are of one file. */
        fun strongTag(tag: String?): String? = tag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }

        /** `max-age`, or else `Expires` less `Date`. An `Expires` that is no date has already passed. */
        private fun statedLifetime(directives: CacheDirectives, expires: String?, date: String?, nowSeconds: Long): Long? {
            directives.maxAgeSeconds?.let { return it }
            if (expires == null) return null
            val until = httpDateSeconds(expires) ?: return 0
            return (until - (httpDateSeconds(date) ?: nowSeconds)).coerceAtLeast(0)
        }

        /** The corrected initial age of RFC 9111, 4.2.3. */
        private fun initialAge(date: String?, age: String?, requestSeconds: Long, nowSeconds: Long): Long {
            val apparent = httpDateSeconds(date)?.let { (nowSeconds - it).coerceAtLeast(0) } ?: 0
            val stated = age?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            return maxOf(apparent, stated + (nowSeconds - requestSeconds).coerceAtLeast(0))
        }
    }
}

/** The SHA-256 of [input] in lower case hexadecimal. Names in the store are digests, so no address or login is written to it. */
internal fun sha256Hex(input: ByteArray): String {
    val h = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLength = input.size.toLong() * 8
    val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (block in padded.indices step 64) {
        for (i in 0 until 16) {
            val at = block + i * 4
            w[i] = ((padded[at].toInt() and 0xFF) shl 24) or ((padded[at + 1].toInt() and 0xFF) shl 16) or
                ((padded[at + 2].toInt() and 0xFF) shl 8) or (padded[at + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + SHA256_K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    return buildString(64) {
        for (word in h) append(word.toUInt().toString(16).padStart(8, '0'))
    }
}

private val SHA256_K = longArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
).map { it.toInt() }.toIntArray()
