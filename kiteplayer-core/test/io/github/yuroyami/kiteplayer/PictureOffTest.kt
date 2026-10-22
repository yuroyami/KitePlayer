package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Turning the picture off and on again happens in place while a sound carries the clock (#529), as
 * mpv and VLC do it: the item is not opened again, the sound plays on untouched, and a live source,
 * which cannot seek back to the position a reopen would need, takes the choice too.
 */
class PictureOffTest {

    private suspend fun TestScope.opened(script: MediaScript): CoreHarness {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        return harness
    }

    private val CoreHarness.tracks: Tracks get() = core.snapshots.value.tracks

    private val CoreHarness.shownUs: List<Long> get() = checkNotNull(renderer).presentations.map { it.pts.micros }

    private val CoreHarness.heardSound: Boolean get() = sink.audibleValues.any { it != 0f }

    @Test
    fun aPictureTurnedOffStopsInPlaceAndTheSoundPlaysOn() = runTest {
        val harness = opened(MediaScript(durationUs = 12_000_000))
        harness.core.play()
        harness.run(2.seconds)
        assertEquals(TrackId(0), harness.tracks.selectedVideo)

        assertEquals(TrackChange.Applied(TrackKind.Video, null), harness.core.selectTrack(TrackKind.Video, null))
        assertEquals(null, harness.tracks.selectedVideo)
        assertEquals(null, harness.core.snapshots.value.videoSize, "the snapshot has no picture")
        val seen = harness.shownUs.size
        harness.sink.audibleValues.clear()
        harness.run(2.seconds)

        assertEquals(seen, harness.shownUs.size, "no picture was shown after it went off")
        assertTrue(harness.heardSound, "the sound plays on")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val position = harness.core.position()
        assertTrue(position in 3.5.seconds..4.5.seconds, "the clock ran on: $position")
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aPictureTurnedBackOnPlaysInPlaceFromThePosition() = runTest {
        val harness = opened(MediaScript(durationUs = 12_000_000))
        harness.core.play()
        harness.run(2.seconds)
        harness.core.selectTrack(TrackKind.Video, null)
        harness.run(2.seconds)

        val seen = harness.shownUs.size
        val at = harness.core.position().inWholeMicroseconds
        assertEquals(TrackChange.Applied(TrackKind.Video, TrackId(0)), harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        harness.run(1.seconds)

        assertEquals(TrackId(0), harness.tracks.selectedVideo)
        val shown = harness.shownUs.drop(seen)
        assertTrue(shown.isNotEmpty() && shown.first() in at..at + 100_000, "from the position $at: ${shown.take(3)}")
        assertTrue(shown.last() > at + 800_000, "and plays on: ${shown.takeLast(3)}")
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        assertEquals(emptyList(), harness.events.filterIsInstance<PlayerEvent.TrackChosenByPlayer>())
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aLiveListenerCanTurnAPlayingPictureOffAndOnAgain() = runTest {
        val harness = opened(MediaScript(durationUs = 30_000_000, live = true))
        harness.core.play()
        harness.run(2.seconds)

        assertEquals(TrackChange.Applied(TrackKind.Video, null), harness.core.selectTrack(TrackKind.Video, null))
        val seen = harness.shownUs.size
        harness.sink.audibleValues.clear()
        harness.run(2.seconds)
        assertEquals(seen, harness.shownUs.size)
        assertTrue(harness.heardSound, "the sound plays on")

        assertEquals(TrackChange.Applied(TrackKind.Video, TrackId(0)), harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        harness.run(1.seconds)
        assertTrue(harness.shownUs.size > seen, "the picture came back")
        assertEquals(1, harness.backend.openCalls)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSeekWhileThePictureIsOffShowsNothingAndTheChoiceStands() = runTest {
        val harness = opened(MediaScript(durationUs = 12_000_000))
        harness.core.play()
        harness.run(2.seconds)
        harness.core.selectTrack(TrackKind.Video, null)
        val seen = harness.shownUs.size

        assertIs<SeekResult.Applied>(harness.core.seek(Pts(6_000_000), SeekMode.Precise))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertEquals(seen, harness.shownUs.size, "the seek brought no picture back")
        assertEquals(null, harness.tracks.selectedVideo)
        assertTrue(harness.heardSound, "the sound plays from the target")

        val at = harness.core.position().inWholeMicroseconds
        assertTrue(at in 6_500_000..7_500_000, "the position followed the seek: $at")
        harness.core.selectTrack(TrackKind.Video, TrackId(0))
        harness.run(500.milliseconds)
        val shown = harness.shownUs.drop(seen)
        assertTrue(shown.isNotEmpty() && shown.first() in at..at + 100_000, "from the position $at: ${shown.take(3)}")
        assertEquals(1, harness.backend.openCalls)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aPictureWithNoSoundBesideItCannotBeTurnedOff() = runTest {
        // The picture carries the clock, as the sound does in a song, whose sound cannot be turned
        // off either. A reopen without it used to fail the player for want of a stream.
        val harness = opened(MediaScript(durationUs = 12_000_000, hasAudio = false))
        harness.core.play()
        harness.run(1.seconds)

        val refusal = assertFailsWith<UnsupportedOperationException> { harness.core.selectTrack(TrackKind.Video, null) }
        assertTrue(refusal.message?.contains("only timeline-carrying stream") == true, refusal.message)
        val seen = harness.shownUs.size
        harness.run(500.milliseconds)
        assertEquals(TrackId(0), harness.tracks.selectedVideo)
        assertTrue(harness.shownUs.size > seen, "the picture plays on")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertEquals(1, harness.backend.openCalls)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }
}
