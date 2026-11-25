@file:OptIn(ExperimentalForeignApi::class, KitePlayerInternalApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRegionMake2D
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Metal renderer's capability flags match what it draws (#722). The engine warns about picture
 * controls and 360 degree video from these flags, so a flag that says yes while the renderer draws
 * nothing would hide the warning. Each check draws through the composer with the uniforms the
 * renderer packs from the same setting.
 */
class MetalCapabilityTest {

    private class TestFrame(width: Int, height: Int) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Bgra
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
    private val renderer = MetalVideoRenderer(CAMetalLayer(), MetalPictureResolver { null })

    /** A BGRA picture whose left half is [left] and right half is [right]. */
    private fun picture(width: Int, height: Int, left: Int, right: Int): MetalPicture.SoftwarePlanes {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val value = if (x < width / 2) left else right
            val at = (y * width + x) * 4
            bytes[at] = value.toByte()
            bytes[at + 1] = value.toByte()
            bytes[at + 2] = value.toByte()
            bytes[at + 3] = -1
        }
        return MetalPicture.SoftwarePlanes(width, height, PlayerPixelFormat.Bgra, listOf(MetalPicture.SoftwarePlanes.Plane(bytes, width * 4, height)))
    }

    /** The red channel at the target's centre. */
    private fun centre(
        picture: MetalPicture.SoftwarePlanes,
        adjust: FloatArray = DISABLED_ADJUST_UNIFORMS,
        sphere: ((VideoFrame) -> FloatArray)? = null,
    ): Int {
        val composer = MetalFrameComposer(device, MTLPixelFormatRGBA8Unorm)
        try {
            val target = device.makeTargetTexture(VIEW, VIEW, MTLPixelFormatRGBA8Unorm)
            val frame = TestFrame(picture.width, picture.height)
            composer.encode(target, frame, picture, null, VIEW, VIEW, adjustUniforms = adjust, sphere = sphere?.invoke(frame)).waitUntilCompleted()
            val bytes = ByteArray(VIEW * VIEW * 4)
            bytes.usePinned { pinned ->
                target.getBytes(
                    pinned.addressOf(0),
                    bytesPerRow = (VIEW * 4).toULong(),
                    fromRegion = MTLRegionMake2D(0u, 0u, VIEW.toULong(), VIEW.toULong()),
                    mipmapLevel = 0u,
                )
            }
            return bytes[(VIEW / 2 * VIEW + VIEW / 2) * 4].toInt() and 0xFF
        } finally {
            composer.close()
        }
    }

    @Test
    fun appliesAdjustmentsSaysWhetherABrighterPictureIsDrawn() {
        val grey = picture(32, 32, 128, 128)
        val plain = centre(grey, packAdjustUniforms(VideoAdjustments()))
        val brighter = centre(grey, packAdjustUniforms(VideoAdjustments(brightness = 0.3f)))
        val applies = renderer.appliesAdjustments
        assertEquals(applies, brighter != plain, "appliesAdjustments is $applies and the picture read $plain then $brighter")
    }

    @Test
    fun projectsSaysWhetherTheViewTurnsThePicture() {
        // Dark on the left half of the sphere, light on the right: the front view and the view
        // behind look at different halves only when the view is drawn.
        val sphere = picture(64, 32, 40, 220)
        val whole = VideoProjection(ProjectionKind.Equirectangular)
        fun look(yaw: Float): (VideoFrame) -> FloatArray =
            { frame -> packSphereUniforms(SphereLook(whole, SphericalView(yaw = yaw, fieldOfView = 60f)), frame, VIEW, VIEW) }
        val front = centre(sphere, sphere = look(90f))
        val behind = centre(sphere, sphere = look(-90f))
        val projects = renderer.projects(whole.kind)
        assertEquals(projects, front != behind, "projects is $projects and the two views read $front and $behind")
    }

    private companion object {
        const val VIEW = 16
    }
}
