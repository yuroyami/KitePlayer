package io.github.yuroyami.kiteplayer.internal

/** The browser has no filesystem path to read; the caller warns typed. */
internal actual fun readExternalBytesOrNull(path: String): ByteArray? = null

internal actual val playerPlatformName: String = "wasm"
