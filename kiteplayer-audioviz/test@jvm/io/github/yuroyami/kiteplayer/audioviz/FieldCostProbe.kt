package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.LaceReaction
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import java.io.File
import kotlin.test.Test

/**
 * Prints what one frame of the memory field costs on this host. It never fails: the number that
 * matters is the one measured on the ASUS release build, and this is the host figure beside it.
 */
class FieldCostProbe {

    // The field's texture is a Compose bitmap, so the probe needs the Skia graphics even on its own.
    init { useSkiaGraphics() }

    @Test
    fun oneFrameOfTheFieldOnThisHost() {
        val lines = ArrayList<String>()
        for (rows in intArrayOf(90, 108)) {
            val field = MemoryField(rows)
            field.size(16f / 9f)
            field.halfLife = 1f
            val xs = FloatArray(64) { -1.5f + 3f * it / 63f }
            val ys = FloatArray(64) { 0.5f * kotlin.math.sin(it * 0.3f) }
            val flow = Flows.Mixed(Flows.Swirl(0.8f), Flows.Tunnel(0.3f), 0.5f)
            repeat(120) {
                field.advance(flow, 1f / 60f)
                field.line(xs, ys, 64, 1.5f, 1f)
            }
            val started = System.nanoTime()
            repeat(600) {
                field.advance(flow, 1f / 60f)
                field.line(xs, ys, 64, 1.5f, 1f)
            }
            val perFrame = (System.nanoTime() - started) / 600.0 / 1_000_000.0
            lines += "field ${field.columns} by $rows: ${"%.3f".format(perFrame)} ms a frame (advect, decay, a 64 point line)"
        }
        lines.forEach(::println)
        File("build/reports/field-cost.txt").apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }

    @Test
    fun contoursSeaOnThisHost() {
        val field = MemoryField(rows = 90, withExtra = true)
        field.size(16f / 9f)
        field.halfLife = 3f
        val xs = FloatArray(5) { -1.4f + 0.7f * it }
        val ys = FloatArray(5) { 0.3f * kotlin.math.sin(it * 1.3f) }
        val speeds = FloatArray(5) { if (it % 2 == 0) 0.4f else -0.4f }
        val flow = Flows.Vortices({ 5 }, xs, ys, speeds)
        val mask = FloatArray(field.columns * field.rows) { 1f }
        val reaction = LaceReaction()
        reaction.sprout(field, mask, 40, 1.6f, Rng(3L))
        fun frame(index: Int) {
            field.advance(flow, 1f / 60f)
            field.drop(-0.5f + 0.01f * (index % 100), 0.1f, 0.3f, 1f)
            reaction.step(field, mask)
        }
        repeat(120) { frame(it) }
        val started = System.nanoTime()
        repeat(600) { frame(it) }
        val perFrame = (System.nanoTime() - started) / 600.0 / 1_000_000.0
        val line = "contour sea ${field.columns} by ${field.rows}: ${"%.3f".format(perFrame)} ms a frame " +
            "(advect with five vortices, one drop, one reaction step)"
        println(line)
        File("build/reports/field-cost-contour.txt").apply { parentFile.mkdirs() }.writeText(line + "\n")
    }
}
