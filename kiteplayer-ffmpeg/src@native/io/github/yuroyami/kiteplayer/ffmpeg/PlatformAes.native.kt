package io.github.yuroyami.kiteplayer.ffmpeg

/** No platform cipher is reached from here yet, so the Kotlin one runs (#866). */
internal actual fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = null
