@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider

internal actual fun platformSubtitleTypesetterProviders(): List<SubtitleTypesetterProvider> = emptyList()
