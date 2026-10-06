package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.net.JarURLConnection
import java.net.URI

/**
 * Plays the address a Compose Multiplatform resource has in a packaged desktop app (#457), the
 * `jar:file:...!/composeResources/...` that `Res.getUri` returns there, by reading the entry out of
 * the app's jar. The entry seeks, by opening it again when a seek goes back, because it is stored
 * compressed. Null for any other address, which then plays as it is: a `file:` address through
 * FFmpeg's own file reader, as when `Res.getUri` runs from the build directory.
 *
 * The player does this by itself for an item with no reader of its own, so `MediaItem(Res.getUri(...))`
 * plays as it is. This door is for an app that configures a resolver of its own, which the player
 * asks first and alone.
 */
public fun MediaIo.Companion.ofResourceUri(uri: String): MediaIoFactory? {
    if (!uri.startsWith("jar:", ignoreCase = true)) return null
    val url = URI(uri).toURL()
    return MediaIoFactory {
        val connection = url.openConnection() as JarURLConnection
        val entry = connection.jarEntry ?: throw MediaIoException("$uri names no entry of its jar")
        val size = entry.size.takeIf { it >= 0 } ?: throw MediaIoException("$uri does not say how long it is")
        ReopeningStreamMediaIo({ (url.openConnection() as JarURLConnection).inputStream }, size)
    }
}
