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
        @Suppress("DEPRECATION")
        PlaybackWarning.TonemappingUnavailable("x"),
        PlaybackWarning.HdrToneMapped("PQ", 0),
        PlaybackWarning.ColorApproximated("x"),
        PlaybackWarning.ChannelLayoutUnknown(6, "x"),
        PlaybackWarning.BadTimestamps("x"),
        PlaybackWarning.TrackDeselected(TrackId(0), "x"),
        PlaybackWarning.StartupIncomplete("x"),
        PlaybackWarning.StartPositionIgnored(kotlin.time.Duration.ZERO, "x"),
        PlaybackWarning.PathologicalInterleaving(TrackId(0), 1),
        PlaybackWarning.AudioDeviceUnderrun("x"),
        PlaybackWarning.NoRenderSurface("x"),
        PlaybackWarning.OptionsUnused(listOf("x")),
        PlaybackWarning.CommandRefused("setSpeed", "x"),
        PlaybackWarning.ResourcesNotReleased("x"),
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
            "PlaybackCore's sink-event collection, on AudioSinkEvent.DeviceLost and DeviceChanged, " +
                "and on FormatChangeRequested naming the request (SALANKE N11)",
        )
        is PlaybackWarning.AudioDeviceUnderrun -> listOf(
            "PlaybackCore's sink-event collection, on AudioSinkEvent.Underrun, once per session " +
                "(SALANKE N11; the feed used to be read and dropped)",
        )
        is PlaybackWarning.AudioUnderrun -> listOf(
            "PlaybackCore's stats pass, when the sink's underrun total moves",
        )
        is PlaybackWarning.AudioDrainIncomplete -> listOf(
            "PlaybackCore's end-of-stream drain, when the sink's drain deadline passes unfinished",
            "PlaybackCore's end-of-stream tail wait, when decoded audio does not reach the device in time",
        )
        is PlaybackWarning.ResourcesNotReleased -> listOf(
            "PlaybackCore.teardownSession, naming every close that failed while the session was released",
        )
        is PlaybackWarning.AudioLatencyUnreliable -> listOf(
            "PlaybackCore's open path, when the sink reports LatencyQuality.Unreliable",
        )
        is PlaybackWarning.HdrToneMapped -> listOf(
            "PlaybackCore.watchRendererEvents, on RendererEvent.ToneMapEngaged from the renderer " +
                "that actually rolled HDR off, latched once per open",
        )
        is PlaybackWarning.ColorApproximated -> listOf(
            "KiteFFmpegSource.warnIfColorIsApproximated in :kiteplayer-ffmpeg, once per stream, for " +
                "BT.2020 constant luminance alone since 2026-08-25",
        )
        // DELIBERATELY NEVER EMITTED. Deprecated 2026-08-25 (KP-TONEMAP-WARN): it conflated a true
        // BT.2020 CL claim with an HDR claim that was false on every built-in display path. Kept
        // for 0.x source compatibility; both emission sites are gone.
        is PlaybackWarning.TonemappingUnavailable -> emptyList()
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
            "KiteFFmpegMediaBackend.open, from MediaSource.unusedOpenOptions after the pre-open funnel ran",
        )
        is PlaybackWarning.CommandRefused -> listOf(
            "PlaybackCore's SetSpeed and SetPreservePitch handlers, refusing a live change on an unseekable source",
            "PlaybackCore's AttachRenderer and DetachRenderer handlers, when the scheduler never quiesced",
            "PlaybackCore.handleLoop, skipping the repeat an unseekable source cannot make",
        )
    }

    /**
     * Types that are DELIBERATELY never emitted, each with the reason it still exists.
     *
     * A warning with no emission site is normally a defect, which is what the audit below is for.
     * A deprecated one kept for source compatibility is the exception, and it has to be named here
     * rather than allowed to look like an oversight. Being in this set and naming a site is a
     * contradiction the audit refuses.
     */
    private val deliberatelyNeverEmitted: Map<String, String> = mapOf(
        "TonemappingUnavailable" to
            "deprecated 2026-08-25 (KP-TONEMAP-WARN): it conflated a true BT.2020 CL claim with " +
            "an HDR claim false on every built-in display path. Split into ColorApproximated and " +
            "HdrToneMapped; kept for 0.x source compatibility, both emission sites deleted",
    )

    @Test
    fun `every warning type names its emission sites and its message carries its facts`() {
        for (warning in samples) {
            val name = warning::class.simpleName
            val sites = documentedEmissionSites(warning)
            val neverEmitted = deliberatelyNeverEmitted[name]
            if (neverEmitted != null) {
                assertTrue(
                    sites.isEmpty(),
                    "$name is listed as never emitted ($neverEmitted) and also names a site: $sites",
                )
            } else {
                assertTrue(sites.isNotEmpty(), "$name documents no emission site")
            }
            assertTrue(warning.message.isNotBlank(), "$name has a blank message")
        }
        assertTrue(samples.size >= 15, "the census lost a row: ${samples.size}")
    }

    /** A name in the never-emitted set that is not in the census is a row that outlived its type. */
    @Test
    fun `the never emitted set names only types that still exist`() {
        val census = samples.map { it::class.simpleName }.toSet()
        for (name in deliberatelyNeverEmitted.keys) {
            assertTrue(name in census, "$name is excused from emission but is not a warning type")
        }
    }
}
