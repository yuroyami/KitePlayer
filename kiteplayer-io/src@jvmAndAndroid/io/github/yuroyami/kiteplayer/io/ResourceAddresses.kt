package io.github.yuroyami.kiteplayer.io

/**
 * The asset name an `android_asset` address names (#457), its `%` escapes decoded, or null for
 * another address. Compose Multiplatform's `Res.getUri` gives such an address on Android.
 */
internal fun assetNameOf(uri: String): String? {
    val prefix = "file:///android_asset/"
    if (!uri.startsWith(prefix)) return null
    return percentDecoded(uri.substring(prefix.length).substringBefore('?').substringBefore('#')).takeIf { it.isNotEmpty() }
}

/** [text] with its `%XX` escapes decoded as UTF-8, and a `+` left as it is, as in a path. */
internal fun percentDecoded(text: String): String {
    if ('%' !in text) return text
    val out = java.io.ByteArrayOutputStream(text.length)
    var at = 0
    while (at < text.length) {
        val char = text[at]
        val hex = if (char == '%' && at + 2 < text.length) text.substring(at + 1, at + 3).toIntOrNull(16) else null
        if (hex != null) {
            out.write(hex)
            at += 3
        } else {
            out.write(char.toString().encodeToByteArray())
            at++
        }
    }
    return out.toByteArray().decodeToString()
}
