package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputGenerationLeaseBookTest {
    @Test
    fun capacityAppliesBackpressureBeforeImageReaderWouldThrow() {
        val book = OutputGenerationLeaseBook<String, String>(capacity = 2)
        assertTrue(book.register("one"))
        assertTrue(book.promote("one", "lease-one"))
        assertTrue(book.register("two"))
        assertFalse(book.hasCapacity)
        assertFalse(book.register("three"))

        assertFalse(book.removeLease("lease-one"))
        assertTrue(book.hasCapacity)
        assertTrue(book.register("three"))
    }

    @Test
    fun perFrameConfigurationSurvivesDynamicFormatOrdering() {
        val book = FrameConfigurationBook()
        val landscape = FrameConfiguration(VideoSize(1920, 1080), 0)
        val portrait = FrameConfiguration(VideoSize(1080, 1920), 90)
        book.register(100L, landscape)
        book.register(200L, portrait)

        assertEquals(landscape, book.takeExact(100L))
        assertEquals(portrait, book.takeExact(200L))
    }

    @Test
    fun aCoalescedSurfaceTextureFrameDropsOnlyOlderConfigurations() {
        val book = FrameConfigurationBook()
        val old = FrameConfiguration(VideoSize(640, 360), 0)
        val newest = FrameConfiguration(VideoSize(1280, 720), 90)
        book.register(10L, old)
        book.register(20L, newest)

        assertEquals(newest, book.takeExact(20L))
        assertEquals(null, book.takeExact(10L))
    }

    @Test
    fun callbackDroughtCannotGrowFrameConfigurationsWithTheStream() {
        val book = FrameConfigurationBook(capacity = 4)
        repeat(100) { index ->
            book.register(index.toLong(), FrameConfiguration(VideoSize(640, 360), 0))
        }

        assertEquals(4, book.size)
        assertEquals(null, book.takeExact(95L))
        assertEquals(VideoSize(640, 360), book.takeExact(99L)?.size)
    }

    @Test
    fun retiredGenerationClosesOnlyAfterItsPublishedLeaseReturns() {
        val book = OutputGenerationLeaseBook<Any, Any>()
        val image = Any()
        val lease = Any()

        assertTrue(book.register(image))
        assertTrue(book.promote(image, lease))
        assertFalse(book.retire(), "a Compose-visible lease still owns the ImageReader")
        assertFalse(book.active)
        assertFalse(book.removeImage(image), "promotion already moved the image into its lease")
        assertTrue(book.removeLease(lease), "the final lease retires the reader")
        assertFalse(book.removeLease(lease), "reader closure is emitted exactly once")
    }

    @Test
    fun fenceWaitInFlightAlsoKeepsTheOldGenerationAlive() {
        val book = OutputGenerationLeaseBook<Any, Any>()
        val image = Any()

        assertTrue(book.register(image))
        assertFalse(book.retire())
        assertFalse(book.register(Any()), "retired generations accept no newer image")
        assertTrue(book.removeImage(image))
    }

    @Test
    fun emptyGenerationCanCloseAtRetirement() {
        val book = OutputGenerationLeaseBook<Any, Any>()

        assertTrue(book.retire())
        assertFalse(book.retire())
    }

    @Test
    fun finalTeardownReturnsEveryOutstandingOwnerAndClosesOnce() {
        val book = OutputGenerationLeaseBook<String, String>()
        assertTrue(book.register("waiting"))
        assertTrue(book.register("promoted"))
        assertTrue(book.promote("promoted", "lease"))

        val drained = book.forceDrain()
        assertEquals(listOf("waiting"), drained.images)
        assertEquals(listOf("lease"), drained.leases)
        assertTrue(drained.closeReader)

        val again = book.forceDrain()
        assertTrue(again.images.isEmpty())
        assertTrue(again.leases.isEmpty())
        assertFalse(again.closeReader)
    }
}
