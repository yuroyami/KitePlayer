package io.github.yuroyami.kiteplayer.ffmpeg

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The JVM's AES, which the JIT runs on the processor's AES instructions (#866). */
internal actual fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/CBC/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }
} catch (_: java.security.GeneralSecurityException) {
    // A runtime without the cipher, which no standard one is, keeps the Kotlin fallback.
    null
}
