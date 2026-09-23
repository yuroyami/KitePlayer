package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The web build of KiteFFmpeg has no filter graph, so the factory refuses there. The engine then
 * keeps its own resampler; the core's seam tests cover that half.
 */
class KiteFFmpegResamplerWebTest {

    @Test
    fun theWebBuildHasNoFilterGraphSoTheFactoryRefuses() {
        assertFailsWith<FFmpegException> { KiteFFmpegResampler().create(44_100, 48_000, 2) }
    }
}
