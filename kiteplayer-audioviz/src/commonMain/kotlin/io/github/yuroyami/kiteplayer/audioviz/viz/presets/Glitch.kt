package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import io.github.yuroyami.kiteplayer.audioviz.viz.*

/** One musical collage: independently articulated layers share a changing composition. */
internal class Glitch : Layered(
    "Glitch", VizEnergy.Mid,
    Kit(7_315L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f,
        cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(0.07f)),
        VizDrive(VizDriver.Bands, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Size, response = VizResponse.envelope(0.28f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, response = VizResponse.envelope(0.18f)),
        VizDrive(VizDriver.HighHit, VizProperty.Texture, response = VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.Section, VizProperty.Shape),
        VizDrive(VizDriver.Drop, VizProperty.Shape),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape),
        silence = VizSilence.Still, silenceSettleSeconds = 2f,
    )
    private val composition = VizParam("Composition", 0f, 5f, 0f).apply {
        step = 1f; choices = listOf("Auto", "Spectrum", "Crystal", "Ribbon", "Tunnel", "Eclipse")
    }
    private val response = VizParam("Response", 0f, 2f, 1f)
    private val changes = VizParam("Scene changes", 0f, 2f, 1f)
    private val travel = VizParam("Travel", 0f, 2f, 1f)
    private val rotation = VizParam("Rotation", 0f, 2f, 1f)
    private val detailDensity = VizParam("Density", 0.3f, 1.5f, 1f)
    private val size = VizParam("Scale", 0.6f, 1.5f, 1f)
    private val wheel = VizParam("Wheel", 0f, 1.5f, 1f)
    private val crystals = VizParam("Crystals", 0f, 1.5f, 1f)
    private val eclipse = VizParam("Eclipse", 0f, 1.5f, 1f)
    private val ribbons = VizParam("Ribbons", 0f, 1.5f, 1f)
    private val tunnel = VizParam("Tunnel", 0f, 1.5f, 1f)
    private val stars = VizParam("Stars", 0f, 1.5f, 0.65f)
    private val spread = VizParam("Colour spread", 0f, 1f, 0.85f)
    private val brightness = VizParam("Brightness", 0.25f, 1.5f, 1f)
    private val glow = VizParam("Glow", 0f, 1f, 0.38f)
    private val chromaticSplit = VizParam("Chromatic split", 0f, 1f, 0.32f)
    private val mirror = VizParam("Mirror", 0f, 4f, 0f).apply {
        step = 1f; choices = listOf("Auto", "Off", "Vertical", "Horizontal", "Both")
    }
    override val params: List<VizParam> = listOf(composition, response, changes, travel, rotation,
        detailDensity, size, wheel, crystals, eclipse, ribbons, tunnel, stars, spread, brightness, glow, chromaticSplit, mirror)
    override val post: PostSpec get() = PostSpec(bloom = glow.value * 0.8f, bloomRadius = 0.025f,
        threshold = 0.60f, vignette = 0.18f, grain = 0.004f, glitch = chromaticSplit.value > 0f,
        aberration = chromaticSplit.value * 0.006f, scanlines = 0.10f)

    internal val scene = GlitchScene()
    private val fields = GlitchFields()
    private val geometry = GlitchGeometry()

    override fun advance(state: VizRenderState) {
        scene.advance(state, response.value, changes.value, travel.value, rotation.value, composition.value.toInt())
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        scene.configure(composition.value.toInt(), response.value)
        drawRect(Color.Black)
        val mirroring = when (mirror.value.toInt()) {
            0 -> if (scene.composition == 3) 2 else 0
            1 -> 0
            else -> mirror.value.toInt() - 1
        }
        fun DrawScope.halfPicture() {
            if (mirroring == 1 || mirroring == 3) {
                clipRect(right = this.size.width * 0.5f) { picture(state) }
                clipRect(left = this.size.width * 0.5f) { scale(-1f, 1f) { picture(state) } }
            } else picture(state)
        }
        if (mirroring == 2 || mirroring == 3) {
            clipRect(bottom = this.size.height * 0.5f) { halfPicture() }
            clipRect(top = this.size.height * 0.5f) { scale(1f, -1f) { halfPicture() } }
        } else halfPicture()
    }

    private fun DrawScope.picture(state: VizRenderState) {
        val light = (state.lift * brightness.value).coerceIn(0f, 1.5f)
        with(fields) {
            draw(scene, detailDensity.value, this@Glitch.size.value, wheel.value * scene.weights[0],
                eclipse.value * scene.weights[2], ribbons.value * scene.weights[3], spread.value, light,
                chromaticSplit.value * state.motionScale)
        }
        with(geometry) {
            draw(scene, detailDensity.value, this@Glitch.size.value, crystals.value * scene.weights[1],
                tunnel.value * scene.weights[4], stars.value, spread.value, light,
                chromaticSplit.value * state.motionScale)
        }
    }

    override fun onReset() { scene.reset() }
}
