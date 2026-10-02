package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import java.io.File
import kotlin.test.Test

/**
 * Prints what one frame of the memory field costs on this host. It never fails: the number that
 * matters is the one measured on the ASUS release build, and this is the host figure beside it.
 */
class FieldCostProbe {

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
}
