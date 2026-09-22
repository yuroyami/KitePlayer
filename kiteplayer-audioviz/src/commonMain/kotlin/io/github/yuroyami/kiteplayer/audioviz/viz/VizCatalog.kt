package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Breath
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lantern
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Shatter
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Tide
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Ink
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.LensRain
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.RippleWell
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Twist
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FlowField
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FractalZoom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Phosphor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.ReactionDiffusion
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SmokeRise
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bloom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Blur
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Contour
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Drift
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fountain
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Gemini
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Gravity
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Kaleidoscope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lava
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Mandala
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Melt
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Nebula
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OceanMist
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OilSlick
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pipe
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Plasma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.PrismBurst
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pulse
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Reactor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.RingFlight
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Skidmark
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Smoke
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Sparkle
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Stereogram
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Sunburst
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.AuroraField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.BlobField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NebulaField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.runtimeShadersSupported
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFi

/** Every drawing this library ships, grouped the way a player's menu groups them. */
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

        Sunburst(),
        Gemini(),
        Skidmark(),
        Bloom(),
        Pulse(),

        Blur(),
        Fountain(),
        Gravity(),
        Sparkle(),
        Breath(),
        Tide(),
        Lantern(),

        Contour(),

        Alchemy(),
        Plasma(),
        NebulaField(),
        Nebula(),
        Aurora(),
        AuroraField(),
        Kaleidoscope(),
        Smoke(),

        Reactor(),
        Stereogram(),

        Pipe(),
        RingFlight(),
        Drift(),

        Mandala(),
        Melt(),
        Lava(),
        PrismBurst(),
        OilSlick(),
        Shatter(),

        Twist(),
        RippleWell(),
        Ink(),
        LensRain(),
        FractalZoom(),
        FlowField(),
        Phosphor(),

        Odyssey(),
        NeonLoFi(),
        BlobField(),

        ReactionDiffusion(),
        SmokeRise(),
    ).filter { it.canRunHere() }

    /** The former public catalogue name resolves to the single replacement. */
    internal fun canonicalName(name: String): String =
        if (name.trim().equals("Terrain March", ignoreCase = true)) "Neon Lo-Fi" else name

    internal fun matchesSearch(name: String, query: String): Boolean =
        query.isBlank() || name.contains(query, ignoreCase = true) ||
            (name == "Neon Lo-Fi" && "Terrain March".contains(query, ignoreCase = true))

    private fun Visualization.canRunHere(): Boolean =
        this !is ShaderPreset || runtimeShadersSupported || hasFallback

    /** The same set, split by family, for a menu with headings. */
    public fun byFamily(): Map<VizFamily, List<Visualization>> = create().groupBy { it.family }
}
