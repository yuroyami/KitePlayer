package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** End-to-end acceptance for HANDOFF Mission A against a genuine multi-track container fake. */
class MissionATrackSwitchAcceptanceTest {

    private data class ContinuityBaseline(
        val sessions: Int,
        val backendOpens: Int,
        val source: ScriptedSource,
        val sourceSeeks: Int,
        val seekFlushes: Long,
        val statusEntries: Int,
        val frames: Int,
    )

    private fun cue(text: String): SubtitleCue.Text = SubtitleCue.Text(
        startMicros = 0,
        endMicros = 20_000_000,
        spans = listOf(StyledSpan(text)),
    )

    private fun fixture(includeRefusedTracks: Boolean = false): MediaScript = MediaScript(
        durationUs = 20_000_000,
        subtitleCues = listOf(cue("subtitle-A")),
        additionalAudioTracks = buildList {
            add(
                ScriptedAudioTrack(
                    index = 3,
                    marker = 0.25f,
                    language = "jpn",
                    title = "audio-B",
                ),
            )
            if (includeRefusedTracks) {
                add(
                    ScriptedAudioTrack(
                        index = 5,
                        marker = 0.5f,
                        language = "fra",
                        title = "audio-refused",
                        decoderAccepted = false,
                    ),
                )
            }
        },
        additionalSubtitleTracks = buildList {
            add(
                ScriptedSubtitleTrack(
                    index = 4,
                    cues = listOf(cue("subtitle-B")),
                    language = "jpn",
                    title = "subtitle-B",
                ),
            )
            if (includeRefusedTracks) {
                add(
                    ScriptedSubtitleTrack(
                        index = 6,
                        cues = listOf(cue("subtitle-refused")),
                        language = "fra",
                        title = "subtitle-refused",
                        decoderAccepted = false,
                    ),
                )
            }
        },
    )

    private fun CoreHarness.baseline(): ContinuityBaseline = ContinuityBaseline(
        sessions = backend.sessions.size,
        backendOpens = backend.openCalls,
        source = source,
        sourceSeeks = source.seeks,
        seekFlushes = core.seekFlushCycles,
        statusEntries = core.statusHistory.size,
        frames = renderer!!.count,
    )

    private suspend fun CoreHarness.assertContinuousSince(before: ContinuityBaseline, label: String) {
        run(250.milliseconds)
        assertEquals(before.sessions, backend.sessions.size, "$label reopened the backend session")
        assertEquals(before.backendOpens, backend.openCalls, "$label reopened the media backend")
        assertSame(before.source, source, "$label replaced the source")
        assertEquals(before.sourceSeeks, source.seeks, "$label moved the demux cursor with a seek")
        assertEquals(before.seekFlushes, core.seekFlushCycles, "$label ran a repositioning seek")
        assertEquals(
            emptyList(),
            core.statusHistory.drop(before.statusEntries),
            "$label changed playback status",
        )
        assertTrue(renderer!!.count > before.frames, "$label interrupted video presentation")
        assertEquals(PlaybackStatus.Playing, core.snapshots.value.status)
    }

    private fun assertOnlyAudioMarker(values: Set<Float>, expected: Float, label: String) {
        val tolerance = 0.0001f
        assertTrue(
            values.any { abs(it - expected) <= tolerance },
            "$label never reached the sink: heard $values, expected $expected",
        )
        assertTrue(
            values.all { abs(it) <= tolerance || abs(it - expected) <= tolerance },
            "$label leaked another track/epoch into the sink: heard $values, expected only $expected",
        )
    }

    @Test
    fun subtitleDisableEnableAndSwitchStayInsideTheRunningGraph() = runTest {
        val harness = CoreHarness(
            this,
            script = fixture(),
            config = PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("eng"))),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.output.rasterizedCueTexts.any { "subtitle-A" in it })
        assertTrue(
            harness.source.demuxFrontierUs >= 5_000_000,
            "the demux never read far enough ahead to exercise retained-track switching",
        )
        val originalSource = harness.source

        var before = harness.baseline()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, null))
        harness.assertContinuousSince(before, "subtitle disable")
        assertEquals(null, harness.core.snapshots.value.tracks.selectedSubtitle)
        assertTrue(harness.renderer!!.overlays.filterNotNull().last().images.isEmpty())

        harness.output.rasterizedCueTexts.clear()
        before = harness.baseline()
        assertIs<TrackChange.Applied>(
            harness.core.selectTrack(TrackKind.Subtitle, TrackId(harness.script.subtitleIndex)),
        )
        harness.assertContinuousSince(before, "subtitle enable")
        assertEquals(TrackId(harness.script.subtitleIndex), harness.core.snapshots.value.tracks.selectedSubtitle)
        assertTrue(
            harness.output.rasterizedCueTexts.any { "subtitle-A" in it },
            "re-enabled subtitle A never became visible",
        )

        harness.output.rasterizedCueTexts.clear()
        before = harness.baseline()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, TrackId(4)))
        harness.assertContinuousSince(before, "subtitle A-to-B switch")
        assertEquals(TrackId(4), harness.core.snapshots.value.tracks.selectedSubtitle)
        assertTrue(
            harness.output.rasterizedCueTexts.any { "subtitle-B" in it },
            "subtitle B never became visible",
        )
        assertTrue(
            harness.output.rasterizedCueTexts.none { "subtitle-A" in it },
            "subtitle A was rasterized after B became the selection",
        )
        assertSame(originalSource, harness.source)

        harness.close()
        assertEquals(0, harness.ledger.liveCount, "track switches leaked packets, frames or buffers")
        assertEquals(0, harness.ledger.doubleCloseCount, "track switches closed an owner twice")
    }

    @Test
    fun audioAtoBOffAndOnStayInsideTheRunningGraphAndChangeWhatIsHeard() = runTest {
        val harness = CoreHarness(this, script = fixture())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(
            harness.source.demuxFrontierUs >= 5_000_000,
            "the demux never read far enough ahead to exercise retained-track switching",
        )
        assertOnlyAudioMarker(
            harness.sink.audibleValues,
            trackSample(Generation.Initial, 1f),
            "audio A",
        )
        val originalSource = harness.source

        var before = harness.baseline()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(3)))
        harness.sink.audibleValues.clear()
        harness.assertContinuousSince(before, "audio A-to-B switch")
        assertEquals(TrackId(3), harness.core.snapshots.value.tracks.selectedAudio)
        assertOnlyAudioMarker(
            harness.sink.audibleValues,
            trackSample(Generation.Initial, 0.25f),
            "audio B",
        )

        before = harness.baseline()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, null))
        harness.sink.audibleValues.clear()
        harness.assertContinuousSince(before, "audio disable")
        assertEquals(null, harness.core.snapshots.value.tracks.selectedAudio)
        assertEquals(emptySet(), harness.sink.audibleValues, "audio remained audible after disable")

        before = harness.baseline()
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(3)))
        harness.sink.audibleValues.clear()
        harness.assertContinuousSince(before, "audio enable")
        assertEquals(TrackId(3), harness.core.snapshots.value.tracks.selectedAudio)
        assertOnlyAudioMarker(
            harness.sink.audibleValues,
            trackSample(Generation.Initial, 0.25f),
            "re-enabled audio B",
        )
        assertSame(originalSource, harness.source)

        harness.close()
        assertEquals(0, harness.ledger.liveCount, "track switches leaked packets, frames or buffers")
        assertEquals(0, harness.ledger.doubleCloseCount, "track switches closed an owner twice")
    }

    @Test
    fun aTargetDecoderRefusalIsTypedAndLeavesTheOldTracksRunning() = runTest {
        val harness = CoreHarness(
            this,
            script = fixture(includeRefusedTracks = true),
            config = PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("eng"))),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val originalSource = harness.source

        var before = harness.baseline()
        val audioFailure = assertIs<TrackChange.Discarded>(
            harness.core.selectTrack(TrackKind.Audio, TrackId(5)),
            "audio decoder refusal was reported as success",
        )
        assertTrue(audioFailure.reason.isNotBlank())
        harness.assertContinuousSince(before, "refused audio switch")
        assertEquals(TrackId(harness.script.audioIndex), harness.core.snapshots.value.tracks.selectedAudio)

        before = harness.baseline()
        val subtitleFailure = assertIs<TrackChange.Discarded>(
            harness.core.selectTrack(TrackKind.Subtitle, TrackId(6)),
            "subtitle decoder refusal was reported as success",
        )
        assertTrue(subtitleFailure.reason.isNotBlank())
        harness.assertContinuousSince(before, "refused subtitle switch")
        assertEquals(TrackId(harness.script.subtitleIndex), harness.core.snapshots.value.tracks.selectedSubtitle)
        assertSame(originalSource, harness.source)

        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }
}
