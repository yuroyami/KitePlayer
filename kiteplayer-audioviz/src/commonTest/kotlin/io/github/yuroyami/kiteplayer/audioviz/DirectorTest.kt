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

    init { useSkiaGraphics() } // before catalogue, which builds drawings

    private val catalogue = VizCatalog.create()

    /** Plays a fake song through a real analyser and drives the director at sixty frames a second. */
    private fun run(
        mono: FloatArray,
        seconds: Float,
        seed: Long = 1L,
        onChange: (String) -> Unit = {},
    ): VizDirector {
        val director = VizDirector(catalogue, seed = seed)
        val player = SongPlayer(mono)
        var showing = director.current.name
        var elapsed = 0f
        while (elapsed < seconds) {
            val frame = player.next(1f / 60f)
            director.advance(frame, 1f / 60f)
            if (director.current.name != showing) {
                showing = director.current.name
                onChange(showing)
            }
            elapsed += 1f / 60f
        }
        return director
    }

    @Test
    fun aChangeTakesTimeRatherThanHappeningAtOnce() {
        var partWayThrough = 0
        val director = VizDirector(catalogue, seed = 5L)
        val player = SongPlayer(SyntheticSong.drumLoop(70f))
        repeat(60 * 60) {
            director.advance(player.next(1f / 60f), 1f / 60f)
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
        // With no tempo there are no phrases to count, so it falls back to a plain wait. It should
        // still not thrash: a pad should sit on one drawing for a good while.
        val seen = ArrayList<String>()
        run(SyntheticSong.calmPad(40f), seconds = 38f) { seen += it }
        println("changes under a pad in 38 seconds: ${seen.size}")
        assertTrue(seen.size <= 2, "a pad should be left alone, changed ${seen.size} times")
    }

    @Test
    fun theSameSeedGivesTheSameRun() {
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        run(SyntheticSong.drumLoop(90f), seconds = 85f, seed = 99L) { first += it }
        run(SyntheticSong.drumLoop(90f), seconds = 85f, seed = 99L) { second += it }
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
    fun changesBeginOnTheBar() {
        // A steady beat grid made by hand: 120 beats a minute, sure of it, the phrase running on.
        val director = VizDirector(catalogue, seed = 11L)
        val starts = ArrayList<Float>()
        var phrase = 0f
        var wasChanging = false
        repeat(60 * 180) {
            val delta = 1f / 60f
            phrase = (phrase + delta * 2f / 16f) % 1f
            val frame = SpectrumFrame(
                ptsMicros = 0L,
                bands = FloatArray(8) { 0.5f },
                peaks = FloatArray(8),
                scope = FloatArray(8),
                level = 0.5f,
                bass = 0.5f,
                mid = 0.5f,
                treble = 0.5f,
                beat = 0f,
                pulse = 0f,
                energy = 0.8f,
                density = 0.7f,
                mood = 0.75f,
                bpm = 120f,
                beatConfidence = 0.9f,
                barPhase = (phrase * 4f) % 1f,
                phrasePhase = phrase,
            )
            director.advance(frame, delta)
            if (director.changing && !wasChanging) starts += frame.barPhase
            wasChanging = director.changing
        }
        println("changes began at these places in the bar: $starts")
        assertTrue(starts.size >= 3, "three minutes of a steady beat should bring a few changes, brought ${starts.size}")
        assertTrue(starts.all { it < 0.05f || it > 0.95f }, "every change should begin on a bar line: $starts")
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
