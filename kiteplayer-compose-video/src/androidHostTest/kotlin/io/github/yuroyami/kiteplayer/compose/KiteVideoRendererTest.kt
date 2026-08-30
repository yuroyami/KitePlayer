package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ownership, validation and publish arms of the KiteVideo renderer (S1.d.3 step 6), with
 * the image seam faked so no real bitmap exists anywhere. The frame ledger ends closed-exactly-
 * once in every arm, like its three sibling suites.
 */
private class TestFrame(
    width: Int = 4,
    height: Int = 2,
    override val rotationDegrees: Int = 0,
    override val hardwareSurface: HwSurfaceKind? = null,
) : VideoFrame {
    override val pts: Pts = Pts(0)
    override val duration: Pts? = null
    override val size: VideoSize = VideoSize(width, height)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
    override val generation: Generation = Generation(0)

    @Volatile var closes = 0
        private set

    override fun close() {
        closes += 1
    }
}

private class FakeHardwareRenderer : KiteVideoHardwareRenderer {
    val factory = object : VideoDecoderFactory {
        override val name: String = "fake GPU"
        override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? = null
    }
    var presented = 0L
    var superseded = 2L
    var failed = 3L
    var closes = 0
    var viewport: Triple<Int, Int, Float>? = null
    val viewports = mutableListOf<Triple<Int, Int, Float>>()
    var quality: io.github.yuroyami.kiteplayer.RenderQuality? = null

    override fun setRenderQuality(quality: io.github.yuroyami.kiteplayer.RenderQuality) {
        this.quality = quality
    }

    override val presentedFrames: Long get() = presented
    override val supersededFrames: Long get() = superseded
    override val failedFrames: Long get() = failed
    override val events: Flow<RendererEvent> = emptyFlow()
    override fun videoDecoderFactories(): List<VideoDecoderFactory> = listOf(factory)
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = setOf(HwSurfaceKind.MediaCodecBuffer)
    override fun supports(format: PlayerPixelFormat): Boolean = format == PlayerPixelFormat.Opaque
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        frame.close()
        presented += 1
        return true
    }
    override fun vsyncIntervalNanos(): Long? = null
    override fun setViewport(width: Int, height: Int, scale: Float) {
        Triple(width, height, scale).also {
            viewport = it
            viewports += it
        }
    }
    override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
    override fun close() {
        closes += 1
    }
}

private class FakeImage(override val width: Int, override val height: Int) : ImageBitmap {
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = false
    override fun prepareToDraw() = Unit
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = error("the fake image holds no pixels")
}

private class Harness(
    val convert: (VideoFrame) -> ByteArray = { frame ->
        ByteArray(frame.size.width * frame.size.height * 4)
    },
    val makeImage: (ByteArray, Int, Int) -> ImageBitmap = { _, w, h -> FakeImage(w, h) },
    hardwareRenderer: KiteVideoHardwareRenderer? = null,
) {
    val published = CopyOnWriteArrayList<KiteVideoFrame?>()
    val publishThreads = CopyOnWriteArrayList<String>()
    val publishedOverlays = CopyOnWriteArrayList<KiteVideoOverlay?>()
    val publishedFilterQualities = CopyOnWriteArrayList<androidx.compose.ui.graphics.FilterQuality>()
    @Volatile var overlayImagesBuilt = 0
    val renderer = KiteVideoRenderer(
        convert = convert,
        makeImage = { rgba, width, height -> FrameImage(makeImage(rgba, width, height)) },
        makeOverlayImage = { _, w, h ->
            overlayImagesBuilt += 1
            FakeImage(w, h)
        },
        publishOverlay = { overlay -> publishedOverlays += overlay },
        publishFilterQuality = { quality -> publishedFilterQualities += quality },
        publish = { frame ->
            published += frame
            publishThreads += Thread.currentThread().name
        },
        hardwareRenderer = hardwareRenderer,
    )

    fun awaitPublished(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (published.size < count) {
            check(System.nanoTime() < deadline) { "nothing published within the deadline" }
            Thread.sleep(2)
        }
    }

    fun awaitFailed(count: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (renderer.failedFrames < count) {
            check(System.nanoTime() < deadline) { "no failure counted within the deadline" }
            Thread.sleep(2)
        }
    }
}

class KiteVideoRendererTest {

    /**
     * 17.21 RQ-3: the ladder splits in two here, and BOTH halves have to arrive.
     *
     * The scaler is the drawing step's own business, so it leaves as a `filterQuality`. Dithering
     * and debanding are not something Compose can do, so the whole value has to go on to the
     * platform GPU tier underneath. Only forwarding the first half is the failure that hides: the
     * engine talks to this renderer and never to the one below it, so a dither switched on would
     * have reached nothing at all on the Android GPU path.
     */
    @Test
    fun renderQualityReachesBothTheDrawAndTheGpuTierBeneathIt() {
        val hardware = FakeHardwareRenderer()
        val h = Harness(hardwareRenderer = hardware)
        try {
            h.renderer.setRenderQuality(io.github.yuroyami.kiteplayer.RenderQuality.Off)
            assertEquals(
                listOf(androidx.compose.ui.graphics.FilterQuality.Low),
                h.publishedFilterQualities.toList(),
                "no kernel is the sampling every renderer did before the ladder existed",
            )

            val cubic = io.github.yuroyami.kiteplayer.RenderQuality(
                dither = true,
                deband = true,
                scaler = io.github.yuroyami.kiteplayer.VideoScaler.CatmullRom,
            )
            h.renderer.setRenderQuality(cubic)
            assertEquals(
                androidx.compose.ui.graphics.FilterQuality.High,
                h.publishedFilterQualities.last(),
                "a kernel must select the best sampling the drawing step offers",
            )
            assertEquals(
                cubic,
                hardware.quality,
                "the WHOLE value must reach the GPU tier: it owns the two passes Compose cannot do",
            )
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun noArgumentStateIsSoftwareOnlyWithoutAnOwningAndroidWindow() {
        val state = KiteVideoState()
        try {
            assertTrue(state.renderer.videoDecoderFactories().isEmpty())
            assertFalse(state.renderer.supports(PlayerPixelFormat.Opaque))
        } finally {
            state.renderer.close()
        }
    }

    private fun overlayOf(hash: Long, vararg positions: Pair<Int, Int>) =
        io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(
            images = positions.map { (x, y) ->
                io.github.yuroyami.kiteplayer.spi.OverlayImage(
                    x = x,
                    y = y,
                    bitmap = io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap(8, 4, ByteArray(8 * 4 * 4)),
                )
            },
            viewportWidth = 1280,
            viewportHeight = 720,
            contentHash = hash,
        )

    /** The S2.d overlay arms, the KiteVideo half of S4.c landing 4. */
    @Test
    fun anOverlayIsConvertedOncePerHashAndClearedExactlyOnce() = runBlocking {
        val h = Harness()
        h.renderer.setOverlay(overlayOf(1L, 10 to 20, 30 to 40))
        val first = h.publishedOverlays.single()!!
        assertEquals(2, first.items.size)
        assertEquals(1280, first.viewportWidth)
        assertEquals(10, first.items[0].x)
        assertEquals(20, first.items[0].y)
        assertEquals(8, first.items[0].width)
        assertEquals(4, first.items[0].height)
        assertEquals(2, h.overlayImagesBuilt)

        // The same hash republishes nothing and rebuilds nothing.
        h.renderer.setOverlay(overlayOf(1L, 10 to 20, 30 to 40))
        assertEquals(1, h.publishedOverlays.size)
        assertEquals(2, h.overlayImagesBuilt)

        // A new hash rebuilds; clearing publishes one null; clearing again is silent.
        h.renderer.setOverlay(overlayOf(2L, 5 to 6))
        assertEquals(2, h.publishedOverlays.size)
        assertEquals(3, h.overlayImagesBuilt)
        h.renderer.setOverlay(null)
        assertNull(h.publishedOverlays.last())
        assertEquals(3, h.publishedOverlays.size)
        h.renderer.setOverlay(null)
        assertEquals(3, h.publishedOverlays.size)
        h.renderer.close()
    }

    @Test
    fun closePublishesANullOverlaySoNoClosedRenderersCuesOutliveIt() = runBlocking {
        val h = Harness()
        h.renderer.setOverlay(overlayOf(9L, 1 to 1))
        h.renderer.close()
        assertNull(h.publishedOverlays.last())
    }

    @Test
    fun aPresentedFrameIsConvertedPublishedAndClosedOnce() = runBlocking {
        val h = Harness()
        val frame = TestFrame(width = 6, height = 4, rotationDegrees = 90)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitPublished(1)
        val out = h.published.single()!!
        assertEquals(6, out.image.width)
        assertEquals(4, out.image.height)
        assertEquals(VideoSize(6, 4), out.size)
        assertEquals(90, out.rotationDegrees)
        assertEquals(1, frame.closes)
        assertEquals(1L, h.renderer.presentedFrames)
        assertEquals(0L, h.renderer.failedFrames)
        h.renderer.close()
    }

    @Test
    fun aHardwareFrameUsesTheGpuRendererAndAggregatesItsCounters() = runBlocking {
        val hardware = FakeHardwareRenderer()
        var softwareConversions = 0
        val renderer = KiteVideoRenderer(
            convert = { frame ->
                softwareConversions += 1
                ByteArray(frame.size.width * frame.size.height * 4)
            },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            publish = {},
            hardwareRenderer = hardware,
        )
        val frame = TestFrame(hardwareSurface = HwSurfaceKind.MediaCodecBuffer)

        assertTrue(renderer.present(frame, 99L))
        assertEquals(1, frame.closes)
        assertEquals(0, softwareConversions)
        assertEquals(listOf(hardware.factory), renderer.videoDecoderFactories())
        assertTrue(renderer.supports(PlayerPixelFormat.Opaque))
        assertEquals(1L, renderer.presentedFrames)
        assertEquals(2L, renderer.supersededFrames)
        assertEquals(3L, renderer.failedFrames)
        renderer.setViewport(640, 360, 2f)
        assertEquals(Triple(640, 360, 2f), hardware.viewport)

        renderer.close()
        renderer.close()
        assertEquals(1, hardware.closes)
        renderer.setViewport(1_280, 720, 1f)
        assertEquals(listOf(Triple(640, 360, 2f)), hardware.viewports)
    }

    @Test
    fun viewportBookAggregatesEveryNodeAndClearsOnlyAfterTheLastOneLeaves() {
        val applied = mutableListOf<Triple<Int, Int, Float>>()
        val book = KiteVideoViewportBook { width, height, scale ->
            applied += Triple(width, height, scale)
        }
        val wideNode = Any()
        val tallNode = Any()

        book.update(wideNode, 1_920, 360)
        book.update(wideNode, 1_920, 360)
        book.update(tallNode, 320, 1_080)
        book.remove(Any())
        book.remove(wideNode)
        book.update(tallNode, 0, 1_080)
        book.remove(tallNode)

        assertEquals(
            listOf(
                Triple(1_920, 360, 1f),
                Triple(1_920, 1_080, 1f),
                Triple(320, 1_080, 1f),
                Triple(0, 0, 1f),
            ),
            applied,
        )
    }

    @Test
    fun publicationCannotRetireAFrameBetweenDrawAcquireAndRecord() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        var releases = 0
        val first = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        ) { releases += 1 }
        val replacement = KiteVideoFrame(FakeImage(4, 2), VideoSize(4, 2), 0)
        state.publishFrame(first)

        val acquired = state.acquireFrameForDraw()
        assertTrue(acquired === first)
        state.publishFrame(replacement)
        assertEquals(0, releases, "an in-flight draw still owns the replaced frame")

        state.frameDrawFinished(first, recorded = true)
        state.publishFrame(null)
        assertEquals(0, releases, "recording is not the asynchronous RenderThread fence")
        state.frameCommitted(first)
        assertEquals(0, releases, "the committed display list still contains the old image")
        state.frameCommitted(null)
        assertEquals(1, releases)
        state.renderer.close()
    }

    @Test
    fun clearingWhileTheSameFrameIsRedrawingWaitsForThatDrawToFinish() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        var releases = 0
        val frame = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        ) { releases += 1 }
        state.publishFrame(frame)
        val first = state.acquireFrameForDraw()!!
        state.frameDrawFinished(first, recorded = true)
        state.frameCommitted(first)

        val redraw = state.acquireFrameForDraw()!!
        state.publishFrame(null)
        assertEquals(0, releases, "the same frame is still inside the draw phase")

        state.frameDrawFinished(redraw, recorded = true)
        assertEquals(0, releases, "the draw record can still be sampled asynchronously")
        state.frameCommitted(redraw)
        assertEquals(0, releases, "the redraw committed the same image before the clear")
        state.frameCommitted(null)
        assertEquals(1, releases)
        state.renderer.close()
    }

    @Test
    fun aReplacementLeaseRetiresOnlyWhenItsDisplayListIsCommitted() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        var releases = 0
        val first = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        ) { releases += 1 }
        val replacement = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        )

        state.publishFrame(first)
        state.acquireFrameForDraw()
        state.frameDrawFinished(first, recorded = true)
        state.frameCommitted(first)
        state.publishFrame(replacement)
        assertEquals(0, releases, "the old display list may still be replayed")

        state.acquireFrameForDraw()
        state.frameDrawFinished(replacement, recorded = true)
        state.frameCommitted(replacement)
        assertEquals(1, releases)
        state.renderer.close()
    }

    @Test
    fun aCommittedSoftwareReplacementRetiresTheOldHardwareLease() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        var releases = 0
        val hardware = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        ) { releases += 1 }
        val software = KiteVideoFrame(FakeImage(4, 2), VideoSize(4, 2), 0)

        state.publishFrame(hardware)
        state.acquireFrameForDraw()
        state.frameDrawFinished(hardware, recorded = true)
        state.frameCommitted(hardware)
        state.publishFrame(software)
        assertEquals(0, releases, "the hardware image remains in the submitted display list")

        state.acquireFrameForDraw()
        state.frameDrawFinished(software, recorded = true)
        state.frameCommitted(null)
        assertEquals(1, releases, "software commit replaces the hardware-backed display list")
        state.renderer.close()
    }

    @Test
    fun twoKiteVideoNodesRetainTheirOwnCommittedHardwareLease() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        val firstNode = Any()
        val secondNode = Any()
        var releases = 0
        val hardware = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        ) { releases += 1 }

        state.publishFrame(hardware)
        repeat(2) {
            state.acquireFrameForDraw()
            state.frameDrawFinished(hardware, recorded = true)
        }
        state.publishFrame(null)
        state.frameCommitted(firstNode, hardware)
        state.frameCommitted(secondNode, hardware)

        state.frameCommitted(firstNode, null)
        assertEquals(0, releases, "the second node's display list still owns the image")
        state.frameCommitted(secondNode, null)
        assertEquals(1, releases)
        state.renderer.close()
    }

    @Test
    fun gpuCompletionCountsEachHardwareImageOnceAcrossComposeNodes() {
        val state = KiteVideoState(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(FakeImage(width, height)) },
            releaseImages = {},
        )
        val firstNode = Any()
        val secondNode = Any()
        val first = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        )
        val second = KiteVideoFrame(
            FakeImage(4, 2),
            VideoSize(4, 2),
            0,
            requiresCommitFence = true,
        )

        state.publishFrame(first)
        repeat(2) {
            state.acquireFrameForDraw()
            state.frameDrawFinished(first, recorded = true)
        }
        state.frameCommitted(firstNode, first, completionVsyncNanos = 100_000_000L)
        state.frameCommitted(secondNode, first, completionVsyncNanos = 101_000_000L)
        assertEquals(
            KiteVideoGpuCompletionStats(1L, 100_000_000L, 100_000_000L),
            state.gpuCompletionStats,
        )

        state.publishFrame(second)
        state.acquireFrameForDraw()
        state.frameDrawFinished(second, recorded = true)
        state.frameCommitted(firstNode, second, completionVsyncNanos = 133_000_000L)
        assertEquals(2L, state.gpuCompletionStats.frames)
        assertEquals(33_000_000L, state.gpuCompletionStats.spanNanos)
        state.renderer.close()
    }

    @Test
    fun softwareFramesKeepUsingTheFallbackWhenTheGpuRendererExists() = runBlocking {
        val hardware = FakeHardwareRenderer()
        val h = Harness(hardwareRenderer = hardware)
        val frame = TestFrame()

        assertTrue(h.renderer.present(frame, 0L))
        h.awaitPublished(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, hardware.presented)
        assertEquals(1L, h.renderer.presentedFrames)
        h.renderer.close()
    }

    @Test
    fun theNewestFrameWinsAndTheDisplacedOneIsClosedAndCounted() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val h = Harness(convert = { frame ->
            entered.countDown()
            check(gate.await(10, TimeUnit.SECONDS)) { "the gate never opened" }
            ByteArray(frame.size.width * frame.size.height * 4)
        })
        val plug = TestFrame()
        val displaced = TestFrame()
        val kept = TestFrame()

        assertTrue(h.renderer.present(plug, 0L))
        check(entered.await(10, TimeUnit.SECONDS)) { "the worker never started" }
        // The worker is inside convert(plug); these two race nothing.
        assertTrue(h.renderer.present(displaced, 0L))
        assertTrue(h.renderer.present(kept, 0L))
        assertEquals(1, displaced.closes)
        assertEquals(1L, h.renderer.supersededFrames)
        gate.countDown()

        h.awaitPublished(2)
        assertEquals(1, plug.closes)
        assertEquals(1, kept.closes)
        assertEquals(2L, h.renderer.presentedFrames)
        h.renderer.close()
    }

    @Test
    fun aThrowingConverterCountsTheFrameAndTheWorkerSurvives() = runBlocking {
        var first = true
        val h = Harness(convert = { frame ->
            if (first) {
                first = false
                error("deliberate conversion failure")
            }
            ByteArray(frame.size.width * frame.size.height * 4)
        })
        val bad = TestFrame()
        assertTrue(h.renderer.present(bad, 0L))
        h.awaitFailed(1)
        assertEquals(1, bad.closes)

        val good = TestFrame()
        assertTrue(h.renderer.present(good, 0L))
        h.awaitPublished(1)
        assertEquals(1L, h.renderer.presentedFrames)
        assertEquals(1L, h.renderer.failedFrames)
        h.renderer.close()
    }

    @Test
    fun aShortConversionIsRefusedNeverPublished() = runBlocking {
        val h = Harness(convert = { ByteArray(3) })
        val frame = TestFrame(width = 4, height = 2)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
        // Only the close-time null was ever published.
        assertEquals(listOf(null), h.published.toList())
    }

    @Test
    fun aFailedImageBuildCountsTheFrame() = runBlocking {
        val h = Harness(makeImage = { _, _, _ -> error("deliberate image failure") })
        val frame = TestFrame()
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
    }

    @Test
    fun publishRunsOnTheRendererWorkerNeverTheCaller() = runBlocking {
        val h = Harness()
        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitPublished(1)
        val publisher = h.publishThreads.first()
        assertTrue(publisher.contains("kitevideo"), "published from $publisher")
        h.renderer.close()
    }

    @Test
    fun closePublishesNullRefusesLateFramesAndIsIdempotent() = runBlocking {
        val h = Harness()
        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitPublished(1)
        h.renderer.close()
        assertNull(h.published.last())

        val late = TestFrame()
        assertFalse(h.renderer.present(late, 0L))
        assertEquals(1, late.closes)
        assertEquals(1L, h.renderer.failedFrames)

        h.renderer.close()
        assertEquals(1, h.published.count { it == null })
    }

    @Test
    fun closeReleasesTheImagesExactlyOnceAfterTheNullPublish() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        var releases = 0
        val renderer = KiteVideoRenderer(
            convert = { frame -> ByteArray(frame.size.width * frame.size.height * 4) },
            makeImage = { _, w, h -> FrameImage(FakeImage(w, h)) },
            publish = { frame -> order += if (frame == null) "publish-null" else "publish" },
            releaseImages = {
                releases += 1
                order += "release"
            },
        )
        assertTrue(renderer.present(TestFrame(), 0L))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (order.none { it == "publish" }) {
            check(System.nanoTime() < deadline) { "nothing published within the deadline" }
            Thread.sleep(2)
        }
        renderer.close()
        renderer.close()
        assertEquals(1, releases, "a double close must release once")
        assertEquals(listOf("publish", "publish-null", "release"), order.toList())
    }

    @Test
    fun theCostTrackerCountsOnlyPublishedFrames() = runBlocking {
        var first = true
        val h = Harness(convert = { frame ->
            if (first) {
                first = false
                error("deliberate conversion failure")
            }
            ByteArray(frame.size.width * frame.size.height * 4)
        })
        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitFailed(1)
        assertEquals(0L, h.renderer.costSnapshot().samples, "a failed frame is not a cost sample")

        assertTrue(h.renderer.present(TestFrame(), 0L))
        h.awaitPublished(1)
        val cost = h.renderer.costSnapshot()
        assertEquals(1L, cost.samples)
        assertTrue(cost.lastNanos >= 0L)
        assertTrue(cost.worstNanos >= cost.lastNanos || cost.samples > 1)
        assertTrue(cost.averageNanos in 0..cost.worstNanos)
        h.renderer.close()
    }

    @Test
    fun aZeroSampleCostSnapshotIsAllZeros() {
        val h = Harness()
        val cost = h.renderer.costSnapshot()
        assertEquals(0L, cost.samples)
        assertEquals(0L, cost.lastNanos)
        assertEquals(0L, cost.averageNanos)
        assertEquals(0L, cost.worstNanos)
        h.renderer.close()
    }

    @Test
    fun theWorstCostIsMonotone() {
        val tracker = FrameCostTracker()
        tracker.record(50)
        tracker.record(200)
        tracker.record(100)
        val cost = tracker.snapshot()
        assertEquals(3L, cost.samples)
        assertEquals(100L, cost.lastNanos)
        assertEquals(200L, cost.worstNanos)
        assertEquals(116L, cost.averageNanos)
    }

    @Test
    fun aDegenerateFrameSizeIsRefused() = runBlocking {
        val h = Harness()
        val frame = TestFrame(width = 0, height = 2)
        assertTrue(h.renderer.present(frame, 0L))
        h.awaitFailed(1)
        assertEquals(1, frame.closes)
        assertEquals(0L, h.renderer.presentedFrames)
        h.renderer.close()
    }
}

/**
 * a foreign frame is refused ONCE, in words a consumer can read, and the renderer stops
 * paying for the attempt.
 *
 * Before this, the pairing failed on every frame forever and carried a ClassCastException message,
 * which is a compiler implementation detail that reads differently on Kotlin/Native and the JVM.
 */
class UnsupportedFrameTypeTest {

    private fun foreignConverter(): (VideoFrame) -> ByteArray = { frame ->
        throw UnsupportedFrameType(actual = frame::class.simpleName ?: "an unnamed frame", expected = "KiteFFmpegVideoFrame")
    }

    @Test
    fun aForeignFrameIsRefusedOnceAndNamesBothTypes() {
        var conversions = 0
        val harness = Harness(
            convert = { frame ->
                conversions += 1
                foreignConverter()(frame)
            },
        )
        val events = CopyOnWriteArrayList<RendererEvent>()
        val collector = Thread {
            runBlocking { harness.renderer.events.collect { events += it } }
        }.apply { isDaemon = true; start() }

        try {
            // One at a time, each awaited: the pending slot holds ONE frame and a displaced one is
            // counted superseded, not failed, so presenting five at once would prove nothing.
            val frames = (1..5).map { TestFrame() }
            frames.forEachIndexed { index, frame ->
                runBlocking { assertTrue(harness.renderer.present(frame, 0L)) }
                harness.awaitFailed((index + 1).toLong())
            }

            // Every frame is still counted and still closed. Silence would be worse than a count.
            assertEquals(5L, harness.renderer.failedFrames)
            frames.forEach { assertEquals(1, it.closes, "every refused frame is closed exactly once") }

            // But the converter is asked exactly once: the pairing is dead after the first refusal.
            assertEquals(1, conversions, "a dead pairing must not be re-attempted per frame")

            val refusals = events.filterIsInstance<RendererEvent.Failed>()
                .filter { "KiteFFmpegVideoFrame" in it.detail }
            assertEquals(1, refusals.size, "the refusal must be published once, not per frame: $events")
            assertTrue("TestFrame" in refusals.single().detail, "the refusal must name the actual type: ${refusals.single().detail}")
        } finally {
            collector.interrupt()
            harness.renderer.close()
        }
    }
}
