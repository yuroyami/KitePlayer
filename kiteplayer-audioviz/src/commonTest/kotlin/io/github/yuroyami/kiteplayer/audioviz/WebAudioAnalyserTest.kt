package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The browser analyser rebuilt from a frame, for the ported drawings, checked against Chrome's own algorithm. */
class WebAudioAnalyserTest {

    @Test
    fun bytesFollowTheBrowserLayoutAndRange() {
        val player = SongPlayer(SyntheticSong.drumLoop(8f), bandCount = 48)
        val analyser = WebAudioAnalyser()
        assertEquals(1024, analyser.binCount)
        var bassSum = 0L
        var trebleSum = 0L
        repeat(180) {
            analyser.update(player.next(1f / 60f))
            for (bin in 0 until 1024) assertTrue(analyser.frequencyBytes[bin] in 0..255)
            // Bins 2 to 12 are about 43 to 260 Hz on the page's 44.1 kHz layout; 400 to 500 are 8.6 to 10.8 kHz.
            for (bin in 2..12) bassSum += analyser.frequencyBytes[bin]
            for (bin in 400..500) trebleSum += analyser.frequencyBytes[bin]
        }
        val bassMean = bassSum / (180f * 11f)
        val trebleMean = trebleSum / (180f * 101f)
        assertTrue(bassMean > trebleMean + 20f, "a drum loop reads louder low than high: $bassMean against $trebleMean")
        assertTrue(bassMean > 60f, "loud bass bins should sit well above the floor, had $bassMean")
    }

    @Test
    fun smoothingHoldsTheRowBetweenFrames() {
        val samples = SyntheticSong.drumLoop(8f)
        val jumpy = WebAudioAnalyser(smoothing = 0f)
        val smooth = WebAudioAnalyser(smoothing = 0.8f)
        val playerA = SongPlayer(samples, bandCount = 48)
        val playerB = SongPlayer(samples, bandCount = 48)
        var jumpyChange = 0L
        var smoothChange = 0L
        val lastJumpy = IntArray(1024)
        val lastSmooth = IntArray(1024)
        repeat(180) { step ->
            jumpy.update(playerA.next(1f / 60f))
            smooth.update(playerB.next(1f / 60f))
            if (step > 0) {
                for (bin in 0 until 1024) {
                    jumpyChange += abs(jumpy.frequencyBytes[bin] - lastJumpy[bin])
                    smoothChange += abs(smooth.frequencyBytes[bin] - lastSmooth[bin])
                }
            }
            jumpy.frequencyBytes.copyInto(lastJumpy)
            smooth.frequencyBytes.copyInto(lastSmooth)
        }
        assertTrue(smoothChange < jumpyChange / 2, "smoothing should at least halve the frame-to-frame change: $smoothChange against $jumpyChange")
    }

    @Test
    fun timeDomainBytesSitAtSilenceForSilenceAndSpanTheRowForSound() {
        val analyser = WebAudioAnalyser()
        val silent = SongPlayer(SyntheticSong.silence(5f), bandCount = 48)
        val row = IntArray(512)
        analyser.timeDomainBytes(silent.next(1f / 60f), row)
        assertTrue(row.all { it == 128 }, "silence reads 128 everywhere")
        val loud = SongPlayer(SyntheticSong.drumLoop(8f), bandCount = 48)
        var spread = 0
        repeat(120) {
            analyser.timeDomainBytes(loud.next(1f / 60f), row)
            spread = maxOf(spread, row.max() - row.min())
        }
        assertTrue(spread > 40, "a drum loop should swing the row, spread was $spread")
    }

    @Test
    fun aFullScaleSineAtABinCentreReadsNearChromesMinus7Point5DecibelsAtEverySize() {
        // 3,750 Hz is the centre of bin 20 of 256, bin 160 of 2048 and bin 320 of 4096, at 48 kHz.
        val samples = FloatArray(RATE * 6) { sin(2.0 * PI * 3_750.0 * it / RATE).toFloat() }
        // Where a page's bins are as narrow as the frame's own spread, a tone is spread wider than Chrome sees it.
        for ((size, lowest) in listOf(256 to -9.0, 2048 to -10.0, 4096 to -11.5)) {
            val analyser = WebAudioAnalyser(fftSize = size, sampleRate = RATE)
            val player = SongPlayer(samples, bandCount = 48)
            repeat(120) { analyser.update(player.next(1f / 60f)) }
            val bin = 3_750 * size / RATE
            val peak = analyser.frequencyDecibels[bin].toDouble()
            assertTrue(peak in lowest..-7.0, "fftSize $size read $peak dB at bin $bin, where Chrome reads $CHROME_SINE_DB")
            assertTrue(analyser.frequencyDecibels.indices.all { analyser.frequencyDecibels[it] <= peak + 0.01 }, "the tone's bin is the loudest at fftSize $size")
        }
    }

    @Test
    fun aSineBetweenTwoBinsSpreadsAsTheWindowSays() {
        // Bin 20.5 of 256 at 48 kHz: the two nearest bins read alike, each down by the window's half-bin loss.
        val samples = FloatArray(RATE * 6) { (0.5 * sin(2.0 * PI * 20.5 * RATE / 256 * it / RATE)).toFloat() }
        val player = SongPlayer(samples, bandCount = 48)
        val analyser = WebAudioAnalyser(fftSize = 256, sampleRate = RATE)
        val browser = BrowserAnalyser(256)
        repeat(150) {
            val frame = player.next(0.02f)
            val end = frame.power?.window?.endMicros ?: return@repeat
            analyser.read(frame)
            browser.read(samples, (end * RATE / 1_000_000L).toInt())
        }
        assertEquals(analyser.frequencyDecibels[20], analyser.frequencyDecibels[21], 0.3f, "the two nearest bins read alike")
        for (bin in 19..22) {
            val ours = analyser.frequencyDecibels[bin]
            val chrome = browser.decibels[bin]
            assertTrue(abs(ours - chrome) < 1.5f, "bin $bin read $ours dB where Chrome reads $chrome dB")
        }
        assertTrue(analyser.frequencyDecibels[30] < analyser.frequencyDecibels[20] - 60f, "the window's skirt falls away ten bins out")
    }

    @Test
    fun broadbandNoiseReadsTheSameIntegratedLevelWhateverTheFftSize() {
        // Uniform noise of amplitude 0.5 has a mean square of 1/12. Summed over its bins, Chrome's smoothed
        // magnitudes of Blackman snapshots give 0.3046 (the window's mean square) times 2 times pi over 4 of that.
        val random = Random(7)
        val samples = FloatArray(RATE * 6) { (random.nextFloat() * 2f - 1f) * 0.5f }
        val expected = 10.0 * log10(2.0 * 0.3046 * PI / 4.0 / 12.0)
        val levels = ArrayList<Double>()
        for (size in listOf(256, 2048, 4096)) {
            val analyser = WebAudioAnalyser(fftSize = size, sampleRate = RATE)
            val player = SongPlayer(samples, bandCount = 48)
            var total = 0.0
            var reads = 0
            repeat(180) { step ->
                analyser.update(player.next(1f / 60f))
                if (step < 60) return@repeat
                var power = 0.0
                for (bin in 0 until analyser.binCount) power += 10.0.pow(analyser.frequencyDecibels[bin] / 10.0)
                total += power
                reads++
            }
            val level = 10.0 * log10(total / reads)
            levels += level
            assertTrue(abs(level - expected) < 0.75, "fftSize $size integrated $level dB, Chrome's level is $expected dB")
        }
        assertTrue(levels.max() - levels.min() < 0.5, "the integrated level should not depend on the FFT size: $levels")
    }

    @Test
    fun aShortFftReadsTheDrumLoopAsTheBrowserDoes() {
        val samples = SyntheticSong.drumLoop(10f)
        val player = SongPlayer(samples, bandCount = 48)
        val analyser = WebAudioAnalyser(fftSize = 256, sampleRate = RATE)
        val browser = BrowserAnalyser(256)
        var ours = 0L
        var theirs = 0L
        var reads = 0
        repeat(300) { index ->
            val frame = player.next(0.02f)
            val end = frame.power?.window?.endMicros ?: return@repeat
            analyser.read(frame)
            browser.read(samples, (end * RATE / 1_000_000L).toInt())
            // Both rows start at zero and take about half a second to settle.
            if (index < 50) return@repeat
            for (bin in 0 until 80) {
                ours += analyser.frequencyBytes[bin]
                theirs += browser.bytes[bin]
            }
            reads++
        }
        assertTrue(reads > 200, "the player should give a spectrum on every read, had $reads")
        // Real songs sit within 2 percent. This loop's hats die in about 11 ms, and the frame's 43 ms window
        // cannot see the gaps the browser's 5 ms snapshots fall into, so its upper bins read about 10 bytes high.
        val ratio = ours.toDouble() / theirs
        assertTrue(abs(ratio - 1.0) < 0.08, "the 80-bin volume should follow the browser's: ${ours / reads} against ${theirs / reads}")
    }

    @Test
    fun aFrameWithoutASpectrumFadesTheRowToZero() {
        val player = SongPlayer(SyntheticSong.drumLoop(6f), bandCount = 48)
        val analyser = WebAudioAnalyser(fftSize = 256)
        assertEquals(128, analyser.binCount)
        repeat(50) { analyser.read(player.next(0.02f)) }
        assertTrue(analyser.frequencyBytes.sum() > 0, "the drum loop lights the row")
        val nothing = SpectrumFrame.silent(48, 256)
        repeat(200) { analyser.read(nothing) }
        assertTrue(analyser.frequencyBytes.all { it == 0 }, "the row falls to zero, as the browser's does in silence")
    }

    /**
     * Chrome's `AnalyserNode` on raw samples: a periodic Blackman window, twice the transform over
     * its size, smoothing on the magnitude, and bytes truncated from -100 to -30 dB.
     */
    private class BrowserAnalyser(private val size: Int, private val smoothing: Double = 0.8) {
        val bytes = IntArray(size / 2)
        val decibels = FloatArray(size / 2)
        private val magnitude = DoubleArray(size / 2)
        private val window = DoubleArray(size) { n ->
            val x = n.toDouble() / size
            0.42 - 0.5 * cos(2.0 * PI * x) + 0.08 * cos(4.0 * PI * x)
        }
        private val cosines = DoubleArray(size) { cos(2.0 * PI * it / size) }
        private val sines = DoubleArray(size) { sin(2.0 * PI * it / size) }
        private val windowed = DoubleArray(size)

        /** One read of the [size] samples that end just before [end]. */
        fun read(samples: FloatArray, end: Int) {
            for (n in 0 until size) {
                val at = end - size + n
                windowed[n] = (if (at in samples.indices) samples[at].toDouble() else 0.0) * window[n]
            }
            for (bin in 0 until size / 2) {
                var re = 0.0
                var im = 0.0
                for (n in 0 until size) {
                    val turn = (bin * n) % size
                    re += windowed[n] * cosines[turn]
                    im -= windowed[n] * sines[turn]
                }
                val now = 2.0 * sqrt(re * re + im * im) / size
                magnitude[bin] = smoothing * magnitude[bin] + (1.0 - smoothing) * now
                val level = if (magnitude[bin] <= 0.0) -100.0 else 20.0 * log10(magnitude[bin])
                decibels[bin] = level.toFloat()
                bytes[bin] = (255.0 * (level + 100.0) / 70.0).coerceIn(0.0, 255.0).toInt()
            }
        }
    }

    private companion object {
        const val RATE = 48_000

        /** Chrome's reading of a full-scale sine at a bin's centre: 20 log10(0.42), the Blackman window's mean. */
        const val CHROME_SINE_DB = -7.54
    }
}
