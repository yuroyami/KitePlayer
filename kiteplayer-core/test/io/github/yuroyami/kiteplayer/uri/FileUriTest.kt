package io.github.yuroyami.kiteplayer.uri

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(KitePlayerInternalApi::class)
class FileUriTest {
    /** A percent escape is `%` and two hexadecimal digits; a sign is not one (#875). */
    @Test
    fun aPercentSignBeforeASignStaysLiteral() {
        assertEquals("/tmp/100%-1.mp4", FileUri.localPath("file:///tmp/100%-1.mp4"))
        assertEquals("/tmp/100%+1.mp4", FileUri.localPath("file:///tmp/100%+1.mp4"))
        assertEquals("/tmp/a b.mp4", FileUri.localPath("file:///tmp/a%20b.mp4"))
        assertEquals("/tmp/é.mp4", FileUri.localPath("file:///tmp/%C3%a9.mp4"))
        assertEquals("/tmp/50%.mp4", FileUri.localPath("file:///tmp/50%.mp4"))
    }
}
