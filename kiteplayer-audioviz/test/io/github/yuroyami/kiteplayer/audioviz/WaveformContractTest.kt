package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveformContractTest {
    @Test
    fun traceMetadataIdentifiesActualSamplesAndIndependentTriggerOffsets() {
        val analyzer = SpectrumAnalyzer(fftSize = 1024, hop = 256, sampleRate = 48_000, scopePoints = 256, stereoPoints = 512)
        val pcm = FloatArray(3840) { (0.2 * sin(2 * PI * 440 * it / 48_000)).toFloat() }
        analyzer.feed(pcm, 256, 1, -100_000L)
        assertNull(analyzer.latest.scopeMetadata)
        analyzer.feed(pcm.copyOfRange(256, pcm.size), pcm.size - 256, 1)
        val frame = analyzer.latest
        val mono = assertNotNull(frame.scopeMetadata)
        val stereo = assertNotNull(frame.stereoScopeMetadata)
        assertEquals(256, mono.window.sampleCount)
        assertEquals(512, stereo.window.sampleCount)
        assertEquals(WaveformChannels.MeanAll, mono.channels)
        assertEquals(WaveformChannels.FirstPair, stereo.channels)
        assertEquals(1, stereo.sourceChannelCount)
        assertEquals(1f, mono.sourceAmplitudeGain)
        for ((metadata, samples) in listOf(mono to frame.scope, stereo to frame.scopeLeft)) {
            val start = metadata.firstSampleIndex.toInt()
            assertEquals(pcm.size - samples.size + metadata.triggerOffsetSamples, start)
            assertTrue(metadata.triggerOffsetSamples <= 0)
            assertEquals(-100_000L + start * 1_000_000L / 48_000, metadata.window.startMicros)
            assertEquals(-100_000L + (start + samples.size) * 1_000_000L / 48_000, metadata.window.endMicros)
            assertEquals(pcm.slice(start until start + samples.size), samples.toList())
        }
        assertEquals(frame.scopeLeft.toList(), frame.scopeRight.toList())
    }

    @Test
    fun traceGainIsTheSquareRootOfTheSinglePowerGain() {
        val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
        analyzer.setSongReferencePower(0.25)
        val loud = FloatArray(24_000) { (0.1 * sin(2 * PI * 1000 * it / 8_000)).toFloat() }
        analyzer.feed(loud, loud.size, 1)
        assertEquals(2f, analyzer.latest.waveformGain, 1e-6f)
        val first = analyzer.latest.scope.maxOf { abs(it) } * analyzer.latest.waveformGain
        analyzer.feed(FloatArray(8_000) { loud[it] * 0.25f }, 8_000, 1)
        val second = analyzer.latest.scope.maxOf { abs(it) } * analyzer.latest.waveformGain
        assertEquals(first * 0.25f, second, 1e-6f)
        assertNull(analyzer.latest.scopeMetadata?.window?.referenceMicros)
        assertEquals(256, analyzer.stereoPoints)
        assertEquals(analyzer.stereoPoints, analyzer.latest.scopeLeft.size)
    }
}
