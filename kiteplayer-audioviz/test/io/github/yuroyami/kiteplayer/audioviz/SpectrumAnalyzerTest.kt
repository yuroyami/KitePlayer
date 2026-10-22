package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.sin
import io.github.yuroyami.kiteplayer.Generation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {

    private val rate = 48_000

    @Test
    fun silenceLeavesEveryBarDown() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        analyzer.feed(FloatArray(8192), frames = 4096, channels = 2)

        assertTrue(analyzer.latest.bands.all { it < 0.02f }, "silence must not light a bar")
        assertTrue(analyzer.latest.level < 0.02f)
    }

    @Test
    fun aHigherToneLightsAHigherBar() {
        val low = loudestBandOf(1_000.0)
        val high = loudestBandOf(6_000.0)

        assertTrue(low < high, "6 kHz landed at bar $high, 1 kHz at bar $low: the mapping is backwards")
    }

    @Test
    fun aToneRaisesTheLevelAndTheBarsAboveSilence() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        feedTone(analyzer, 1_000.0, seconds = 0.4)

        assertTrue(analyzer.latest.level > 0.4f, "a full-scale tone should read loud, was ${analyzer.latest.level}")
        assertTrue(analyzer.latest.bands.max() > 0.5f)
    }

    @Test
    fun resetPutsEverythingBack() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        feedTone(analyzer, 1_000.0, seconds = 0.3)
        analyzer.reset()

        assertTrue(analyzer.latest.bands.all { it == 0f })
        assertTrue(analyzer.latest.peaks.all { it == 0f })
        assertEquals(-1L, analyzer.latest.ptsMicros)
    }

    @Test
    fun anAnalyserThatWasResetAnalysesLikeANewOne() {
        class Run(val frames: List<SpectrumFrame>, val structure: List<Pair<AudioEventKind, Long>>)

        fun run(analyzer: SpectrumAnalyzer, song: FloatArray): Run {
            val frames = ArrayList<SpectrumFrame>()
            val structure = ArrayList<Pair<AudioEventKind, Long>>()
            analyzer.onAnalysis = { frame ->
                frames += frame
                frame.structure?.let { batch -> for (index in 0 until batch.size) structure += batch[index].kind to batch[index].ptsMicros }
            }
            var start = 0
            while (start < song.size) {
                val count = minOf(1024, song.size - start)
                analyzer.feed(song.copyOfRange(start, start + count), count, channels = 1, ptsMicros = start * 1_000_000L / rate)
                start += count
            }
            return Run(frames, structure)
        }
        // A quiet pad into loud drums holds a drop, which the section detector must find again after a reset.
        val song = SyntheticSong.calmPad(20f) + SyntheticSong.drumLoop(20f)
        val fresh = run(SpectrumAnalyzer(sampleRate = rate), song)
        val used = SpectrumAnalyzer(sampleRate = rate)
        // Other music first, long enough to leave a tempo, a mood, a key, sections and a loudness range behind.
        run(used, SyntheticSong.drumLoop(20f) + SyntheticSong.calmPad(20f))
        used.reset()
        val again = run(used, song)
        assertTrue(fresh.structure.isNotEmpty(), "the fixture must hold a structural decision")
        assertEquals(fresh.structure, again.structure, "the structural decisions differ after a reset")
        assertTrue(fresh.frames.size > 1000 && fresh.frames.size == again.frames.size,
            "${fresh.frames.size} frames from a new analyser and ${again.frames.size} after a reset")
        for (index in fresh.frames.indices) {
            val a = fresh.frames[index]
            val b = again.frames[index]
            fun same(name: String, x: Float, y: Float) = assertEquals(x, y, 1e-5f, "$name of frame $index differs after a reset")
            assertEquals(a.ptsMicros, b.ptsMicros, "frame $index")
            same("level", a.level, b.level)
            same("bass", a.bass, b.bass)
            same("energy", a.energy, b.energy)
            same("mood", a.mood, b.mood)
            same("density", a.density, b.density)
            same("novelty", a.novelty, b.novelty)
            same("bpm", a.bpm, b.bpm)
            same("beatConfidence", a.beatConfidence, b.beatConfidence)
            same("beatPhase", a.beatPhase, b.beatPhase)
            same("keyConfidence", a.keyConfidence, b.keyConfidence)
            same("loudShort", a.loudShort, b.loudShort)
            same("loudLong", a.loudLong, b.loudLong)
            same("dropPulse", a.dropPulse, b.dropPulse)
            same("kickPulse", a.kickPulse, b.kickPulse)
            same("snarePulse", a.snarePulse, b.snarePulse)
            same("width", a.width, b.width)
            same("centroid", a.centroid, b.centroid)
            same("flatness", a.flatness, b.flatness)
            assertTrue(a.bands.contentEquals(b.bands), "bands of frame $index differ after a reset")
        }
    }

    @Test
    fun resettingTheAnalyzerAndTimelineKeepsFreshAnalysisPublishable() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val timeline = SpectrumTimeline()
        analyzer.onAnalysis = timeline::push
        feedTone(analyzer, 1_000.0, seconds = 0.1, ptsMicros = 0L)
        val retired = assertNotNull(timeline.newest())
        timeline.clear()
        analyzer.reset()
        feedTone(analyzer, 1_000.0, seconds = 0.1, ptsMicros = 1_000_000L)
        val fresh = assertNotNull(timeline.newest(), "an authoring reset must advance its continuity identity")
        assertEquals(timeline.revision, fresh.analysisRevision)
        assertTrue(fresh.analysisRevision > retired.analysisRevision)

        timeline.reset(Generation(7))
        analyzer.reset(timeline.generation, timeline.revision)
        feedTone(analyzer, 1_000.0, seconds = 0.1, ptsMicros = -1_000_000L)
        val changed = assertNotNull(timeline.newest())
        assertEquals(Generation(7), changed.generation)
        assertEquals(timeline.revision, changed.analysisRevision)
    }

    @Test
    fun timestampsFollowTheAudioAndTrailItByHalfAWindow() {
        val analyzer = SpectrumAnalyzer(fftSize = 1024, sampleRate = rate)
        feedTone(analyzer, 1_000.0, seconds = 0.5, ptsMicros = 10_000_000L)

        val pts = analyzer.latest.ptsMicros
        assertTrue(pts >= 10_000_000L, "a window cannot start before the audio did, was $pts")
        // The timestamp describes the centre of the Hann window, before the newest sample.
        assertTrue(pts <= 10_500_000L, "the window should trail the newest sample, was $pts")
    }

    @Test
    fun theTimelineHandsBackWhatWasAudible() {
        val timeline = SpectrumTimeline(capacity = 8)
        assertNull(timeline.newest())

        val frames = (0 until 5).map { frameAt(it * 100_000L) }
        frames.forEach(timeline::push)

        assertEquals(frames.last().ptsMicros, timeline.newest()?.ptsMicros)
        assertEquals(200_000L, timeline.at(250_000L)?.ptsMicros, "should pick the newest one already heard")
        assertNull(timeline.at(9_000_000L), "a starved timeline must expire its last analysis")
        assertNull(timeline.at(-1L), "nothing has been heard yet")
        assertNotNull(timeline.at(0L))
    }

    private fun frameAt(ptsMicros: Long) = SpectrumFrame(
        ptsMicros = ptsMicros,
        bands = FloatArray(4),
        peaks = FloatArray(4),
        scope = FloatArray(4),
        level = 0f,
        bass = 0f,
        mid = 0f,
        treble = 0f,
        beat = 0f,
        pulse = 0f,
    )

    @Test
    fun aSteadyToneHoldsItsTraceStill() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        feedTone(analyzer, 440.0, seconds = 0.5)
        val first = analyzer.latest.scope.copyOf()
        feedTone(analyzer, 440.0, seconds = 0.137)
        val second = analyzer.latest.scope
        var drift = 0f
        for (index in first.indices) drift += kotlin.math.abs(first[index] - second[index])
        drift /= first.size
        println("a 440 Hz trace moved by $drift on average between two moments")
        assertTrue(drift < 0.05f, "a steady tone should draw the same trace every time, it moved by $drift")
    }

    private fun loudestBandOf(hz: Double): Int {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        feedTone(analyzer, hz, seconds = 0.4)
        val bands = analyzer.latest.bands
        return bands.indices.maxByOrNull { bands[it] } ?: -1
    }

    private fun feedTone(
        analyzer: SpectrumAnalyzer,
        hz: Double,
        seconds: Double,
        ptsMicros: Long = -1L,
    ) {
        val frames = (rate * seconds).toInt()
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = sin(2.0 * PI * hz * frame / rate).toFloat()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        analyzer.feed(samples, frames, channels = 2, ptsMicros = ptsMicros)
    }
}
