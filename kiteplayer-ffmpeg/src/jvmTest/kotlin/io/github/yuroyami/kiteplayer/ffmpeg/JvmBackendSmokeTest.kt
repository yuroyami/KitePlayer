package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpeg
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one-line proof that the desktop JVM has a REAL backend, not the placeholder it carried
 * until phase W.
 *
 * It is deliberately separate from the matrix: if the JNI library ever stops loading, this fails
 * with the identity's own refusal message instead of twenty-seven confusing matrix rows.
 */
class JvmBackendSmokeTest {

    @Test
    fun theJvmVariantIsARealFFmpegBuild() {
        val identity = FFmpeg.identity
        assertTrue(identity.isAcceptable, "FFmpeg identity refused: ${identity.provisioning}")
        assertTrue(FFmpeg.hasDecoder("h264"), "no h264 decoder on the JVM")
        assertTrue(FFmpeg.hasDecoder("hevc"), "no hevc decoder on the JVM")
        assertTrue(FFmpeg.hasFilter("scale"), "no scale filter on the JVM")
        println("jvm FFmpeg avcodec=${FFmpeg.versions.avcodec}")
    }
}
