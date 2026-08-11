@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runtime qualification of the RemoteIO arm on the named iOS simulator. */
class CoreAudioSinkIosTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun `RemoteIO consumes the C ring publishes an anchor and tears down completely`() = runBlocking {
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 48_000 }
        val ring = handoff.ring
        var observedOffsetNanos: Long? = null

        try {
            fillRing(ring, 0)
            sink.start()
            var attempts = 0
            while (observedOffsetNanos == null && attempts < 100) {
                delay(10)
                val anchor = ringAnchor(ring)
                if (anchor.valid) {
                    observedOffsetNanos = anchor.audibleAtNanos - AppleHostClock.nanos()
                }
                attempts++
            }
            sink.stop()

            assertTrue(sink.callbacks > 0, "RemoteIO never entered the C callback")
            assertTrue(ringConsumed(ring) > 0, "RemoteIO consumed no frame from the C ring")
            val offset = observedOffsetNanos
            assertTrue(offset != null, "RemoteIO published no clock anchor")
            val offsetMillis = offset / 1_000_000.0
            assertTrue(
                offsetMillis > -5.0 && offsetMillis < 500.0,
                "the RemoteIO anchor must share the engine clock and remain near now, was $offsetMillis ms",
            )
        } finally {
            sink.close()
        }

        assertEquals(0, sink.retainedResources(), "close must release the C sink, ring and format")
        sink.close()
        assertEquals(0, sink.retainedResources(), "a second close must remain a no-op")

        val fresh = CoreAudioSink()
        try {
            val freshHandoff = fresh.openWithRing(format) { 4_800 }
            fillRing(freshHandoff.ring, 0)
            fresh.start()
            var attempts = 0
            while (fresh.callbacks == 0L && attempts < 100) {
                delay(10)
                attempts++
            }
            assertTrue(fresh.callbacks > 0, "a fresh sink did not start after the first sink closed")
            fresh.stop()
        } finally {
            fresh.close()
        }
        assertEquals(0, fresh.retainedResources(), "the fresh sink must also release every C handle")
    }
}
