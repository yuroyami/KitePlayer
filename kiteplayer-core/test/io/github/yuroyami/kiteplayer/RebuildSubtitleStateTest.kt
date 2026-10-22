package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SRT = "1\n00:00:01,000 --> 00:00:05,000\nFrom the file\n\n"

/** Serves one fixed SRT file, as an application's own subtitle reader would. */
private class SrtIo : MediaIo {
    private val bytes = SRT.encodeToByteArray()
    override val size: Long = bytes.size.toLong()
    override val seekable: Boolean = true
    private var at = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) return -1
        val n = minOf(length, bytes.size - at)
        bytes.copyInto(into, offset, at, at + n)
        at += n
        return n
    }

    override suspend fun seek(position: Long) {
        at = position.toInt()
    }

    override fun close() = Unit
}

/**
 * The subtitle state survives a rebuild of the playback graph (#212).
 *
 * A video track switch, a renderer swap and a hardware decoder recovery all rebuild the session.
 * The rebuild replaces the track table with the container's own, so each case checks that the
 * external rows and both subtitle selections come back.
 */
class RebuildSubtitleStateTest {

    private val config = PlayerConfig(
        subtitles = SubtitleConfig(preferredLanguages = listOf("eng")),
        progressInterval = 50.milliseconds,
    )

    private fun cue(start: Long, end: Long, text: String): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan(text)))

    private fun texts(cues: List<SubtitleCue>) = cues.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    private suspend fun openWithExternal(harness: CoreHarness) {
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(SubtitleSource(uri = "memory://subs.srt", io = { SrtIo() })),
            ),
        )
    }

    private fun externalId(harness: CoreHarness): TrackId =
        harness.core.snapshots.value.tracks.all.single { it.kind == TrackKind.Subtitle && it.id.value < 0 }.id

    @Test
    fun anExternalPrimarySurvivesAVideoRebuild() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000), config = config)
        openWithExternal(harness)
        val external = externalId(harness)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, external))
        harness.core.play()
        harness.run(300.milliseconds)

        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        harness.run(1500.milliseconds)

        val tracks = harness.core.snapshots.value.tracks
        assertEquals(external, tracks.selectedSubtitle, "the rebuild dropped the external selection")
        assertTrue(tracks.all.any { it.id == external }, "the rebuild dropped the external row")
        assertEquals(listOf("From the file"), texts(harness.core.subtitleCues.value))
        harness.close()
    }

    @Test
    fun theSecondarySurvivesAVideoRebuild() = runTest {
        val script = MediaScript(
            durationUs = 6_000_000,
            subtitleCues = listOf(cue(500_000, 5_000_000, "primary line")),
            additionalSubtitleTracks = listOf(
                ScriptedSubtitleTrack(
                    index = 3,
                    cues = listOf(cue(500_000, 5_000_000, "secondary line")),
                    language = "jpn",
                    title = "scripted subtitle B",
                ),
            ),
        )
        val harness = CoreHarness(this, script = script, config = config)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectSecondarySubtitle(TrackId(3)))

        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        harness.run(1500.milliseconds)

        assertEquals(
            TrackId(3),
            harness.core.snapshots.value.tracks.selectedSecondarySubtitle,
            "the rebuild dropped the secondary selection",
        )
        assertEquals(listOf("primary line", "secondary line"), texts(harness.core.subtitleCues.value))
        harness.close()
    }

    private fun recoveringHarness(scope: TestScope): CoreHarness {
        val harness = CoreHarness(
            scope = scope,
            script = MediaScript(durationUs = 6_000_000),
            faults = FaultPlan().apply { videoDecodeFailsAfterFrames = 12 },
            config = config.copy(hardwareDecode = HwdecPolicy.Auto),
        )
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)
        return harness
    }

    @Test
    fun anExternalSubtitleSurvivesADecoderRecovery() = runTest {
        val harness = recoveringHarness(this)
        openWithExternal(harness)
        val external = externalId(harness)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, external))
        harness.core.play()
        harness.run(1500.milliseconds)
        assertEquals(2, harness.backend.openCalls, "the decoder never failed, so this proves nothing")

        val tracks = harness.core.snapshots.value.tracks
        assertTrue(tracks.all.any { it.id == external }, "the recovery dropped the external row")
        assertEquals(external, tracks.selectedSubtitle, "the recovery dropped the external selection")
        assertEquals(listOf("From the file"), texts(harness.core.subtitleCues.value))
        // Selecting it again must not claim it belongs to other media.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, external))
        harness.run(1.seconds)
        harness.close()
    }
}
