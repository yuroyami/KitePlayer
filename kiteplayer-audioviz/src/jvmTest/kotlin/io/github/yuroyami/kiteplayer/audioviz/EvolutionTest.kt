package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Evolution
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pacer fires morphs and births from musical activity, never from elapsed time alone. */
class EvolutionTest {

    /** Runs the pacer over [seconds] of a synthetic song at 60 steps a second and answers it. */
    private fun run(samples: FloatArray, seconds: Float, evolution: Evolution = Evolution()): Evolution {
        val player = SongPlayer(samples, bandCount = 48)
        val gestures = Gestures()
        var elapsed = 0f
        var music = 0f
        repeat((seconds * 60f).toInt()) {
            val frame = player.next(1f / 60f)
            elapsed += 1f / 60f
            music += frame.motionRate / 60f
            val state = VizRenderState(frame, elapsed, 1f / 60f, VizPalette.Prism, music)
            gestures.update(state)
            evolution.update(state, gestures)
        }
        return evolution
    }

    @Test
    fun busyMusicFiresMorphsAndBirths() {
        val evolution = run(SyntheticSong.drumLoop(124f), 120f)
        println("evolution: drum loop ${evolution.morphs} morphs and ${evolution.births} births in two minutes")
        assertTrue(evolution.morphs >= 6, "two minutes of drums must fire at least six morphs, fired ${evolution.morphs}")
        assertTrue(evolution.births >= 2, "two minutes of drums must fire at least two births, fired ${evolution.births}")
    }

    @Test
    fun aBalladFiresFewMorphs() {
        val evolution = run(SyntheticSong.calmPad(124f), 120f)
        println("evolution: calm pad ${evolution.morphs} morphs in two minutes")
        assertTrue(evolution.morphs <= 2, "two minutes of a pad must fire at most two morphs, fired ${evolution.morphs}")
    }

    @Test
    fun silenceFiresNothing() {
        val evolution = run(SyntheticSong.silence(64f), 60f)
        assertEquals(0, evolution.morphs)
        assertEquals(0, evolution.births)
        assertEquals(0f, evolution.activity)
    }

    @Test
    fun theSameMusicLateFiresAtDifferentSteps() {
        val loop = SyntheticSong.drumLoop(64f)
        // A quarter second of silence first; the loop's last quarter second falls off the end.
        val late = FloatArray(loop.size).also { loop.copyInto(it, 12_000, 0, loop.size - 12_000) }
        val stepsA = morphSteps(loop)
        val stepsB = morphSteps(late)
        assertTrue(stepsA.isNotEmpty() && stepsA != stepsB, "a quarter second shift must move the morphs: $stepsA against $stepsB")
    }

    private fun morphSteps(samples: FloatArray): List<Int> {
        val player = SongPlayer(samples, bandCount = 48)
        val gestures = Gestures()
        val evolution = Evolution()
        val steps = ArrayList<Int>()
        var elapsed = 0f
        var music = 0f
        repeat(3_600) { step ->
            val frame = player.next(1f / 60f)
            elapsed += 1f / 60f
            music += frame.motionRate / 60f
            val state = VizRenderState(frame, elapsed, 1f / 60f, VizPalette.Prism, music)
            gestures.update(state)
            evolution.update(state, gestures)
            if (evolution.morph) steps += step
        }
        return steps
    }
}
