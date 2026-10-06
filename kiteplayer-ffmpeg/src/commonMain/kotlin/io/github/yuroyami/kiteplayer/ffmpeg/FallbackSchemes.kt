package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException

/**
 * The schemes FFmpeg's own protocols open in this build, in lower case: the URL fallback refuses
 * every other scheme before it opens anything. It must name exactly the protocols KiteFFmpeg is
 * built with, which `LiveSchemeTest` checks against the library. Empty on the web, where FFmpeg
 * has no file system and no sockets, so every address there needs a reader.
 */
internal expect val fallbackSchemes: Set<String>

/**
 * The schemes whose sender pushes media at the pace it plays. A source opened on one of them, with
 * no duration, is a real-time source. A `tcp://` stream is not one: TCP holds a sender back to the
 * pace the reader reads, so what has arrived says nothing about how late the picture is.
 */
internal val realTimeSchemes: Set<String> = setOf("udp", "rtp", "rtsp", "rtmp")

/**
 * The scheme of [uri] as FFmpeg reads it, in lower case: the run of letters, digits, `+`, `-` and
 * `.` before the first colon. An address with none, and a one-letter scheme, which is a Windows
 * drive, is a path, whose scheme is `file`.
 */
internal fun fallbackScheme(uri: String): String {
    val end = uri.indexOfFirst { !it.isSchemeCharacter() }
    if (end < 2 || uri[end] != ':') return "file"
    return uri.substring(0, end).lowercase()
}

private fun Char.isSchemeCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '+' || this == '-' || this == '.'

/**
 * The address the URL fallback hands FFmpeg for [uri]. FFmpeg compares a protocol's name exactly, so
 * the scheme goes in lower case. An `rtp://` address also carries the read timeout in its query
 * unless it names one: FFmpeg's RTP reader waits for the first packet through the protocol alone,
 * which takes its timeout from the address and from no option.
 */
internal fun fallbackAddress(uri: String): String {
    val scheme = fallbackScheme(uri)
    if (scheme == "file") return uri
    val address = scheme + uri.substring(scheme.length)
    if (scheme != "rtp") return address
    val query = address.substringAfter('?', "")
    if (query.split('&').any { it.startsWith("timeout=") }) return address
    val separator = if ('?' in address) "&" else "?"
    return "$address${separator}timeout=${URL_FALLBACK_READ_TIMEOUT.inWholeMicroseconds}"
}

/**
 * Refuses [uri] when FFmpeg has no protocol for its scheme in this build, with
 * [PlaybackError.SchemeUnsupported], before anything is opened or sent.
 */
internal fun requireFallbackScheme(uri: String) {
    val scheme = fallbackScheme(uri)
    if (scheme in fallbackSchemes) return
    throw PlaybackException(PlaybackError.SchemeUnsupported(uri, scheme, schemeAdvice(scheme)))
}

/** What would open an address of [scheme], or null when there is nothing to say. */
private fun schemeAdvice(scheme: String): String? = when {
    fallbackSchemes.isEmpty() ->
        "A web page has no sockets and no file system of its own, so an address plays through " +
            "kiteplayer-network or a reader on the item"
    scheme == "https" -> "An https address plays through kiteplayer-network, or a reader on the item"
    scheme == "rtmps" || scheme == "rtsps" -> "It needs TLS, which FFmpeg here does not carry"
    scheme == "srt" -> "SRT needs libsrt, which this build does not carry"
    else -> null
}
