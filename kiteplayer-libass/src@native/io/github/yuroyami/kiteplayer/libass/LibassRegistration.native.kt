@file:OptIn(kotlin.ExperimentalStdlibApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters
import kotlin.native.EagerInitialization

// The same pinned-toolchain hook kiteplayer-network uses: adding the module is the whole setup.
@EagerInitialization
private val libassRegistration: Unit = SubtitleTypesetters.register(LibassTypesetterProvider())
