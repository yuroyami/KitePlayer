package io.github.yuroyami.kiteplayer.internal

/** The scheme, host and port of an http or https URI: together they name one server. */
internal data class HttpOrigin(val scheme: String, val host: String, val port: Int)

/**
 * The origin of [uri], or null when [uri] is not an http or https URI in plain form.
 *
 * The authority must be a host of ASCII letters, digits, `-`, `.` and `_`, or a bracketed IPv6
 * literal, with an optional port of 1 to 65535. User information, a backslash, a percent sign,
 * whitespace or any other character there gives null, because URL parsers disagree about which
 * host such a URI names. Hosts compare in ASCII lower case, and a missing port is the scheme's
 * default. Other spellings of one server, such as a trailing dot, compare as different origins.
 */
internal fun httpOriginOf(uri: String): HttpOrigin? {
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = asciiLowercase(uri.substring(0, schemeEnd))
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> return null
    }
    val start = schemeEnd + 3
    var end = start
    while (end < uri.length && uri[end] != '/' && uri[end] != '?' && uri[end] != '#') end++
    val authority = uri.substring(start, end)

    val hostEnd = if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 2) return null
        for (i in 1 until close) if (!authority[i].isIpv6LiteralChar()) return null
        close + 1
    } else {
        var at = 0
        while (at < authority.length && authority[at].isHostChar()) at++
        if (at == 0) return null
        at
    }
    val port = when {
        hostEnd == authority.length -> defaultPort
        authority[hostEnd] != ':' -> return null
        else -> portOf(authority.substring(hostEnd + 1)) ?: return null
    }
    return HttpOrigin(scheme, asciiLowercase(authority.substring(0, hostEnd)), port)
}

/** True when [a] and [b] are both http or https URIs with one [HttpOrigin]. */
internal fun sameHttpOrigin(a: String, b: String): Boolean {
    val origin = httpOriginOf(a) ?: return false
    return origin == httpOriginOf(b)
}

/** 1 to 5 ASCII digits naming a port from 1 to 65535, or null. */
private fun portOf(text: String): Int? {
    if (text.isEmpty() || text.length > 5 || text.any { it !in '0'..'9' }) return null
    return text.toInt().takeIf { it in 1..65_535 }
}

private fun Char.isHostChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '.' || this == '_'

private fun Char.isIpv6LiteralChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F' || this == ':' || this == '.'

/** Lower case for ASCII letters only, so no other character can fold into one. */
private fun asciiLowercase(text: String): String =
    buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }
