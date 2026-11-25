@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.CoreGraphics.CGSizeMake
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A renderer gives a software picture's arrays back once it stops reading them (#840), so a
 * resolver can take them from a pool: the Metal renderer when the next picture replaces the one it
 * keeps for a redraw, and the sample buffer renderer as soon as it has copied the planes.
 */
class SoftwarePlanesReleaseTest {

    private class TestFrame : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(SIZE, SIZE, 1, 1)
        override val pixelFormat = PlayerPixelFormat.Nv12
        override val colorSpace = ColorSpaceInfo()
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    private val lock = SynchronizedObject()
    private val released = mutableListOf<Int>()
    private var made = 0

    private fun releasedNow(): List<Int> = synchronized(lock) { released.toList() }

    private fun picture(): MetalPicture.SoftwarePlanes {
        val id = synchronized(lock) { made++ }
        return MetalPicture.SoftwarePlanes(
            width = SIZE,
            height = SIZE,
            format = PlayerPixelFormat.Nv12,
            planes = listOf(
                MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE) { 100 }, SIZE, SIZE),
                MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE / 2) { 128.toByte() }, SIZE, SIZE / 2),
            ),
            onRelease = { synchronized(lock) { released += id } },
        )
    }

    @Test
    fun releaseRunsOnlyOnce() {
        val one = picture()
        one.release()
        one.release()
        assertEquals(listOf(0), releasedNow())
    }

    @Test
    fun theMetalRendererReleasesAPictureWhenTheNextOneReplacesIt() = runBlocking {
        val layer = CAMetalLayer()
        layer.setDrawableSize(CGSizeMake(SIZE.toDouble(), SIZE.toDouble()))
        val renderer = MetalVideoRenderer(layer, MetalPictureResolver { picture() })
        try {
            renderer.showOne(1)
            assertEquals(emptyList(), releasedNow(), "the shown picture is kept for a redraw")
            renderer.showOne(2)
            assertEquals(listOf(0), releasedNow(), "the first picture went back when the second replaced it")
        } finally {
            renderer.close()
        }
        assertEquals(listOf(0, 1), releasedNow(), "close gives back the kept picture")
    }

    @Test
    fun theSampleBufferRendererReleasesAPictureOnceItIsCopied() = runBlocking {
        val renderer = SampleBufferVideoRenderer(AVSampleBufferDisplayLayer(), MetalPictureResolver { picture() })
        try {
            renderer.present(TestFrame(), 0L)
            assertEquals(listOf(0), releasedNow())
            renderer.present(TestFrame(), 0L)
            assertEquals(listOf(0, 1), releasedNow())
        } finally {
            renderer.close()
        }
    }

    private suspend fun MetalVideoRenderer.showOne(count: Long) {
        present(TestFrame(), 0L)
        withTimeoutOrNull(10.seconds) { while (presentedFrames + failedFrames < count) delay(5) }
        assertEquals(count, presentedFrames, "frames drawn")
    }

    private companion object {
        const val SIZE = 16
    }
}
