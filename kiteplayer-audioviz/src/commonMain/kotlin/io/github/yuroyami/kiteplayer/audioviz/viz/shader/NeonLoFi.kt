package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.*

/**
 * A night drive at dusk toward a sun on the horizon.
 *
 * The sky runs from indigo overhead to a rose glow, and the gold to coral sun is cut by eight gaps,
 * one band each, bass at the bottom. Twelve towers stand on the horizon, one per note name, and a
 * tower's windows light while its note sounds. Terraces beside the road have black faces and neon
 * edges, cyan near and magenta far, and a face lights while its band is loud. The road passes one
 * white dash a beat and one pair of amber lamps a bar. A kick pulses the sun and the road's edges,
 * a snare sends a shooting star, and hats make the stars twinkle. A section drives on to the next
 * region: highway, mountain pass, city or coast. A breakdown slows the car into rain on a wet road
 * that mirrors the sun, and a drop lifts the view off the road for a bar before it lands again.
 */
internal class NeonLoFi : ShaderPreset(NeonLoFiSky.SOURCE, "Neon Lo-Fi", VizEnergy.Mid, 31f, Kit(31_007L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f,
        shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f))) {
    override val hasFallback: Boolean get() = true
    override val frontParallax: Float get() = 0f
    override val fallbackParallax: Float get() = 0f
    override val useRuntimeShader: Boolean get() = !forcePortable
    private val scene = VizParam("Scene", 0f, 4f, 0f).apply {
        step = 1f; choices = listOf("Auto", "Highway", "Mountain pass", "City", "Coast")
    }
    override val params = listOf(scene,
        VizParam("Spectrum floor", 0f, 2f, 1f), VizParam("Musical mountains", 0f, 2f, 1f),
        VizParam("Brightness", 0.3f, 1.5f, 1f), VizParam("Tape finish", 0f, 1.5f, 0.5f),
        VizParam("Journey pace", 0.35f, 2f, 1f), VizParam("Flight speed", 0f, 2f, 1f),
        VizParam("Sun size", 0.5f, 1.5f, 1f), VizParam("Sky ribbons", 0f, 1f, 0.6f),
        VizParam("Rain", 0f, 2f, 1f), VizParam("Hit accents", 0f, 1.5f, 0.7f),
        VizParam("Neon intensity", 0f, 1.8f, 1f), VizParam("Vividness", 0.8f, 1.4f, 1.1f))
    internal val layoutGene = genes.number("Route variation", 0f, 1f, 0.37f)
    private var previewLayout: Float? = null
    internal val configurationLayout: Float get() = previewLayout ?: layoutGene.value
    private val values = FloatArray(13)
    internal val world = NeonLoFiWorld()
    internal val floor = NeonLoFiFloor()
    internal val decor = NeonLoFiDecor()
    internal val paint = NeonLoFiPaint()
    internal val view = NeonLoFiView()
    private val fallback = NeonLoFiFallback()
    private val ridges by lazy { PixelImage(256, 3) }
    internal var forcePortable = false
    /** The drop's lift-off: where it is heading, how far it has come, and the cycle it lands on. */
    private var liftTarget = 0f
    private var landAt = -1
    override val post: PostSpec get() {
        if (forcePortable || !runtimeShadersSupported) return PostSpec.Off
        val finish = world.controls[4]
        return PostSpec(bloom = 0.5f * finish, bloomRadius = 0.018f, threshold = 0.65f,
            vignette = 0.18f, grain = 0.012f * finish * world.flight.motion, glitch = false, aberration = 0f, scanlines = 0.10f * finish)
    }
    override val mapping: VizMapping by mappingOf(
        // The spectrum raises and lights the terraces and cuts the sun's gaps; the heard song's
        // history is the mountains in the pass.
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Treble, VizProperty.Brightness), VizDrive(VizDriver.Treble, VizProperty.Texture),
        // The notes light the towers' windows, and the key turns the sky and the sun a little.
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        // A snare sends a shooting star across the sky, one a bar at most.
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(NeonLoFiWorld.STAR_SECONDS)),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Camera, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Drop, VizProperty.Size, VizCurve.Scaled, VizResponse.envelope(1f)),
        // A breakdown brings rain, and a wet road that mirrors the sun and the lamps.
        VizDrive(VizDriver.Breakdown, VizProperty.Colour, VizCurve.Discrete, VizResponse.envelope(3f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Spawn, VizCurve.Discrete, VizResponse.envelope(8f)),
        // The next region blends in over many seconds, so its first change shows a little after the boundary.
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(12f, delaySeconds = 0.5f)),
        silence = VizSilence.Still, silenceSettleSeconds = 3f,
    )

    /** Preview is an independent simulation with the same selected recipe, not shared actors/history. */
    internal fun copyRecipeFrom(source: NeonLoFi) {
        for (i in params.indices) params[i].value = source.params[i].value
        previewLayout = source.configurationLayout
        world.flight.layout = source.world.flight.layout
    }
    override fun advance(state: VizRenderState) {
        for (i in params.indices) values[i] = params[i].value
        // On a drop the view lifts off the road for one cycle and lands on the first beat after it.
        // Under reduced motion the lift-off plays as light only: the sun stands whole where it is.
        if (gestures.surge) {
            liftTarget = 1f
            landAt = gestures.cycles + if (gestures.cyclePhase > 0.5f) 2 else 1
        }
        if (landAt >= 0 && gestures.cycles >= landAt) { liftTarget = 0f; landAt = -1 }
        liftShown = NeonLoFiFlight.approach(liftShown, liftTarget, state.stepSeconds, if (liftTarget > 0f) 0.35f else 0.2f)
        world.flight.lift = liftShown * state.motionScale.coerceIn(0f, 1f)
        world.advance(state, values, configurationLayout, if (gestures.pulseUsable) gestures.beatSeconds else 0f)
    }
    /** How far the lift-off has come, before reduced motion scales the camera's part of it. */
    internal var liftShown = 0f
        private set
    private fun prepare(state: VizRenderState, width: Float, height: Float) {
        paint.prepare(state, world); view.prepare(width, height, world)
    }

    /** Every value the shader reads, by name, so the device fixture writes exactly what the screen gets. */
    internal fun shaderUniforms(): LinkedHashMap<String, FloatArray> {
        val f = world.flight
        fun role(index: Int) = paint.roles.copyOfRange(index * 3, index * 3 + 3)
        return linkedMapOf(
            "uExposure" to floatArrayOf(paint.exposure),
            "nLow" to role(NeonLoFiPaint.LOW), "nMid" to role(NeonLoFiPaint.MID), "nHigh" to role(NeonLoFiPaint.HIGH),
            "nCap" to role(NeonLoFiPaint.CAP), "nInk" to role(NeonLoFiPaint.INK),
            "nNear" to role(NeonLoFiPaint.NEAR), "nFar" to role(NeonLoFiPaint.FAR),
            "nCamera" to floatArrayOf(cos(f.bank), sin(f.bank), f.height, f.travel.toFloat()),
            "nLife" to floatArrayOf(world.time, f.layout, world.air, world.slowLevel),
            "nStyle" to floatArrayOf(world.controls[11], world.controls[8], world.controls[2], world.regions.weights[3]),
            "nRegions" to world.regions.weights.copyOf(),
            "nSun" to view.sun.copyOf(),
            "nLens" to floatArrayOf(f.horizontalFocal(view.width, view.height) / view.height),
            "nTexture" to floatArrayOf(world.texture),
            "nGaps0" to world.gaps.copyOfRange(0, 4), "nGaps1" to world.gaps.copyOfRange(4, 8),
            // The sun stands whole through a lift-off even when reduced motion keeps the camera down.
            "nPulse" to floatArrayOf(world.kick * world.controls[10] * 0.85f, world.rain.coerceIn(0f, 1f),
                liftShown, f.horizon),
        )
    }
    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        prepare(state, view.width, view.height)
        for ((name, value) in shaderUniforms()) when (value.size) {
            1 -> program.uniform(name, value[0])
            2 -> program.uniform(name, value[0], value[1])
            3 -> program.uniform(name, value[0], value[1], value[2])
            4 -> program.uniform(name, value[0], value[1], value[2], value[3])
            else -> program.uniforms(name, value)
        }
        // The ridges change with the history and the waveform with every frame, so both go up each frame.
        run {
            for (i in world.profile.indices) {
                val byte = (world.profile[i].coerceIn(0f, 1f) * 255f).roundToInt()
                ridges.pixels[i] = -0x1000000 or (byte shl 16) or (byte shl 8) or byte
            }
            // The third row is the waveform, for the stripes of light on the coast's sea.
            for (i in world.wave.indices) {
                val byte = (world.wave[i].coerceIn(0f, 1f) * 255f).roundToInt()
                ridges.pixels[world.profile.size + i] = -0x1000000 or (byte shl 16) or (byte shl 8) or byte
            }
            ridges.upload()
        }
        program.child("nRidges", ridges.image)
    }
    override fun shaderSize(width: Float, height: Float) { view.prepare(width, height, world) }
    override fun DrawScope.drawFallback(state: VizRenderState) {
        prepare(state, size.width, size.height)
        with(fallback) { draw(world, paint, view, liftShown) }
    }
    override fun DrawScope.drawTop(state: VizRenderState) {
        prepare(state, size.width, size.height)
        floor.portable = forcePortable || neonLoFiLimitedMesh
        floor.build(world, paint, size.width, size.height)
        decor.build(world, paint, size.width, size.height, floor.portable)
        drawMesh(decor.sky); drawMesh(decor.city); drawMesh(floor.mesh); drawMesh(decor.rain)
        drawMesh(decor.glow, androidx.compose.ui.graphics.BlendMode.Plus)
    }
    override fun onReset() {
        world.reset(); paint.reset()
        liftTarget = 0f; landAt = -1; liftShown = 0f
    }
}

/** API 26-28 hardware meshes emit individual triangle paths, and need a deliberately smaller tier. */
internal expect val neonLoFiLimitedMesh: Boolean
