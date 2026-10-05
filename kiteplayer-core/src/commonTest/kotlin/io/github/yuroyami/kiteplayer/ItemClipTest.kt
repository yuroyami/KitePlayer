@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.inClip
import io.github.yuroyami.kiteplayer.internal.scanMediaAudio
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An item with a clip plays only that part of its file, and counts its position, its length, its
 * chapters and its seeks from the clip's start (#456). Every timestamp the player hands on, the
 * audio tap's and the pictures', stays in the file's own time.
 */
class ItemClipTest {

    private val sixSeconds = MediaScript(durationUs = 6_000_000)

    private class Block(val ptsUs: Long, val frames: Int, val sampleRate: Int) {
        val endUs: Long get() = ptsUs + frames * 1_000_000L / sampleRate
    }

    /** Every block after the last cut in [blocks], and every block in [all]. */
    private class RecordingTap : AudioTap {
        val blocks = mutableListOf<Block>()
        val all = mutableListOf<Block>()

        override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
            blocks += Block(pts.micros, frames, format.sampleRate)
            all += Block(pts.micros, frames, format.sampleRate)
        }

        override fun onDiscontinuity() {
            blocks.clear()
        }
    }

    /** One sample frame at 48 kHz, which is as close as a cut to the sample can land. */
    private val frameUs = 21L

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    private fun clipped(start: Duration, end: Duration?, uri: String = "scripted://media", startPosition: Duration? = null) =
        MediaItem(uri, startPosition = startPosition, clip = MediaClip(start, end))

    private fun assertNear(expectedUs: Long, actualUs: Long, toleranceUs: Long, message: String) {
        assertTrue(abs(expectedUs - actualUs) <= toleranceUs, "$message: expected $expectedUs, was $actualUs")
    }

    @Test
    fun aClippedItemPlaysOnlyItsPartAndCountsFromItsStart() = runTest {
        val harness = CoreHarness(this, script = sixSeconds)
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.attachRenderer()
        harness.core.open(clipped(2_100.milliseconds, 4_300.milliseconds))

        // Paused, the position is the picture's, as after any precise seek: the first one at or
        // after the clip's start.
        assertTrue(harness.core.position() < 40.milliseconds, "the item starts at zero: ${harness.core.position()}")
        assertEquals(2_200.milliseconds, harness.core.snapshots.value.duration, "the item is as long as its clip")
        harness.core.play()
        assertTrue(harness.runUntil(6.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the item ends")

        val first = assertNotNull(tap.blocks.firstOrNull(), "nothing was heard")
        assertNear(2_100_000, first.ptsUs, frameUs, "the first sample heard is the clip's first")
        assertTrue(tap.blocks.all { it.ptsUs >= 2_100_000 - frameUs && it.endUs <= 4_300_000 + frameUs }, "a sample outside the clip was heard")
        assertNear(4_300_000, tap.blocks.last().endUs, 21_334, "the last sample heard is the clip's last")
        val frames = tap.blocks.sumOf { it.frames.toLong() }
        assertTrue(abs(frames - 2_200 * 48L) <= 2, "the clip's 2.2 s of sound was heard whole: $frames frames")

        val pictures = assertNotNull(harness.renderer).timestamps.map { it.micros }
        assertTrue(pictures.isNotEmpty(), "no picture was shown")
        assertTrue(pictures.all { it in 2_100_000 until 4_300_000 }, "a picture outside the clip was shown: $pictures")
        assertEquals(2_120_000, pictures.first(), "the first picture is the first one at or after the clip's start")
        assertEquals(4_280_000, pictures.last(), "the last picture is the last one before the clip's end")

        // Read once the clock has settled on the last sample, as the clock of any ended item does.
        harness.run(100.milliseconds)
        assertNear(2_200_000, harness.core.position().inWholeMicroseconds, 1_000, "the item ends at its own length")
        assertTrue(harness.source.demuxFrontierUs < 5_000_000, "the reads went on to ${harness.source.demuxFrontierUs} µs, past the clip")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSubtitleOnScreenAtTheClipsEndLeavesThere() = runTest {
        val cue = io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
            3_500_000,
            5_500_000,
            listOf(io.github.yuroyami.kiteplayer.subtitle.StyledSpan("across the end")),
        )
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, subtitleCues = listOf(cue)))
        harness.attachRenderer()
        harness.core.open(clipped(2.seconds, 4.seconds))
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.subtitleCues.value.isNotEmpty() }, "the cue shows inside the clip")
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the item ends")
        assertTrue(harness.core.position() < 2_100.milliseconds, "the cue held the end back: ${harness.core.position()}")
        harness.run(100.milliseconds)
        assertEquals(emptyList(), harness.core.subtitleCues.value, "the cue left at the clip's end")
        assertEquals(emptyList(), assertNotNull(harness.renderer).overlays.lastOrNull()?.images.orEmpty(), "and its picture with it")
        harness.close()
    }

    @Test
    fun aClipWithNoEndPlaysToTheEndOfTheFile() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, hasVideo = false))
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.open(clipped(1.seconds, null))
        assertEquals(2.seconds, harness.core.snapshots.value.duration)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        assertNear(1_000_000, tap.blocks.first().ptsUs, frameUs, "the clip's first sample is the first heard")
        assertTrue(abs(tap.blocks.sumOf { it.frames.toLong() } - 2_000 * 48L) <= 1024, "two seconds were heard")
        harness.close()
    }

    @Test
    fun seeksCountFromTheClipsStartAndStayInsideIt() = runTest {
        val harness = CoreHarness(this, script = sixSeconds)
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.attachRenderer()
        harness.core.open(clipped(2.seconds, 4.seconds))

        val inside = assertIs<SeekResult.Applied>(harness.core.seek(Pts(1_000_000), SeekMode.Precise)).landedAt
        assertEquals(1_000_000, inside.micros, "the seek lands at the item's 1 s")
        assertEquals(1.seconds, harness.core.position())
        harness.run(100.milliseconds)
        assertNear(3_000_000, tap.blocks.first().ptsUs, frameUs, "the item's 1 s is the file's 3 s")
        val completed = harness.events.filterIsInstance<PlayerEvent.SeekCompleted>().last()
        assertEquals(1.seconds, completed.landedAt, "SeekCompleted says where the item landed")

        harness.core.seek(Pts(-1_000_000), SeekMode.Precise)
        assertEquals(Duration.ZERO, harness.core.position(), "a seek before the start lands on it")
        harness.run(100.milliseconds)
        assertNear(2_000_000, tap.blocks.first().ptsUs, frameUs, "and plays from the clip's start")

        harness.core.seek(Pts(9_000_000), SeekMode.Precise)
        assertTrue(harness.core.position() <= 2.seconds, "a seek past the end stays inside the item: ${harness.core.position()}")
        harness.run(100.milliseconds)
        assertTrue(tap.blocks.all { it.endUs <= 4_000_000 + frameUs }, "nothing past the clip was heard")
        assertTrue(assertNotNull(harness.renderer).timestamps.all { it.micros in 2_000_000 until 4_000_000 }, "no picture outside the clip")

        harness.core.seekToFractionLater(0.5, SeekMode.Precise)
        harness.run(100.milliseconds)
        assertNear(1_000_000, harness.core.position().inWholeMicroseconds, 40_000, "half of the item is its 1 s")
        harness.close()
    }

    @Test
    fun theStartPositionCountsFromTheClipsStart() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.open(clipped(2.seconds, 5.seconds, startPosition = 1.seconds))
        assertEquals(1.seconds, harness.core.position())
        harness.core.play()
        harness.run(100.milliseconds)
        assertNear(3_000_000, tap.blocks.first().ptsUs, frameUs, "the item's 1 s is the file's 3 s")
        harness.close()
    }

    @Test
    fun aClipThatStartsPastTheEndOfTheMediaRefusesToOpen() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        val failure = assertFailsWith<PlaybackException> { harness.core.open(clipped(5.seconds, 6.seconds)) }
        assertIs<PlaybackError.ConfigurationInvalid>(failure.error, "the open failed with ${failure.error}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "the refused open released what it opened")
    }

    @Test
    fun theChaptersAreTheClipsOwn() = runTest {
        val chapters = listOf(
            Chapter(0, Duration.ZERO, 1.seconds, "one"),
            Chapter(1, 1.seconds, 3.seconds, "two"),
            Chapter(2, 3.seconds, 6.seconds, "three"),
        )
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false, chapters = chapters))
        val changes = mutableListOf<Chapter?>()
        harness.core.open(clipped(2.seconds, 5.seconds))
        assertEquals(
            listOf(Chapter(0, Duration.ZERO, 1.seconds, "two"), Chapter(1, 1.seconds, 3.seconds, "three")),
            harness.core.snapshots.value.chapters,
        )
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        harness.events.filterIsInstance<PlayerEvent.ChapterChanged>().mapTo(changes) { it.chapter }
        // The item's end is the end of its last chapter, which belongs to none.
        assertEquals(listOf("two", "three", null), changes.map { it?.title }, "the crossings are the clip's: $changes")
        harness.close()
    }

    @Test
    fun aRepeatOfAClippedItemStartsAgainAtTheClipsStart() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.open(clipped(2.seconds, 4.seconds))
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        harness.run(5.seconds)
        assertTrue(tap.all.all { it.ptsUs >= 2_000_000 - frameUs && it.endUs <= 4_000_000 + frameUs }, "a sample outside the clip was heard")
        val starts = tap.all.count { abs(it.ptsUs - 2_000_000L) <= frameUs }
        assertTrue(starts >= 3, "three passes started at the clip's start in 5 s, $starts did")
        assertTrue(harness.core.position() <= 2.seconds, "the position stays inside the item: ${harness.core.position()}")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the passes follow each other with no gap")
        assertEquals(1, harness.events.count { it is PlayerEvent.Opened }, "the repeat preloads its pass rather than opening again")
        harness.close()
    }

    @Test
    fun anABLoopInAClippedItemCountsFromTheClipsStart() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.open(clipped(2.seconds, 5.seconds))
        val reply = CompletableDeferred<Unit>()
        harness.core.post(CoreCommand.SetAbLoop(1.seconds, 2.seconds, reply))
        reply.await()
        harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        // What the open put in the ring from the clip's start went at the seek.
        val heard = tap.all.size - tap.blocks.size
        harness.core.play()
        harness.run(3.seconds)
        val loop = tap.all.drop(heard)
        assertTrue(loop.isNotEmpty())
        assertTrue(
            loop.all { it.ptsUs >= 3_000_000 - frameUs && it.endUs <= 4_000_000 + frameUs },
            "the loop plays the item's 1 s to 2 s, the file's 3 s to 4 s: ${loop.minOf { it.ptsUs }} to ${loop.maxOf { it.endUs }}",
        )
        assertTrue(loop.count { abs(it.ptsUs - 3_000_000) <= frameUs } >= 2, "the loop went back to A")
        val position = harness.core.position()
        assertTrue(position >= 1.seconds && position <= 2.seconds, "the position stays inside the loop: $position")
        harness.close()
    }

    @Test
    fun aQueueOfClipsPreloadsEachAtItsStartWithNoGap() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.openQueue(
            listOf(
                clipped(Duration.ZERO, 2.seconds, "scripted://a"),
                clipped(2.seconds, 4.seconds, "scripted://b"),
                clipped(4.seconds, null, "scripted://c"),
            ),
            0,
        )
        harness.core.play()
        assertTrue(harness.runUntil(8.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the queue ends")

        // One continuous run of sound from 0 to 6 s: each item's first sample follows the last one
        // of the item before it.
        var expectedUs = 0L
        for (block in tap.all) {
            assertNear(expectedUs, block.ptsUs, frameUs, "a gap or an overlap at a join")
            expectedUs = block.endUs
        }
        assertNear(6_000_000, expectedUs, 21_334, "the queue played to the end of the file")
        assertEquals(listOf("open", "resume", "start", "drain"), harness.sink.calls.filter { it != "flush" }.take(4), "one device start for the queue: ${harness.sink.calls}")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertEquals(emptyList(), harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>())
        assertEquals(2.seconds, harness.core.snapshots.value.duration, "the last item is as long as its clip")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anAudioScanCoversTheClipOnly() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val blocks = mutableListOf<Block>()
        val result = scanMediaAudio(
            harness.backend,
            clipped(2.seconds, 4.seconds),
            track = null,
            preferredLanguages = emptyList(),
            range = null,
            sink = { pts, _, frames, format -> blocks += Block(pts.micros, frames, format.sampleRate) },
        )
        assertTrue(blocks.first().ptsUs <= 2_000_000 && blocks.first().endUs > 2_000_000, "the scan starts at the clip: ${blocks.first().ptsUs}")
        assertTrue(assertNotNull(result.endPts).micros in 4_000_000..4_021_334, "the scan stops at the clip's end: ${result.endPts}")
        harness.close()
    }

    @Test
    fun anInspectionReportsTheClipsLengthAndChapters() = runTest {
        val chapters = listOf(Chapter(0, Duration.ZERO, 3.seconds, "one"), Chapter(1, 3.seconds, null, "two"))
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, chapters = chapters))
        val inspection = harness.core.inspect(clipped(2.seconds, 5.seconds))
        assertEquals(3.seconds, inspection.duration)
        assertEquals(
            listOf(Chapter(0, Duration.ZERO, 1.seconds, "one"), Chapter(1, 1.seconds, 3.seconds, "two")),
            inspection.chapters,
        )
        harness.close()
    }

    @Test
    fun chaptersAreCutToTheClipAndNumberedAgain() {
        val file = listOf(
            Chapter(0, Duration.ZERO, 10.seconds, "a"),
            Chapter(1, 10.seconds, 20.seconds, "b"),
            Chapter(2, 20.seconds, null, "c"),
        )
        assertEquals(
            listOf(Chapter(0, Duration.ZERO, 5.seconds, "b"), Chapter(1, 5.seconds, 15.seconds, "c")),
            file.inClip(15_000_000, 30_000_000),
        )
        assertEquals(
            listOf(Chapter(0, Duration.ZERO, 5.seconds, "a")),
            file.inClip(5_000_000, 10_000_000),
            "a chapter that starts at the clip's end is not in it",
        )
        assertEquals(listOf(Chapter(0, Duration.ZERO, null, "c")), file.inClip(25_000_000, null), "no end on either side stays open")
        assertEquals(
            listOf(Chapter(0, Duration.ZERO, 500.microseconds, "a"), Chapter(1, 500.microseconds, 1_000_500.microseconds, "b")),
            file.inClip(9_999_500, 11_000_000),
            "a clip across a boundary keeps both halves",
        )
    }
}
