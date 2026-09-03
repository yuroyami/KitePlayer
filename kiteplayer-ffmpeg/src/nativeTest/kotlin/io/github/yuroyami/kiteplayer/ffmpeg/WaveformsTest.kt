@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.SampleFormat
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Audio apps draw the file. A synthesised sine written through the sink is the oracle: every
 * bucket of a steady tone has the same peak and the same RMS, and both are known in advance.
 */
class WaveformsTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    /**
     * About two seconds of mono 48 kHz audio from [sample] (index to -1..1), written as FLAC.
     * The encoder takes whole frames, so the file runs to the end of the last one and the tone
     * runs with it; the exact length is whatever the file says.
     */
    private suspend fun writeFlac(name: String, sample: (Int) -> Float): String {
        val path = "$mediaDir/../build/waveform-$name.flac"
        val rate = 48_000
        val wanted = rate * 2
        MediaSink.open(path, "flac").use { sink ->
            val encoder = sink.addAudioEncoder(
                AudioEncoderSpec(codec = CodecId.Flac, sampleRate = rate, channels = 1, sampleFormat = SampleFormat.S16),
            )
            val perFrame = encoder.frameSize.takeIf { it > 0 } ?: 1024
            val frames = (wanted + perFrame - 1) / perFrame
            encoder.drive(
                (0 until frames).asFlow().map { i ->
                    val bytes = ByteArray(perFrame * 2)
                    for (s in 0 until perFrame) {
                        val index = i * perFrame + s
                        val value = (sample(index) * 32767f).toInt().coerceIn(-32768, 32767)
                        bytes[s * 2] = (value and 0xFF).toByte()
                        bytes[s * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    Frame.ofAudio(
                        bytes = bytes,
                        sampleCount = perFrame,
                        sampleRate = rate,
                        channels = 1,
                        sampleFormat = SampleFormat.S16,
                        ptsMicros = i.toLong() * perFrame * 1_000_000L / rate,
                    )
                },
            )
        }
        return path
    }

    @Test
    fun `a steady tone gives the same peak and rms in every bucket`() = runBlocking {
        // 1 kHz at 48 kHz is 48 samples a cycle; a bucket of about 2 ms holds about two cycles,
        // so every bucket's peak is the amplitude and its RMS is the textbook amplitude over root
        // two, give or take the fraction of a cycle the bucket edge cuts.
        val path = writeFlac("tone") { i -> 0.5f * sin(2.0 * PI * 1000.0 * i / 48_000.0).toFloat() }
        val waveform = Waveforms.of(MediaItem(path), buckets = 1000)

        assertTrue(
            waveform.bucketDuration >= 2.milliseconds && waveform.bucketDuration < 2.1.milliseconds,
            "a thousand buckets over about two seconds, got ${waveform.bucketDuration}",
        )
        assertEquals(1000, waveform.peaks.size)
        assertEquals(1000, waveform.rms.size)
        waveform.peaks.forEachIndexed { i, peak -> assertTrue(abs(peak - 0.5f) <= 0.02f, "bucket $i peak $peak") }
        waveform.rms.forEachIndexed { i, rms -> assertTrue(abs(rms - 0.354f) <= 0.02f, "bucket $i rms $rms") }
    }

    @Test
    fun `silence is all zeros`() = runBlocking {
        val path = writeFlac("silence") { 0f }
        val waveform = Waveforms.of(MediaItem(path), buckets = 50)
        assertTrue(waveform.peaks.all { it == 0f }, "peaks ${waveform.peaks.toList()}")
        assertTrue(waveform.rms.all { it == 0f })
    }

    @Test
    fun `zero buckets is refused before anything opens`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            Waveforms.of(MediaItem("$mediaDir/does-not-exist.flac"), buckets = 0)
        }
        Unit
    }

    @Test
    fun `a real file draws something within range`() = runBlocking {
        val waveform = Waveforms.of(MediaItem("$mediaDir/audio-flac.flac"), buckets = 200)
        assertTrue(waveform.peaks.all { it in 0f..1f }, "a peak outside 0..1: ${waveform.peaks.toList()}")
        assertTrue(waveform.peaks.any { it > 0.1f }, "music has peaks; got ${waveform.peaks.max()}")
        assertTrue(waveform.rms.indices.all { waveform.rms[it] <= waveform.peaks[it] + 1e-4f }, "rms can never exceed the peak")
    }
}
