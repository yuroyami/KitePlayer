package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.*

/** A musical night drive: today's spectrum underfoot, the heard song along the horizon. */
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
    private val ridges by lazy { PixelImage(256, 2) }
    private var uploadedRevision = -1L
    internal var forcePortable = false
    override val post: PostSpec get() {
        if (forcePortable || !runtimeShadersSupported) return PostSpec.Off
        val finish = world.controls[4]
        return PostSpec(bloom = 0.5f * finish, bloomRadius = 0.018f, threshold = 0.65f,
            vignette = 0.18f, grain = 0.012f * finish * world.flight.motion, glitch = false, aberration = 0f, scanlines = 0.10f * finish)
    }
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.SlowLevel, VizProperty.Size),
        VizDrive(VizDriver.Bass, VizProperty.Shape), VizDrive(VizDriver.Mid, VizProperty.Shape),
        VizDrive(VizDriver.Treble, VizProperty.Brightness), VizDrive(VizDriver.Treble, VizProperty.Texture),
        VizDrive(VizDriver.Timbre, VizProperty.Texture),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Pulse, VizProperty.Camera),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Drop, VizProperty.Size, VizCurve.Scaled, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Speed, VizCurve.Discrete, VizResponse.envelope(3f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Spawn, VizCurve.Discrete, VizResponse.envelope(8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(12f)),
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
        world.advance(state, values, configurationLayout)
    }
    private fun prepare(state: VizRenderState, width: Float, height: Float) {
        paint.prepare(state, world); view.prepare(width, height, world)
    }
    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        prepare(state, view.width, view.height)
        val f = world.flight
        program.uniform("uExposure", paint.exposure)
        fun role(name: String, index: Int) = program.uniform(name, paint.roles[index * 3], paint.roles[index * 3 + 1], paint.roles[index * 3 + 2])
        role("nLow", 0); role("nMid", 1); role("nHigh", 2); role("nCap", 3); role("nInk", 4)
        program.uniform("nCamera", cos(f.bank), sin(f.bank), f.height, f.travel.toFloat())
        program.uniform("nLife", world.time, f.layout, world.air, world.slowLevel)
        program.uniform("nStyle", world.controls[11], world.controls[8], world.controls[2], world.regions.weights[3])
        program.uniforms("nRegions", world.regions.weights)
        program.uniforms("nSun", view.sun)
        program.uniform("nLens", f.horizontalFocal(view.width, view.height) / view.height)
        program.uniform("nTexture", world.texture)
        if (uploadedRevision != world.profileRevision) {
            for (i in world.profile.indices) {
                val byte = (world.profile[i].coerceIn(0f, 1f) * 255f).roundToInt()
                ridges.pixels[i] = -0x1000000 or (byte shl 16) or (byte shl 8) or byte
            }
            ridges.upload(); uploadedRevision = world.profileRevision
        }
        program.child("nRidges", ridges.image)
    }
    override fun shaderSize(width: Float, height: Float) { view.prepare(width, height, world) }
    override fun DrawScope.drawFallback(state: VizRenderState) {
        prepare(state, size.width, size.height)
        with(fallback) { draw(world, paint, view) }
    }
    override fun DrawScope.drawTop(state: VizRenderState) {
        prepare(state, size.width, size.height)
        floor.portable = forcePortable || neonLoFiLimitedMesh
        floor.build(world, paint, size.width, size.height)
        decor.build(world, paint, size.width, size.height, floor.portable)
        drawMesh(decor.sky); drawMesh(decor.city); drawMesh(floor.mesh); drawMesh(decor.rain)
    }
    override fun onReset() { world.reset(); uploadedRevision = -1L }
}

/** API 26-28 hardware meshes emit individual triangle paths, and need a deliberately smaller tier. */
internal expect val neonLoFiLimitedMesh: Boolean
