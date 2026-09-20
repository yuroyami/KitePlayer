package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored form of a song map: what it keeps, and what it refuses.
 *
 * A stored map is read back into the analysis worker, so a file this release cannot trust has to
 * answer null rather than a map with wrong numbers in it. Every rejection below is a file that a
 * real cache directory produces: an older release's map, a half-written file, and a file that is
 * not a map at all.
 */
class SongMapCodecTest {

    private fun sample(): SongMap = SongMap(
        version = SongMap.VERSION,
        track = TrackId(3),
        coveredThroughMicros = 242_000_000L,
        complete = true,
        referencePower = 0.18407720014689583,
        structure = arrayOf(
            AudioDetection(AudioEventKind.SectionBoundary, 1_000_000L, 1_200_000L, 0.5f, 0.75f, 0.25f),
            AudioDetection(AudioEventKind.Drop, 60_000_000L, 60_100_000L, 0.9f, 0.6f, 0.8f),
        ),
        keys = arrayOf(
            KeySegment(0L, 60_000_000L, 9, KeyMode.Minor, 0.7f),
            KeySegment(60_000_000L, 242_000_000L, 2, KeyMode.Major, 0.55f),
        ),
        levelCurve = floatArrayOf(-30.5f, -28.25f, -70f, 0f, -12.125f),
        curveStartMicros = 100_000L,
    )

    @Test
    fun aMapSurvivesBeingStoredAndReadBack() {
        val original = sample()
        val copy = assertNotNull(decodeSongMap(encodeSongMap(original)), "a map this release wrote")
        assertEquals(original.version, copy.version)
        assertEquals(original.track, copy.track)
        assertEquals(original.coveredThroughMicros, copy.coveredThroughMicros)
        assertEquals(original.complete, copy.complete)
        assertEquals(original.referencePower, copy.referencePower)
        assertEquals(original.curveStartMicros, copy.curveStartMicros)
        assertTrue(original.levelCurve.contentEquals(copy.levelCurve), "the level curve changed")
        assertEquals(original.structureCount, copy.structureCount)
        for (index in 0 until original.structureCount) {
            val was = original.structure(index)
            val now = copy.structure(index)
            assertEquals(was.kind, now.kind)
            assertEquals(was.ptsMicros, now.ptsMicros)
            assertEquals(was.availableMicros, now.availableMicros)
            assertEquals(was.strength, now.strength)
            assertEquals(was.confidence, now.confidence)
            assertEquals(was.surprise, now.surprise)
        }
        assertEquals(original.keyCount, copy.keyCount)
        for (index in 0 until original.keyCount) {
            val was = original.key(index)
            val now = copy.key(index)
            assertEquals(was.startMicros, now.startMicros)
            assertEquals(was.endMicros, now.endMicros)
            assertEquals(was.tonic, now.tonic)
            assertEquals(was.mode, now.mode)
            assertEquals(was.confidence, now.confidence)
        }
        // The map is read back to decide what a picture does, so its readings must match exactly.
        assertEquals(original.levelAt(350_000L), copy.levelAt(350_000L))
    }

    @Test
    fun aPartialMapKeepsItsMissingReference() {
        val partial = SongMap(SongMap.VERSION, TrackId(0), 30_000_000L, complete = false, referencePower = null,
            structure = emptyArray(), keys = emptyArray(), levelCurve = FloatArray(0), curveStartMicros = 0L)
        val copy = assertNotNull(decodeSongMap(encodeSongMap(partial)))
        assertNull(copy.referencePower, "a partial map must not gain a reference")
        assertEquals(false, copy.complete)
        assertEquals(0, copy.levelCurve.size)
    }

    @Test
    fun aFileFromAnotherAnalysisVersionIsRefused() {
        val bytes = encodeSongMap(sample())
        // The version is the second field, right after the magic.
        bytes[4] = (SongMap.VERSION + 1).toByte()
        assertNull(decodeSongMap(bytes), "a map from another analysis version was accepted")
    }

    @Test
    fun aTruncatedOrForeignFileIsRefusedRatherThanCrashing() {
        val bytes = encodeSongMap(sample())
        for (size in listOf(0, 3, 8, 20, bytes.size - 1)) {
            assertNull(decodeSongMap(bytes.copyOf(size)), "$size bytes of a map were accepted")
        }
        assertNull(decodeSongMap(ByteArray(64) { 0x7F }), "a file that is not a map was accepted")
        assertNull(decodeSongMap("not a song map at all".encodeToByteArray()), "text was accepted")
    }

    @Test
    fun aCorruptLengthCannotAskForAnEnormousAllocation() {
        val bytes = encodeSongMap(sample())
        // The structure count sits after magic, version, track, covered, complete, hasReference, reference.
        val at = 4 + 4 + 4 + 8 + 1 + 1 + 8
        for (shift in 0 until 4) bytes[at + shift] = (Int.MAX_VALUE ushr (shift * 8)).toByte()
        assertNull(decodeSongMap(bytes), "a corrupt count was trusted")
    }
}
