package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A song map applied on the analysis worker: its reference, its structure and its verification. */
class SongMapFeedTest {
    private val worker = ManualVizDispatcher()
    private val feed = AudioVizFeed(worker)
    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    @AfterTest
    fun closeFeed() {
        feed.close()
        worker.runAll()
    }

    private fun blocks(mono: FloatArray, each: (Pts, FloatArray, Int) -> Unit) {
        val block = FloatArray(2048)
        var start = 0
        while (start < mono.size) {
            val frames = minOf(1024, mono.size - start)
            for (i in 0 until frames) { block[2 * i] = mono[start + i]; block[2 * i + 1] = mono[start + i] }
            each(Pts(start * 1_000_000L / 48_000), block, frames)
            start += frames
        }
    }

    private fun mapOf(mono: FloatArray): SongMap {
        val builder = SongMapBuilder(TrackId(1))
        blocks(mono) { pts, block, frames -> builder.feed(pts, block, frames, format) }
        return builder.build(true)
    }

    private fun play(mono: FloatArray, afterBlock: () -> Unit = {}) = blocks(mono) { pts, block, frames ->
        feed.onAudio(Generation.Initial, pts, block, frames, format)
        worker.runAll()
        afterBlock()
    }

    @Test
    fun aMatchingMapStaysAndMovesTheSharedGainToItsReference() {
        val song = SyntheticSong.drumLoop(12f)
        val map = mapOf(song)
        var rejected = false
        feed.install(MapInstall(map, "song", 1L) { rejected = true })
        play(song)
        assertEquals(false, rejected, "live audio matches its own map")
        assertEquals(0L, feed.stats.rejectedSongMaps)
        val reference = assertNotNull(map.referencePower)
        val drivers = assertNotNull(assertNotNull(feed.timeline.newest()).drivers)
        assertTrue(abs(drivers.referencePower / reference - 1.0) < 1e-6, "reference ${drivers.referencePower} against $reference")
    }

    @Test
    fun aMapOfOtherAudioIsWithdrawnWithinFiveSecondsOfReadings() {
        val map = mapOf(SyntheticSong.calmPad(12f))
        var rejectedAt: Long? = null
        feed.install(MapInstall(map, "pad", 1L) { rejectedAt = feed.timeline.newest()?.ptsMicros })
        play(SyntheticSong.drumLoop(12f))
        val at = assertNotNull(rejectedAt, "the map was never withdrawn")
        assertTrue(at <= 6_500_000L, "withdrawn at $at")
        assertEquals(1L, feed.stats.rejectedSongMaps)
        val drivers = assertNotNull(assertNotNull(feed.timeline.newest()).drivers)
        assertNotEquals(map.referencePower, drivers.referencePower, "the causal reference is back")
    }

    @Test
    fun mappedStructureArrivesOnTimeAndTheLiveDuplicateIsCounted() {
        val song = SyntheticSong.calmPad(12f) + SyntheticSong.drumLoop(12f)
        val map = mapOf(song)
        assertEquals(listOf(AudioEventKind.Drop), (0 until map.structureCount).map { map.structure(it).kind })
        feed.install(MapInstall(map, "drop", 1L) {})
        val cursor = feed.timeline.eventCursor()
        val found = ArrayList<DeliveredAudioEvent>()
        play(song) {
            val at = feed.timeline.newest()?.takeIf { it.hasTimestamp }?.ptsMicros ?: return@play
            val delivery = cursor.sample(at)
            for (index in 0 until delivery.size) {
                if (delivery[index].event.detection.kind == AudioEventKind.Drop) found += delivery[index]
            }
        }
        val drop = found.single()
        assertEquals(AudioEventSource.SongMap, drop.event.source)
        assertEquals(0L, drop.lateByMicros, "a mapped drop is known before playback reaches it")
        assertEquals(1L, cursor.duplicateDiscards, "the live confirmation of the same drop is a duplicate")
        assertNull(feed.timeline.nextEvent(Long.MAX_VALUE / 2, AudioEventKind.Drop))
    }
}
