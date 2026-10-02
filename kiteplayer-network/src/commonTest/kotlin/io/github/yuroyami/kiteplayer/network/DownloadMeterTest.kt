package io.github.yuroyami.kiteplayer.network

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DownloadMeterTest {

    @Test
    fun noRateBeforeHalfAMebibyte() {
        val meter = DownloadMeter()
        repeat(7) { meter.add(65_536, 50.milliseconds) }
        assertNull(meter.bitsPerSecond())
        meter.add(65_536, 50.milliseconds)
        assertNotNull(meter.bitsPerSecond())
    }

    @Test
    fun aSteadyDownloadReadsItsOwnRate() {
        val meter = DownloadMeter()
        // 64 KiB every 50 ms is 10,485,760 bits per second.
        repeat(32) { meter.add(65_536, 50.milliseconds) }
        assertEquals(10_485_760.0, assertNotNull(meter.bitsPerSecond()).toDouble(), 10_000.0)
    }

    @Test
    fun recentBytesWeighMost() {
        val meter = DownloadMeter()
        repeat(32) { meter.add(65_536, 500.milliseconds) }
        repeat(48) { meter.add(65_536, 50.milliseconds) }
        // Three half-lives on, the slow bytes weigh an eighth, and the figure is over half the new rate.
        val rate = assertNotNull(meter.bitsPerSecond())
        assertTrue(rate in 5_000_000..10_485_760, "$rate")
    }

    @Test
    fun aDownloadThatNeverWaitsForTheReaderIsMeasuredWhole() = runTest {
        val body = ByteChannel(autoFlush = true)
        val pipe = ByteChannel(autoFlush = true)
        val meter = DownloadMeter()
        launch {
            repeat(16) {
                delay(50)
                body.writeFully(ByteArray(65_536))
            }
            body.flushAndClose()
        }
        val reader = launch { drain(pipe) }
        copyMeasured(body, pipe, meter, testTimeSource.markNow(), testTimeSource)
        pipe.flushAndClose()
        reader.join()
        assertEquals(1_048_576, meter.measuredBytes())
        assertEquals(10_485_760.0, assertNotNull(meter.bitsPerSecond()).toDouble(), 10_485_760 * 0.05)
    }

    @Test
    fun aWriteThatWaitsForTheReaderEndsTheMeasure() = runTest {
        val body = ByteChannel(autoFlush = true)
        val pipe = ByteChannel(autoFlush = true)
        val meter = DownloadMeter()
        launch {
            repeat(32) {
                delay(1)
                body.writeFully(ByteArray(65_536))
            }
            body.flushAndClose()
        }
        // The reader starts late, so the pipe fills and the copy waits for it.
        val reader = launch {
            delay(5_000)
            drain(pipe)
        }
        copyMeasured(body, pipe, meter, testTimeSource.markNow(), testTimeSource)
        pipe.flushAndClose()
        reader.join()
        // The pipe holds a mebibyte, so about that much was measured before the wait, and nothing after.
        assertTrue(meter.measuredBytes() in 524_288L until 2_097_152L, "${meter.measuredBytes()}")
    }

    private suspend fun drain(pipe: ByteChannel) {
        val sink = ByteArray(65_536)
        while (pipe.readAvailable(sink, 0, sink.size) >= 0) Unit
    }
}
