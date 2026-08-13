package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * The send and receive shape below mirrors what libavcodec actually does, including the cases a
 * simpler interface cannot express: one packet producing zero frames, one packet producing several,
 * and a decoder that must be drained before it will accept more input.
 *
 * A decoder with a different natural shape, for example the browser's `VideoDecoder`, adapts to
 * this one easily. The reverse is not true, which is why this shape was chosen.
 *
 * Epochs are the other half of the contract. A decoder holds frames from packets it has already
 * accepted, so the epoch a frame belongs to is decided when the packet was offered and not when the
 * frame comes out. That is why `send` carries no generation: the only legal way to move a decoder to
 * a new epoch is [VideoDecoder.flush], which drops what is buffered and takes the new generation as
 * its argument. Stamping the new epoch on a send would relabel frames decoded before it.
 */
public interface VideoDecoderFactory {
    /** Null when this factory cannot handle the stream. The engine then tries the next candidate. */
    public suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder?

    /** For logs and for the warning emitted when a candidate is skipped. */
    public val name: String
}

public interface VideoDecoder : AutoCloseable {
    public val hardware: HwdecStatus

    /**
     * Offers a packet. A null packet starts the drain that flushes the decoder's internal delay.
     *
     * @return false when the decoder did not accept the packet and the caller must [receive] before
     *         offering again. A platform codec may transiently return null from that receive while
     *         its internal worker makes progress. The caller must retry the same packet, never discard it.
     */
    public suspend fun send(packet: PlayerPacket?): Boolean

    /**
     * @return the next decoded frame, or null when more input is needed. The frame carries the
     *         generation the decoder was in when its packets were offered, which is the generation
     *         the last [flush] set.
     */
    public suspend fun receive(): VideoFrame?

    /**
     * True once [receive] has reported the end of the stream, and until the next [flush].
     *
     * Null from [receive] means two different things, "give me more input" and "there is nothing left",
     * and the engine has to tell them apart: one of the six conditions end of stream is made of is that
     * this decoder has nothing left to give. Without the flag the engine would have to infer it from
     * having sent a null packet and then guess how many null receives are enough, which is how a player
     * ends up either cutting the last frames off or never finishing at all.
     */
    public val isDrained: Boolean

    /**
     * Discards all internal state and enters [newGeneration].
     *
     * This is the only epoch boundary a decoder has. Every frame received after it carries
     * [newGeneration], and every frame decoded before it is gone rather than relabelled.
     *
     * Called after the queues have been cleared and before any packet of the new generation is
     * offered. The order matters: flushing before the queues are cleared lets a stale packet reach
     * a freshly flushed decoder.
     */
    public suspend fun flush(newGeneration: Generation)
}

public interface AudioDecoderFactory {
    public suspend fun create(stream: PlayerStreamInfo): AudioDecoder?
    public val name: String
}

public interface AudioDecoder : AutoCloseable {
    /** What this decoder produces. May change mid-stream, which the engine handles. */
    public val outputFormat: AudioFormat

    public suspend fun send(packet: PlayerPacket?): Boolean
    public suspend fun receive(): AudioBuffer?

    /** True once [receive] has reported the end of the stream. See [VideoDecoder.isDrained]. */
    public val isDrained: Boolean

    /** The only epoch boundary. See [VideoDecoder.flush]. */
    public suspend fun flush(newGeneration: Generation)
}

/**
 * Creates subtitle decoders.
 *
 * No backend supplies one: both return an empty list from `BackendSession.subtitleDecoders`, and the
 * engine's own subtitle handler has an empty body, so no cue is decoded, timed or drawn. The SubRip
 * parser in `kiteplayer-subtitles` reads a file but is not connected to playback.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public interface SubtitleDecoderFactory {
    public suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder?
    public val name: String
}

/**
 * Turns subtitle packets into cues.
 *
 * Nothing implements this and nothing calls it. See [SubtitleDecoderFactory].
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public interface SubtitleDecoder : AutoCloseable {
    public suspend fun send(packet: PlayerPacket?): Boolean

    /**
     * @return cues decoded from the packets sent so far. A single packet can produce several cues,
     *         and some formats produce none until the following packet arrives.
     */
    public suspend fun receive(): List<SubtitleCue>

    /** The only epoch boundary. See [VideoDecoder.flush]. */
    public suspend fun flush(newGeneration: Generation)
}

/**
 * Decoded PCM, ready for the engine's filter chain.
 *
 * Unlike a video frame, audio does cross into Kotlin memory. It has to: resampling, channel
 * remixing, tempo change and the drift correction all happen in the engine, and audio is small.
 * One second of 48 kHz stereo float is 384 KB, against 187 MB for a second of 1080p60 video.
 */
public interface AudioBuffer : AutoCloseable {
    public val pts: Pts
    public val format: AudioFormat

    /** Sample frames in this buffer. One frame is one sample for every channel. */
    public val frameCount: Int

    public val generation: Generation

    /**
     * Copies channel [channel] into [into] as floats in the range -1 to 1.
     *
     * Float is the engine's internal format because every filter wants it and because it removes
     * clipping between stages. Conversion to the device's format happens once, at the sink boundary.
     */
    public fun copyChannel(channel: Int, into: FloatArray, offset: Int = 0)
}

public data class AudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
    val channelLayout: ChannelLayout = ChannelLayout.forChannelCount(channels),
    /**
     * Which speaker each channel drives, as the native order mask: one bit per speaker, channels in
     * the order of those bits from the lowest upward.
     *
     * This is what the engine's downmix keys on, and [channels] cannot replace it. Six channels are
     * 5.1 with side surrounds or 5.1 with back surrounds, and a downmix that guesses wrong sends the
     * surround content to speakers the mix never intended. Null when the source declared no layout,
     * or declared one no mask can describe, which is a custom channel order or ambisonics. The engine
     * then falls back to the channel count and says so through a warning.
     */
    val channelLayoutMask: Long? = null,
) {
    public val bytesPerFrame: Int get() = channels * sampleFormat.bytes

    /** Duration of [frames] sample frames at this rate. */
    public fun durationOf(frames: Int): Pts =
        Pts(if (sampleRate == 0) 0 else frames.toLong() * 1_000_000L / sampleRate)

    public fun framesIn(duration: Pts): Int =
        (duration.micros * sampleRate / 1_000_000L).toInt()
}

public enum class SampleFormat(public val bytes: Int, public val isFloat: Boolean) {
    S16(2, false),
    S24(3, false),
    S32(4, false),
    F32(4, true),
    ;
}

/**
 * Which speaker each channel drives.
 *
 * Modelled explicitly because a wrong mapping is worse than a wrong sample rate: it puts dialogue
 * in a surround speaker, which sounds like a broken file rather than a broken player.
 */
public enum class ChannelLayout {
    Mono,
    Stereo,
    Quad,
    Surround51,
    Surround71,
    /** Something the engine does not model. Channels pass through in source order. */
    Unknown,
    ;

    public companion object {
        public fun forChannelCount(channels: Int): ChannelLayout = when (channels) {
            1 -> Mono
            2 -> Stereo
            4 -> Quad
            6 -> Surround51
            8 -> Surround71
            else -> Unknown
        }
    }
}
