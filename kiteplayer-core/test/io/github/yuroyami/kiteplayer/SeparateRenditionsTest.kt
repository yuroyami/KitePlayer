package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sounds that are downloads of their own, as an HLS stream's audio renditions are (#455). Only the
 * sound heard is read. A switch fetches the new one from the moment playing, with the picture and
 * the old sound going on until it covers that moment, and the old one is then no longer read. A
 * file keeps reading every sound, which is what makes its switch instant.
 */
class SeparateRenditionsTest {

    private fun script(separate: Boolean, seekable: Boolean = true, durationUs: Long = 30_000_000) = MediaScript(
        durationUs = durationUs,
        separateAudioRenditions = separate,
        seekable = seekable,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 2, marker = 0.25f, language = "fre"),
            ScriptedAudioTrack(index = 3, marker = 0.5f, language = "ger"),
            ScriptedAudioTrack(index = 4, marker = 0.75f, language = "spa"),
        ),
    )

    private fun CoreHarness.soundsRead(): Set<Int> = source.selectionHistory.last().filter { it in 1..4 }.toSet()

    private fun heardOnly(values: Set<Float>, marker: Float): Boolean =
        values.any { abs(it - marker) < 0.0001f } && values.all { abs(it) < 0.0001f || abs(it - marker) < 0.0001f }

    @Test
    fun onlyTheSoundHeardIsReadWhenEachIsADownloadOfItsOwn() = runTest {
        val harness = CoreHarness(this, script = script(separate = true))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(setOf(1), harness.soundsRead(), "the open read sounds nobody hears")
        harness.close()
    }

    @Test
    fun aFileReadsEverySoundAsBefore() = runTest {
        val harness = CoreHarness(this, script = script(separate = false))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(setOf(1, 2, 3, 4), harness.soundsRead())
        harness.close()
    }

    @Test
    fun aSwitchFetchesTheSoundWithThePictureAndTheOldSoundGoingOn() = runTest {
        val media = script(separate = true)
        val harness = CoreHarness(this, script = media)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        val frames = harness.renderer!!.count
        val shownBefore = harness.renderer!!.timestamps.size
        val decodedBefore = media.videoProbe.decodedUs.size
        val statuses = harness.core.statusHistory.size
        val seekFlushes = harness.core.seekFlushCycles
        val readsWentBack = harness.source.seeks
        val asked = currentTime
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(3)))
        val took = (currentTime - asked).milliseconds
        assertTrue(took < 2.seconds, "the switch took $took")
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.5f), "heard ${harness.sink.audibleValues} after the switch")
        assertEquals(setOf(3), harness.soundsRead(), "the sound left behind is still read")
        assertTrue(harness.renderer!!.count > frames, "the picture stopped")
        assertEquals(readsWentBack + 1, harness.source.seeks, "the reads did not go back for the new sound")
        // What the reads went back over was dropped, so no picture was shown twice or out of order.
        val shown = harness.renderer!!.timestamps.drop(shownBefore).map { it.micros }
        assertEquals(shown.sorted().distinct(), shown, "a picture read again was shown again")

        assertEquals(seekFlushes, harness.core.seekFlushCycles, "the switch ran a seek of the player")
        assertEquals(emptyList(), harness.core.statusHistory.drop(statuses), "the switch changed the status")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        // Back to the first, which is fetched again in the same way.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(1)))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 1f), "heard ${harness.sink.audibleValues} after switching back")
        assertEquals(setOf(1), harness.soundsRead())
        // Played to the end, no picture the reads went back over reached the decoder again. Read
        // again, it would sit behind everything the reads had reached and come back as a jump back.
        harness.run(30.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val decoded = media.videoProbe.decodedUs.drop(decodedBefore - 1)
        assertEquals(decoded.sorted().distinct(), decoded, "a picture read again was decoded again")
        harness.close()
    }

    @Test
    fun aSourceThatCannotSeekSwitchesWhenPlaybackReachesTheFetchedSound() = runTest {
        // Long enough that the reads are still going when the switch comes, as on a live stream.
        val harness = CoreHarness(this, script = script(separate = true, seekable = false, durationUs = 600_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        val seeks = harness.source.seeks
        val change = harness.core.selectTrack(TrackKind.Audio, TrackId(2))
        assertIs<TrackChange.Applied>(change, "$change ${harness.source.selectionHistory} frontier=${harness.source.demuxFrontierUs} pos=${harness.core.position()}")
        assertEquals(seeks, harness.source.seeks, "a source that cannot seek was asked to")
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.25f), "heard ${harness.sink.audibleValues} after the switch")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aSourceThatCannotSeekAndWasReadToItsEndRefusesTheSwitchAtOnce() = runTest {
        val harness = CoreHarness(this, script = script(separate = true, seekable = false))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        val asked = currentTime
        val change = harness.core.selectTrack(TrackKind.Audio, TrackId(2))
        val discarded = assertIs<TrackChange.Discarded>(change)
        assertTrue("read to its end" in discarded.reason, discarded.reason)
        assertTrue((currentTime - asked).milliseconds < 1.seconds, "the refusal waited")
        assertEquals(TrackId(1), harness.core.snapshots.value.tracks.selectedAudio)
        harness.close()
    }
}

