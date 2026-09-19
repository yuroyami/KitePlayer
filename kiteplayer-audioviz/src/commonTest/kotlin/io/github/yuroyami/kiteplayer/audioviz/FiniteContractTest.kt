package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every public number stays finite and every documented range holds, whatever the input does. */
class FiniteContractTest {
    private val worker = ManualVizDispatcher()
    private val feed = AudioVizFeed(worker)
    private val problems = ArrayList<String>()
    private var checked = 0

    @AfterTest
    fun closeFeed() {
        feed.close()
        worker.runAll()
    }

    @Test
    fun hostileInputNeverPublishesANonFiniteOrOutOfRangeValue() {
        var micros = 0L
        var generation = Generation.Initial
        fun play(seconds: Float, rate: Int, channels: Int, sample: (Int, Int) -> Float) {
            val format = AudioFormat(rate, channels, SampleFormat.F32)
            val block = FloatArray(1024 * channels)
            var written = 0
            val total = (seconds * rate).toInt()
            while (written < total) {
                val frames = minOf(1024, total - written)
                for (frame in 0 until frames) for (channel in 0 until channels) {
                    block[frame * channels + channel] = sample(written + frame, channel)
                }
                feed.onAudio(generation, Pts(micros), block, frames, format)
                worker.runAll()
                feed.timeline.newest()?.let { check(it, "rate $rate channels $channels at $micros") }
                micros += frames * 1_000_000L / rate
                written += frames
            }
        }
        val noise = Rng(7L)
        play(1f, 48_000, 2) { _, _ -> 0f }
        play(2f, 48_000, 2) { index, _ ->
            when (index % 997) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                2 -> Float.NEGATIVE_INFINITY
                3 -> 1e30f
                else -> noise.signed()
            }
        }
        play(1f, 48_000, 2) { _, _ -> 16f }
        play(1f, 48_000, 2) { index, _ -> if (index % 2 == 0) 16f else -16f }
        play(1f, 48_000, 2) { _, _ -> 1e-38f }
        play(3f, 48_000, 2) { index, channel ->
            val t = index / 48_000f
            (if (channel == 0) 1f else -1f) * sin(2f * PI.toFloat() * (40f + 2_000f * t) * t) *
                if ((index / 12_000) % 2 == 0) 1f else 0.001f
        }
        generation = generation.next()
        feed.onDiscontinuity(generation)
        micros = -500_000L
        play(2f, 44_100, 1) { index, _ -> if (index % 11_025 < 400) 0.9f * noise.signed() else 0.05f * noise.signed() }
        generation = generation.next()
        feed.onDiscontinuity(generation)
        play(2f, 96_000, 6) { index, channel -> 0.3f * sin(index * 0.01f * (channel + 1)) }
        play(1f, 8_000, 2) { _, _ -> noise.signed() }

        // The view path interpolates and joins events; check what a drawing actually receives.
        val state = AudioVizState(feed = feed) { VizClockReading(micros - 300_000L, 1.0, generation) }
        repeat(20) { check(state.nextFrame(), "view frame $it") }
        assertTrue(checked > 500, "only $checked frames were checked")
        assertTrue(problems.isEmpty(), problems.take(12).joinToString("\n"))
    }

    private fun check(frame: SpectrumFrame, where: String) {
        checked++
        fun finite(name: String, value: Float) { if (!value.isFinite()) problems += "$where: $name = $value" }
        fun unit(name: String, value: Float) {
            finite(name, value)
            if (value.isFinite() && (value < 0f || value > 1f)) problems += "$where: $name = $value outside 0..1"
        }
        fun range(name: String, value: Float, low: Float, high: Float) {
            finite(name, value)
            if (value.isFinite() && (value < low || value > high)) problems += "$where: $name = $value outside $low..$high"
        }
        listOf("level" to frame.level, "bass" to frame.bass, "mid" to frame.mid, "treble" to frame.treble,
            "beat" to frame.beat, "pulse" to frame.pulse, "levelRel" to frame.levelRel, "bassRel" to frame.bassRel,
            "midRel" to frame.midRel, "trebleRel" to frame.trebleRel, "kick" to frame.kick, "snare" to frame.snare,
            "hat" to frame.hat, "onsetStrength" to frame.onsetStrength, "kickPulse" to frame.kickPulse,
            "snarePulse" to frame.snarePulse, "hatPulse" to frame.hatPulse, "energy" to frame.energy,
            "density" to frame.density, "mood" to frame.mood, "loudShort" to frame.loudShort,
            "loudLong" to frame.loudLong, "dropPulse" to frame.dropPulse, "beatConfidence" to frame.beatConfidence,
            "beatPhase" to frame.beatPhase, "barPhase" to frame.barPhase, "phrasePhase" to frame.phrasePhase,
            "keyHue" to frame.keyHue, "keyConfidence" to frame.keyConfidence, "centroid" to frame.centroid,
            "flatness" to frame.flatness, "width" to frame.width,
        ).forEach { (name, value) -> unit(name, value) }
        range("novelty", frame.novelty, 0f, 4f)
        range("trend", frame.trend, 0f, 3f)
        range("bpm", frame.bpm, 0f, 1_000f)
        finite("beatInSeconds", frame.beatInSeconds)
        if (frame.beatInSeconds.isFinite() && frame.beatInSeconds < 0f && frame.beatInSeconds != -1f) {
            problems += "$where: beatInSeconds = ${frame.beatInSeconds}"
        }
        finite("waveformGain", frame.waveformGain)
        finite("motionRate", frame.motionRate)
        for ((name, array) in listOf("bands" to frame.bands, "peaks" to frame.peaks, "bandsRel" to frame.bandsRel)) {
            array.forEachIndexed { index, value -> unit("$name[$index]", value) }
        }
        for ((name, array) in listOf("scope" to frame.scope, "scopeLeft" to frame.scopeLeft, "scopeRight" to frame.scopeRight)) {
            array.forEachIndexed { index, value -> range("$name[$index]", value, -16f, 16f) }
        }
        frame.chroma.forEachIndexed { index, value -> range("chroma[$index]", value, 0f, Float.MAX_VALUE) }
        frame.rhythm?.let { rhythm ->
            range("rhythm.bpm", rhythm.bpm, 0f, 1_000f)
            unit("rhythm.tempoSupport", rhythm.tempoSupport)
            unit("rhythm.beatSupport", rhythm.beatSupport)
            unit("rhythm.beatPhase", rhythm.beatPhase)
            range("rhythm.alternativeBpm", rhythm.alternativeBpm, 0f, 1_000f)
            unit("rhythm.alternativeSupport", rhythm.alternativeSupport)
            finite("rhythm.beatInSeconds", rhythm.beatInSeconds)
        }
        frame.drivers?.let { drivers ->
            val all = listOf(drivers.overall, drivers.bass, drivers.mid, drivers.treble) +
                List(drivers.bandCount) { drivers.band(it) }
            all.forEachIndexed { index, driver ->
                unit("driver[$index].fast", driver.fast)
                unit("driver[$index].slow", driver.slow)
                unit("driver[$index].peak", driver.peak)
            }
            val gainDb = 10.0 * kotlin.math.log10(drivers.powerGain)
            if (!drivers.powerGain.isFinite() || gainDb < -24.0001 || gainDb > 24.0001) problems += "$where: powerGain ${drivers.powerGain}"
            if (!drivers.referencePower.isFinite() || drivers.referencePower <= 0.0) problems += "$where: reference ${drivers.referencePower}"
        }
        frame.power?.let { power ->
            range("power.total", power.totalMeanSquare, 0f, Float.MAX_VALUE)
            for (index in 0 until power.binCount) range("power.bin[$index]", power.binMeanSquare(index), 0f, Float.MAX_VALUE)
            for (index in 0 until power.bandCount) range("power.band[$index]", power.bandMeanSquare(index), 0f, Float.MAX_VALUE)
        }
        frame.programme?.meanSquare?.let { if (!it.isFinite() || it < 0.0) problems += "$where: programme $it" }
        frame.detections?.let { batch ->
            for (index in 0 until batch.size) {
                val hit = batch[index]
                unit("detection.strength", hit.strength)
                unit("detection.confidence", hit.confidence)
                unit("detection.surprise", hit.surprise)
                if (hit.ptsMicros > hit.availableMicros) problems += "$where: detection after its availability"
            }
        }
    }
}
