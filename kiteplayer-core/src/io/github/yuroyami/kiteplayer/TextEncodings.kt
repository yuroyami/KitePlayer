package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.decodeSubtitleBytes

/**
 * Text whose encoding nobody stated, read as the player reads a subtitle file (#423): by its
 * byte-order mark, as UTF-8 when its bytes are valid UTF-8, and otherwise in the single-byte table
 * its bytes read most likely in, such as windows-1251 for Russian or windows-1252 for French. An
 * internet radio station's song titles arrive this way, often not in UTF-8, as mpv found.
 */
public object TextEncodings {

    /**
     * [bytes] as text. [languageHint], a language the text is likely in, settles a reading the bytes
     * leave close and never overrules a clear one. A byte no table reads becomes U+FFFD.
     */
    public fun decode(bytes: ByteArray, languageHint: String? = null): String =
        decodeSubtitleBytes(bytes, languageHint).text
}
