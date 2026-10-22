package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.FrameDurationEstimator
import io.github.yuroyami.kiteplayer.internal.SyncLaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameDurationEstimatorTest {

    private fun estimator(rate: Double? = null, discontinuous: Boolean = false) = FrameDurationEstimator(
        containerFrameRate = rate,
        maxFrameDurationUs = if (discontinuous) {
            SyncLaw.MAX_FRAME_DURATION_DISCONTINUOUS_US
        } else {
            SyncLaw.MAX_FRAME_DURATION_NORMAL_US
        },
    )

    @Test
    fun `a measured duration is used when it is sane`() {
        val e = estimator()
        assertEquals(41_667, e.estimate(measuredUs = 41_667, decoderReportedUs = null))
    }

    @Test
    fun `matroska millisecond rounding is snapped away`() {
        // A 23.976 fps file in Matroska reports 42 ms, 41 ms, 42 ms, 42 ms, 41 ms as the muxer
        // rounds to whole milliseconds and compensates. Scheduling on those raw values makes a
        // 60 Hz display alternate 2 and 3 vsyncs per frame, which is visible judder.
        val e = estimator(rate = 24000.0 / 1001.0)
        val declared = 41_708L // 1001/24000 seconds
        val wobble = listOf(42_000L, 41_000L, 42_000L, 42_000L, 41_000L)

        var last = 0L
        repeat(20) { i ->
            last = e.estimate(measuredUs = wobble[i % wobble.size], decoderReportedUs = null)
        }

        assertEquals(
            declared,
            last,
            "after enough agreeing samples the estimator must snap to the declared rate and stop wobbling",
        )
    }

    @Test
    fun `snapping needs the configured number of agreeing samples`() {
        val e = estimator(rate = 25.0)
        // 25 fps is 40 000 us. Feed values 1 ms off, inside the 3.1 ms tolerance.
        repeat(FrameDurationEstimator.SNAP_AFTER_SAMPLES - 1) {
            val d = e.estimate(measuredUs = 41_000, decoderReportedUs = null)
            assertEquals(41_000, d, "before the sample threshold the measurement is used as-is")
        }
        assertEquals(40_000, e.estimate(measuredUs = 41_000, decoderReportedUs = null))
    }

    @Test
    fun `a real rate change breaks the snap`() {
        val e = estimator(rate = 25.0)
        repeat(FrameDurationEstimator.SNAP_AFTER_SAMPLES) { e.estimate(40_100, null) }
        assertEquals(40_000, e.estimate(40_100, null))

        // The stream genuinely switches to 50 fps. That is well outside the tolerance, so the
        // estimator must stop trusting the declared rate rather than force 25 fps onto it.
        assertEquals(20_000, e.estimate(20_000, null))
        assertEquals(20_000, e.estimate(20_000, null))
    }

    @Test
    fun `a null measurement falls back through the decoder then the container`() {
        val e = estimator(rate = 30.0)
        assertEquals(
            33_000,
            e.estimate(measuredUs = null, decoderReportedUs = 33_000),
            "with no next frame yet, the decoder's own duration is next best",
        )
        assertEquals(
            33_333,
            e.estimate(measuredUs = null, decoderReportedUs = null),
            "with nothing else, the container's declared rate",
        )
    }

    @Test
    fun `a broken stream with nothing usable gets the conventional guess`() {
        val e = estimator(rate = null)
        assertEquals(FrameDurationEstimator.FALLBACK_DURATION_US, e.estimate(null, null))
    }

    @Test
    fun `absurd measurements are rejected`() {
        val e = estimator(rate = 25.0, discontinuous = true)
        // An MPEG-TS timestamp jump of an hour. Using it would freeze the picture for an hour.
        assertEquals(40_000, e.estimate(measuredUs = 3_600_000_000, decoderReportedUs = null))
        // Zero and negative deltas happen on reordered or duplicated timestamps.
        assertEquals(40_000, e.estimate(measuredUs = 0, decoderReportedUs = null))
        assertEquals(40_000, e.estimate(measuredUs = -5_000, decoderReportedUs = null))
    }

    @Test
    fun `a nonsense container rate is ignored`() {
        for (rate in listOf(0.0, -25.0, Double.NaN, Double.POSITIVE_INFINITY, 100_000.0)) {
            val e = estimator(rate = rate)
            assertEquals(
                FrameDurationEstimator.FALLBACK_DURATION_US,
                e.estimate(null, null),
                "rate $rate must not be trusted",
            )
        }
    }

    @Test
    fun `reset forgets the snap`() {
        val e = estimator(rate = 25.0)
        repeat(FrameDurationEstimator.SNAP_AFTER_SAMPLES) { e.estimate(40_100, null) }
        assertEquals(40_000, e.estimate(40_100, null))

        e.reset()
        assertEquals(
            40_100,
            e.estimate(40_100, null),
            "a seek invalidates the measurement history, because a duration measured across the boundary is meaningless",
        )
    }

    @Test
    fun `the estimate is always usable`() {
        val e = estimator(rate = 23.976)
        val hostile = listOf<Long?>(null, 0, -1, 1, Long.MAX_VALUE, 41_708, 3_600_000_001)
        for (measured in hostile) {
            for (reported in hostile) {
                val d = e.estimate(measured, reported)
                assertTrue(d > 0, "measured=$measured reported=$reported gave $d")
                assertTrue(d <= SyncLaw.MAX_FRAME_DURATION_NORMAL_US, "measured=$measured gave $d")
            }
        }
    }
}
