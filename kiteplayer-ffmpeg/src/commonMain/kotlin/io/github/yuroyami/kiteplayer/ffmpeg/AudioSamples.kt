package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.Frame

/** Decoded audio in one shape: interleaved floats in -1..1, whatever the codec produced. */
public object AudioSamples {

    /**
     * The frame's samples as interleaved floats, sample by sample with the channels side by side,
     * in -1..1. Planar formats arrive from the frame plane after plane and are re-interleaved
     * here; packed formats are already in order. Integer formats are scaled by their full range,
     * so a full-scale negative sample reads exactly -1.
     *
     * @throws IllegalArgumentException for a sample format no decoder here produces
     */
    public fun toFloatInterleaved(frame: Frame): FloatArray {
        val info = frame.info
        val channels = info.channelCount
        val count = info.sampleCount
        require(channels > 0 && count >= 0) { "a frame with $channels channels and $count samples carries no audio" }
        val bytes = frame.copyPlanesToByteArray()
        val name = info.sampleFormat.name
        val planar = name.endsWith("p")
        val width: Int
        val read: (Int) -> Float
        when (name.removeSuffix("p")) {
            "u8" -> { width = 1; read = { at -> ((bytes[at].toInt() and 0xFF) - 128) / 128f } }
            "s16" -> { width = 2; read = { at -> le16(bytes, at) / 32768f } }
            "s32" -> { width = 4; read = { at -> le32(bytes, at) / 2147483648f } }
            "flt" -> { width = 4; read = { at -> Float.fromBits(le32(bytes, at)) } }
            "dbl" -> { width = 8; read = { at -> Double.fromBits(le64(bytes, at)).toFloat() } }
            else -> throw IllegalArgumentException("cannot read samples in the $name format")
        }
        require(bytes.size >= count * channels * width) {
            "a $name frame of $count samples by $channels channels needs ${count * channels * width} bytes, got ${bytes.size}"
        }
        val out = FloatArray(count * channels)
        for (sample in 0 until count) {
            for (channel in 0 until channels) {
                val index = if (planar) channel * count + sample else sample * channels + channel
                out[sample * channels + channel] = read(index * width)
            }
        }
        return out
    }

    private fun le16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            (bytes[at + 3].toInt() shl 24)

    private fun le64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}
