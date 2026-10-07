@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.FrameShape
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The messages between the page and a worker player (#100) come back as they went. Each message
 * is encoded to the JS object `postMessage` would copy and decoded from it, so a field that one
 * side writes under one name and the other reads under another fails here rather than in a browser.
 *
 * Every command, control, answer and failure is here, every event, warning and error, and a
 * snapshot and statistics with every field off its default.
 */
class WorkerProtocolTest {

    private val item = MediaItem(
        uri = "https://example.com/a b.mkv",
        headers = mapOf("Authorization" to "Bearer x", "X-Empty" to ""),
        externalSubtitles = listOf(
            SubtitleSource("https://example.com/a.srt", title = "English", language = "en", selectImmediately = true),
            SubtitleSource("https://example.com/b.vtt"),
        ),
        videoFilter = "hflip",
        startPosition = 90.seconds,
        formatHint = "matroska",
        openOptions = mapOf("probesize" to "32"),
        demux = DemuxPolicy(
            probe = ProbeDepth.Custom(1_000_000L, 2.seconds),
            corruptPackets = CorruptPackets.Drop,
            generateTimestamps = true,
            lowLatency = true,
            skipInitialBytes = 188L,
            maxBitrate = 5_000_000L,
            maxVideoHeight = 720,
            variant = 2,
            program = 202,
        ),
        title = "Title",
        artist = "Artist",
        album = "Album",
        audioFilter = "volume=0.5",
        audioContent = AudioContent.Speech,
        clip = MediaClip(start = 61.seconds, end = 3.minutes),
        growth = FileGrowth(endsAfter = 5.seconds),
        thumbnails = ThumbnailSource("https://example.com/thumbs.vtt"),
        runsIntoNext = true,
    )

    private val tracks = Tracks(
        all = listOf(
            TrackInfo(
                TrackId(0), TrackKind.Video, "h264", language = "und", title = "Main", isDefault = true,
                bitrate = 4_000_000L, videoSize = VideoSize(1920, 1080, 4, 3), frameRate = 23.976,
                metadata = mapOf("handler" to "VideoHandler"),
                dolbyVision = DolbyVisionInfo(profile = 7, level = 6, baseLayerCompatibility = 6, hasEnhancementLayer = true),
            ),
            TrackInfo(
                TrackId(1), TrackKind.Audio, "aac", language = "jpn", isForced = true, isAccessibility = true,
                sampleRate = 48_000, channels = 6,
            ),
            TrackInfo(TrackId(2), TrackKind.Subtitle, "ass", language = "eng", isCoverArt = true),
            TrackInfo(TrackId(-1), TrackKind.Subtitle, "subrip", title = "External"),
        ),
        selectedVideo = TrackId(0),
        selectedAudio = TrackId(1),
        selectedSubtitle = TrackId(2),
        selectedSecondarySubtitle = TrackId(-1),
        variants = listOf(StreamVariant(0, 800_000L, 640, 360, 30.0, "avc1.4d401e"), StreamVariant(1, 3_000_000L)),
        selectedVariant = 1,
        programs = listOf(
            MediaProgram(101, listOf(TrackId(0), TrackId(1)), "ChannelA", "Kite", mapOf("service_name" to "ChannelA")),
            MediaProgram(202, listOf(TrackId(2))),
        ),
        selectedProgram = 202,
        thumbnails = ThumbnailSet(width = 160, height = 90, interval = 10.seconds),
    )

    private val style = SubtitleStyleOverride(
        fontFamily = "Noto Sans",
        fontSizePx = 42f,
        primaryColor = 0xFFFFFF00.toInt(),
        outlineColor = 0xFF000000.toInt(),
        outlineWidthPx = 2f,
        shadowColor = 0x80000000.toInt(),
        shadowOffsetPx = 1.5f,
        backgroundColor = 0x40000000,
        backgroundPaddingPx = 6f,
        bold = true,
        italic = false,
    )

    private val snapshot = PlayerSnapshot(
        status = PlaybackStatus.Playing,
        media = item,
        duration = 600.seconds,
        seekable = true,
        videoSize = VideoSize(1920, 1080, 4, 3),
        tracks = tracks,
        chapters = listOf(Chapter(0, Duration.ZERO, 60.seconds, "Opening"), Chapter(1, 60.seconds, null, null)),
        markers = listOf(Marker(30.seconds, "intro-end")),
        metadata = mapOf("title" to "Ünïcødé ✓"),
        speed = 1.5,
        volume = 0.25f,
        muted = true,
        loop = LoopMode.All,
        videoScale = VideoScale.Fill,
        videoAdjustments = VideoAdjustments(0.1f, 1.2f, 0.8f, -30f, 1.5f),
        renderQuality = RenderQuality(true, true, 32f, 8f, 16f, VideoScaler.CatmullRom, true, AnimationUpscaler.Fast),
        videoTransform = VideoTransform(2.39f, 1.5f, -0.25f, 0.5f, rotationDegrees = 270, mirrorHorizontal = true, mirrorVertical = true),
        subtitleDelay = (-1500).milliseconds,
        subtitleScale = 1.25f,
        subtitleStyle = style,
        subtitlePosition = 0.9f,
        forcedPicturesOnly = true,
        subtitleTypesetter = "libass",
        audioDelay = 200.milliseconds,
        abLoopA = 10.seconds,
        abLoopB = 20.seconds,
        preservePitch = false,
        error = PlaybackError.SourceStalled("https://example.com/x", 30.seconds),
        generation = Generation(42),
        queue = listOf(item, MediaItem("https://example.com/b.mp4")),
        queueIndex = 0,
        audioSessionId = 17,
        appliedReplayGainDb = -6.5f,
        balance = -0.5f,
        videoEnabled = false,
        sleepTimer = SleepTimer.After(15.minutes),
        equalizer = EqualizerSettings(listOf(1f, 2f, 3f, 4f, 5f, -1f, -2f, -3f, -4f, -5.5f), -3f),
        shuffle = true,
        queueOrder = listOf(1, 0),
        playRequested = true,
        preloadedIndex = 1,
        hdrPolicy = HdrPolicy.ToneMap,
        videoDynamicRange = VideoDynamicRange.ToneMapped,
        failedQueueItems = setOf(1),
        durationIsEstimate = true,
        stereoMode = StereoMode.Swapped,
        nightMode = true,
        dialogueLevelDb = 4.5f,
        pitchSemitones = -2.5,
        skipSilence = true,
        lyrics = "A line\nAnother line",
    )

    private val stats = PlaybackStats(
        decodedVideoFrames = 1L, submittedFrames = 2L, headlessFrames = 3L, droppedFramesLate = 4L,
        refusedFrames = 5L, droppedFramesDecode = 6L, repeatedFrames = 7L, audioUnderruns = 8L,
        rebuffers = 9L, droppedEvents = 10L, avDrift = (-12).milliseconds, videoDecodeFps = 29.97,
        videoQueueDepth = 500.milliseconds, audioQueueDepth = 250.milliseconds, audioLatency = 40.milliseconds,
        audioLatencyQuality = LatencyQuality.Estimated, hardwareDecode = HwdecStatus.HardwareWithDownload(HwdecKind.WebCodecs),
        ioBytesTotal = 123_456_789L, ioBytesPerSecond = 1_000_000L, decodeTimeP50 = 3_100.microseconds,
        decodeTimeP95 = 9_800.microseconds, presentLatenessP95 = (-2).milliseconds, containerBitrate = 4_500_000L,
        syncMode = SyncMode.AudioMaster, masterClock = MasterClock.Audio, audioLimitedFrames = 31L,
    )

    @Suppress("DEPRECATION")
    private val warnings = listOf(
        PlaybackWarning.RendererFailed("lost context"),
        PlaybackWarning.OptionsUnused(listOf("probesize", "typo")),
        PlaybackWarning.DeinterlaceUnavailable("mpeg2video", "no filters"),
        PlaybackWarning.HardwareDecodeUnavailable("hevc", "no decoder"),
        PlaybackWarning.FrameDropping(12),
        PlaybackWarning.AudioDeviceChanged("device lost: headphones"),
        PlaybackWarning.AudioUnderrun(3L),
        PlaybackWarning.SourceReconnecting(1_048_576L, 2, "reset"),
        PlaybackWarning.AddressRenewed("https://cdn.test/seg-3.ts", 403),
        PlaybackWarning.GrowthUnavailable("recording.ts"),
        PlaybackWarning.AudioTapFailed("threw"),
        PlaybackWarning.AudioDeviceUnderrun("ran dry"),
        PlaybackWarning.AudioDrainIncomplete("bounded out"),
        PlaybackWarning.AudioLatencyUnreliable("counted"),
        PlaybackWarning.AudioSourceFormatChanged(48_000, 2, 44_100, 6),
        PlaybackWarning.HdrToneMapped("smpte2084", 0),
        PlaybackWarning.ColorApproximated("bt2020c"),
        PlaybackWarning.CropIgnored(1, "left 1920 of 1920 columns"),
        PlaybackWarning.TonemappingUnavailable("old"),
        PlaybackWarning.ChannelLayoutUnknown(3, "guessed"),
        PlaybackWarning.BadTimestamps("non monotonic"),
        PlaybackWarning.TrackDeselected(TrackId(-2), "unreadable"),
        PlaybackWarning.ContainerDeclarationDiverged(0, "width", "1920", "1440"),
        PlaybackWarning.SubtitleSourceUnreadable("https://example.com/a.srt", "404"),
        PlaybackWarning.ThumbnailsUnreadable("https://example.com/thumbs.vtt", "404"),
        PlaybackWarning.SubtitleCharsetGuessed("https://example.com/a.srt", "windows-1252", "Shift_JIS"),
        PlaybackWarning.SubtitleCharsetGuessed("https://example.com/b.srt", "windows-1252"),
        PlaybackWarning.TypesetterUnavailable("libass", "no module"),
        PlaybackWarning.SubtitlesNotDrawn("too many cues"),
        PlaybackWarning.ResamplerUnavailable("none on the web"),
        PlaybackWarning.CommandRefused("setSpeed", "speed must be within 0.25..4.0, was 10.0"),
        PlaybackWarning.StartupIncomplete("slow"),
        PlaybackWarning.ResourcesNotReleased("decoder"),
        PlaybackWarning.StartPositionIgnored(90.seconds, "not seekable"),
        PlaybackWarning.PathologicalInterleaving(TrackId(1), 40),
        PlaybackWarning.NoRenderSurface("gone"),
        PlaybackWarning.RecordingStopped("/tmp/a.mkv", "a seek"),
        PlaybackWarning.GaplessFallback(3, "different rate"),
        PlaybackWarning.SegmentSkipped("https://example.com/seg1.ts", "HTTP 500"),
        PlaybackWarning.ExternalClockSilent("none set"),
        PlaybackWarning.VariantLowered(2, 1, "waited 3s"),
        PlaybackWarning.QueueItemSkipped(1, "https://example.com/gone.mp4", PlaybackError.SourceUnavailable("https://example.com/gone.mp4", null, "HTTP 404")),
    )

    private val errors = listOf(
        PlaybackError.SourceUnavailable("https://example.com/x", null, "HTTP 404"),
        PlaybackError.SourceUnavailable("https://example.com/y", null),
        PlaybackError.SourceStalled("https://example.com/x", 30.seconds),
        PlaybackError.SchemeUnsupported("srt://example.com:9000", "srt", "SRT needs libsrt"),
        PlaybackError.SchemeUnsupported("rtsp://camera/stream", "rtsp"),
        PlaybackError.NotMedia("https://example.com/x", "no stream"),
        PlaybackError.NoPlayableStream(tracks.all),
        PlaybackError.DecoderFailed("hevc", "no decoder"),
        PlaybackError.RuntimeCompromised("teardown"),
        PlaybackError.ConfigurationInvalid("a refused part"),
        PlaybackError.AudioDeviceUnavailable("speaker-2", "gone"),
        PlaybackError.RendererIncompatible("canvas", FrameShape.Surface(HwSurfaceKind.WebVideoFrame, PlayerPixelFormat.Opaque)),
        PlaybackError.RendererIncompatible("canvas", FrameShape.Memory(PlayerPixelFormat.Yuv420p10le)),
        PlaybackError.Internal("the worker stopped"),
    )

    private fun PageMessage.roundTrip(): PageMessage? = decodePageMessage(encode())

    private fun WorkerMessage.roundTrip(): WorkerMessage? = decodeWorkerMessage(encode())

    @Test
    fun everyCommandComesBackAsItWent() {
        val commands = listOf(
            Command.Open(item),
            Command.Open(MediaItem("blob:https://example.com/0")),
            Command.OpenQueue(listOf(item, MediaItem("https://example.com/b.mp4", clip = MediaClip(start = 4.seconds))), 1),
            Command.Seek(9_007_199_254_740_991L.microseconds, SeekMode.Precise),
            Command.Seek(Duration.INFINITE, SeekMode.Keyframe),
            Command.Seek((-1).seconds, SeekMode.KeyframeThenRefine),
            Command.Stop,
            Command.Next,
            Command.Previous,
            Command.AddToQueue(listOf(item), null),
            Command.AddToQueue(listOf(MediaItem("https://example.com/c.mp4")), 0),
            Command.RemoveFromQueue(2),
            Command.MoveInQueue(0, 3),
            Command.ClearQueue,
            Command.StepFrame(StepDirection.Backward),
            Command.SeekToChapter(4),
            Command.NextChapter,
            Command.PreviousChapter,
            Command.SelectTrack(TrackKind.Subtitle, TrackId(-1)),
            Command.SelectTrack(TrackKind.Audio, null),
            Command.SelectSecondarySubtitle(TrackId(3)),
            Command.SelectSecondarySubtitle(null),
            Command.SelectVariant(2),
            Command.SelectVariant(null),
            Command.SelectProgram(202),
            Command.SelectProgram(null),
            Command.AddExternalSubtitle(SubtitleSource("https://example.com/c.srt", "Commentary", "en", true)),
            Command.AddExternalSubtitle(SubtitleSource("https://example.com/d.srt", encoding = "windows-1250")),
            Command.ReloadExternalSubtitle(TrackId(-2), "windows-874"),
            Command.ReloadExternalSubtitle(TrackId(-1), null),
            Command.DiagnosticsDump,
            Command.SupportBundle,
            Command.WarningHistory,
        )
        commands.forEachIndexed { id, command ->
            val message = PageMessage.Call(id, command)
            assertEquals(message, message.roundTrip(), "the call ${command.member} changed on the way")
        }
        assertEquals(23, commands.map { it.member }.distinct().size, "every command is here")
    }

    @Test
    fun everyControlComesBackAsItWent() {
        val controls = listOf(
            Control.Play,
            Control.Pause,
            Control.SetViewport(640, 360, 2f),
            Control.RequestSeek(42.seconds, SeekMode.KeyframeThenRefine),
            Control.SetSpeed(1.5),
            Control.SetPreservePitch(false),
            Control.SetPreservePitch(true),
            Control.SetKeyframeChoice(KeyframeChoice.Closest),
            Control.SetVolume(0.5f),
            Control.SetDuckLevel(0.2f),
            Control.SetBalance(-0.75f),
            Control.SetStereoMode(StereoMode.LeftOnly),
            Control.SetNightMode(true),
            Control.SetDialogueLevel(-3.5f),
            Control.SetPitch(7.0),
            Control.SetSkipSilence(true),
            Control.SetMuted(true),
            Control.SetVideoEnabled(false),
            Control.SetLoop(LoopMode.One),
            Control.SetShuffle(true, Long.MAX_VALUE - 1),
            Control.SetShuffle(false, null),
            Control.SetAbLoop(5.seconds, 7.seconds),
            Control.SetAbLoop(5.seconds, null),
            Control.SetAbLoop(null, null),
            Control.SetVideoScale(VideoScale.Stretch),
            Control.SetVideoAdjustments(snapshot.videoAdjustments),
            Control.SetRenderQuality(snapshot.renderQuality),
            Control.SetVideoTransform(snapshot.videoTransform),
            Control.SetVideoTransform(VideoTransform.Identity),
            Control.SetHdrPolicy(HdrPolicy.ToneMap),
            Control.SetSubtitleDelay((-250).milliseconds),
            Control.SetSubtitleScale(1.25f),
            Control.SetSubtitleStyle(style),
            Control.SetSubtitleStyle(SubtitleStyleOverride(bold = false)),
            Control.SetSubtitleStyle(null),
            Control.SetSubtitlePosition(0.85f),
            Control.SetForcedPicturesOnly(true),
            Control.SetForcedPicturesOnly(false),
            Control.SetSubtitleSafeArea(SubtitleSafeArea(0.05f, 0.1f, 0.15f, 0.2f)),
            Control.SetAudioDelay(Duration.INFINITE),
            Control.SetSleepTimer(SleepTimer.After(30.minutes), 5.seconds),
            Control.SetSleepTimer(SleepTimer.At(90.seconds), Duration.ZERO),
            Control.SetSleepTimer(SleepTimer.EndOfItem, KitePlayer.DEFAULT_SLEEP_FADE),
            Control.SetSleepTimer(null, KitePlayer.DEFAULT_SLEEP_FADE),
            Control.SetEqualizer(snapshot.equalizer),
            Control.SetMarkers(listOf(Marker(1.seconds, "a"), Marker(2.seconds, "b"))),
            Control.SetMarkers(emptyList()),
        )
        for (control in controls) {
            val message = PageMessage.Send(control)
            assertEquals(message, message.roundTrip(), "the control ${control.member} changed on the way")
        }
        assertEquals(35, controls.map { it.member }.distinct().size, "every control is here")
    }

    @Test
    fun theOtherPageMessagesComeBackAsTheyWent() {
        val messages = listOf(
            PageMessage.Init("https://example.com/kite.mjs", 48_000, 2, 0.02, "https://example.com/kiteass.mjs"),
            PageMessage.Init("./kite.mjs", 0, 0, null),
            PageMessage.Close(5),
        )
        for (message in messages) assertEquals(message, message.roundTrip())
    }

    @Test
    fun everyAnswerAndFailureComesBackAsItWent() {
        val replies = listOf(
            WorkerMessage.Reply(1),
            WorkerMessage.Reply(2, answer = Answer.Change(TrackChange.Applied(TrackKind.Subtitle, TrackId(2)))),
            WorkerMessage.Reply(3, answer = Answer.Change(TrackChange.Applied(TrackKind.Audio, null))),
            WorkerMessage.Reply(4, answer = Answer.Change(TrackChange.Superseded(TrackKind.Video, TrackId(5)))),
            WorkerMessage.Reply(5, answer = Answer.Change(TrackChange.Discarded("a stop came first"))),
            WorkerMessage.Reply(6, answer = Answer.Track(TrackId(-3))),
            WorkerMessage.Reply(7, answer = Answer.Text("KitePlayer support bundle\nplatform wasmJs")),
            WorkerMessage.Reply(8, answer = Answer.Warnings(warnings.mapIndexed { i, w -> TimedWarning(Long.MAX_VALUE - i, w) })),
            WorkerMessage.Reply(9, answer = Answer.Warnings(emptyList())),
            WorkerMessage.Reply(10, Failure.Playback(PlaybackError.NotMedia("https://example.com/x"))),
            WorkerMessage.Reply(11, Failure.Argument("speed must be within 0.25..4.0")),
            WorkerMessage.Reply(12, Failure.State("nothing is open")),
            WorkerMessage.Reply(13, Failure.Unsupported("the source is not seekable")),
        )
        for (reply in replies) assertEquals(reply, reply.roundTrip())
    }

    @Test
    fun everyEventComesBackAsItWent() {
        val events = listOf(
            PlayerEvent.Opened(item, tracks),
            PlayerEvent.SeekCompleted(Generation(7), 61_500.milliseconds),
            PlayerEvent.VideoSizeChanged(VideoSize(1280, 720, 1, 1)),
            PlayerEvent.AudioFormatChanged(44_100, 6),
            PlayerEvent.FirstFrameRendered(41.milliseconds),
            PlayerEvent.FramePresented(Pts(1_001_000L), Long.MAX_VALUE - 7, 3.milliseconds, true),
            PlayerEvent.Ended,
            PlayerEvent.Warning(warnings.first()),
            PlayerEvent.Failed(errors.first()),
            PlayerEvent.ChapterChanged(Chapter(2, 120.seconds, 180.seconds, "Middle")),
            PlayerEvent.ChapterChanged(null),
            PlayerEvent.MarkerReached(Marker(30.seconds, "intro-end")),
            PlayerEvent.TracksAdded(tracks.all.takeLast(2)),
            PlayerEvent.TrackChosenByPlayer(TrackKind.Audio, TrackId(3)),
            PlayerEvent.TrackChosenByPlayer(TrackKind.Subtitle, null),
        )
        for (event in events) assertEquals(WorkerMessage.Event(event), WorkerMessage.Event(event).roundTrip())
        assertEquals(13, events.map { it::class }.distinct().size, "every kind of event is here")
    }

    @Test
    fun everyWarningComesBackOfTheSameKind() {
        for (warning in warnings) {
            val message = WorkerMessage.Event(PlayerEvent.Warning(warning))
            assertEquals(message, message.roundTrip(), "the warning $warning changed on the way")
        }
        assertEquals(41, warnings.map { it::class }.distinct().size, "every kind of warning is here")
    }

    @Test
    fun everyErrorComesBackOfTheSameKind() {
        for (error in errors) {
            val message = WorkerMessage.Event(PlayerEvent.Failed(error))
            assertEquals(message, message.roundTrip(), "the error $error changed on the way")
        }
        assertEquals(11, errors.map { it::class }.distinct().size, "every kind of error is here")
    }

    @Test
    fun theStateProgressAndStatsComeBackAsTheyWent() {
        val messages = listOf(
            WorkerMessage.Hello,
            WorkerMessage.Ready,
            WorkerMessage.InitFailed("the codec module did not load"),
            WorkerMessage.State(snapshot),
            WorkerMessage.State(PlayerSnapshot()),
            WorkerMessage.Progressed(Progress(12_345_678L.microseconds, 2.seconds, listOf(0.seconds..30.seconds))),
            WorkerMessage.Progressed(Progress()),
            WorkerMessage.Stats(stats),
            WorkerMessage.Stats(stats.copy(hardwareDecode = HwdecStatus.HardwareZeroCopy(HwdecKind.VideoToolbox), containerBitrate = null)),
            WorkerMessage.Stats(PlaybackStats()),
            WorkerMessage.Audio(true),
            WorkerMessage.Audio(false),
        )
        for (message in messages) assertEquals(message, message.roundTrip())
    }

    @Test
    fun aMessageOfAnotherKindIsIgnored() {
        assertNull(decodePageMessage(null))
        assertNull(decodeWorkerMessage(null))
        assertNull(decodePageMessage(WorkerMessage.Ready.encode()))
        assertNull(decodeWorkerMessage(PageMessage.Send(Control.Play).encode()))
        assertNull(decodeWorkerMessage(jsObject()))
        assertNull(decodePageMessage(unknownCommand()))
        assertNull(decodeWorkerMessage(unknownEvent()))
    }

    @Test
    fun aValueItsTypeRefusesMakesNoMessage() {
        assertNull(decodePageMessage(threeBandEqualizer()), "an equaliser has ten bands, and the decoder must not throw")
    }

    @Test
    fun aReplyThatCannotBeReadStillEndsItsCall() {
        val reply = assertIs<WorkerMessage.Reply>(decodeWorkerMessage(replyWithUnknownAnswer()))
        assertEquals(7, reply.id)
        val failure = assertIs<Failure.Playback>(reply.failure)
        assertIs<PlaybackError.Internal>(failure.error)
    }

    @Test
    fun aFailureIsThrownOnThePageAsTheKindKitePlayerThrows() {
        val error = PlaybackError.NotMedia("https://example.com/x")
        assertEquals(Failure.Playback(error), Failure.of(PlaybackException(error)))
        assertEquals(Failure.Argument("bad"), Failure.of(IllegalArgumentException("bad")))
        assertEquals(Failure.State("closed"), Failure.of(IllegalStateException("closed")))
        assertEquals(Failure.Unsupported("no"), Failure.of(UnsupportedOperationException("no")))
        assertEquals(Failure.Playback(PlaybackError.Internal("boom")), Failure.of(RuntimeException("boom")))

        assertEquals(error, assertIs<PlaybackException>(Failure.Playback(error).toException()).error)
        assertIs<IllegalArgumentException>(Failure.Argument("bad").toException())
        assertIs<UnsupportedOperationException>(Failure.Unsupported("no").toException())
        assertIs<IllegalStateException>(Failure.State("closed").toException())
    }

    @Test
    fun aCancellationInTheWorkerDoesNotCancelThePageCaller() {
        val failure = Failure.of(CancellationException("the worker's call was cancelled"))
        assertEquals(Failure.State("the worker's call was cancelled"), failure)
        val thrown = failure.toException()
        assertIs<IllegalStateException>(thrown)
        assertFalse(thrown is CancellationException, "a cancellation on the page would cancel the caller's coroutine")
    }

    @Test
    fun anItemWithAReaderOfItsOwnIsRefused() {
        val reader = MediaIo.ofBytes(ByteArray(1))
        val refused = listOf(
            MediaItem.from(reader, "bytes.mp4"),
            MediaItem("https://example.com/a.mp4", externalSubtitles = listOf(SubtitleSource("a.srt", io = reader))),
        )
        for (media in refused) {
            assertIs<PlaybackError.ConfigurationInvalid>(crossingRefusal(media)?.error, "$media must be refused")
        }
        assertIs<PlaybackError.ConfigurationInvalid>(crossingRefusal(SubtitleSource("a.srt", io = reader))?.error)
        assertNull(crossingRefusal(item), "an item with addresses only crosses whole")
        assertNull(crossingRefusal(SubtitleSource("a.srt")))
    }

    @Test
    fun aNumberPast2To53CrossesExactly() {
        val seed = Long.MAX_VALUE - 1
        assertTrue(seed.toDouble().toLong() != seed, "a JS number would round this seed")
        val crossed = assertIs<PageMessage.Send>(PageMessage.Send(Control.SetShuffle(true, seed)).roundTrip())
        assertEquals(seed, assertIs<Control.SetShuffle>(crossed.control).seed)
    }
}

@JsFun("() => ({ t: 'call', id: 3, command: { t: 'notAMember' } })")
private external fun unknownCommand(): JsAny

@JsFun("() => ({ t: 'event', event: { t: 'NotAnEvent' } })")
private external fun unknownEvent(): JsAny

@JsFun("() => ({ t: 'send', control: { t: 'setEqualizer', value: { gainsDb: [1, 2, 3], preampDb: 0 } } })")
private external fun threeBandEqualizer(): JsAny

@JsFun("() => ({ t: 'reply', id: 7, answer: { t: 'notAnAnswer' } })")
private external fun replyWithUnknownAnswer(): JsAny
