package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A video filter that cannot be built fails the decode, and leaks nothing: the decoded frame the
 * graph was going to take is closed (#263). The count is KiteFFmpeg's own table of live native
 * handles, read by reflection because it is internal to that library.
 */
class FilterBuildFailureLeakTest {

    private fun liveHandles(): Long {
        val internals = Class.forName("io.github.yuroyami.kiteffmpeg.Internals")
        val instance = internals.getField("INSTANCE").get(null)
        val method = internals.declaredMethods.first { it.name.startsWith("liveHandles") && it.parameterCount == 0 }
        method.isAccessible = true
        return method.invoke(instance) as Long
    }

    @Test
    fun aFilterThatCannotBeBuiltClosesTheFrameItWasGiven() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val before = liveHandles()
        repeat(3) {
            val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteFFmpegSource
            source.videoFilterDescription = "kiteplayer_no_such_filter"
            try {
                val video = source.streams.first { it.kind == TrackKind.Video }
                source.selectStreams(setOf(video.index))
                val decoder = assertNotNull(source.videoDecoderFactories().first().create(video, HwdecPolicy.Off))
                try {
                    val failure = runCatching {
                        while (true) {
                            val packet = source.readPacket() ?: break
                            try {
                                if (packet.streamIndex == video.index) {
                                    while (!decoder.send(packet)) decoder.receive()?.close()
                                    decoder.receive()?.close()
                                }
                            } finally {
                                packet.close()
                            }
                        }
                    }.exceptionOrNull()
                    assertTrue(failure != null, "a filter this build does not carry must fail the decode")
                } finally {
                    decoder.close()
                }
            } finally {
                source.close()
            }
        }
        assertEquals(before, liveHandles(), "every refused build left its decoded frame open")
    }
}
