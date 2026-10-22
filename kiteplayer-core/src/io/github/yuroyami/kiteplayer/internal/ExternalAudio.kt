package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.AudioSource
import io.github.yuroyami.kiteplayer.KeyframeChoice
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException

/*
 * The audio files and streams an item plays beside its media (#392), which
 * [MediaItem.externalAudio] names. Each one is an input: a backend session of its own, opened
 * through the item's backend. The engine sees one session and one source. The source lists the
 * media's streams and the audio streams of every input, and reads them as one container would give
 * them, in the order of their timestamps. FFmpeg's HLS reader joins its audio renditions the same
 * way, and mpv plays an extra audio file through a reader of its own.
 */

/**
 * Where the stream indexes of external audio inputs start. A container numbers its streams from
 * zero, the ones it announces later included, so none reaches it, and the caption tracks the engine
 * makes start at 1 shl 24, above every input.
 */
internal const val EXTERNAL_AUDIO_STREAM_BASE: Int = 1 shl 20

/** How many stream indexes each input has. A stream of an input numbered past it is left out. */
internal const val EXTERNAL_AUDIO_STREAM_SPAN: Int = 1 shl 10

/** How many inputs fit below the caption tracks. An input past it is left out, with a warning. */
internal const val EXTERNAL_AUDIO_INPUT_LIMIT: Int =
    ((1 shl 24) - EXTERNAL_AUDIO_STREAM_BASE) / EXTERNAL_AUDIO_STREAM_SPAN

/** The index the engine knows stream [stream] of input [input] by, [input] counting from zero. */
internal fun externalAudioStreamIndex(input: Int, stream: Int): Int =
    EXTERNAL_AUDIO_STREAM_BASE + input * EXTERNAL_AUDIO_STREAM_SPAN + stream

/**
 * Opens [item] through [backend], and each of its external audio inputs beside it (#392). An item
 * with none opens exactly as [MediaBackend.open] opens it.
 *
 * [resolve] gives the reader of one input, from the item made of it, or null to let the backend
 * open the address. The engine passes its network resolver. An input that cannot open, or holds no
 * audio track, is left out with [PlaybackWarning.AudioSourceUnreadable], which the session hands to
 * its warning sink. Only a failure of the media itself fails the open.
 */
internal suspend fun openWithExternalAudio(
    backend: MediaBackend,
    item: MediaItem,
    resolve: suspend (MediaItem) -> MediaIo? = { null },
): BackendSession {
    if (item.externalAudio.isEmpty()) return backend.open(item)
    val main = backend.open(item)
    val inputs = ArrayList<ExternalAudioInput>()
    val leftOut = ArrayList<PlaybackWarning>()
    try {
        item.externalAudio.forEachIndexed { number, declared ->
            if (number >= EXTERNAL_AUDIO_INPUT_LIMIT) {
                leftOut += PlaybackWarning.AudioSourceUnreadable(declared.uri, "an item plays at most $EXTERNAL_AUDIO_INPUT_LIMIT of them")
                return@forEachIndexed
            }
            // The item's headers go only to the item's own server, as for an external subtitle.
            val headers = if (sameHttpOrigin(declared.uri, item.uri)) item.headers else emptyMap()
            val asItem = MediaItem(uri = declared.uri, headers = headers, io = declared.io)
            var reader: MediaIo? = null
            try {
                reader = resolve(asItem)
                val owned = reader
                val session = backend.open(if (owned == null) asItem else asItem.copy(io = MediaIoFactory { owned }))
                val input = ExternalAudioInput(number, declared, session, owned)
                if (input.streams.isEmpty()) {
                    runCatching { input.close() }
                    leftOut += PlaybackWarning.AudioSourceUnreadable(declared.uri, "it holds no audio track")
                } else {
                    inputs += input
                }
            } catch (cancellation: CancellationException) {
                reader?.let { runCatching { it.close() } }
                throw cancellation
            } catch (failure: Throwable) {
                // The backend's own unwind may have closed the reader first. A second close is allowed.
                reader?.let { runCatching { it.close() } }
                val detail = failure.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                leftOut += PlaybackWarning.AudioSourceUnreadable(declared.uri, "it could not be opened$detail")
            }
        }
    } catch (failure: Throwable) {
        // A cancelled open leaves nothing open.
        inputs.forEach { runCatching { it.close() } }
        runCatching { main.close() }
        throw failure
    }
    return ExternalAudioSession(main, inputs, leftOut)
}

/**
 * [listed], the stream list [packet] announces, with the external audio streams of this source
 * added when the packet came from the media itself, whose list does not know them (#392).
 */
internal fun PlayerMediaSource.streamsAnnouncedBy(packet: PlayerPacket, listed: List<PlayerStreamInfo>): List<PlayerStreamInfo> =
    if (this is ExternalAudioSource && packet !is ExternalAudioPacket) withExternalAudio(listed) else listed

/** One opened external audio input: its session, and its audio streams under the engine's indexes. */
internal class ExternalAudioInput(
    /** The input's place in [MediaItem.externalAudio], which its stream indexes are made of. */
    val number: Int,
    private val declared: AudioSource,
    val session: BackendSession,
    /** The reader the engine resolved for this input, or null when the backend opened the address. */
    private val reader: MediaIo? = null,
) {
    /** Closes the input, then the reader made for it. A reader takes a second close. */
    fun close() {
        try {
            session.close()
        } finally {
            reader?.let { runCatching { it.close() } }
        }
    }

    private val base = externalAudioStreamIndex(number, 0)
    private val listed = atomic(relabel(session.source.streams))

    /** The input's audio streams as the engine lists them. Replaced whole, never changed in place. */
    val streams: List<PlayerStreamInfo> get() = listed.value

    /** True for an index of this input, listed or not. */
    fun owns(index: Int): Boolean = index >= base && index < base + EXTERNAL_AUDIO_STREAM_SPAN

    /** The index the input's own source knows [index] by. */
    fun ownIndex(index: Int): Int = index - base

    /** The engine's index of the input's own stream [index]. */
    fun engineIndex(index: Int): Int = base + index

    /** The stream at [index] as the input's own source describes it, which its decoders expect. */
    fun ownStream(index: Int): PlayerStreamInfo? =
        session.source.streams.firstOrNull { it.kind == TrackKind.Audio && it.index == ownIndex(index) }

    /** Takes in the input's stream list after a packet said it changed. */
    fun relist(own: List<PlayerStreamInfo>) {
        listed.value = relabel(own)
    }

    /** Only the sound: a picture, a cover or subtitles of an input are not the item's. */
    private fun relabel(own: List<PlayerStreamInfo>): List<PlayerStreamInfo> = own
        .filter { it.kind == TrackKind.Audio && it.index in 0 until EXTERNAL_AUDIO_STREAM_SPAN }
        .map { stream ->
            stream.copy(
                index = engineIndex(stream.index),
                title = declared.title ?: stream.title ?: redactUri(declared.uri).ifEmpty { null },
                language = declared.language ?: stream.language,
                // The media's own default sound stays the one an open chooses first.
                isDefault = false,
            )
        }
}

/**
 * The session of an item with external audio inputs: the media's session, with the audio streams
 * and decoders of each input added. Closing it closes every input and then the media.
 */
internal class ExternalAudioSession(
    private val main: BackendSession,
    private val inputs: List<ExternalAudioInput>,
    leftOut: List<PlaybackWarning>,
) : BackendSession {
    private val joined: ExternalAudioSource? = if (inputs.isEmpty()) null else ExternalAudioSource(main.source, inputs)

    /** The media's own source when every input was left out, so the item plays as if it named none. */
    override val source: PlayerMediaSource = joined ?: main.source

    override val videoDecoders: List<VideoDecoderFactory> get() = main.videoDecoders
    override val subtitleDecoders: List<SubtitleDecoderFactory> get() = main.subtitleDecoders

    /**
     * One factory for each place in the media's list and in the longest list of an input. For a
     * stream of the media it asks the media's factory at that place, and for a stream of an input,
     * that input's.
     */
    override val audioDecoders: List<AudioDecoderFactory> =
        if (inputs.isEmpty()) {
            main.audioDecoders
        } else {
            List(maxOf(main.audioDecoders.size, inputs.maxOf { it.session.audioDecoders.size })) { place -> Decoders(place) }
        }

    /** The warnings of the inputs left out, held until the engine gives its sink. */
    private val held = atomic<List<PlaybackWarning>>(leftOut)

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
        main.setWarningSink(sink)
        inputs.forEach { it.session.setWarningSink(sink) }
        held.getAndSet(emptyList()).forEach(sink)
    }

    override fun close() {
        var first: Throwable? = null
        fun attempt(close: () -> Unit) {
            try {
                close()
            } catch (failure: Throwable) {
                first?.addSuppressed(failure) ?: run { first = failure }
            }
        }
        attempt { joined?.close() }
        inputs.asReversed().forEach { input -> attempt { input.close() } }
        attempt { main.close() }
        first?.let { throw it }
    }

    private inner class Decoders(private val place: Int) : AudioDecoderFactory {
        override val name: String
            get() = main.audioDecoders.getOrNull(place)?.name
                ?: inputs.firstNotNullOfOrNull { it.session.audioDecoders.getOrNull(place)?.name }
                ?: "external audio"

        override suspend fun create(stream: PlayerStreamInfo): AudioDecoder? {
            val input = inputs.firstOrNull { it.owns(stream.index) }
                ?: return main.audioDecoders.getOrNull(place)?.create(stream)
            // The input's backend knows the stream by its own index and description.
            val own = input.ownStream(stream.index) ?: return null
            val decoder = input.session.audioDecoders.getOrNull(place)?.create(own) ?: return null
            return ExternalAudioDecoder(decoder)
        }
    }
}

/** A decoder of an input's stream. It gives the backend its own packet back, which the backend may downcast. */
private class ExternalAudioDecoder(private val decoder: AudioDecoder) : AudioDecoder by decoder {
    override suspend fun send(packet: PlayerPacket?): Boolean =
        decoder.send(if (packet is ExternalAudioPacket) packet.own else packet)
}

/** A packet of an input, under the engine's index of its stream. */
internal class ExternalAudioPacket(
    val own: PlayerPacket,
    override val streamIndex: Int,
    /** The whole item's streams, when this packet changed the input's list. */
    override val newStreams: List<PlayerStreamInfo>?,
) : PlayerPacket by own {
    /** The item's tags are the media's. */
    override val newContainerTags: Map<String, String>? get() = null

    /** The item's programmes are the media's. */
    override val newPrograms: List<io.github.yuroyami.kiteplayer.MediaProgram>? get() = null
}

/**
 * The source of an item with external audio inputs (#392): the media's source and each input's,
 * read as one. Every member not listed here answers for the media alone, as the item's length,
 * tags, chapters, attachments, variants, programmes, thumbnails and times of day do.
 *
 * The demux lane makes every call but [interrupt] and the read of [streams], as for any source.
 * The source cannot record: a recording is one file's packets, so [io.github.yuroyami.kiteplayer.spi.RecordingCapable]
 * is not passed on.
 */
internal class ExternalAudioSource(
    private val main: PlayerMediaSource,
    private val inputs: List<ExternalAudioInput>,
) : PlayerMediaSource by main {

    /** One reader: the media's, or an input's. It is read only while one of its streams is selected. */
    private class Reader(val source: PlayerMediaSource, val input: ExternalAudioInput?) {
        /** The reader's own indexes of the streams it reads. */
        var selected: Set<Int> = emptySet()
        var reading = false

        /** The one packet read ahead, which the merge compares, and its time. */
        var ahead: PlayerPacket? = null
        var aheadAtUs = NO_TIME
        var ended = false

        /** True when the reader is not where the other readers are, so it seeks before its next read. */
        var misplaced = false

        fun dropAhead() {
            ahead?.close()
            ahead = null
            aheadAtUs = NO_TIME
        }
    }

    private val readers: List<Reader> = listOf(Reader(main, null)) + inputs.map { Reader(it.session.source, it) }

    /** Where the reads are: the latest time handed out, or the place of the last seek. */
    private var frontierUs = NO_TIME

    /** The media's streams unchanged, then the audio streams of each input in the item's order. */
    override val streams: List<PlayerStreamInfo> get() = withExternalAudio(main.streams)

    fun withExternalAudio(listed: List<PlayerStreamInfo>): List<PlayerStreamInfo> = listed + inputs.flatMap { it.streams }

    /** True only when the media and every input can seek, because a seek moves them all. */
    override val seekable: Boolean get() = readers.all { it.source.seekable }

    /** Always true: each input is a download of its own, so the engine reads only the sound heard. */
    override val separateAudioRenditions: Boolean get() = true

    /**
     * Gives each reader its own part of [indices]. A reader with no stream in the set is not read
     * until it has one again, and its packet read ahead is dropped.
     */
    override fun selectStreams(indices: Set<Int>) {
        require(indices.isNotEmpty()) { "no stream was selected" }
        val wanted = readers.map { HashSet<Int>() }
        for (index in indices) {
            if (index < EXTERNAL_AUDIO_STREAM_BASE) {
                wanted[0] += index
                continue
            }
            val at = inputs.indexOfFirst { input -> input.streams.any { it.index == index } }
            require(at >= 0) { "stream $index is not one this source has" }
            wanted[at + 1] += inputs[at].ownIndex(index)
        }
        // The media first: its refusal of an index leaves every reader as it was.
        readers.forEachIndexed { at, reader ->
            val next = wanted[at]
            if (next.isEmpty()) {
                reader.reading = false
                reader.dropAhead()
                return@forEachIndexed
            }
            if (next != reader.selected) {
                reader.source.selectStreams(next)
                reader.selected = next
            }
            if (!reader.reading) {
                reader.reading = true
                reader.ended = false
                reader.misplaced = true
            }
        }
    }

    /** Asks every reader, since the lane may wait inside any one. False when one of them cannot. */
    override fun interrupt(): Boolean {
        var all = true
        readers.forEach { if (!it.source.interrupt()) all = false }
        return all
    }

    /**
     * The packet with the lowest decode time among the readers that are read, one packet read
     * ahead for each. A packet with no time goes out at once. A reader at its end gives nothing
     * more, and the answer is null once every reader read is at its end. A read that fails, of the
     * media or of an input, fails as any source's read does.
     */
    override suspend fun readPacket(): PlayerPacket? {
        var next: Reader? = null
        for (reader in readers) {
            if (!reader.reading) continue
            if (reader.misplaced) align(reader)
            if (reader.ahead == null && !reader.ended) fill(reader)
            if (reader.ahead == null) continue
            if (reader.aheadAtUs == NO_TIME) {
                next = reader
                break
            }
            if (next == null || reader.aheadAtUs < next.aheadAtUs) next = reader
        }
        if (next == null) {
            // A later read asks each reader again, as it would ask one source at its end again.
            readers.forEach { it.ended = false }
            return null
        }
        val packet = next.ahead
        if (next.aheadAtUs != NO_TIME && next.aheadAtUs > frontierUs) frontierUs = next.aheadAtUs
        next.ahead = null
        next.aheadAtUs = NO_TIME
        return packet
    }

    private suspend fun fill(reader: Reader) {
        val packet = reader.source.readPacket()
        if (packet == null) {
            reader.ended = true
            return
        }
        reader.aheadAtUs = (packet.dts ?: packet.pts)?.micros ?: NO_TIME
        val input = reader.input
        reader.ahead = if (input == null) {
            packet
        } else {
            val merged = packet.newStreams?.let { own ->
                input.relist(own)
                streams
            }
            ExternalAudioPacket(packet, input.engineIndex(packet.streamIndex), merged)
        }
    }

    /** Brings a reader that was not read, or that a failed seek left behind, to where the reads are. */
    private suspend fun align(reader: Reader) {
        reader.misplaced = false
        // Nothing was read and nothing sought, so every reader is at its start.
        if (frontierUs == NO_TIME || !reader.source.seekable) return
        reader.dropAhead()
        reader.ended = false
        reader.source.seekToKeyframe(Pts(frontierUs))
    }

    /** Seeks every reader read, as [seekToKeyframe] with a choice does, and answers the media's landing. */
    override suspend fun seekToKeyframe(target: Pts): Pts? = seek(target, null)

    /**
     * Seeks the media by [choice], then each input read to the keyframe before the place the media
     * landed. When the media does not say where it landed, that place is the time of its next
     * packet, read ahead here, and [target] when it has none. The packets read ahead before the
     * seek are dropped. The answer is the media's own, so the engine finds an unknown landing from
     * the first decoded frame as it always does.
     */
    override suspend fun seekToKeyframe(target: Pts, choice: KeyframeChoice): Pts? = seek(target, choice)

    private suspend fun seek(target: Pts, choice: KeyframeChoice?): Pts? {
        val read = readers.filter { it.reading }
        val first = read.firstOrNull() ?: error("selectStreams must be called before seeking")
        // A seek that fails part way leaves the rest marked, and the next read brings them along.
        read.forEach { reader ->
            reader.dropAhead()
            reader.ended = false
            reader.misplaced = true
        }
        frontierUs = target.micros
        val landing = if (choice == null) first.source.seekToKeyframe(target) else first.source.seekToKeyframe(target, choice)
        first.misplaced = false
        if (read.size == 1) return landing
        var aim = landing
        if (aim == null) {
            fill(first)
            aim = if (first.aheadAtUs == NO_TIME) target else Pts(first.aheadAtUs)
        }
        frontierUs = aim.micros
        for (reader in read) {
            if (reader === first) continue
            reader.source.seekToKeyframe(aim)
            reader.misplaced = false
        }
        return landing
    }

    /** Drops the packets read ahead. The session closes the media's source and each input's. */
    override fun close() {
        readers.forEach { it.dropAhead() }
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
    }
}
