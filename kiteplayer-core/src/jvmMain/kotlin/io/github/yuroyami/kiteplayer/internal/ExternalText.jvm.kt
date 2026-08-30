package io.github.yuroyami.kiteplayer.internal

internal actual fun readExternalBytesOrNull(path: String): ByteArray? {
    val file = java.io.File(path)
    if (!file.isFile || !file.canRead()) return null
    return runCatching { file.readBytes() }.getOrNull()
}

internal actual val playerPlatformName: String = "jvm"
