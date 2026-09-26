package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * ReplayGain follows the selected audio stream across a live track switch (#288).
 *
 * Track A carries its own -6.02 dB tag. Track B has none, so the container's 0 dB applies to it.
 * Track 5 is refused by its decoder, so selecting it must leave the gain where it was.
 */
class ReplayGainSwitchTest {

    private val halfDb = -6.02f
    private val half = 10f.pow(halfDb / 20f)

    private fun script() = MediaScript(
        durationUs = 20_000_000,
        containerTags = mapOf("REPLAYGAIN_TRACK_GAIN" to "0.00 dB"),
        audioMetadata = mapOf("REPLAYGAIN_TRACK_GAIN" to "$halfDb dB"),
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 3, marker = 0.25f, language = "jpn", title = "audio-B"),
            ScriptedAudioTrack(
                index = 5,
                marker = 0.5f,
                title = "audio-refused",
                decoderAccepted = false,
                metadata = mapOf("REPLAYGAIN_TRACK_GAIN" to "-12.00 dB"),
            ),
        ),
    )

    private fun assertHeardOnly(harness: CoreHarness, expected: Float, label: String) {
        val values = harness.sink.audibleValues
        assertTrue(values.any { abs(it - expected) <= 0.0001f }, "$label never reached the sink: heard $values, expected $expected")
        assertTrue(
            values.all { abs(it) <= 0.0001f || abs(it - expected) <= 0.0001f },
            "$label: heard $values, expected only $expected",
        )
    }

    private fun appliedDb(harness: CoreHarness): Float = harness.core.snapshots.value.appliedReplayGainDb!!

    @Test
    fun theGainFollowsTheSelectedStreamAcrossLiveSwitches() = runTest {
        val script = script()
        val harness = CoreHarness(this, script = script, config = PlayerConfig(audio = AudioConfig(replayGain = ReplayGainMode.Track)))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertHeardOnly(harness, trackSample(Generation.Initial, 1f) * half, "track A at its own -6.02 dB")
        assertEquals(halfDb, appliedDb(harness), absoluteTolerance = 0.01f)

        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(3)))
        harness.sink.audibleValues.clear()
        harness.run(500.milliseconds)
        assertHeardOnly(harness, trackSample(Generation.Initial, 0.25f), "track B at the container's 0 dB")
        assertEquals(0f, appliedDb(harness), absoluteTolerance = 0.01f, "the snapshot kept track A's gain")

        // A refused switch keeps the stream that plays, and its gain with it.
        assertIs<TrackChange.Discarded>(harness.core.selectTrack(TrackKind.Audio, TrackId(5)))
        harness.run(100.milliseconds)
        assertEquals(0f, appliedDb(harness), absoluteTolerance = 0.01f, "a refused switch changed the gain")

        // Off and back on to track A brings its own gain back.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, null))
        harness.run(200.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(script.audioIndex)))
        harness.sink.audibleValues.clear()
        harness.run(500.milliseconds)
        assertHeardOnly(harness, trackSample(Generation.Initial, 1f) * half, "track A again at -6.02 dB")
        assertEquals(halfDb, appliedDb(harness), absoluteTolerance = 0.01f)
        harness.close()
    }
}
