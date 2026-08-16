// The test scheduler's own clock reading is what proves a storm happened inside one millisecond.
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekPhase
import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.SeekTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The seek machine: the quiesce-first order, the coalescing rules, and the overshoot ladder.
 *
 * The reason these are testable at all is that time and the media are both scripted, so a storm of
 * twenty seeks inside one virtual millisecond is a deterministic sequence rather than a race that
 * sometimes reproduces. What is asserted here is the contract, not the outcome: what order the pipeline
 * was touched in, how many flush cycles a storm costs, that nothing from a superseded epoch was ever
 * presented or heard, and that every caller was answered exactly once.
 */
class SeekMachineTest {

    @Test
    fun `a seek stops the device before it flushes a decoder and moves the cursor last`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)

        harness.trace.clear()
        harness.core.seek(Pts(2_000_000), SeekMode.Precise)

        assertEquals(
            listOf("sink.stop", "video.flush", "audio.flush", "sink.stop", "source.seek"),
            harness.trace.entries.toList(),
            "the seek order is the contract: the device is stopped before the workers are asked to " +
                "quiesce, each decoder is flushed on its owning worker, the device is stopped again as " +
                "the ring is cleared, and only then does the cursor move",
        )
        harness.close()
    }

    @Test
    fun `twenty seeks in one virtual millisecond produce exactly one flush cycle`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        // Every one of them is issued before the actor gets a pass, which is what "one millisecond"
        // means here: they are one storm and not twenty events.
        val started = harness.scheduler.currentTime
        val seeks = (1..20).map { index ->
            async { harness.core.seek(Pts(index * 100_000L), SeekMode.Precise) }
        }
        assertEquals(started, harness.scheduler.currentTime, "the storm was issued without time passing")

        val results = seeks.awaitAll()

        assertEquals(
            1,
            harness.core.seekFlushCycles,
            "a storm merges into one request, so the pipeline is flushed once and not twenty times",
        )
        assertEquals(
            1,
            results.count { it is SeekResult.Applied },
            "exactly one of them ran: ${results.map { it::class.simpleName }}",
        )
        assertEquals(19, results.count { it is SeekResult.Superseded }, "and the rest were answered as superseded")
        val applied = assertNotNull(results.last() as? SeekResult.Applied, "the last request is the one that ran")
        assertTrue(
            applied.landedAt.micros >= 2_000_000 - SeekTiming.PRECISE_TOLERANCE_US,
            "the merged request keeps the newest absolute target, which was 2 s: ${applied.landedAt}",
        )
        assertEquals(1, harness.source.seeks, "the cursor moved once")
        harness.close()
    }

    @Test
    fun `no frame from a superseded generation is ever presented`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()

        repeat(8) { round ->
            harness.run(120.milliseconds)
            harness.core.seek(Pts(200_000L + round * 300_000L), SeekMode.Precise)
        }
        harness.run(500.milliseconds)

        val presentations = harness.renderer?.presentations ?: emptyList()
        assertTrue(presentations.size > 8, "the run presented something: ${presentations.size} frames")

        // Generations never go backwards at the renderer, which is the strong form of the invariant: a
        // frame from an epoch the viewer has left must not reach the screen even one hop late.
        var highest = presentations.first().generation
        presentations.forEach { presentation ->
            assertTrue(
                presentation.generation >= highest,
                "a frame from ${presentation.generation} reached the renderer after ${highest} had: " +
                    "generation tags are checked at the last hop and quiescence stops the rest",
            )
            highest = presentation.generation
        }

        // And within one generation the timestamps only move forward.
        presentations.groupBy { it.generation }.forEach { (generation, group) ->
            group.zipWithNext().forEach { (earlier, later) ->
                assertTrue(
                    later.pts >= earlier.pts,
                    "$generation presented ${later.pts} after ${earlier.pts}",
                )
            }
        }
        harness.close()
    }

    @Test
    fun `no audio from a superseded generation is ever heard`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        harness.core.seek(Pts(1_500_000), SeekMode.Precise)
        // Every scripted sample carries its own epoch as its value, so what the device was handed names
        // the epochs that became audible.
        harness.sink.audibleValues.clear()
        harness.run(500.milliseconds)

        val epoch = harness.core.snapshots.value.generation
        val allowed = setOf(0f, epochSample(epoch))
        assertEquals(
            emptySet(),
            harness.sink.audibleValues - allowed,
            "samples from a superseded epoch reached the device: heard ${harness.sink.audibleValues}, " +
                "and only silence or ${epochSample(epoch)}, which is epoch ${epoch.value}, may be",
        )
        harness.close()
    }

    @Test
    fun `a seek storm terminates in bounded virtual time`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()

        // Fifty requests with no pause between them, then nothing. A machine with an unbounded rule in it
        // never comes out of this.
        repeat(50) { index ->
            harness.core.seekLater(Pts((index * 37_000L) % 2_000_000L), SeekMode.KeyframeThenRefine)
        }
        harness.run(30.seconds)

        assertEquals(
            SeekPhase.Idle,
            harness.core.phase,
            "the seek machine settled rather than holding a request for ever",
        )
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "and playback reached a terminal state",
        )
        assertEquals(0, harness.ledger.liveCount, "with every frame and packet released")
        harness.close()
    }

    @Test
    fun `relative requests add up while they wait`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        // Holding an arrow key must move by the total of the presses. That only works because a request
        // keeps its shape until the moment it is resolved against a position.
        harness.core.seekByLater(500.milliseconds, SeekMode.Keyframe)
        harness.core.seekByLater(500.milliseconds, SeekMode.Keyframe)
        harness.core.seekByLater(500.milliseconds, SeekMode.Keyframe)
        harness.run(2.seconds)

        assertEquals(1, harness.core.seekFlushCycles, "three presses are one seek")
        assertEquals(
            listOf(1_500_000L),
            harness.source.seekTargets,
            "the cursor was asked for the total of the three offsets, not for the last one",
        )
        harness.close()
    }

    @Test
    fun `an absolute request absorbs the relative ones waiting in front of it`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        harness.core.seekByLater(500.milliseconds, SeekMode.Keyframe)
        harness.core.seekToFractionLater(0.5, SeekMode.Keyframe)
        harness.run(2.seconds)

        assertEquals(1, harness.core.seekFlushCycles)
        assertEquals(
            listOf(2_000_000L),
            harness.source.seekTargets,
            "dragging a seek bar supersedes the key presses that came before it",
        )
        harness.close()
    }

    @Test
    fun `the strictest mode of a merged storm wins`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        // A precise request must not be quietly downgraded by a coarse one arriving after it.
        harness.core.seekLater(Pts(1_000_000), SeekMode.Precise)
        harness.core.seekLater(Pts(1_000_000), SeekMode.Keyframe)
        harness.run(2.seconds)

        val landed = harness.core.position().inWholeMicroseconds
        assertTrue(
            landed >= 1_000_000 - SeekTiming.PRECISE_TOLERANCE_US,
            "the merged seek stayed precise and landed at $landed rather than on the keyframe before it",
        )
        harness.close()
    }

    @Test
    fun `a container that overshoots is walked back along the backoff ladder`() = runTest {
        // A container with no index resolves a seek by byte position and can land after the target. The
        // first decoded frame is the evidence, and the ladder is the answer.
        val harness = CoreHarness(this, script = MediaScript(seekOvershootUs = 700_000))
        harness.openWithRenderer()

        val result = harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        val applied = assertNotNull(result as? SeekResult.Applied)

        assertTrue(
            harness.source.seekTargets.size >= 2,
            "the first attempt overshot, so the ladder aimed lower: ${harness.source.seekTargets}",
        )
        assertEquals(
            1_000_000L,
            harness.source.seekTargets.first(),
            "the first attempt aims at the target itself, because almost every seek costs one attempt",
        )
        assertEquals(
            1_000_000L - SeekTiming.OVERSHOOT_BACKOFF_US[1],
            harness.source.seekTargets[1],
            "and the second backs off by the ladder's first step",
        )
        assertTrue(
            applied.landedAt.micros <= 1_000_000 + SeekTiming.PRECISE_TOLERANCE_US,
            "the ladder ended on a landing at or before the target: ${applied.landedAt}",
        )
        harness.close()
    }

    @Test
    fun `a request held by the frame barrier is bounded and then runs`() = runTest {
        // A video stream that decodes nothing: the seek can never show a frame of its own, so the barrier
        // rule has something to hold. Bounded, because a waiting rule that cannot expire is a wedge.
        val faults = FaultPlan().also { it.videoDecodeProducesNothing = true }
        val harness = CoreHarness(this, faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        harness.core.seek(Pts(600_000), SeekMode.Keyframe)
        val afterFirst = harness.scheduler.currentTime
        harness.core.seekLater(Pts(1_200_000), SeekMode.Keyframe)
        harness.run(1.seconds)

        val heldFor = harness.source.seekTargets.size
        assertEquals(2, heldFor, "the held request ran once its window expired: ${harness.source.seekTargets}")
        assertTrue(
            harness.scheduler.currentTime - afterFirst >= SeekTiming.COALESCE_WINDOW_US / 1_000,
            "and it waited the coalescing window out rather than running immediately",
        )
        assertEquals(SeekPhase.Idle, harness.core.phase)
        harness.close()
    }

    @Test
    fun `a seek while paused leaves the player paused on a frame at the new position`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val before = harness.renderer?.count ?: 0

        harness.core.seek(Pts(1_200_000), SeekMode.Precise)

        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertTrue(
            (harness.renderer?.count ?: 0) > before,
            "a seek ends on a picture of where it landed, even with the clock stopped",
        )
        val last = assertNotNull(harness.renderer?.presentations?.last())
        assertTrue(
            last.pts.micros >= 1_200_000 - SeekTiming.PRECISE_TOLERANCE_US,
            "and that picture is from the new position, not the old one: ${last.pts}",
        )
        harness.close()
    }

    @Test
    fun `a seek past the end reaches the end rather than being overwritten`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.seek(Pts(5_000_000), SeekMode.Precise)
        harness.run(10.seconds)

        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "a seek past the end terminates: the rule that a precise request waits for the previous " +
                "restart is what stops its end-of-stream result from being overwritten",
        )
        harness.close()
    }

    @Test
    fun `every seek in a long storm is answered exactly once`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()

        val replies = mutableListOf<CompletableDeferred<SeekResult>>()
        val callers = (1..12).map { index ->
            launch {
                val reply = CompletableDeferred<SeekResult>()
                replies += reply
                reply.complete(harness.core.seek(Pts(index * 150_000L), SeekMode.Precise))
            }
        }
        harness.run(3.seconds)
        callers.forEach { it.join() }

        assertEquals(12, replies.size)
        assertTrue(replies.all { it.isCompleted }, "every caller was answered")
        harness.close()
    }

    @Test
    fun `position stands on the requested timeline while a seekLater is still in flight`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)

        // A seek-bar release inside the coalesce window of a previous request: the newest request is
        // held until a frame from the first has shown. That hold is where the published position used
        // to keep advancing on the old timeline, which a polling seek bar renders as the thumb
        // snapping back before jumping to the destination.
        harness.core.seekLater(Pts(2_000_000), SeekMode.Precise)
        harness.run(20.milliseconds)
        harness.core.seekLater(Pts(2_500_000), SeekMode.Precise)

        val target = 2_500_000L
        var settled = false
        repeat(40) {
            harness.run(25.milliseconds)
            val read = harness.core.position().inWholeMicroseconds
            assertTrue(
                read >= target - SeekTiming.PRECISE_TOLERANCE_US,
                "a poll observed $read µs while the newest request asked for $target µs: once a seek " +
                    "is accepted, position() must answer on the requested timeline, never behind it",
            )
            if (!settled && read >= target - SeekTiming.PRECISE_TOLERANCE_US) settled = true
        }
        assertTrue(settled, "the merged seek landed at the newest target")
        harness.close()
    }

    @Test
    fun `a paused seek never publishes an active status`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.run(200.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)

        val historyBefore = harness.core.statusHistory.toList()
        harness.core.seekLater(Pts(1_000_000), SeekMode.Precise)
        harness.run(2.seconds)

        assertTrue(
            harness.events.filterIsInstance<PlayerEvent.SeekCompleted>().isNotEmpty(),
            "the paused seek did not complete",
        )
        assertEquals(
            historyBefore,
            harness.core.statusHistory.toList(),
            "a seek while paused must stay Paused for its whole run: every state mirror reads a " +
                "transient Buffering as a momentary unpause",
        )
        harness.close()
    }

    @Test
    fun `a seek after Ended revives playback when play is still requested`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(6.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)

        harness.core.seekLater(Pts(1_000_000), SeekMode.Precise)
        harness.run(1.seconds)

        val afterSeek = harness.core.snapshots.value.status
        assertTrue(
            afterSeek == PlaybackStatus.Playing || afterSeek == PlaybackStatus.Buffering,
            "play was still requested, so the applied seek must leave Ended and resume, not sit in $afterSeek",
        )
        harness.run(6.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "the revived playback must reach the end again",
        )
        harness.close()
    }

    @Test
    fun `a paused seek after Ended lands Paused and play works again`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(6.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)

        harness.core.pause()
        harness.core.seekLater(Pts(1_000_000), SeekMode.Precise)
        harness.run(1.seconds)
        assertEquals(
            PlaybackStatus.Paused,
            harness.core.snapshots.value.status,
            "an applied seek is a legal exit from Ended, and with no play requested it lands Paused",
        )

        harness.core.play()
        harness.run(500.milliseconds)
        val resumed = harness.core.snapshots.value.status
        assertTrue(
            resumed == PlaybackStatus.Playing || resumed == PlaybackStatus.Buffering,
            "play after the revived seek must restart playback, not stay in $resumed",
        )
        harness.close()
    }
}
