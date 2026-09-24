package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.ShortFftAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The browser's analyser with a short FFT, rebuilt from a frame and checked against a direct analysis of the same audio. */
class ShortFftAnalyserTest {

    @Test
    fun theDrumLoopReadsAsADirectBrowserAnalysisOfTheSameAudio() {
        val samples = SyntheticSong.drumLoop(10f)
        val player = SongPlayer(samples, bandCount = 48)
        val analyser = ShortFftAnalyser(sampleRate = RATE)
        val onePoint = WebAudioAnalyser(fftSize = 256, sampleRate = RATE)
        val browser = BrowserAnalyser(256)
        var ours = 0L
        var points = 0L
        var theirs = 0L
        var reads = 0
        repeat(300) { index ->
            val frame = player.next(0.02f)
            val end = frame.power?.window?.endMicros ?: return@repeat
            analyser.read(frame)
            onePoint.update(frame)
            browser.read(samples, (end * RATE / 1_000_000L).toInt())
            // Both rows start at zero and take about half a second to settle.
            if (index < 50) return@repeat
            for (bin in 0 until 80) {
                ours += analyser.frequencyBytes[bin]
                points += onePoint.frequencyBytes[bin]
                theirs += browser.bytes[bin]
            }
            reads++
        }
        assertTrue(reads > 200, "the player should give a spectrum on every read, had $reads")
        val ratio = ours.toDouble() / theirs
        // Real songs sit within 2 percent. This loop's hats die in about 11 ms, and the frame's 43 ms window
        // cannot see the gaps the browser's 5 ms snapshots fall into, so its upper bins read about 10 bytes high.
        assertTrue(abs(ratio - 1.0) < 0.08, "the 80-bin volume should follow the browser's: ${ours / reads} against ${theirs / reads}")
        // One point of the finer spectrum misses most of a wide bin, which is why this analyser exists.
        val pointError = abs(points.toDouble() / theirs - 1.0)
        assertTrue(pointError > 3 * abs(ratio - 1.0), "one point read ${points / reads}, this ${ours / reads}, the browser ${theirs / reads}")
    }

    @Test
    fun aSteadyToneReadsJustBelowTheBrowser() {
        // 3,750 Hz is the centre of bin 20 on a 256-point, 48 kHz layout.
        val samples = FloatArray(RATE * 8) { (0.01 * sin(2.0 * PI * 3_750.0 * it / RATE)).toFloat() }
        val player = SongPlayer(samples, bandCount = 48)
        val analyser = ShortFftAnalyser(sampleRate = RATE)
        val browser = BrowserAnalyser(256)
        repeat(150) {
            val frame = player.next(0.02f)
            val end = frame.power?.window?.endMicros ?: return@repeat
            analyser.read(frame)
            browser.read(samples, (end * RATE / 1_000_000L).toInt())
        }
        // A 0.01 sine reads -47.5 dB, byte 191. A steady tone has no spread to average away, so this reads about 1 dB low.
        assertEquals(191, browser.bytes[20])
        val peak = analyser.frequencyBytes[20]
        assertTrue(peak in 185..191, "bin 20 read $peak against the browser's 191")
        assertTrue(analyser.frequencyBytes[60] < 20, "a bin far from the tone stays near the floor, read ${analyser.frequencyBytes[60]}")
    }

    @Test
    fun aFrameWithoutASpectrumFadesTheRowToZero() {
        val player = SongPlayer(SyntheticSong.drumLoop(6f), bandCount = 48)
        val analyser = ShortFftAnalyser()
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
                bytes[bin] = if (magnitude[bin] <= 0.0) 0 else
                    (255.0 * (20.0 * log10(magnitude[bin]) + 100.0) / 70.0).coerceIn(0.0, 255.0).toInt()
            }
        }
    }

    private companion object {
        const val RATE = 48_000
    }
}
