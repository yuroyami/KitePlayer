package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.AcidTunnel
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Blackout
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Breath
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lantern
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Piston
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Riot
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
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.StableFluids
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bloom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Blur
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Contour
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.DanceOfTheFreq
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Drain
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Drift
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Equaliser
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FireStorm
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fountain
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Gemini
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Gravity
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.HexShaft
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Hyperdrive
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Implosion
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Ionizer
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Kaleidoscope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lava
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.LockOn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Mandala
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Melt
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Nebula
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OceanMist
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.OilSlick
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pipe
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Plasma
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.PrismBurst
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Pulse
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Radar
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.RainbowBar
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Reactor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.RingFlight
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Ripple
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Scope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Skidmark
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Smoke
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Sparkle
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Spikes
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Starfield
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Stereogram
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Strands
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.StrobeWeb
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Sunburst
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Terrain
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Tunnel
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Vortex
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Wave
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Wireframe
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Wormhole
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.AuroraField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.BlobField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Cathedral
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Mandelbox
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Menger
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NebulaField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonCity
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.PlasmaField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.runtimeShadersSupported
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.TerrainMarch

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
        Equaliser(),
        OceanMist(),
        Scope(),
        FireStorm(),
        Piston(),

        Spikes(),
        RainbowBar(),
        Vortex(),
        LockOn(),
        Sunburst(),
        Implosion(),
        Gemini(),
        Skidmark(),
        Bloom(),
        Radar(),
        Pulse(),
        Riot(),
        Blackout(),

        Blur(),
        Fountain(),
        Gravity(),
        Sparkle(),
        Wave(),
        Ionizer(),
        Breath(),
        Tide(),
        Lantern(),

        Ripple(),
        DanceOfTheFreq(),
        Strands(),
        Contour(),

        Alchemy(),
        Plasma(),
        PlasmaField(),
        NebulaField(),
        Nebula(),
        Aurora(),
        AuroraField(),
        Kaleidoscope(),
        Tunnel(),
        Smoke(),

        Reactor(),
        Stereogram(),

        Pipe(),
        HexShaft(),
        Wormhole(),
        RingFlight(),
        Starfield(),
        Drift(),
        Terrain(),
        Wireframe(),

        AcidTunnel(),
        Drain(),
        Hyperdrive(),
        Mandala(),
        Melt(),
        Lava(),
        PrismBurst(),
        OilSlick(),
        StrobeWeb(),
        Shatter(),

        Twist(),
        RippleWell(),
        Ink(),
        LensRain(),
        FractalZoom(),
        FlowField(),
        Phosphor(),

        Cathedral(),
        NeonCity(),
        Menger(),
        Mandelbox(),
        TerrainMarch(),
        BlobField(),

        StableFluids(),
        ReactionDiffusion(),
        SmokeRise(),
    ).filter { it.canRunHere() }

    private fun Visualization.canRunHere(): Boolean =
        this !is ShaderPreset || runtimeShadersSupported || hasFallback

    /** The same set, split by family, for a menu with headings. */
    public fun byFamily(): Map<VizFamily, List<Visualization>> = create().groupBy { it.family }
}
