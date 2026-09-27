@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MediaItem.audioFilter] through the FFmpeg backend, on one second of a 440 Hz tone at half scale:
 * 16-bit mono PCM at 48 kHz in a WAV file built here.
 */
class AudioFilterTest {

    private fun toneWav(): ByteArray {
        val rate = 48_000
        val frames = rate
        val data = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (0.5 * sin(2.0 * PI * 440.0 * i / rate) * 32_767).roundToInt()
            data[i * 2] = sample.toByte()
            data[i * 2 + 1] = (sample shr 8).toByte()
        }
        val out = ArrayList<Byte>(44 + data.size)
        fun text(value: String) = value.forEach { out += it.code.toByte() }
        fun int32(value: Int) = (0 until 4).forEach { out += (value shr (8 * it)).toByte() }
        fun int16(value: Int) = (0 until 2).forEach { out += (value shr (8 * it)).toByte() }
        text("RIFF"); int32(36 + data.size); text("WAVE")
        text("fmt "); int32(16); int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        text("data"); int32(data.size)
        data.forEach { out += it }
        return out.toByteArray()
    }

    /** Every sample the backend decodes from the tone, through [chain] when it is not null. */
    private fun decodeTone(chain: String?): FloatArray = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(toneWav()), label = "tone.wav").copy(audioFilter = chain)
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Audio })
            source.selectStreams(setOf(stream.index))
            val decoder = source.newAudioDecoder(stream)
            val samples = ArrayList<Float>()
            try {
                suspend fun drain() {
                    while (true) {
                        val buffer = decoder.receive() ?: break
                        try {
                            val values = FloatArray(buffer.frameCount * buffer.format.channels)
                            buffer.copyInterleaved(values)
                            values.forEach { samples += it }
                        } finally {
                            buffer.close()
                        }
                    }
                }
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) drain()
                    } finally {
                        packet.close()
                    }
                    drain()
                }
                decoder.send(null)
                drain()
            } finally {
                decoder.close()
            }
            samples.toFloatArray()
        } finally {
            source.close()
        }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun zeroCrossings(samples: FloatArray): Int = (1 until samples.size).count { (samples[it - 1] < 0f) != (samples[it] < 0f) }

    @Test
    fun aVolumeChainHalvesTheLevel() {
        val plain = decodeTone(null)
        val halved = decodeTone("volume=0.5")
        val ratio = rms(halved) / rms(plain)
        println("volume=0.5: rms ${rms(halved)} against ${rms(plain)}, ratio $ratio")
        assertTrue(abs(ratio - 0.5) < 0.01, "the level moved by $ratio, not by half")
    }

    @Test
    fun aPitchChainDoublesThePitchAtTheSameSpeed() {
        val plain = decodeTone(null)
        val shifted = decodeTone("asetrate=96000,aresample=48000,atempo=0.5")
        val plainHz = zeroCrossings(plain) / 2.0 * 48_000 / plain.size
        val shiftedHz = zeroCrossings(shifted) / 2.0 * 48_000 / shifted.size
        println("pitch chain: ${plain.size} samples at $plainHz Hz became ${shifted.size} at $shiftedHz Hz")
        assertTrue(abs(plainHz - 440.0) < 5.0, "the tone itself reads $plainHz Hz")
        assertTrue(abs(shiftedHz - 880.0) < 18.0, "the shifted tone reads $shiftedHz Hz, not 880")
        // atempo gives back only part of its last window at the end: the ffmpeg command line turns
        // this second into 46,958 samples too. The speed is what must not change, so 3 percent.
        assertTrue(abs(shifted.size - plain.size) < plain.size * 3 / 100, "the length moved from ${plain.size} to ${shifted.size}")
    }
}
