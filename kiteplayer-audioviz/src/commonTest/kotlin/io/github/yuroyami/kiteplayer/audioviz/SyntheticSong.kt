package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.asFuture
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Three pieces of fake music, used everywhere a test needs something to listen to.
 *
 * The point of them is the gap between them. A visualiser that shows the same picture for the pad
 * and for the drum loop is broken however good either picture looks, and no test can say so
 * unless both exist and are honestly different: one quiet and smooth, one loud and full of hits.
 *
 * Levels are deliberately far apart, because the thing being tested is whether the library copes
 * with a song being mastered quietly. The pad sits near -38 dBFS and the loop near -12.
 */
internal object SyntheticSong {

    /** Nothing at all. The floor every other measurement is compared against. */
    fun silence(seconds: Float, sampleRate: Int = 48_000): FloatArray =
        FloatArray((seconds * sampleRate).toInt())

    /**
     * A soft chord that swells and fades, with no transients anywhere.
     *
     * This is what "calm" means to the library: quiet, smooth, and with nothing in it that a
     * beat detector could catch.
     */
    fun calmPad(seconds: Float, sampleRate: Int = 48_000): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val partials = floatArrayOf(110f, 165f, 220f, 330f, 440f)
        val out = FloatArray(total)
        for (index in 0 until total) {
            val time = index.toFloat() / sampleRate
            val swell = 0.7f + 0.3f * sin(2f * PI.toFloat() * 0.15f * time)
            var sum = 0f
            for (partial in partials.indices) {
                sum += sin(2f * PI.toFloat() * partials[partial] * time + partial)
            }
            out[index] = sum / partials.size * swell * 0.045f
        }
        return out
    }

    /**
     * A drum pattern with a kick, a snare on the off beats and hats between them, plus a bass
     * note and a chord stab.
     *
     * Loud, busy and squarely on a grid, so the tempo tracker has something to find.
     */
    fun drumLoop(seconds: Float, beatsPerMinute: Float = 130f, sampleRate: Int = 48_000): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val beat = 60f / beatsPerMinute
        val out = FloatArray(total)
        var noise = 4_242L
        for (index in 0 until total) {
            val time = index.toFloat() / sampleRate
            val intoBeat = time % beat
            val intoHalfBeat = time % (beat / 2f)
            val intoBar = time % (beat * 4f)

            noise = noise * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = (noise shr 40).toFloat() / 8_388_608f

            // A kick is a tone that slides down fast. That slide is what makes it a kick. It starts
            // inside the range a kick detector watches rather than above it: a tone sweeping in
            // from higher up arrives as a second onset a moment after the hit, which is a fault in
            // the generator rather than in the detector.
            val kick = sin(2f * PI.toFloat() * (46f + 56f * exp(-intoBeat * 30f)) * intoBeat) *
                exp(-intoBeat * 11f) * 0.9f
            // Snares land on beats two and four.
            val sinceSnare = if (intoBar >= beat) (intoBar - beat) % (beat * 2f) else beat
            val snare = white * exp(-sinceSnare * 26f) * 0.55f
            val hat = white * exp(-intoHalfBeat * 90f) * 0.22f
            // The bass note fades rather than switching level part way through the beat. A step in
            // gain is itself a burst of low frequency energy, and a kick detector is right to call
            // that an onset, so a stepped generator quietly tests the wrong thing.
            val bass = sin(2f * PI.toFloat() * 55f * time) * 0.3f * (0.55f + 0.45f * exp(-intoBeat * 3.5f))
            var stab = 0f
            for (partial in intArrayOf(330, 415, 494, 660)) {
                stab += sin(2f * PI.toFloat() * partial * time)
            }
            stab = stab / 4f * exp(-intoBeat * 7f) * 0.45f

            out[index] = (kick + snare + hat + bass + stab).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * What the two channels of the pad disagree on: its upper notes slightly detuned, so left and
     * right drift in and out of phase the way a chorus or a wide reverb makes them.
     *
     * It is added to one channel and taken from the other, so the mono mix is exactly [calmPad] and
     * every mono measurement stays the same. Only the stereo readings see it.
     */
    fun calmPadSide(seconds: Float, sampleRate: Int = 48_000): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val out = FloatArray(total)
        for (index in 0 until total) {
            val time = index.toFloat() / sampleRate
            val swell = 0.7f + 0.3f * sin(2f * PI.toFloat() * 0.15f * time)
            val drift = sin(2f * PI.toFloat() * 221.3f * time) + sin(2f * PI.toFloat() * 331.9f * time + 1f)
            out[index] = drift * 0.5f * swell * 0.03f
        }
        return out
    }

    /** The drum loop's stereo: hats bouncing between left and right, and a wider copy of the stab. */
    fun drumLoopSide(seconds: Float, beatsPerMinute: Float = 130f, sampleRate: Int = 48_000): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val beat = 60f / beatsPerMinute
        val out = FloatArray(total)
        var noise = 9_191L
        for (index in 0 until total) {
            val time = index.toFloat() / sampleRate
            val halfBeats = (time / (beat / 2f)).toInt()
            val intoHalfBeat = time % (beat / 2f)
            val intoBeat = time % beat
            noise = noise * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = (noise shr 40).toFloat() / 8_388_608f
            val pan = if (halfBeats % 2 == 0) 1f else -1f
            val hat = white * exp(-intoHalfBeat * 90f) * 0.14f * pan
            val chorus = sin(2f * PI.toFloat() * 662.5f * time) * exp(-intoBeat * 7f) * 0.12f
            out[index] = hat + chorus
        }
        return out
    }
}

/**
 * Plays synthetic PCM against an independent audible clock with 200 ms of decoded audio ahead.
 *
 * The production analyser and event cursor remain real. This is a deterministic render fixture,
 * not a hardware clock or a held-out recognition corpus. End of input drains without looping.
 */
internal class SongPlayer(
    mono: FloatArray,
    private val sampleRate: Int = 48_000,
    bandCount: Int = 48,
    /** Audio skipped at attachment, after feeding it to settle analysis history. */
    warmupSeconds: Float = 3f,
    /** Left is mono plus side; right is mono minus side. Channel power remains independent. */
    private val side: FloatArray? = null,
) {
    private val timeline = SpectrumTimeline(capacity = 512)
    private val analyzer = SpectrumAnalyzer(bandCount = bandCount, sampleRate = sampleRate).apply {
        onAnalysis = timeline::push
    }
    private val events = timeline.eventCursor()
    private val source = mono
    private var readIndex = 0
    private var clockSeconds = 0.0
    private val playedMicros: Long get() = (clockSeconds * 1_000_000.0).toLong()
    private val timelineFuture = timeline.asFuture { playedMicros }

    val future: VizFuture = object : VizFuture {
        override fun at(secondsAhead: Float): SpectrumFrame? =
            if (secondsAhead in 0f..0.1f) timelineFuture.at(secondsAhead) else null
        override val nextOnsetSeconds: Float
            get() = timelineFuture.nextOnsetSeconds.let { if (it in 0f..0.1f) it else -1f }
        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
            timelineFuture.nextEvent(kind)?.takeIf { it.secondsUntil in 0f..0.1f }
    }

    init {
        require(warmupSeconds.isFinite() && warmupSeconds >= 0f)
        val warmup = (warmupSeconds * sampleRate).toInt().coerceAtMost(source.size)
        if (warmup > 0) {
            feed(0, warmup)
            readIndex = warmup
            clockSeconds = warmup.toDouble() / sampleRate
        }
        fillAhead()
        events.sample(playedMicros)
    }

    /** Advance the audible clock once, then sample features and all delivered events there. */
    fun next(deltaSeconds: Float): SpectrumFrame {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0f)
        clockSeconds += deltaSeconds
        fillAhead()
        val at = playedMicros
        val delivery = events.sample(at)
        val frame = timeline.interpolated(at) ?: SpectrumFrame.silent(analyzer.bandCount, analyzer.scopePoints)
        return frame.withDeliveredEvents(delivery)
    }

    private fun fillAhead() {
        val through = minOf(source.size.toLong(), (clockSeconds * sampleRate).toLong() + sampleRate / 5).toInt()
        if (through <= readIndex) return
        feed(readIndex, through - readIndex)
        readIndex = through
    }

    private fun feed(from: Int, count: Int) {
        // PTS comes from source sample count, independently of display cadence and its rounding.
        val ptsMicros = from * 1_000_000L / sampleRate
        val stereo = side
        if (stereo == null) {
            analyzer.feed(source.copyOfRange(from, from + count), count, channels = 1, ptsMicros = ptsMicros)
            return
        }
        val interleaved = FloatArray(count * 2)
        for (index in 0 until count) {
            val middle = source[from + index]
            val spread = stereo.getOrElse(from + index) { 0f }
            interleaved[index * 2] = middle + spread
            interleaved[index * 2 + 1] = middle - spread
        }
        analyzer.feed(interleaved, count, channels = 2, ptsMicros = ptsMicros)
    }

    val latest: SpectrumFrame get() = analyzer.latest
}
