package io.github.yuroyami.kiteplayer.libass

/** This platform's libass enumerates system fonts through its own provider; nothing is scanned. */
internal actual fun readFontFiles(directories: List<String>, budgetBytes: Long): List<Pair<String, ByteArray>> = emptyList()

internal actual fun defaultFontDirectories(): List<String> = emptyList()

internal actual val platformNeedsSystemFontFiles: Boolean = false
