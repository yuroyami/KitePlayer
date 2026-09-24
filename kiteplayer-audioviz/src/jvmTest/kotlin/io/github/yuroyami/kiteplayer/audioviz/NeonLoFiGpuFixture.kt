package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

/** Exact background inputs plus the actual ordered foreground batches, for a separate Android run. */
internal object NeonLoFiGpuFixture {
    fun write(viz: NeonLoFi, state: VizRenderState, folder: File, mode: Int) {
        val w = viz.world; val view = viz.view
        File(folder, "neonlofi.sksl").writeText(ShaderLibrary.HEADER + NeonLoFiSky.SOURCE)
        // The same values the drawing hands its shader, by name.
        val uniforms = linkedMapOf<String, FloatArray>("uResolution" to floatArrayOf(view.width, view.height))
        uniforms.putAll(viz.shaderUniforms())
        File(folder, "neon-$mode.properties").writeText(uniforms.entries.joinToString("\n") { (name, data) -> "$name=${data.joinToString(",")}" })
        // Two rows of ridges from the history, then the waveform the coast's stripes read.
        val ridge = BufferedImage(256, 3, BufferedImage.TYPE_INT_ARGB)
        for (i in w.profile.indices) {
            val byte = (w.profile[i].coerceIn(0f, 1f) * 255).roundToInt()
            ridge.setRGB(i % 256, i / 256, -0x1000000 or (byte shl 16) or (byte shl 8) or byte)
        }
        for (i in w.wave.indices) {
            val byte = (w.wave[i].coerceIn(0f, 1f) * 255).roundToInt()
            ridge.setRGB(i, 2, -0x1000000 or (byte shl 16) or (byte shl 8) or byte)
        }
        ImageIO.write(ridge, "png", File(folder, "neon-$mode-ridges.png"))
        val bands = BufferedImage(64, 1, BufferedImage.TYPE_INT_ARGB)
        for (i in 0..63) bands.setRGB(i, 0, -0x1000000 or ((state.frame.bands[i] * 255).roundToInt() shl 16))
        ImageIO.write(bands, "png", File(folder, "neon-$mode-bands.png"))
        DataOutputStream(File(folder, "neon-$mode-mesh.bin").outputStream()).use { out ->
            val batches = listOf(viz.decor.sky, viz.decor.city, viz.floor.mesh, viz.decor.rain, viz.decor.glow)
            out.writeInt(batches.size)
            for (mesh in batches) {
                out.writeInt(mesh.vertexCount); out.writeInt(mesh.indexCount)
                for (p in mesh.positionsExact()) out.writeFloat(p)
                for (c in mesh.colorsExact()) out.writeInt(c)
                for (i in mesh.indicesExact()) out.writeShort(i.toInt())
            }
        }
    }
}
