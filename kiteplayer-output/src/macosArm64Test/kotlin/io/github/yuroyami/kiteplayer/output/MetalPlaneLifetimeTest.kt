@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.yuroyami.kiteplayer.output

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
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLCommandBufferStatusCompleted
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLTextureProtocol
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real GPU reads are held until later CPU uploads finish, so a fast GPU cannot hide aliasing. */
class MetalPlaneLifetimeTest {

    private class TestFrame(picture: MetalPicture.SoftwarePlanes) : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(picture.width, picture.height, 1, 1)
        override val colorSpace = ColorSpaceInfo(
            matrix = ColorMatrix.Bt709,
            primaries = ColorPrimaries.Bt709,
            transfer = ColorTransfer.Bt709,
            fullRange = false,
        )
        override val pixelFormat = picture.format
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    private data class Color(val y: Int, val cb: Int, val cr: Int)

    private class Rendered(
        val commands: MTLCommandBufferProtocol,
        val target: MTLTextureProtocol,
        val width: Int,
        val height: Int,
    )

    /** Every tested command carries its own wait before the first pass, not a timing guess. */
    private class HeldGpu(device: MTLDeviceProtocol) : AutoCloseable {
        private val event = checkNotNull(device.newSharedEvent()) { "Metal refused a shared event" }
        private val expired = atomic(false)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var watchdog: Job? = null

        fun commands(queue: MTLCommandQueueProtocol): MTLCommandBufferProtocol? {
            // Pipeline compilation before the first buffer is not part of the guarded wait.
            if (watchdog == null) {
                watchdog = scope.launch {
                    delay(10_000)
                    expired.value = true
                    event.signaledValue = 1uL
                }
            }
            return queue.commandBuffer()?.also { it.encodeWaitForEvent(event, value = 1uL) }
        }

        fun release() {
            event.signaledValue = 1uL
            watchdog?.cancel()
        }

        fun assertNotExpired() {
            assertFalse(expired.value, "the GPU gate needed its watchdog; encoding or close waited too early")
        }

        override fun close() {
            // Always release before an owner waits or closes, including after an assertion fails.
            release()
            scope.cancel()
        }
    }

    private fun device(): MTLDeviceProtocol =
        checkNotNull(MTLCreateSystemDefaultDevice()) { "this host has no Metal device" }.also {
            // Cold shader compilation belongs to setup, before the event watchdog or worker
            // readiness deadline starts. The worker's composer reuses this per-device cache.
            MetalPipelines.of(it, MTLPixelFormatBGRA8Unorm)
        }

    /** Nonzero padding catches wrong strides; odd chroma sizes must include the final row and column. */
    private fun picture(
        color: Color,
        format: PlayerPixelFormat = PlayerPixelFormat.Nv12,
        width: Int = 17,
        height: Int = 13,
    ): MetalPicture.SoftwarePlanes {
        fun plane(columns: Int, rows: Int, components: Int, value: (Int) -> Int): MetalPicture.SoftwarePlanes.Plane {
            val stride = columns * components + 8
            val bytes = ByteArray(stride * rows) { 0xA5.toByte() }
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    for (component in 0 until components) {
                        bytes[row * stride + column * components + component] = value(component).toByte()
                    }
                }
            }
            return MetalPicture.SoftwarePlanes.Plane(bytes, stride, rows)
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val luma = plane(width, height, 1) { color.y }
        val planes = when (format) {
            PlayerPixelFormat.Nv12 -> listOf(
                luma,
                plane(chromaWidth, chromaHeight, 2) { if (it == 0) color.cb else color.cr },
            )
            PlayerPixelFormat.Yuv420p -> listOf(
                luma,
                plane(chromaWidth, chromaHeight, 1) { color.cb },
                plane(chromaWidth, chromaHeight, 1) { color.cr },
            )
            else -> error("the fixture does not make $format")
        }
        return MetalPicture.SoftwarePlanes(width, height, format, planes)
    }

    private fun encode(composer: MetalFrameComposer, picture: MetalPicture.SoftwarePlanes): Rendered {
        val target = composer.device.makeTargetTexture(picture.width, picture.height)
        val commands = composer.encode(
            target, TestFrame(picture), picture, null, picture.width, picture.height,
        )
        return Rendered(commands, target, picture.width, picture.height)
    }

    private fun assertColor(rendered: Rendered, color: Color) {
        rendered.commands.waitUntilCompleted()
        assertEquals(MTLCommandBufferStatusCompleted, rendered.commands.status, "Metal failed: ${rendered.commands.error}")
        val bytes = ByteArray(rendered.width * rendered.height * 4)
        bytes.usePinned {
            rendered.target.getBytes(
                it.addressOf(0),
                bytesPerRow = (rendered.width * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, rendered.width.toULong(), rendered.height.toULong()),
                mipmapLevel = 0u,
            )
        }
        // BT.709 limited-range YCbCr to BGRA, independent of which slot the renderer chose.
        val y = (color.y - 16) * 255.0 / 219.0
        val cb = (color.cb - 128) * 255.0 / 224.0
        val cr = (color.cr - 128) * 255.0 / 224.0
        val expected = intArrayOf(
            (y + 1.8556 * cb).roundToInt().coerceIn(0, 255),
            (y - 0.187324 * cb - 0.468124 * cr).roundToInt().coerceIn(0, 255),
            (y + 1.5748 * cr).roundToInt().coerceIn(0, 255),
            255,
        )
        for (pixel in 0 until rendered.width * rendered.height) {
            for (channel in 0..3) {
                val actual = bytes[pixel * 4 + channel].toInt() and 0xFF
                assertTrue(
                    abs(actual - expected[channel]) <= 2,
                    "pixel $pixel channel $channel read $actual instead of ${expected[channel]} for $color",
                )
            }
        }
    }

    private fun heldColors(format: PlayerPixelFormat) {
        val device = device()
        val held = HeldGpu(device)
        val composer = MetalFrameComposer(device, makeCommands = held::commands)
        try {
            val colors = listOf(RED, GREEN, BLUE)
            val rendered = colors.map { encode(composer, picture(it, format)) }
            held.assertNotExpired()
            held.release()
            rendered.zip(colors).forEach { (output, color) -> assertColor(output, color) }
        } finally {
            held.close()
            composer.close()
        }
    }

    @Test
    fun heldNv12FramesKeepTheirOwnLumaAndChroma() = heldColors(PlayerPixelFormat.Nv12)

    @Test
    fun heldPlanarFramesKeepAllThreePlanes() = heldColors(PlayerPixelFormat.Yuv420p)

    @Test
    fun formatAndGeometryChangesKeepOlderGpuInputsAlive() {
        val device = device()
        val held = HeldGpu(device)
        val composer = MetalFrameComposer(device, makeCommands = held::commands)
        try {
            val first = encode(composer, picture(RED, width = 19, height = 11))
            val second = encode(composer, picture(GREEN, PlayerPixelFormat.Yuv420p, width = 13, height = 17))
            val third = encode(composer, picture(BLUE, width = 7, height = 5))
            held.assertNotExpired()
            assertEquals(3, composer.softwarePlaneSetCount, "the cap includes all three recipes")
            held.release()
            assertColor(first, RED)
            assertColor(second, GREEN)
            assertColor(third, BLUE)
        } finally {
            held.close()
            composer.close()
        }
    }

    @Test
    fun aFailedPlaneUploadDoesNotOverwriteAnOlderFrameOrStrandASet() {
        val device = device()
        val held = HeldGpu(device)
        val composer = MetalFrameComposer(device, makeCommands = held::commands)
        try {
            val first = encode(composer, picture(RED))
            val valid = picture(GREEN)
            val badStride = (valid.width + 1) / 2 * 2 - 1
            val rows = (valid.height + 1) / 2
            val invalid = MetalPicture.SoftwarePlanes(
                valid.width, valid.height, valid.format,
                listOf(valid.planes[0], MetalPicture.SoftwarePlanes.Plane(ByteArray(badStride * rows), badStride, rows)),
            )
            assertFailsWith<IllegalArgumentException> { encode(composer, invalid) }
            val second = encode(composer, picture(BLUE))
            held.assertNotExpired()
            assertEquals(2, composer.softwarePlaneSetCount, "the abandoned set must be reusable immediately")
            held.release()
            assertColor(first, RED)
            assertColor(second, BLUE)
        } finally {
            held.close()
            composer.close()
        }
    }

    @Test
    fun anEncodingFailureBeforeCommitLeavesItsSetReusable() {
        val device = device()
        val held = HeldGpu(device)
        val composer = MetalFrameComposer(device, makeCommands = held::commands)
        try {
            val first = encode(composer, picture(RED))
            val discarded = picture(GREEN)
            val target = device.makeTargetTexture(discarded.width, discarded.height)
            assertFailsWith<IndexOutOfBoundsException> {
                // The missing quality word fails after software upload and before any commit.
                composer.encode(
                    target, TestFrame(discarded), discarded, null, discarded.width, discarded.height,
                    qualityUniforms = floatArrayOf(),
                )
            }
            val second = encode(composer, picture(BLUE))
            held.assertNotExpired()
            assertEquals(2, composer.softwarePlaneSetCount)
            held.release()
            assertColor(first, RED)
            assertColor(second, BLUE)
        } finally {
            held.close()
            composer.close()
        }
    }

    @Test
    fun commandBufferRefusalOwnsNoSoftwareSetAndTheNextEncodeCanRecover() {
        val device = device()
        var refused = false
        val composer = MetalFrameComposer(device, makeCommands = { queue ->
            if (!refused) {
                refused = true
                null
            } else {
                queue.commandBuffer()
            }
        })
        try {
            assertFailsWith<IllegalStateException> { encode(composer, picture(RED)) }
            assertEquals(0, composer.softwarePlaneSetCount)
            assertColor(encode(composer, picture(GREEN)), GREEN)
            assertEquals(1, composer.softwarePlaneSetCount)
        } finally {
            composer.close()
        }
    }

    @Test
    fun theFourthInFlightUploadWaitsWithinOneGlobalThreeSetBound() = runBlocking {
        val device = device()
        val held = HeldGpu(device)
        val fourthEntered = CompletableDeferred<Unit>()
        val fourthReturned = CompletableDeferred<Unit>()
        val dispatcher = newSingleThreadContext("metal-plane-bound-test")
        try {
            val work = async(dispatcher) {
                var calls = 0
                val composer = MetalFrameComposer(device, makeCommands = { queue ->
                    held.commands(queue).also { if (++calls == 4) fourthEntered.complete(Unit) }
                })
                try {
                    val outputs = ArrayList<Pair<Rendered, Color>>()
                    for (index in 0 until 12) {
                        val color = listOf(RED, GREEN, BLUE)[index % 3]
                        val format = if (index % 2 == 0) PlayerPixelFormat.Nv12 else PlayerPixelFormat.Yuv420p
                        outputs += encode(composer, picture(color, format, width = 9 + index * 2, height = 7 + index)) to color
                        assertTrue(composer.softwarePlaneSetCount <= 3, "geometry changes escaped the global bound")
                        if (index == 3) fourthReturned.complete(Unit)
                    }
                    outputs.forEach { (output, color) -> assertColor(output, color) }
                } finally {
                    composer.close()
                }
            }
            try {
                withTimeout(5_000) { fourthEntered.await() }
                assertNull(
                    withTimeoutOrNull(100) { fourthReturned.await() },
                    "a fourth CPU upload passed three GPU reads that are still held",
                )
                held.assertNotExpired()
            } finally {
                held.release()
            }
            withTimeout(5_000) { work.await() }
        } finally {
            held.close()
            dispatcher.close()
        }
    }

    @Test
    fun completedStorageIsReusedAndCloseReleasesItAndRefusesNewWork() {
        val composer = MetalFrameComposer(device())
        try {
            repeat(12) { index ->
                val color = if (index % 2 == 0) RED else GREEN
                assertColor(encode(composer, picture(color)), color)
                assertEquals(1, composer.softwarePlaneSetCount, "completed frames must reuse their source storage")
            }
            composer.close()
            composer.close()
            assertEquals(0, composer.softwarePlaneSetCount)
            assertFalse(composer.canEncode(picture(RED)))
            assertFailsWith<IllegalStateException> { encode(composer, picture(RED)) }
            val target = composer.device.makeTargetTexture(3, 3)
            assertFailsWith<IllegalStateException> { composer.encodeBackground(target, null, 3, 3) }
        } finally {
            composer.close()
        }
    }

    @Test
    fun closeFencesHeldGpuWorkAndDrainsObservedCallbacks() = runBlocking {
        val device = device()
        val held = HeldGpu(device)
        val completed = atomic(0)
        val closing = CompletableDeferred<Unit>()
        val dispatcher = newSingleThreadContext("metal-plane-close-test")
        try {
            val work = async(dispatcher) {
                val composer = MetalFrameComposer(device, makeCommands = { queue ->
                    held.commands(queue)?.also { it.addCompletedHandler { completed.incrementAndGet() } }
                })
                try {
                    val first = encode(composer, picture(RED))
                    val second = encode(composer, picture(GREEN))
                    composer.encodeBackground(device.makeTargetTexture(5, 5), null, 5, 5)
                    closing.complete(Unit)
                    composer.close()
                    // This observes all callbacks on this run. It does not assume or force
                    // relative scheduling of different buffers' CPU completion handlers.
                    assertEquals(3, completed.value, "close returned before a submitted completion handler")
                    assertEquals(0, composer.softwarePlaneSetCount)
                    composer.close()
                    assertColor(first, RED)
                    assertColor(second, GREEN)
                } finally {
                    composer.close()
                }
            }
            try {
                withTimeout(5_000) { closing.await() }
                assertEquals(0, completed.value)
                assertNull(withTimeoutOrNull(100) { work.await() }, "close returned while the GPU reads were held")
                held.assertNotExpired()
            } finally {
                held.release()
            }
            withTimeout(5_000) { work.await() }
        } finally {
            held.close()
            dispatcher.close()
        }
    }

    @Test
    fun twoComposersRetainIndependentSoftwareStorage() {
        val device = device()
        val held = HeldGpu(device)
        val firstComposer = MetalFrameComposer(device, makeCommands = held::commands)
        val secondComposer = MetalFrameComposer(device)
        try {
            val first = encode(firstComposer, picture(RED))
            val second = encode(secondComposer, picture(BLUE))
            assertColor(second, BLUE)
            secondComposer.close()
            held.assertNotExpired()
            held.release()
            assertColor(first, RED)
        } finally {
            held.close()
            firstComposer.close()
            secondComposer.close()
        }
    }

    private companion object {
        val RED = Color(63, 102, 240)
        val GREEN = Color(173, 42, 26)
        val BLUE = Color(32, 240, 118)
    }
}
