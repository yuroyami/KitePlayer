package io.github.yuroyami.kiteplayer.output

import android.media.AudioAttributes
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import io.github.yuroyami.kiteplayer.spi.AudioFormat

/**
 * The ONE internal boundary holding every `android.media` call the audio path makes (S1.c.4
 * step 3). `AudioTrackSink` is written entirely against this seam, which is what lets the host
 * suite drive every lifecycle and arithmetic arm with a fake and no device, exactly the way the
 * decoder-fallback seam works in the FFmpeg backend module. Production is [PlatformAudioTrackDriver];
 * nothing else in this module may name `AudioTrack`.
 */
internal interface AudioTrackDriver {

    /** The device's own buffer size in sample frames, from `getBufferSizeInFrames`. */
    val bufferSizeInFrames: Int

    /**
     * Called once by the writer thread as its first act. Production raises the thread to
     * THREAD_PRIORITY_AUDIO here; the fake does nothing, which is also why this lives on the
     * seam: android.os.Process is a stub on a host JVM and killed the writer silently when the
     * sink called it directly (caught by the host suite hanging, 2026-08-12).
     */
    fun onWriterThreadStart()

    fun play()

    /** Pauses playback AND unblocks a blocking write in progress; the writer relies on that. */
    fun pause()

    /** Stops playback; also unblocks a blocking write. */
    fun stop()

    /** Discards everything written but not yet played. Only the stop path calls this. */
    fun flush()

    /** Releases the device. After this every other call is a caller bug the fake records. */
    fun release()

    /**
     * Blocking interleaved float write. Returns the number of FLOATS written, which the platform
     * may make smaller than requested when it is interrupted by pause or stop. Zero or negative
     * is a device failure and never a reason to spin (S1.c.4 step 5).
     */
    fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int

    /**
     * The platform's `AudioTimestamp`, or null when it has none yet. The pair is the position of
     * a frame the hardware presented and the [io.github.yuroyami.kiteplayer.MonotonicClock]-based
     * instant it was presented at.
     */
    fun timestamp(): DriverTimestamp?

    /** The RAW 32-bit `playbackHeadPosition`; the sink extends it across wraps (step 6). */
    fun playbackHeadPosition(): Int
}

internal class DriverTimestamp(val framePosition: Long, val nanoTime: Long)

internal fun interface AudioTrackDriverFactory {
    /** Opens a device for [accepted]. Throwing here is the only failure shape open handles. */
    fun open(accepted: AudioFormat): AudioTrackDriver
}

/**
 * The production driver: MODE_STREAM, PCM float, USAGE_MEDIA / CONTENT_TYPE_MOVIE, buffer at
 * least `getMinBufferSize` (S1.c.4 step 3). `AudioTimestamp` nanoTime is on the
 * `System.nanoTime` (CLOCK_MONOTONIC) base, which is why [AndroidMonotonicClock] reads that exact clock and
 * why the internal sink constructor exists: production cannot accidentally pair the timestamp
 * with another time base.
 */
internal class PlatformAudioTrackDriver(accepted: AudioFormat) : AudioTrackDriver {

    private val track: AudioTrack
    private val timestamp = AudioTimestamp()

    init {
        val channelMask = if (accepted.channels == 1) {
            PlatformAudioFormat.CHANNEL_OUT_MONO
        } else {
            PlatformAudioFormat.CHANNEL_OUT_STEREO
        }
        val minBytes = AudioTrack.getMinBufferSize(
            accepted.sampleRate,
            channelMask,
            PlatformAudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBytes > 0) {
            "AudioTrack.getMinBufferSize refused ${accepted.sampleRate} Hz ${accepted.channels}ch float: $minBytes"
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                PlatformAudioFormat.Builder()
                    .setEncoding(PlatformAudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(accepted.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBytes)
            .build()
    }

    override val bufferSizeInFrames: Int get() = track.bufferSizeInFrames

    override fun onWriterThreadStart() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    }

    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun stop() = track.stop()
    override fun flush() = track.flush()
    override fun release() = track.release()

    override fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int =
        track.write(source, offsetFloats, sizeFloats, AudioTrack.WRITE_BLOCKING)

    override fun timestamp(): DriverTimestamp? =
        if (track.getTimestamp(timestamp)) {
            DriverTimestamp(timestamp.framePosition, timestamp.nanoTime)
        } else {
            null
        }

    override fun playbackHeadPosition(): Int = track.playbackHeadPosition
}
