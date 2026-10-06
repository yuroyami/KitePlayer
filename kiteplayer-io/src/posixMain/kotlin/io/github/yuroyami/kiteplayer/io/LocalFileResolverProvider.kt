@file:OptIn(kotlin.ExperimentalStdlibApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoProviders
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import kotlin.native.EagerInitialization

/**
 * Reads a local file through [MediaIo.ofPath] for an item that needs a Kotlin reader of it, which is
 * a file still being written (#430). It serves local files, so automatic resolution never asks it
 * and every other local file stays on FFmpeg's own reader.
 */
internal class LocalFileResolverProvider : MediaIoResolverProvider {
    override val id: String = "io.github.yuroyami.kiteplayer.io.files"

    override val servesLocalFiles: Boolean get() = true

    override fun create(): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo = MediaIo.ofPath(uri).open()
    }
}

// A pinned-toolchain hook, as the network module's registration is.
@EagerInitialization
private val localFileRegistration: Unit = MediaIoProviders.register(LocalFileResolverProvider())
