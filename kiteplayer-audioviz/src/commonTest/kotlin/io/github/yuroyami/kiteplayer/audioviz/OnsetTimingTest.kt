package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

class OnsetTimingTest {
    @Test
    fun detectionsKeepAReferenceInTheLatestInputInterval() {
        for (rate in listOf(8_000, 44_100, 48_000)) {
            val analyzer = SpectrumAnalyzer(sampleRate = rate)
            val audio = FloatArray(rate / 2)
            audio[rate * 3 / 10 + 39] = 0.8f
            var count = 0
            analyzer.onAnalysis = { frame ->
                frame.detections?.let { batch ->
                    val reference = batch.availableThroughMicros - analyzer.hop * 500_000L / rate
                    assertEquals(reference, batch.completeThroughMicros,
                        "the detection watermark follows the detector's reference time")
                    for (index in 0 until batch.size) {
                        val event = batch[index]
                        assertEquals(reference, event.ptsMicros)
                        assertTrue(event.ptsMicros > frame.ptsMicros,
                            "the spectral centre and the causal attack estimate have separate references")
                        assertEquals(batch.availableThroughMicros, event.availableMicros)
                        if (event.kind == AudioEventKind.Onset) {
                            count++
                            val source = (rate * 3 / 10 + 39) * 1_000_000L / rate
                            assertTrue(abs(event.ptsMicros - source) <= 6_000L,
                                "rate=$rate source=$source observed=${event.ptsMicros}")
                        }
                    }
                }
            }
            analyzer.feed(audio, audio.size, 1, 0L)
            assertEquals(1, count, "rate=$rate")
        }
    }

    private fun timeline(): SpectrumTimeline {
        fun frame(at: Long, scalarBeat: Float, batch: AudioDetections) = SpectrumFrame(
            ptsMicros = at, bands = FloatArray(4), peaks = FloatArray(4), scope = FloatArray(4),
            level = 0.5f, bass = 0f, mid = 0f, treble = 0f, beat = scalarBeat, pulse = 0f,
            detections = batch,
        )
        val timeline = SpectrumTimeline(4)
        val event = AudioDetection(AudioEventKind.Onset, 50_000L, 60_000L, 0.7f, 0.8f, 0.5f)
        timeline.push(frame(10_000L, 0.7f, AudioDetections(60_000L, 50_000L, arrayOf(event))))
        timeline.push(frame(20_000L, 0f, AudioDetections(70_000L, 60_000L, emptyArray())))
        assertEquals(1, timeline.eventStats.retainedEvents)
        return timeline
    }

    @Test
    fun anticipationUsesTheEventTimeAcrossSeparatelyTimedFeatures() {
        val timeline = timeline()
        assertEquals(0.03f, timeline.nextOnsetSeconds(20_000L), 1e-6f)
        assertEquals(-1f, timeline.nextOnsetSeconds(50_000L))
        timeline.clear()
        assertEquals(-1f, timeline.nextOnsetSeconds(20_000L))
    }

    @Test
    fun scalarCompatibilitySamplingUsesTheEventTime() {
        val timeline = timeline()
        assertEquals(0f, assertNotNull(timeline.sample(25_000L, 0L)).beat,
            "the raw feature's scalar must not fire before its timestamped detection")
        assertEquals(0.7f, assertNotNull(timeline.sample(55_000L, 25_000L)).beat)
        assertEquals(0f, assertNotNull(timeline.sample(60_000L, 55_000L)).beat)
    }
}
