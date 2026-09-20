package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules the automatic chooser follows.
 *
 * A plain timer that moves to the next drawing in the list gets two things wrong that a person
 * would not: it changes in the middle of a phrase, and
 * what it changes to has nothing to do with what the music is doing.
 */
class DirectorTest {
    /** A run opens on a random drawing and walks its own sequence; a given seed plays one back. */
    @Test
    fun theFirstDrawingComesFromTheSeed() {
        val catalogue = VizCatalog.create()
        val starts = (1L..40L).map { VizDirector(catalogue, seed = it).current.name }.toSet()
        assertTrue(starts.size > 1, "forty seeds opened on one drawing: $starts")
        assertEquals(VizDirector(catalogue, seed = 7L).current.name, VizDirector(catalogue, seed = 7L).current.name)
        val unseeded = (1..40).map { VizDirector(catalogue).current.name }.toSet()
        assertTrue(unseeded.size > 1, "forty unseeded directors opened on one drawing: $unseeded")
    }


    init { useSkiaGraphics() } // before catalogue, which builds drawings

    private val catalogue = VizCatalog.create()

    /**
     * Generated audio supplies continuous features. Boundary events are injected independently;
     * these tests qualify scene decisions, not section recognition.
     */
    private fun run(
        mono: FloatArray,
        seconds: Float,
        seed: Long = 1L,
        boundaries: Boolean = true,
        onChange: (String) -> Unit = {},
    ): VizDirector {
        val director = VizDirector(catalogue, seed = seed)
        // Only injected boundary IDs belong to this scene-decision fixture.
        val player = SongPlayer(mono)
        var showing = director.current.name
        var elapsed = 0f
        var boundaryIndex = 0
        while (elapsed < seconds) {
            var frame = player.next(1f / 60f).withEvents()
            if (boundaries && elapsed >= (boundaryIndex + 1) * 12f) {
                frame = boundary(frame, boundaryIndex++.toLong())
            }
            director.advance(frame, 1f / 60f)
            if (director.current.name != showing) {
                showing = director.current.name
                onChange(showing)
            }
            elapsed += 1f / 60f
        }
        return director
    }

    private fun boundary(frame: SpectrumFrame, sequence: Long): SpectrumFrame {
        val hit = AudioEvent(frame.generation, frame.analysisRevision, sequence,
            AudioDetection(AudioEventKind.SectionBoundary, frame.ptsMicros, frame.ptsMicros,
                0.3f, 0.9f, 0.8f))
        return frame.withDeliveredEvents(AudioEventDelivery(frame.generation, frame.analysisRevision,
            frame.ptsMicros, arrayOf(DeliveredAudioEvent(hit, 0L))))
    }

    @Test
    fun aChangeTakesTimeRatherThanHappeningAtOnce() {
        var partWayThrough = 0
        val director = VizDirector(catalogue, seed = 5L)
        val player = SongPlayer(SyntheticSong.drumLoop(70f))
        repeat(60 * 60) { index ->
            val frame = player.next(1f / 60f).withEvents()
            director.advance(if (index > 0 && index % 720 == 0) boundary(frame, index.toLong()) else frame, 1f / 60f)
            if (director.changing) partWayThrough++
        }
        assertTrue(partWayThrough > 30, "a change should last a while, was mid change on $partWayThrough frames")
        assertTrue(director.progress in 0f..1f, "progress should stay in range")
    }

    @Test
    fun itDoesNotRepeatItselfQuickly() {
        val seen = ArrayList<String>()
        run(SyntheticSong.drumLoop(140f), seconds = 130f) { seen += it }
        println("shown over two minutes: ${seen.size} changes, ${seen.toSet().size} different")
        assertTrue(seen.size >= 3, "it should have changed a few times in two minutes, changed ${seen.size}")
        for (index in seen.indices) {
            val window = seen.subList(maxOf(0, index - 7), index)
            assertTrue(
                seen[index] !in window,
                "${seen[index]} came back within eight changes: $seen",
            )
        }
    }

    @Test
    fun busyMusicGetsBusyDrawings() {
        val seen = ArrayList<String>()
        run(SyntheticSong.drumLoop(140f), seconds = 130f) { seen += it }
        val buckets = seen.mapNotNull { name -> catalogue.firstOrNull { it.name == name }?.bucket }
        val calm = buckets.count { it == VizEnergy.Calm }
        println("buckets chosen under a drum loop: $buckets")
        assertTrue(
            calm <= buckets.size / 3,
            "a drum loop should mostly get lively drawings, got $calm calm ones out of ${buckets.size}",
        )
    }

    @Test
    fun quietMusicIsLeftAlone() {
        // No accepted boundary was provided. Quiet music must keep its scene.
        val seen = ArrayList<String>()
        run(SyntheticSong.calmPad(40f), seconds = 38f, boundaries = false) { seen += it }
        println("changes under a pad in 38 seconds: ${seen.size}")
        assertEquals(0, seen.size, "a pad with no boundary should be left alone")
    }

    @Test
    fun theSameSeedGivesTheSameRun() {
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        run(SyntheticSong.drumLoop(90f), seconds = 85f, seed = 99L) { first += it }
        run(SyntheticSong.drumLoop(90f), seconds = 85f, seed = 99L) { second += it }
        assertTrue(first.isNotEmpty(), "the seeded comparison must include scene decisions")
        assertEquals(first, second, "the same seed should replay exactly")
    }

    @Test
    fun choosingByHandOverridesEverything() {
        val director = VizDirector(catalogue, seed = 3L)
        val wanted = catalogue.first { it.name == "Bars" }
        director.show(wanted)
        assertEquals("Bars", director.current.name)
        assertTrue(!director.changing, "choosing by hand should land at once, not fade")
    }

    @Test
    fun everyTransitionIsOneOfTheKnownKinds() {
        // Cheap, but it catches an ordinal added to the enum without a branch in the shader.
        assertEquals(6, VizTransition.entries.size, "the blend program knows about six kinds")
    }

    @Test
    fun changesBeginOnlyAtTheAcceptedBoundaryIncludingNonDownbeatPositions() {
        val director = VizDirector(catalogue, seed = 11L)
        var starts = 0
        repeat(60 * 120) { index ->
            val delta = 1f / 60f
            val at = index / 60f
            val plain = SpectrumFrame((at * 1_000_000L).toLong(), FloatArray(8), FloatArray(8),
                FloatArray(8), 0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f,
                mood = 0.75f, bpm = 120f, beatConfidence = 0.9f, barPhase = 0.37f)
            val accepted = index > 0 && index % 720 == 0
            val frame = if (accepted) boundary(plain, index.toLong()) else plain
            val wasChanging = director.changing
            director.advance(frame, delta)
            if (director.changing && !wasChanging) {
                starts++
                assertTrue(accepted, "scene change without a boundary at $at")
                assertEquals(0.37f, frame.barPhase, "a boundary need not be a counted downbeat")
            }
        }
        assertTrue(starts >= 3, "injected boundaries must actually change scenes")
    }

    @Test
    fun onlyDrawingsWithEchoesAreHandedOff() {
        for (drawing in catalogue) {
            val echoes = drawing.moodSpec != null || drawing.trail > 0f || drawing.warp != null
            assertEquals(
                echoes,
                VizTransition.WarpHandoff in drawing.transitions,
                "${drawing.name} offers the hand-off wrongly",
            )
        }
    }
}
