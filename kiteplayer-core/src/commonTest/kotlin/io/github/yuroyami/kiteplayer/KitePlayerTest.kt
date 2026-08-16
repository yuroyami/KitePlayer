package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The public player, over a scripted session in virtual time.
 *
 * What is under test here is the outside of the engine: that the surface is the one the design fixes,
 * that every numeric input is checked at the boundary rather than deep inside a worker, that a refusal
 * is the right kind of refusal, and that the two groups of frame counters stay distinguishable. The
 * machine behind it has its own tests; this is about what an application can and cannot do with it.
 */
class KitePlayerTest {

    private fun player(harness: CoreHarness): KitePlayer = KitePlayer(harness.core)

    // ---------------------------------------------------------------------------------------------
    // Creation.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `creating a player without backends fails typed and names what to pass`() {
        val noBackend = assertFailsWith<PlaybackException> { KitePlayer.create() }
        val backendError = noBackend.error
        assertTrue(
            backendError is PlaybackError.ConfigurationInvalid,
            "a missing backend is a configuration error and not a null pointer later: $backendError",
        )
        assertTrue(
            backendError.detail.contains("KiteCodecMediaBackend"),
            "the error names what to pass: ${backendError.detail}",
        )

        val noOutput = assertFailsWith<PlaybackException> {
            KitePlayer.create(PlayerConfig(backends = Backends(backend = ScriptedBackend())))
        }
        val outputError = noOutput.error
        assertTrue(outputError is PlaybackError.ConfigurationInvalid, "and so is a missing output: $outputError")
        assertTrue(
            outputError.detail.contains("AppleOutputBackend"),
            "which also names what to pass: ${outputError.detail}",
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Input validation, D32.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every numeric input is checked at the boundary`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://validation"))

        // A position. Infinity is the one a plain range check lets through, which is why it is explicit.
        assertFailsWith<IllegalArgumentException> { player.seek(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { player.seek((-1).seconds) }
        assertFailsWith<IllegalArgumentException> { player.seekLater(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { player.seekLater((-1).milliseconds) }

        // A rate. Zero is not a rate and infinity passes `value > 0`.
        assertFailsWith<IllegalArgumentException> { player.setSpeed(0.0) }
        assertFailsWith<IllegalArgumentException> { player.setSpeed(-1.0) }
        assertFailsWith<IllegalArgumentException> { player.setSpeed(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { player.setSpeed(Double.NaN) }

        // A volume, whose documented range is 0 to 1. Above unity is amplification and is refused.
        assertFailsWith<IllegalArgumentException> { player.setVolume(1.5f) }
        assertFailsWith<IllegalArgumentException> { player.setVolume(-0.1f) }
        assertFailsWith<IllegalArgumentException> { player.setVolume(Float.NaN) }

        // And a value inside every range is taken and published.
        player.setVolume(0.25f)
        player.setMuted(true)
        harness.run(100.milliseconds)
        assertEquals(0.25f, player.state.value.volume)
        assertTrue(player.state.value.muted)
        harness.close()
    }

    @Test
    fun `LoopMode All is accepted and repeats the current item with no larger queue`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://loop"))

        // S4.e unlocked All: with a queue of one, or plain opened media, the whole queue IS the
        // current item, so the mode is accepted and repeats it rather than being refused.
        player.setLoop(LoopMode.All)
        harness.run(100.milliseconds)
        assertEquals(LoopMode.All, player.state.value.loop)

        player.setLoop(LoopMode.One)
        harness.run(100.milliseconds)
        assertEquals(LoopMode.One, player.state.value.loop, "the modes that exist are accepted")
        harness.close()
    }

    @Test
    fun `speed refuses every non-unity rate until a real rate control exists`() = runTest {
        val withAudio = CoreHarness(this)
        val player = player(withAudio)
        player.open(MediaItem("scripted://audio"))

        val refusal = assertFailsWith<UnsupportedOperationException> { player.setSpeed(2.0) }
        assertTrue(
            refusal.message?.contains("tempo") == true,
            "the refusal names the missing stage rather than pretending: ${refusal.message}",
        )
        player.setSpeed(1.0)
        withAudio.run(100.milliseconds)
        assertEquals(1.0, player.state.value.speed, "and unity is always legal")
        withAudio.close()

        // Video-only is refused too: the frame scheduler has no scaled timer, so a stored 2.0
        // would report a rate the picture provably does not play at (audit P1-11).
        val videoOnly = CoreHarness(this, script = MediaScript(hasAudio = false))
        val silentPlayer = player(videoOnly)
        silentPlayer.open(MediaItem("scripted://video-only"))
        assertFailsWith<UnsupportedOperationException> { silentPlayer.setSpeed(2.0) }
        videoOnly.run(100.milliseconds)
        assertEquals(
            1.0,
            silentPlayer.state.value.speed,
            "a refused rate must not leak into published state",
        )
        videoOnly.close()
    }

    // ---------------------------------------------------------------------------------------------
    // Errors, and where they are readable from.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a failed open throws the typed exception and the failure stays on the snapshot`() = runTest {
        val harness = CoreHarness(this)
        harness.backend.openFailure = IllegalStateException("the scripted container is corrupt")
        val player = player(harness)

        val failure = assertFailsWith<PlaybackException> { player.open(MediaItem("scripted://broken")) }
        assertTrue(
            failure.error is PlaybackError.SourceUnavailable,
            "the exception carries the typed error: ${failure.error}",
        )
        assertEquals(PlaybackStatus.Failed, player.state.value.status)
        assertSame(
            failure.error,
            player.state.value.error,
            "and the same error is retained on the snapshot, because the event stream replays nothing",
        )
        harness.close()
    }

    @Test
    fun `selectTrack refuses a subtitle track and a source that cannot seek`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://tracks"))

        val subtitles = assertFailsWith<UnsupportedOperationException> {
            player.selectTrack(TrackKind.Subtitle, TrackId(9))
        }
        assertTrue(
            subtitles.message?.contains("subtitle") == true,
            "the refusal names what is missing: ${subtitles.message}",
        )
        harness.close()

        val fixed = CoreHarness(this, script = MediaScript(seekable = false))
        val fixedPlayer = player(fixed)
        fixedPlayer.open(MediaItem("scripted://not-seekable"))
        val refusal = assertFailsWith<UnsupportedOperationException> {
            fixedPlayer.selectTrack(TrackKind.Audio, null)
        }
        assertTrue(
            refusal.message?.contains("seek") == true,
            "a switch reopens and seeks back, so a source that cannot seek is refused: ${refusal.message}",
        )
        // The same source refuses a seek for the same reason, through the same kind of exception.
        assertFailsWith<UnsupportedOperationException> { fixedPlayer.seek(1.seconds) }
        fixed.close()
    }

    @Test
    fun `commands after a close are refused rather than lost`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://closing"))
        harness.close()

        assertFailsWith<IllegalStateException> { player.play() }
        assertFailsWith<IllegalStateException> { player.pause() }
        assertFailsWith<IllegalStateException> { player.setVolume(0.5f) }
        assertFailsWith<IllegalStateException> { player.setMuted(true) }
        assertFailsWith<IllegalStateException> { player.detachRenderer() }
        // Close itself is idempotent and terminal, so it says nothing the second time.
        player.close()
    }

    @Test
    fun `awaited close returns only after teardown and publishes healthy idle`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://awaited-close"))

        assertEquals(0, harness.session.closeCount)
        assertTrue(!harness.sink.closed)

        player.closeAndAwait()

        assertEquals(1, harness.session.closeCount, "the backend session is closed before the facade returns")
        assertTrue(harness.sink.closed, "the audio sink is closed before the facade returns")
        assertEquals(PlaybackStatus.Idle, player.state.value.status)
        assertNull(player.state.value.error, "a healthy close must not manufacture an error")
        harness.close()
    }

    // ---------------------------------------------------------------------------------------------
    // What the flows report, D21.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `stats keep what the schedule submitted apart from what nothing drew`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.attachRenderer(harness.renderer!!)
        player.open(MediaItem("scripted://stats"))
        player.play()
        harness.run(2.seconds)

        val stats = player.stats.value
        assertTrue(stats.decodedVideoFrames > 0, "frames were decoded")
        assertTrue(stats.submittedFrames > 0, "and a renderer accepted them")
        assertEquals(0, stats.headlessFrames, "with one attached, nothing was presented into the void")
        assertTrue(
            harness.renderer.count >= stats.submittedFrames,
            "the renderer's own count is the drawing truth and is never behind the engine's: " +
                "${harness.renderer.count} against ${stats.submittedFrames}",
        )
        assertEquals(MasterClock.Audio, stats.masterClock, "audio drives the clock when there is audio")
        assertEquals(0, stats.droppedFramesDecode, "nothing drops a packet before decoding it")
        assertEquals(Duration.ZERO, stats.audioLatency, "and no latency figure is read from the sink")

        // Detached, the schedule keeps its pacing and counts the frames nothing drew, which is the whole
        // reason the two counters are separate numbers.
        val submittedBefore = stats.submittedFrames
        player.detachRenderer()
        harness.run(1.seconds)
        val after = player.stats.value
        assertTrue(after.headlessFrames > 0, "a detached player still paces frames: ${after.headlessFrames}")
        assertTrue(
            after.submittedFrames == submittedBefore || after.submittedFrames > submittedBefore,
            "and the submitted count never goes backwards",
        )
        harness.close()
    }

    @Test
    fun `stats publish current decoder hardware and change immediately after demotion`() = runTest {
        val harness = CoreHarness(this, config = PlayerConfig(statsInterval = 10.milliseconds))
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.MediaCodec)
        val player = player(harness)

        player.open(MediaItem("scripted://dynamic-hardware-status"))
        harness.run(50.milliseconds)
        assertEquals(
            HwdecStatus.HardwareWithDownload(HwdecKind.MediaCodec),
            player.stats.value.hardwareDecode,
            "stats read the live decoder path rather than assuming software",
        )

        // A real fallback changes this from the video-decode worker. The injected status uses the same
        // cross-thread-safe shape, so the actor must read it again instead of preserving the open value.
        harness.backend.videoDecoderStatus.value = HwdecStatus.Software
        harness.run(50.milliseconds)
        assertEquals(
            HwdecStatus.Software,
            player.stats.value.hardwareDecode,
            "the first stats publication after demotion must not retain a stale hardware claim",
        )
        harness.close()
    }

    @Test
    fun `position and progress come from the player and agree with each other`() = runTest {
        val harness = CoreHarness(this, config = PlayerConfig(progressInterval = 50.milliseconds))
        val player = player(harness)
        player.attachRenderer(harness.renderer!!)
        player.open(MediaItem("scripted://progress"))
        assertEquals(Duration.ZERO, player.position(), "an open lands at the start")
        assertNull(player.state.value.error)

        player.play()
        harness.run(1.seconds)
        val position = player.position()
        assertTrue(position > 500.milliseconds, "the position advanced with playback, was $position")
        val sampled = player.progress.value.position
        assertTrue(
            (position - sampled).inWholeMilliseconds in -200..200,
            "the sampled progress is the same reading one interval old: $sampled against $position",
        )

        player.pause()
        harness.run(200.milliseconds)
        val frozen = player.position()
        harness.run(1.seconds)
        assertEquals(frozen, player.position(), "a paused player's position does not move")
        harness.close()
    }

    @Test
    fun `a seek through the facade lands and the player reports where`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.attachRenderer(harness.renderer!!)
        player.open(MediaItem("scripted://seek"))

        player.seek(2.seconds, SeekMode.Precise)
        val landed = player.position()
        assertTrue(
            (landed - 2.seconds).inWholeMilliseconds in -50..50,
            "a precise seek lands on its target, was $landed",
        )
        val completion = harness.events.filterIsInstance<PlayerEvent.SeekCompleted>().lastOrNull()
        assertTrue(completion != null, "and the player announced which seek completed")
        assertEquals(
            player.state.value.generation,
            completion.generation,
            "with the epoch the seek established",
        )
        harness.close()
    }
}
