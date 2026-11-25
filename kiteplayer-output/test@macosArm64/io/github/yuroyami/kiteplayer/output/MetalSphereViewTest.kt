@file:OptIn(ExperimentalForeignApi::class, KitePlayerInternalApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRegionMake2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Metal renderer's view into a 360 degree picture (#586), with real Metal on the host. Every
 * pixel is checked against [SphericalLens], the law the other renderers are tested against,
 * wherever the picture is one colour around the point the lens reads.
 */
class MetalSphereViewTest {

    private class TestFrame : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(PICTURE_WIDTH, PICTURE_HEIGHT, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Bgra
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
    private val composer = MetalFrameComposer(device, MTLPixelFormatRGBA8Unorm)

    /** Four bands of longitude in four colours, darker in the lower half of the sphere. */
    private fun bandAt(across: Double, down: Double): IntArray {
        val band = BANDS[((across * 4).toInt()).coerceIn(0, 3)]
        return if (down < 0.5) band else IntArray(3) { band[it] / 2 }
    }

    private fun picture(): MetalPicture.SoftwarePlanes {
        val bytes = ByteArray(PICTURE_WIDTH * PICTURE_HEIGHT * 4)
        for (y in 0 until PICTURE_HEIGHT) {
            for (x in 0 until PICTURE_WIDTH) {
                val colour = bandAt((x + 0.5) / PICTURE_WIDTH, (y + 0.5) / PICTURE_HEIGHT)
                val at = (y * PICTURE_WIDTH + x) * 4
                bytes[at] = colour[2].toByte()
                bytes[at + 1] = colour[1].toByte()
                bytes[at + 2] = colour[0].toByte()
                bytes[at + 3] = -1
            }
        }
        return MetalPicture.SoftwarePlanes(
            PICTURE_WIDTH, PICTURE_HEIGHT, PlayerPixelFormat.Bgra,
            listOf(MetalPicture.SoftwarePlanes.Plane(bytes, PICTURE_WIDTH * 4, PICTURE_HEIGHT)),
        )
    }

    private fun render(look: SphereLook?): ByteArray {
        val target = device.makeTargetTexture(VIEW_WIDTH, VIEW_HEIGHT, MTLPixelFormatRGBA8Unorm)
        val frame = TestFrame()
        composer.encode(
            target, frame, picture(), null, VIEW_WIDTH, VIEW_HEIGHT,
            sphere = look?.let { packSphereUniforms(it, frame, VIEW_WIDTH, VIEW_HEIGHT) },
        ).waitUntilCompleted()
        val bytes = ByteArray(VIEW_WIDTH * VIEW_HEIGHT * 4)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (VIEW_WIDTH * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, VIEW_WIDTH.toULong(), VIEW_HEIGHT.toULong()),
                mipmapLevel = 0u,
            )
        }
        return bytes
    }

    /** Checks every pixel the lens reads well inside one band; answers how many it checked. */
    private fun assertMatchesTheLens(look: SphereLook): Int {
        val drawn = render(look)
        val lens = SphericalLens(look.projection, look.view, VIEW_WIDTH.toFloat(), VIEW_HEIGHT.toFloat())
        var checked = 0
        for (y in 0 until VIEW_HEIGHT) {
            for (x in 0 until VIEW_WIDTH) {
                val at = (y * VIEW_WIDTH + x) * 4
                val got = IntArray(3) { drawn[at + it].toInt() and 0xFF }
                val point = lens.pictureAt(x + 0.5, y + 0.5)
                if (point == null) {
                    assertTrue(got.all { it == 0 }, "($x, $y) lies outside the tile and should be black, got ${got.toList()}")
                    checked++
                    continue
                }
                if (nearAnEdge(point.across, point.down, look.projection.kind)) continue
                val expected = bandAt(point.across, point.down)
                for (channel in 0 until 3) {
                    assertTrue(
                        abs(got[channel] - expected[channel]) <= 1,
                        "($x, $y) reads ${point.across}, ${point.down}: got ${got.toList()}, expected ${expected.toList()}",
                    )
                }
                checked++
            }
        }
        return checked
    }

    /** True within two picture pixels of a band's edge, where the bilinear read blends two colours. */
    private fun nearAnEdge(across: Double, down: Double, kind: ProjectionKind): Boolean {
        val margin = 2.0 / PICTURE_WIDTH
        val quarter = across * 4
        val fromBand = abs(quarter - kotlin.math.round(quarter)) / 4
        val fromHalf = abs(down - 0.5)
        val fromSeam = minOf(across, 1.0 - across)
        return fromBand < margin || fromHalf < 2.0 / PICTURE_HEIGHT ||
            (kind !is ProjectionKind.Equirectangular && fromSeam < margin)
    }

    @Test
    fun theFrontViewMatchesTheLens() {
        val checked = assertMatchesTheLens(SphereLook(VideoProjection(ProjectionKind.Equirectangular), SphericalView()))
        assertTrue(checked > VIEW_WIDTH * VIEW_HEIGHT / 2, "only $checked pixels were checked")
    }

    @Test
    fun aTurnedViewMatchesTheLensAllTheWayRound() {
        for (yaw in 0 until 360 step 45) {
            val view = SphericalView(yaw = yaw.toFloat(), pitch = 20f, fieldOfView = 100f)
            assertMatchesTheLens(SphereLook(VideoProjection(ProjectionKind.Equirectangular, yaw = 10.0), view))
        }
    }

    @Test
    fun aTileLeavesTheRestOfTheSphereBlack() {
        val tile = ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0)
        val look = SphereLook(VideoProjection(tile), SphericalView(yaw = 60f, fieldOfView = 120f))
        val drawn = render(look)
        val lens = SphericalLens(look.projection, look.view, VIEW_WIDTH.toFloat(), VIEW_HEIGHT.toFloat())
        var missing = 0
        for (y in 0 until VIEW_HEIGHT) {
            for (x in 0 until VIEW_WIDTH) {
                if (lens.pictureAt(x + 0.5, y + 0.5) != null) continue
                missing++
                val at = (y * VIEW_WIDTH + x) * 4
                assertEquals(0, drawn[at].toInt() or drawn[at + 1].toInt() or drawn[at + 2].toInt(), "($x, $y)")
            }
        }
        assertTrue(missing > 0, "the view should reach past the tile")
    }

    @Test
    fun withoutAViewThePictureDrawsFlat() {
        // The whole picture letterboxed: the left edge of the target shows the first band.
        val drawn = render(null)
        val at = (VIEW_HEIGHT / 4 * VIEW_WIDTH + 2) * 4
        assertEquals(BANDS[0].toList(), IntArray(3) { drawn[at + it].toInt() and 0xFF }.toList())
    }

    private companion object {
        const val PICTURE_WIDTH = 256
        const val PICTURE_HEIGHT = 128
        const val VIEW_WIDTH = 64
        const val VIEW_HEIGHT = 48
        val BANDS = listOf(intArrayOf(220, 40, 40), intArrayOf(40, 200, 60), intArrayOf(50, 60, 210), intArrayOf(230, 230, 230))
    }
}
