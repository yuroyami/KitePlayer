@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
import java.nio.file.Paths

/**
 * Reads a local file through [MediaIo.ofPath][ofPath] for an item that needs a Kotlin reader of it, which is
 * a file still being written (#430). It serves local files, so automatic resolution never asks it
 * and every other local file stays on FFmpeg's own reader. Found by ServiceLoader, so a public class
 * in bytecode with a constructor that takes nothing.
 */
internal class LocalFileResolverProvider : MediaIoResolverProvider {
    override val id: String = LOCAL_FILE_RESOLVER_ID

    override val servesLocalFiles: Boolean get() = true

    override fun create(): MediaIoResolver = object : MediaIoResolver {
        override suspend fun resolve(uri: String): MediaIo = MediaIo.ofPath(Paths.get(uri)).open()
    }
}
