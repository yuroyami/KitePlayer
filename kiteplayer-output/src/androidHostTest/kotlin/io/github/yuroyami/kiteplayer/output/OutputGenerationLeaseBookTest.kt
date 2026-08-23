package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputGenerationLeaseBookTest {
    @Test
    fun capacityAppliesBackpressureBeforeImageReaderWouldThrow() {
        val book = OutputGenerationLeaseBook<String, String>(capacity = 2)
        assertTrue(book.beginOutput())
        assertTrue(book.register("one"))
        assertTrue(book.promote("one", "lease-one"))
        assertTrue(book.markPublished("lease-one"))
        assertTrue(book.beginOutput())
        assertTrue(book.register("two"))
        assertFalse(book.hasCapacity)
        assertFalse(book.beginOutput(), "the producer must not reserve past ImageReader capacity")

        assertEquals(OutputGenerationResolution(false, 0L), book.removeLease("lease-one"))
        assertTrue(book.hasCapacity)
        assertTrue(book.beginOutput())
        assertTrue(book.register("three"))
    }

    @Test
    fun unacquiredOutputsConsumeCapacityBeforeImageCallbacksRun() {
        val book = OutputGenerationLeaseBook<String, String>(capacity = 2)

        assertTrue(book.beginOutput())
        assertTrue(book.hasAcquisitionCapacity)
        assertTrue(book.beginOutput())
        assertFalse(book.hasCapacity)
        assertTrue(book.hasAcquisitionCapacity)
        assertFalse(book.beginOutput())

        assertTrue(book.register("one"))
        assertTrue(book.hasAcquisitionCapacity)
        assertTrue(book.register("two"))
        assertFalse(book.hasAcquisitionCapacity)
    }

    @Test
    fun perFrameConfigurationSurvivesDynamicFormatOrdering() {
        val book = FrameConfigurationBook()
        val landscape = FrameConfiguration(VideoSize(1920, 1080), 0)
        val portrait = FrameConfiguration(VideoSize(1080, 1920), 90)
        book.register(100L, landscape)
        book.register(200L, portrait)

        assertEquals(MatchedFrameConfiguration(landscape, 0L), book.takeExact(100L))
        assertEquals(MatchedFrameConfiguration(portrait, 0L), book.takeExact(200L))
    }

    @Test
    fun aCoalescedSurfaceTextureFrameDropsOnlyOlderConfigurations() {
        val book = FrameConfigurationBook()
        val old = FrameConfiguration(VideoSize(640, 360), 0)
        val newest = FrameConfiguration(VideoSize(1280, 720), 90)
        book.register(10L, old)
        book.register(20L, newest)

        assertEquals(MatchedFrameConfiguration(newest, 1L), book.takeExact(20L))
        assertEquals(null, book.takeExact(10L))
        assertTrue(book.wasResolved(10L))
        assertTrue(book.wasResolved(20L))
        assertFalse(book.wasResolved(30L))
    }

    @Test
    fun staleCallbackIdentitySurvivesAnInterveningLatch() {
        val book = FrameConfigurationBook()
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        book.register(10L, configuration)
        book.register(20L, configuration)
        assertEquals(MatchedFrameConfiguration(configuration, 0L), book.takeExact(10L))
        assertEquals(MatchedFrameConfiguration(configuration, 0L), book.takeExact(20L))

        assertEquals(null, book.takeExact(10L))
        assertTrue(book.wasResolved(10L), "a late queued callback is not an unregistered frame")
    }

    @Test
    fun resolvedCallbackHistoryStaysBounded() {
        val book = FrameConfigurationBook()
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        repeat(256) { index ->
            val timestamp = index.toLong()
            assertTrue(book.register(timestamp, configuration))
            assertEquals(MatchedFrameConfiguration(configuration, 0L), book.takeExact(timestamp))
        }

        assertFalse(book.wasResolved(0L), "ancient callback identities must not grow forever")
        assertTrue(book.wasResolved(255L))
    }

    @Test
    fun callbackDroughtCannotGrowFrameConfigurationsWithTheStream() {
        val book = FrameConfigurationBook(capacity = 4)
        repeat(100) { index ->
            book.register(index.toLong(), FrameConfiguration(VideoSize(640, 360), 0))
        }

        assertEquals(4, book.size)
        assertEquals(null, book.takeExact(95L))
        val newest = book.takeExact(99L)
        assertEquals(VideoSize(640, 360), newest?.configuration?.size)
        assertEquals(99L, newest?.skippedFrames)
    }

    @Test
    fun frameConfigurationsStayBoundedUnderConcurrentRegistration() {
        val capacity = 64
        val count = 4_096
        val book = FrameConfigurationBook(capacity)
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val workers = List(4) { shard ->
            thread(start = true, name = "frame-configuration-writer-$shard") {
                try {
                    start.await()
                    for (timestamp in shard until count step 4) {
                        check(book.register(timestamp.toLong(), configuration))
                    }
                } catch (caught: Throwable) {
                    failure.compareAndSet(null, caught)
                }
            }
        }

        start.countDown()
        workers.forEach { worker ->
            worker.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(worker.isAlive, "a configuration writer did not terminate")
        }
        failure.get()?.let { throw it }

        assertEquals(capacity, book.size)
        assertEquals(null, book.takeExact((count - capacity - 1).toLong()))
        assertEquals(
            MatchedFrameConfiguration(configuration, (count - 1).toLong()),
            book.takeExact((count - 1).toLong()),
        )
    }

    @Test
    fun closingFrameConfigurationsRejectsAConcurrentLateRegistration() {
        val book = FrameConfigurationBook(capacity = 4)
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = thread(start = true, name = "frame-configuration-close-race") {
            try {
                var timestamp = 0L
                while (book.register(timestamp++, configuration)) {
                    started.countDown()
                }
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
            }
        }

        assertTrue(started.await(5, TimeUnit.SECONDS), "the configuration writer did not start")
        book.close()
        writer.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(writer.isAlive, "the configuration writer accepted values after close")
        failure.get()?.let { throw it }

        assertEquals(0, book.size)
        assertFalse(book.register(Long.MAX_VALUE, configuration))
        assertEquals(null, book.takeExact(Long.MAX_VALUE))
    }

    @Test
    fun closingFrameConfigurationsReturnsEveryUnconsumedAcceptedFrame() {
        val book = FrameConfigurationBook(capacity = 2)
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        assertTrue(book.register(10L, configuration))
        assertTrue(book.register(20L, configuration))
        assertTrue(book.register(30L, configuration))

        assertEquals(3L, book.close(), "one pruned and two queued frames never reached SurfaceTexture")
        assertEquals(0L, book.close(), "close accounting is emitted exactly once")
    }

    @Test
    fun failedMediaCodecReleaseCancelsItsConfigurationWithoutSupersedingIt() {
        val configuration = FrameConfiguration(VideoSize(640, 360), 0)
        val stored = FrameConfigurationBook()
        assertTrue(stored.register(100L, configuration))
        assertTrue(stored.cancel(100L))
        assertEquals(0L, stored.close())

        val immediatelyPruned = FrameConfigurationBook(capacity = 1)
        assertTrue(immediatelyPruned.register(200L, configuration))
        assertTrue(immediatelyPruned.register(100L, configuration))
        assertTrue(immediatelyPruned.cancel(100L))
        assertEquals(MatchedFrameConfiguration(configuration, 0L), immediatelyPruned.takeExact(200L))
        assertFalse(immediatelyPruned.cancel(100L), "one failed release is cancellable exactly once")
    }

    @Test
    fun replacingAnIdenticalTimestampCountsTheDisplacedFrame() {
        val book = FrameConfigurationBook()
        val first = FrameConfiguration(VideoSize(640, 360), 0)
        val replacement = FrameConfiguration(VideoSize(1280, 720), 90)

        assertTrue(book.register(100L, first))
        assertTrue(book.register(100L, replacement))

        assertEquals(MatchedFrameConfiguration(replacement, 1L), book.takeExact(100L))
        assertEquals(null, book.takeExact(100L), "a duplicate callback has no second configuration")
    }

    @Test
    fun gpuViewportUsesScaledPhysicalPixelsAndTreatsZeroAsInactive() {
        assertEquals(GpuViewport(640, 360), physicalGpuViewport(320, 180, 2f))
        assertEquals(null, physicalGpuViewport(0, 180, 2f))
        assertEquals(null, physicalGpuViewport(320, 0, 2f))
        assertEquals(null, physicalGpuViewport(320, 180, 0f))
        assertEquals(null, physicalGpuViewport(320, 180, Float.NaN))
        assertEquals(
            GpuViewport(Int.MAX_VALUE, Int.MAX_VALUE),
            physicalGpuViewport(Int.MAX_VALUE, Int.MAX_VALUE, Float.MAX_VALUE),
        )
    }

    @Test
    fun gpuOutputFitsStoredPixelsWithoutUpscalingOrLosingRotation() {
        val fullHd = VideoSize(1920, 1080)

        assertEquals(
            VideoSize(640, 360),
            fittedGpuOutputSize(fullHd, 0, GpuViewport(640, 480)),
        )
        assertEquals(
            fullHd,
            fittedGpuOutputSize(fullHd, 0, GpuViewport(3840, 2160)),
        )
        assertEquals(
            VideoSize(640, 360),
            fittedGpuOutputSize(fullHd, 90, GpuViewport(360, 640)),
        )
        assertEquals(
            VideoSize(333, 187),
            fittedGpuOutputSize(fullHd, 0, GpuViewport(333, 333)),
        )
        assertEquals(null, fittedGpuOutputSize(fullHd, 0, null))
    }

    /**
     * 17.21 RQ-3: a requested kernel is the ONE thing that lets the blit enlarge.
     *
     * The buffer normally stops at the source's own size, because the drawing step can enlarge
     * just as well and for free. On Android it cannot, so when a kernel is asked for the
     * enlargement moves here and the buffer takes the full fitted footprint instead.
     */
    @Test
    fun gpuOutputEnlargesOnlyWhenAKernelWillDoTheEnlarging() {
        val small = VideoSize(640, 360)

        assertEquals(
            small,
            fittedGpuOutputSize(small, 0, GpuViewport(1920, 1080)),
            "without a kernel the buffer must still refuse to grow past the source",
        )
        assertEquals(
            VideoSize(1920, 1080),
            fittedGpuOutputSize(small, 0, GpuViewport(1920, 1080), allowUpscale = true),
            "with one it takes the whole fitted footprint, which is what the kernel then fills",
        )
        assertEquals(
            VideoSize(1920, 1080),
            fittedGpuOutputSize(small, 90, GpuViewport(1080, 1920), allowUpscale = true),
            "a quarter turn swaps the portrait footprint back into the unrotated landscape buffer",
        )
        assertEquals(
            VideoSize(1080, 607),
            fittedGpuOutputSize(small, 0, GpuViewport(1080, 1080), allowUpscale = true),
            "the fit is still a fit: the narrow axis binds and aspect is kept",
        )
        assertEquals(
            VideoSize(1920, 1080),
            fittedGpuOutputSize(VideoSize(3840, 2160), 0, GpuViewport(1920, 1080), allowUpscale = true),
            "a downscale is unaffected by the flag, since the cap was never what bound it",
        )
    }

    @Test
    fun gpuOutputUsesPixelAspectToAvoidShadingInvisiblePixels() {
        val anamorphicSource = VideoSize(
            width = 720,
            height = 576,
            pixelAspectNumerator = 16,
            pixelAspectDenominator = 11,
        )

        assertEquals(
            VideoSize(640, 352),
            fittedGpuOutputSize(anamorphicSource, 0, GpuViewport(640, 480)),
        )
        assertEquals(
            VideoSize(720, 576),
            fittedGpuOutputSize(anamorphicSource, 0, GpuViewport(1047, 576)),
        )
        assertEquals(
            VideoSize(720, 576),
            fittedGpuOutputSize(anamorphicSource, 90, GpuViewport(576, 1047)),
        )
    }

    @Test
    fun retiredGenerationClosesOnlyAfterItsPublishedLeaseReturns() {
        val book = OutputGenerationLeaseBook<Any, Any>()
        val image = Any()
        val lease = Any()

        assertTrue(book.beginOutput())
        assertTrue(book.register(image))
        assertTrue(book.promote(image, lease))
        assertTrue(book.markPublished(lease))
        assertEquals(
            OutputGenerationResolution(closeReader = false, supersededFrames = 0L),
            book.retire(),
            "a Compose-visible lease still owns the ImageReader",
        )
        assertFalse(book.active)
        assertEquals(
            OutputGenerationResolution(false, 0L),
            book.removeImage(image),
            "promotion already moved the image into its lease",
        )
        assertEquals(
            OutputGenerationResolution(true, 0L),
            book.removeLease(lease),
            "the final lease retires the reader",
        )
        assertEquals(
            OutputGenerationResolution(false, 0L),
            book.removeLease(lease),
            "reader closure is emitted exactly once",
        )
    }

    @Test
    fun fenceWaitInFlightAlsoKeepsTheOldGenerationAlive() {
        val book = OutputGenerationLeaseBook<Any, Any>()
        val image = Any()

        assertTrue(book.beginOutput())
        assertTrue(book.register(image))
        assertEquals(OutputGenerationResolution(false, 1L), book.retire())
        assertFalse(book.register(Any()), "retired generations accept no newer image")
        assertEquals(OutputGenerationResolution(true, 0L), book.removeImage(image))
    }

    @Test
    fun emptyGenerationCanCloseAtRetirement() {
        val book = OutputGenerationLeaseBook<Any, Any>()

        assertEquals(OutputGenerationResolution(true, 0L), book.retire())
        assertEquals(OutputGenerationResolution(false, 0L), book.retire())
    }

    @Test
    fun resizeRetirementAccountsEveryInFlightStageExactlyOnce() {
        val book = OutputGenerationLeaseBook<String, String>()
        assertTrue(book.beginOutput()) // Swapped, ImageReader callback not run yet.
        assertTrue(book.beginOutput())
        assertTrue(book.register("fence-waiting"))
        assertTrue(book.beginOutput())
        assertTrue(book.register("posted"))
        assertTrue(book.promote("posted", "posted-lease"))

        assertEquals(OutputGenerationResolution(false, 3L), book.retire())
        assertFalse(book.markPublished("posted-lease"), "retirement already owns that outcome")
        assertEquals(OutputGenerationResolution(false, 0L), book.removeImage("fence-waiting"))
        assertEquals(OutputGenerationResolution(true, 0L), book.removeLease("posted-lease"))
        assertEquals(OutputGenerationResolution(false, 0L), book.retire())
    }

    @Test
    fun publicationWinningTheResizeRaceIsNeverAlsoSuperseded() {
        val book = OutputGenerationLeaseBook<String, String>()
        assertTrue(book.beginOutput())
        assertTrue(book.register("image"))
        assertTrue(book.promote("image", "lease"))
        assertTrue(book.markPublished("lease"))

        assertEquals(OutputGenerationResolution(false, 0L), book.retire())
        assertEquals(OutputGenerationResolution(true, 0L), book.removeLease("lease"))
    }

    @Test
    fun zeroViewportRetirementAccountsUnacquiredSwapsOnce() {
        val book = OutputGenerationLeaseBook<Any, Any>()
        assertTrue(book.beginOutput())
        assertTrue(book.beginOutput())

        assertEquals(OutputGenerationResolution(true, 2L), book.retire())
        assertEquals(OutputGenerationResolution(false, 0L), book.retire())
    }

    @Test
    fun finalTeardownReturnsEveryOutstandingOwnerAndClosesOnce() {
        val book = OutputGenerationLeaseBook<String, String>()
        assertTrue(book.beginOutput())
        assertTrue(book.register("waiting"))
        assertTrue(book.beginOutput())
        assertTrue(book.register("promoted"))
        assertTrue(book.promote("promoted", "lease"))

        val drained = book.forceDrain()
        assertEquals(listOf("waiting"), drained.images)
        assertEquals(listOf("lease"), drained.leases)
        assertTrue(drained.closeReader)
        assertEquals(2L, drained.supersededFrames)

        val again = book.forceDrain()
        assertTrue(again.images.isEmpty())
        assertTrue(again.leases.isEmpty())
        assertFalse(again.closeReader)
        assertEquals(0L, again.supersededFrames)
    }
}
