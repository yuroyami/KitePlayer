@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider

internal actual fun platformMediaIoProviders(): List<MediaIoResolverProvider> = emptyList()
