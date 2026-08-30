package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.StatusMachine
import io.github.yuroyami.kiteplayer.internal.platformPlaybackDispatchers
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
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
import kotlin.test.assertSame
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
            "handleQueueAdvance",
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
    fun `an attached renderer decoder factory wins over the backend`() = runTest {
        val script = MediaScript()
        val ledger = LeakLedger()
        val selectedStatus = HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec)
        val factory = RecordingVideoDecoderFactory { _, _ ->
            ScriptedVideoDecoder(
                script = script,
                ledger = ledger,
                faults = FaultPlan.None,
                hardwareStatus = ScriptedVideoDecoderStatus(selectedStatus),
            )
        }
        val renderer = RecordingRenderer(decoderFactories = listOf(factory))
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(
                hardwareDecode = HwdecPolicy.Require,
                statsInterval = 10.milliseconds,
            ),
            ledger = ledger,
            renderer = renderer,
        )

        harness.openWithRenderer()
        harness.run(50.milliseconds)

        assertEquals(1, factory.createCount)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Require), factory.policies)
        assertEquals(selectedStatus, harness.core.stats.value.hardwareDecode)
        assertEquals(TrackId(script.videoIndex), harness.core.snapshots.value.tracks.selectedVideo)
        harness.close()
    }

    @Test
    fun `the backend is tried when an attached renderer factory fails`() = runTest {
        val factory = RecordingVideoDecoderFactory { _, _ ->
            error("renderer surface decoder unavailable")
        }
        val renderer = RecordingRenderer(decoderFactories = listOf(factory))
        val harness = CoreHarness(
            scope = this,
            config = PlayerConfig(statsInterval = 10.milliseconds),
            renderer = renderer,
        )
        val backendStatus = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)
        harness.backend.videoDecoderStatus.value = backendStatus

        harness.openWithRenderer()
        harness.run(50.milliseconds)

        assertEquals(1, factory.createCount)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Auto), factory.policies)
        assertEquals(backendStatus, harness.core.stats.value.hardwareDecode)
        assertEquals(TrackId(0), harness.core.snapshots.value.tracks.selectedVideo)
        assertEquals(1, renderer.count, "the backend decoder still supplied the initial frame")
        assertEquals(
            emptyList(),
            harness.events
                .filterIsInstance<PlayerEvent.Warning>()
                .map { it.warning }
                .filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>(),
            "Auto candidate refusal continues silently when the next candidate succeeds",
        )
        harness.close()
    }

    @Test
    fun `cancellation at decoder dispatcher handoff closes the created decoder`() = runTest {
        val parent = Job()
        val script = MediaScript()
        val ledger = LeakLedger()
        var decoderCloseCount = 0
        val factory = RecordingVideoDecoderFactory { _, _ ->
            val delegate = ScriptedVideoDecoder(
                script = script,
                ledger = ledger,
                faults = FaultPlan.None,
                hardwareStatus = ScriptedVideoDecoderStatus(
                    HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec),
                ),
            )
            val decoder = object : VideoDecoder by delegate {
                override fun close() {
                    decoderCloseCount++
                    delegate.close()
                }
            }
            // The decoder exists on its owning dispatcher, but the actor is cancelled before
            // withContext can deliver the result. The acquisition helper must retain local ownership.
            parent.cancel()
            decoder
        }
        val renderer = RecordingRenderer(decoderFactories = listOf(factory))
        val backend = ScriptedBackend(script = script, ledger = ledger)
        val sessionDispatcher = StandardTestDispatcher(testScheduler, "session")
        val decodeDispatcher = StandardTestDispatcher(testScheduler, "video-decode")
        val sharedDispatcher = StandardTestDispatcher(testScheduler, "other-workers")
        val dispatchers = object : PlaybackDispatchers {
            override val session = sessionDispatcher
            override val demux = sharedDispatcher
            override val videoDecode = decodeDispatcher
            override val audioDecode = sharedDispatcher
            override val audioFeed = sharedDispatcher
            override val videoSchedule = sharedDispatcher
            override val raster = sharedDispatcher
            override fun close() = Unit
        }
        val core = PlaybackCore(
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            backend = backend,
            output = ScriptedOutput(VirtualClock(testScheduler), ScriptedSink()),
            dispatchers = dispatchers,
            closeDispatchers = false,
            parent = parent,
        )
        core.attachRenderer(renderer)

        val opening = async { runCatching { core.open(MediaItem("scripted://handoff-cancel")) } }
        opening.await()
        parent.join()

        assertEquals(1, decoderCloseCount)
        assertEquals(1, backend.sessions.single().closeCount)
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `auto reopens in backend software when the backend hardware decoder dies mid-play`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val faults = FaultPlan().apply { videoDecodeFailsAfterFrames = 12 }
        val harness = CoreHarness(
            scope = this,
            script = script,
            faults = faults,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto, statsInterval = 10.milliseconds),
        )
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)

        harness.openWithRenderer()
        val openedGeneration = harness.core.snapshots.value.generation
        harness.core.play()
        harness.run(1.seconds)

        val snapshot = harness.core.snapshots.value
        assertTrue(
            snapshot.status != PlaybackStatus.Failed,
            "a backend hardware failure must recover, not fail the player: ${snapshot.error}",
        )
        assertTrue(snapshot.generation.value > openedGeneration.value, "recovery did not fence a new generation")
        assertEquals(2, harness.backend.openCalls, "recovery must reopen the media exactly once")
        assertEquals(
            listOf<HwdecPolicy>(HwdecPolicy.Off),
            harness.backend.sessions.last().videoDecoderPolicies,
            "the replacement decoder must be backend-only and forced software",
        )
        assertEquals(HwdecStatus.Software, harness.core.stats.value.hardwareDecode)
        assertTrue(harness.core.progress.value.position > Duration.ZERO, "playback did not resume")
        val warnings = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>()
        assertEquals(1, warnings.size, "exactly one hardware-unavailable warning is owed: $warnings")
        assertTrue(warnings.single().reason.contains("receive failed"), warnings.single().reason)
        harness.close()
    }

    @Test
    fun `auto reopens in backend software after queued direct frames fail`() = runTest {
        for (policy in listOf<HwdecPolicy>(HwdecPolicy.Auto)) {
            val script = MediaScript(durationUs = 4_000_000)
            val hardwareFrames = LeakLedger()
            val factory = RecordingVideoDecoderFactory { _, _ ->
                queuedRendererDecoderFailingOnReceive(script, hardwareFrames, failOutputAt = 12)
            }
            val renderer = RecordingRenderer(decoderFactories = listOf(factory))
            val harness = CoreHarness(
                scope = this,
                script = script,
                config = PlayerConfig(hardwareDecode = policy, statsInterval = 10.milliseconds),
                renderer = renderer,
            )

            harness.openWithRenderer()
            val openedGeneration = harness.core.snapshots.value.generation
            harness.core.play()
            harness.run(1.seconds)

            val snapshot = harness.core.snapshots.value
            assertTrue(snapshot.status != PlaybackStatus.Failed, "recovery failed for $policy: ${snapshot.error}")
            assertTrue(snapshot.generation.value > openedGeneration.value, "recovery did not fence a new generation")
            assertEquals(2, harness.backend.openCalls, "recovery must reopen the media exactly once")
            assertEquals(emptyList(), harness.backend.sessions.first().videoDecoderPolicies)
            assertEquals(
                listOf<HwdecPolicy>(HwdecPolicy.Off),
                harness.backend.sessions.last().videoDecoderPolicies,
                "the replacement decoder must be backend-only and forced software",
            )
            assertEquals(HwdecStatus.Software, harness.core.stats.value.hardwareDecode)
            assertEquals(TrackId(script.videoIndex), snapshot.tracks.selectedVideo)
            assertEquals(TrackId(script.audioIndex), snapshot.tracks.selectedAudio)
            assertTrue(harness.backend.sessions.last().scriptedSource.seeks > 0, "the new source was not repositioned")
            assertTrue(harness.core.progress.value.position > Duration.ZERO, "playback did not resume")
            assertEquals(0, hardwareFrames.liveCount, "queued direct frames outlived their failed codec")
            assertTrue(hardwareFrames.closeCount >= 4, "the failure did not occur with direct frames queued")

            val firstRecovered = renderer.presentations.indexOfFirst { it.generation == snapshot.generation }
            assertTrue(firstRecovered >= 0, "the recovered generation presented no frame")
            assertTrue(
                renderer.presentations.drop(firstRecovered).all { it.generation == snapshot.generation },
                "a stale direct frame crossed the rebuilt-session fence: ${renderer.presentations}",
            )
            val warnings = harness.events
                .filterIsInstance<PlayerEvent.Warning>()
                .map { it.warning }
                .filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>()
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().reason.contains("receive failed"), warnings.single().reason)

            harness.close()
            assertEquals(0, harness.ledger.liveCount)
            assertEquals(0, hardwareFrames.liveCount)
        }
    }

    @Test
    fun `auto recovers during initial fill and ignores the stale failed-session outcome`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val hardwareFrames = LeakLedger()
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(script, hardwareFrames, failOutputAt = 3)
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()
        harness.run(200.milliseconds)

        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertEquals(2, harness.backend.openCalls)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Off), harness.session.videoDecoderPolicies)
        assertTrue(harness.core.snapshots.value.generation.value > Generation.Initial.value)
        assertNull(harness.core.snapshots.value.error, "the queued old WorkerOutcome killed the rebuilt session")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `track selection queued with decoder failure is applied by the software reopen`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val hardwareFrames = LeakLedger()
        lateinit var harness: CoreHarness
        var selectionCompleted = false
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(
                script = script,
                hardwareFrames = hardwareFrames,
                failOutputAt = 12,
                onBeforeFailure = {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        harness.core.selectTrack(TrackKind.Audio, null)
                        selectionCompleted = true
                    }
                },
            )
        }
        harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        assertTrue(selectionCompleted, "the selectTrack reply was left pending during recovery")
        assertEquals(2, harness.backend.openCalls, "the requested track must be folded into the recovery reopen")
        assertNull(harness.core.snapshots.value.tracks.selectedAudio)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Off), harness.session.videoDecoderPolicies)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `video deselection queued with decoder failure becomes a valid audio only recovery`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val hardwareFrames = LeakLedger()
        lateinit var harness: CoreHarness
        var selectionCompleted = false
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(
                script = script,
                hardwareFrames = hardwareFrames,
                failOutputAt = 12,
                onBeforeFailure = {
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        harness.core.selectTrack(TrackKind.Video, null)
                        selectionCompleted = true
                    }
                },
            )
        }
        harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        assertTrue(selectionCompleted)
        assertEquals(2, harness.backend.openCalls)
        assertNull(harness.core.snapshots.value.tracks.selectedVideo)
        assertEquals(TrackId(script.audioIndex), harness.core.snapshots.value.tracks.selectedAudio)
        assertTrue(harness.core.snapshots.value.status != PlaybackStatus.Failed)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `startup recovery for a new item never inherits the ended item position`() = runTest {
        val script = MediaScript(durationUs = 800_000)
        val hardwareFrames = LeakLedger()
        var creation = 0
        val factory = RecordingVideoDecoderFactory { _, _ ->
            creation++
            queuedRendererDecoderFailingOnReceive(
                script = script,
                hardwareFrames = hardwareFrames,
                failOutputAt = if (creation == 1) Int.MAX_VALUE else 3,
            )
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )
        harness.openWithRenderer("scripted://first")
        harness.core.play()
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)

        harness.openWithRenderer("scripted://second")

        assertEquals(3, harness.backend.openCalls)
        assertEquals(listOf(0L), harness.backend.sessions.last().scriptedSource.seekTargets)
        assertTrue(harness.core.position() < 200.milliseconds, "the second item inherited the first item's end position")
        harness.close()
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `live audio track change after recovery stays backend software only`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val hardwareFrames = LeakLedger()
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(script, hardwareFrames, failOutputAt = 12)
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(2, harness.backend.openCalls)

        harness.core.selectTrack(TrackKind.Audio, null)
        harness.run(200.milliseconds)

        assertEquals(2, harness.backend.openCalls, "the live audio change reopened the recovered graph")
        assertEquals(1, factory.createCount, "a live audio change reselected renderer hardware")
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Off), harness.session.videoDecoderPolicies)
        assertEquals(HwdecStatus.Software, harness.core.stats.value.hardwareDecode)
        assertNull(harness.core.snapshots.value.tracks.selectedAudio)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `decoder failure during precise seek reopens software and completes the user seek once`() = runTest {
        val script = MediaScript(durationUs = 4_000_000)
        val hardwareFrames = LeakLedger()
        val factory = RecordingVideoDecoderFactory { _, _ ->
            rendererDecoderFailingOnFirstFlush(script, hardwareFrames)
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )
        harness.openWithRenderer()

        val result = assertIs<SeekResult.Applied>(harness.core.seek(Pts(2_000_000), SeekMode.Precise))
        harness.run(100.milliseconds)

        assertTrue(result.landedAt.micros >= 2_000_000)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertEquals(2, harness.backend.openCalls)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Off), harness.session.videoDecoderPolicies)
        assertEquals(
            1,
            harness.events.filterIsInstance<PlayerEvent.SeekCompleted>().size,
            "internal recovery must not publish a second public seek completion",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `software reopen refusal after renderer failure is terminal`() = runTest {
        val script = MediaScript(durationUs = 4_000_000, hasAudio = false)
        val hardwareFrames = LeakLedger()
        val faults = FaultPlan().also { it.videoDecodersRefuse = true }
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(script, hardwareFrames, failOutputAt = 12)
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            faults = faults,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        val error = assertIs<PlaybackError.DecoderFailed>(harness.core.snapshots.value.error)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertTrue(error.detail.contains("reopening with backend software failed"), error.detail)
        assertEquals(2, harness.backend.openCalls)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Off), harness.backend.sessions.last().videoDecoderPolicies)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `require keeps renderer runtime failure terminal`() = runTest {
        val script = MediaScript(durationUs = 4_000_000, hasAudio = false)
        val hardwareFrames = LeakLedger()
        val factory = RecordingVideoDecoderFactory { _, _ ->
            queuedRendererDecoderFailingOnReceive(script, hardwareFrames, failOutputAt = 12)
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertIs<PlaybackError.DecoderFailed>(harness.core.snapshots.value.error)
        assertEquals(1, harness.backend.openCalls, "Require must not open a software replacement session")
        assertEquals(emptyList(), harness.backend.sessions.single().videoDecoderPolicies)
        harness.close()
        assertEquals(0, hardwareFrames.liveCount)
    }

    @Test
    fun `nonseekable auto skips renderer hardware`() = runTest {
        val script = MediaScript(seekable = false)
        val factory = RecordingVideoDecoderFactory { _, _ ->
            error("a nonseekable Auto source must not create renderer-coupled hardware")
        }
        val harness = CoreHarness(
            scope = this,
            script = script,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )

        harness.openWithRenderer()

        assertEquals(0, factory.createCount)
        assertEquals(listOf<HwdecPolicy>(HwdecPolicy.Auto), harness.session.videoDecoderPolicies)
        assertEquals(HwdecStatus.Software, harness.core.stats.value.hardwareDecode)
        harness.close()
    }

    @Test
    fun `transient decoder readiness with no output is retried`() = runTest {
        val script = MediaScript()
        val ledger = LeakLedger()
        var readinessMisses = 3
        val factory = RecordingVideoDecoderFactory { _, _ ->
            val delegate = ScriptedVideoDecoder(script, ledger, FaultPlan.None)
            object : VideoDecoder by delegate {
                override suspend fun send(packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket?): Boolean {
                    if (packet != null && readinessMisses > 0) {
                        readinessMisses--
                        return false
                    }
                    return delegate.send(packet)
                }
            }
        }
        val renderer = RecordingRenderer(decoderFactories = listOf(factory))
        val harness = CoreHarness(scope = this, script = script, ledger = ledger, renderer = renderer)

        harness.openWithRenderer()

        assertEquals(0, readinessMisses)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertEquals(1, renderer.count, "the decoder eventually supplied the initial frame")
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
        assertEquals(
            emptyList(),
            harness.events
                .filterIsInstance<PlayerEvent.Warning>()
                .map { it.warning }
                .filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>(),
            "Auto refusal is a decoder-selection result, not a hardware warning",
        )
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
    fun `replacing the media with a renderer attached primes the new session instead of waiting out the deadlines`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)
        harness.core.pause()
        harness.run(100.milliseconds)

        // What loading a second file actually looks like from a caller: stop, then open. The renderer
        // stays attached across it, because it belongs to the view and not to the session.
        val startedAt = harness.scheduler.currentTime
        harness.core.stop()
        harness.core.open(MediaItem("scripted://second"))
        val elapsed = harness.scheduler.currentTime - startedAt

        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertTrue(
            elapsed < 2_000,
            "the replacement open took ${elapsed}ms: a second file must prime its pipeline like the " +
                "first, not sit out the initial-fill and first-frame deadlines",
        )
        harness.close()
    }

    @Test
    fun `a second open after a seek primes its pipeline instead of discarding every frame`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)

        // The one thing the sibling test above never does. A seek moves the player's epoch, and
        // everything the NEXT session is built from has to be aligned to that epoch, not just the
        // packet queues: a schedule left at the initial generation rejects every frame the new
        // session decodes as stale, so nothing is ever presented (owner report 2026-08-22).
        harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        harness.core.pause()
        harness.run(100.milliseconds)

        val presentedBefore = harness.renderer!!.count
        val startedAt = harness.scheduler.currentTime
        harness.core.stop()
        harness.core.open(MediaItem("scripted://second"))
        val elapsed = harness.scheduler.currentTime - startedAt
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        assertTrue(
            elapsed < 2_000,
            "the replacement open took ${elapsed}ms: a seek before it must not cost the new session " +
                "the initial-fill and first-frame deadlines",
        )
        assertTrue(
            harness.renderer.count > presentedBefore,
            "the open after a seek presented no frame at all: the new session decoded into a " +
                "schedule still at the initial generation, so every frame was discarded as stale",
        )
        harness.close()
    }

    @Test
    fun `a play issued after a paused seek reports Buffering while the pipeline refills`() = runTest {
        // A slow source: every packet read costs 10ms, so the queues a seek flushed refill over
        // most of a virtual second instead of instantly, which is what a phone reading a long-GOP
        // file looks like (owner report 2026-08-25, iPhone XS). Slow enough that the refill window
        // is real, fast enough that steady playback holds once it starts.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 120_000_000, readDelayUs = 10_000),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)
        harness.core.pause()
        harness.run(100.milliseconds)

        // Pause, drag the bar, then tap play while the flushed pipeline is still refilling. The
        // status must acknowledge the intent at once: Buffering is by its own definition "the user
        // asked for playback and the engine cannot supply it", and a silent Paused here is what
        // made every tap on the device look ignored.
        harness.core.seek(Pts(2_000_000), SeekMode.Precise)
        harness.core.play()
        // One actor pass: the acknowledgment is level-triggered in the restart handler, not a
        // property of the play command itself. 50ms is far below the seconds the refill takes.
        harness.run(50.milliseconds)
        val acknowledged = harness.core.snapshots.value.status
        assertTrue(
            acknowledged == PlaybackStatus.Buffering || acknowledged == PlaybackStatus.Playing,
            "a play against a refilling pipeline reported $acknowledged: the intent was accepted " +
                "but nothing visible changed, so the caller cannot tell the tap from a dropped one",
        )

        // Level-triggered as before: the restart honours the play the moment the streams are ready.
        harness.run(10.seconds)
        assertEquals(
            PlaybackStatus.Playing,
            harness.core.snapshots.value.status,
            "the play issued during the refill was never honoured",
        )
        harness.close()
    }

    @Test
    fun `a seek past the end settles at the end instead of waiting out the startup budget`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        // What a shared-playlist consumer does when the room's position belongs to a LONGER file
        // than the one just opened (owner report 2026-08-23).
        harness.core.seekLater(Pts(60_000_000), SeekMode.KeyframeThenRefine)

        // The position may never name a time the media does not have, not even while the request
        // is still in flight and the mask is answering for it.
        val duration = harness.core.snapshots.value.duration
        assertNotNull(duration)
        assertTrue(
            harness.core.position() <= duration,
            "position reported ${harness.core.position()} on media that is only $duration long",
        )

        // A frame at or after the end cannot exist, so nothing may wait for one: the pipeline is
        // already at end of stream and the engine can say so at once.
        harness.run(1.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "a seek to the end sat in ${harness.core.snapshots.value.status} instead of ending: the " +
                "first-frame push waited out its whole budget for a frame past the end",
        )
        assertTrue(
            harness.core.position() <= duration,
            "position settled at ${harness.core.position()} past the end",
        )
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
            2,
            harness.core.seekFlushCycles,
            "twenty requests in one pass are ONE merged seek; its KeyframeThenRefine mode runs " +
                "that one seek's two phases (the keyframe landing, then the exact one), so " +
                "exactly two flush cycles and never twenty",
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
    fun `actor parent cancellation tears down an installed session before it returns`() = runTest {
        val parent = Job()
        val harness = CoreHarness(scope = this, parent = parent)
        harness.open()

        parent.cancelAndJoin()
        harness.stopDevice()

        assertEquals(1, harness.session.closeCount, "the backend session survived parent cancellation")
        assertTrue(harness.sink.closed, "the audio path survived parent cancellation")
        assertEquals(0, harness.ledger.liveCount, "frames or packets survived parent cancellation")
    }

    @Test
    fun `an expired close budget still releases the installed graph`() = runTest {
        val clock = VirtualClock(testScheduler)
        val ledger = LeakLedger()
        val backend = ScriptedBackend(ledger = ledger)
        val sink = ScriptedSink()
        val core = PlaybackCore(
            config = PlayerConfig(),
            backend = backend,
            output = ScriptedOutput(clock, sink),
            dispatchers = PlaybackDispatchers.sharing(StandardTestDispatcher(testScheduler)),
            closeDispatchers = false,
            parent = backgroundScope.coroutineContext[Job],
            closeDeadline = Duration.ZERO,
        )
        core.open(MediaItem("scripted://close-budget"))

        val failure = assertFailsWith<PlaybackException> { core.closeAndAwait() }

        assertTrue(failure.error is PlaybackError.RuntimeCompromised)
        assertEquals(1, backend.sessions.single().closeCount)
        assertTrue(sink.closed)
        assertEquals(0, ledger.liveCount)
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
            override val raster = context

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
    fun `audio deselection stays live on a source that cannot seek`() = runTest {
        val fixed = CoreHarness(this, script = MediaScript(seekable = false))
        fixed.openWithRenderer()
        val source = fixed.source

        assertIs<TrackChange.Applied>(fixed.core.selectTrack(TrackKind.Audio, null))

        assertNull(fixed.core.snapshots.value.tracks.selectedAudio)
        assertEquals(1, fixed.backend.openCalls, "the live audio transaction reopened the source")
        assertSame(source, fixed.source, "the live audio transaction replaced the source")
        assertEquals(0, source.seeks, "the live audio transaction tried to reposition the source")
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
    fun `a stale identity detach is a no-op and the newer renderer keeps its frames`() = runTest {
        val harness = CoreHarness(this, renderer = null)
        harness.open()
        harness.core.play()
        val old = RecordingRenderer()
        harness.core.attachRenderer(old)
        harness.run(200.milliseconds)

        val new = RecordingRenderer()
        harness.core.attachRenderer(new)
        harness.core.detachRenderer(expected = old)
        harness.run(300.milliseconds)

        assertTrue(new.count > 0, "a stale detach must not remove the newer renderer")
        harness.close()
    }

    @Test
    fun `a matching identity detach detaches and fences`() = runTest {
        val harness = CoreHarness(this, renderer = null)
        harness.open()
        harness.core.play()
        val renderer = RecordingRenderer()
        harness.core.attachRenderer(renderer)
        harness.run(200.milliseconds)

        harness.core.detachRenderer(expected = renderer)
        val atDetach = renderer.count
        harness.run(300.milliseconds)
        assertEquals(atDetach, renderer.count, "a matching identity detach stops submissions")
        harness.close()
    }

    @Test
    fun `replacing a coupled renderer rebuilds the video path against the new one`() = runTest {
        val script = MediaScript()
        val ledger = LeakLedger()
        val factoryA = coupledFactory(script, ledger)
        val rendererA = RecordingRenderer(decoderFactories = listOf(factoryA))
        val harness = CoreHarness(
            scope = this,
            script = script,
            ledger = ledger,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
            renderer = rendererA,
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)
        assertEquals(1, harness.backend.openCalls)
        assertEquals(1, factoryA.createCount)

        val factoryB = coupledFactory(script, ledger)
        val rendererB = RecordingRenderer(decoderFactories = listOf(factoryB))
        harness.core.attachRenderer(rendererB)
        harness.run(500.milliseconds)

        assertEquals(2, harness.backend.openCalls, "the coupled swap reopened the source exactly once")
        assertEquals(1, factoryB.createCount, "the rebuild selected the new renderer's decoder")
        assertTrue(rendererB.count > 0, "the new renderer receives frames after the rebuild")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `reattaching the same coupled renderer does not rebuild`() = runTest {
        val script = MediaScript()
        val ledger = LeakLedger()
        val factory = coupledFactory(script, ledger)
        val renderer = RecordingRenderer(decoderFactories = listOf(factory))
        val harness = CoreHarness(
            scope = this,
            script = script,
            ledger = ledger,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
            renderer = renderer,
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.detachRenderer(expected = renderer)
        harness.core.attachRenderer(renderer)
        harness.run(300.milliseconds)

        assertEquals(1, harness.backend.openCalls, "the same coupled renderer must not force a reopen")
        assertEquals(1, factory.createCount)
        harness.close()
    }

    @Test
    fun `a backend-origin swap does not rebuild and keeps frames flowing`() = runTest {
        val harness = CoreHarness(this, renderer = null)
        harness.open()
        harness.core.play()
        val first = RecordingRenderer()
        harness.core.attachRenderer(first)
        harness.run(200.milliseconds)

        val second = RecordingRenderer()
        harness.core.attachRenderer(second)
        harness.run(300.milliseconds)

        assertEquals(1, harness.backend.openCalls, "portable frames need no reopen")
        assertTrue(second.count > 0, "the second renderer receives frames immediately")
        harness.close()
    }

    @Test
    fun `a paused swap repaints one frame on the new renderer`() = runTest {
        val harness = CoreHarness(this, renderer = null)
        harness.open()
        val first = RecordingRenderer()
        harness.core.attachRenderer(first)
        harness.core.play()
        harness.run(300.milliseconds)
        harness.core.pause()
        harness.run(100.milliseconds)

        val second = RecordingRenderer()
        harness.core.attachRenderer(second)
        harness.run(500.milliseconds)

        assertTrue(second.count > 0, "the paused swap must hand the new renderer a picture")
        assertEquals(1, harness.backend.openCalls, "the repaint is a seek, not a reopen")
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `a coupled swap on an unseekable source warns and stays put`() = runTest {
        val script = MediaScript(seekable = false)
        val ledger = LeakLedger()
        val factoryA = coupledFactory(script, ledger)
        val rendererA = RecordingRenderer(decoderFactories = listOf(factoryA))
        val harness = CoreHarness(
            scope = this,
            script = script,
            ledger = ledger,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
            renderer = rendererA,
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        val rendererB = RecordingRenderer(decoderFactories = listOf(coupledFactory(script, ledger)))
        harness.core.attachRenderer(rendererB)
        harness.run(300.milliseconds)

        assertEquals(1, harness.backend.openCalls, "an unseekable source must not be reopened")
        val refused = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.CommandRefused>()
            .filter { it.member == "attachRenderer" }
        assertTrue(refused.isNotEmpty(), "the impossible rebuild must be said out loud")
        harness.close()
    }

    @Test
    fun `loop all is accepted and stands beside one`() = runTest {
        val harness = CoreHarness(this)
        harness.open()
        // S4.e unlocked All: with no larger queue it repeats the current item like One, so the
        // mode is stored rather than refused. QueueTest owns the wrapping behaviour.
        harness.core.setLoop(LoopMode.All)
        assertEquals(LoopMode.All, harness.core.snapshots.value.loop)
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
    fun `auto selection never picks a descriptive audio track over an ordinary sibling`() = runTest {
        // Container order puts the described track first, which is how broadcast files arrive.
        // The picker must still hand the ordinary track to a caller who asked for nothing special.
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 2_000_000,
                hasAudio = false,
                additionalAudioTracks = listOf(
                    ScriptedAudioTrack(index = 1, marker = 2f, language = "eng", isAccessibility = true),
                    ScriptedAudioTrack(index = 3, marker = 3f, language = "eng"),
                ),
            ),
            config = PlayerConfig(audio = AudioConfig(preferredLanguages = listOf("eng"))),
        )
        harness.openWithRenderer()
        assertEquals(
            TrackId(3),
            harness.core.snapshots.value.tracks.selectedAudio,
            "the described track was auto-picked over the ordinary one",
        )
        harness.close()
    }

    @Test
    fun `forced subtitles follow the audio language even with no language preference`() = runTest {
        // A forced track is authored for the viewers of this audio: foreign lines inside audio
        // they otherwise understand. That pairing must not hide behind preferredLanguages.
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 2_000_000,
                additionalSubtitleTracks = listOf(
                    ScriptedSubtitleTrack(
                        index = 3,
                        cues = emptyList(),
                        language = "eng",
                        isForced = true,
                    ),
                ),
            ),
            config = PlayerConfig(),
        )
        harness.openWithRenderer()
        assertEquals(
            TrackId(3),
            harness.core.snapshots.value.tracks.selectedSubtitle,
            "the forced track matching the audio language was not auto-selected",
        )
        harness.close()
    }

    @Test
    fun `a forced track in an unrelated language is never auto-selected`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 2_000_000,
                additionalSubtitleTracks = listOf(
                    ScriptedSubtitleTrack(
                        index = 3,
                        cues = emptyList(),
                        language = "fra",
                        isForced = true,
                    ),
                ),
            ),
            config = PlayerConfig(),
        )
        harness.openWithRenderer()
        assertEquals(
            null,
            harness.core.snapshots.value.tracks.selectedSubtitle,
            "a forced track for a different audience was auto-selected",
        )
        harness.close()
    }

    @Test
    fun `a device that refuses to open fails typed and rolls the whole build back`() = runTest {
        // The one open-path fault the scripted device can inject, and until now nothing injected:
        // the reverse-order rollback ledger in buildSession was a guard no test could fail.
        val faults = FaultPlan().apply { sinkOpenFails = true }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000), faults = faults)

        val failure = assertFailsWith<PlaybackException> { harness.openWithRenderer() }
        val error = assertIs<PlaybackError.Internal>(failure.error)
        assertTrue(error.detail.contains("audio output"), "wrong classification: ${error.detail}")
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertTrue(
            harness.backend.sessions.single().closeCount > 0,
            "the failed build did not close the backend session it had acquired",
        )
        assertEquals(0, harness.sink.openCount, "the refusing device counted an open it never made")

        // The rollback's whole point: the same player instance recovers once the device does.
        faults.sinkOpenFails = false
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(
            harness.core.snapshots.value.status != PlaybackStatus.Failed,
            "the player did not survive its own rollback: ${harness.core.snapshots.value.error}",
        )
        assertTrue(harness.sink.framesPlayed > 0, "recovered playback never reached the device")

        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `an inactive switch cache that hoards the byte budget is truncated rather than wedging`() = runTest {
        // Audio B is cached for instant switching but never selected. Its packets are large and
        // carry no duration, FFmpeg's routine shape, so its retained history alone can hold the
        // whole byte cap. Relief must be able to sacrifice the inactive lane, or the demuxer
        // waits forever on a drain that cannot fire.
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 3_000_000,
                additionalAudioTracks = listOf(
                    ScriptedAudioTrack(
                        index = 3,
                        marker = 2f,
                        language = "jpn",
                        packetSizeBytes = 8 * 1024,
                        packetDurationKnown = false,
                    ),
                ),
            ),
            config = PlayerConfig(
                // readyDuration must be reachable under totalDuration, or readiness itself wedges.
                buffer = BufferPolicy(
                    readyDuration = 200.milliseconds,
                    totalDuration = 400.milliseconds,
                    totalBytes = 48 * 1024,
                ),
            ),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(25.seconds)

        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "the inactive cache held the byte budget and playback wedged: ${harness.core.debugState}",
        )
        // The budget was freed from the inactive lane, so the media being played was never cut:
        // a PathologicalInterleaving warning here would mean playback paid for B's hoard.
        val selectedCuts = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.PathologicalInterleaving>()
        assertEquals(
            emptyList(),
            selectedCuts,
            "relief gapped the selected media instead of the inactive cache",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun `selecting no audio track stays inside the live session`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val before = harness.core.position()
        val originalSource = harness.source
        val statusEntries = harness.core.statusHistory.size
        val frames = harness.renderer!!.count

        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, null))
        harness.run(500.milliseconds)

        assertEquals(
            1,
            harness.backend.openCalls,
            "an audio change must not reopen the source",
        )
        assertSame(originalSource, harness.source, "an audio change replaced the live source")
        assertEquals(
            emptyList(),
            harness.core.statusHistory.drop(statusEntries),
            "an audio change interrupted playback status",
        )
        assertTrue(harness.renderer!!.count > frames, "video presentation stopped during audio deselection")
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
    // The interlude's real-time seam, Kotlin half.
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

/** A renderer-coupled decoder factory whose decoder reports hardware zero-copy, for swap tests. */
private fun coupledFactory(script: MediaScript, ledger: LeakLedger): RecordingVideoDecoderFactory =
    RecordingVideoDecoderFactory { _, _ ->
        ScriptedVideoDecoder(
            script = script,
            ledger = ledger,
            faults = FaultPlan.None,
            hardwareStatus = ScriptedVideoDecoderStatus(HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec)),
        )
    }

private class RecordingVideoDecoderFactory(
    private val createDecoder: suspend (PlayerStreamInfo, HwdecPolicy) -> VideoDecoder?,
) : VideoDecoderFactory {
    override val name: String = "renderer-coupled test decoder"
    var createCount: Int = 0
        private set
    val policies: MutableList<HwdecPolicy> = mutableListOf()

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        createCount++
        policies += hwdec
        return createDecoder(stream, hwdec)
    }
}

private fun queuedRendererDecoderFailingOnReceive(
    script: MediaScript,
    hardwareFrames: LeakLedger,
    failOutputAt: Int,
    onBeforeFailure: (() -> Unit)? = null,
): VideoDecoder {
    val delegate = ScriptedVideoDecoder(
        script = script,
        ledger = hardwareFrames,
        faults = FaultPlan.None,
        hardwareStatus = ScriptedVideoDecoderStatus(
            HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec),
        ),
    )
    return object : VideoDecoder by delegate {
        private var outputs: Int = 0

        override suspend fun receive(): io.github.yuroyami.kiteplayer.spi.VideoFrame? {
            val frame = delegate.receive() ?: return null
            if (++outputs == failOutputAt) {
                onBeforeFailure?.invoke()
                frame.close()
                error("renderer decoder failed on receive $outputs")
            }
            return frame
        }
    }
}

private fun rendererDecoderFailingOnFirstFlush(
    script: MediaScript,
    hardwareFrames: LeakLedger,
): VideoDecoder {
    val delegate = ScriptedVideoDecoder(
        script = script,
        ledger = hardwareFrames,
        faults = FaultPlan.None,
        hardwareStatus = ScriptedVideoDecoderStatus(
            HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec),
        ),
    )
    return object : VideoDecoder by delegate {
        private var failed = false

        override suspend fun flush(newGeneration: Generation) {
            if (!failed) {
                failed = true
                error("renderer decoder failed during seek flush")
            }
            delegate.flush(newGeneration)
        }
    }
}
