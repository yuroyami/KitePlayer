@file:OptIn(KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecSource
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecSourceFactory
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The S2.d measured exit on the iOS simulator (KV-1's exit through KV-2's path): real media,
 * the platform decoder policy, the production convert and image seams, and the frame cost
 * instrument read at the end. The number lands in the log beside A3's Android software
 * baseline; simulator numbers are provisional by the standing rule.
 */
@OptIn(ExperimentalForeignApi::class)
class KiteVideoGpuPathSimTest {

    /** The same run through the CPU converter, so the two numbers sit side by side. */
    @Test
    fun theCpuPathOnThisSimulatorForComparison() = runBlocking {
        val mediaDir = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteCodecSource
        try {
            val stream = assertNotNull(
                source.streams.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt },
            )
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(
                source.videoDecoderFactories().first().create(stream, HwdecPolicy.Off),
            )
            var published = 0
            val state = KiteVideoState()
            val cpuRenderer = KiteVideoRenderer(
                convert = { frame ->
                    io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter.toRgba(
                        frame as io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame,
                    )
                },
                makeImage = { rgba, w, h -> FrameImagePool().imageFor(rgba, w, h) },
                publish = { if (it != null) published++ },
            )
            try {
                var presented = 0
                while (presented < 60) {
                    val packet = source.readPacket() ?: break
                    if (packet.streamIndex != stream.index) { packet.close(); continue }
                    var consumed = false
                    while (!consumed) {
                        consumed = decoder.send(packet)
                        while (true) {
                            val frame = decoder.receive() ?: break
                            cpuRenderer.present(frame, 0L)
                            presented++
                            var waited = 0
                            while (published < presented && waited < 4_000) {
                                platform.posix.usleep(1_000u)
                                waited++
                            }
                        }
                    }
                    packet.close()
                }
                val cost = cpuRenderer.costSnapshot()
                println(
                    "cpu-path cost: samples=${cost.samples} " +
                        "avgMs=${cost.averageNanos / 1_000_000.0} worstMs=${cost.worstNanos / 1_000_000.0} " +
                        "presented=$published failed=${cpuRenderer.failedFrames}",
                )
                assertTrue(published >= 50)
            } finally {
                cpuRenderer.close()
                decoder.close()
            }
            state.renderer.close()
        } finally {
            source.close()
        }
    }

    @Test
    fun kiteVideoPlaysRealMediaThroughTheGpuPathAndTheCostIsMeasured() = runBlocking {
        val mediaDir = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteCodecSource
        try {
            val stream = assertNotNull(
                source.streams.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt },
            )
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(
                source.videoDecoderFactories().first().create(stream, HwdecPolicy.Auto),
            )
            println("gpu-path hwdec: ${decoder.hardware}")

            val state = KiteVideoState()
            val renderer = state.renderer
            try {
                var presented = 0
                while (presented < 60) {
                    val packet = source.readPacket() ?: break
                    if (packet.streamIndex != stream.index) {
                        packet.close()
                        continue
                    }
                    var consumed = false
                    while (!consumed) {
                        consumed = decoder.send(packet)
                        while (true) {
                            val frame = decoder.receive() ?: break
                            renderer.present(frame, 0L)
                            presented++
                            // Presentation is newest-wins; pacing by the published count keeps
                            // this a CONVERSION measurement rather than a supersede benchmark.
                            var waited = 0
                            while (state.presentedFrames < presented && waited < 2_000) {
                                platform.posix.usleep(1_000u)
                                waited++
                            }
                        }
                    }
                    packet.close()
                }
                val cost = state.frameCost
                println(
                    "gpu-path cost: samples=${cost.samples} " +
                        "avgMs=${cost.averageNanos / 1_000_000.0} worstMs=${cost.worstNanos / 1_000_000.0} " +
                        "presented=${state.presentedFrames} failed=${state.failedFrames}",
                )
                assertTrue(state.presentedFrames >= 50, "published ${state.presentedFrames} of $presented presented")
                assertTrue(cost.samples >= 50, "the cost instrument sampled ${cost.samples}")
                assertTrue(state.failedFrames == 0L, "${state.failedFrames} frames failed")
            } finally {
                renderer.close()
                decoder.close()
            }
        } finally {
            source.close()
        }
    }
}
