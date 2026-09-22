package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import platform.Foundation.NSURL

/**
 * Plays a file URL, such as one from a document picker. Each open starts security-scoped access to
 * [url], and the reader stops that access when it closes. So a picked file stays readable while
 * the player reads it, and the caller has nothing to release.
 */
public fun MediaIo.Companion.ofUrl(url: NSURL): MediaIoFactory = MediaIoFactory {
    val path = url.path?.takeIf { url.fileURL } ?: throw MediaIoException("Not a file URL: ${url.absoluteString}")
    // False for a URL that needs no scope, such as one inside the app's own container. That is
    // not an error. It only says that there is nothing to stop later.
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        ScopedMediaIo(PosixFileMediaIo.open(path), url, scoped)
    } catch (failure: Throwable) {
        if (scoped) url.stopAccessingSecurityScopedResource()
        throw failure
    }
}

/** Stops the security scope of [url] after [inner] closes, once. */
internal class ScopedMediaIo(
    private val inner: MediaIo,
    private val url: NSURL,
    private var scoped: Boolean,
) : MediaIo by inner {
    override fun close() {
        inner.close()
        if (scoped) {
            scoped = false
            url.stopAccessingSecurityScopedResource()
        }
    }
}
