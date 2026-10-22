@file:OptIn(kotlin.ExperimentalStdlibApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters
import kotlin.EagerInitialization

// The same pinned-toolchain hook kiteplayer-network uses on the web: adding the module is the setup.
@EagerInitialization
private val libassRegistration: Unit = SubtitleTypesetters.register(LibassTypesetterProvider())
