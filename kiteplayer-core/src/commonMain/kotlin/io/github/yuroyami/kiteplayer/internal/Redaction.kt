package io.github.yuroyami.kiteplayer.internal

/**
 * The one redaction law the support bundle uses (SEC-3).
 *
 * The bundle used `substringAfterLast('/')`, which keeps everything after the last slash. That is
 * exactly where credentials live: `https://host/video.mp4?token=SECRET` came out as
 * `video.mp4?token=SECRET`, and support bundles get pasted into issue trackers.
 *
 * A redacted URI is its basename and nothing else. The query and the fragment go first, so no
 * token, signature or session id can survive; anything before the last slash goes with them, so
 * no host, path or `user:password@` userinfo survives either.
 */
internal fun redactUri(uri: String): String {
    val basename = uri.substringBefore('#').substringBefore('?').substringAfterLast('/')
    return basename.ifEmpty { "(redacted)" }
}

/**
 * Every URI inside free text, redacted by [redactUri].
 *
 * Error messages and warnings quote the URI they failed on, so redacting only the dedicated path
 * lines left the secret in the bundle one line further down. Trailing punctuation is put back, so
 * `cannot open https://host/a.mp4?t=S: 404` reads `cannot open a.mp4: 404`.
 */
internal fun redactUrisIn(text: String): String = URI_IN_TEXT.replace(text) { match ->
    val trailing = match.value.takeLastWhile { it in TRAILING_PUNCTUATION }
    redactUri(match.value.dropLast(trailing.length)) + trailing
}

/** `scheme://` and then anything that is not whitespace or a quote. */
private val URI_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]*""")

private const val TRAILING_PUNCTUATION: String = ".,;:!?)]}"
