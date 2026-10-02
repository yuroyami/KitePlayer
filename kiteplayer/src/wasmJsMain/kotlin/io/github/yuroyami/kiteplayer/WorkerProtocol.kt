@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.time.Duration.Companion.microseconds

/*
 * The messages between the page and a player in a Web Worker (#100), written by hand.
 *
 * Each message is a plain JS object with a type in `t`, because a plain object is what
 * `postMessage` copies fastest and what a person reading the traffic in the browser's tools can
 * read. Each has an encoder and a decoder here, and nothing else in the module builds or reads one.
 * Times cross in microseconds as JS numbers, which are exact to 285 years.
 */

/** What the page sends the worker. A command with an [id] gets exactly one [WorkerMessage.Reply]. */
internal sealed interface PageMessage {
    /** The first message. The canvas and the audio port travel beside it, as transferables. */
    data class Init(
        val codecUrl: String,
        val sampleRate: Int,
        val channels: Int,
        val latencySeconds: Double?,
    ) : PageMessage

    data class Open(val id: Int, val item: ItemRecord) : PageMessage
    data class Seek(val id: Int, val positionMicros: Long) : PageMessage
    data class Stop(val id: Int) : PageMessage
    data class Close(val id: Int) : PageMessage
    data object Play : PageMessage
    data object Pause : PageMessage
    data class Viewport(val width: Int, val height: Int, val scale: Float) : PageMessage
}

/** What the worker sends the page. */
internal sealed interface WorkerMessage {
    /** The worker's code is running and listening. */
    data object Hello : WorkerMessage

    /** The codec module loaded and the player exists. */
    data object Ready : WorkerMessage

    data class InitFailed(val message: String) : WorkerMessage

    /** The answer to the command [id]: done when [error] is null. */
    data class Reply(val id: Int, val error: ErrorRecord?) : WorkerMessage

    data class State(val state: StateRecord) : WorkerMessage
    data class Progress(val positionMicros: Long, val bufferedAheadMicros: Long) : WorkerMessage
    data class Event(val event: EventRecord) : WorkerMessage

    /** The worker's sink asks the page to resume its audio device, or to suspend it. */
    data class Audio(val resume: Boolean) : WorkerMessage
}

/** The parts of a [MediaItem] that can cross. [ItemRecord.of] refuses an item with any other part. */
internal data class ItemRecord(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val formatHint: String? = null,
    val openOptions: Map<String, String> = emptyMap(),
    val startMicros: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
) {
    @OptIn(KitePlayerLowLevelApi::class)
    fun toItem(): MediaItem = MediaItem(
        uri = uri,
        headers = headers,
        formatHint = formatHint,
        openOptions = openOptions,
        startPosition = startMicros?.microseconds,
        title = title,
        artist = artist,
        album = album,
    )

    companion object {
        /** [item] as a record, or the reason it cannot cross to the worker. */
        @OptIn(KitePlayerLowLevelApi::class)
        fun of(item: MediaItem): Result<ItemRecord> {
            val refused = listOfNotNull(
                "a reader of its own".takeIf { item.io != null },
                "external subtitles".takeIf { item.externalSubtitles.isNotEmpty() },
                "a video filter".takeIf { item.videoFilter != null },
                "an audio filter".takeIf { item.audioFilter != null },
                "a demux policy".takeIf { item.demux != DemuxPolicy() },
            )
            if (refused.isNotEmpty()) {
                return Result.failure(
                    PlaybackException(
                        PlaybackError.ConfigurationInvalid(
                            "an item for the worker player cannot have ${refused.joinToString(" or ")} yet; " +
                                "those cannot cross to the worker",
                        ),
                    ),
                )
            }
            return Result.success(
                ItemRecord(
                    uri = item.uri,
                    headers = item.headers,
                    formatHint = item.formatHint,
                    openOptions = item.openOptions,
                    startMicros = item.startPosition?.inWholeMicroseconds,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                ),
            )
        }
    }
}

/** A [PlaybackError] as it crosses: its kind, its message, and the fields its kind is rebuilt from. */
internal data class ErrorRecord(
    val kind: String,
    val message: String,
    val uri: String? = null,
    val detail: String? = null,
    val codec: String? = null,
    val stalledMicros: Long? = null,
) {
    /** The error again. A kind with fields that cannot cross comes back as [PlaybackError.Internal]. */
    fun toError(): PlaybackError = when (kind) {
        "SourceUnavailable" -> PlaybackError.SourceUnavailable(uri.orEmpty(), null, detail)
        "SourceStalled" -> PlaybackError.SourceStalled(uri.orEmpty(), (stalledMicros ?: 0L).microseconds)
        "NotMedia" -> PlaybackError.NotMedia(uri.orEmpty(), detail)
        "DecoderFailed" -> PlaybackError.DecoderFailed(codec.orEmpty(), detail.orEmpty())
        "RuntimeCompromised" -> PlaybackError.RuntimeCompromised(detail.orEmpty())
        "ConfigurationInvalid" -> PlaybackError.ConfigurationInvalid(detail.orEmpty())
        "Internal" -> PlaybackError.Internal(detail ?: message)
        else -> PlaybackError.Internal(message)
    }

    companion object {
        fun of(error: PlaybackError): ErrorRecord = when (error) {
            is PlaybackError.SourceUnavailable -> ErrorRecord("SourceUnavailable", error.message, uri = error.uri, detail = error.detail)
            is PlaybackError.SourceStalled ->
                ErrorRecord("SourceStalled", error.message, uri = error.uri, stalledMicros = error.stalledFor.inWholeMicroseconds)
            is PlaybackError.NotMedia -> ErrorRecord("NotMedia", error.message, uri = error.uri, detail = error.detail)
            is PlaybackError.DecoderFailed -> ErrorRecord("DecoderFailed", error.message, codec = error.codec, detail = error.detail)
            is PlaybackError.RuntimeCompromised -> ErrorRecord("RuntimeCompromised", error.message, detail = error.detail)
            is PlaybackError.ConfigurationInvalid -> ErrorRecord("ConfigurationInvalid", error.message, detail = error.detail)
            is PlaybackError.Internal -> ErrorRecord("Internal", error.message, detail = error.detail)
            else -> ErrorRecord(error::class.simpleName ?: "Internal", error.message)
        }

        /** Anything that is not a [PlaybackException] crosses as [PlaybackError.Internal]. */
        fun of(failure: Throwable): ErrorRecord =
            (failure as? PlaybackException)?.let { of(it.error) }
                ?: (failure.message ?: failure.toString()).let { ErrorRecord("Internal", it, detail = it) }
    }
}

/** The parts of a [PlayerSnapshot] that cross. */
internal data class StateRecord(
    val status: PlaybackStatus,
    val hasMedia: Boolean,
    val durationMicros: Long?,
    val seekable: Boolean,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val speed: Double,
    val volume: Float,
    val muted: Boolean,
    val metadata: Map<String, String>,
) {
    /** The snapshot again, with [media] standing for the item, which the page kept. */
    fun toSnapshot(media: MediaItem?): PlayerSnapshot = PlayerSnapshot(
        status = status,
        media = media.takeIf { hasMedia },
        duration = durationMicros?.microseconds,
        seekable = seekable,
        videoSize = if (videoWidth != null && videoHeight != null) VideoSize(videoWidth, videoHeight) else null,
        metadata = metadata,
        speed = speed,
        volume = volume,
        muted = muted,
    )

    companion object {
        fun of(snapshot: PlayerSnapshot): StateRecord = StateRecord(
            status = snapshot.status,
            hasMedia = snapshot.media != null,
            durationMicros = snapshot.duration?.inWholeMicroseconds,
            seekable = snapshot.seekable,
            videoWidth = snapshot.videoSize?.width,
            videoHeight = snapshot.videoSize?.height,
            speed = snapshot.speed,
            volume = snapshot.volume,
            muted = snapshot.muted,
            metadata = snapshot.metadata,
        )
    }
}

/** The [PlayerEvent]s that cross. */
internal sealed interface EventRecord {
    data object Ended : EventRecord
    data class Failed(val error: ErrorRecord) : EventRecord
    data class FirstFrameRendered(val latencyMicros: Long) : EventRecord
    data class VideoSizeChanged(val width: Int, val height: Int) : EventRecord
    data class AudioFormatChanged(val sampleRate: Int, val channels: Int) : EventRecord

    fun toEvent(): PlayerEvent = when (this) {
        Ended -> PlayerEvent.Ended
        is Failed -> PlayerEvent.Failed(error.toError())
        is FirstFrameRendered -> PlayerEvent.FirstFrameRendered(latencyMicros.microseconds)
        is VideoSizeChanged -> PlayerEvent.VideoSizeChanged(VideoSize(width, height))
        is AudioFormatChanged -> PlayerEvent.AudioFormatChanged(sampleRate, channels)
    }

    companion object {
        /** [event] as a record, or null for an event that does not cross yet. */
        fun of(event: PlayerEvent): EventRecord? = when (event) {
            PlayerEvent.Ended -> Ended
            is PlayerEvent.Failed -> Failed(ErrorRecord.of(event.error))
            is PlayerEvent.FirstFrameRendered -> FirstFrameRendered(event.latency.inWholeMicroseconds)
            is PlayerEvent.VideoSizeChanged -> VideoSizeChanged(event.size.width, event.size.height)
            is PlayerEvent.AudioFormatChanged -> AudioFormatChanged(event.sampleRate, event.channels)
            else -> null
        }
    }
}

// ---- Encoding --------------------------------------------------------------------------------

internal fun PageMessage.encode(): JsAny = jsObject().also { o ->
    when (this) {
        is PageMessage.Init -> {
            o.type("init")
            o.setString("codecUrl", codecUrl)
            o.setNumber("sampleRate", sampleRate.toDouble())
            o.setNumber("channels", channels.toDouble())
            latencySeconds?.let { o.setNumber("latency", it) }
        }
        is PageMessage.Open -> {
            o.type("open")
            o.setNumber("id", id.toDouble())
            o.setAny("item", item.encode())
        }
        is PageMessage.Seek -> {
            o.type("seek")
            o.setNumber("id", id.toDouble())
            o.setNumber("position", positionMicros.toDouble())
        }
        is PageMessage.Stop -> {
            o.type("stop")
            o.setNumber("id", id.toDouble())
        }
        is PageMessage.Close -> {
            o.type("close")
            o.setNumber("id", id.toDouble())
        }
        PageMessage.Play -> o.type("play")
        PageMessage.Pause -> o.type("pause")
        is PageMessage.Viewport -> {
            o.type("viewport")
            o.setNumber("width", width.toDouble())
            o.setNumber("height", height.toDouble())
            o.setNumber("scale", scale.toDouble())
        }
    }
}

internal fun WorkerMessage.encode(): JsAny = jsObject().also { o ->
    when (this) {
        WorkerMessage.Hello -> o.type("hello")
        WorkerMessage.Ready -> o.type("ready")
        is WorkerMessage.InitFailed -> {
            o.type("initFailed")
            o.setString("message", message)
        }
        is WorkerMessage.Reply -> {
            o.type("reply")
            o.setNumber("id", id.toDouble())
            error?.let { o.setAny("error", it.encode()) }
        }
        is WorkerMessage.State -> {
            o.type("state")
            o.setAny("state", state.encode())
        }
        is WorkerMessage.Progress -> {
            o.type("progress")
            o.setNumber("position", positionMicros.toDouble())
            o.setNumber("buffered", bufferedAheadMicros.toDouble())
        }
        is WorkerMessage.Event -> {
            o.type("event")
            o.setAny("event", event.encode())
        }
        is WorkerMessage.Audio -> {
            o.type("audio")
            o.setBoolean("resume", resume)
        }
    }
}

private fun ItemRecord.encode(): JsAny = jsObject().also { o ->
    o.setString("uri", uri)
    o.setAny("headers", headers.toJsObject())
    formatHint?.let { o.setString("formatHint", it) }
    o.setAny("openOptions", openOptions.toJsObject())
    startMicros?.let { o.setNumber("start", it.toDouble()) }
    title?.let { o.setString("title", it) }
    artist?.let { o.setString("artist", it) }
    album?.let { o.setString("album", it) }
}

private fun ErrorRecord.encode(): JsAny = jsObject().also { o ->
    o.setString("kind", kind)
    o.setString("message", message)
    uri?.let { o.setString("uri", it) }
    detail?.let { o.setString("detail", it) }
    codec?.let { o.setString("codec", it) }
    stalledMicros?.let { o.setNumber("stalled", it.toDouble()) }
}

private fun StateRecord.encode(): JsAny = jsObject().also { o ->
    o.setString("status", status.name)
    o.setBoolean("hasMedia", hasMedia)
    durationMicros?.let { o.setNumber("duration", it.toDouble()) }
    o.setBoolean("seekable", seekable)
    videoWidth?.let { o.setNumber("videoWidth", it.toDouble()) }
    videoHeight?.let { o.setNumber("videoHeight", it.toDouble()) }
    o.setNumber("speed", speed)
    o.setNumber("volume", volume.toDouble())
    o.setBoolean("muted", muted)
    o.setAny("metadata", metadata.toJsObject())
}

private fun EventRecord.encode(): JsAny = jsObject().also { o ->
    when (this) {
        EventRecord.Ended -> o.type("ended")
        is EventRecord.Failed -> {
            o.type("failed")
            o.setAny("error", error.encode())
        }
        is EventRecord.FirstFrameRendered -> {
            o.type("firstFrame")
            o.setNumber("latency", latencyMicros.toDouble())
        }
        is EventRecord.VideoSizeChanged -> {
            o.type("videoSize")
            o.setNumber("width", width.toDouble())
            o.setNumber("height", height.toDouble())
        }
        is EventRecord.AudioFormatChanged -> {
            o.type("audioFormat")
            o.setNumber("sampleRate", sampleRate.toDouble())
            o.setNumber("channels", channels.toDouble())
        }
    }
}

/** This map as a plain JS object of strings. */
internal fun Map<String, String>.toJsObject(): JsAny = jsObject().also { o -> forEach { (key, value) -> o.setString(key, value) } }

// ---- Decoding --------------------------------------------------------------------------------

/** The message in [data], or null for anything that is not one: the page ignores what it does not know. */
internal fun decodePageMessage(data: JsAny?): PageMessage? {
    val o = data ?: return null
    return when (o.getString("t")) {
        "init" -> PageMessage.Init(
            codecUrl = o.getString("codecUrl") ?: return null,
            sampleRate = o.getInt("sampleRate") ?: return null,
            channels = o.getInt("channels") ?: return null,
            latencySeconds = o.getNumberOrNull("latency"),
        )
        "open" -> PageMessage.Open(o.getInt("id") ?: return null, decodeItem(o.getAny("item")) ?: return null)
        "seek" -> PageMessage.Seek(o.getInt("id") ?: return null, o.getLong("position") ?: return null)
        "stop" -> PageMessage.Stop(o.getInt("id") ?: return null)
        "close" -> PageMessage.Close(o.getInt("id") ?: return null)
        "play" -> PageMessage.Play
        "pause" -> PageMessage.Pause
        "viewport" -> PageMessage.Viewport(
            width = o.getInt("width") ?: return null,
            height = o.getInt("height") ?: return null,
            scale = o.getNumberOrNull("scale")?.toFloat() ?: return null,
        )
        else -> null
    }
}

/** The message in [data], or null for anything that is not one. */
internal fun decodeWorkerMessage(data: JsAny?): WorkerMessage? {
    val o = data ?: return null
    return when (o.getString("t")) {
        "hello" -> WorkerMessage.Hello
        "ready" -> WorkerMessage.Ready
        "initFailed" -> WorkerMessage.InitFailed(o.getString("message").orEmpty())
        "reply" -> WorkerMessage.Reply(o.getInt("id") ?: return null, o.getAny("error")?.let(::decodeError))
        "state" -> WorkerMessage.State(decodeState(o.getAny("state")) ?: return null)
        "progress" -> WorkerMessage.Progress(o.getLong("position") ?: return null, o.getLong("buffered") ?: 0L)
        "event" -> WorkerMessage.Event(decodeEvent(o.getAny("event")) ?: return null)
        "audio" -> WorkerMessage.Audio(o.getBoolean("resume"))
        else -> null
    }
}

private fun decodeItem(data: JsAny?): ItemRecord? {
    val o = data ?: return null
    return ItemRecord(
        uri = o.getString("uri") ?: return null,
        headers = decodeMap(o.getAny("headers")),
        formatHint = o.getString("formatHint"),
        openOptions = decodeMap(o.getAny("openOptions")),
        startMicros = o.getLong("start"),
        title = o.getString("title"),
        artist = o.getString("artist"),
        album = o.getString("album"),
    )
}

private fun decodeError(o: JsAny): ErrorRecord = ErrorRecord(
    kind = o.getString("kind") ?: "Internal",
    message = o.getString("message").orEmpty(),
    uri = o.getString("uri"),
    detail = o.getString("detail"),
    codec = o.getString("codec"),
    stalledMicros = o.getLong("stalled"),
)

private fun decodeState(data: JsAny?): StateRecord? {
    val o = data ?: return null
    return StateRecord(
        status = PlaybackStatus.entries.firstOrNull { it.name == o.getString("status") } ?: return null,
        hasMedia = o.getBoolean("hasMedia"),
        durationMicros = o.getLong("duration"),
        seekable = o.getBoolean("seekable"),
        videoWidth = o.getInt("videoWidth"),
        videoHeight = o.getInt("videoHeight"),
        speed = o.getNumberOrNull("speed") ?: 1.0,
        volume = o.getNumberOrNull("volume")?.toFloat() ?: 1f,
        muted = o.getBoolean("muted"),
        metadata = decodeMap(o.getAny("metadata")),
    )
}

private fun decodeEvent(data: JsAny?): EventRecord? {
    val o = data ?: return null
    return when (o.getString("t")) {
        "ended" -> EventRecord.Ended
        "failed" -> EventRecord.Failed(o.getAny("error")?.let(::decodeError) ?: return null)
        "firstFrame" -> EventRecord.FirstFrameRendered(o.getLong("latency") ?: return null)
        "videoSize" -> EventRecord.VideoSizeChanged(o.getInt("width") ?: return null, o.getInt("height") ?: return null)
        "audioFormat" -> EventRecord.AudioFormatChanged(o.getInt("sampleRate") ?: return null, o.getInt("channels") ?: return null)
        else -> null
    }
}

private fun decodeMap(data: JsAny?): Map<String, String> {
    val o = data ?: return emptyMap()
    val keys = jsKeys(o)
    val out = LinkedHashMap<String, String>()
    for (i in 0 until keys.length) {
        val key = keys[i]?.toString() ?: continue
        o.getString(key)?.let { out[key] = it }
    }
    return out
}

// ---- The JS object helpers -------------------------------------------------------------------

private fun JsAny.type(value: String) = setString("t", value)

private fun JsAny.getInt(key: String): Int? = getNumberOrNull(key)?.toInt()

private fun JsAny.getLong(key: String): Long? = getNumberOrNull(key)?.toLong()

private fun JsAny.getNumberOrNull(key: String): Double? = jsGetNumber(this, key).takeUnless { it.isNaN() }

private fun JsAny.getString(key: String): String? = jsGetString(this, key)

private fun JsAny.getBoolean(key: String): Boolean = jsGetBoolean(this, key)

private fun JsAny.getAny(key: String): JsAny? = jsGetAny(this, key)

private fun JsAny.setString(key: String, value: String) = jsSet(this, key, value.toJsString())

private fun JsAny.setNumber(key: String, value: Double) = jsSetNumber(this, key, value)

private fun JsAny.setBoolean(key: String, value: Boolean) = jsSetBoolean(this, key, value)

private fun JsAny.setAny(key: String, value: JsAny) = jsSet(this, key, value)

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

@JsFun("(o, k) => (o[k] === undefined || o[k] === null) ? null : o[k]")
private external fun jsGetAny(o: JsAny, key: String): JsAny?

@JsFun("(o) => Object.keys(o)")
private external fun jsKeys(o: JsAny): JsArray<JsString>
