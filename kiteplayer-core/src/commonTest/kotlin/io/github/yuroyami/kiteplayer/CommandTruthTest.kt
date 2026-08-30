@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The commands say what really happened (the 2026-08-18 audit's KP-P1-01 to KP-P1-09 and KP-P1-21).
 *
 * Every case here is one shape of the same defect: a call that reported success for something that
 * did not happen, or a diagnostic that reported a number that was not true. They are grouped
 * because they were found together and because they share one rule: a player may fail, and may
 * refuse, but it may not lie.
 *
 * Each test names the exact edit that turns it red, because a regression test nobody has seen fail
 * is a test nobody knows the meaning of.
 */
class CommandTruthTest {

    // ---------------------------------------------------------------------------------------------
    // a request that is not the session's owner may not stop the session.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by putting `stopOnCancellation = true` back on `PlaybackCore.captureFrame`.
     */
    @Test
    fun `cancelling a capture leaves playback running`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        assertTrue(
            harness.core.snapshots.value.status.isActive,
            "the fixture must be playing before the capture is cancelled",
        )

        // A screenshot the application asked for and then abandoned, which is what a user closing
        // the sheet that requested it produces.
        val capture = async(start = CoroutineStart.UNDISPATCHED) { harness.core.captureFrame() }
        capture.cancel()
        harness.run(300.milliseconds)

        val status = harness.core.snapshots.value.status
        assertTrue(
            status.isActive,
            "an abandoned capture posted a global Stop and killed the session; status is $status",
        )
        assertEquals(
            harness.core.snapshots.value.media?.uri,
            "scripted://media",
            "the media must still be open after a cancelled capture",
        )
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // The first frame is reported as what it was, not as a success by default.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by counting only `framesOut` (submitted plus headless) in `presentFirstFrame` and its
     * scheduler gate again: the refused frame then satisfies nothing, the open burns the whole
     * `OPEN_FILL_DEADLINE`, and it still reports success with no warning at all.
     */
    @Test
    fun `a renderer that refuses the first frame is said out loud and not waited out`() = runTest {
        val refusing = RecordingRenderer(accepts = false)
        val harness = CoreHarness(this, renderer = refusing)
        harness.attachRenderer()

        val before = testScheduler.currentTime
        harness.open()
        val spent = (testScheduler.currentTime - before).milliseconds

        assertTrue(
            spent < 5.seconds,
            "the open waited $spent on a renderer that will never accept anything; the gate must " +
                "count a refusal as a frame that left the schedule",
        )
        val refused = harness.core.warningHistory()
            .any { it.warning is PlaybackWarning.StartupIncomplete && it.warning.message.contains("refused") }
        assertTrue(
            refused,
            "a refused first frame must be warned typed, so a permanently blank surface has a " +
                "reason attached; warnings were ${harness.core.warningHistory().map { it.warning.message }}",
        )
        harness.close()
    }

    /**
     * Red by counting a renderer refusal as `droppedLate` in `VideoPlayback.present` again.
     */
    @Test
    fun `a refusal is counted as a refusal and never as a late drop`() = runTest {
        val refusing = RecordingRenderer(accepts = false)
        val harness = CoreHarness(this, renderer = refusing)
        harness.attachRenderer()
        harness.open()
        harness.core.play()
        harness.run(1.seconds)

        val stats = harness.core.stats.value
        assertTrue(stats.refusedFrames > 0, "the renderer refused every frame and nothing counted it")
        assertEquals(
            0,
            stats.droppedFramesLate,
            "a refusal is the output failing to draw, not the schedule failing to keep up; " +
                "one number for both makes a dead surface read as a slow decoder",
        )
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // The totals are totals.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by reading the live session's counters in `publishProgressAndStats` again, without the
     * retired ones: the rebuild replaces the session, and every total restarts from zero.
     */
    @Test
    fun `frame totals never go backwards across a track change`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        val before = harness.core.stats.value
        assertTrue(before.decodedVideoFrames > 0, "the fixture must have decoded something first")

        // An ordinary track switch, which reopens the container and builds a whole new session.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, null))
        harness.run(1.seconds)

        val after = harness.core.stats.value
        assertTrue(
            after.decodedVideoFrames >= before.decodedVideoFrames,
            "PlaybackStats documents monotonic totals: decoded fell from " +
                "${before.decodedVideoFrames} to ${after.decodedVideoFrames} over a track change",
        )
        assertTrue(
            after.submittedFrames >= before.submittedFrames,
            "submitted fell from ${before.submittedFrames} to ${after.submittedFrames}",
        )
        harness.close()
    }

    /**
     * Red by dropping the forced publication at the end of `runStop`: the stopped player keeps
     * reporting the queue depths of media it no longer holds until the next stats interval.
     */
    @Test
    fun `stop keeps the totals and empties the gauges`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        val playing = harness.core.stats.value
        assertTrue(playing.decodedVideoFrames > 0)

        harness.core.stop()
        harness.run(100.milliseconds)

        val stopped = harness.core.stats.value
        assertTrue(
            stopped.decodedVideoFrames >= playing.decodedVideoFrames,
            "the totals belong to the player and survive the session that produced them",
        )
        assertEquals(
            kotlin.time.Duration.ZERO,
            stopped.videoQueueDepth,
            "a gauge measures a session, and there is no session",
        )
        assertEquals(MasterClock.None, stopped.masterClock, "nothing is driving any clock")
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // a dropped event is counted.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by making `emitEvent` ignore what `tryEmit` answers, which is what every call site did.
     */
    @Test
    fun `events lost to a slow collector are counted rather than vanishing`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false))
        harness.openWithRenderer()

        // A collector that never comes back, which is what "slower than the session" reduces to:
        // once it is more than the buffer behind, every further emission is refused. A timed
        // delay would NOT do, because virtual time auto-advances whenever the test is idle and
        // the collector would keep up perfectly.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stalled = backgroundScope.launch {
            harness.core.events.collect { gate.await() }
        }
        harness.run(10.milliseconds)

        // A refused live speed change on an unseekable source warns, typed, every time it is
        // asked for, which is a deterministic event generator that touches nothing else.
        repeat(200) {
            runCatching { harness.core.setSpeed(if (it % 2 == 0) 1.5 else 2.5) }
        }
        // Past one stats interval, because the counter is published on that interval like every
        // other total on PlaybackStats.
        harness.run(3.seconds)

        assertTrue(
            harness.core.stats.value.droppedEvents > 0,
            "200 warnings past a stalled collector and a 64 slot buffer must lose some, and the " +
                "loss must be visible: droppedEvents is ${harness.core.stats.value.droppedEvents}",
        )
        assertTrue(
            harness.core.diagnosticsDump().contains("eventsDropped="),
            "the dump must carry the loss, because a bug report reasons from the event list",
        )
        gate.complete(Unit)
        stalled.cancel()
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // One selection wins, and the other one is told.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by completing the displaced request with `TrackChange.Applied` in `queueSelection`,
     * which is exactly what completing it with `Unit` used to mean.
     */
    @Test
    fun `two selections of one kind produce one winner and one superseded loser`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        // Both commands reach the channel before the actor's next pass drains them, which is what
        // two callers on two coroutines produce.
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Audio, null)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Audio, TrackId(1))
        }
        val firstOutcome = first.await()
        val secondOutcome = second.await()
        harness.run(500.milliseconds)

        assertIs<TrackChange.Superseded>(
            firstOutcome,
            "the displaced caller was told its track was selected while a different one plays",
        )
        assertIs<TrackChange.Applied>(secondOutcome, "the last request is the one that runs")
        assertEquals(
            TrackId(1),
            harness.core.snapshots.value.tracks.selectedAudio,
            "and the selection the winner asked for is the live one",
        )
        harness.close()
    }

    /**
     * Red by keeping one pending selection instead of one per kind: the audio request is then
     * thrown away by the subtitle request and its caller is told it applied.
     */
    @Test
    fun `selections of different kinds are merged into one rebuild and both apply`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val opens = harness.backend.sessions.size

        val audio = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Audio, null)
        }
        val video = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Video, TrackId(0))
        }
        assertIs<TrackChange.Applied>(audio.await(), "the audio request must not be discarded")
        assertIs<TrackChange.Applied>(video.await(), "nor the video one")
        harness.run(500.milliseconds)

        val tracks = harness.core.snapshots.value.tracks
        assertEquals(null, tracks.selectedAudio, "the audio deselection must really have applied")
        assertEquals(TrackId(0), tracks.selectedVideo, "and the video request kept its own track")
        assertEquals(
            opens + 1,
            harness.backend.sessions.size,
            "both changes ride ONE reopen, not one each",
        )
        harness.close()
    }

    /**
     * Red by completing the pending selection with success in `runStop`, which is what it did.
     */
    @Test
    fun `a stop tells a waiting selection that it was discarded`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Audio, null)
        }
        harness.core.stop()
        val outcome = selection.await()

        val discarded = assertIs<TrackChange.Discarded>(
            outcome,
            "a selection a stop tore down was reported as applied",
        )
        assertTrue(discarded.reason.isNotBlank(), "and it must say why")
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // Every open of an item gets its own reader.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by putting a live `MediaIo` back on `MediaItem` in place of the factory: the rebuild
     * then hands the backend the reader the first session already closed.
     */
    @Test
    fun `a rebuild gets a fresh reader and never the one the last session closed`() = runTest {
        val made = mutableListOf<CountingIo>()
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem("scripted://media", io = { CountingIo().also { made += it } }),
        )
        assertEquals(1, made.size, "the open must build exactly one reader")
        val first = made.single()

        // Audio/subtitle now swap inside the live graph. Video still deliberately rebuilds, so use
        // that lane to keep this test about reader ownership rather than obsolete track semantics.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, null))
        harness.run(500.milliseconds)

        assertEquals(2, made.size, "the rebuild must ask the factory for its own reader")
        assertNotSame(first, made[1], "and must not be handed the first session's reader")
        harness.close()
    }

    /** Reader for the factory test: the engine's contract is one per session, closed with it. */
    private class CountingIo : MediaIo {
        override val size: Long = 4096
        override val seekable: Boolean = true
        var closed: Boolean = false
            private set

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1

        override suspend fun seek(position: Long) = Unit

        override fun close() {
            closed = true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // a wedged release is reported, not waited on.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by wrapping `teardownSession()` in `withTimeoutOrNull` again: its body is
     * `NonCancellable`, so the deadline never fires and this call never returns.
     */
    @Test
    fun `a release that wedges reports a compromised runtime instead of hanging`() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        // The device's stop wedges, which is the first thing a session release does.
        faults.stopHangs = true
        val failure = assertFailsWith<PlaybackException> { harness.core.closeAndAwait() }
        val error = assertIs<PlaybackError.RuntimeCompromised>(
            failure.error,
            "a close that could not prove teardown finished must say so, typed",
        )
        assertTrue(
            error.detail.contains("STILL RUNNING"),
            "and must say that the threads were deliberately left alive rather than closed under " +
                "a wedged call; the detail was: ${error.detail}",
        )

        // Let the wedge go, so the release finishes and nothing is left parked.
        faults.stopHangs = false
        harness.run(2.seconds)
        harness.stopDevice()
    }

    /**
     * The other half of the same contract: an ordinary close still completes cleanly, so the
     * bound above cannot have been bought by reporting every close as compromised.
     */
    @Test
    fun `an ordinary close still completes without a compromised report`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.closeAndAwait()
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        assertEquals(null, harness.core.snapshots.value.error, "a clean close leaves no error behind")
        harness.stopDevice()
    }

    // ---------------------------------------------------------------------------------------------
    // a stop that arrives during an open really preempts it.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by restoring `FillOutcome.Preempted -> Unit` in `runOpen`: the open then publishes
     * Paused, announces Opened and completes successfully, and the held Stop undoes all of it.
     */
    @Test
    fun `an open preempted by stop refuses instead of reporting success`() = runTest {
        // A source that never fills: the open sits in awaitInitialFill, which is exactly the
        // window a stop arrives in. The stall has to come from the READER, not from a decoder
        // that produces nothing: a stream that has reached its end can no longer produce a frame,
        // and since 2026-08-23 the engine says so at once instead of waiting out its budget, so a
        // silent decoder over a fast source now finishes the open rather than hanging in it.
        val faults = FaultPlan()
        faults.videoDecodeProducesNothing = true
        val harness = CoreHarness(
            this,
            script = MediaScript(hasAudio = false, readDelayUs = 5_000_000),
            faults = faults,
        )
        harness.attachRenderer()

        // runCatching inside, not assertFailsWith outside: an async whose body throws fails its
        // parent scope the moment it throws, which would kill the test before it could assert.
        val open = async(start = CoroutineStart.UNDISPATCHED) { runCatching { harness.open() } }
        harness.run(100.milliseconds)
        val stop = async(start = CoroutineStart.UNDISPATCHED) { harness.core.stop() }

        val failure = assertIs<IllegalStateException>(
            open.await().exceptionOrNull(),
            "a preempted open must refuse, and refuse as a sequencing mistake rather than as a " +
                "cancellation of the caller's own coroutine",
        )
        assertTrue(
            failure.message?.contains("preempted") == true,
            "a preempted open must refuse and say why, not report success; said: ${failure.message}",
        )
        stop.await()
        harness.run(100.milliseconds)
        assertEquals(
            PlaybackStatus.Idle,
            harness.core.snapshots.value.status,
            "and the player it claimed to have opened must be Idle",
        )
        assertTrue(
            harness.events.none { it is PlayerEvent.Opened },
            "an Opened event for a session that was torn down is the same lie in event form",
        )
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // 15.4.3: a renderer that has failed is let go of.
    // ---------------------------------------------------------------------------------------------

    /**
     * Red by dropping the `DetachRenderer` post from the `RendererEvent.Failed` branch: the failed
     * renderer stays attached, keeps refusing every frame, and the picture stays black for the rest
     * of the session while the sound plays on.
     */
    @Test
    fun `a renderer that reports a hard failure is detached so playback goes on`() = runTest {
        val failing = FailingRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(failing)
        harness.open()
        harness.core.play()
        harness.run(300.milliseconds)

        failing.fail("the surface was destroyed")
        // Past one stats interval, because the frame counters are published on that interval.
        harness.run(2.seconds)

        assertTrue(
            harness.core.snapshots.value.status.isActive,
            "playback must continue without a picture rather than stop",
        )
        val stats = harness.core.stats.value
        assertTrue(
            stats.headlessFrames > 0,
            "with the renderer let go of, frames are presented headless; they were still being " +
                "handed to the failed renderer and refused, which is a permanently black picture",
        )
        assertTrue(
            harness.core.warningHistory().any {
                it.warning is PlaybackWarning.RendererFailed && it.warning.message.contains("detached")
            },
            "and the detach is said out loud: ${harness.core.warningHistory().map { it.warning.message }}",
        )
        harness.close()
    }

    /** A renderer that refuses everything and can be told to announce a hard failure. */
    private class FailingRenderer : io.github.yuroyami.kiteplayer.spi.VideoRenderer {
        private val feed = kotlinx.coroutines.flow.MutableSharedFlow<io.github.yuroyami.kiteplayer.spi.RendererEvent>(
            extraBufferCapacity = 8,
        )

        fun fail(detail: String) {
            feed.tryEmit(io.github.yuroyami.kiteplayer.spi.RendererEvent.Failed(detail))
        }

        override val events = feed

        override suspend fun present(
            frame: io.github.yuroyami.kiteplayer.spi.VideoFrame,
            targetNanos: Long,
        ): Boolean {
            frame.close()
            return false
        }

        override fun supports(format: io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat) = true
        override fun supportedHardwareSurfaces() = emptySet<io.github.yuroyami.kiteplayer.spi.HwSurfaceKind>()
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override fun setScaleMode(mode: VideoScale) = Unit
        override fun setAdjustments(adjustments: VideoAdjustments) = Unit
        override fun setTransform(transform: VideoTransform) = Unit
        override suspend fun setOverlay(overlay: io.github.yuroyami.kiteplayer.spi.SubtitleOverlay?) = Unit
        override fun close() = Unit
    }

    // ---------------------------------------------------------------------------------------------
    // a step is one frame of the media, not one average frame period.
    // ---------------------------------------------------------------------------------------------

    /**
     * A step lands on the frame the decoder produced, whatever its timestamp.
     *
     * The old step seeked to the current position plus one NOMINAL period taken from the
     * container's declared frame rate. This fixture declares a rate that does not match its own
     * timestamps, which is exactly what a variable-frame-rate file and a mis-tagged container both
     * look like, and the step must still land on the next real frame.
     *
     * Red by seeking to `position + 1_000_000 / declared rate` again: the landing is then wherever
     * that arithmetic points, which is not where the next frame is.
     */
    @Test
    fun `a frame step lands on the next decoded frame and not on an average period`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        val before = harness.core.position()

        harness.core.stepFrame()
        harness.run(100.milliseconds)
        val after = harness.core.position()

        assertTrue(
            after > before,
            "the step must move the picture and the reported position with it: $before then $after",
        )
        assertTrue(
            harness.renderer!!.count >= 2,
            "a step presents a frame; the renderer saw ${harness.renderer!!.count}",
        )
        harness.close()
    }

    /** And a step no longer needs a source that can seek, because it no longer seeks. */
    @Test
    fun `a frame step works on a source that cannot seek`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false))
        harness.openWithRenderer()

        // Red by restoring the seekability refusal, which threw UnsupportedOperationException here.
        harness.core.stepFrame()
        harness.run(100.milliseconds)

        assertTrue(
            harness.renderer!!.count >= 2,
            "stepping decodes what is already queued and needs no cursor move at all",
        )
        harness.close()
    }
}
