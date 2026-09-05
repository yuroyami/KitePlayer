@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NetworkConfig
import io.github.yuroyami.kiteplayer.spi.MediaIoProviders

/** One precedence rule for every session open and rebuild, including direct core construction. */
internal suspend fun resolveMediaIo(
    item: MediaItem,
    config: NetworkConfig,
    automatic: suspend (String, Map<String, String>) -> MediaIo? = MediaIoProviders::resolve,
): MediaIo? = when {
    item.io != null -> item.io.invoke()
    config.ioResolver != null -> config.ioResolver.resolve(item.uri, item.headers)
    config.autoResolve -> automatic(item.uri, item.headers)
    else -> null
}
