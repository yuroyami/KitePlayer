@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGSizeMake
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLRegionMake2D
import platform.QuartzCore.CAMetalLayer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The Metal renderer dims a flashing picture and leaves every other picture alone (#500). */
class MetalFlashGuardTest {

    private class TestFrame : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(SIZE, SIZE, 1, 1)
        override val pixelFormat = PlayerPixelFormat.Nv12
        override val colorSpace = ColorSpaceInfo(ColorMatrix.Bt709, ColorPrimaries.Bt709, ColorTransfer.Bt709, fullRange = true)
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    private class FixedHeadroom : HeadroomSource {
        override val potential = 1f
        override val current = 1f
        override val known = true
        override fun refresh() = Unit
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")

    /** A full-range grey: luma [y], neutral chroma. */
    private fun grey(y: Int) = flat(y, 128, 128)

    /** One full-range BT.709 colour over the whole picture. */
    private fun flat(y: Int, cb: Int, cr: Int) = MetalPicture.SoftwarePlanes(
        width = SIZE,
        height = SIZE,
        format = PlayerPixelFormat.Nv12,
        planes = listOf(
            MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE) { y.toByte() }, SIZE, SIZE),
            MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE / 2) { (if (it % 2 == 0) cb else cr).toByte() }, SIZE, SIZE / 2),
        ),
    )

    private fun light(code: Int): Float {
        val encoded = code / 255f
        return if (encoded <= 0.04045f) encoded / 12.92f else ((encoded + 0.055f) / 1.055f).pow(2.4f)
    }

    /**
     * Draws [lumas] at 30 pictures a second through the composer with the guard, as the renderer
     * does, and answers what each picture came out as: all of its bytes, and the factor it had.
     */
    private fun drawn(lumas: List<Int>, guarded: Boolean = true): List<Pair<ByteArray, Float>> =
        drawnPictures(lumas.map(::grey), guarded)

    private fun drawnPictures(pictures: List<MetalPicture.SoftwarePlanes>, guarded: Boolean = true): List<Pair<ByteArray, Float>> {
        val composer = MetalFrameComposer(device)
        val guard = MetalFlashGuard(device)
        val target = device.makeTargetTexture(SIZE, SIZE)
        try {
            return pictures.mapIndexed { index, picture ->
                val factor = if (guarded) guard.factorForNext() else 1f
                val commands = composer.encode(
                    target, TestFrame(), picture, null, SIZE, SIZE,
                    adjustUniforms = dimAdjustUniforms(DISABLED_ADJUST_UNIFORMS, factor, linearLight = false),
                    toneMapped = true,
                    measureTarget = if (guarded) guard.target else null,
                )
                if (guarded) guard.submitted(commands, index * 1_000_000_000L / 30)
                commands.waitUntilCompleted()
                val bytes = ByteArray(SIZE * SIZE * 4)
                bytes.usePinned { pinned ->
                    target.getBytes(
                        pinned.addressOf(0),
                        bytesPerRow = (SIZE * 4).toULong(),
                        fromRegion = MTLRegionMake2D(0u, 0u, SIZE.toULong(), SIZE.toULong()),
                        mipmapLevel = 0u,
                    )
                }
                bytes to factor
            }
        } finally {
            composer.close()
        }
    }

    /** Black and white, three pictures each: five flashes a second. */
    private fun strobe(pictures: Int) = List(pictures) { if (it / 3 % 2 == 0) 0 else 255 }

    @Test
    fun aRedAndBlueStrobeOfOneLuminanceIsDimmedByTheRedRule() {
        // Red is 255, 0, 0 and the blue is 0, 124, 255, which has its relative luminance.
        val red = flat(54, 99, 255)
        val blue = flat(107, 208, 60)
        val factors = drawnPictures(List(60) { if (it / 3 % 2 == 0) blue else red }).map { it.second }
        assertEquals(22, factors.indexOfFirst { it < 1f }, "the factors were $factors")
        // A full red leg is 320 on the rule's scale and must come out under 20 (#561).
        assertTrue(factors.last() < 0.3f, "the factor was ${factors.last()}")
    }

    @Test
    fun aStrobeIsDimmedOneFrameAfterItsRunStarts() {
        val out = drawn(strobe(90))
        val lights = out.map { (bytes, _) -> light(bytes[(SIZE / 2 * SIZE + SIZE / 2) * 4 + 1].toInt() and 0xFF) }
        val factors = out.map { it.second }
        // The seventh leg is on picture 21, and its copy is read as picture 22 is about to draw.
        val firstDimmed = factors.indexOfFirst { it < 1f }
        assertEquals(22, firstDimmed, "the factors were $factors")
        assertTrue(lights.take(21).zipWithNext().any { (a, b) -> abs(b - a) > 0.9f }, "the strobe is drawn whole before its run")
        val after = lights.drop(firstDimmed)
        val largest = after.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue(largest < 0.10f, "a leg of $largest came out after the run started")
        assertTrue(after.max() > 0.05f, "the picture is dimmed, not blacked out: ${after.max()}")
    }

    @Test
    fun aPictureThatDoesNotFlashIsDrawnBitForBit() {
        // A slow ramp and one cut: never a run.
        val lumas = List(60) { if (it < 30) 40 + it else 200 }
        val guarded = drawn(lumas)
        val plain = drawn(lumas, guarded = false)
        assertTrue(guarded.all { it.second == 1f })
        guarded.indices.forEach { assertContentEquals(plain[it].first, guarded[it].first, "picture $it") }
    }

    @Test
    fun theFactorFoldsIntoThePictureControls() {
        assertSame(DISABLED_ADJUST_UNIFORMS, dimAdjustUniforms(DISABLED_ADJUST_UNIFORMS, 1f, linearLight = false))
        val alone = dimAdjustUniforms(DISABLED_ADJUST_UNIFORMS, 0.5f, linearLight = false)
        assertEquals(listOf(0.5f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f), alone.take(12))
        assertEquals(1, alone[12].toRawBits(), "the matrix is switched on")
        assertEquals(0, alone[14].toRawBits(), "the gamma stays off")
        val brighter = packAdjustUniforms(io.github.yuroyami.kiteplayer.VideoAdjustments(brightness = 0.2f))
        val both = dimAdjustUniforms(brighter, 0.5f, linearLight = false)
        (0 until 12).forEach { assertEquals(brighter[it] * 0.5f, both[it], "coefficient $it") }
        // An extended-range target holds light, which goes as the encoded value's power of 2.2.
        val asLight = dimAdjustUniforms(DISABLED_ADJUST_UNIFORMS, 0.5f, linearLight = true)
        assertEquals(0.5f.pow(2.2f), asLight[0], 1e-6f)
    }

    /** Presents a strobe through the real renderer, each picture once the last is on the layer. */
    private fun factorAfterAStrobe(mode: FlashGuard?, systemSetting: Boolean): Float = runBlocking {
        val layer = CAMetalLayer()
        layer.setDrawableSize(CGSizeMake(SIZE.toDouble(), SIZE.toDouble()))
        var y = 0
        val renderer = MetalVideoRenderer(layer, MetalPictureResolver { grey(y) }, { systemSetting }) { FixedHeadroom() }
        try {
            if (mode != null) renderer.setFlashGuard(mode)
            var lowest = 1f
            repeat(60) { index ->
                // A leg on every second picture, far more than three flashes a second at any pace.
                y = if (index / 2 % 2 == 0) 0 else 255
                val before = renderer.presentedFrames + renderer.failedFrames
                renderer.present(TestFrame(), 0L)
                withTimeoutOrNull(10.seconds) {
                    while (renderer.presentedFrames + renderer.failedFrames == before) delay(2)
                }
                lowest = minOf(lowest, renderer.flashFactor)
            }
            assertEquals(60L, renderer.presentedFrames, "every picture reached the layer")
            lowest
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theRendererDimsAStrobeWhenTheGuardIsOn() {
        val factor = factorAfterAStrobe(FlashGuard.On, systemSetting = false)
        assertTrue(factor < 0.5f, "the factor only went down to $factor")
    }

    @Test
    fun theRendererDrawsAStrobeWholeWhenTheGuardIsOff() {
        assertEquals(1f, factorAfterAStrobe(FlashGuard.Off, systemSetting = true))
    }

    @Test
    fun theRendererFollowsTheSystemSettingUntilToldOtherwise() {
        assertTrue(factorAfterAStrobe(mode = null, systemSetting = true) < 0.5f, "Dim Flashing Lights is on")
        assertEquals(1f, factorAfterAStrobe(mode = null, systemSetting = false), "Dim Flashing Lights is off")
        assertEquals(1f, factorAfterAStrobe(FlashGuard.FollowSystem, systemSetting = false))
    }

    @Test
    fun theSystemSettingIsReadFromMediaAccessibility() {
        // This Mac is newer than macOS 13.3, so the lookup by name must find the setting.
        assertTrue(AppleDimFlashingLights.known, "the setting was not found by name")
        assertEquals(platform.MediaAccessibility.MADimFlashingLightsEnabled(), AppleDimFlashingLights.enabled)
    }

    private companion object {
        const val SIZE = 64
    }
}
