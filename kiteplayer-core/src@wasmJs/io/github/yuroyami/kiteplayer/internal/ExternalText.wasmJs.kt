package io.github.yuroyami.kiteplayer.internal

/** The browser has no filesystem path to read; the caller warns typed. */
internal actual fun readExternalFile(path: String, limit: Int): ExternalFile = ExternalFile.Unreadable

internal actual val playerPlatformName: String = "wasm"
