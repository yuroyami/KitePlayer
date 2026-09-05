package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame

/**
 * No engine on this target yet: the browser build of the chain is its own piece of work, tracked
 * in the issue tracker. The module resolves here so the standard entry points keep one dependency
 * graph across targets, and the provider is honest: [open] answers null and nothing registers.
 */
internal actual class LibassEngine private constructor() : AutoCloseable {
    actual fun openTrack(header: ByteArray): Boolean = false
    actual fun openDocument(script: ByteArray): Boolean = false
    actual fun addEvent(payload: ByteArray, startMillis: Long, durationMillis: Long) {}
    actual fun clearEvents() {}
    actual fun addFont(name: String, data: ByteArray) {}
    actual fun setFrame(frame: TypesetFrame) {}
    actual fun render(timeMillis: Long): ByteArray? = null
    actual override fun close() {}

    actual companion object {
        actual fun open(): LibassEngine? = null
        actual fun libraryVersion(): Int = 0
    }
}

internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> = emptyList()

internal actual fun defaultFontDirectories(): List<String> = emptyList()

internal actual val platformNeedsSystemFontFiles: Boolean = false
