package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.StatusMachine
import io.github.yuroyami.kiteplayer.internal.platformPlaybackDispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The session core, driven through scripted media in virtual time.
 *
 * What is under test here is the loop and its contracts: the order the handlers run in, what each
 * command is legal to do and when, the six conditions that make up the end of a stream, the two signals
 * buffering needs, and the policy that a failure is typed, retained and never confused with a
 * cancellation. Timing rules of their own have their own tests; this is about the machine that drives
 * them.
 */
class PlaybackCoreTest {

    // ---------------------------------------------------------------------------------------------
    // The loop.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the pass runs its handlers in the one order the design fixes`() = runTest {
        val harness = CoreHarness(this)
        val expected = listOf(
            "drainCommands",
            "handleTrackChanges",
            "handleAudioFill",
            "handleVideoWrite",
            "handlePlaybackRestart",
            "handlePlaybackTime",
            "handleBuffering",
            "handleSubtitles",
            "handleEof",
            "handleLoop",
            "handleQueuedSeek",
            "publishSnapshot",
            "awaitWork",
        )
        assertEquals(expected, harness.core.handlerOrder, "the declared order is the contract")

        harness.recordHandlers()
        harness.open()
        harness.stopRecordingHandlers()

        assertTrue(harness.handlerTrace.size >= expected.size, "at least one full pass ran")
        // Every pass is the whole list in the same order, so the trace is that list repeated.
        harness.handlerTrace.chunked(expected.size).dropLast(1).forEachIndexed { pass, names ->
            assertEquals(expected, names, "pass $pass ran its handlers out of order")
        }
        harness.close()
    }

    @Test
    fun `an idle loop sleeps the wake floor and no longer`() = runTest {
        val harness = CoreHarness(this)
        harness.open()
        val before = harness.core.loopPasses

        // Nothing is playing and nothing is queued, so the only thing that wakes the loop is its own
        // floor. Five floors of virtual time must therefore be about five passes and not one.
        harness.run(250.milliseconds)
        val passes = harness.core.loopPasses - before

        assertTrue(
            passes in 4..6,
            "a 250 ms wait with a 50 ms floor is five passes, was $passes: a longer sleep makes a " +
                "level-triggered loop miss conditions that nothing signals",
        )
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // Open.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `opening selects defaults fills paused and ends on a frame`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "an open ends paused on the first frame")
        assertEquals(TrackId(0), snapshot.tracks.selectedVideo, "the first non-cover-art video is chosen")
        assertEquals(TrackId(1), snapshot.tracks.selectedAudio)
        assertEquals(4.seconds, snapshot.duration)
        assertTrue(snapshot.seekable)
        assertEquals(1, harness.source.selectCalls, "streams are selected exactly once, before the first read")

        assertEquals(1, harness.renderer?.count, "the first frame is presented with the clock stopped")
        assertEquals(0, harness.sink.startCount, "the fill happened with the device stopped")
        assertNull(snapshot.error)
        harness.close()
    }

    @Test
    fun `a video stream no decoder accepts is deselected and the audio still plays`() = runTest {
        val faults = FaultPlan().also { it.videoDecodersRefuse = true }
        val harness = CoreHarness(this, faults = faults)
        harness.openWithRenderer()

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "an open fails only when nothing playable is left")
        assertNull(snapshot.tracks.selectedVideo, "the stream whose every candidate refused is deselected")
        assertEquals(TrackId(1), snapshot.tracks.selectedAudio)
        harness.close()
    }

    @Test
    fun `an open with nothing playable fails typed and the error is retained`() = runTest {
        val faults = FaultPlan().also {
            it.videoDecodersRefuse = true
            it.audioDecodersRefuse = true
        }
        val harness = CoreHarness(this, faults = faults)

        val failure = assertFailsWith<PlaybackException> { harness.open() }
        assertTrue(failure.error is PlaybackError.NoPlayableStream, "the error names what was wrong: ${failure.error}")

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Failed, snapshot.status)
        assertEquals(
            failure.error,
            snapshot.error,
            "a fatal error is retained on the snapshot: an event stream replays nothing to a late consumer",
        )
        assertEquals(1, harness.session.closeCount, "nothing half open survives a failed open")
        harness.close()
    }

    @Test
    fun `cancelling a suspended open leaves the player idle and nothing half built`() = runTest {
        val harness = CoreHarness(this)
        harness.backend.openGate = kotlinx.coroutines.CompletableDeferred()

        val caller = launch { harness.core.open(MediaItem("scripted://slow")) }
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Opening, harness.core.snapshots.value.status)

        caller.cancel()
        harness.backend.openGate?.complete(Unit)
        harness.run(500.milliseconds)

        assertEquals(
            PlaybackStatus.Idle,
            harness.core.snapshots.value.status,
            "a cancelled open leaves Idle, never a half-open graph",
        )
        assertEquals(0, harness.ledger.liveCount, "and nothing it allocated is still live")
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // The legality table, one case per row.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `open is legal from idle and ended and refused while playing`() = runTest {
        val harness = CoreHarness(this)
        harness.open()
        harness.core.play()
        harness.run(200.milliseconds)

        val refusal = assertFailsWith<IllegalStateException> { harness.open() }
        assertTrue(
            refusal.message?.contains("stop()") == true,
            "the refusal says what to do instead: ${refusal.message}",
        )
        harness.core.stop()
        harness.open()
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `play and pause are idempotent in their own state and queue during an open`() = runTest {
        val harness = CoreHarness(this)
        harness.backend.openGate = kotlinx.coroutines.CompletableDeferred()
        val opening = launch { harness.open() }
        harness.run(50.milliseconds)
        assertEquals(PlaybackStatus.Opening, harness.core.snapshots.value.status)

        // Queued, not refused. A caller cannot time its own play against an open it cannot see inside,
        // so asking during one has to mean "start as soon as you can".
        harness.core.play()
        harness.core.play()

        harness.backend.openGate?.complete(Unit)
        opening.join()
        harness.run(300.milliseconds)
        assertEquals(
            PlaybackStatus.Playing,
            harness.core.snapshots.value.status,
            "the play issued during the open is honoured once the pipeline can serve it",
        )

        harness.core.pause()
        harness.core.pause()
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)

        harness.core.play()
        harness.run(200.milliseconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `a suspended seek completes exactly once with where it landed`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        val result = harness.core.seek(Pts(2_000_000), SeekMode.Precise)
        val applied = assertNotNull(result as? SeekResult.Applied, "a seek that ran reports where it landed")
        assertTrue(
            applied.landedAt.micros >= 2_000_000 - 5_000,
            "a precise seek lands at the target and not at the keyframe before it: ${applied.landedAt}",
        )
        assertEquals(1, harness.core.seekFlushCycles, "one seek is one flush cycle")
        harness.close()
    }

    @Test
    fun `seekLater is fire and forget and coalesces`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        repeat(20) { harness.core.seekLater(Pts(1_000_000L + it * 10_000L), SeekMode.KeyframeThenRefine) }
        harness.run(2.seconds)

        assertEquals(
            1,
            harness.core.seekFlushCycles,
            "twenty requests in one pass are one merged seek, so one flush cycle",
        )
        harness.close()
    }

    @Test
    fun `stop preempts and returns the player to idle`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)

        harness.core.stop()

        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        assertNull(harness.core.snapshots.value.media)
        assertEquals(1, harness.session.closeCount, "stop tears the session down")
        assertEquals(0, harness.ledger.liveCount, "and leaves nothing live")
        harness.close()
    }

    @Test
    fun `close is idempotent terminal and resolves what was outstanding`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        harness.core.closeAndAwait()
        harness.core.closeAndAwait()

        assertEquals(0, harness.ledger.liveCount, "closing releases every frame and packet")
        assertFailsWith<IllegalStateException> { harness.core.play() }
    }

    @Test
    fun `non suspending close and concurrent awaited closes share one success`() = runTest {
        val core = directCore()
        core.close()

        val first = async(start = CoroutineStart.UNDISPATCHED) { core.closeAndAwait() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { core.closeAndAwait() }
        first.await()
        second.await()
        core.closeAndAwait()

        assertEquals(PlaybackStatus.Idle, core.snapshots.value.status)
        assertNull(core.snapshots.value.error)
    }

    @Test
    fun `concurrent and repeated awaited closes share one typed failure and stay terminal`() = runTest {
        val core = directCore(closeDeadline = Duration.ZERO)
        core.close()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { core.closeAndAwait() }.exceptionOrNull()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { core.closeAndAwait() }.exceptionOrNull()
        }
        val firstFailure = assertIs<PlaybackException>(first.await())
        val secondFailure = assertIs<PlaybackException>(second.await())
        val repeatedFailure = assertFailsWith<PlaybackException> { core.closeAndAwait() }

        assertTrue(firstFailure.error is PlaybackError.RuntimeCompromised)
        assertEquals(firstFailure.error, secondFailure.error)
        assertEquals(firstFailure.error, repeatedFailure.error)
        assertFailsWith<IllegalStateException> { core.play() }
        assertFailsWith<IllegalStateException> {
            core.seekLater(Pts(1_000_000), SeekMode.KeyframeThenRefine)
        }
    }

    @Test
    fun `cancelling one close waiter leaves the shared teardown for the next waiter`() = runTest {
        val core = directCore()
        core.close()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { core.closeAndAwait() }

        cancelled.cancelAndJoin()
        core.closeAndAwait()

        assertEquals(PlaybackStatus.Idle, core.snapshots.value.status)
        assertNull(core.snapshots.value.error)
    }

    @Test
    fun `close CAS rejects suspending and direct seek commands before the actor advances`() = runTest {
        val core = directCore()
        core.close()

        assertFailsWith<IllegalStateException> { core.open(MediaItem("scripted://too-late")) }
        assertFailsWith<IllegalStateException> {
            core.seekLater(Pts(1_000_000), SeekMode.KeyframeThenRefine)
        }
        assertFailsWith<IllegalStateException> {
            core.seekByLater(1.seconds, SeekMode.KeyframeThenRefine)
        }
        assertFailsWith<IllegalStateException> {
            core.seekToFractionLater(0.5, SeekMode.KeyframeThenRefine)
        }
        core.closeAndAwait()
    }

    @Test
    fun `actor parent cancellation settles awaited close as typed failure`() = runTest {
        val parent = Job()
        val core = directCore(parent = parent)
        parent.cancel()

        val failure = withContext(Dispatchers.Default) {
            assertFailsWith<PlaybackException> {
                withTimeout(1.seconds) { core.closeAndAwait() }
            }
        }

        assertTrue(failure.error is PlaybackError.RuntimeCompromised)
        assertFailsWith<IllegalStateException> { core.play() }
        assertFailsWith<IllegalStateException> {
            core.seekLater(Pts(1_000_000), SeekMode.KeyframeThenRefine)
        }
    }

    @Test
    fun `owned production dispatchers close after their session actor returns`() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                val clock = MonotonicClock.System
                val core = PlaybackCore(
                    config = PlayerConfig(),
                    backend = ScriptedBackend(),
                    output = ScriptedOutput(clock, ScriptedSink()),
                    dispatchers = platformPlaybackDispatchers(),
                )

                core.closeAndAwait()

                assertEquals(PlaybackStatus.Idle, core.snapshots.value.status)
                assertNull(core.snapshots.value.error)
            }
        }
    }

    @Test
    fun `dispatcher close failure is published before the shared terminal result fails`() = runTest {
        val context = Dispatchers.Default
        val refusingDispatchers = object : PlaybackDispatchers {
            override val session = context
            override val demux = context
            override val videoDecode = context
            override val audioDecode = context
            override val audioFeed = context
            override val videoSchedule = context

            override fun close(): Nothing = error("the dispatcher set refuses to close")
        }
        val core = directCore(
            parent = null,
            dispatchers = refusingDispatchers,
            closeDispatchers = true,
        )

        val failure = withContext(Dispatchers.Default) {
            runCatching { withTimeout(5.seconds) { core.closeAndAwait() } }.exceptionOrNull()
        }
        val typed = assertIs<PlaybackException>(failure)
        assertIs<PlaybackError.RuntimeCompromised>(typed.error)
        assertEquals(PlaybackStatus.Idle, core.snapshots.value.status)
        assertEquals(typed.error, core.snapshots.value.error)
    }

    @Test
    fun `selectTrack is refused on a source that cannot seek and reopens one that can`() = runTest {
        val fixed = CoreHarness(this, script = MediaScript(seekable = false))
        fixed.openWithRenderer()
        val refusal = assertFailsWith<UnsupportedOperationException> {
            fixed.core.selectTrack(TrackKind.Audio, TrackId(1))
        }
        assertTrue(refusal.message?.contains("seek back") == true, "the refusal says why: ${refusal.message}")
        fixed.close()
    }

    @Test
    fun `attaching and detaching a renderer is legal while playing and detach fences`() = runTest {
        val harness = CoreHarness(this, renderer = null)
        harness.open()
        harness.core.play()
        harness.run(300.milliseconds)

        val renderer = RecordingRenderer()
        harness.core.attachRenderer(renderer)
        harness.run(300.milliseconds)
        assertTrue(renderer.count > 0, "a renderer attached mid-playback starts receiving frames")

        harness.core.detachRenderer()
        val atDetach = renderer.count
        harness.run(300.milliseconds)
        assertEquals(
            atDetach,
            renderer.count,
            "detach fences: no submission to the old renderer is outstanding when it returns",
        )
        harness.close()
    }

    @Test
    fun `loop all is refused because there is no queue to repeat`() = runTest {
        val harness = CoreHarness(this)
        harness.open()
        assertFailsWith<IllegalArgumentException> { harness.core.setLoop(LoopMode.All) }
        harness.core.setLoop(LoopMode.One)
        assertEquals(LoopMode.One, harness.core.snapshots.value.loop)
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // End of stream, buffering, and the still image.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `playing to the end satisfies all six end of stream conditions in order`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(6.seconds)

        val eos = harness.core.endOfStream
        assertTrue(eos.demuxerEnded, "the demuxer reached the end of the container")
        assertTrue(eos.audioDecoderDrained, "the audio decoder reported its own drain")
        assertTrue(eos.videoDecoderDrained, "the video decoder reported its own drain")
        assertTrue(eos.draining, "the device was told the media had ended, not left to guess")
        assertTrue(eos.sinkDrained, "the device played out what it held")
        assertTrue(eos.keepOpen, "and the last frame stays on screen")
        assertTrue(!eos.drainFailed, "a device that answers does not need the drain bounded out")
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `a device that never empties has its drain bounded rather than polled for ever`() = runTest {
        val faults = FaultPlan().also { it.drainHangs = true }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 500_000), faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(20.seconds)

        assertTrue(harness.core.endOfStream.drainFailed, "the drain completed as failed")
        assertTrue(harness.core.endOfStream.sinkDrained, "and the end of stream did not stall on it")
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `the end of the buffers is not the end of the media while paused`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 500_000))
        harness.openWithRenderer()
        harness.run(3.seconds)

        assertEquals(
            PlaybackStatus.Paused,
            harness.core.snapshots.value.status,
            "a viewer who asked for a still picture has not reached the end of anything",
        )
        harness.close()
    }

    @Test
    fun `a cover art still image is shown for five seconds and then finishes`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasAudio = false, videoIsCoverArt = true, durationUs = 40_000),
        )
        harness.openWithRenderer()
        harness.core.play()

        harness.run(3.seconds)
        assertEquals(
            PlaybackStatus.Playing,
            harness.core.snapshots.value.status,
            "three seconds in, the picture is still the picture",
        )

        harness.run(3.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "a still image displays for five seconds and is then done",
        )
        harness.close()
    }

    @Test
    fun `buffering needs both signals and a fast source never produces one`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)

        // A local file reads far faster than playback consumes, so the output is never starved with the
        // demuxer short at the same time. One signal on its own must never be enough: an output that has
        // just been handed its last buffer, or a queue that is momentarily empty, says nothing.
        assertEquals(
            0,
            harness.core.statusHistory.count { it == PlaybackStatus.Buffering },
            "a file that reads instantly rebuffered anyway: ${harness.core.statusHistory}",
        )
        harness.close()
    }

    @Test
    fun `a source slower than playback rebuffers and leaves buffering when it catches up`() = runTest {
        // 50 ms of reading for every 40 ms of media, which is what a stalling network looks like from here.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 4_000_000, readDelayUs = 50_000),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(25.seconds)

        val history = harness.core.statusHistory
        val firstBuffering = history.indexOf(PlaybackStatus.Buffering)
        assertTrue(firstBuffering >= 0, "a source that cannot keep up must say so: $history")
        assertTrue(
            harness.core.stats.value.rebuffers > 0,
            "and count it, because a consumer wanting a rate diffs two snapshots",
        )
        assertTrue(
            history.drop(firstBuffering).contains(PlaybackStatus.Playing),
            "and it must leave buffering by the same door it came in by, when the start rule holds " +
                "again: $history",
        )
        harness.close()
    }

    @Test
    fun `a badly interleaved file truncates one stream rather than stopping`() = runTest {
        // Every video packet before any audio packet, against a read-ahead budget small enough to be
        // reached. Without an answer this is the deadlock the packet queue's own documentation warns
        // about: the video queue is at the cap, the audio queue is empty, the audio clock never starts, so
        // nothing is consumed and the cap is never freed.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 3_000_000, badlyInterleaved = true),
            config = PlayerConfig(
                buffer = BufferPolicy(totalDuration = 400.milliseconds, totalBytes = 48 * 1024),
            ),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(20.seconds)

        val warning = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.PathologicalInterleaving>()
        assertTrue(
            warning.isNotEmpty(),
            "a gap in one stream beats a player that has stopped, and the caller is told: ${harness.events}",
        )
        assertTrue(
            harness.sink.framesPlayed > 0,
            "the starved stream reached the device, which is what the truncation was for",
        )
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "and the file finished rather than wedging: ${harness.core.debugState}",
        )
        harness.close()
    }

    @Test
    fun `selecting no audio track reopens the session without it`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val before = harness.core.position()

        harness.core.selectTrack(TrackKind.Audio, null)
        harness.run(500.milliseconds)

        assertEquals(
            2,
            harness.backend.openCalls,
            "a track change reopens the source, because one selection is all a source allows",
        )
        assertNull(
            harness.core.snapshots.value.tracks.selectedAudio,
            "a null track id means no track, not a track chosen for me",
        )
        assertTrue(
            harness.core.position() >= before - 200.milliseconds,
            "and playback resumes near where it was: was $before, now ${harness.core.position()}",
        )
        assertEquals(0, harness.ledger.doubleCloseCount, "the old session's frames were released once each")
        harness.close()
    }

    @Test
    fun `every status transition the session made is a legal one`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 800_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        harness.core.pause()
        harness.core.seek(Pts(400_000), SeekMode.Precise)
        harness.core.play()
        harness.run(4.seconds)
        harness.core.stop()

        assertEquals(
            emptyList(),
            harness.core.illegalTransitions,
            "the core recorded a transition its own state machine forbids",
        )
        harness.core.statusHistory.zipWithNext().forEach { (from, to) ->
            assertTrue(StatusMachine.isLegal(from, to), "$from to $to is not a legal transition")
        }
        harness.close()
    }

    @Test
    fun `a decoder that dies becomes a typed failure and not a hang`() = runTest {
        val faults = FaultPlan().also { it.failReadAfter = 12 }
        val harness = CoreHarness(this, faults = faults)

        // The read failure lands after the open has already filled, so this is a live pipeline failing.
        val outcome = runCatching { harness.openWithRenderer() }
        harness.run(2.seconds)

        val snapshot = harness.core.snapshots.value
        assertTrue(
            snapshot.status == PlaybackStatus.Failed,
            "a worker that dies is a handled failure; status was ${snapshot.status}, open said $outcome",
        )
        assertNotNull(snapshot.error, "and the failure is named")
        assertEquals(0, harness.ledger.liveCount, "with nothing left live")
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // The interlude's real-time seam, Kotlin half (I-02, I-03).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a failed selectStreams still closes the audio path it had opened`() = runTest {
        // Interlude item I-03. buildSession opens the audio path, then selects streams; a throw
        // from selectStreams used to close only the backend session, leaking a live sink that
        // since B1.8 owns a C sink, a C ring and an initialised AudioUnit, while
        // retainedResources() reported zero. The scripted sink records its close, so the leak is
        // one boolean here: remove the inner catch in buildSession and this is the test that
        // fails.
        val faults = FaultPlan()
        faults.failSelectStreams = true
        val harness = CoreHarness(this, faults = faults)

        assertFailsWith<PlaybackException> { harness.open() }
        assertTrue(harness.sink.closed, "the audio path leaked through a failed open")
        harness.close()
    }

    @Test
    fun `a close with a stalled worker still completes and closes the audio path exactly once`() = runTest(timeout = 150.seconds) {
        // Interlude item I-02, the reachable half. The audio decoder parks on a cancellable
        // suspension and never reaches a quiescent boundary, so its quiesce burns a full
        // QUIESCE_DEADLINE; close must still complete, cancel the parked job, join it, and only
        // then close the audio path, exactly once.
        //
        // What this test measured about the UNREACHABLE half, recorded here because it is the
        // reason no scripted test exhausts the close budget: the budget arithmetic hazard of the
        // review (five quiesce deadlines summing to the whole CLOSE_DEADLINE, the joins then
        // swallowed in a cancelled context) needs at least five workers stuck at once, and only
        // three of the five can stall at all, since the audio feeder and the video scheduler
        // quiesce cooperatively by construction. Three stalls burn 6 of the 10 seconds and the
        // teardown still finishes. So the NonCancellable join's own falsification lives at the C
        // level, where the review reproduced the freed-ring write under AddressSanitizer; this
        // test pins the Kotlin contract around it.
        val faults = FaultPlan()
        faults.stallAudioDecodeReceive = true
        val harness = CoreHarness(this, faults = faults)

        harness.open()
        harness.core.play()
        harness.run(100.milliseconds)
        harness.close()

        assertTrue(harness.sink.closed, "the audio path must be closed despite the stalled worker")
        assertTrue(
            harness.sink.stopCount > 0,
            "teardown stops the device before anything lets go of the ring",
        )
    }

    private fun TestScope.directCore(
        closeDeadline: Duration = 10.seconds,
        parent: Job? = backgroundScope.coroutineContext[Job],
        dispatchers: PlaybackDispatchers? = null,
        closeDispatchers: Boolean = false,
    ): PlaybackCore {
        val clock = VirtualClock(testScheduler)
        return PlaybackCore(
            config = PlayerConfig(),
            backend = ScriptedBackend(),
            output = ScriptedOutput(clock, ScriptedSink()),
            dispatchers = dispatchers ?: PlaybackDispatchers.sharing(StandardTestDispatcher(testScheduler)),
            closeDispatchers = closeDispatchers,
            parent = parent,
            closeDeadline = closeDeadline,
        )
    }
}
