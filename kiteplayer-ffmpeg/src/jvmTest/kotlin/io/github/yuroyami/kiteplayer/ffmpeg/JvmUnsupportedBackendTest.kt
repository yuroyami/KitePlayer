package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kitecodec.FFmpeg
import io.github.yuroyami.kitecodec.FFmpegError
import io.github.yuroyami.kitecodec.FFmpegException
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class JvmUnsupportedBackendTest {

    @Test
    fun publicJvmBackendRefusesPredictablyWithoutJni() = runBlocking {
        assertFalse(FFmpeg.identity.isAcceptable)

        val failure = assertFailsWith<FFmpegException> {
            KiteCodecMediaBackend().open(MediaItem("https://example.invalid/video.mp4"))
        }

        assertIs<FFmpegError.Unsupported>(failure.error)
        assertContains(failure.message.orEmpty(), "placeholder backend")
        assertContains(failure.message.orEmpty(), "not implemented yet")
    }
}
