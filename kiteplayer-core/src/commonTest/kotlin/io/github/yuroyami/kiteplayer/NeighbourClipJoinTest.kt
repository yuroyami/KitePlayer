@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Neighbouring parts of one file play on one open (#456), as the tracks of an album in one file
 * with a cue sheet do: the next item's sound follows on the same reads and the same decoders, so
 * nothing opens again, the device never stops and every sample is heard once. A part that does not
 * start where the one before ends, or another file, takes the gapless road through a preload.
 */
class NeighbourClipJoinTest {

    private val minute = MediaScript(durationUs = 60_000_000, hasVideo = false)

    private fun track(n: Int, start: Duration, end: Duration?, uri: String = "scripted://album") =
        MediaItem(uri, clip = MediaClip(start, end), title = "Track $n")

    private val album = listOf(
        track(1, Duration.ZERO, 20.seconds),
        track(2, 20.seconds, 40.seconds),
        track(3, 40.seconds, null),
    )

    private class Block(val ptsUs: Long, val frames: Int, val sampleRate: Int) {
        val endUs: Long get() = ptsUs + frames * 1_000_000L / sampleRate
    }

    /** Every block heard after the last cut, and the cuts after the first sound, which a join must not make. */
    private class RecordingTap : AudioTap {
        val blocks = mutableListOf<Block>()
        var seams = 0

        override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
            blocks += Block(pts.micros, frames, format.sampleRate)
        }

        override fun onDiscontinuity() {
            if (blocks.isNotEmpty()) seams++
            blocks.clear()
        }
    }

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    private suspend fun CoreHarness.setSleepTimer(timer: SleepTimer?) {
        val reply = CompletableDeferred<Unit>()
        core.post(CoreCommand.SetSleepTimer(timer, Duration.ZERO, reply))
        reply.await()
    }

    private val CoreHarness.queueIndex: Int? get() = core.snapshots.value.queueIndex

    private fun assertContiguous(blocks: List<Block>, fromUs: Long, toUs: Long) {
        assertTrue(blocks.isNotEmpty(), "nothing was heard")
        assertTrue(abs(blocks.first().ptsUs - fromUs) <= FRAME_US, "the sound starts at ${blocks.first().ptsUs}")
        blocks.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(b.ptsUs - a.endUs) <= FRAME_US, "a sample was lost or heard twice between ${a.endUs} and ${b.ptsUs}")
        }
        assertTrue(abs(blocks.last().endUs - toUs) <= FRAME_US, "the sound ends at ${blocks.last().endUs}, not $toUs")
    }

    @Test
    fun theTracksOfOneFilePlayOnOneOpenWithEverySampleHeardOnce() = runTest {
        val harness = CoreHarness(this, script = minute)
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.openQueue(album, 0)
        harness.core.play()

        assertTrue(harness.runUntil(25.seconds) { harness.queueIndex == 1 }, "the queue moved to the second track")
        val position = harness.core.position()
        assertTrue(position < 30.milliseconds, "the second track counts from its start: $position")
        assertEquals(20.seconds, harness.core.snapshots.value.duration, "the second track is 20 seconds long")
        assertEquals("Track 2", harness.core.snapshots.value.media?.title)
        harness.run(5.seconds)
        val later = harness.core.position()
        assertTrue(later in 4.9.seconds..5.1.seconds, "the second track's clock runs from its start: $later")

        assertTrue(harness.runUntil(40.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the album ends")
        assertEquals(2, harness.queueIndex)
        assertEquals(1, harness.backend.openCalls, "the file opened once")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls.takeWhile { it != "drain" }, "the device never stopped")
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        assertEquals(0, tap.seams, "the sound has no seam")
        // The last track runs to the end of the file, whose sound is whole decoder buffers.
        val buffers = (minute.durationUs + minute.audioBufferDurationUs - 1) / minute.audioBufferDurationUs
        assertContiguous(tap.blocks, 0L, tap.blocks.last().endUs)
        assertEquals(buffers * minute.audioBufferFrames, tap.blocks.sumOf { it.frames.toLong() }, "every sample of the file was heard once")
        assertEquals(buffers * minute.audioBufferFrames, harness.sink.framesPlayed)

        val moves = harness.events.filter { it is PlayerEvent.Ended || it is PlayerEvent.Opened }
        assertEquals(6, moves.size, "opened and ended, three times: $moves")
        assertEquals(
            listOf("Track 1", "Track 2", "Track 3"),
            moves.filterIsInstance<PlayerEvent.Opened>().map { it.media.title },
        )
        val afterPlay = harness.core.statusHistory.dropWhile { it != PlaybackStatus.Playing }.dropLast(1)
        assertTrue(afterPlay.all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekInsideAJoinedTrackStaysInsideIt() = runTest {
        val harness = CoreHarness(this, script = minute)
        harness.core.openQueue(album, 0)
        harness.core.play()
        assertTrue(harness.runUntil(25.seconds) { harness.queueIndex == 1 })
        harness.core.seek(Pts(10_000_000), SeekMode.Precise)
        harness.run(500.milliseconds)
        val position = harness.core.position()
        assertTrue(position in 10.seconds..11.seconds, "a seek 10 s into the track lands there: $position")
        assertTrue(harness.runUntil(15.seconds) { harness.queueIndex == 2 }, "the third track still joins after the seek")
        assertEquals(1, harness.backend.openCalls)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aTimerToStopAtTheEndOfTheTrackEndsItExactlyAtItsEnd() = runTest {
        val harness = CoreHarness(this, script = minute)
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.openQueue(album, 0)
        harness.core.play()
        harness.run(15.seconds)
        harness.setSleepTimer(SleepTimer.EndOfItem)
        assertTrue(harness.runUntil(10.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the track ends")
        assertEquals(0, harness.queueIndex, "the queue stayed on the first track")
        assertContiguous(tap.blocks, 0L, 20_000_000L)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aJoinWithdrawnAfterTheNextTracksSoundWasWrittenStillEndsAtTheEnd() = runTest {
        val harness = CoreHarness(this, script = minute)
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.core.openQueue(album, 0)
        harness.core.play()
        // The ring runs ahead of what is heard, so at 19.9 s the second track's first sound is in it.
        assertTrue(harness.runUntil(21.seconds) { harness.core.position() >= 19.9.seconds })
        harness.setSleepTimer(SleepTimer.EndOfItem)
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the track ends")
        assertEquals(0, harness.queueIndex, "the queue stayed on the first track")
        val last = tap.blocks.maxOf { it.endUs }
        assertTrue(abs(last - 20_000_000L) <= FRAME_US, "nothing past the end is heard after the way back: $last")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aTrackThatDoesNotStartWhereTheLastEndedTakesThePreload() = runTest {
        val harness = CoreHarness(this, script = minute)
        harness.core.openQueue(listOf(track(1, Duration.ZERO, 20.seconds), track(2, 25.seconds, 40.seconds)), 0)
        harness.core.play()
        assertTrue(harness.runUntil(25.seconds) { harness.queueIndex == 1 })
        assertEquals(2, harness.backend.openCalls, "the second track opened its own reads")
        harness.run(1.seconds)
        val position = harness.core.position()
        assertTrue(position in 0.9.seconds..1.1.seconds, "and plays from its start: $position")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anotherFileTakesThePreload() = runTest {
        val harness = CoreHarness(this, script = minute)
        harness.core.openQueue(listOf(track(1, Duration.ZERO, 20.seconds), track(2, 20.seconds, 40.seconds, uri = "scripted://other")), 0)
        harness.core.play()
        assertTrue(harness.runUntil(25.seconds) { harness.queueIndex == 1 })
        assertEquals(2, harness.backend.openCalls, "the other file opened its own reads")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun picturesJoinWithTheSound() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 12_000_000))
        harness.attachRenderer()
        harness.core.openQueue(listOf(track(1, Duration.ZERO, 6.seconds), track(2, 6.seconds, null)), 0)
        harness.core.play()
        assertTrue(harness.runUntil(8.seconds) { harness.queueIndex == 1 })
        val renderer = harness.renderer!!
        val before = renderer.count
        harness.run(1.seconds)
        assertTrue(renderer.count > before + 10, "the pictures go on after the join")
        assertEquals(1, harness.backend.openCalls)
        assertEquals(1, harness.sink.openCount)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    private companion object {
        /** One sample frame at 48 kHz, which is as close as a cut to the sample can land. */
        const val FRAME_US = 21L
    }
}
