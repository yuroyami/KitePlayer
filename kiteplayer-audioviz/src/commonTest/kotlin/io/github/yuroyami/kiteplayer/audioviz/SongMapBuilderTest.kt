package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Song maps built from synthetic development fixtures, streamed the way a scan hands them over. */
class SongMapBuilderTest {
    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    private fun build(mono: FloatArray, complete: Boolean = true): SongMap {
        val builder = SongMapBuilder(TrackId(1))
        val block = FloatArray(1024 * 2)
        var start = 0
        while (start < mono.size) {
            val frames = minOf(1024, mono.size - start)
            for (i in 0 until frames) { block[2 * i] = mono[start + i]; block[2 * i + 1] = mono[start + i] }
            builder.feed(Pts(start * 1_000_000L / 48_000), block, frames, format)
            start += frames
        }
        return builder.build(complete)
    }

    /** Independent programme readings: every 100 ms, the ungated 400 ms K-weighted mean square in dB. */
    private fun programmeLevels(mono: FloatArray): List<Pair<Long, Double>> {
        val power = ProgrammePower(format)
        val out = ArrayList<Pair<Long, Double>>()
        for (index in mono.indices) {
            power.addChannel(0, mono[index])
            power.addChannel(1, mono[index])
            power.endFrame()
            if ((index + 1) % 4_800 == 0 && power.ready && !power.digitalSilence) {
                val centre = ((index + 1) - power.windowSamples / 2) * 1_000_000L / 48_000
                out += centre to 10 * log10(power.meanSquare!!)
            }
        }
        return out
    }

    private fun SongMap.structureKinds(): List<Pair<AudioEventKind, Double>> =
        (0 until structureCount).map { structure(it).kind to structure(it).ptsMicros / 1e6 }

    @Test
    fun aCompleteMapCarriesThe95thPercentileOfProgrammePower() {
        val mono = SyntheticSong.calmPad(10f) + SyntheticSong.drumLoop(10f)
        val map = build(mono)
        val levels = programmeLevels(mono).map { it.second }.filter { it > -70.0 }.sorted()
        val expected = levels[((levels.size - 1) * 0.95).toInt()]
        val reference = assertNotNull(map.referencePower, "a complete map has a reference")
        assertEquals(expected, 10 * log10(reference), 0.3, "the reference in dB")
        assertTrue(map.complete)
        assertTrue(abs(map.coveredThroughMicros - 20_000_000L) <= 25_000L, "covered ${map.coveredThroughMicros}")
    }

    @Test
    fun partialAndSilentMapsHaveNoReference() {
        val partial = build(SyntheticSong.drumLoop(10f), complete = false)
        assertNull(partial.referencePower, "a partial map must not pose as the whole song")
        assertTrue(partial.coveredThroughMicros > 9_900_000L)
        assertNull(build(SyntheticSong.silence(10f)).referencePower, "silence gives no reference")
    }

    @Test
    fun theMapHoldsTheLiveDetectorsDropAtItsTime() {
        val map = build(SyntheticSong.calmPad(20f) + SyntheticSong.drumLoop(20f))
        val drops = map.structureKinds().filter { it.first == AudioEventKind.Drop }
        assertEquals(1, drops.size, "structure: ${map.structureKinds()}")
        assertTrue(abs(drops.single().second - 20.0) <= 0.2, "drop at ${drops.single().second}")
    }

    @Test
    fun aModeChangeTheLiveDetectorMissesIsPlacedNearItsChangePoint() {
        val map = build(TonalFixtures.progression(0, minor = false, seconds = 20f) +
            TonalFixtures.progression(0, minor = true, seconds = 20f))
        val boundaries = map.structureKinds().filter { it.first == AudioEventKind.SectionBoundary }
        assertEquals(1, boundaries.size, "structure: ${map.structureKinds()}")
        assertTrue(abs(boundaries.single().second - 20.0) <= 1.5, "key change placed at ${boundaries.single().second}")
    }

    @Test
    fun keySegmentsDescribeEachKey() {
        val map = build(TonalFixtures.progression(0, minor = false, seconds = 20f) +
            TonalFixtures.progression(9, minor = true, seconds = 20f))
        val keys = (0 until map.keyCount).map { map.key(it) }
        assertTrue(keys.size >= 2, "segments: ${keys.map { "${it.tonic}${it.mode}@${it.startMicros}" }}")
        assertEquals(0 to KeyMode.Major, keys.first().tonic to keys.first().mode)
        assertEquals(9 to KeyMode.Minor, keys.last().tonic to keys.last().mode)
        for (index in 1 until keys.size) assertTrue(keys[index].startMicros >= keys[index - 1].endMicros)
    }

    @Test
    fun theLevelCurveFollowsTheProgrammeLevelAndStaysSmall() {
        val mono = SyntheticSong.calmPad(10f) + SyntheticSong.drumLoop(10f)
        val map = build(mono)
        for ((at, level) in programmeLevels(mono)) {
            val curve = assertNotNull(map.levelAt(at), "no curve value at $at")
            assertEquals(level, curve.toDouble(), 0.5, "level at $at")
        }
        assertTrue(map.levelCurve.size <= 201, "one value per 100 ms, got ${map.levelCurve.size}")
    }

    private fun part(firstCell: Int, cells: Int, db: Float) = SongMapPart(
        coveredThroughMicros = (firstCell + cells) * SongMap.CURVE_STEP_MICROS,
        histogram = IntArray(SongMapBuilder.HISTOGRAM_BINS), readings = 0,
        curve = FloatArray(cells) { db }, curveStartMicros = firstCell * SongMap.CURVE_STEP_MICROS,
        structure = emptyList(), keys = emptyList(),
    )

    @Test
    fun aHoleBetweenPartsCarriesThePreviousReadingForward() {
        val map = mergeSongMapParts(listOf(part(0, 10, -10f), part(20, 10, -30f)), TrackId(1), complete = false)
        assertEquals(30, map.levelCurve.size)
        for (cell in 0 until 10) assertEquals(-10f, map.levelCurve[cell], "cell $cell of the first part")
        for (cell in 10 until 20) assertEquals(-10f, map.levelCurve[cell], "cell $cell of the hole")
        for (cell in 20 until 30) assertEquals(-30f, map.levelCurve[cell], "cell $cell of the second part")
    }

    @Test
    fun cellsBeforeTheFirstReadingTakeTheFirstReading() {
        val map = mergeSongMapParts(listOf(part(5, 10, -20f)), TrackId(1), complete = false)
        assertEquals(10, map.levelCurve.size)
        assertTrue(map.levelCurve.all { it == -20f })
    }

    private fun boundary(seconds: Double) = AudioDetection(
        AudioEventKind.SectionBoundary, (seconds * 1_000_000).toLong(), (seconds * 1_000_000).toLong(), 0f, 0.5f, 0f,
    )

    @Test
    fun keyBoundariesKeepFourSecondsFromEveryOtherBoundary() {
        val structure = listOf(boundary(30.0))
        val changes = listOf(boundary(5.0), boundary(5.2), boundary(9.6), boundary(9.8), boundary(31.0), boundary(40.0))
        val merged = withKeyBoundaries(structure, changes).map { it.ptsMicros / 1_000_000.0 }
        assertEquals(listOf(5.0, 9.6, 30.0, 40.0), merged)
    }
}
