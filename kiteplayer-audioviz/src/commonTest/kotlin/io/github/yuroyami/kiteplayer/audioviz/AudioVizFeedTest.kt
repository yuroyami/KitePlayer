package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed between the player's tap and the picture: sound in, analyses out, each stamped with the
 * time its audio plays at, and nothing stale carried across a seek.
 */
class AudioVizFeedTest {
    private val worker = ManualVizDispatcher()
    private val opened = mutableListOf<AudioVizFeed>()

    private fun feed(queue: AudioPcmQueue = AudioPcmQueue()): AudioVizFeed =
        AudioVizFeed(worker, queue).also { opened += it }

    @AfterTest
    fun closeFeeds() {
        opened.forEach { it.close() }
        worker.runAll()
    }

    @Test
    fun `the tap does not run analysis before returning to playback`() {
        val feed = feed()
        val borrowed = FloatArray(8192) { 0.3f }
        feed.onAudio(Pts.Zero, borrowed, 4096, AudioFormat(44_100, 2, SampleFormat.F32))
        assertNull(feed.timeline.newest(), "analysis must run on its own worker")
        borrowed.fill(0f)
        worker.runAll()
        assertTrue(assertNotNull(feed.timeline.newest()).level > 0f, "the worker retained borrowed input")
        assertEquals(1L, feed.stats.copiedBlocks)
        assertTrue(feed.stats.publishedAnalyses > 0L)
    }

    @Test
    fun `a stalled worker drops visual blocks and recovers with a new local revision`() {
        val feed = feed(AudioPcmQueue(capacity = 2))
        hear(feed, 0L, 0.2f)
        val revision = assertNotNull(feed.timeline.newest()).analysisRevision
        val block = FloatArray(2048) { 0.3f }
        val format = AudioFormat(44_100, 2, SampleFormat.F32)
        repeat(3) { feed.onAudio(Pts(200_000L + it * 23_220L), block, 1024, format) }
        assertEquals(1L, feed.stats.droppedBlocks)
        assertEquals(1024L, feed.stats.droppedSampleFrames)
        assertNull(feed.timeline.newest(), "overflow must invalidate visuals immediately")
        worker.runAll()
        assertNull(feed.timeline.newest(), "queued pre-gap work must not repopulate the timeline")
        assertEquals(0L, feed.stats.queuedPcmNanos)
        hear(feed, 300_000L, 0.2f)
        val recovered = assertNotNull(feed.timeline.newest())
        assertEquals(Generation.Initial, recovered.generation)
        assertTrue(recovered.analysisRevision > revision)
    }

    @Test
    fun `closing cancels queued work and rejects later callbacks`() {
        val feed = feed()
        feed.onAudio(Pts.Zero, FloatArray(8192), 4096, AudioFormat(44_100, 2, SampleFormat.F32))
        feed.close()
        feed.close()
        worker.runAll()
        assertNull(feed.timeline.newest())
        assertEquals(0L, feed.stats.queuedPcmNanos)
        assertEquals(0L, feed.stats.publishedAnalyses)
        hear(feed, 0L, 0.5f)
        assertEquals(1L, feed.stats.copiedBlocks, "closed sessions cannot accept fresh PCM")
    }

    @Test
    fun `a reset retires queued PCM before new audio starts`() {
        val feed = feed()
        val format = AudioFormat(44_100, 2, SampleFormat.F32)
        val data = FloatArray(8192) { 0.3f }
        feed.onAudio(Generation.Initial, Pts.Zero, data, 4096, format)
        feed.onDiscontinuity(Generation(5))
        feed.onAudio(Generation(5), Pts(5_000_000L), data, 4096, format)
        worker.runAll()
        val frame = assertNotNull(feed.timeline.newest())
        assertEquals(Generation(5), frame.generation)
        assertTrue(frame.ptsMicros >= 5_000_000L)
        assertNull(feed.timeline.at(0L))
    }

    @Test
    fun `non finite and extreme samples are counted and cannot poison analysis`() {
        val feed = feed()
        val data = FloatArray(8192) { 0.3f }
        data[0] = Float.NaN
        data[1] = Float.POSITIVE_INFINITY
        data[2] = Float.NEGATIVE_INFINITY
        data[3] = Float.MAX_VALUE
        feed.onAudio(Pts.Zero, data, 4096, AudioFormat(44_100, 2, SampleFormat.F32))
        worker.runAll()
        val frame = assertNotNull(feed.timeline.newest())
        assertEquals(4L, feed.stats.sanitizedSamples)
        assertTrue(frame.level.isFinite() && frame.bands.all { it.isFinite() } && frame.chroma.all { it.isFinite() })
    }

    @Test
    fun `sound becomes analyses stamped with the time it plays at`() {
        val feed = feed()
        hear(feed, fromMicros = 5_000_000, seconds = 0.5f)
        val newest = assertNotNull(feed.timeline.newest(), "half a second of sound gave no analysis")
        // Half a second in, less the half window each analysis is centred on.
        assertTrue(newest.ptsMicros in 5_400_000..5_500_000, "the newest analysis is stamped ${newest.ptsMicros}")
        assertTrue(newest.level > 0f, "the analysis of a drum loop is silent")
        assertNotNull(feed.timeline.at(5_250_000), "a moment inside what was heard has no analysis")
    }

    @Test
    fun `a discontinuity drops what came before even when the new sound is close in time`() {
        val feed = feed()
        hear(feed, fromMicros = 0, seconds = 1f)
        feed.onDiscontinuity()
        // A seek ten milliseconds back: too small a step for the timestamps alone to give it away.
        hear(feed, fromMicros = 990_000, seconds = 0.3f)
        assertNull(feed.timeline.at(500_000), "an analysis from before the seek survived it")
        val newest = assertNotNull(feed.timeline.newest())
        assertTrue(newest.ptsMicros in 990_000..1_300_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }

    @Test
    fun `a jump in time starts the analysis again even with no discontinuity`() {
        val feed = feed()
        hear(feed, fromMicros = 0, seconds = 1f)
        // A loop back to the start, with no flush between the two passes.
        hear(feed, fromMicros = 0, seconds = 0.5f)
        val newest = assertNotNull(feed.timeline.newest())
        assertTrue(newest.ptsMicros in 400_000..500_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }

    @Test
    fun `a new sample rate gets an analysis that counts time at that rate`() {
        val feed = feed()
        hear(feed, fromMicros = 0, seconds = 0.5f, sampleRate = 44_100)
        hear(feed, fromMicros = 500_000, seconds = 1f, sampleRate = 48_000)
        val newest = assertNotNull(feed.timeline.newest())
        // Still counting at 44.1 kHz, the last analysis would be stamped near 1.56 s.
        assertTrue(newest.ptsMicros in 1_400_000..1_500_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }

    @Test
    fun `a changed channel mask retires analysis even with the same channel count`() {
        val feed = feed()
        val data = FloatArray(12_288) { 0.2f }
        val back = AudioFormat(48_000, 6, SampleFormat.F32, channelLayoutMask = 0x3fL)
        feed.onAudio(Pts.Zero, data, 2048, back)
        worker.runAll()
        val before = assertNotNull(feed.timeline.newest()).analysisRevision
        feed.onAudio(Pts(42_666L), data, 2048, back.copy(channelLayoutMask = 0x60fL))
        worker.runAll()
        assertTrue(assertNotNull(feed.timeline.newest()).analysisRevision > before)
        assertNull(feed.timeline.at(20_000L))
    }

    @Test
    fun `millisecond timestamp quantisation does not repeatedly restart analysis`() {
        val feed = feed()
        val format = AudioFormat(44_100, 2, SampleFormat.F32)
        val data = FloatArray(2048) { 0.2f }
        var firstRevision: Long? = null
        repeat(30) { block ->
            val roundedPts = block * 1024L * 1000L / 44_100L * 1000L
            feed.onAudio(Pts(roundedPts), data, 1024, format)
            worker.runAll()
            feed.timeline.newest()?.let {
                if (firstRevision == null) firstRevision = it.analysisRevision
                assertEquals(firstRevision, it.analysisRevision)
            }
        }
        assertNotNull(firstRevision)
        assertTrue(feed.stats.publishedAnalyses >= 50)
    }

    private fun hear(feed: AudioVizFeed, fromMicros: Long, seconds: Float, sampleRate: Int = 44_100) {
        feed.hear(fromMicros, seconds, sampleRate, afterBlock = worker::runAll)
    }

    @Test
    fun `negative source timestamps stay valid and stale blocks are rejected`() {
        val feed = feed()
        hear(feed, fromMicros = -1_000_000, seconds = 0.5f)
        val negative = assertNotNull(feed.timeline.newest())
        assertTrue(negative.hasTimestamp)
        assertTrue(negative.ptsMicros in -600_000L..-500_000L)
        feed.onDiscontinuity(Generation(4))
        assertNull(feed.timeline.newest(), "reset must be visible before the next block arrives")
        feed.onAudio(Generation(3), Pts.Zero, FloatArray(8192), 4096, AudioFormat(44_100, 2, SampleFormat.F32))
        assertNull(feed.timeline.newest(), "a retired callback must not repopulate the history")
        hear(feed, 0L, 0.3f)
        assertEquals(Generation(4), assertNotNull(feed.timeline.newest()).generation)
    }
}

/**
 * Hands [seconds] of the drum loop to the tap the way the player does: stereo blocks of 1024
 * frames in one reused array, each stamped with the time it plays at.
 */
internal fun AudioTap.hear(fromMicros: Long, seconds: Float, sampleRate: Int = 44_100, afterBlock: () -> Unit = {}) {
    val mono = SyntheticSong.drumLoop(seconds, sampleRate = sampleRate)
    val format = AudioFormat(sampleRate, 2, SampleFormat.F32)
    val block = FloatArray(BLOCK_FRAMES * 2)
    var start = 0
    while (start < mono.size) {
        val frames = minOf(BLOCK_FRAMES, mono.size - start)
        for (i in 0 until frames) {
            block[2 * i] = mono[start + i]
            block[2 * i + 1] = mono[start + i]
        }
        onAudio(Pts(fromMicros + start * 1_000_000L / sampleRate), block, frames, format)
        afterBlock()
        start += frames
    }
}

private const val BLOCK_FRAMES = 1024
