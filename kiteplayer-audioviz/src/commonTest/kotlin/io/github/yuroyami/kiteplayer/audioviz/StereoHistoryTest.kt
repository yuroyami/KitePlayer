package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.StereoHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The analyser's stereo sample history: what a drawing that analyses raw samples reads. */
class StereoHistoryTest {

    private val rate = 48_000

    /** Left counts up from 1 and right counts down from -1, so every sample says where it came from. */
    private fun stereo(from: Int, frames: Int): FloatArray = FloatArray(frames * 2) { index ->
        val sample = from + index / 2 + 1
        if (index % 2 == 0) sample / 1e6f else -sample / 1e6f
    }

    private fun feed(analyzer: SpectrumAnalyzer, seconds: Float, block: Int = 480) {
        val frames = (seconds * rate).toInt()
        var done = 0
        while (done < frames) {
            val count = minOf(block, frames - done)
            analyzer.feed(stereo(done, count), count, channels = 2, ptsMicros = done * 1_000_000L / rate)
            done += count
        }
    }

    @Test
    fun aFramesTimeReadsTheSamplesThatEndThere() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val history = analyzer.stereoHistory
        // The first read asks for the storage; the next write creates it.
        assertNull(history.indexAt(analyzer.analysisRevision, 0L))
        feed(analyzer, 1f)
        val frame = analyzer.latest
        assertSame(history, frame.stereoHistory)
        // The analyser dates a frame at the middle of its window, which ends at the newest sample.
        val index = assertNotNull(history.indexAt(frame.analysisRevision, frame.ptsMicros))
        assertEquals(rate - analyzer.fftSize / 2L, index)
        val left = FloatArray(100)
        val right = FloatArray(100)
        assertEquals(100, history.read(frame.analysisRevision, index - 100, left, right))
        for (offset in 0 until 100) {
            val sample = index - 100 + offset + 1
            assertEquals(sample / 1e6f, left[offset], "left at $offset")
            assertEquals(-sample / 1e6f, right[offset], "right at $offset")
        }
    }

    @Test
    fun whatIsNotHeldReadsZero() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val history = analyzer.stereoHistory
        history.indexAt(analyzer.analysisRevision, 0L)
        feed(analyzer, 4f)
        val revision = analyzer.latest.analysisRevision
        val written = 4L * rate
        val left = FloatArray(200)
        val right = FloatArray(200)
        // Straddling the far end of the ring: the part older than the capacity is gone.
        val from = written - history.capacity - 100
        assertEquals(100, history.read(revision, from, left, right))
        assertEquals(0f, left[99])
        assertEquals((from + 100 + 1) / 1e6f, left[100])
        // Straddling the write head: the part not yet written reads zero.
        assertEquals(50, history.read(revision, written - 50, left, right, 0, 100))
        assertEquals(written / 1e6f, left[49])
        assertEquals(0f, left[50])
    }

    @Test
    fun anotherRevisionReadsNothing() {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val history = analyzer.stereoHistory
        history.indexAt(analyzer.analysisRevision, 0L)
        feed(analyzer, 1f)
        val before = analyzer.latest
        analyzer.reset()
        feed(analyzer, 0.5f)
        assertTrue(before.analysisRevision != analyzer.latest.analysisRevision)
        assertNull(history.indexAt(before.analysisRevision, before.ptsMicros))
        val left = FloatArray(10) { 1f }
        val right = FloatArray(10) { 1f }
        assertEquals(0, history.read(before.analysisRevision, 1_000L, left, right))
        assertTrue(left.all { it == 0f } && right.all { it == 0f })
        // The reset started counting again from the first sample after it.
        val now = analyzer.latest
        assertEquals(rate / 2L - analyzer.fftSize / 2L, history.indexAt(now.analysisRevision, now.ptsMicros))
    }

    @Test
    fun aMonoSourceFillsBothSidesAndFivePointOneKeepsItsCentre() {
        val mono = StereoHistory(rate)
        mono.indexAt(7L, 0L)
        mono.write(floatArrayOf(0.25f, -0.5f), 2, 1, 0L, 7L)
        val left = FloatArray(2)
        val right = FloatArray(2)
        assertEquals(2, mono.read(7L, 0L, left, right))
        assertEquals(listOf(0.25f, -0.5f), left.toList())
        assertEquals(listOf(0.25f, -0.5f), right.toList())
        assertEquals(1, mono.sourceChannels(7L))

        // Front left, front right, centre, low frequency, surround left, surround right.
        val surround = StereoHistory(rate)
        surround.indexAt(1L, 0L)
        surround.write(floatArrayOf(0.1f, 0.2f, 0.4f, 0.9f, 0.3f, -0.3f), 1, 6, 0L, 1L)
        assertEquals(1, surround.read(1L, 0L, left, right, 0, 1))
        assertEquals(0.1f + 0.70710677f * (0.4f + 0.3f), left[0], 1e-6f)
        assertEquals(0.2f + 0.70710677f * (0.4f - 0.3f), right[0], 1e-6f)

        // A sample that is not a number reads silence, and a wild one is held to 16, as the analyser holds it.
        val wild = StereoHistory(rate)
        wild.indexAt(1L, 0L)
        wild.write(floatArrayOf(Float.NaN, 40f), 1, 2, 0L, 1L)
        wild.read(1L, 0L, left, right, 0, 1)
        assertEquals(0f, left[0])
        assertEquals(16f, right[0])
    }

    @Test
    fun theStorageIsLetGoWhenNobodyReads() {
        val history = StereoHistory(rate)
        val left = FloatArray(10)
        val right = FloatArray(10)
        // Nobody has asked yet, so nothing is kept.
        history.write(FloatArray(20) { 0.5f }, 10, 2, 0L, 1L)
        assertEquals(0, history.read(1L, 0L, left, right))
        // That read asked, so the next write keeps what it writes.
        history.write(FloatArray(20) { 0.5f }, 10, 2, 0L, 1L)
        assertEquals(10, history.read(1L, 10L, left, right))
        // The write after that read still counts as asked for; thirty seconds more without one lets it go.
        val second = FloatArray(rate * 2)
        repeat(StereoHistory.IDLE_SECONDS.toInt() + 2) { history.write(second, rate, 2, 0L, 1L) }
        val end = 20L + (StereoHistory.IDLE_SECONDS + 2) * rate
        assertEquals(0, history.read(1L, end - 10, left, right))
    }

    @Test
    fun aPlayersFrameCarriesTheHistoryThroughTheTimeline() {
        val song = SyntheticSong.drumLoop(4f)
        val player = SongPlayer(song, side = SyntheticSong.drumLoopSide(4f))
        val frame = player.next(1f / 60f)
        val history = assertNotNull(frame.stereoHistory)
        history.indexAt(frame.analysisRevision, frame.ptsMicros)
        repeat(30) { player.next(1f / 60f) }
        val later = player.next(1f / 60f)
        val index = assertNotNull(later.stereoHistory?.indexAt(later.analysisRevision, later.ptsMicros))
        val left = FloatArray(256)
        val right = FloatArray(256)
        assertEquals(256, history.read(later.analysisRevision, index - 256, left, right))
        assertEquals(2, history.sourceChannels(later.analysisRevision))
        // The player feeds the loop plus its side to the left and minus it to the right.
        val side = SyntheticSong.drumLoopSide(4f)
        for (offset in 0 until 256) {
            val at = (index - 256 + offset).toInt()
            assertEquals(song[at] + side[at], left[offset], 1e-6f)
            assertEquals(song[at] - side[at], right[offset], 1e-6f)
        }
    }
}
