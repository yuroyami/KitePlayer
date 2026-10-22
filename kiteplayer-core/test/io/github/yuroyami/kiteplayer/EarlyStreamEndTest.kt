package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.captionTrackIndex
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A stream that ends long before the others, or has a long hole (#570). The reads fill the whole
 * read-ahead with the other streams and find its queue empty. That stream is then over for now:
 * the others play on with nothing dropped, and it plays again when it gives packets again.
 */
class EarlyStreamEndTest {

    private fun CoreHarness.cuts(): List<PlaybackWarning> = events
        .filterIsInstance<PlayerEvent.Warning>()
        .map { it.warning }
        .filterIsInstance<PlaybackWarning.PathologicalInterleaving>()

    /** The sample frames the engine gave the device. It gives none while a sound is over. */
    private fun CoreHarness.heardFrames(): Long = sink.framesPlayed

    private fun CoreHarness.shownCaptions(): List<String> =
        core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }.filter { it.isNotEmpty() }

    private fun shortSound(endUs: Long = 3_000_000) = MediaScript(
        durationUs = 40_000_000,
        hasAudio = false,
        additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 0.5f, packetEndUs = endUs)),
    )

    @Test
    fun aSoundThatEndsBeforeThePictureLeavesThePicturePlaying() = runTest {
        val harness = CoreHarness(this, script = shortSound())
        harness.openWithRenderer()
        assertEquals(TrackId(2), harness.core.snapshots.value.tracks.selectedAudio)
        harness.core.play()
        harness.run(20.seconds)
        // The picture goes on at its own pace, neither held nor hurried.
        val midway = harness.core.position().inWholeMicroseconds
        assertTrue(abs(midway - 20_000_000) < 500_000, "20 s in, the position is $midway")
        harness.run(25.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(1000, harness.renderer!!.timestamps.size, "pictures were lost")
        assertTrue(harness.cuts().isEmpty(), "a file interleaved well was cut: ${harness.cuts()}")
        assertTrue(PlaybackStatus.Buffering !in harness.core.statusHistory, "${harness.core.statusHistory}")
        assertEquals(TrackId(2), harness.core.snapshots.value.tracks.selectedAudio, "the sound stays the track chosen")
        harness.close()
    }

    @Test
    fun aPictureThatEndsBeforeTheSoundLeavesTheSoundPlaying() = runTest {
        val media = MediaScript(
            durationUs = 40_000_000,
            videoTimestampsUs = (0 until 75).map { it * 40_000L },
            videoKeyframesUs = (0 until 75 step 10).map { it * 40_000L }.toSet(),
        )
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(45.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val heard = harness.heardFrames()
        assertTrue(heard >= 40L * 48_000 - 4_800, "sound was lost: heard $heard of ${40 * 48_000} frames")
        assertEquals(75, harness.renderer!!.timestamps.size)
        assertTrue(harness.cuts().isEmpty(), "a file interleaved well was cut: ${harness.cuts()}")
        harness.close()
    }

    @Test
    fun aSoundWithALongHolePlaysAgainAfterIt() = runTest {
        val media = MediaScript(
            durationUs = 60_000_000,
            hasAudio = false,
            additionalAudioTracks = listOf(
                ScriptedAudioTrack(index = 2, marker = 0.5f, packetHoleUs = 3_000_000L until 50_000_000L),
            ),
        )
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(48.seconds)
        val beforeTheReturn = harness.heardFrames()
        assertTrue(abs(beforeTheReturn - 3L * 48_000) < 4_800, "heard $beforeTheReturn frames before the hole ended")
        harness.run(5.seconds)
        harness.sink.audibleValues.clear()
        harness.run(4.seconds)
        assertTrue(harness.sink.audibleValues.any { abs(it - 0.5f) < 0.0001f }, "the sound did not come back: ${harness.sink.audibleValues}")
        harness.run(8.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val heard = harness.heardFrames()
        // Three seconds before the hole and ten after it, less what the return may cost.
        assertTrue(heard >= 13L * 48_000 - 24_000, "heard $heard of ${13 * 48_000} frames")
        assertEquals(1500, harness.renderer!!.timestamps.size, "pictures were lost")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        harness.close()
    }

    @Test
    fun aPictureWithALongHoleShowsAgainAfterIt() = runTest {
        val before = (0 until 75).map { it * 40_000L }
        val after = (0 until 250).map { 50_000_000L + it * 40_000L }
        val media = MediaScript(
            durationUs = 60_000_000,
            videoTimestampsUs = before + after,
            videoKeyframesUs = (before + after).filterIndexed { index, _ -> index % 10 == 0 }.toSet() + 50_000_000L,
        )
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(65.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val shown = harness.renderer!!.timestamps.map { it.micros }
        assertEquals(75, shown.count { it < 50_000_000 })
        assertEquals(250, shown.count { it >= 50_000_000 }, "the picture did not come back whole")
        val heard = harness.heardFrames()
        assertTrue(heard >= 60L * 48_000 - 4_800, "sound was lost: heard $heard of ${60 * 48_000} frames")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        harness.close()
    }

    @Test
    fun aPictureWithTwoLongHolesShowsAgainAfterEach() = runTest {
        val first = (0 until 75).map { it * 40_000L }
        val second = (0 until 75).map { 50_000_000L + it * 40_000L }
        val third = (0 until 250).map { 100_000_000L + it * 40_000L }
        val all = first + second + third
        val media = MediaScript(
            durationUs = 110_000_000,
            videoTimestampsUs = all,
            videoKeyframesUs = all.filterIndexed { index, _ -> index % 10 == 0 }.toSet() + 50_000_000L + 100_000_000L,
        )
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(115.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val shown = harness.renderer!!.timestamps.map { it.micros }
        assertEquals(75, shown.count { it < 50_000_000 })
        assertEquals(75, shown.count { it in 50_000_000 until 100_000_000 }, "the picture did not come back after the first hole")
        assertEquals(250, shown.count { it >= 100_000_000 }, "the picture did not come back after the second hole")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        harness.close()
    }

    @Test
    fun theCaptionsInsideAPictureComeBackWithItAfterALongHole() = runTest {
        val before = (0 until 75).map { it * 40_000L }
        val after = (0 until 250).map { 50_000_000L + it * 40_000L }
        val media = MediaScript(
            durationUs = 60_000_000,
            videoTimestampsUs = before + after,
            videoKeyframesUs = (before + after).filterIndexed { index, _ -> index % 10 == 0 }.toSet() + 50_000_000L,
            videoCaptions = { pts ->
                when (pts) {
                    1_000_000L -> "HELLO"
                    2_000_000L -> SCRIPTED_CAPTION_CLEAR
                    52_000_000L -> "AGAIN"
                    54_000_000L -> "MORE"
                    else -> null
                }
            },
        )
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, TrackId(captionTrackIndex(0))))
        harness.run(500.milliseconds)
        assertEquals(listOf("HELLO"), harness.shownCaptions())
        val flushes = ScriptedCaptionDecoderFactory.flushes
        harness.run(51.seconds)
        assertEquals(listOf("AGAIN"), harness.shownCaptions(), "at ${harness.core.position()}")
        harness.run(2.seconds)
        assertEquals(listOf("MORE"), harness.shownCaptions(), "at ${harness.core.position()}")
        // One flush for the return. A caption decoder remembers its screen from packet to packet.
        assertEquals(1, ScriptedCaptionDecoderFactory.flushes - flushes, "flushes of the caption decoder")
        harness.close()
    }

    @Test
    fun aSeekBackFromAfterTheSoundEndedPlaysTheSoundAgain() = runTest {
        val harness = CoreHarness(this, script = shortSound())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(10.seconds)
        harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        // A seek changes the sign and the scale of the scripted samples, so any sound counts.
        assertTrue(harness.sink.audibleValues.any { it != 0f }, "no sound after the seek back: ${harness.sink.audibleValues}")
        // And forward again, to where the sound has ended: the picture plays from there.
        harness.core.seek(Pts(20_000_000), SeekMode.Precise)
        val shown = harness.renderer!!.timestamps.size
        harness.run(5.seconds)
        val position = harness.core.position().inWholeMicroseconds
        assertTrue(abs(position - 25_000_000) < 1_000_000, "5 s after the seek to 20 s, the position is $position")
        assertTrue(harness.renderer!!.timestamps.size - shown >= 100, "the picture stopped after the seek")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        harness.close()
    }
}
