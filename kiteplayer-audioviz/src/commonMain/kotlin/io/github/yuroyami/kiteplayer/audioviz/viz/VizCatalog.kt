package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fluctus
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bloom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Contour
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Gemini
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Kaleidoscope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OceanMist
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Plasma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pulse
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Stereogram
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.ThinIce
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.AuroraField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Marble
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NebulaField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Pipe
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.runtimeShadersSupported
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFi

/** Every independent preset this library ships, in one flat catalogue. */
public object VizCatalog {

    /**
     * A fresh set of every drawing, in menu order.
     *
     * New instances each call, because a drawing owns its particles and its rings. Build the list
     * once, hold it, and switch between the members. On a device that cannot run shaders, the shader
     * drawings with no stand-in are left out rather than shown black.
     */
    public fun create(): List<Visualization> = listOf(
        Bars(),
        OceanMist(),
        Gemini(),
        Bloom(),
        Pulse(),
        Contour(),
        Alchemy(),
        Plasma(),
        NebulaField(),
        Aurora(),
        AuroraField(),
        Kaleidoscope(),
        Stereogram(),
        Pipe(),
        ThinIce(),
        Fluctus(),
        Glitch(),
        Odyssey(),
        NeonLoFi(),
        Marble(),
    ).filter { it.canRunHere() }

    /** The former public catalogue name resolves to the single replacement. */
    internal fun canonicalName(name: String): String =
        if (name.trim().equals("Terrain March", ignoreCase = true)) "Neon Lo-Fi" else name

    internal fun matchesSearch(name: String, query: String): Boolean =
        query.isBlank() || name.contains(query, ignoreCase = true) ||
            (name == "Neon Lo-Fi" && "Terrain March".contains(query, ignoreCase = true))

    private fun Visualization.canRunHere(): Boolean =
        this !is ShaderPreset || runtimeShadersSupported || hasFallback
}
