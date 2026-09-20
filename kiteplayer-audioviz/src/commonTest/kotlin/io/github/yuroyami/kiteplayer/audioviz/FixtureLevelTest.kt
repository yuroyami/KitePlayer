package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.idle
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.tempo
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the shared readings come to on the fixtures the rendering suites use.
 *
 * Every drawing sizes its light and its speed from these few numbers, so the numbers themselves
 * decide whether a quiet passage can look quieter and whether silence can look settled. The test
 * prints them as a table and holds what no drawing can work around: a passage 12 dB down reads
 * clearly lower, the light a drawing is given falls with it, and silence leaves a drawing's own
 * paths at about a tenth of their speed.
 */
class FixtureLevelTest {

    private class Reading(val name: String) {
        var frames = 0
        var level = 0f
        var energy = 0f
        var mood = 0f
        var drive = 0f
        var lift = 0f
        var motionRate = 0f
        var idle = 0f
        var tempo = 0f
        var gain = 0.0

        fun add(state: VizRenderState) {
            frames++
            level += state.frame.levelRel
            energy += state.frame.energy
            mood += state.frame.mood
            drive += state.drive
            lift += state.lift
            motionRate += state.frame.motionRate
            idle += state.idle
            tempo += state.tempo
            state.frame.drivers?.let { gain += it.powerGain }
        }

        private fun mean(sum: Float) = if (frames == 0) 0f else sum / frames
        val meanDrive get() = mean(drive)
        val meanLift get() = mean(lift)
        val meanIdle get() = mean(idle)
        val meanGain get() = if (frames == 0) 0.0 else gain / frames
        val meanLevel get() = mean(level)

        override fun toString(): String = buildString {
            append(name.padEnd(12))
            for (value in listOf(
                mean(level), mean(energy), mean(mood), mean(drive), mean(lift),
                mean(motionRate), mean(idle), mean(tempo),
            )) {
                append(((value * 100).toInt() / 100f).toString().padStart(7))
            }
            append("   gain ")
            append(if (frames == 0) "0" else (gain / frames).toString().take(7))
        }
    }

    /** Runs [samples] through the analysis the drawings see, and averages the settled part. */
    private fun read(name: String, samples: FloatArray, settleSeconds: Float = 2f, reference: Double = REFERENCE): Reading {
        val player = SongPlayer(samples, referencePower = reference)
        val reading = Reading(name)
        val delta = 1f / 60f
        var time = 0f
        var musicTime = 0f
        var step = 0
        val from = (settleSeconds * 60f).toInt()
        while (step < 420) {
            val frame = player.next(delta)
            time += delta
            musicTime += delta * frame.motionRate
            if (step >= from) {
                reading.add(VizRenderState(frame, time, delta, VizPalette.Prism, musicTime))
            }
            step++
        }
        return reading
    }

    /** A steady tone with a single attack and no rhythm in it, the same one the qualification uses. */
    private fun tone(seconds: Float): FloatArray {
        val rate = 48_000
        val out = FloatArray((seconds * rate).toInt())
        for (index in out.indices) {
            val at = index.toFloat() / rate
            val rise = (at / 0.01f).coerceAtMost(1f)
            out[index] = 0.35f * rise * (sin(2f * PI.toFloat() * 220f * at) + 0.5f * sin(2f * PI.toFloat() * 440f * at))
        }
        return out
    }

    @Test
    fun theFixturesReadFarApartOnLightAndOnSpeed() {
        val loudSong = SyntheticSong.drumLoop(8f)
        val loud = read("drums", loudSong)
        val quiet = read("drums -12dB", FloatArray(loudSong.size) { loudSong[it] * 0.25f })
        val pad = read("calm pad", SyntheticSong.calmPad(8f))
        val held = read("held tone", tone(8f))
        val still = read("silence", SyntheticSong.silence(8f))

        println("       level energy   mood  drive   lift motion   idle  tempo")
        for (reading in listOf(loud, quiet, pad, held, still)) println(reading)

        val sameGain = loud.meanGain == quiet.meanGain
        assertTrue(sameGain, "the two levels were drawn on different gains: ${loud.meanGain} and ${quiet.meanGain}")
        // The standard's own rule for two passages 12 dB apart, with a map in place.
        val levelFall = (loud.meanLevel - quiet.meanLevel) / loud.meanLevel
        assertTrue(levelFall >= 0.3f, "the settled quiet level is only ${levelFall * 100} percent lower")
        val fall = (loud.meanLift - quiet.meanLift) / loud.meanLift
        assertTrue(fall >= 0.25f, "12 dB down gives only ${fall * 100} percent less light to work with")
        val settled = still.meanIdle / loud.meanIdle
        assertTrue(settled <= 0.15f, "silence runs a drawing's own paths at $settled of the speed of drums")
        assertTrue(still.meanDrive <= 0.05f, "silence reads a drive of ${still.meanDrive}")
    }

    private companion object {
        /**
         * The reference a song map would fix, so every fixture is drawn on the same scale.
         *
         * It is the drum fixture's own programme power. The same number is in the qualification
         * suite, and for the same reason: a much smaller reference puts the loud run above the top
         * of the height curve, where 12 dB down reads almost the same.
         */
        const val REFERENCE = 0.046
    }
}
