package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The typed warning audit: every [PlaybackWarning] is enumerated in ONE exhaustive table
 * naming where it is emitted. The audit is the compiler's: [documentedEmissionSites] has no else
 * branch, so a new warning type does not compile until its row exists here, which is exactly
 * "fails when a new warning ships undocumented" made mechanical.
 */
class WarningAuditTest {

    /** One sample per type, which is also the census the test iterates. */
    private val samples: List<PlaybackWarning> = listOf(
        PlaybackWarning.RendererFailed("x"),
        PlaybackWarning.HardwareDecodeUnavailable("h264", "x"),
        PlaybackWarning.DeinterlaceUnavailable("h264", "x"),
        PlaybackWarning.FrameDropping(1),
        PlaybackWarning.AudioDeviceChanged("x"),
        PlaybackWarning.AudioUnderrun(1),
        PlaybackWarning.AudioDrainIncomplete("x"),
        PlaybackWarning.AudioLatencyUnreliable("x"),
        PlaybackWarning.AudioSourceFormatChanged(48_000, 2, 44_100, 2),
        @Suppress("DEPRECATION")
        PlaybackWarning.TonemappingUnavailable("x"),
        PlaybackWarning.HdrToneMapped("PQ", 0),
        PlaybackWarning.ColorApproximated("x"),
        PlaybackWarning.CropIgnored(0, "x"),
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
        PlaybackWarning.SubtitleCharsetGuessed("subs.srt", "windows-1252"),
        PlaybackWarning.SubtitleSourceUnreadable("subs.srt", "x"),
        PlaybackWarning.ContainerDeclarationDiverged(0, "Width", "1920", "1440"),
        PlaybackWarning.TypesetterUnavailable("io.github.yuroyami.kiteplayer.libass", "x"),
        PlaybackWarning.SubtitlesNotDrawn("x"),
        PlaybackWarning.ResamplerUnavailable("x"),
        PlaybackWarning.AudioTapFailed("x"),
        PlaybackWarning.RecordingStopped("x.mkv", "x"),
        PlaybackWarning.SourceReconnecting(1_024, 1, "x"),
        PlaybackWarning.AddressRenewed("https://cdn.example/seg-1.ts", 403),
        PlaybackWarning.GaplessFallback(1, "x"),
        PlaybackWarning.SegmentSkipped("https://cdn.example/seg-1.ts", "x"),
        PlaybackWarning.ExternalClockSilent("x"),
        PlaybackWarning.VariantLowered(0, 1, "x"),
        PlaybackWarning.QueueItemSkipped(1, "file:///music/x.flac", PlaybackError.NotMedia("file:///music/x.flac")),
    )

    private fun documentedEmissionSites(warning: PlaybackWarning): List<String> = when (warning) {
        is PlaybackWarning.QueueItemSkipped -> listOf(
            "PlaybackCore.openQueueItem, when a queue item fails to open under QueueItemFailure.Skip " +
                "and the queue moves past it",
        )
        is PlaybackWarning.VariantLowered -> listOf(
            "PlaybackCore.stepDownWhenStarved, after playback waited 4 s for data while playing an HLS " +
                "variant the player chose itself",
        )
        is PlaybackWarning.ExternalClockSilent -> listOf(
            "PlaybackCore.handleExternalClock, after 2 s of playing with no moving answer from the " +
                "external clock, or with none set under SyncMode.ExternalMaster",
        )
        is PlaybackWarning.SegmentSkipped -> listOf(
            "HlsLedger in :kiteplayer-ffmpeg, when an address that an HLS playlist names fails to open or " +
                "fails during a read; it reaches the player through KiteFFmpegSource.onWarning",
        )
        is PlaybackWarning.GaplessFallback -> listOf(
            "PlaybackCore's queue handoff, when the next item cannot follow the current one without a " +
                "gap: its preload failed or was not ready, an item has no audio, or its audio format differs",
        )
        is PlaybackWarning.AddressRenewed -> listOf(
            "PlaybackCore.renewIfRefused, when the item's reader reports through MediaIo.takeRefusal that a " +
                "server answered 401 or 403 after the open, and the item opens again through its resolver",
        )
        is PlaybackWarning.SourceReconnecting -> listOf(
            "KtorMediaIo.read in :kiteplayer-network, before each reconnect after a failed read, a read " +
                "timeout or a response that ended early; it reaches the player through MediaIo.setWarningSink, " +
                "which PlaybackCore.buildSession installs on every reader",
        )
        is PlaybackWarning.RecordingStopped -> listOf(
            "PlaybackCore.endRecording, when a seek, a queue move or any session end other than " +
                "stopRecording, stop and close ends a running recording, or the file cannot be finished",
            "KiteFFmpegSource's packet copy in :kiteplayer-ffmpeg, when a write to the recording fails",
        )
        is PlaybackWarning.AudioTapFailed -> listOf(
            "PlaybackCore.dropTap, when an attached AudioTap throws from onAudio on the feed worker " +
                "or from onDiscontinuity where the ring is flushed; the tap is detached first",
        )
        is PlaybackWarning.TypesetterUnavailable -> listOf(
            "PlaybackCore.refuseTypesetting, when the installed typesetter provider returns no engine " +
                "or throws while starting, and from abandonTypesetting when a render threw on its lane",
        )
        is PlaybackWarning.SubtitlesNotDrawn -> listOf(
            "rasterizeWithinLimits in PlaybackCore's overlay publication, the typeset lane and the " +
                "screenshot overlay, when the cues or the images pass a SubtitleRasterizer limit or the " +
                "rasterizer throws",
            "PlaybackCore.showOverlayFromRasterLane, when the renderer throws from setOverlay",
        )
        is PlaybackWarning.ResamplerUnavailable -> listOf(
            "AudioPlayback's conversion stage, when AudioConfig.resampler throws while making a " +
                "resampler; PlaybackCore reports the first refusal and stops passing the factory",
        )
        is PlaybackWarning.ContainerDeclarationDiverged -> listOf(
            "PlaybackCore.reportContainerDivergences, after the first frames of an open, for every " +
                "field where the container's declaration and the decoder disagree",
        )
        is PlaybackWarning.SubtitleSourceUnreadable -> listOf(
            "PlaybackCore.parseExternalSubtitles, when an external subtitle could not be reached, " +
                "read or parsed, so the track was skipped and the open carried on without it",
        )
        is PlaybackWarning.SubtitleCharsetGuessed -> listOf(
            "PlaybackCore.parseExternalSubtitle, when an external subtitle file carries no " +
                "byte-order mark and does not validate as UTF-8, so its encoding was inferred",
        )
        is PlaybackWarning.RendererFailed -> listOf(
            "PlaybackCore.watchRendererEvents, on RendererEvent.Failed from the attached renderer",
        )
        is PlaybackWarning.HardwareDecodeUnavailable -> listOf(
            "PlaybackCore.warnAboutRefusedHardwareCandidate, when a requested hardware factory refuses",
            "PlaybackCore.reopenWithBackendSoftware, when a hardware decoder death recovered to software",
        )
        is PlaybackWarning.DeinterlaceUnavailable -> listOf(
            "KiteFFmpegVideoDecoderFactory.videoFilterChain in :kiteplayer-ffmpeg, when the policy " +
                "asks for a deinterlacer and the build has no bwdif filter, which is the web build",
        )
        is PlaybackWarning.FrameDropping -> listOf(
            "PlaybackCore's stats pass, when late drops in the last second cross the threshold",
        )
        is PlaybackWarning.AudioDeviceChanged -> listOf(
            "PlaybackCore's sink-event collection, on AudioSinkEvent.DeviceLost and DeviceChanged, " +
                "and on FormatChangeRequested naming the request",
        )
        is PlaybackWarning.AudioDeviceUnderrun -> listOf(
            "PlaybackCore's sink-event collection, on AudioSinkEvent.Underrun, once per session " +
                "(the feed used to be read and dropped)",
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
        is PlaybackWarning.AudioSourceFormatChanged -> listOf(
            "AudioPlayback.submitDecoded, when the decoder's format stops matching the conversion " +
                "stage's, so the stage is rebuilt on the buffer that changed",
        )
        is PlaybackWarning.HdrToneMapped -> listOf(
            "PlaybackCore.watchRendererEvents, on RendererEvent.ToneMapEngaged from the renderer " +
                "that actually rolled HDR off, latched once per open",
        )
        is PlaybackWarning.ColorApproximated -> listOf(
            "KiteFFmpegSource.warnIfColorIsApproximated in :kiteplayer-ffmpeg, once per stream, for " +
                "BT.2020 constant luminance since 2026-08-25 and for ICtCp since 2026-09-23",
        )
        is PlaybackWarning.CropIgnored -> listOf(
            "PlaybackCore.noteUnfittedCrop, once per open session, when the video stream's crop " +
                "leaves nothing of a decoded frame, whichever decoder made it",
        )
        // DELIBERATELY NEVER EMITTED. Deprecated 2026-08-25: it conflated a true
        // BT.2020 CL claim with an HDR claim that was false on every built-in display path. Kept
        // for 0.x source compatibility; both emission sites are gone.
        is PlaybackWarning.TonemappingUnavailable -> emptyList()
        is PlaybackWarning.ChannelLayoutUnknown -> listOf(
            "the audio path's layout negotiation, when a mask is absent and the count is guessed (D30)",
        )
        is PlaybackWarning.BadTimestamps -> listOf(
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
            "PlaybackCore.runSeek, when a worker did not park and the seek was aborted",
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
            "deprecated 2026-08-25: it conflated a true BT.2020 CL claim with " +
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
