@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NetworkConfig
import io.github.yuroyami.kiteplayer.spi.MediaIoProviders

/**
 * One precedence rule for every session open and rebuild, including direct core construction.
 *
 * A file still being written ([MediaItem.growth]) is read through Kotlin (#430), so a local path
 * that automatic resolution leaves to the backend goes to a provider that serves local files. An
 * explicit resolver's answer stands, as it does for every item, and so does turning automatic
 * resolution off.
 */
internal suspend fun resolveMediaIo(
    item: MediaItem,
    config: NetworkConfig,
    localFile: suspend (String) -> MediaIo? = MediaIoProviders::resolveLocalFile,
    automatic: suspend (String, Map<String, String>) -> MediaIo? = MediaIoProviders::resolve,
): MediaIo? = when {
    item.io != null -> item.io.open()
    config.ioResolver != null -> config.ioResolver.resolve(item.uri, item.headers)
    config.autoResolve -> automatic(item.uri, item.headers)
        ?: if (item.growth != null) localPathOf(item.uri)?.let { localFile(it) } else null
    else -> null
}

/**
 * The local file [uri] names, a bare path or a `file:` address, as FFmpeg's file reader reads it, or
 * null for any other scheme. A single letter before the colon is a Windows drive, not a scheme.
 */
internal fun localPathOf(uri: String): String? {
    if (uri.startsWith("file://")) return uri.removePrefix("file://").ifEmpty { null }
    if (uri.startsWith("file:")) return uri.removePrefix("file:").ifEmpty { null }
    val colon = uri.indexOf(':')
    if (colon < 2) return uri.ifEmpty { null }
    val scheme = uri.substring(0, colon)
    val looksLikeScheme = scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    return if (looksLikeScheme) null else uri
}
