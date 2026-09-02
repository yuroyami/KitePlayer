@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * ReplayGain, end to end: a tag in the container changes the samples the device receives.
 *
 * The parsing and the clamp are proven where they live, in `ReplayGainTest`, and the multiply in
 * `TrimStageTest`. What only a session can prove is the wiring: that the tags are read from the
 * right place at the right moment, that the gain reaches the pipeline before any audio flows
 * through it, and that turning the feature off leaves the samples exactly as they were.
 *
 * The scripted device records every sample it is handed, which is what makes "the audio is
 * quieter" a measurement rather than a claim.
 */
class ReplayGainSessionTest {

    private val minusSixDb = mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB")

    private suspend fun peakHeardWith(
        config: PlayerConfig,
        tags: Map<String, String>,
        scope: kotlinx.coroutines.test.TestScope,
    ): Pair<Float, PlayerSnapshot> {
        val harness = CoreHarness(
            scope,
            script = MediaScript(durationUs = 2_000_000, containerTags = tags),
            config = config,
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        val snapshot = harness.core.snapshots.value
        val peak = harness.sink.audibleValues.maxOfOrNull { abs(it) } ?: 0f
        harness.close()
        return peak to snapshot
    }

    @Test
    fun `a track gain tag makes the audio quieter`() = runTest {
        val (loud, _) = peakHeardWith(PlayerConfig(), minusSixDb, this)
        val (quiet, snapshot) = peakHeardWith(
            PlayerConfig(audio = AudioConfig(replayGain = ReplayGainMode.Track)),
            minusSixDb,
            this,
        )

        assertTrue(loud > 0f, "the harness heard nothing at all, so the comparison means nothing")
        // -6.02 dB is a factor of one half, which is the whole point of choosing that number.
        assertTrue(
            abs(quiet / loud - 0.5f) < 0.02f,
            "expected about half the amplitude with the tag honoured, heard $quiet against $loud",
        )
        assertEquals(-6.02f, snapshot.appliedReplayGainDb!!, absoluteTolerance = 0.01f)
    }

    @Test
    fun `the feature off leaves the samples untouched`() = runTest {
        // The default, and the one that must stay bit-exact: a tag nobody asked to honour is a tag
        // that changes nothing.
        val (withTag, snapshot) = peakHeardWith(PlayerConfig(), minusSixDb, this)
        val (withoutTag, _) = peakHeardWith(PlayerConfig(), emptyMap(), this)
        assertEquals(withoutTag, withTag, "an ignored tag changed the audio anyway")
        assertNull(snapshot.appliedReplayGainDb, "a player with the feature off reported a gain")
    }

    @Test
    fun `no usable tag falls back to the configured value`() = runTest {
        val (quiet, snapshot) = peakHeardWith(
            PlayerConfig(
                audio = AudioConfig(replayGain = ReplayGainMode.Track, replayGainFallbackDb = -6.02f),
            ),
            emptyMap(),
            this,
        )
        val (loud, _) = peakHeardWith(PlayerConfig(), emptyMap(), this)
        assertTrue(
            abs(quiet / loud - 0.5f) < 0.02f,
            "the fallback was not applied: heard $quiet against $loud",
        )
        assertEquals(-6.02f, snapshot.appliedReplayGainDb!!, absoluteTolerance = 0.01f)
    }

    @Test
    fun `the preamp moves what the tag asked for`() = runTest {
        // Tag says -6.02, preamp says +6.02, so the two cancel and nothing is applied.
        val (heard, snapshot) = peakHeardWith(
            PlayerConfig(
                audio = AudioConfig(replayGain = ReplayGainMode.Track, replayGainPreampDb = 6.02f),
            ),
            minusSixDb,
            this,
        )
        val (plain, _) = peakHeardWith(PlayerConfig(), minusSixDb, this)
        assertTrue(
            abs(heard / plain - 1f) < 0.02f,
            "a cancelling preamp still changed the level: heard $heard against $plain",
        )
        assertEquals(0f, snapshot.appliedReplayGainDb!!, absoluteTolerance = 0.02f)
    }

    @Test
    fun `a positive gain is held back by the file's own peak`() = runTest {
        // +6 dB over a file peaking at 1.0 would clip, and the default ceiling is unity, so the
        // clamp cuts the gain to exactly nothing. This is the arm that stops ReplayGain from
        // becoming a way to distort quiet recordings.
        val (heard, snapshot) = peakHeardWith(
            PlayerConfig(audio = AudioConfig(replayGain = ReplayGainMode.Track)),
            mapOf("REPLAYGAIN_TRACK_GAIN" to "+6 dB", "REPLAYGAIN_TRACK_PEAK" to "1.0"),
            this,
        )
        val (plain, _) = peakHeardWith(PlayerConfig(), emptyMap(), this)
        assertTrue(
            abs(heard / plain - 1f) < 0.02f,
            "the peak clamp did not hold the boost back: heard $heard against $plain",
        )
        assertEquals(0f, snapshot.appliedReplayGainDb!!, absoluteTolerance = 0.02f)
    }
}
