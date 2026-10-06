package io.github.yuroyami.kiteplayer.output

import android.media.AudioAttributes
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import io.github.yuroyami.kiteplayer.AudioContent
import io.github.yuroyami.kiteplayer.spi.AudioFormat

/**
 * The ONE internal boundary holding every `android.media` call the audio path makes.
 * `AudioTrackSink` is written entirely against this seam, which is what lets the host
 * suite drive every lifecycle and arithmetic arm with a fake and no device, exactly the way the
 * decoder-fallback seam works in the FFmpeg backend module. Production is [PlatformAudioTrackDriver];
 * nothing else in this module may name `AudioTrack`.
 */
internal interface AudioTrackDriver {

    /** The device's own buffer size in sample frames, from `getBufferSizeInFrames`. */
    val bufferSizeInFrames: Int

    /** The track's `audioSessionId`, which is what an `AudioEffect` attaches to. */
    val sessionId: Int

    /**
     * What the device was opened with. [write] takes floats either way: a driver opened in 16-bit
     * converts them itself, with dither, on the writer thread (#445).
     */
    val encoding: DriverEncoding get() = DriverEncoding.Float

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
     * is a device failure and never a reason to spin.
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

/**
 * SCRATCH holder by contract: a driver may reuse one instance across polls, so the
 * caller reads the fields before its next driver call and never retains the object. The sink
 * calls the driver and copies the pair out under one lock, because two of its threads read
 * timestamps. Mutable so each reader can keep a holder of its own.
 */
internal class DriverTimestamp(var framePosition: Long = 0L, var nanoTime: Long = 0L)

internal fun interface AudioTrackDriverFactory {
    /**
     * Opens a device for [accepted] whose sound is [content]. Throwing here is the only failure
     * shape open handles.
     */
    fun open(accepted: AudioFormat, content: AudioContent): AudioTrackDriver
}

/** The sample encoding a device was opened with. */
internal enum class DriverEncoding { Float, Pcm16 }

/**
 * A device opened in 32-bit float, or in 16-bit PCM when the device refuses float (#445).
 *
 * Android has taken float since API 21, but some devices and emulator images still refuse it at
 * `getMinBufferSize` or fail to build the track, and the same device plays 16-bit. A refusal of
 * 16-bit too throws the 16-bit failure, with the float one attached.
 */
internal fun openWithFloatFallback(open: (DriverEncoding) -> AudioTrackDriver): AudioTrackDriver =
    try {
        open(DriverEncoding.Float)
    } catch (floatRefused: Exception) {
        try {
            open(DriverEncoding.Pcm16)
        } catch (pcmRefused: Exception) {
            pcmRefused.addSuppressed(floatRefused)
            throw pcmRefused
        }
    }

/**
 * Converts [count] floats of [source] from [offset] to 16-bit PCM in [destination], with triangular
 * dither of one step, so quantisation leaves an even hiss and not distortion that follows the music.
 * [dither] carries the noise generator's state from one call to the next and is the caller's.
 */
internal fun floatsToPcm16(source: FloatArray, offset: Int, count: Int, destination: ShortArray, dither: Pcm16Dither) {
    for (i in 0 until count) {
        val scaled = source[offset + i] * 32767f + dither.next()
        val rounded = if (scaled >= 0f) (scaled + 0.5f).toInt() else (scaled - 0.5f).toInt()
        destination[i] = rounded.coerceIn(-32768, 32767).toShort()
    }
}

/**
 * Triangular noise of one 16-bit step from a xorshift generator: no allocation and no lock,
 * because it runs on the audio writer thread.
 */
internal class Pcm16Dither(private var state: Int = 0x2545F491) {
    private fun uniform(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x ushr 8) * (1f / (1 shl 24))
    }

    /** The difference of two uniform values, in -1 to 1 steps. */
    fun next(): Float = uniform() - uniform()
}

/**
 * The `AudioAttributes` content type of [content] (#446). Some devices pick their equaliser, their
 * virtual surround or their dialogue processing from it. [AudioContent.Automatic] never reaches a
 * device, because the engine answers it first; it reads as a film, which every open declared
 * before the item could say.
 */
internal fun contentType(content: AudioContent): Int = when (content) {
    AudioContent.Music -> AudioAttributes.CONTENT_TYPE_MUSIC
    AudioContent.Speech -> AudioAttributes.CONTENT_TYPE_SPEECH
    AudioContent.Movie, AudioContent.Automatic -> AudioAttributes.CONTENT_TYPE_MOVIE
}

/**
 * The production driver: MODE_STREAM, PCM float or, on a device that refuses float, 16-bit PCM,
 * USAGE_MEDIA with the content type [contentType] gives for the item, buffer at least `getMinBufferSize`. `AudioTimestamp` nanoTime is on the
 * `System.nanoTime` (CLOCK_MONOTONIC) base, which is why [AndroidMonotonicClock] reads that exact clock and
 * why the internal sink constructor exists: production cannot accidentally pair the timestamp
 * with another time base.
 */
internal class PlatformAudioTrackDriver(
    accepted: AudioFormat,
    override val encoding: DriverEncoding = DriverEncoding.Float,
    content: AudioContent = AudioContent.Movie,
) : AudioTrackDriver {

    private val track: AudioTrack
    private val timestamp = AudioTimestamp()

    /* The 16-bit path's scratch and noise, used by the writer thread alone. Grown only when a
     * write is larger than any before it, which the sink's fixed block makes once. */
    private var shorts = ShortArray(0)
    private val dither = Pcm16Dither()

    init {
        /* The four masks this sink speaks. 5.1 and 7.1-surround use the platform
         * orders that MATCH FFmpeg's native interleave (FL FR FC LFE BL BR [SL SR]), so the
         * engine's samples reach the right speakers without a remap. */
        val channelMask = when (accepted.channels) {
            1 -> PlatformAudioFormat.CHANNEL_OUT_MONO
            2 -> PlatformAudioFormat.CHANNEL_OUT_STEREO
            6 -> PlatformAudioFormat.CHANNEL_OUT_5POINT1
            8 -> PlatformAudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> PlatformAudioFormat.CHANNEL_OUT_STEREO
        }
        val platformEncoding = when (encoding) {
            DriverEncoding.Float -> PlatformAudioFormat.ENCODING_PCM_FLOAT
            DriverEncoding.Pcm16 -> PlatformAudioFormat.ENCODING_PCM_16BIT
        }
        val minBytes = AudioTrack.getMinBufferSize(accepted.sampleRate, channelMask, platformEncoding)
        require(minBytes > 0) {
            "AudioTrack.getMinBufferSize refused ${accepted.sampleRate} Hz ${accepted.channels}ch $encoding: $minBytes"
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType(content))
                    .build(),
            )
            .setAudioFormat(
                PlatformAudioFormat.Builder()
                    .setEncoding(platformEncoding)
                    .setSampleRate(accepted.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBytes)
            .build()
        // A device that cannot run the format can hand back a track it never initialised rather
        // than throw, and that is a refusal too.
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack did not initialise ${accepted.sampleRate} Hz ${accepted.channels}ch $encoding")
        }
    }

    override val bufferSizeInFrames: Int get() = track.bufferSizeInFrames

    override val sessionId: Int get() = track.audioSessionId

    override fun onWriterThreadStart() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    }

    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun stop() = track.stop()
    override fun flush() = track.flush()
    override fun release() = track.release()

    override fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int {
        if (encoding == DriverEncoding.Float) return track.write(source, offsetFloats, sizeFloats, AudioTrack.WRITE_BLOCKING)
        if (shorts.size < sizeFloats) shorts = ShortArray(sizeFloats)
        floatsToPcm16(source, offsetFloats, sizeFloats, shorts, dither)
        // One short per float, so the count the platform answers is already in floats.
        return track.write(shorts, 0, sizeFloats, AudioTrack.WRITE_BLOCKING)
    }

    /* One holder for the life of the driver; the ~94-per-second poll allocated two
     * objects per call before (the AudioTimestamp was already reused, this one was not). */
    private val out = DriverTimestamp()

    override fun timestamp(): DriverTimestamp? =
        if (track.getTimestamp(timestamp)) {
            out.framePosition = timestamp.framePosition
            out.nanoTime = timestamp.nanoTime
            out
        } else {
            null
        }

    override fun playbackHeadPosition(): Int = track.playbackHeadPosition
}
