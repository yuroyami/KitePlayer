package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A `CancellationException` that the app's own code throws, such as the timeout of a `withTimeout`
 * around its HTTP call, is that call's failure and not the player's end (#589). The actor read it
 * as its own cancellation, published `RuntimeCompromised` and closed for good.
 */
class StrayCancellationTest {

    @Test
    fun anExternalSubtitleReaderThatThrowsACancellationIsASubtitleFailure() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        val item = MediaItem(
            "scripted://media",
            externalSubtitles = listOf(SubtitleSource("https://example.test/a.srt", io = { throw CancellationException("stray") })),
        )
        harness.core.open(item)
        harness.run(100.milliseconds)

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "the media itself must open: ${snapshot.error}")
        val skipped = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.SubtitleSourceUnreadable>().single()
        assertTrue("stray" in skipped.message, skipped.message)

        // Still a player: it plays, and opens the next item.
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.core.stop()
        harness.core.open(MediaItem("scripted://media"))
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aDecoderFactoryThatThrowsACancellationCostsItsTrackAndNotThePlayer() = runTest {
        val script = MediaScript(
            durationUs = 3_000_000,
            subtitleCues = listOf(SubtitleCue.Text(startMicros = 0, endMicros = 2_000_000, spans = listOf(StyledSpan("hello")))),
        )
        val faults = FaultPlan().apply { subtitleDecodersThrow = CancellationException("stray") }
        val harness = CoreHarness(this, script = script, faults = faults)
        harness.open()
        harness.run(100.milliseconds)

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "the open must succeed: ${snapshot.error}")
        assertNull(snapshot.tracks.selectedSubtitle, "the subtitle track is deselected")
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    /**
     * A hardware decoder that throws a cancellation while its lane runs has failed, and the
     * engine recovers it in software as it does any decoder failure (#589). The lane took the
     * exception for its own stop, ended with nothing said, and the picture stood still.
     */
    @Test
    fun aDecoderThatThrowsACancellationWhilePlayingIsRecoveredAsAFailure() = runTest {
        val faults = FaultPlan().apply {
            videoDecodeFailsAfterFrames = 12
            videoDecodeFailure = CancellationException("stray")
        }
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 6_000_000),
            faults = faults,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
        )
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertEquals(2, harness.backend.openCalls, "the failed decoder was never recovered")
        val presented = assertNotNull(harness.renderer).count
        harness.run(1.seconds)
        assertTrue(assertNotNull(harness.renderer).count > presented, "the picture stood still after the decoder failed")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    /** A source call of an open that throws a cancellation fails that open, typed, and the player stays open (#589). */
    @Test
    fun aSourceCallThatThrowsACancellationFailsTheOpenAndNotThePlayer() = runTest {
        val faults = FaultPlan().apply {
            failSelectStreams = true
            selectStreamsFailure = CancellationException("stray")
        }
        val harness = CoreHarness(this, faults = faults)
        harness.attachRenderer()
        val failure = runCatching { harness.core.open(MediaItem("scripted://media")) }.exceptionOrNull()
        assertTrue(failure is PlaybackException, "the open ended with $failure, not a typed failure")
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)

        faults.failSelectStreams = false
        harness.core.stop()
        harness.core.open(MediaItem("scripted://media"))
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the player did not open the next item")
        harness.close()
    }
}
