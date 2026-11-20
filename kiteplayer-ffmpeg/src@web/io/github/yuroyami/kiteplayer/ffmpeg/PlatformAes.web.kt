package io.github.yuroyami.kiteplayer.ffmpeg

/** The browser's cipher answers only asynchronously, and the reader needs the bytes now, so the Kotlin one runs (#866). */
internal actual fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = null
