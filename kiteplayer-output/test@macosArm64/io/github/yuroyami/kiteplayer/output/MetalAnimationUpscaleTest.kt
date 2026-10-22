@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AnimationUpscaler
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.RenderQuality
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoScaler
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLTextureProtocol
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The animation upscaler on a real Metal device (#421): both tiers draw what the CPU reference
 * draws, through the composer the renderer draws with, only past the networks' 1.2x rule, and
 * what each costs a frame on this machine.
 */
class MetalAnimationUpscaleTest {

    private class TestFrame(
        width: Int,
        height: Int,
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba,
        override val rotationDegrees: Int = 0,
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height, 1, 1)
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private fun composer() = MetalFrameComposer(checkNotNull(MTLCreateSystemDefaultDevice()) { "no Metal device" })

    /** [rgba] in whole 8-bit levels, top row first, as a decoded RGBA frame holds it. */
    private fun rgbaPicture(rgba: FloatArray, width: Int, height: Int): MetalPicture.SoftwarePlanes {
        val bytes = ByteArray(rgba.size) { (rgba[it].coerceIn(0f, 1f) * 255f).roundToInt().toByte() }
        return MetalPicture.SoftwarePlanes(
            width, height, PlayerPixelFormat.Rgba,
            listOf(MetalPicture.SoftwarePlanes.Plane(bytes, width * 4, height)),
        )
    }

    private fun encode(
        composer: MetalFrameComposer,
        target: MTLTextureProtocol,
        frame: VideoFrame,
        picture: MetalPicture,
        upscaler: Anime4kNetwork?,
        scaleMode: VideoScale = VideoScale.Fit,
        adjustUniforms: FloatArray = DISABLED_ADJUST_UNIFORMS,
        qualityUniforms: FloatArray = DISABLED_QUALITY_UNIFORMS,
    ): MTLCommandBufferProtocol = composer.encode(
        target, frame, picture, null, target.width.toInt(), target.height.toInt(),
        scaleMode = scaleMode,
        adjustUniforms = adjustUniforms,
        qualityUniforms = qualityUniforms,
        upscaler = upscaler,
    ).also { it.waitUntilCompleted() }

    /** Renders and reads back the red, green and blue levels, four ints a pixel in RGBA order. */
    private fun render(
        composer: MetalFrameComposer,
        frame: VideoFrame,
        picture: MetalPicture,
        targetWidth: Int,
        targetHeight: Int,
        upscaler: Anime4kNetwork?,
        scaleMode: VideoScale = VideoScale.Fit,
        adjustUniforms: FloatArray = DISABLED_ADJUST_UNIFORMS,
        qualityUniforms: FloatArray = DISABLED_QUALITY_UNIFORMS,
    ): IntArray {
        val target = composer.device.makeTargetTexture(targetWidth, targetHeight)
        encode(composer, target, frame, picture, upscaler, scaleMode, adjustUniforms, qualityUniforms)
        val bgra = ByteArray(targetWidth * targetHeight * 4)
        bgra.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (targetWidth * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, targetWidth.toULong(), targetHeight.toULong()),
                mipmapLevel = 0u,
            )
        }
        return IntArray(bgra.size) { i ->
            val channel = i % 4
            bgra[i - channel + if (channel == 3) 3 else 2 - channel].toInt() and 0xFF
        }
    }

    private fun levels(floats: FloatArray, gain: Float = 1f): IntArray =
        IntArray(floats.size) { ((floats[it] * gain).coerceIn(0f, 1f) * 255f).roundToInt() }

    /** The worst and the mean distance between two pictures, in 8-bit levels, alpha left out. */
    private fun distance(got: IntArray, want: IntArray): Pair<Int, Double> {
        var worst = 0
        var total = 0L
        for (i in got.indices) {
            if (i % 4 == 3) continue
            worst = maxOf(worst, abs(got[i] - want[i]))
            total += abs(got[i] - want[i])
        }
        return worst to total.toDouble() / (got.size / 4 * 3)
    }

    /** The fixture doubled with bilinear at texel centres, as the plain scaling pass draws it. */
    private fun bilinearDoubled(picture: FloatArray, width: Int, height: Int): IntArray {
        val nothing = Anime4kNetwork(
            "bilinear",
            listOf(
                Anime4kLayer(
                    listOf(Anime4kTerm(Anime4kTerm.SOURCE, Anime4kPart.Raw, 0, 0, List(16) { "0.0" }.joinToString(", "))),
                    "0.0, 0.0, 0.0, 0.0",
                ),
            ),
        )
        return levels(Anime4kReference.upscale(nothing, picture, width, height))
    }

    /**
     * The golden test. Drawn at exactly twice its size, the scaling pass after the network adds
     * nothing of its own, so the frame is the network's output in 8 bits: within two levels of the
     * reference, and well away from what bilinear alone draws. A network that was skipped would
     * read as bilinear, and a picture upside down would miss by about 70 levels on average.
     */
    @Test
    fun bothTiersDrawTheReferencePictureUpright() {
        val fixture = Anime4kFixture()
        val composer = composer()
        val frame = TestFrame(fixture.width, fixture.height)
        val picture = rgbaPicture(fixture.picture, fixture.width, fixture.height)
        val bilinear = bilinearDoubled(fixture.picture, fixture.width, fixture.height)
        val plain = render(composer, frame, picture, fixture.width * 2, fixture.height * 2, upscaler = null)
        assertTrue(distance(plain, bilinear).first <= 1, "with no network the scaling pass draws bilinear")
        for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
            val reference = levels(Anime4kReference.upscale(network, fixture.picture, fixture.width, fixture.height))
            val drawn = render(composer, frame, picture, fixture.width * 2, fixture.height * 2, network)
            val (worst, mean) = distance(drawn, reference)
            assertTrue(worst <= 2 && mean < 0.25, "${network.name} misses the reference by up to $worst levels, $mean on average")
            val fromBilinear = distance(drawn, bilinear).second
            assertTrue(fromBilinear > 2.0, "${network.name} drew within $fromBilinear levels of plain bilinear")
        }
        composer.close()
    }

    /**
     * A decoded film is planes of luma and chroma, not RGB. The source pass converts them before the
     * network reads them, top row first. The reference here is fed the same picture as the composer
     * draws it at its own size, so only the network and the doubling are left to compare.
     */
    @Test
    fun aPlanarPictureIsConvertedBeforeTheNetworkReadsIt() {
        val fixture = Anime4kFixture()
        val width = fixture.width
        val height = fixture.height
        val luma = ByteArray(width * height) { (16 + fixture.picture[it * 4 + 1] * 219f).roundToInt().toByte() }
        // Chroma follows the picture's red, so the planes carry colour and not only grey.
        val chroma = ByteArray(width * height / 2)
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val red = fixture.picture[(y * 2 * width + x * 2) * 4]
                chroma[(y * (width / 2) + x) * 2] = 128.toByte()
                chroma[(y * (width / 2) + x) * 2 + 1] = (100 + red * 56f).roundToInt().toByte()
            }
        }
        val picture = MetalPicture.SoftwarePlanes(
            width, height, PlayerPixelFormat.Nv12,
            listOf(
                MetalPicture.SoftwarePlanes.Plane(luma, width, height),
                MetalPicture.SoftwarePlanes.Plane(chroma, width, height / 2),
            ),
        )
        val frame = TestFrame(
            width, height, PlayerPixelFormat.Nv12,
            colorSpace = ColorSpaceInfo(ColorMatrix.Bt709, ColorPrimaries.Bt709, ColorTransfer.Bt709, fullRange = false),
        )
        val composer = composer()
        val atOwnSize = render(composer, frame, picture, width, height, upscaler = null)
        val asFloats = FloatArray(atOwnSize.size) { atOwnSize[it] / 255f }
        for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
            val reference = levels(Anime4kReference.upscale(network, asFloats, width, height))
            val drawn = render(composer, frame, picture, width * 2, height * 2, network)
            val (worst, mean) = distance(drawn, reference)
            // The reference reads the picture rounded to 8 bits and the network reads it unrounded.
            assertTrue(worst <= 4 && mean < 0.5, "${network.name} misses the reference by up to $worst levels, $mean on average")
            val fromBilinear = distance(drawn, bilinearDoubled(asFloats, width, height)).second
            assertTrue(fromBilinear > 2.0, "${network.name} drew within $fromBilinear levels of plain bilinear")
        }
        composer.close()
    }

    /**
     * No tier is no network. A frame drawn with none is the plain frame, which at twice the size is
     * bilinear, and a network that ran on an earlier frame leaves nothing behind for the next one.
     */
    @Test
    fun offDrawsThePlainFrameBitForBit() {
        assertNull(Anime4kNetwork.of(AnimationUpscaler.Off))
        assertNull(Anime4kNetwork.of(RenderQuality.Standard.animationUpscaler))
        val fixture = Anime4kFixture()
        val width = fixture.width * 2
        val height = fixture.height * 2
        val composer = composer()
        val frame = TestFrame(fixture.width, fixture.height)
        val picture = rgbaPicture(fixture.picture, fixture.width, fixture.height)
        val quality = packQualityUniforms(
            RenderQuality(dither = true, scaler = VideoScaler.CatmullRom, linearLight = true),
            sourceWidth = fixture.width,
            sourceHeight = fixture.height,
        )
        for (uniforms in listOf(DISABLED_QUALITY_UNIFORMS, quality)) {
            val before = render(composer, frame, picture, width, height, upscaler = null, qualityUniforms = uniforms)
            val upscaled = render(composer, frame, picture, width, height, Anime4kNetworks.small, qualityUniforms = uniforms)
            val off = render(
                composer, frame, picture, width, height, Anime4kNetwork.of(AnimationUpscaler.Off), qualityUniforms = uniforms,
            )
            assertContentEquals(before, off)
            assertFalse(upscaled.contentEquals(off), "the network changes the picture, and Off does not")
        }
        val plain = render(composer, frame, picture, width, height, Anime4kNetwork.of(AnimationUpscaler.Off))
        val fromBilinear = distance(plain, bilinearDoubled(fixture.picture, fixture.width, fixture.height)).first
        assertTrue(fromBilinear <= 1, "Off drew up to $fromBilinear levels away from plain bilinear")
        composer.close()
    }

    /**
     * The networks' own rule, through the composer: more than 1.2 times on both axes. At or under
     * it the frame is the plain one, bit for bit, and past it the network changes the picture.
     */
    @Test
    fun theNetworkRunsOnlyPastTheOriginalsRatio() {
        val fixture = Anime4kFixture(60, 40)
        val composer = composer()
        val frame = TestFrame(fixture.width, fixture.height)
        val picture = rgbaPicture(fixture.picture, fixture.width, fixture.height)
        fun differs(width: Int, height: Int, scaleMode: VideoScale = VideoScale.Fit): Boolean {
            val plain = render(composer, frame, picture, width, height, upscaler = null, scaleMode = scaleMode)
            val upscaled = render(composer, frame, picture, width, height, Anime4kNetworks.small, scaleMode)
            return !plain.contentEquals(upscaled)
        }
        assertFalse(differs(72, 48), "exactly 1.2 times")
        assertTrue(differs(75, 50), "1.25 times on both axes")
        assertTrue(differs(120, 80), "twice the size")
        assertFalse(differs(120, 48, VideoScale.Stretch), "only the width grows enough")
        assertFalse(differs(60, 40), "drawn at its own size")
        composer.close()
    }

    /** The rule reads the picture as the quad shows it: turned, and with a crop taken off. */
    @Test
    fun theRatioIsOfThePictureAsItIsShown() {
        val turned = TestFrame(64, 48, rotationDegrees = 90)
        // Turned, the picture is 48 across and 64 down.
        assertTrue(upscaleRunsAt(quadUniformsFor(turned, 60, 80), 64, 48, 60, 80), "1.25 times, turned")
        assertFalse(upscaleRunsAt(quadUniformsFor(turned, 57, 76), 64, 48, 57, 76), "1.1875 times, turned")
        assertFalse(upscaleRunsAt(quadUniformsFor(turned, 80, 60), 64, 48, 80, 60), "letterboxed to 45 by 60")
        val upright = TestFrame(64, 48)
        // A letterbox draws the picture on part of the target, and the rule reads that part.
        assertTrue(upscaleRunsAt(quadUniformsFor(upright, 200, 60), 64, 48, 200, 60), "80 by 60 inside 200 by 60")
        assertFalse(upscaleRunsAt(quadUniformsFor(upright, 200, 57), 64, 48, 200, 57), "76 by 57 inside 200 by 57")
        // Half the quad's texture span is half the picture, as a crop leaves it.
        val halfWidth = floatArrayOf(1f, 1f, 0.5f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
        assertTrue(upscaleRunsAt(halfWidth, 64, 48, 40, 60), "32 by 48 drawn on 40 by 60")
        assertFalse(upscaleRunsAt(halfWidth, 64, 48, 38, 60), "32 by 48 drawn on 38 by 60")
    }

    /**
     * The doubled picture goes through the scaling pass like any picture: the colour controls and
     * linear light run on it. Half gain halves the reference, and linear light at the doubled
     * picture's own size keeps every level within one step, as it does without the network.
     *
     * The doubled picture is half floats, so the detail the network draws a little past white or
     * under black reaches the colour controls whole, as it does on Android.
     */
    @Test
    fun theColourControlsAndLinearLightRunOnTheDoubledPicture() {
        val fixture = Anime4kFixture()
        val composer = composer()
        val frame = TestFrame(fixture.width, fixture.height)
        val picture = rgbaPicture(fixture.picture, fixture.width, fixture.height)
        val halfGain = FloatArray(15).also {
            it[0] = 0.5f
            it[4] = 0.5f
            it[8] = 0.5f
            it[12] = Float.fromBits(1)
        }
        val linearLight = packQualityUniforms(RenderQuality(linearLight = true))
        val reference = Anime4kReference.upscale(Anime4kNetworks.small, fixture.picture, fixture.width, fixture.height)
        for (quality in listOf(DISABLED_QUALITY_UNIFORMS, linearLight)) {
            val drawn = render(
                composer, frame, picture, fixture.width * 2, fixture.height * 2, Anime4kNetworks.small,
                adjustUniforms = halfGain, qualityUniforms = quality,
            )
            val (worst, mean) = distance(drawn, levels(reference, gain = 0.5f))
            assertTrue(worst <= 2 && mean < 0.5, "at half gain the picture misses half the reference by up to $worst levels, $mean on average")
        }
        composer.close()
    }

    /**
     * What a frame costs on this GPU: a 1280x720 picture doubled and drawn at 2560x1440, the median
     * of 30 frames each finished before the next, beside the same frame with no network. Printed,
     * not judged: the numbers decide whether a tier may ever default on, per device class (#421).
     */
    @Test
    fun eachTierReportsWhatAFrameCosts() {
        val width = 1280
        val height = 720
        val composer = composer()
        val pixels = FloatArray(width * height * 4) { i -> if (i % 4 == 3) 1f else ((i / 4) % 251) / 250f }
        val picture = rgbaPicture(pixels, width, height)
        val frame = TestFrame(width, height)
        val target = composer.device.makeTargetTexture(width * 2, height * 2)
        for ((name, network) in listOf("no network" to null, "Fast" to Anime4kNetworks.small, "Quality" to Anime4kNetworks.medium)) {
            repeat(3) { encode(composer, target, frame, picture, network) }
            val wall = DoubleArray(30)
            val gpu = DoubleArray(30)
            for (i in 0 until 30) {
                val start = TimeSource.Monotonic.markNow()
                val commands = encode(composer, target, frame, picture, network)
                wall[i] = start.elapsedNow().inWholeMicroseconds / 1000.0
                gpu[i] = (commands.GPUEndTime - commands.GPUStartTime) * 1000.0
            }
            wall.sort()
            gpu.sort()
            fun ms(value: Double) = ((value * 100).roundToInt() / 100.0).toString()
            println(
                "KiteUpscaleCost $name: ${width}x$height to ${width * 2}x${height * 2} " +
                    "median ${ms(wall[15])} ms, fastest ${ms(wall.first())} ms, slowest ${ms(wall.last())} ms; " +
                    "on the GPU median ${ms(gpu[15])} ms, on ${composer.device.name}",
            )
        }
        composer.close()
    }
}
