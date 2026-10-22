@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider
import java.util.ServiceLoader

internal actual fun platformSubtitleTypesetterProviders(): List<SubtitleTypesetterProvider> =
    ServiceLoader.load(SubtitleTypesetterProvider::class.java, SubtitleTypesetterProvider::class.java.classLoader)
        .iterator().asSequence().toList()
