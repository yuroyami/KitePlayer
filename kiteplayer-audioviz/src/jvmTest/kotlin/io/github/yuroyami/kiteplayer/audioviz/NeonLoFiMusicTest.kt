package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFiWorld
import java.io.File
import kotlin.math.*
import kotlin.test.*

/** Matched RMS through the real analyzer with one fixed reference; camera and post contribute nothing. */
class NeonLoFiMusicTest {
    private fun pcm(kind: Int): FloatArray {
        var random = 11L
        val out = FloatArray(48_000 * 12) { i ->
            val t = i / 48_000.0
            random = random * 6364136223846793005L + 1442695040888963407L
            val noise = (random shr 40).toFloat() / 8388608f
            val note = ((t * 8).toInt() % 7)
            when (kind) {
                0 -> sin(t * 2 * PI * 880).toFloat()
                1 -> (sin(t * 2 * PI * (440 * 2.0.pow(note / 12.0))) * exp(-(t % 0.125) * 18)).toFloat()
                2 -> noise
                else -> noise * exp(-(t % (if ((t * 3).toInt() % 2 == 0) 0.31 else 0.19)) * 32).toFloat()
            }
        }
        val rms = sqrt(out.sumOf { it.toDouble() * it } / out.size)
        for (i in out.indices) out[i] = (out[i] * 0.16 / rms).toFloat()
        return out
    }
    @Test fun cleanNotesAndIrregularAttacksOrganizeTheFloorDifferentlyFromHeldSound() {
        val report = File("build/neonlofi-qualification/matched-music.csv").apply { parentFile.mkdirs() }
        report.writeText("fixture,mean_energy,local_height_variation,accepted_events,mean_height\n")
        val movement = DoubleArray(4)
        for (kind in 0..3) {
            val player = SongPlayer(pcm(kind), referencePower = 0.0256)
            val world = NeonLoFiWorld()
            val controls = world.controls.copyOf().apply { this[6] = 0f; this[10] = 0f }
            val before = FloatArray(16)
            var energy = 0.0; var height = 0.0
            for (frame in 0 until 420) {
                val sound = player.next(1f / 60)
                world.advance(VizRenderState(sound, frame / 60f, 1f / 60, VizPalette.Mono).also { it.motionScale = 0f }, controls, 0.37f)
                if (frame > 60) for (lane in 0..15) {
                    val h = world.floorHeight(lane, 20f)
                    movement[kind] += abs(h - before[lane]); before[lane] = h; height += h
                }
                energy += sound.energy
            }
            report.appendText("$kind,${energy / 420},${movement[kind]},${world.eventCount},${height / (359 * 16)}\n")
            assertEquals(0.0, world.flight.travel)
        }
        assertTrue(movement[1] > movement[0] * 2.5, "Rapid notes must be visibly more articulated than a held tone")
        assertTrue(movement[3] > movement[2] * 1.3, "Irregular attacks must organize the floor beyond noisy sustain")
    }
}
