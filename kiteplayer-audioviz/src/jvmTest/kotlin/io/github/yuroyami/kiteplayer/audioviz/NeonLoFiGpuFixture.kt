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
        val w = viz.world; val f = w.flight; val paint = viz.paint; val view = viz.view
        File(folder, "neonlofi.sksl").writeText(ShaderLibrary.HEADER + NeonLoFiSky.SOURCE)
        val uniforms = linkedMapOf(
            "uResolution" to floatArrayOf(view.width, view.height), "uExposure" to floatArrayOf(paint.exposure),
            "nCamera" to floatArrayOf(cos(f.bank), sin(f.bank), f.height, f.travel.toFloat()),
            "nLife" to floatArrayOf(w.time, f.layout, w.air, w.slowLevel),
            "nStyle" to floatArrayOf(w.controls[11], w.controls[8], w.controls[2], w.regions.weights[3]),
            "nRegions" to w.regions.weights, "nSun" to view.sun,
            "nLens" to floatArrayOf(f.horizontalFocal(view.width, view.height) / view.height),
            "nTexture" to floatArrayOf(w.texture))
        for ((i, name) in listOf("nLow", "nMid", "nHigh", "nCap", "nInk").withIndex()) uniforms[name] = paint.roles.copyOfRange(i * 3, i * 3 + 3)
        File(folder, "neon-$mode.properties").writeText(uniforms.entries.joinToString("\n") { (name, data) -> "$name=${data.joinToString(",")}" })
        val ridge = BufferedImage(256, 2, BufferedImage.TYPE_INT_ARGB)
        for (i in w.profile.indices) {
            val byte = (w.profile[i].coerceIn(0f, 1f) * 255).roundToInt()
            ridge.setRGB(i % 256, i / 256, -0x1000000 or (byte shl 16) or (byte shl 8) or byte)
        }
        ImageIO.write(ridge, "png", File(folder, "neon-$mode-ridges.png"))
        val bands = BufferedImage(64, 1, BufferedImage.TYPE_INT_ARGB)
        for (i in 0..63) bands.setRGB(i, 0, -0x1000000 or ((state.frame.bands[i] * 255).roundToInt() shl 16))
        ImageIO.write(bands, "png", File(folder, "neon-$mode-bands.png"))
        DataOutputStream(File(folder, "neon-$mode-mesh.bin").outputStream()).use { out ->
            val batches = listOf(viz.decor.sky, viz.decor.city, viz.floor.mesh, viz.decor.rain)
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
