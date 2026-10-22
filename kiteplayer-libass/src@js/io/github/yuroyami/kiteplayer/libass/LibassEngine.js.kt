package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.TypesetFrame

/**
 * No engine on this target, by design. The web engine lives in the wasmJs variant; the JavaScript
 * variant is the unavailable facade the standard entry points keep so one dependency graph
 * resolves on every target. The provider is honest: [open] answers null and nothing registers.
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
