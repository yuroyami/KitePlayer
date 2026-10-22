package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Training fixtures with known causes. These are not a held-out music recognition score. */
class SuperFluxRecognitionTest {
    private fun detect(rate: Int, samples: FloatArray): List<AudioDetection> {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val events = ArrayList<AudioDetection>()
        analyzer.onAnalysis = { frame ->
            frame.detections?.let { batch ->
                for (index in 0 until batch.size) events += batch[index]
            }
        }
        analyzer.feed(samples, samples.size, 1, 0L)
        return events
    }

    private fun reportTiming(label: String, rate: Int, targets: List<Long>, hits: List<AudioDetection>) {
        val errors = targets.indices.map { hits[it].ptsMicros - targets[it] }
        val ordered = errors.sorted()
        val absolute = errors.map { abs(it) }.sorted()
        val availability = targets.indices.map { hits[it].availableMicros - targets[it] }
        val middle = ordered.size / 2
        val median = if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2.0 else ordered[middle].toDouble()
        val p95 = absolute[((absolute.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
        println("onset timing: fixture=$label rate=$rate matched=${hits.size} missed=0 " +
            "signedErrorsUs=$errors medianUs=$median absoluteP95Us=$p95 absoluteMaxUs=${absolute.last()} " +
            "availableAfterSourceUs=$availability")
    }

    @Test
    fun isolatedImpulsesAndSixtyMillisecondDoubleHitsHaveExactCounts() {
        val requested = listOf(400_000L, 701_125L, 761_125L, 1_204_375L, 1_608_750L)
        for (rate in listOf(8_000, 16_000, 44_100, 48_000)) {
            val samples = FloatArray(rate * 2)
            val indices = requested.map { (it * rate / 1_000_000L).toInt() }
            val targets = indices.map { it * 1_000_000L / rate }
            for (index in indices) samples[index] = 0.8f
            val hits = detect(rate, samples).filter { it.kind == AudioEventKind.Onset }
            assertEquals(targets.size, hits.size, "rate=$rate times=${hits.map { it.ptsMicros }}")
            for (index in targets.indices) {
                assertTrue(abs(hits[index].ptsMicros - targets[index]) <= 25_000L,
                    "rate=$rate target=${targets[index]} observed=${hits[index].ptsMicros}")
                assertTrue(hits[index].availableMicros >= targets[index], "no sample is available before its impulse")
            }
            reportTiming("impulses", rate, targets, hits)
        }
    }

    @Test
    fun aHeldToneHasOneAttackAndNoContinuingEventTrain() {
        val rate = 8_000
        val samples = FloatArray(rate * 2) { index ->
            if (index < rate * 3 / 10) 0f else (0.4 * sin(2 * PI * 1_000 * index / rate)).toFloat()
        }
        val hits = detect(rate, samples).filter { it.kind == AudioEventKind.Onset }
        assertEquals(1, hits.size, "times=${hits.map { it.ptsMicros }}")
        assertTrue(abs(hits.single().ptsMicros - 300_000L) <= 25_000L)
        reportTiming("held tone", rate, listOf(300_000L), hits)
    }

    @Test
    fun halfSemitoneVibratoDoesNotCreateFreshAttacks() {
        val rate = 8_000
        var phase = 0.0
        val samples = FloatArray(rate * 2) { index ->
            val frequency = 1_000.0 * 2.0.pow(0.5 * sin(2 * PI * 5 * index / rate) / 12)
            phase += 2 * PI * frequency / rate
            (0.4 * sin(phase)).toFloat()
        }
        val hits = detect(rate, samples).filter { it.kind != AudioEventKind.EnergyRise && it.ptsMicros > 200_000L }
        assertTrue(hits.isEmpty(), "sustained vibrato produced ${hits.map { it.kind to it.ptsMicros }}")
    }

    @Test
    fun lowAttacksRemainDetectableOverASustainedBassTone() {
        val rate = 8_000
        val targets = listOf(4_000, 8_000, 12_000)
        val samples = FloatArray(rate * 2) { index ->
            val time = index.toDouble() / rate
            var sample = 0.12 * sin(2 * PI * 55 * time)
            for (target in targets) {
                val since = (index - target).toDouble() / rate
                if (since in 0.0..0.15) sample += 0.8 * exp(-since / 0.025) * sin(2 * PI * 80 * since)
            }
            sample.toFloat()
        }
        val hits = detect(rate, samples).filter { it.kind == AudioEventKind.LowTransient && it.ptsMicros > 200_000L }
        assertEquals(3, hits.size, "hits=${hits.map { Triple(it.ptsMicros, it.confidence, it.strength) }}")
        for (index in targets.indices) {
            assertTrue(abs(hits[index].ptsMicros - targets[index] * 1_000_000L / rate) <= 25_000L)
        }
        reportTiming("bass plus attacks", rate, targets.map { it * 1_000_000L / rate }, hits)
    }

    @Test
    fun rapidLowAttacksRemainDistinctAtDifferentLevels() {
        val rate = 8_000
        val targets = List(8) { 4_000 + it * 600 }
        for (amplitude in listOf(0.05, 0.2, 0.8)) {
            val samples = FloatArray(rate * 3 / 2) { index ->
                var sample = 0.0
                for (target in targets) {
                    val since = (index - target).toDouble() / rate
                    if (since in 0.0..0.06) sample += amplitude * exp(-since / 0.012) * sin(2 * PI * 80 * since)
                }
                sample.toFloat()
            }
            val hits = detect(rate, samples).filter { it.kind == AudioEventKind.LowTransient }
            assertEquals(targets.size, hits.size, "amplitude=$amplitude times=${hits.map { it.ptsMicros }}")
            for (index in targets.indices) {
                assertTrue(abs(hits[index].ptsMicros - targets[index] * 1_000_000L / rate) <= 25_000L)
            }
            reportTiming("rapid low attacks amplitude=$amplitude", rate, targets.map { it * 1_000_000L / rate }, hits)
        }
    }
}
