@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.FrameShape
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/*
 * The messages between the page and a player in a Web Worker (#100), written by hand.
 *
 * Each message is a plain JS object with a type in `t`, because a plain object is what
 * `postMessage` copies fastest and what a person reading the traffic in the browser's tools can
 * read. Each has an encoder and a decoder here, and nothing else in the module builds or reads one.
 *
 * The public types cross as themselves: the encoders read a PlayerSnapshot or a PlaybackWarning
 * and the decoders build one, with no record type in between. Every `when` over PlayerEvent,
 * PlaybackWarning and PlaybackError names each kind and has no `else`, so a kind added to the core
 * stops this build, beside the decoder that needs it too.
 *
 * Times cross in microseconds as JS numbers, which are exact to 285 years, and an infinite one as
 * JS Infinity. A number that may pass 2^53, a shuffle seed or a monotonic clock reading, crosses as
 * a decimal string. A part of a type that cannot cross, such as an error's cause or an item's own
 * reader, is left behind.
 */

/** What the page sends the worker. */
internal sealed interface PageMessage {
    /**
     * The first message. The canvas and the audio port travel beside it, as transferables. Both
     * addresses are whole, made so on the page; a null [libassUrl] leaves libass to load on the
     * first ASS track, from beside the worker binary.
     */
    data class Init(
        val codecUrl: String,
        val sampleRate: Int,
        val channels: Int,
        val latencySeconds: Double?,
        val libassUrl: String? = null,
    ) : PageMessage

    /** Runs [command] and gets exactly one [WorkerMessage.Reply] with the same [id]. */
    data class Call(val id: Int, val command: Command) : PageMessage

    /** Closes the player and ends the worker, replied to as a call is. */
    data class Close(val id: Int) : PageMessage

    /**
     * Applies [control] with no reply. A control the player refuses comes back as
     * [PlaybackWarning.CommandRefused] on the events, named by [Control.member].
     */
    data class Send(val control: Control) : PageMessage
}

/** A player call that waits for the worker. [member] is the `KitePlayer` member it runs, and its key. */
internal sealed class Command(val member: String) {
    data class Open(val item: MediaItem) : Command("open")
    data class OpenQueue(val items: List<MediaItem>, val startIndex: Int) : Command("openQueue")
    data class Seek(val to: Duration, val mode: SeekMode) : Command("seek")
    data object Stop : Command("stop")
    data object Next : Command("next")
    data object Previous : Command("previous")
    data class AddToQueue(val items: List<MediaItem>, val index: Int?) : Command("addToQueue")
    data class RemoveFromQueue(val index: Int) : Command("removeFromQueue")
    data class MoveInQueue(val from: Int, val to: Int) : Command("moveInQueue")
    data object ClearQueue : Command("clearQueue")
    data class StepFrame(val direction: StepDirection) : Command("stepFrame")

    /** Run in the worker, which knows the exact position the chapter commands start from. */
    data class SeekToChapter(val index: Int) : Command("seekToChapter")
    data object NextChapter : Command("nextChapter")
    data object PreviousChapter : Command("previousChapter")
    data class SelectTrack(val kind: TrackKind, val track: TrackId?) : Command("selectTrack")
    data class SelectSecondarySubtitle(val track: TrackId?) : Command("selectSecondarySubtitle")
    data class SelectVariant(val index: Int?) : Command("selectVariant")
    data class AddExternalSubtitle(val source: SubtitleSource) : Command("addExternalSubtitle")
    data object DiagnosticsDump : Command("diagnosticsDump")
    data object SupportBundle : Command("supportBundle")
    data object WarningHistory : Command("warningHistory")
}

/**
 * A player call that does not wait. [member] is the `KitePlayer` member it runs, its key, and the
 * member a refusal names.
 */
internal sealed class Control(val member: String) {
    data object Play : Control("play")
    data object Pause : Control("pause")

    /** The renderer's call rather than the player's: the canvas belongs to the worker. */
    data class SetViewport(val width: Int, val height: Int, val scale: Float) : Control("setViewport")
    data class RequestSeek(val to: Duration, val mode: SeekMode) : Control("requestSeek")
    data class SetSpeed(val value: Double) : Control("setSpeed")
    data class SetPreservePitch(val value: Boolean) : Control("setPreservePitch")
    data class SetVolume(val value: Float) : Control("setVolume")
    data class SetDuckLevel(val level: Float) : Control("setDuckLevel")
    data class SetBalance(val value: Float) : Control("setBalance")
    data class SetMuted(val value: Boolean) : Control("setMuted")
    data class SetVideoEnabled(val enabled: Boolean) : Control("setVideoEnabled")
    data class SetLoop(val mode: LoopMode) : Control("setLoop")
    data class SetShuffle(val enabled: Boolean, val seed: Long?) : Control("setShuffle")
    data class SetAbLoop(val a: Duration?, val b: Duration?) : Control("setAbLoop")
    data class SetVideoScale(val mode: VideoScale) : Control("setVideoScale")
    data class SetVideoAdjustments(val value: VideoAdjustments) : Control("setVideoAdjustments")
    data class SetRenderQuality(val value: RenderQuality) : Control("setRenderQuality")
    data class SetVideoTransform(val value: VideoTransform) : Control("setVideoTransform")
    data class SetHdrPolicy(val value: HdrPolicy) : Control("setHdrPolicy")
    data class SetSubtitleDelay(val value: Duration) : Control("setSubtitleDelay")
    data class SetSubtitleScale(val value: Float) : Control("setSubtitleScale")
    data class SetSubtitleStyle(val value: SubtitleStyleOverride?) : Control("setSubtitleStyle")
    data class SetSubtitlePosition(val value: Float) : Control("setSubtitlePosition")
    data class SetSubtitleSafeArea(val value: SubtitleSafeArea) : Control("setSubtitleSafeArea")
    data class SetAudioDelay(val value: Duration) : Control("setAudioDelay")
    data class SetSleepTimer(val timer: SleepTimer?, val fade: Duration) : Control("setSleepTimer")
    data class SetEqualizer(val settings: EqualizerSettings) : Control("setEqualizer")
    data class SetMarkers(val markers: List<Marker>) : Control("setMarkers")
}

/** What a [Command] returns, for the commands that return something. */
internal sealed interface Answer {
    data class Change(val change: TrackChange) : Answer
    data class Track(val id: TrackId) : Answer
    data class Text(val text: String) : Answer
    data class Warnings(val warnings: List<TimedWarning>) : Answer
}

/**
 * Why a [Command] failed, as the kind of exception `KitePlayer` throws for it, so the page throws
 * the same kind.
 */
internal sealed interface Failure {
    data class Playback(val error: PlaybackError) : Failure
    data class Argument(val message: String) : Failure
    data class State(val message: String) : Failure
    data class Unsupported(val message: String) : Failure

    /** The exception the page throws. */
    fun toException(): Exception = when (this) {
        is Playback -> PlaybackException(error)
        is Argument -> IllegalArgumentException(message)
        is State -> IllegalStateException(message)
        is Unsupported -> UnsupportedOperationException(message)
    }

    companion object {
        /**
         * The failure [thrown] stands for. A cancellation is a state failure: it is the worker's
         * own coroutine that was cancelled, so sending it as one would cancel the page's caller for
         * nothing. It is checked first because it is an [IllegalStateException] too. Anything else
         * is [PlaybackError.Internal] with its message.
         */
        fun of(thrown: Throwable): Failure {
            val message = thrown.message ?: thrown.toString()
            return when (thrown) {
                is PlaybackException -> Playback(thrown.error)
                is CancellationException -> State(message)
                is IllegalArgumentException -> Argument(message)
                is IllegalStateException -> State(message)
                is UnsupportedOperationException -> Unsupported(message)
                else -> Playback(PlaybackError.Internal(message))
            }
        }
    }
}

/** What the worker sends the page. */
internal sealed interface WorkerMessage {
    /** The worker's code is running and listening. */
    data object Hello : WorkerMessage

    /** The codec module loaded and the player exists. */
    data object Ready : WorkerMessage

    data class InitFailed(val message: String) : WorkerMessage

    /** The one answer to the call [id]: done when [failure] is null, with [answer] for a call that returns one. */
    data class Reply(val id: Int, val failure: Failure? = null, val answer: Answer? = null) : WorkerMessage

    data class State(val snapshot: PlayerSnapshot) : WorkerMessage
    data class Progressed(val progress: Progress) : WorkerMessage
    data class Stats(val stats: PlaybackStats) : WorkerMessage
    data class Event(val event: PlayerEvent) : WorkerMessage

    /** The worker's sink asks the page to resume its audio device, or to suspend it. */
    data class Audio(val resume: Boolean) : WorkerMessage
}

/**
 * Why [item] cannot cross to the worker, or null when it can. A reader of its own is code, and code
 * does not cross between threads; everything else of an item does.
 */
internal fun crossingRefusal(item: MediaItem): PlaybackException? = refusal(
    "an item",
    listOfNotNull(
        "a reader of its own".takeIf { item.io != null },
        "an external subtitle with a reader of its own".takeIf { item.externalSubtitles.any { it.io != null } },
    ),
)

/** Why [source] cannot cross to the worker, or null when it can. */
internal fun crossingRefusal(source: SubtitleSource): PlaybackException? =
    refusal("a subtitle", listOfNotNull("a reader of its own".takeIf { source.io != null }))

private fun refusal(what: String, parts: List<String>): PlaybackException? {
    if (parts.isEmpty()) return null
    return PlaybackException(
        PlaybackError.ConfigurationInvalid(
            "$what for the worker player cannot have ${parts.joinToString(" or ")}, because a reader " +
                "cannot cross to the worker; give it an address instead",
        ),
    )
}

// ---- Encoding --------------------------------------------------------------------------------

internal fun PageMessage.encode(): JsAny = record {
    when (val message = this@encode) {
        is PageMessage.Init -> {
            kind("init")
            put("codecUrl", message.codecUrl)
            put("sampleRate", message.sampleRate)
            put("channels", message.channels)
            put("latency", message.latencySeconds)
            put("libassUrl", message.libassUrl)
        }
        is PageMessage.Call -> {
            kind("call")
            put("id", message.id)
            put("command", encodeCommand(message.command))
        }
        is PageMessage.Close -> {
            kind("close")
            put("id", message.id)
        }
        is PageMessage.Send -> {
            kind("send")
            put("control", encodeControl(message.control))
        }
    }
}

internal fun WorkerMessage.encode(): JsAny = record {
    when (val message = this@encode) {
        WorkerMessage.Hello -> kind("hello")
        WorkerMessage.Ready -> kind("ready")
        is WorkerMessage.InitFailed -> {
            kind("initFailed")
            put("message", message.message)
        }
        is WorkerMessage.Reply -> {
            kind("reply")
            put("id", message.id)
            put("failure", message.failure?.let(::encodeFailure))
            put("answer", message.answer?.let(::encodeAnswer))
        }
        is WorkerMessage.State -> {
            kind("state")
            put("snapshot", encodeSnapshot(message.snapshot))
        }
        is WorkerMessage.Progressed -> {
            kind("progress")
            put("progress", encodeProgress(message.progress))
        }
        is WorkerMessage.Stats -> {
            kind("stats")
            put("stats", encodeStats(message.stats))
        }
        is WorkerMessage.Event -> {
            kind("event")
            put("event", encodeEvent(message.event))
        }
        is WorkerMessage.Audio -> {
            kind("audio")
            put("resume", message.resume)
        }
    }
}

private fun encodeCommand(command: Command): JsAny = record {
    kind(command.member)
    when (command) {
        is Command.Open -> put("item", encodeItem(command.item))
        is Command.OpenQueue -> {
            put("items", command.items.encodeEach(::encodeItem))
            put("startIndex", command.startIndex)
        }
        is Command.Seek -> {
            put("to", command.to)
            put("mode", command.mode)
        }
        is Command.AddToQueue -> {
            put("items", command.items.encodeEach(::encodeItem))
            put("index", command.index)
        }
        is Command.RemoveFromQueue -> put("index", command.index)
        is Command.MoveInQueue -> {
            put("from", command.from)
            put("to", command.to)
        }
        is Command.StepFrame -> put("direction", command.direction)
        is Command.SeekToChapter -> put("index", command.index)
        is Command.SelectTrack -> {
            put("kind", command.kind)
            put("track", command.track?.value)
        }
        is Command.SelectSecondarySubtitle -> put("track", command.track?.value)
        is Command.SelectVariant -> put("index", command.index)
        is Command.AddExternalSubtitle -> put("source", encodeSubtitle(command.source))
        Command.Stop, Command.Next, Command.Previous, Command.ClearQueue, Command.NextChapter,
        Command.PreviousChapter, Command.DiagnosticsDump, Command.SupportBundle, Command.WarningHistory,
        -> Unit
    }
}

private fun encodeControl(control: Control): JsAny = record {
    kind(control.member)
    when (control) {
        Control.Play, Control.Pause -> Unit
        is Control.SetViewport -> {
            put("width", control.width)
            put("height", control.height)
            put("scale", control.scale)
        }
        is Control.RequestSeek -> {
            put("to", control.to)
            put("mode", control.mode)
        }
        is Control.SetSpeed -> put("value", control.value)
        is Control.SetPreservePitch -> put("value", control.value)
        is Control.SetVolume -> put("value", control.value)
        is Control.SetDuckLevel -> put("value", control.level)
        is Control.SetBalance -> put("value", control.value)
        is Control.SetMuted -> put("value", control.value)
        is Control.SetVideoEnabled -> put("value", control.enabled)
        is Control.SetLoop -> put("value", control.mode)
        is Control.SetShuffle -> {
            put("value", control.enabled)
            putExact("seed", control.seed)
        }
        is Control.SetAbLoop -> {
            put("a", control.a)
            put("b", control.b)
        }
        is Control.SetVideoScale -> put("value", control.mode)
        is Control.SetVideoAdjustments -> put("value", encodeAdjustments(control.value))
        is Control.SetRenderQuality -> put("value", encodeRenderQuality(control.value))
        is Control.SetVideoTransform -> put("value", encodeTransform(control.value))
        is Control.SetHdrPolicy -> put("value", control.value)
        is Control.SetSubtitleDelay -> put("value", control.value)
        is Control.SetSubtitleScale -> put("value", control.value)
        is Control.SetSubtitleStyle -> put("value", control.value?.let(::encodeStyle))
        is Control.SetSubtitlePosition -> put("value", control.value)
        is Control.SetSubtitleSafeArea -> put("value", encodeSafeArea(control.value))
        is Control.SetAudioDelay -> put("value", control.value)
        is Control.SetSleepTimer -> {
            put("value", control.timer?.let(::encodeSleepTimer))
            put("fade", control.fade)
        }
        is Control.SetEqualizer -> put("value", encodeEqualizer(control.settings))
        is Control.SetMarkers -> put("value", control.markers.encodeEach(::encodeMarker))
    }
}

private fun encodeAnswer(answer: Answer): JsAny = record {
    when (answer) {
        is Answer.Change -> {
            kind("change")
            put("change", encodeTrackChange(answer.change))
        }
        is Answer.Track -> {
            kind("track")
            put("id", answer.id.value)
        }
        is Answer.Text -> {
            kind("text")
            put("text", answer.text)
        }
        is Answer.Warnings -> {
            kind("warnings")
            put("warnings", answer.warnings.encodeEach(::encodeTimedWarning))
        }
    }
}

private fun encodeFailure(failure: Failure): JsAny = record {
    when (failure) {
        is Failure.Playback -> {
            kind("playback")
            put("error", encodeError(failure.error))
        }
        is Failure.Argument -> {
            kind("argument")
            put("message", failure.message)
        }
        is Failure.State -> {
            kind("state")
            put("message", failure.message)
        }
        is Failure.Unsupported -> {
            kind("unsupported")
            put("message", failure.message)
        }
    }
}

private fun encodeTrackChange(change: TrackChange): JsAny = record {
    when (change) {
        is TrackChange.Applied -> {
            kind("Applied")
            put("kind", change.kind)
            put("track", change.track?.value)
        }
        is TrackChange.Superseded -> {
            kind("Superseded")
            put("kind", change.kind)
            put("track", change.by?.value)
        }
        is TrackChange.Discarded -> {
            kind("Discarded")
            put("reason", change.reason)
        }
    }
}

@OptIn(KitePlayerLowLevelApi::class)
private fun encodeItem(item: MediaItem): JsAny = record {
    put("uri", item.uri)
    put("headers", item.headers.toJsObject())
    put("subtitles", item.externalSubtitles.encodeEach(::encodeSubtitle))
    put("videoFilter", item.videoFilter)
    put("start", item.startPosition)
    put("formatHint", item.formatHint)
    put("openOptions", item.openOptions.toJsObject())
    put("demux", encodeDemux(item.demux))
    put("title", item.title)
    put("artist", item.artist)
    put("album", item.album)
    put("audioFilter", item.audioFilter)
}

private fun encodeSubtitle(source: SubtitleSource): JsAny = record {
    put("uri", source.uri)
    put("title", source.title)
    put("language", source.language)
    put("selectImmediately", source.selectImmediately)
}

private fun encodeDemux(demux: DemuxPolicy): JsAny = record {
    put(
        "probe",
        record {
            when (val probe = demux.probe) {
                ProbeDepth.Default -> kind("Default")
                ProbeDepth.Fast -> kind("Fast")
                ProbeDepth.Thorough -> kind("Thorough")
                is ProbeDepth.Custom -> {
                    kind("Custom")
                    put("bytes", probe.bytes)
                    put("duration", probe.duration)
                }
            }
        },
    )
    put("corruptPackets", demux.corruptPackets)
    put("generateTimestamps", demux.generateTimestamps)
    put("lowLatency", demux.lowLatency)
    put("skipInitialBytes", demux.skipInitialBytes)
    put("maxBitrate", demux.maxBitrate)
    put("maxVideoHeight", demux.maxVideoHeight)
    put("variant", demux.variant)
}

private fun encodeSnapshot(snapshot: PlayerSnapshot): JsAny = record {
    put("status", snapshot.status)
    put("media", snapshot.media?.let(::encodeItem))
    put("duration", snapshot.duration)
    put("seekable", snapshot.seekable)
    put("videoSize", snapshot.videoSize?.let(::encodeVideoSize))
    put("tracks", encodeTracks(snapshot.tracks))
    put("chapters", snapshot.chapters.encodeEach(::encodeChapter))
    put("markers", snapshot.markers.encodeEach(::encodeMarker))
    put("metadata", snapshot.metadata.toJsObject())
    put("speed", snapshot.speed)
    put("volume", snapshot.volume)
    put("muted", snapshot.muted)
    put("loop", snapshot.loop)
    put("videoScale", snapshot.videoScale)
    put("videoAdjustments", encodeAdjustments(snapshot.videoAdjustments))
    put("renderQuality", encodeRenderQuality(snapshot.renderQuality))
    put("videoTransform", encodeTransform(snapshot.videoTransform))
    put("subtitleDelay", snapshot.subtitleDelay)
    put("subtitleScale", snapshot.subtitleScale)
    put("subtitleStyle", snapshot.subtitleStyle?.let(::encodeStyle))
    put("subtitlePosition", snapshot.subtitlePosition)
    put("subtitleTypesetter", snapshot.subtitleTypesetter)
    put("audioDelay", snapshot.audioDelay)
    put("abLoopA", snapshot.abLoopA)
    put("abLoopB", snapshot.abLoopB)
    put("preservePitch", snapshot.preservePitch)
    put("error", snapshot.error?.let(::encodeError))
    put("generation", snapshot.generation.value)
    put("queue", snapshot.queue.encodeEach(::encodeItem))
    put("queueIndex", snapshot.queueIndex)
    put("audioSessionId", snapshot.audioSessionId)
    put("replayGainDb", snapshot.appliedReplayGainDb)
    put("balance", snapshot.balance)
    put("videoEnabled", snapshot.videoEnabled)
    put("sleepTimer", snapshot.sleepTimer?.let(::encodeSleepTimer))
    put("equalizer", encodeEqualizer(snapshot.equalizer))
    put("shuffle", snapshot.shuffle)
    put("queueOrder", numbers(snapshot.queueOrder.map(Int::toDouble)))
    put("playRequested", snapshot.playRequested)
    put("preloadedIndex", snapshot.preloadedIndex)
    put("hdrPolicy", snapshot.hdrPolicy)
    put("videoDynamicRange", snapshot.videoDynamicRange)
}

private fun encodeVideoSize(size: VideoSize): JsAny = record {
    put("width", size.width)
    put("height", size.height)
    put("parNum", size.pixelAspectNumerator)
    put("parDen", size.pixelAspectDenominator)
}

private fun encodeTracks(tracks: Tracks): JsAny = record {
    put("all", tracks.all.encodeEach(::encodeTrack))
    put("video", tracks.selectedVideo?.value)
    put("audio", tracks.selectedAudio?.value)
    put("subtitle", tracks.selectedSubtitle?.value)
    put("secondarySubtitle", tracks.selectedSecondarySubtitle?.value)
    put("variants", tracks.variants.encodeEach(::encodeVariant))
    put("variant", tracks.selectedVariant)
}

private fun encodeTrack(track: TrackInfo): JsAny = record {
    put("id", track.id.value)
    put("kind", track.kind)
    put("codec", track.codec)
    put("language", track.language)
    put("title", track.title)
    put("isDefault", track.isDefault)
    put("isForced", track.isForced)
    put("isAccessibility", track.isAccessibility)
    put("bitrate", track.bitrate)
    put("videoSize", track.videoSize?.let(::encodeVideoSize))
    put("frameRate", track.frameRate)
    put("sampleRate", track.sampleRate)
    put("channels", track.channels)
    put("isCoverArt", track.isCoverArt)
    put("metadata", track.metadata.toJsObject())
}

private fun encodeVariant(variant: StreamVariant): JsAny = record {
    put("index", variant.index)
    put("bitrate", variant.bitrate)
    put("width", variant.width)
    put("height", variant.height)
    put("frameRate", variant.frameRate)
    put("codecs", variant.codecs)
}

private fun encodeChapter(chapter: Chapter): JsAny = record {
    put("index", chapter.index)
    put("start", chapter.start)
    put("end", chapter.end)
    put("title", chapter.title)
}

private fun encodeMarker(marker: Marker): JsAny = record {
    put("position", marker.position)
    put("id", marker.id)
}

private fun encodeAdjustments(value: VideoAdjustments): JsAny = record {
    put("brightness", value.brightness)
    put("contrast", value.contrast)
    put("saturation", value.saturation)
    put("hue", value.hueDegrees)
    put("gamma", value.gamma)
}

private fun encodeRenderQuality(value: RenderQuality): JsAny = record {
    put("dither", value.dither)
    put("deband", value.deband)
    put("debandThreshold", value.debandThreshold)
    put("debandRange", value.debandRange)
    put("debandGrain", value.debandGrain)
    put("scaler", value.scaler)
    put("linearLight", value.linearLight)
}

private fun encodeTransform(value: VideoTransform): JsAny = record {
    put("aspect", value.aspectOverride)
    put("zoom", value.zoom)
    put("panX", value.panX)
    put("panY", value.panY)
}

private fun encodeStyle(value: SubtitleStyleOverride): JsAny = record {
    put("fontFamily", value.fontFamily)
    put("fontSizePx", value.fontSizePx)
    put("primaryColor", value.primaryColor)
    put("outlineColor", value.outlineColor)
    put("outlineWidthPx", value.outlineWidthPx)
    put("shadowColor", value.shadowColor)
    put("shadowOffsetPx", value.shadowOffsetPx)
    put("backgroundColor", value.backgroundColor)
    put("backgroundPaddingPx", value.backgroundPaddingPx)
    put("bold", value.bold)
    put("italic", value.italic)
}

private fun encodeSafeArea(value: SubtitleSafeArea): JsAny = record {
    put("left", value.left)
    put("top", value.top)
    put("right", value.right)
    put("bottom", value.bottom)
}

private fun encodeSleepTimer(timer: SleepTimer): JsAny = record {
    when (timer) {
        is SleepTimer.After -> {
            kind("After")
            put("duration", timer.duration)
        }
        is SleepTimer.At -> {
            kind("At")
            put("position", timer.position)
        }
        SleepTimer.EndOfItem -> kind("EndOfItem")
    }
}

private fun encodeEqualizer(value: EqualizerSettings): JsAny = record {
    put("gainsDb", numbers(value.gainsDb.map(Float::toDouble)))
    put("preampDb", value.preampDb)
}

private fun encodeProgress(progress: Progress): JsAny = record {
    put("position", progress.position)
    put("bufferedAhead", progress.bufferedAhead)
    put(
        "bufferedRanges",
        progress.bufferedRanges.encodeEach { range ->
            record {
                put("start", range.start)
                put("end", range.endInclusive)
            }
        },
    )
}

private fun encodeStats(stats: PlaybackStats): JsAny = record {
    put("decodedVideoFrames", stats.decodedVideoFrames)
    put("submittedFrames", stats.submittedFrames)
    put("headlessFrames", stats.headlessFrames)
    put("droppedFramesLate", stats.droppedFramesLate)
    put("refusedFrames", stats.refusedFrames)
    put("droppedFramesDecode", stats.droppedFramesDecode)
    put("repeatedFrames", stats.repeatedFrames)
    put("audioUnderruns", stats.audioUnderruns)
    put("rebuffers", stats.rebuffers)
    put("droppedEvents", stats.droppedEvents)
    put("avDrift", stats.avDrift)
    put("videoDecodeFps", stats.videoDecodeFps)
    put("videoQueueDepth", stats.videoQueueDepth)
    put("audioQueueDepth", stats.audioQueueDepth)
    put("audioLatency", stats.audioLatency)
    put("audioLatencyQuality", stats.audioLatencyQuality)
    when (val hardware = stats.hardwareDecode) {
        HwdecStatus.Software -> put("hardwareDecode", "Software")
        is HwdecStatus.HardwareZeroCopy -> {
            put("hardwareDecode", "HardwareZeroCopy")
            put("hardwareKind", hardware.kind)
        }
        is HwdecStatus.HardwareWithDownload -> {
            put("hardwareDecode", "HardwareWithDownload")
            put("hardwareKind", hardware.kind)
        }
    }
    put("ioBytesTotal", stats.ioBytesTotal)
    put("ioBytesPerSecond", stats.ioBytesPerSecond)
    put("decodeTimeP50", stats.decodeTimeP50)
    put("decodeTimeP95", stats.decodeTimeP95)
    put("presentLatenessP95", stats.presentLatenessP95)
    put("containerBitrate", stats.containerBitrate)
    put("syncMode", stats.syncMode)
    put("masterClock", stats.masterClock)
}

private fun encodeTimedWarning(timed: TimedWarning): JsAny = record {
    putExact("atNanos", timed.atNanos)
    put("warning", encodeWarning(timed.warning))
}

private fun encodeEvent(event: PlayerEvent): JsAny = record {
    when (event) {
        is PlayerEvent.Opened -> {
            kind("Opened")
            put("media", encodeItem(event.media))
            put("tracks", encodeTracks(event.tracks))
        }
        is PlayerEvent.SeekCompleted -> {
            kind("SeekCompleted")
            put("generation", event.generation.value)
            put("landedAt", event.landedAt)
        }
        is PlayerEvent.VideoSizeChanged -> {
            kind("VideoSizeChanged")
            put("size", encodeVideoSize(event.size))
        }
        is PlayerEvent.AudioFormatChanged -> {
            kind("AudioFormatChanged")
            put("sampleRate", event.sampleRate)
            put("channels", event.channels)
        }
        is PlayerEvent.FirstFrameRendered -> {
            kind("FirstFrameRendered")
            put("latency", event.latency)
        }
        // Never sent from the worker, whose player leaves frameEvents off; named so this stays complete.
        is PlayerEvent.FramePresented -> {
            kind("FramePresented")
            put("pts", event.pts.micros)
            putExact("atNanos", event.atNanos)
            put("latency", event.latency)
            put("exact", event.exact)
        }
        PlayerEvent.Ended -> kind("Ended")
        is PlayerEvent.Warning -> {
            kind("Warning")
            put("warning", encodeWarning(event.warning))
        }
        is PlayerEvent.Failed -> {
            kind("Failed")
            put("error", encodeError(event.error))
        }
        is PlayerEvent.ChapterChanged -> {
            kind("ChapterChanged")
            put("chapter", event.chapter?.let(::encodeChapter))
        }
        is PlayerEvent.MarkerReached -> {
            kind("MarkerReached")
            put("marker", encodeMarker(event.marker))
        }
    }
}

/** The error without its cause, which is a Throwable and does not cross. */
private fun encodeError(error: PlaybackError): JsAny = record {
    when (error) {
        is PlaybackError.SourceUnavailable -> {
            kind("SourceUnavailable")
            put("uri", error.uri)
            put("detail", error.detail)
        }
        is PlaybackError.SchemeUnsupported -> {
            kind("SchemeUnsupported")
            put("uri", error.uri)
            put("scheme", error.scheme)
            put("detail", error.detail)
        }
        is PlaybackError.SourceStalled -> {
            kind("SourceStalled")
            put("uri", error.uri)
            put("stalledFor", error.stalledFor)
        }
        is PlaybackError.NotMedia -> {
            kind("NotMedia")
            put("uri", error.uri)
            put("detail", error.detail)
        }
        is PlaybackError.NoPlayableStream -> {
            kind("NoPlayableStream")
            put("streams", error.streams.encodeEach(::encodeTrack))
        }
        is PlaybackError.DecoderFailed -> {
            kind("DecoderFailed")
            put("codec", error.codec)
            put("detail", error.detail)
        }
        is PlaybackError.RuntimeCompromised -> {
            kind("RuntimeCompromised")
            put("detail", error.detail)
        }
        is PlaybackError.ConfigurationInvalid -> {
            kind("ConfigurationInvalid")
            put("detail", error.detail)
        }
        is PlaybackError.AudioDeviceUnavailable -> {
            kind("AudioDeviceUnavailable")
            put("device", error.device)
            put("detail", error.detail)
        }
        is PlaybackError.RendererIncompatible -> {
            kind("RendererIncompatible")
            put("renderer", error.renderer)
            put("frames", encodeFrameShape(error.frames))
        }
        is PlaybackError.Internal -> {
            kind("Internal")
            put("detail", error.detail)
        }
    }
}

private fun encodeFrameShape(shape: FrameShape): JsAny = record {
    when (shape) {
        is FrameShape.Memory -> kind("Memory")
        is FrameShape.Surface -> {
            kind("Surface")
            put("surface", shape.kind)
        }
    }
    put("pixelFormat", shape.pixelFormat)
}

@Suppress("DEPRECATION")
private fun encodeWarning(warning: PlaybackWarning): JsAny = record {
    when (warning) {
        is PlaybackWarning.RendererFailed -> {
            kind("RendererFailed")
            put("detail", warning.detail)
        }
        is PlaybackWarning.OptionsUnused -> {
            kind("OptionsUnused")
            put("keys", strings(warning.keys))
        }
        is PlaybackWarning.DeinterlaceUnavailable -> {
            kind("DeinterlaceUnavailable")
            put("codec", warning.codec)
            put("detail", warning.detail)
        }
        is PlaybackWarning.HardwareDecodeUnavailable -> {
            kind("HardwareDecodeUnavailable")
            put("codec", warning.codec)
            put("reason", warning.reason)
        }
        is PlaybackWarning.FrameDropping -> {
            kind("FrameDropping")
            put("dropped", warning.droppedInLastSecond)
        }
        is PlaybackWarning.AudioDeviceChanged -> {
            kind("AudioDeviceChanged")
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioUnderrun -> {
            kind("AudioUnderrun")
            put("total", warning.totalSoFar)
        }
        is PlaybackWarning.SourceReconnecting -> {
            kind("SourceReconnecting")
            put("position", warning.position)
            put("attempt", warning.attempt)
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioTapFailed -> {
            kind("AudioTapFailed")
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioDeviceUnderrun -> {
            kind("AudioDeviceUnderrun")
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioDrainIncomplete -> {
            kind("AudioDrainIncomplete")
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioLatencyUnreliable -> {
            kind("AudioLatencyUnreliable")
            put("detail", warning.detail)
        }
        is PlaybackWarning.AudioSourceFormatChanged -> {
            kind("AudioSourceFormatChanged")
            put("fromSampleRate", warning.fromSampleRate)
            put("fromChannels", warning.fromChannels)
            put("toSampleRate", warning.toSampleRate)
            put("toChannels", warning.toChannels)
        }
        is PlaybackWarning.HdrToneMapped -> {
            kind("HdrToneMapped")
            put("transfer", warning.transfer)
            put("stream", warning.streamIndex)
        }
        is PlaybackWarning.ColorApproximated -> {
            kind("ColorApproximated")
            put("detail", warning.detail)
        }
        is PlaybackWarning.TonemappingUnavailable -> {
            kind("TonemappingUnavailable")
            put("detail", warning.detail)
        }
        is PlaybackWarning.ChannelLayoutUnknown -> {
            kind("ChannelLayoutUnknown")
            put("channels", warning.channels)
            put("detail", warning.detail)
        }
        is PlaybackWarning.BadTimestamps -> {
            kind("BadTimestamps")
            put("detail", warning.detail)
        }
        is PlaybackWarning.TrackDeselected -> {
            kind("TrackDeselected")
            put("track", warning.track.value)
            put("detail", warning.detail)
        }
        is PlaybackWarning.ContainerDeclarationDiverged -> {
            kind("ContainerDeclarationDiverged")
            put("stream", warning.streamIndex)
            put("field", warning.field)
            put("declared", warning.declared)
            put("decoded", warning.decoded)
        }
        is PlaybackWarning.SubtitleSourceUnreadable -> {
            kind("SubtitleSourceUnreadable")
            put("uri", warning.uri)
            put("reason", warning.reason)
        }
        is PlaybackWarning.SubtitleCharsetGuessed -> {
            kind("SubtitleCharsetGuessed")
            put("uri", warning.uri)
            put("charset", warning.charset)
            put("detected", warning.detected)
        }
        is PlaybackWarning.TypesetterUnavailable -> {
            kind("TypesetterUnavailable")
            put("provider", warning.provider)
            put("detail", warning.detail)
        }
        is PlaybackWarning.SubtitlesNotDrawn -> {
            kind("SubtitlesNotDrawn")
            put("detail", warning.detail)
        }
        is PlaybackWarning.ResamplerUnavailable -> {
            kind("ResamplerUnavailable")
            put("detail", warning.detail)
        }
        is PlaybackWarning.CommandRefused -> {
            kind("CommandRefused")
            put("member", warning.member)
            put("detail", warning.detail)
        }
        is PlaybackWarning.StartupIncomplete -> {
            kind("StartupIncomplete")
            put("detail", warning.detail)
        }
        is PlaybackWarning.ResourcesNotReleased -> {
            kind("ResourcesNotReleased")
            put("detail", warning.detail)
        }
        is PlaybackWarning.StartPositionIgnored -> {
            kind("StartPositionIgnored")
            put("requested", warning.requested)
            put("detail", warning.detail)
        }
        is PlaybackWarning.PathologicalInterleaving -> {
            kind("PathologicalInterleaving")
            put("track", warning.starvedTrack.value)
            put("dropped", warning.droppedPackets)
        }
        is PlaybackWarning.NoRenderSurface -> {
            kind("NoRenderSurface")
            put("detail", warning.detail)
        }
        is PlaybackWarning.RecordingStopped -> {
            kind("RecordingStopped")
            put("path", warning.path)
            put("reason", warning.reason)
        }
        is PlaybackWarning.GaplessFallback -> {
            kind("GaplessFallback")
            put("index", warning.index)
            put("reason", warning.reason)
        }
        is PlaybackWarning.SegmentSkipped -> {
            kind("SegmentSkipped")
            put("uri", warning.uri)
            put("detail", warning.detail)
        }
        is PlaybackWarning.ExternalClockSilent -> {
            kind("ExternalClockSilent")
            put("detail", warning.detail)
        }
        is PlaybackWarning.VariantLowered -> {
            kind("VariantLowered")
            put("from", warning.from)
            put("to", warning.to)
            put("detail", warning.detail)
        }
    }
}

/** This map as a plain JS object of strings. */
internal fun Map<String, String>.toJsObject(): JsAny = jsObject().also { o -> forEach { (key, value) -> o.put(key, value) } }

// ---- Decoding --------------------------------------------------------------------------------

/**
 * The message in [data], or null for anything that is not one: the worker ignores what it does not
 * know. A message whose values a type refuses in its `init` is not one either.
 */
internal fun decodePageMessage(data: JsAny?): PageMessage? = runCatching {
    val o = data ?: return null
    when (o.str("t")) {
        "init" -> PageMessage.Init(
            codecUrl = o.str("codecUrl") ?: missing("codecUrl"),
            sampleRate = o.int("sampleRate") ?: missing("sampleRate"),
            channels = o.int("channels") ?: missing("channels"),
            latencySeconds = o.num("latency"),
            libassUrl = o.str("libassUrl"),
        )
        "call" -> PageMessage.Call(
            o.int("id") ?: missing("id"),
            decodeCommand(o.child("command") ?: missing("command")) ?: return null,
        )
        "close" -> PageMessage.Close(o.int("id") ?: missing("id"))
        "send" -> PageMessage.Send(decodeControl(o.child("control") ?: missing("control")) ?: return null)
        else -> null
    }
}.getOrNull()

/**
 * The message in [data], or null for anything that is not one: the page ignores what it does not
 * know. A reply is the exception: a reply with an id is always one, because the call waiting on it
 * must end. A failure or an answer that cannot be read makes it a failed reply.
 */
internal fun decodeWorkerMessage(data: JsAny?): WorkerMessage? = runCatching {
    val o = data ?: return null
    when (o.str("t")) {
        "hello" -> WorkerMessage.Hello
        "ready" -> WorkerMessage.Ready
        "initFailed" -> WorkerMessage.InitFailed(o.str("message").orEmpty())
        "reply" -> decodeReply(o)
        "state" -> WorkerMessage.State(decodeSnapshot(o.child("snapshot") ?: missing("snapshot")))
        "progress" -> WorkerMessage.Progressed(decodeProgress(o.child("progress") ?: missing("progress")))
        "stats" -> WorkerMessage.Stats(decodeStats(o.child("stats") ?: missing("stats")))
        "event" -> WorkerMessage.Event(decodeEvent(o.child("event") ?: missing("event")) ?: return null)
        "audio" -> WorkerMessage.Audio(o.flag("resume"))
        else -> null
    }
}.getOrNull()

private fun decodeReply(o: JsAny): WorkerMessage.Reply {
    val id = o.int("id") ?: missing("id")
    return runCatching {
        WorkerMessage.Reply(
            id = id,
            failure = o.child("failure")?.let(::decodeFailure),
            answer = o.child("answer")?.let(::decodeAnswer),
        )
    }.getOrElse { unreadable ->
        WorkerMessage.Reply(
            id,
            Failure.Playback(PlaybackError.Internal("the worker's reply could not be read: ${unreadable.message}")),
        )
    }
}

private fun decodeCommand(o: JsAny): Command? = when (o.str("t")) {
    "open" -> Command.Open(decodeItem(o.child("item") ?: missing("item")))
    "openQueue" -> Command.OpenQueue(o.list("items", ::decodeItem) ?: missing("items"), o.int("startIndex") ?: missing("startIndex"))
    "seek" -> Command.Seek(o.micros("to") ?: missing("to"), o.enum<SeekMode>("mode") ?: missing("mode"))
    "stop" -> Command.Stop
    "next" -> Command.Next
    "previous" -> Command.Previous
    "addToQueue" -> Command.AddToQueue(o.list("items", ::decodeItem) ?: missing("items"), o.int("index"))
    "removeFromQueue" -> Command.RemoveFromQueue(o.int("index") ?: missing("index"))
    "moveInQueue" -> Command.MoveInQueue(o.int("from") ?: missing("from"), o.int("to") ?: missing("to"))
    "clearQueue" -> Command.ClearQueue
    "stepFrame" -> Command.StepFrame(o.enum<StepDirection>("direction") ?: missing("direction"))
    "seekToChapter" -> Command.SeekToChapter(o.int("index") ?: missing("index"))
    "nextChapter" -> Command.NextChapter
    "previousChapter" -> Command.PreviousChapter
    "selectTrack" -> Command.SelectTrack(o.enum<TrackKind>("kind") ?: missing("kind"), o.int("track")?.let(::TrackId))
    "selectSecondarySubtitle" -> Command.SelectSecondarySubtitle(o.int("track")?.let(::TrackId))
    "selectVariant" -> Command.SelectVariant(o.int("index"))
    "addExternalSubtitle" -> Command.AddExternalSubtitle(decodeSubtitle(o.child("source") ?: missing("source")))
    "diagnosticsDump" -> Command.DiagnosticsDump
    "supportBundle" -> Command.SupportBundle
    "warningHistory" -> Command.WarningHistory
    else -> null
}

private fun decodeControl(o: JsAny): Control? = when (o.str("t")) {
    "play" -> Control.Play
    "pause" -> Control.Pause
    "setViewport" -> Control.SetViewport(
        o.int("width") ?: missing("width"),
        o.int("height") ?: missing("height"),
        o.float("scale") ?: missing("scale"),
    )
    "requestSeek" -> Control.RequestSeek(o.micros("to") ?: missing("to"), o.enum<SeekMode>("mode") ?: missing("mode"))
    "setSpeed" -> Control.SetSpeed(o.num("value") ?: missing("value"))
    "setPreservePitch" -> Control.SetPreservePitch(o.flag("value"))
    "setVolume" -> Control.SetVolume(o.float("value") ?: missing("value"))
    "setDuckLevel" -> Control.SetDuckLevel(o.float("value") ?: missing("value"))
    "setBalance" -> Control.SetBalance(o.float("value") ?: missing("value"))
    "setMuted" -> Control.SetMuted(o.flag("value"))
    "setVideoEnabled" -> Control.SetVideoEnabled(o.flag("value"))
    "setLoop" -> Control.SetLoop(o.enum<LoopMode>("value") ?: missing("value"))
    "setShuffle" -> Control.SetShuffle(o.flag("value"), o.exact("seed"))
    "setAbLoop" -> Control.SetAbLoop(o.micros("a"), o.micros("b"))
    "setVideoScale" -> Control.SetVideoScale(o.enum<VideoScale>("value") ?: missing("value"))
    "setVideoAdjustments" -> Control.SetVideoAdjustments(decodeAdjustments(o.child("value") ?: missing("value")))
    "setRenderQuality" -> Control.SetRenderQuality(decodeRenderQuality(o.child("value") ?: missing("value")))
    "setVideoTransform" -> Control.SetVideoTransform(decodeTransform(o.child("value") ?: missing("value")))
    "setHdrPolicy" -> Control.SetHdrPolicy(o.enum<HdrPolicy>("value") ?: missing("value"))
    "setSubtitleDelay" -> Control.SetSubtitleDelay(o.micros("value") ?: missing("value"))
    "setSubtitleScale" -> Control.SetSubtitleScale(o.float("value") ?: missing("value"))
    "setSubtitleStyle" -> Control.SetSubtitleStyle(o.child("value")?.let(::decodeStyle))
    "setSubtitlePosition" -> Control.SetSubtitlePosition(o.float("value") ?: missing("value"))
    "setSubtitleSafeArea" -> Control.SetSubtitleSafeArea(decodeSafeArea(o.child("value") ?: missing("value")))
    "setAudioDelay" -> Control.SetAudioDelay(o.micros("value") ?: missing("value"))
    "setSleepTimer" -> Control.SetSleepTimer(o.child("value")?.let(::decodeSleepTimer), o.micros("fade") ?: missing("fade"))
    "setEqualizer" -> Control.SetEqualizer(decodeEqualizer(o.child("value") ?: missing("value")))
    "setMarkers" -> Control.SetMarkers(o.list("value", ::decodeMarker) ?: missing("value"))
    else -> null
}

private fun decodeAnswer(o: JsAny): Answer = when (o.str("t")) {
    "change" -> Answer.Change(decodeTrackChange(o.child("change") ?: missing("change")))
    "track" -> Answer.Track(TrackId(o.int("id") ?: missing("id")))
    "text" -> Answer.Text(o.str("text") ?: missing("text"))
    "warnings" -> Answer.Warnings(o.list("warnings", ::decodeTimedWarning) ?: missing("warnings"))
    else -> missing("answer kind")
}

private fun decodeFailure(o: JsAny): Failure = when (o.str("t")) {
    "playback" -> Failure.Playback(decodeError(o.child("error") ?: missing("error")) ?: missing("error kind"))
    "argument" -> Failure.Argument(o.str("message").orEmpty())
    "state" -> Failure.State(o.str("message").orEmpty())
    "unsupported" -> Failure.Unsupported(o.str("message").orEmpty())
    else -> missing("failure kind")
}

private fun decodeTrackChange(o: JsAny): TrackChange = when (o.str("t")) {
    "Applied" -> TrackChange.Applied(o.enum<TrackKind>("kind") ?: missing("kind"), o.int("track")?.let(::TrackId))
    "Superseded" -> TrackChange.Superseded(o.enum<TrackKind>("kind") ?: missing("kind"), o.int("track")?.let(::TrackId))
    "Discarded" -> TrackChange.Discarded(o.str("reason").orEmpty())
    else -> missing("track change kind")
}

@OptIn(KitePlayerLowLevelApi::class)
private fun decodeItem(o: JsAny): MediaItem = MediaItem(
    uri = o.str("uri") ?: missing("uri"),
    headers = o.map("headers"),
    externalSubtitles = o.list("subtitles", ::decodeSubtitle).orEmpty(),
    videoFilter = o.str("videoFilter"),
    startPosition = o.micros("start"),
    formatHint = o.str("formatHint"),
    openOptions = o.map("openOptions"),
    demux = o.child("demux")?.let(::decodeDemux) ?: DemuxPolicy(),
    title = o.str("title"),
    artist = o.str("artist"),
    album = o.str("album"),
    audioFilter = o.str("audioFilter"),
)

private fun decodeSubtitle(o: JsAny): SubtitleSource = SubtitleSource(
    uri = o.str("uri") ?: missing("uri"),
    title = o.str("title"),
    language = o.str("language"),
    selectImmediately = o.flag("selectImmediately"),
)

private fun decodeDemux(o: JsAny): DemuxPolicy = DemuxPolicy(
    probe = o.child("probe")?.let(::decodeProbe) ?: ProbeDepth.Default,
    corruptPackets = o.enum<CorruptPackets>("corruptPackets") ?: CorruptPackets.Keep,
    generateTimestamps = o.flag("generateTimestamps"),
    lowLatency = o.flag("lowLatency"),
    skipInitialBytes = o.long("skipInitialBytes") ?: 0L,
    maxBitrate = o.long("maxBitrate"),
    maxVideoHeight = o.int("maxVideoHeight"),
    variant = o.int("variant"),
)

private fun decodeProbe(o: JsAny): ProbeDepth = when (o.str("t")) {
    "Default" -> ProbeDepth.Default
    "Fast" -> ProbeDepth.Fast
    "Thorough" -> ProbeDepth.Thorough
    "Custom" -> ProbeDepth.Custom(o.long("bytes") ?: missing("bytes"), o.micros("duration") ?: missing("duration"))
    else -> missing("probe kind")
}

private fun decodeSnapshot(o: JsAny): PlayerSnapshot {
    val default = PlayerSnapshot()
    return PlayerSnapshot(
        status = o.enum<PlaybackStatus>("status") ?: missing("status"),
        media = o.child("media")?.let(::decodeItem),
        duration = o.micros("duration"),
        seekable = o.flag("seekable"),
        videoSize = o.child("videoSize")?.let(::decodeVideoSize),
        tracks = o.child("tracks")?.let(::decodeTracks) ?: default.tracks,
        chapters = o.list("chapters", ::decodeChapter) ?: default.chapters,
        markers = o.list("markers", ::decodeMarker) ?: default.markers,
        metadata = o.map("metadata"),
        speed = o.num("speed") ?: default.speed,
        volume = o.float("volume") ?: default.volume,
        muted = o.flag("muted"),
        loop = o.enum<LoopMode>("loop") ?: default.loop,
        videoScale = o.enum<VideoScale>("videoScale") ?: default.videoScale,
        videoAdjustments = o.child("videoAdjustments")?.let(::decodeAdjustments) ?: default.videoAdjustments,
        renderQuality = o.child("renderQuality")?.let(::decodeRenderQuality) ?: default.renderQuality,
        videoTransform = o.child("videoTransform")?.let(::decodeTransform) ?: default.videoTransform,
        subtitleDelay = o.micros("subtitleDelay") ?: default.subtitleDelay,
        subtitleScale = o.float("subtitleScale") ?: default.subtitleScale,
        subtitleStyle = o.child("subtitleStyle")?.let(::decodeStyle),
        subtitlePosition = o.float("subtitlePosition") ?: default.subtitlePosition,
        subtitleTypesetter = o.str("subtitleTypesetter"),
        audioDelay = o.micros("audioDelay") ?: default.audioDelay,
        abLoopA = o.micros("abLoopA"),
        abLoopB = o.micros("abLoopB"),
        preservePitch = o.bool("preservePitch") ?: default.preservePitch,
        error = o.child("error")?.let(::decodeError),
        generation = o.long("generation")?.let(::Generation) ?: default.generation,
        queue = o.list("queue", ::decodeItem) ?: default.queue,
        queueIndex = o.int("queueIndex") ?: default.queueIndex,
        audioSessionId = o.int("audioSessionId"),
        appliedReplayGainDb = o.float("replayGainDb"),
        balance = o.float("balance") ?: default.balance,
        videoEnabled = o.bool("videoEnabled") ?: default.videoEnabled,
        sleepTimer = o.child("sleepTimer")?.let(::decodeSleepTimer),
        equalizer = o.child("equalizer")?.let(::decodeEqualizer) ?: default.equalizer,
        shuffle = o.flag("shuffle"),
        queueOrder = o.numbers("queueOrder")?.map(Double::toInt) ?: default.queueOrder,
        playRequested = o.flag("playRequested"),
        preloadedIndex = o.int("preloadedIndex"),
        hdrPolicy = o.enum<HdrPolicy>("hdrPolicy") ?: default.hdrPolicy,
        videoDynamicRange = o.enum<VideoDynamicRange>("videoDynamicRange") ?: default.videoDynamicRange,
    )
}

private fun decodeVideoSize(o: JsAny): VideoSize = VideoSize(
    width = o.int("width") ?: missing("width"),
    height = o.int("height") ?: missing("height"),
    pixelAspectNumerator = o.int("parNum") ?: 1,
    pixelAspectDenominator = o.int("parDen") ?: 1,
)

private fun decodeTracks(o: JsAny): Tracks = Tracks(
    all = o.list("all", ::decodeTrack).orEmpty(),
    selectedVideo = o.int("video")?.let(::TrackId),
    selectedAudio = o.int("audio")?.let(::TrackId),
    selectedSubtitle = o.int("subtitle")?.let(::TrackId),
    selectedSecondarySubtitle = o.int("secondarySubtitle")?.let(::TrackId),
    variants = o.list("variants", ::decodeVariant).orEmpty(),
    selectedVariant = o.int("variant"),
)

private fun decodeTrack(o: JsAny): TrackInfo = TrackInfo(
    id = TrackId(o.int("id") ?: missing("id")),
    kind = o.enum<TrackKind>("kind") ?: missing("kind"),
    codec = o.str("codec") ?: missing("codec"),
    language = o.str("language"),
    title = o.str("title"),
    isDefault = o.flag("isDefault"),
    isForced = o.flag("isForced"),
    isAccessibility = o.flag("isAccessibility"),
    bitrate = o.long("bitrate"),
    videoSize = o.child("videoSize")?.let(::decodeVideoSize),
    frameRate = o.num("frameRate"),
    sampleRate = o.int("sampleRate"),
    channels = o.int("channels"),
    isCoverArt = o.flag("isCoverArt"),
    metadata = o.map("metadata"),
)

private fun decodeVariant(o: JsAny): StreamVariant = StreamVariant(
    index = o.int("index") ?: missing("index"),
    bitrate = o.long("bitrate") ?: missing("bitrate"),
    width = o.int("width"),
    height = o.int("height"),
    frameRate = o.num("frameRate"),
    codecs = o.str("codecs"),
)

private fun decodeChapter(o: JsAny): Chapter = Chapter(
    index = o.int("index") ?: missing("index"),
    start = o.micros("start") ?: missing("start"),
    end = o.micros("end"),
    title = o.str("title"),
)

private fun decodeMarker(o: JsAny): Marker = Marker(o.micros("position") ?: missing("position"), o.str("id") ?: missing("id"))

private fun decodeAdjustments(o: JsAny): VideoAdjustments = VideoAdjustments(
    brightness = o.float("brightness") ?: 0f,
    contrast = o.float("contrast") ?: 1f,
    saturation = o.float("saturation") ?: 1f,
    hueDegrees = o.float("hue") ?: 0f,
    gamma = o.float("gamma") ?: 1f,
)

private fun decodeRenderQuality(o: JsAny): RenderQuality {
    val default = RenderQuality.Off
    return RenderQuality(
        dither = o.flag("dither"),
        deband = o.flag("deband"),
        debandThreshold = o.float("debandThreshold") ?: default.debandThreshold,
        debandRange = o.float("debandRange") ?: default.debandRange,
        debandGrain = o.float("debandGrain") ?: default.debandGrain,
        scaler = o.enum<VideoScaler>("scaler") ?: default.scaler,
        linearLight = o.flag("linearLight"),
    )
}

private fun decodeTransform(o: JsAny): VideoTransform = VideoTransform(
    aspectOverride = o.float("aspect"),
    zoom = o.float("zoom") ?: 1f,
    panX = o.float("panX") ?: 0f,
    panY = o.float("panY") ?: 0f,
)

private fun decodeStyle(o: JsAny): SubtitleStyleOverride = SubtitleStyleOverride(
    fontFamily = o.str("fontFamily"),
    fontSizePx = o.float("fontSizePx"),
    primaryColor = o.int("primaryColor"),
    outlineColor = o.int("outlineColor"),
    outlineWidthPx = o.float("outlineWidthPx"),
    shadowColor = o.int("shadowColor"),
    shadowOffsetPx = o.float("shadowOffsetPx"),
    backgroundColor = o.int("backgroundColor"),
    backgroundPaddingPx = o.float("backgroundPaddingPx") ?: SubtitleStyleOverride().backgroundPaddingPx,
    bold = o.bool("bold"),
    italic = o.bool("italic"),
)

private fun decodeSafeArea(o: JsAny): SubtitleSafeArea = SubtitleSafeArea(
    left = o.float("left") ?: 0f,
    top = o.float("top") ?: 0f,
    right = o.float("right") ?: 0f,
    bottom = o.float("bottom") ?: 0f,
)

private fun decodeSleepTimer(o: JsAny): SleepTimer = when (o.str("t")) {
    "After" -> SleepTimer.After(o.micros("duration") ?: missing("duration"))
    "At" -> SleepTimer.At(o.micros("position") ?: missing("position"))
    "EndOfItem" -> SleepTimer.EndOfItem
    else -> missing("sleep timer kind")
}

private fun decodeEqualizer(o: JsAny): EqualizerSettings = EqualizerSettings(
    gainsDb = o.numbers("gainsDb")?.map(Double::toFloat) ?: missing("gainsDb"),
    preampDb = o.float("preampDb") ?: 0f,
)

private fun decodeProgress(o: JsAny): Progress = Progress(
    position = o.micros("position") ?: missing("position"),
    bufferedAhead = o.micros("bufferedAhead") ?: Duration.ZERO,
    bufferedRanges = o.list("bufferedRanges") { range ->
        (range.micros("start") ?: missing("start"))..(range.micros("end") ?: missing("end"))
    }.orEmpty(),
)

private fun decodeStats(o: JsAny): PlaybackStats {
    val default = PlaybackStats()
    val kind = o.enum<HwdecKind>("hardwareKind")
    return PlaybackStats(
        decodedVideoFrames = o.long("decodedVideoFrames") ?: 0L,
        submittedFrames = o.long("submittedFrames") ?: 0L,
        headlessFrames = o.long("headlessFrames") ?: 0L,
        droppedFramesLate = o.long("droppedFramesLate") ?: 0L,
        refusedFrames = o.long("refusedFrames") ?: 0L,
        droppedFramesDecode = o.long("droppedFramesDecode") ?: 0L,
        repeatedFrames = o.long("repeatedFrames") ?: 0L,
        audioUnderruns = o.long("audioUnderruns") ?: 0L,
        rebuffers = o.long("rebuffers") ?: 0L,
        droppedEvents = o.long("droppedEvents") ?: 0L,
        avDrift = o.micros("avDrift") ?: Duration.ZERO,
        videoDecodeFps = o.num("videoDecodeFps") ?: 0.0,
        videoQueueDepth = o.micros("videoQueueDepth") ?: Duration.ZERO,
        audioQueueDepth = o.micros("audioQueueDepth") ?: Duration.ZERO,
        audioLatency = o.micros("audioLatency") ?: Duration.ZERO,
        audioLatencyQuality = o.enum<LatencyQuality>("audioLatencyQuality") ?: default.audioLatencyQuality,
        hardwareDecode = when (o.str("hardwareDecode")) {
            "HardwareZeroCopy" -> HwdecStatus.HardwareZeroCopy(kind ?: missing("hardwareKind"))
            "HardwareWithDownload" -> HwdecStatus.HardwareWithDownload(kind ?: missing("hardwareKind"))
            else -> HwdecStatus.Software
        },
        ioBytesTotal = o.long("ioBytesTotal") ?: 0L,
        ioBytesPerSecond = o.long("ioBytesPerSecond") ?: 0L,
        decodeTimeP50 = o.micros("decodeTimeP50") ?: Duration.ZERO,
        decodeTimeP95 = o.micros("decodeTimeP95") ?: Duration.ZERO,
        presentLatenessP95 = o.micros("presentLatenessP95") ?: Duration.ZERO,
        containerBitrate = o.long("containerBitrate"),
        syncMode = o.enum<SyncMode>("syncMode") ?: default.syncMode,
        masterClock = o.enum<MasterClock>("masterClock") ?: default.masterClock,
    )
}

private fun decodeTimedWarning(o: JsAny): TimedWarning = TimedWarning(
    atNanos = o.exact("atNanos") ?: missing("atNanos"),
    warning = decodeWarning(o.child("warning") ?: missing("warning")) ?: missing("warning kind"),
)

/** The event in [o], or null for a kind this side does not know. */
private fun decodeEvent(o: JsAny): PlayerEvent? = when (o.str("t")) {
    "Opened" -> PlayerEvent.Opened(decodeItem(o.child("media") ?: missing("media")), decodeTracks(o.child("tracks") ?: missing("tracks")))
    "SeekCompleted" -> PlayerEvent.SeekCompleted(
        Generation(o.long("generation") ?: missing("generation")),
        o.micros("landedAt") ?: missing("landedAt"),
    )
    "VideoSizeChanged" -> PlayerEvent.VideoSizeChanged(decodeVideoSize(o.child("size") ?: missing("size")))
    "AudioFormatChanged" -> PlayerEvent.AudioFormatChanged(o.int("sampleRate") ?: missing("sampleRate"), o.int("channels") ?: missing("channels"))
    "FirstFrameRendered" -> PlayerEvent.FirstFrameRendered(o.micros("latency") ?: missing("latency"))
    "FramePresented" -> PlayerEvent.FramePresented(
        pts = Pts(o.long("pts") ?: missing("pts")),
        atNanos = o.exact("atNanos") ?: missing("atNanos"),
        latency = o.micros("latency") ?: missing("latency"),
        exact = o.flag("exact"),
    )
    "Ended" -> PlayerEvent.Ended
    "Warning" -> PlayerEvent.Warning(decodeWarning(o.child("warning") ?: missing("warning")) ?: return null)
    "Failed" -> PlayerEvent.Failed(decodeError(o.child("error") ?: missing("error")) ?: return null)
    "ChapterChanged" -> PlayerEvent.ChapterChanged(o.child("chapter")?.let(::decodeChapter))
    "MarkerReached" -> PlayerEvent.MarkerReached(decodeMarker(o.child("marker") ?: missing("marker")))
    else -> null
}

/** The error in [o], or null for a kind this side does not know. */
private fun decodeError(o: JsAny): PlaybackError? = when (o.str("t")) {
    "SourceUnavailable" -> PlaybackError.SourceUnavailable(o.str("uri") ?: missing("uri"), null, o.str("detail"))
    "SchemeUnsupported" -> PlaybackError.SchemeUnsupported(
        o.str("uri") ?: missing("uri"),
        o.str("scheme") ?: missing("scheme"),
        o.str("detail"),
    )
    "SourceStalled" -> PlaybackError.SourceStalled(o.str("uri") ?: missing("uri"), o.micros("stalledFor") ?: missing("stalledFor"))
    "NotMedia" -> PlaybackError.NotMedia(o.str("uri") ?: missing("uri"), o.str("detail"))
    "NoPlayableStream" -> PlaybackError.NoPlayableStream(o.list("streams", ::decodeTrack).orEmpty())
    "DecoderFailed" -> PlaybackError.DecoderFailed(o.str("codec") ?: missing("codec"), o.str("detail").orEmpty())
    "RuntimeCompromised" -> PlaybackError.RuntimeCompromised(o.str("detail").orEmpty())
    "ConfigurationInvalid" -> PlaybackError.ConfigurationInvalid(o.str("detail").orEmpty())
    "AudioDeviceUnavailable" -> PlaybackError.AudioDeviceUnavailable(o.str("device") ?: missing("device"), o.str("detail").orEmpty())
    "RendererIncompatible" -> PlaybackError.RendererIncompatible(
        o.str("renderer") ?: missing("renderer"),
        decodeFrameShape(o.child("frames") ?: missing("frames")),
    )
    "Internal" -> PlaybackError.Internal(o.str("detail").orEmpty())
    else -> null
}

private fun decodeFrameShape(o: JsAny): FrameShape {
    val format = o.enum<PlayerPixelFormat>("pixelFormat") ?: missing("pixelFormat")
    return when (o.str("t")) {
        "Memory" -> FrameShape.Memory(format)
        "Surface" -> FrameShape.Surface(o.enum<HwSurfaceKind>("surface") ?: missing("surface"), format)
        else -> missing("frame shape kind")
    }
}

/** The warning in [o], or null for a kind this side does not know. */
@Suppress("DEPRECATION")
private fun decodeWarning(o: JsAny): PlaybackWarning? {
    val detail = o.str("detail").orEmpty()
    return when (o.str("t")) {
        "RendererFailed" -> PlaybackWarning.RendererFailed(detail)
        "OptionsUnused" -> PlaybackWarning.OptionsUnused(o.strings("keys").orEmpty())
        "DeinterlaceUnavailable" -> PlaybackWarning.DeinterlaceUnavailable(o.str("codec").orEmpty(), detail)
        "HardwareDecodeUnavailable" -> PlaybackWarning.HardwareDecodeUnavailable(o.str("codec").orEmpty(), o.str("reason").orEmpty())
        "FrameDropping" -> PlaybackWarning.FrameDropping(o.int("dropped") ?: missing("dropped"))
        "AudioDeviceChanged" -> PlaybackWarning.AudioDeviceChanged(detail)
        "AudioUnderrun" -> PlaybackWarning.AudioUnderrun(o.long("total") ?: missing("total"))
        "SourceReconnecting" -> PlaybackWarning.SourceReconnecting(
            o.long("position") ?: missing("position"),
            o.int("attempt") ?: missing("attempt"),
            detail,
        )
        "AudioTapFailed" -> PlaybackWarning.AudioTapFailed(detail)
        "AudioDeviceUnderrun" -> PlaybackWarning.AudioDeviceUnderrun(detail)
        "AudioDrainIncomplete" -> PlaybackWarning.AudioDrainIncomplete(detail)
        "AudioLatencyUnreliable" -> PlaybackWarning.AudioLatencyUnreliable(detail)
        "AudioSourceFormatChanged" -> PlaybackWarning.AudioSourceFormatChanged(
            o.int("fromSampleRate") ?: missing("fromSampleRate"),
            o.int("fromChannels") ?: missing("fromChannels"),
            o.int("toSampleRate") ?: missing("toSampleRate"),
            o.int("toChannels") ?: missing("toChannels"),
        )
        "HdrToneMapped" -> PlaybackWarning.HdrToneMapped(o.str("transfer").orEmpty(), o.int("stream") ?: missing("stream"))
        "ColorApproximated" -> PlaybackWarning.ColorApproximated(detail)
        "TonemappingUnavailable" -> PlaybackWarning.TonemappingUnavailable(detail)
        "ChannelLayoutUnknown" -> PlaybackWarning.ChannelLayoutUnknown(o.int("channels") ?: missing("channels"), detail)
        "BadTimestamps" -> PlaybackWarning.BadTimestamps(detail)
        "TrackDeselected" -> PlaybackWarning.TrackDeselected(TrackId(o.int("track") ?: missing("track")), detail)
        "ContainerDeclarationDiverged" -> PlaybackWarning.ContainerDeclarationDiverged(
            o.int("stream") ?: missing("stream"),
            o.str("field").orEmpty(),
            o.str("declared").orEmpty(),
            o.str("decoded").orEmpty(),
        )
        "SubtitleSourceUnreadable" -> PlaybackWarning.SubtitleSourceUnreadable(o.str("uri").orEmpty(), o.str("reason").orEmpty())
        "SubtitleCharsetGuessed" -> PlaybackWarning.SubtitleCharsetGuessed(
            o.str("uri").orEmpty(),
            o.str("charset").orEmpty(),
            o.str("detected"),
        )
        "TypesetterUnavailable" -> PlaybackWarning.TypesetterUnavailable(o.str("provider").orEmpty(), detail)
        "SubtitlesNotDrawn" -> PlaybackWarning.SubtitlesNotDrawn(detail)
        "ResamplerUnavailable" -> PlaybackWarning.ResamplerUnavailable(detail)
        "CommandRefused" -> PlaybackWarning.CommandRefused(o.str("member").orEmpty(), detail)
        "StartupIncomplete" -> PlaybackWarning.StartupIncomplete(detail)
        "ResourcesNotReleased" -> PlaybackWarning.ResourcesNotReleased(detail)
        "StartPositionIgnored" -> PlaybackWarning.StartPositionIgnored(o.micros("requested") ?: missing("requested"), detail)
        "PathologicalInterleaving" -> PlaybackWarning.PathologicalInterleaving(
            TrackId(o.int("track") ?: missing("track")),
            o.int("dropped") ?: missing("dropped"),
        )
        "NoRenderSurface" -> PlaybackWarning.NoRenderSurface(detail)
        "RecordingStopped" -> PlaybackWarning.RecordingStopped(o.str("path").orEmpty(), o.str("reason").orEmpty())
        "GaplessFallback" -> PlaybackWarning.GaplessFallback(o.int("index") ?: missing("index"), o.str("reason").orEmpty())
        "SegmentSkipped" -> PlaybackWarning.SegmentSkipped(o.str("uri").orEmpty(), detail)
        "ExternalClockSilent" -> PlaybackWarning.ExternalClockSilent(detail)
        "VariantLowered" -> PlaybackWarning.VariantLowered(o.int("from") ?: missing("from"), o.int("to") ?: missing("to"), detail)
        else -> null
    }
}

/** Thrown inside a decoder for a field that is absent or unreadable, and caught where the message is. */
private fun missing(field: String): Nothing = throw IllegalArgumentException("the message has no readable $field")

// ---- The JS object helpers -------------------------------------------------------------------

private inline fun record(build: JsAny.() -> Unit): JsAny = jsObject().apply(build)

private fun JsAny.kind(value: String) = put("t", value)

// Each writer skips a null, so an absent field reads back as null.

private fun JsAny.put(key: String, value: String?) {
    if (value != null) jsSet(this, key, value.toJsString())
}

private fun JsAny.put(key: String, value: Boolean?) {
    if (value != null) jsSetBoolean(this, key, value)
}

private fun JsAny.put(key: String, value: Int?) {
    if (value != null) jsSetNumber(this, key, value.toDouble())
}

/** Exact to 2^53. A value that may pass that crosses through [putExact]. */
private fun JsAny.put(key: String, value: Long?) {
    if (value != null) jsSetNumber(this, key, value.toDouble())
}

private fun JsAny.put(key: String, value: Float?) {
    if (value != null) jsSetNumber(this, key, value.toDouble())
}

private fun JsAny.put(key: String, value: Double?) {
    if (value != null) jsSetNumber(this, key, value)
}

/** In microseconds, and an infinite one as JS Infinity, which a microsecond count would turn finite. */
private fun JsAny.put(key: String, value: Duration?) {
    if (value == null) return
    val micros = when {
        value == Duration.INFINITE -> Double.POSITIVE_INFINITY
        value == -Duration.INFINITE -> Double.NEGATIVE_INFINITY
        else -> value.inWholeMicroseconds.toDouble()
    }
    jsSetNumber(this, key, micros)
}

private fun JsAny.put(key: String, value: Enum<*>?) {
    if (value != null) put(key, value.name)
}

private fun JsAny.put(key: String, value: JsAny?) {
    if (value != null) jsSet(this, key, value)
}

/** A number that may pass 2^53, as a decimal string. */
private fun JsAny.putExact(key: String, value: Long?) {
    if (value != null) put(key, value.toString())
}

private fun JsAny.str(key: String): String? = jsGetString(this, key)

private fun JsAny.num(key: String): Double? = jsGetNumber(this, key).takeUnless { it.isNaN() }

private fun JsAny.int(key: String): Int? = num(key)?.toInt()

private fun JsAny.long(key: String): Long? = num(key)?.toLong()

private fun JsAny.float(key: String): Float? = num(key)?.toFloat()

private fun JsAny.micros(key: String): Duration? = num(key)?.let { micros ->
    when (micros) {
        Double.POSITIVE_INFINITY -> Duration.INFINITE
        Double.NEGATIVE_INFINITY -> -Duration.INFINITE
        else -> micros.toLong().microseconds
    }
}

/** True only for a field that is `true`: what a writer that skipped `false` would also read back. */
private fun JsAny.flag(key: String): Boolean = jsGetBoolean(this, key)

/** The boolean in [key], or null when there is none: for a field whose null means something. */
private fun JsAny.bool(key: String): Boolean? = when (jsGetBooleanState(this, key)) {
    1 -> true
    0 -> false
    else -> null
}

private fun JsAny.exact(key: String): Long? = str(key)?.toLongOrNull()

private fun JsAny.child(key: String): JsAny? = jsGetAny(this, key)

private inline fun <reified E : Enum<E>> JsAny.enum(key: String): E? {
    val name = str(key) ?: return null
    return enumValues<E>().firstOrNull { it.name == name }
}

private fun JsAny.map(key: String): Map<String, String> {
    val o = child(key) ?: return emptyMap()
    val keys = jsKeys(o)
    val out = LinkedHashMap<String, String>()
    for (i in 0 until keys.length) {
        val name = keys[i]?.toString() ?: continue
        o.str(name)?.let { out[name] = it }
    }
    return out
}

/** The array in [key] with each element decoded, or null when there is no array there. */
private fun <T> JsAny.list(key: String, decode: (JsAny) -> T): List<T>? {
    val array = child(key) ?: return null
    val size = jsLength(array).takeIf { it >= 0 } ?: return null
    return List(size) { i -> decode(jsAt(array, i) ?: missing("$key[$i]")) }
}

private fun JsAny.numbers(key: String): List<Double>? {
    val array = child(key) ?: return null
    val size = jsLength(array).takeIf { it >= 0 } ?: return null
    return List(size) { i -> jsNumberAt(array, i).takeUnless { it.isNaN() } ?: missing("$key[$i]") }
}

private fun JsAny.strings(key: String): List<String>? {
    val array = child(key) ?: return null
    val size = jsLength(array).takeIf { it >= 0 } ?: return null
    return List(size) { i -> jsStringAt(array, i) ?: missing("$key[$i]") }
}

private fun <T> List<T>.encodeEach(encode: (T) -> JsAny): JsAny = jsArray().also { array -> forEach { jsPush(array, encode(it)) } }

private fun numbers(values: List<Double>): JsAny = jsArray().also { array -> values.forEach { jsPushNumber(array, it) } }

private fun strings(values: List<String>): JsAny = jsArray().also { array -> values.forEach { jsPush(array, it.toJsString()) } }

@JsFun("() => ({})")
internal external fun jsObject(): JsAny

/** The field [key] of [o], or null when it is absent: how the transferables beside a message are read. */
@JsFun("(o, k) => (o && o[k] !== undefined && o[k] !== null) ? o[k] : null")
internal external fun jsField(o: JsAny, key: String): JsAny?

@JsFun("(o, k, v) => { o[k] = v; }")
private external fun jsSet(o: JsAny, key: String, value: JsAny)

@JsFun("(o, k, v) => { o[k] = v; }")
private external fun jsSetNumber(o: JsAny, key: String, value: Double)

@JsFun("(o, k, v) => { o[k] = v; }")
private external fun jsSetBoolean(o: JsAny, key: String, value: Boolean)

@JsFun("(o, k) => (typeof o[k] === 'number') ? o[k] : NaN")
private external fun jsGetNumber(o: JsAny, key: String): Double

@JsFun("(o, k) => (typeof o[k] === 'string') ? o[k] : null")
private external fun jsGetString(o: JsAny, key: String): String?

@JsFun("(o, k) => o[k] === true")
private external fun jsGetBoolean(o: JsAny, key: String): Boolean

/** 1 for true, 0 for false, -1 for anything else. */
@JsFun("(o, k) => (o[k] === true) ? 1 : ((o[k] === false) ? 0 : -1)")
private external fun jsGetBooleanState(o: JsAny, key: String): Int

@JsFun("(o, k) => (o[k] === undefined || o[k] === null) ? null : o[k]")
private external fun jsGetAny(o: JsAny, key: String): JsAny?

@JsFun("(o) => Object.keys(o)")
private external fun jsKeys(o: JsAny): JsArray<JsString>

@JsFun("() => []")
private external fun jsArray(): JsAny

@JsFun("(a, v) => { a.push(v); }")
private external fun jsPush(array: JsAny, value: JsAny)

@JsFun("(a, v) => { a.push(v); }")
private external fun jsPushNumber(array: JsAny, value: Double)

/** The length of [array], or -1 when it is not an array. */
@JsFun("(a) => Array.isArray(a) ? a.length : -1")
private external fun jsLength(array: JsAny): Int

@JsFun("(a, i) => (a[i] === undefined || a[i] === null) ? null : a[i]")
private external fun jsAt(array: JsAny, index: Int): JsAny?

@JsFun("(a, i) => (typeof a[i] === 'number') ? a[i] : NaN")
private external fun jsNumberAt(array: JsAny, index: Int): Double

@JsFun("(a, i) => (typeof a[i] === 'string') ? a[i] : null")
private external fun jsStringAt(array: JsAny, index: Int): String?
