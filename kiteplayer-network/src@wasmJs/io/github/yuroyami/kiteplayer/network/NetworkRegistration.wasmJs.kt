@file:OptIn(kotlin.ExperimentalStdlibApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.spi.MediaIoProviders
import kotlin.EagerInitialization

// A pinned-toolchain hook. Optimized dependency-only consumer tests guard linker retention.
@EagerInitialization
private val networkRegistration: Unit = MediaIoProviders.register(KtorMediaIoResolverProvider())
