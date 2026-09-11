package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The readings a drawing uses to tell calm music from lively music.
 *
 * A ballad and a drum track must not look the same. They would if every number the analyser
 * published were measured against silence and full scale: a song only ever uses a slice of that
 * range, and where the slice sits depends on how the track was mastered rather than on how it
 * feels.
 */
class AnalysisFeaturesTest {

    private val rate = 48_000

    private class Run(val frames: List<SpectrumFrame>) {
        fun mean(pick: (SpectrumFrame) -> Float): Float =
            if (frames.isEmpty()) 0f else frames.sumOf { pick(it).toDouble() }.toFloat() / frames.size

        fun max(pick: (SpectrumFrame) -> Float): Float = frames.maxOf { pick(it) }

        fun min(pick: (SpectrumFrame) -> Float): Float = frames.minOf { pick(it) }

        val last: SpectrumFrame get() = frames.last()
    }

    /** Feeds a mono buffer and keeps every analysis, skipping the first second while it settles. */
    private fun play(mono: FloatArray, channels: Int = 1, interleaved: FloatArray? = null): Run {
        val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = 48)
        val kept = ArrayList<SpectrumFrame>()
        var seen = 0
        val settle = rate / 512
        analyzer.onAnalysis = {
            if (seen++ > settle) kept += it
        }
        val source = interleaved ?: mono
        val frames = if (channels == 1) source.size else source.size / channels
        analyzer.feed(source, frames, channels)
        return Run(kept)
    }

    @Test
    fun aGentleSwellIsNotADropButReturningDrumsAre() {
        val pad = play(SyntheticSong.calmPad(24f, rate))
        assertTrue(pad.frames.none { it.drop }, "a smooth chord should not trigger drop effects")

        val track = SyntheticSong.drumLoop(6f, sampleRate = rate) +
            SyntheticSong.calmPad(6f, rate) + SyntheticSong.drumLoop(6f, sampleRate = rate)
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val drops = ArrayList<Long>()
        analyzer.onAnalysis = { if (it.drop) drops += it.ptsMicros }
        analyzer.feed(track, track.size, 1, 0L)
        assertTrue(drops.any { it in 12_000_000L..14_000_000L }, "returning drums should trigger a drop: $drops")
    }

    @Test
    fun theMoodSignalTellsCalmFromLively() {
        val calm = play(SyntheticSong.calmPad(10f, rate))
        val lively = play(SyntheticSong.drumLoop(10f, sampleRate = rate))

        println(
            "calm:   mood ${calm.mean { it.mood }} density ${calm.mean { it.density }} " +
                "drive ${calm.mean { 0.45f * it.energy + 0.55f * it.mood }} level ${calm.mean { it.level }}",
        )
        println(
            "lively: mood ${lively.mean { it.mood }} density ${lively.mean { it.density }} " +
                "drive ${lively.mean { 0.45f * it.energy + 0.55f * it.mood }} level ${lively.mean { it.level }}",
        )

        assertTrue(calm.mean { it.mood } < 0.4f, "a pad should read calm, mood was ${calm.mean { it.mood }}")
        assertTrue(lively.mean { it.mood } > 0.55f, "a drum loop should read lively, mood was ${lively.mean { it.mood }}")
        assertTrue(
            lively.mean { it.mood } > calm.mean { it.mood } * 1.8f,
            "the two moods are too close together to draw differently",
        )
        assertTrue(calm.mean { it.density } < 0.2f, "a pad has no onsets, density was ${calm.mean { it.density }}")
        assertTrue(lively.mean { it.density } > 0.5f, "a drum loop is busy, density was ${lively.mean { it.density }}")
    }

    @Test
    fun onceSettledThePadIsCalmAndTheLoopIsLively() {
        // Mood moves over bars on purpose, so it is judged once it has had time to arrive: the last
        // six seconds of a fourteen second song.
        fun settled(run: Run): Run = Run(run.frames.takeLast(run.frames.size * 6 / 13))
        val calm = settled(play(SyntheticSong.calmPad(14f, rate)))
        val lively = settled(play(SyntheticSong.drumLoop(14f, sampleRate = rate)))
        println("settled mood: pad ${calm.mean { it.mood }}, loop ${lively.mean { it.mood }}")
        assertTrue(calm.mean { it.mood } < 0.3f, "a pad should settle calm, mood was ${calm.mean { it.mood }}")
        assertTrue(lively.mean { it.mood } > 0.7f, "a drum loop should settle lively, mood was ${lively.mean { it.mood }}")
    }

    @Test
    fun aQuietSongGetsTheSameBarsAsALoudOne() {
        // This is the measurement the mood tracking exists for. On the absolute scale the
        // bars of a quiet song are a fraction of the height of a loud one's, whatever is actually
        // being played, because the scale runs from silence to full scale and a track only ever
        // uses a slice of it. Against the song's own range they are comparable, so a drawing shows
        // the shape of the music instead of the level it was mastered at.
        val calm = play(SyntheticSong.calmPad(12f, rate))
        val lively = play(SyntheticSong.drumLoop(12f, sampleRate = rate))

        fun meanBand(run: Run, pick: (SpectrumFrame) -> FloatArray): Float =
            run.frames.sumOf { frame -> pick(frame).average() }.toFloat() / run.frames.size

        val absoluteGap = meanBand(lively) { it.bands } / meanBand(calm) { it.bands }
        val relativeGap = meanBand(lively) { it.bandsRel } / meanBand(calm) { it.bandsRel }
        println("bar height, loud against quiet: absolute ${absoluteGap}x, song relative ${relativeGap}x")

        assertTrue(absoluteGap > 2f, "the absolute scale should show a big gap, showed ${absoluteGap}x")
        assertTrue(
            relativeGap < absoluteGap * 0.6f,
            "the song relative bars should close most of that gap, went from ${absoluteGap}x to ${relativeGap}x",
        )
        // And the absolute reading is still there for anything that wants to be a meter.
        assertTrue(calm.max { it.level } < 0.7f, "the absolute level of a quiet song should stay low")
    }

    @Test
    fun aSongWithDynamicsFillsItsRange() {
        val lively = play(SyntheticSong.drumLoop(12f, sampleRate = rate))
        val span = lively.max { it.energy } - lively.min { it.energy }
        println("lively energy: min ${lively.min { it.energy }} max ${lively.max { it.energy }} span $span")
        assertTrue(lively.max { it.energy } > 0.75f, "the loud moments should reach the top of the range")
        assertTrue(span > 0.5f, "the song should use most of its own range, used $span of it")
    }

    @Test
    fun silenceReadsAsSilence() {
        val quiet = play(SyntheticSong.silence(6f, rate))
        assertTrue(quiet.max { it.energy } <= 0.001f, "silence must not be stretched into a light show")
        assertTrue(quiet.max { it.mood } < 0.1f, "silence is not lively")
    }

    @Test
    fun findsTheTempoOfADrumLoop() {
        val run = play(SyntheticSong.drumLoop(14f, beatsPerMinute = 130f, sampleRate = rate))
        val settled = Run(run.frames.takeLast(run.frames.size / 3))
        val bpm = settled.mean { it.bpm }
        val confidence = settled.mean { it.beatConfidence }
        println("tempo: $bpm bpm, confidence $confidence")
        assertTrue(abs(bpm - 130f) < 4f || abs(bpm - 65f) < 3f, "expected about 130 bpm, found $bpm")
        assertTrue(confidence > 0.35f, "a plain drum loop should be tracked confidently, was $confidence")
        assertTrue(settled.max { it.barPhase } > 0.8f, "the bar position should sweep its whole range")
    }

    @Test
    fun musicWithoutABeatIsNotGivenOne() {
        val run = play(SyntheticSong.calmPad(12f, rate))
        val confidence = run.mean { it.beatConfidence }
        println("pad tempo confidence: $confidence")
        assertTrue(confidence < 0.4f, "a pad has no beat to find, confidence was $confidence")
    }

    @Test
    fun tellsAKickFromAHat() {
        val run = play(SyntheticSong.drumLoop(10f, sampleRate = rate))
        val kicks = run.frames.count { it.kick > 0f }
        val hats = run.frames.count { it.hat > 0f }
        val snares = run.frames.count { it.snare > 0f }
        println("onsets over 10s: kick $kicks snare $snares hat $hats")
        val beats = 10f * 130f / 60f
        assertTrue(kicks > beats * 0.6f, "a 130 bpm loop has a kick on most beats, found $kicks over $beats beats")
        assertTrue(kicks < beats * 2f, "and not three of them per beat, found $kicks over $beats beats")
        assertTrue(hats > 8, "hats land between the kicks, found $hats")
        assertTrue(snares > 4, "snares land on two and four, found $snares")
    }

    @Test
    fun findsTheNotesOfAChord() {
        val seconds = 6f
        val total = (rate * seconds).toInt()
        // C, E and G: a C major chord.
        val notes = floatArrayOf(261.63f, 329.63f, 392.00f)
        val chord = FloatArray(total) { index ->
            val time = index.toFloat() / rate
            var sum = 0f
            for (note in notes) sum += sin(2f * PI.toFloat() * note * time)
            sum / notes.size * 0.3f
        }
        val run = play(chord)
        val chroma = run.last.chroma
        val loudest = chroma.indices.sortedByDescending { chroma[it] }.take(3).sorted()
        println("chroma peaks at $loudest, key confidence ${run.last.keyConfidence}")
        // C is 0, E is 4, G is 7.
        assertTrue(loudest == listOf(0, 4, 7), "a C major chord should light C, E and G, lit $loudest")
    }

    @Test
    fun measuresHowWideTheStereoIs() {
        val seconds = 4f
        val total = (rate * seconds).toInt()
        val tone = FloatArray(total) { sin(2f * PI.toFloat() * 220f * it / rate) * 0.4f }

        val same = FloatArray(total * 2)
        val opposite = FloatArray(total * 2)
        for (index in 0 until total) {
            same[index * 2] = tone[index]
            same[index * 2 + 1] = tone[index]
            opposite[index * 2] = tone[index]
            opposite[index * 2 + 1] = -tone[index]
        }

        val mono = play(tone, channels = 2, interleaved = same)
        val wide = play(tone, channels = 2, interleaved = opposite)
        println("width: same channels ${mono.last.width}, opposite channels ${wide.last.width}")
        assertTrue(mono.last.width < 0.1f, "two identical channels are not wide, read ${mono.last.width}")
        assertTrue(wide.last.width > 0.9f, "two opposite channels are as wide as it gets, read ${wide.last.width}")
    }

    @Test
    fun theTwoChannelsArriveSeparately() {
        val total = rate / 2
        val interleaved = FloatArray(total * 2)
        for (index in 0 until total) {
            val tone = sin(2f * PI.toFloat() * 220f * index / rate) * 0.4f
            interleaved[index * 2] = tone
            interleaved[index * 2 + 1] = -tone
        }
        val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = 16)
        analyzer.feed(interleaved, total, channels = 2, ptsMicros = 0L)
        val frame = analyzer.latest
        assertTrue(frame.scopeLeft.size == analyzer.stereoPoints, "the left trace should be ${analyzer.stereoPoints} points")
        var cancelled = 0f
        var carried = 0f
        for (index in frame.scopeLeft.indices) {
            cancelled += abs(frame.scopeLeft[index] + frame.scopeRight[index])
            carried += abs(frame.scopeLeft[index])
        }
        assertTrue(carried > 1f, "the left channel should carry the tone, carried $carried")
        assertTrue(cancelled < carried * 0.01f, "opposite channels should cancel sample for sample")
    }

    @Test
    fun pinkNoiseIsNotGivenABeat() {
        // Noise with the spectrum of real music and no rhythm at all. A tracker that finds a tempo in
        // it would draw a pulse that is not in the song.
        val total = rate * 12
        val noise = FloatArray(total)
        var seed = 2_024L
        var slow = 0f
        var middle = 0f
        var fast = 0f
        for (index in 0 until total) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = (seed shr 40).toFloat() / 8_388_608f
            // Paul Kellet's economy filter, which tilts white noise towards pink.
            slow = 0.99765f * slow + white * 0.0990460f
            middle = 0.96300f * middle + white * 0.2965164f
            fast = 0.57000f * fast + white * 1.0526913f
            noise[index] = (slow + middle + fast + white * 0.1848f) * 0.05f
        }
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        var surest = 0f
        var seen = 0
        analyzer.onAnalysis = { frame ->
            seen++
            // After four seconds, once the tracker has had time to settle on something.
            if (seen > rate / 512 * 4) surest = maxOf(surest, frame.beatConfidence)
        }
        analyzer.feed(noise, total, channels = 1, ptsMicros = 0L)
        println("pink noise: the most sure of a tempo it got was $surest")
        assertTrue(surest < 0.3f, "pink noise has no beat, but confidence reached $surest")
    }

    @Test
    fun aClickTrackIsFoundWithinFourSeconds() {
        val bpm = 128f
        val beat = 60f / bpm
        val total = rate * 12
        val track = FloatArray(total)
        for (index in 0 until total) {
            val into = (index.toFloat() / rate) % beat
            track[index] = sin(2f * PI.toFloat() * 60f * into) * kotlin.math.exp(-into * 30f) * 0.7f +
                sin(2f * PI.toFloat() * 2_000f * into) * kotlin.math.exp(-into * 400f) * 0.3f
        }
        // Every field describes the timestamp on the frame, including the tempo phase.
        var foundAt = -1f
        val errors = ArrayList<Float>()
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        analyzer.onAnalysis = { frame ->
            val at = frame.ptsMicros / 1_000_000f
            if (foundAt < 0f && frame.beatConfidence > 0.4f && abs(frame.bpm - bpm) < 1f) foundAt = at
            if (at > beat * 8f && at < 11.5f) {
                var off = frame.beatPhase - (at / beat) % 1f
                off -= kotlin.math.round(off)
                errors += abs(off) * beat
            }
        }
        analyzer.feed(track, total, channels = 1, ptsMicros = 0L)
        errors.sort()
        val typical = errors[errors.size / 2]
        println("click track: ${analyzer.latest.bpm} bpm, found at $foundAt s, typical beat position error ${typical * 1000} ms")
        assertTrue(abs(analyzer.latest.bpm - bpm) < 1f, "expected $bpm bpm, found ${analyzer.latest.bpm}")
        assertTrue(foundAt in 0f..4f, "the tempo should be found inside four seconds, it took until $foundAt s")
        assertTrue(typical < 0.02f, "the beat position should be within 20 ms, it was ${typical * 1000} ms out")
    }

    @Test
    fun theQueueCanBeReadAhead() {
        val timeline = SpectrumTimeline(capacity = 64)
        val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = 16)
        analyzer.onAnalysis = timeline::push
        analyzer.feed(SyntheticSong.drumLoop(1f, sampleRate = rate), rate, channels = 1, ptsMicros = 0L)

        val now = 200_000L
        val soon = timeline.ahead(now, 0.3f)
        assertTrue(soon != null, "the queue should reach 300 ms past the play position")
        assertTrue(
            soon.ptsMicros > now,
            "a look ahead should return audio that has not been heard yet, got ${soon.ptsMicros} against $now",
        )
        val onset = timeline.nextOnsetSeconds(now)
        println("next queued onset in $onset s")
        assertTrue(onset != 0f, "either an onset is queued or there is none, never exactly zero")
    }
}
