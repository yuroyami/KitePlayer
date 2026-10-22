package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.MusicalBoundaryGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Injected events from every source, independent of any detector. Times are media microseconds. */
class EventSourcesTest {
    private val timeline = SpectrumTimeline(256)

    private fun boundary(at: Long, confirmedAt: Long, kind: AudioEventKind = AudioEventKind.SectionBoundary) =
        AudioDetection(kind, at, confirmedAt, 0.6f, 0.9f, 0.7f)

    /** One analysis at [at]: onsets exactly at [at], plus an optional structural batch. */
    private fun push(at: Long, onset: Boolean = false, structural: List<AudioDetection> = emptyList(),
        structureThrough: Long? = null): Boolean {
        val onsets = if (onset) arrayOf(AudioDetection(AudioEventKind.Onset, at, at, 0.5f, 0.8f, 0.2f)) else emptyArray()
        val structure = structureThrough?.let {
            AudioDetections(at, it, structural.toTypedArray(), AudioEventSource.LiveStructure)
        }
        val frame = SpectrumFrame(at, FloatArray(4), FloatArray(4), FloatArray(4), 0f, 0f, 0f, 0f, 0f, 0f,
            generation = timeline.generation, analysisRevision = timeline.revision,
            detections = AudioDetections(at, at, onsets), structure = structure)
        return timeline.publisher().push(frame)
    }

    private fun AudioEventDelivery.all(): List<DeliveredAudioEvent> = (0 until size).map { this[it] }

    @Test
    fun onsetsStayOnTimeWhileAStructuralCandidateIsPending() {
        val cursor = timeline.eventCursor()
        var delivered = 0
        for (step in 0..300) {
            val at = step * 10_000L
            // The structural detector holds its watermark at zero: a candidate is undecided.
            assertTrue(push(at, onset = step % 50 == 0, structureThrough = 0L))
            val delivery = cursor.sample(at)
            for (item in delivery.all()) {
                assertEquals(0L, item.lateByMicros, "an onset must not wait for structure")
                assertEquals(AudioEventSource.LiveTransient, item.event.source)
                delivered++
            }
            if (step > 0) assertEquals(0L, delivery.structureCompleteThroughMicros)
        }
        assertEquals(6, delivered)
        assertEquals(0L, timeline.structureEventStats.completeThroughMicros)
    }

    @Test
    fun aLateConfirmationIsDeliveredOnceWithItsLateness() {
        val cursor = timeline.eventCursor()
        val found = ArrayList<DeliveredAudioEvent>()
        for (step in 0..400) {
            val at = step * 10_000L
            val confirm = at == 3_500_000L
            push(at, structural = if (confirm) listOf(boundary(2_000_000L, at)) else emptyList(),
                structureThrough = if (at >= 3_500_000L) 2_000_000L else 0L)
            found += cursor.sample(at).all().filter { it.event.source == AudioEventSource.LiveStructure }
        }
        assertEquals(1, found.size, "a confirmation inside the budget is delivered exactly once")
        assertEquals(2_000_000L, found[0].event.detection.ptsMicros, "the original boundary time is kept")
        assertEquals(1_500_000L, found[0].lateByMicros)
        assertEquals(0L, cursor.lateDiscards)
    }

    @Test
    fun aConfirmationBeyondTheStructuralBudgetIsCountedNotDelivered() {
        val cursor = timeline.eventCursor()
        var found = 0
        for (step in 0..500) {
            val at = step * 10_000L
            val confirm = at == 4_500_000L
            push(at, structural = if (confirm) listOf(boundary(1_000_000L, at)) else emptyList(),
                structureThrough = if (at >= 4_500_000L) 1_000_000L else 0L)
            found += cursor.sample(at).size
        }
        assertEquals(0, found, "3.5 s is beyond the 3 s structural budget")
        assertEquals(1L, cursor.lateDiscards)
    }

    @Test
    fun aSongMapDeliversOnTimeAndALiveDuplicateIsCounted() {
        timeline.installSongMap(SongMapEvents(1L, 0L, 60_000_000L, listOf(boundary(2_000_000L, 0L, AudioEventKind.Drop))))
        val cursor = timeline.eventCursor()
        val found = ArrayList<DeliveredAudioEvent>()
        for (step in 0..400) {
            val at = step * 10_000L
            val confirm = at == 3_500_000L
            push(at, structural = if (confirm) listOf(boundary(2_050_000L, at)) else emptyList(),
                structureThrough = if (at >= 3_500_000L) 2_050_000L else 0L)
            found += cursor.sample(at).all()
        }
        assertEquals(1, found.size, "the map's drop, and not the live duplicate")
        assertEquals(AudioEventSource.SongMap, found[0].event.source)
        assertEquals(AudioEventKind.Drop, found[0].event.detection.kind)
        assertEquals(0L, found[0].lateByMicros)
        assertEquals(1L, cursor.duplicateDiscards)
        assertEquals(found[0].event.detection.ptsMicros, assertNotNull(timeline.nextEvent(1_000_000L, AudioEventKind.Drop)).detection.ptsMicros,
            "a mapped boundary can be anticipated")
    }

    @Test
    fun aMapInstalledLateDeliversOnlyWhatIsStillAhead() {
        val cursor = timeline.eventCursor()
        val found = ArrayList<DeliveredAudioEvent>()
        for (step in 0..500) {
            val at = step * 10_000L
            if (at == 3_000_000L) {
                timeline.installSongMap(SongMapEvents(2L, 0L, 60_000_000L,
                    listOf(boundary(2_000_000L, 0L), boundary(4_000_000L, 0L))))
            }
            push(at)
            found += cursor.sample(at).all()
        }
        assertEquals(listOf(4_000_000L), found.map { it.event.detection.ptsMicros },
            "a boundary the playhead already passed is not delivered from a late map")
        assertEquals(0L, found.single().lateByMicros)
    }

    @Test
    fun aSeekDiscardsEarlierConfirmationsAndDeliversMapEventsAgainUnderTheNewIdentity() {
        timeline.installSongMap(SongMapEvents(1L, 0L, 60_000_000L, listOf(boundary(1_000_000L, 0L))))
        val cursor = timeline.eventCursor()
        val found = ArrayList<DeliveredAudioEvent>()
        for (step in 0..150) {
            push(step * 10_000L)
            found += cursor.sample(step * 10_000L).all()
        }
        assertEquals(1, found.size)
        val before = found.single().event
        timeline.reset(Generation(1))
        found.clear()
        for (step in 50..130) {
            val at = step * 10_000L
            // A confirmation after the seek that belongs before the seek point.
            val stale = at == 900_000L
            push(at, structural = if (stale) listOf(boundary(400_000L, at)) else emptyList(),
                structureThrough = if (at >= 900_000L) 400_000L else null)
            found += cursor.sample(at).all()
        }
        assertEquals(1, found.size, "the map's boundary again, and not the stale confirmation")
        val after = found.single().event
        assertEquals(AudioEventSource.SongMap, after.source)
        assertEquals(Generation(1), after.generation)
        assertFalse(after.sameIdentity(before), "a new generation is a new identity")
        assertEquals(1L, cursor.catchUpDiscards, "the stale confirmation is counted")
    }

    @Test
    fun independentViewsEachReceiveAStructuralEventOnce() {
        val first = timeline.eventCursor()
        val second = timeline.eventCursor()
        var seenFirst = 0
        var seenSecond = 0
        for (step in 0..300) {
            val at = step * 10_000L
            push(at, structural = if (at == 2_500_000L) listOf(boundary(1_500_000L, at)) else emptyList(),
                structureThrough = if (at >= 2_500_000L) 1_500_000L else 0L)
            seenFirst += first.sample(at).size
            if (step % 2 == 0) seenSecond += second.sample(at).size
        }
        assertEquals(1, seenFirst)
        assertEquals(1, seenSecond)
    }

    @Test
    fun overflowingTheStructuralHistoryIsObservable() {
        val cursor = timeline.eventCursor()
        cursor.sample(0L)
        for (step in 1..70) {
            val at = step * 100_000L
            push(at, structural = listOf(boundary(at - 50_000L, at)), structureThrough = at - 50_000L)
        }
        assertEquals(64, timeline.structureEventStats.retainedEvents)
        assertEquals(6L, timeline.structureEventStats.evictedEvents)
        // The unread events were overwritten, so the cursor resets instead of replaying a burst.
        val delivery = cursor.sample(100_000L)
        assertTrue(delivery.reset)
        assertEquals(0, delivery.size)
    }

    @Test
    fun aStructuralBatchBehindItsOwnWatermarkIsRejectedWholeWithoutTouchingTheOnsets() {
        assertTrue(push(1_000_000L, structureThrough = 900_000L))
        assertFalse(push(1_010_000L, onset = true, structural = listOf(boundary(800_000L, 1_010_000L)), structureThrough = 900_000L))
        assertNull(timeline.nextEvent(0L, AudioEventKind.Onset), "a rejected frame publishes no onset either")
        assertTrue(push(1_020_000L, onset = true, structureThrough = 900_000L))
        assertEquals(1_020_000L, timeline.eventStats.completeThroughMicros)
        assertEquals(900_000L, timeline.structureEventStats.completeThroughMicros)
    }

    @Test
    fun theBoundaryGateComparesWholeIdentitiesAcrossSources() {
        val gate = MusicalBoundaryGate()
        fun frameWith(event: AudioEvent) = SpectrumFrame(1_000_000L, FloatArray(4), FloatArray(4), FloatArray(4),
            0f, 0f, 0f, 0f, 0f, 0f, events = AudioEventDelivery(Generation.Initial, 0L, null,
                arrayOf(DeliveredAudioEvent(event, 0L))))
        val live = AudioEvent(Generation.Initial, 0L, 0L, boundary(900_000L, 1_000_000L), AudioEventSource.LiveStructure)
        val mapped = AudioEvent(Generation.Initial, 0L, 0L, boundary(950_000L, 0L, AudioEventKind.Drop), AudioEventSource.SongMap)
        assertNotNull(gate.read(frameWith(live)))
        assertNotNull(gate.read(frameWith(mapped)), "sequence zero of another source is a new event")
        assertNull(gate.read(frameWith(mapped)), "the same identity is consumed once")
    }
}
