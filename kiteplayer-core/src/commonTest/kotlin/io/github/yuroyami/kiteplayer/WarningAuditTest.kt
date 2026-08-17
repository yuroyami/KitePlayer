package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The typed warning audit (S4.d): every [PlaybackWarning] is enumerated in ONE exhaustive table
 * naming where it is emitted. The audit is the compiler's: [documentedEmissionSites] has no else
 * branch, so a new warning type does not compile until its row exists here, which is exactly
 * "fails when a new warning ships undocumented" made mechanical.
 */
class WarningAuditTest {

    /** One sample per type, which is also the census the test iterates. */
    private val samples: List<PlaybackWarning> = listOf(
        PlaybackWarning.RendererFailed("x"),
        PlaybackWarning.HardwareDecodeUnavailable("h264", "x"),
        PlaybackWarning.FrameDropping(1),
        PlaybackWarning.AudioDeviceChanged("x"),
        PlaybackWarning.AudioUnderrun(1),
        PlaybackWarning.AudioDrainIncomplete("x"),
        PlaybackWarning.AudioLatencyUnreliable("x"),
        PlaybackWarning.TonemappingUnavailable("x"),
        PlaybackWarning.ChannelLayoutUnknown(6, "x"),
        PlaybackWarning.BadTimestamps("x"),
        PlaybackWarning.TrackDeselected(TrackId(0), "x"),
        PlaybackWarning.StartupIncomplete("x"),
        PlaybackWarning.StartPositionIgnored(kotlin.time.Duration.ZERO, "x"),
        PlaybackWarning.PathologicalInterleaving(TrackId(0), 1),
        PlaybackWarning.NoRenderSurface("x"),
        PlaybackWarning.OptionsUnused(listOf("x")),
        PlaybackWarning.CommandRefused("setSpeed", "x"),
    )

    private fun documentedEmissionSites(warning: PlaybackWarning): List<String> = when (warning) {
        is PlaybackWarning.RendererFailed -> listOf(
            "PlaybackCore.watchRendererEvents, on RendererEvent.Failed from the attached renderer",
        )
        is PlaybackWarning.HardwareDecodeUnavailable -> listOf(
            "PlaybackCore.warnAboutRefusedHardwareCandidate, when a requested hardware factory refuses",
            "PlaybackCore.reopenWithBackendSoftware, when a hardware decoder death recovered to software",
        )
        is PlaybackWarning.FrameDropping -> listOf(
            "PlaybackCore's stats pass, when late drops in the last second cross the threshold",
        )
        is PlaybackWarning.AudioDeviceChanged -> listOf(
            "PlaybackCore's sink-event collection, on AudioSinkEvent.DeviceLost and DeviceChanged",
        )
        is PlaybackWarning.AudioUnderrun -> listOf(
            "PlaybackCore's stats pass, when the sink's underrun total moves",
        )
        is PlaybackWarning.AudioDrainIncomplete -> listOf(
            "PlaybackCore's end-of-stream drain, when the sink's drain deadline passes unfinished",
        )
        is PlaybackWarning.AudioLatencyUnreliable -> listOf(
            "PlaybackCore's open path, when the sink reports LatencyQuality.Unreliable",
        )
        is PlaybackWarning.TonemappingUnavailable -> listOf(
            "PlaybackCore's open path, when an HDR transfer converts without tone mapping (D16)",
        )
        is PlaybackWarning.ChannelLayoutUnknown -> listOf(
            "the audio path's layout negotiation, when a mask is absent and the count is guessed (D30)",
        )
        is PlaybackWarning.BadTimestamps -> listOf(
            "PlaybackCore's seek machine, when a quiescence deadline forces an abort",
            "the timeline paths that compensate for non-monotonic or missing timestamps",
        )
        is PlaybackWarning.TrackDeselected -> listOf(
            "PlaybackCore.createVideoDecoder and its audio sibling, when every factory refused a stream",
        )
        is PlaybackWarning.StartupIncomplete -> listOf(
            "PlaybackCore's open path, when the pipeline could not be primed before the deadline",
        )
        is PlaybackWarning.StartPositionIgnored -> listOf(
            "PlaybackCore.startPositionTargetUs, when the item's startPosition cannot be honoured",
        )
        is PlaybackWarning.PathologicalInterleaving -> listOf(
            "the demux pump, when one stream starves another past the drop bound",
        )
        is PlaybackWarning.NoRenderSurface -> listOf(
            "PlaybackCore.watchRendererEvents, on RendererEvent.SurfaceLost from the attached renderer",
        )
        is PlaybackWarning.OptionsUnused -> listOf(
            "KiteCodecMediaBackend.open, from MediaSource.unusedOpenOptions after the pre-open funnel ran",
        )
        is PlaybackWarning.CommandRefused -> listOf(
            "PlaybackCore's SetSpeed and SetPreservePitch handlers, refusing a live change on an unseekable source",
            "PlaybackCore's AttachRenderer and DetachRenderer handlers, when the scheduler never quiesced",
            "PlaybackCore.handleLoop, skipping the repeat an unseekable source cannot make",
        )
    }

    @Test
    fun `every warning type names its emission sites and its message carries its facts`() {
        for (warning in samples) {
            val sites = documentedEmissionSites(warning)
            assertTrue(sites.isNotEmpty(), "${warning::class.simpleName} documents no emission site")
            assertTrue(
                warning.message.isNotBlank(),
                "${warning::class.simpleName} has a blank message",
            )
        }
        assertTrue(samples.size >= 15, "the census lost a row: ${samples.size}")
    }
}
