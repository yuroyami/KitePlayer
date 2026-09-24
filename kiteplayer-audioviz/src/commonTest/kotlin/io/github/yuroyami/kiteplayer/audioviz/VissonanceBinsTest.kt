package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.VissonanceBins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vissonance's shared `GetVisualBins`, held to numbers worked out from the page's own arithmetic. */
class VissonanceBinsTest {

    @Test
    fun fractureLayoutTakesOneBinPerBarUntilTheCurveOvertakes() {
        val bins = VissonanceBins(count = 128, start = -200, end = 1300)
        // A start of -200 puts the curve below the bar number, so bars 0 to 65 read bins 1 to 66 one by one.
        for (bar in 0..65) assertEquals(bar + 1, bins.firstBins[bar], "bar $bar")
        assertEquals(77, bins.firstBins[66])
        assertEquals(88, bins.firstBins[67])
        assertEquals(1270, bins.firstBins[127])
        for (bar in 1 until 128) assertTrue(bins.firstBins[bar] > bins.firstBins[bar - 1], "bar $bar must start past bar ${bar - 1}")
    }

    @Test
    fun barZeroOfAStartBeforeTheRowIsTheFloorOfOne() {
        val bins = VissonanceBins(count = 128, start = -200, end = 1300)
        val out = FloatArray(128)
        bins.visualBins(IntArray(2048) { 255 }, out)
        // Bin -200 is undefined, so the page's average is NaN, which becomes 0 and then the floor of 1.
        assertEquals(1f, out[0])
        for (bar in 1 until 128) assertEquals(255f, out[bar], 1e-3f, "bar $bar")
    }

    @Test
    fun theGateRunsFromTheFifthPowerAtTheBassToTheThirdAtTheTreble() {
        val bins = VissonanceBins(count = 128, start = -200, end = 1300)
        val out = FloatArray(128)
        bins.visualBins(IntArray(2048) { 128 }, out)
        // 255 * (128 / 255)^e with e = 5 - 2 i / 128: half the byte range keeps 3 to 13 percent of it.
        assertEquals(8.2142f, out[1], 1e-3f)
        assertEquals(16.1890f, out[64], 1e-3f)
        assertEquals(31.9060f, out[127], 1e-3f)
        assertEquals(5.0, bins.exponent(0))
        assertEquals(4.0, bins.exponent(64))
        bins.visualBins(IntArray(2048), out)
        for (bar in 0 until 128) assertEquals(1f, out[bar], "silence sits on the floor of 1 at bar $bar")
    }

    @Test
    fun eachBarAveragesItsPeakWithThePeakBeforeIt() {
        // Barred's layout: 64 bars from bin 4, where bar 10 reads bins 15 to 18.
        val bins = VissonanceBins(count = 64, start = 4, end = 1300)
        assertEquals(15, bins.firstBins[10])
        assertEquals(19, bins.firstBins[11])
        val bytes = IntArray(2048)
        bytes[16] = 100
        bytes[17] = 255
        val out = FloatArray(64)
        bins.visualBins(bytes, out)
        // Bar 10 peaks at bin 17. It and bar 11 average that peak with a silent neighbour: 127.5 before the gate.
        assertEquals(9.8961f, out[10], 1e-3f)
        assertEquals(10.1127f, out[11], 1e-3f)
        assertEquals(1f, out[9])
        assertEquals(1f, out[12])
    }

    @Test
    fun loudnessIsThePlainMeanOfTheRow() {
        assertEquals(96f, VissonanceBins.loudness(intArrayOf(0, 255, 128, 1)))
        assertEquals(0f, VissonanceBins.loudness(IntArray(2048)))
    }
}
