package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The browser analyser rebuilt from a frame, for the ported drawings. */
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
}
