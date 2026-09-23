@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CopyStream
import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.Packet
import io.github.yuroyami.kiteffmpeg.StreamInfo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The recording of a [KiteFFmpegSource]: a Matroska file that takes a copy of each packet the source
 * reads, with no re-encode.
 *
 * The engine starts and stops a recording on its own thread while the demux worker copies packets on
 * another, so one lock guards the state. The copy streams are declared at the first packet, on the
 * demux thread, because declaring one reads the input stream's parameters, which a read may change.
 */
internal class SourceRecorder(
    private val source: MediaSource,
    private val warn: (PlaybackWarning) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var recording: Recording? = null

    val path: String? get() = synchronized(lock) { recording?.path }

    fun start(path: String, streams: List<StreamInfo>) {
        synchronized(lock) {
            recording?.let { error("a recording to ${it.path} already runs; stop it first") }
            // The muxer opens its file only at the first packet, so a bad path would otherwise fail
            // later, as a warning, instead of here.
            createEmptyFile(path)
            val sink = try {
                MediaSink.open(path, format = "matroska")
            } catch (refusal: FFmpegException) {
                throw refusal.asRecordingRefusal(path)
            }
            recording = Recording(path, sink, streams.filter(::matroskaHolds))
        }
    }

    /** Finishes the file. Throws when the file cannot be finished; the recording has ended anyway. */
    fun stop() {
        take()?.sink?.close()
    }

    /** Copies [packet] into the file when a recording runs. Called on the demux thread. */
    fun copy(packet: Packet) {
        val (ended, failure) = synchronized(lock) {
            val running = recording ?: return
            try {
                running.copy(packet, source)
                return
            } catch (failure: Exception) {
                recording = null
                running to failure
            }
        }
        runCatching { ended.sink.close() }
        warn(PlaybackWarning.RecordingStopped(ended.path, "a packet could not be written: ${failure.describe()}"))
    }

    /** Ends a recording before the read position jumps, because a file with a jump in it is not a recording. */
    fun endForSeek() {
        finish(take() ?: return, reason = "a seek moved the read position")
    }

    /** Ends a recording quietly when the source closes. Only a file that cannot be finished warns. */
    fun close() {
        finish(take() ?: return, reason = null)
    }

    private fun take(): Recording? = synchronized(lock) { recording.also { recording = null } }

    private fun finish(ended: Recording, reason: String?) {
        val failure = try {
            ended.sink.close()
            null
        } catch (failure: Exception) {
            failure
        }
        val why = failure?.let { "the file could not be finished: ${it.describe()}" } ?: reason ?: return
        warn(PlaybackWarning.RecordingStopped(ended.path, why))
    }

    private class Recording(val path: String, val sink: MediaSink, private val streams: List<StreamInfo>) {
        private var copies: Map<Int, CopyStream>? = null

        /** The stream whose keyframe starts the file, or null when the file has no picture. */
        private val picture: Int? = streams.firstOrNull { it.type == MediaType.Video }?.index

        private var startMicros: Long? = null

        fun copy(packet: Packet, source: MediaSource) {
            val copies = this.copies ?: streams.associate { it.index to sink.addCopyStream(source, it) }
                .also { this.copies = it }
            val copy = copies[packet.streamIndex] ?: return
            // The muxer refuses a packet with no time at all.
            val at = packet.ptsMicros ?: packet.dtsMicros ?: return
            val start = startMicros ?: run {
                // The file starts on a picture it can decode. Without a picture it starts at once.
                if (picture != null && (packet.streamIndex != picture || !packet.isKeyframe)) return
                at.also { startMicros = it }
            }
            // Read after the keyframe but timed before it: sound with no picture yet, or a picture
            // that needs frames from before the keyframe.
            if (at < start) return
            copy.write(packet)
        }
    }
}

/**
 * The subtitle formats Matroska holds. Any other one fails the file header and with it the whole
 * recording, so it is left out. The MP4 text format and teletext are the common cases.
 */
internal fun matroskaHolds(stream: StreamInfo): Boolean =
    stream.type != MediaType.Subtitle || stream.codec.name in MATROSKA_SUBTITLES

private val MATROSKA_SUBTITLES = setOf(
    "subrip", "text", "ass", "webvtt", "dvd_subtitle", "dvb_subtitle", "hdmv_pgs_subtitle", "hdmv_text_subtitle",
)

/** Creates an empty file at [path], or throws what a recording that cannot start throws. */
internal expect fun createEmptyFile(path: String)

/** Unsupported means this platform writes no files at all. Anything else is about [path]. */
private fun FFmpegException.asRecordingRefusal(path: String): RuntimeException = when (error) {
    is FFmpegError.Unsupported -> UnsupportedOperationException("this platform cannot write a recording: $message", this)
    else -> IllegalArgumentException("cannot create the recording at $path: $message", this)
}

private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown failure"
