package io.github.yuroyami.kiteplayer.internal

internal actual fun readExternalTextOrNull(path: String): String? {
    val file = java.io.File(path)
    if (!file.isFile || !file.canRead()) return null
    return runCatching { file.readText() }.getOrNull()
}

internal actual val playerPlatformName: String = "android"
