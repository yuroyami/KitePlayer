package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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
            backendError.detail.contains("KiteFFmpegMediaBackend"),
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
    fun `a uri open consults the resolver and the engine interposes its byte cache`() = runTest {
        var resolvedFor: String? = null
        val supplied = object : MediaIo {
            override val size: Long = 1_000L
            override val seekable: Boolean = true
            override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
            override suspend fun seek(position: Long) {}
            override fun close() {}
        }
        val harness = CoreHarness(
            this,
            config = PlayerConfig(
                network = NetworkConfig(
                    ioResolver = MediaIoResolver { uri ->
                        resolvedFor = uri
                        if (uri.startsWith("https://")) supplied else null
                    },
                ),
            ),
        )
        val player = player(harness)

        player.open(MediaItem("https://example.test/movie.mkv"))
        harness.run(200.milliseconds)
        assertEquals("https://example.test/movie.mkv", resolvedFor, "the resolver never saw the uri")
        val opened = harness.backend.lastOpenedItem
        // The item carries a FACTORY now (audit KP-P1-03), and the engine's answers with the one
        // reader it made for this session, so invoking it is how a test sees what the backend saw.
        val delivered = assertNotNull(opened?.io, "the resolved reader never reached the backend").invoke()
        assertNotSame(supplied, delivered, "the engine must interpose the M5 cache, not pass the reader raw")

        // A uri the resolver declines passes through untouched: local files stay on the
        // backend's own fast path.
        resolvedFor = null
        player.stop()
        harness.run(100.milliseconds)
        player.open(MediaItem("scripted://local-file"))
        harness.run(200.milliseconds)
        assertEquals("scripted://local-file", resolvedFor, "the resolver is consulted for every uri open")
        assertNull(harness.backend.lastOpenedItem?.io, "a declined uri must carry no reader")
        harness.close()
    }

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
    fun `speed is real and the position runs at the published rate`() = runTest {
        // Long enough that four media seconds of advance sit nowhere near the end.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        val player = player(harness)
        player.open(MediaItem("scripted://audio"))

        // The live change rides an internal precise seek, so the window covers the seek's own
        // quiescence and landing, not just one actor pass.
        player.setSpeed(2.0)
        harness.run(3.seconds)
        assertEquals(2.0, player.state.value.speed, "an accepted rate is published")

        player.play()
        harness.run(500.milliseconds)
        val before = player.position()
        harness.run(2.seconds)
        val advanced = player.position() - before
        // Two wall seconds at 2x is four media seconds. The bound is loose on purpose: the
        // tempo stage holds splice lookahead and none of that belongs to this assertion. What
        // can never happen at a REAL 2x is 1x's advance.
        assertTrue(
            advanced >= 3.seconds,
            "two wall seconds at 2x advanced only $advanced of media",
        )
        harness.close()

        // Video-only runs at the rate too: with no audio to follow, the schedule itself paces at
        // the rate and the video clock extrapolates at it (the old build refused exactly this).
        val videoOnly = CoreHarness(
            this,
            script = MediaScript(hasAudio = false, durationUs = 30_000_000),
        )
        val silentPlayer = player(videoOnly)
        silentPlayer.open(MediaItem("scripted://video-only"))
        silentPlayer.setSpeed(2.0)
        videoOnly.run(3.seconds)
        silentPlayer.play()
        videoOnly.run(500.milliseconds)
        val silentBefore = silentPlayer.position()
        videoOnly.run(2.seconds)
        val silentAdvanced = silentPlayer.position() - silentBefore
        assertTrue(
            silentAdvanced >= 3.seconds,
            "video-only at 2x advanced only $silentAdvanced of media",
        )
        videoOnly.close()
    }

    @Test
    fun `the armed A-B loop wraps B back to A and keeps playing`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        val player = player(harness)
        player.open(MediaItem("scripted://abloop"))

        // Arming is validated at the boundary like every other numeric input.
        assertFailsWith<IllegalArgumentException> { player.setAbLoop(a = null, b = 2.seconds) }
        assertFailsWith<IllegalArgumentException> { player.setAbLoop(a = (-1).seconds, b = 2.seconds) }
        assertFailsWith<IllegalArgumentException> { player.setAbLoop(a = 2.seconds, b = 2.seconds) }

        player.setAbLoop(a = 2.seconds, b = 4.seconds)
        harness.run(100.milliseconds)
        assertEquals(2.seconds, player.state.value.abLoopA, "the armed A is published")
        assertEquals(4.seconds, player.state.value.abLoopB, "the armed B is published")

        player.play()
        // Ten wall seconds against a two-second region. Without the loop the position sails to
        // ten; with it the position can never come to rest past B, wrap seeks included.
        var maxSeen = Duration.ZERO
        repeat(20) {
            harness.run(500.milliseconds)
            val at = player.position()
            if (at > maxSeen) maxSeen = at
        }
        assertTrue(
            maxSeen < 6.seconds,
            "the loop never wrapped: after ten wall seconds the position reached $maxSeen",
        )
        assertTrue(
            player.state.value.status.isActive,
            "the loop keeps playing; instead the player is ${player.state.value.status}",
        )
        harness.close()

        // A alone loops from A at the END of the media, which is what an armed A with no B means.
        val tail = CoreHarness(this)
        val tailPlayer = player(tail)
        tailPlayer.open(MediaItem("scripted://abloop-tail"))
        tailPlayer.setAbLoop(a = 1.seconds)
        tailPlayer.play()
        // Twelve wall seconds of four-second media: three wraps, and Ended can never hold.
        repeat(24) {
            tail.run(500.milliseconds)
            assertTrue(
                tailPlayer.state.value.status != PlaybackStatus.Ended,
                "an armed A-B loop owns the end of the media, and Ended means it let go",
            )
        }

        // Clearing gives the end of the media back to the ordinary loop mode, which is Off here.
        tailPlayer.setAbLoop(null)
        tail.run(6.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            tailPlayer.state.value.status,
            "with the loop cleared the media ends like any other",
        )
        tail.close()
    }

    @Test
    fun `startPosition opens at the asked position and unseekable media warns typed`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        val player = player(harness)
        player.open(MediaItem("scripted://start", startPosition = 12.seconds))
        // The exact landing rides the ordinary seek machine; give it its window.
        harness.run(3.seconds)
        val at = player.position()
        assertTrue(
            at >= 11.seconds && at <= 13.seconds,
            "an open with startPosition=12s must sit near 12s, sat at $at",
        )
        // And playing from there advances from there, not from zero.
        player.play()
        harness.run(1.seconds)
        assertTrue(player.position() >= 12.seconds, "playback continues from the start position")
        harness.close()

        // An unseekable source cannot start anywhere but where the container does, and says so.
        val fixed = CoreHarness(this, script = MediaScript(seekable = false))
        val fixedPlayer = player(fixed)
        fixedPlayer.open(MediaItem("scripted://start-unseekable", startPosition = 2.seconds))
        fixed.run(100.milliseconds)
        assertTrue(
            fixedPlayer.warningHistory().any { it.warning is PlaybackWarning.StartPositionIgnored },
            "ignoring the start position must be said, typed, not discovered by the position report",
        )
        assertTrue(fixedPlayer.position() < 1.seconds, "and playback stands at the container's own start")
        fixed.close()
    }

    @Test
    fun `picture controls are validated and published and reach the attached renderer`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        harness.attachRenderer()
        player.open(MediaItem("scripted://adjustments"))
        harness.run(100.milliseconds)
        assertEquals(VideoAdjustments.Identity, player.state.value.videoAdjustments, "neutral is the default")
        assertEquals(VideoAdjustments.Identity, harness.renderer?.adjustments, "told on attach, before any change")

        assertFailsWith<IllegalArgumentException> { player.setVideoAdjustments(VideoAdjustments(brightness = 1.5f)) }
        assertFailsWith<IllegalArgumentException> { player.setVideoAdjustments(VideoAdjustments(contrast = -0.1f)) }
        assertFailsWith<IllegalArgumentException> { player.setVideoAdjustments(VideoAdjustments(saturation = 3f)) }
        assertFailsWith<IllegalArgumentException> { player.setVideoAdjustments(VideoAdjustments(hueDegrees = 200f)) }

        val warm = VideoAdjustments(brightness = 0.1f, contrast = 1.2f, saturation = 0.8f, hueDegrees = -15f)
        player.setVideoAdjustments(warm)
        harness.run(100.milliseconds)
        assertEquals(warm, player.state.value.videoAdjustments, "the accepted value is published")
        assertEquals(warm, harness.renderer?.adjustments, "and the live renderer was told")

        // The framing controls travel the same road, so they are proven in the same breath.
        assertFailsWith<IllegalArgumentException> { player.setVideoTransform(VideoTransform(aspectOverride = 0f)) }
        assertFailsWith<IllegalArgumentException> { player.setVideoTransform(VideoTransform(zoom = 8f)) }
        assertFailsWith<IllegalArgumentException> { player.setVideoTransform(VideoTransform(panX = 2f)) }
        val framed = VideoTransform(aspectOverride = 16f / 9f, zoom = 1.5f, panX = 0.1f, panY = -0.1f)
        player.setVideoTransform(framed)
        harness.run(100.milliseconds)
        assertEquals(framed, player.state.value.videoTransform, "the accepted framing is published")
        assertEquals(framed, harness.renderer?.transform, "and the live renderer was told")
        harness.close()
    }

    @Test
    fun `the pitch law is published and a live toggle is accepted on seekable media`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        val player = player(harness)
        player.open(MediaItem("scripted://pitch"))
        harness.run(100.milliseconds)
        assertTrue(player.state.value.preservePitch, "pitch preservation is the default")

        // The toggle at speed rides an internal precise seek exactly like a speed change, so the
        // window covers that seek's quiescence and landing. The audible difference between the
        // two mechanisms is proven at the pipeline level; what belongs here is the surface: the
        // value is accepted, published, and survives the seek it rides.
        player.setSpeed(2.0)
        player.setPreservePitch(false)
        harness.run(3.seconds)
        assertTrue(!player.state.value.preservePitch, "the resampled law is published")
        assertEquals(2.0, player.state.value.speed, "and the rate it applies to is untouched")

        player.setPreservePitch(true)
        harness.run(3.seconds)
        assertTrue(player.state.value.preservePitch, "and the toggle comes back")
        harness.close()
    }

    @Test
    fun `the scale mode is published and reaches the attached renderer`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        harness.attachRenderer()
        player.open(MediaItem("scripted://scale"))
        harness.run(100.milliseconds)
        assertEquals(VideoScale.Fit, player.state.value.videoScale, "Fit is the default")
        assertEquals(VideoScale.Fit, harness.renderer?.scaleMode, "told on attach, before any change")

        player.setVideoScale(VideoScale.Fill)
        harness.run(100.milliseconds)
        assertEquals(VideoScale.Fill, player.state.value.videoScale)
        assertEquals(VideoScale.Fill, harness.renderer?.scaleMode, "the live renderer is told immediately")

        player.setVideoScale(VideoScale.Stretch)
        harness.run(100.milliseconds)
        assertEquals(VideoScale.Stretch, harness.renderer?.scaleMode)
        harness.close()
    }

    @Test
    fun `the runtime subtitle and audio adjustments are published`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://adjust"))

        player.setSubtitleDelay(250.milliseconds)
        player.setSubtitleScale(1.5f)
        player.setSubtitlePosition(0.8f)
        player.setAudioDelay(80.milliseconds)
        harness.run(100.milliseconds)

        val state = player.state.value
        assertEquals(250.milliseconds, state.subtitleDelay)
        assertEquals(1.5f, state.subtitleScale)
        assertEquals(0.8f, state.subtitlePosition)
        assertEquals(80.milliseconds, state.audioDelay)

        assertFailsWith<IllegalArgumentException> { player.setSubtitleScale(0f) }
        assertFailsWith<IllegalArgumentException> { player.setSubtitleScale(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { player.setSubtitlePosition(0f) }
        assertFailsWith<IllegalArgumentException> { player.setSubtitlePosition(1.2f) }
        harness.close()
    }

    @Test
    fun `KeyframeThenRefine shows the keyframe first and lands exactly`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        val player = player(harness)
        harness.attachRenderer()
        player.open(MediaItem("scripted://two-phase"))
        harness.run(100.milliseconds)
        val before = harness.renderer!!.timestamps.size

        // 600 ms sits between the 400 ms keyframe and the 800 ms one. The two-phase promise:
        // the keyframe is PRESENTED first, then the exact frame lands, and the reported result
        // is the exact landing.
        player.seek(600.milliseconds, SeekMode.KeyframeThenRefine)
        harness.run(500.milliseconds)

        val presented = harness.renderer!!.timestamps.drop(before).map { it.micros }
        assertTrue(
            presented.contains(400_000L),
            "the keyframe at 400 ms must be shown first; presented $presented",
        )
        assertTrue(
            presented.contains(600_000L),
            "and the exact frame at 600 ms must land after it; presented $presented",
        )
        assertTrue(
            presented.indexOf(400_000L) < presented.indexOf(600_000L),
            "in that order; presented $presented",
        )
        val at = player.position()
        assertTrue(
            at >= 590.milliseconds && at <= 650.milliseconds,
            "the reported landing is the exact target, was $at",
        )
        harness.close()
    }

    @Test
    fun `speed outside the supported range is refused at the door`() = runTest {
        val harness = CoreHarness(this)
        val player = player(harness)
        player.open(MediaItem("scripted://audio"))
        assertFailsWith<IllegalArgumentException> { player.setSpeed(0.1) }
        assertFailsWith<IllegalArgumentException> { player.setSpeed(4.5) }
        harness.run(100.milliseconds)
        assertEquals(1.0, player.state.value.speed, "a refused rate must not leak into published state")
        harness.close()
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
    fun `selectTrack reports a missing subtitle while audio can change without seeking`() = runTest {
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
        assertIs<TrackChange.Applied>(fixedPlayer.selectTrack(TrackKind.Audio, null))
        assertNull(fixedPlayer.state.value.tracks.selectedAudio)
        assertEquals(1, fixed.backend.openCalls, "an audio change must not reopen a non-seekable source")
        // A real reposition still requires source seekability and remains a typed refusal.
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
