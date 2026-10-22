@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import java.util.ServiceLoader

internal actual fun platformMediaIoProviders(): List<MediaIoResolverProvider> =
    ServiceLoader.load(MediaIoResolverProvider::class.java, MediaIoResolverProvider::class.java.classLoader)
        .iterator().asSequence().toList()
