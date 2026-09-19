package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.AudioScanResult
import io.github.yuroyami.kiteplayer.AudioScanSink
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One scan of one audio track. See [io.github.yuroyami.kiteplayer.scanAudio].
 *
 * The loop is the playback decode worker's, minus the queues and the epochs: offer each packet
 * until the decoder accepts it, taking buffers out in between, then drain with a null packet.
 */
internal suspend fun scanMediaAudio(
    backend: MediaBackend,
    media: MediaItem,
    track: TrackId?,
    preferredLanguages: List<String>,
    sink: AudioScanSink,
): AudioScanResult {
    val session = backend.open(media)
    try {
        val source = session.source
        val stream = if (track != null) {
            source.streams.firstOrNull { it.index == track.value && it.kind == TrackKind.Audio }
                ?: throw IllegalArgumentException("track ${track.value} is not an audio track of this media")
        } else {
            pickAudioStream(source.streams, preferredLanguages)
                ?: throw IllegalArgumentException("this media has no audio track to scan")
        }
        source.selectStreams(setOf(stream.index))
        var decoder: AudioDecoder? = null
        for (factory in session.audioDecoders) {
            decoder = factory.create(stream)
            if (decoder != null) break
        }
        val active = decoder ?: throw UnsupportedOperationException("no audio decoder accepted track ${stream.index}")
        try {
            val interleaver = Interleaver()
            var frames = 0L
            var first: Pts? = null
            var end: Pts? = null
            suspend fun deliver(buffer: AudioBuffer) {
                try {
                    val count = buffer.frameCount
                    if (count <= 0) return
                    val samples = interleaver.interleave(buffer)
                    if (first == null) first = buffer.pts
                    end = Pts(buffer.pts.micros + buffer.format.durationOf(count).micros)
                    frames += count
                    sink.onAudio(buffer.pts, samples, count, buffer.format)
                } finally {
                    buffer.close()
                }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val packet = source.readPacket() ?: break
                try {
                    if (packet.streamIndex != stream.index) continue
                    while (!active.send(packet)) {
                        deliver(active.receive() ?: error("decoder refused a packet and produced nothing; this violates the codec contract"))
                    }
                } finally {
                    packet.close()
                }
                while (true) deliver(active.receive() ?: break)
            }
            var idle = 0
            var ending = false
            while (!active.isDrained) {
                currentCoroutineContext().ensureActive()
                if (!ending) {
                    ending = active.send(null)
                    if (!ending) deliver(active.receive() ?: error("decoder refused to drain and produced nothing"))
                    continue
                }
                val buffer = active.receive()
                if (buffer != null) {
                    idle = 0
                    deliver(buffer)
                } else if (!active.isDrained && ++idle > MAX_IDLE_RECEIVES) {
                    break
                }
            }
            return AudioScanResult(TrackId(stream.index), frames, first, end, active.isDrained)
        } finally {
            active.close()
        }
    } finally {
        session.close()
    }
}

/** A platform codec may answer null a few times while its worker catches up; beyond this it is stuck. */
private const val MAX_IDLE_RECEIVES = 10_000
