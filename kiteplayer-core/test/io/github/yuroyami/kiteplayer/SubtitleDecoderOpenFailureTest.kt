package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A subtitle decoder that cannot open costs its track and not the file. The web build of the media
 * library has no subtitle decoders, so a file whose default track is picture subtitles or captions
 * failed to open there.
 */
class SubtitleDecoderOpenFailureTest {

    private val script = MediaScript(
        durationUs = 3_000_000,
        subtitleCues = listOf(
            SubtitleCue.Text(startMicros = 0, endMicros = 2_000_000, spans = listOf(StyledSpan("hello"))),
        ),
    )

    @Test
    fun aSubtitleDecoderThatThrowsAtOpenDeselectsItsTrackAndThePlayerStillOpens() = runTest {
        val faults = FaultPlan().apply { subtitleDecodersThrow = IllegalStateException("this build has no decoder for it") }
        val harness = CoreHarness(this, script = script, faults = faults)
        harness.open()
        harness.run(100.milliseconds)

        val snapshot = harness.core.snapshots.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "the open must succeed: ${snapshot.error}")
        assertNull(snapshot.tracks.selectedSubtitle, "the subtitle track is deselected")
        val deselected = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.TrackDeselected>()
            .single { it.track == TrackId(script.subtitleIndex) }
        assertTrue("this build has no decoder for it" in deselected.detail, "the warning says why: ${deselected.detail}")
        harness.close()
    }
}
