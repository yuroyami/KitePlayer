package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import kotlin.math.cos
import kotlin.math.sin

/**
 * Curtains of light on up to three depths, each sliding sideways at its own rate over a ridge of
 * mountains drawn from the spectrum, brightest round a spot that drifts across the sky. The lower ends reach down on loud bands, rays flicker through them
 * with the treble, a kick runs a wave along the front curtain, and a drop pulls every curtain to the floor.
 */
internal class Aurora : Layered(
    name = "Aurora",
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 503L, groundKind = GroundKind.Fog, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, seed = 503)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.6f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.SlowLevel, VizProperty.Shape),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.5f, livelyTrail = 0.42f)

    private val depths = genes.choice("Depths", 3, start = 2)
    private val rates = genes.number("Scroll rates", 0.7f, 1.5f, 1f)
    private val ridgeHeight = genes.number("Ridge height", 0.08f, 0.22f, 0.14f)
    private val rayDensity = genes.number("Rays", 0.5f, 1.5f, 1f)

    private val stage = Stage(reachX = 0.3f, reachY = 0.3f, start = 0f)
    private val scroll = FloatArray(3)
    private var ridgeScroll = 0f
    private var wave = -1f
    private var dropHold = 0f
    private val floorPull = Envelope(attackPerSecond = 3f, releasePerSecond = 1f)
    private var time = 0f
    private val comets = Comets(size = 0.02f)
    private val curtainMesh = TriangleMesh(maxVertices = 3 * COLUMNS * 6 + 16)
    private val rayMesh = TriangleMesh(maxVertices = RAYS * 4 + 8)
    private val ridge = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        time += dt * state.tempo
        stage.advance(dt * state.idle)
        for (layer in 0 until 3) scroll[layer] += state.stepSeconds / (gestures.cycleSeconds * BARS_PER_SCREEN[layer] / rates.value)
        ridgeScroll += state.stepSeconds / (gestures.cycleSeconds * 8f)
        if (gestures.kick > 0f) wave = 0f
        if (wave >= 0f) {
            wave += state.stepSeconds / (gestures.beatSeconds * 2f)
            if (wave > 1.2f) wave = -1f
        }
        if (gestures.drop) dropHold = gestures.cycleSeconds
        dropHold -= dt
        floorPull.advance(if (dropHold > 0f) 1f else 0f, dt)
        comets.advance(state, gestures, random)
        kit.place(0, wrap(scroll[0]), 0.35f)
        kit.follow(1, comets.travellers)
    }

    /** Where the lower edge of curtain [layer] hangs at [x], as a share of the height. */
    private fun bottomOf(layer: Int, x: Float, state: VizRenderState): Float {
        val bands = state.frame.bandsRel
        val energy = if (bands.isEmpty()) 0f else bands.foldedAt(wrap(x * (1f + 0.3f * layer) - scroll[layer]))
        var bottom = 0.25f + 0.3f * energy + 0.2f * state.lift - 0.06f * layer + stage.y - 0.5f
        if (layer == 0 && wave >= 0f) {
            val distance = (x - wave) / 0.08f
            bottom += 0.12f * kotlin.math.exp(-distance * distance)
        }
        val floor = 1f - ridgeHeight.value * 0.5f
        return bottom + (floor - bottom) * floorPull.value
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        curtainMesh.clear()
        val layers = depths.count(1)
        val lift = 0.3f + 0.7f * state.lift
        for (layer in layers - 1 downTo 0) {
            val far = layer.toFloat() / 3f
            val top = (0.02f + 0.06f * layer + stage.y - 0.5f).coerceAtLeast(0f) * size.height
            for (column in 0 until COLUMNS) {
                val x0 = column.toFloat() / COLUMNS
                val x1 = (column + 1f) / COLUMNS
                val bottom = bottomOf(layer, x0, state) * size.height
                // Brightest round a spot that drifts across the sky, so the curtains are never lit the same way twice.
                val spot = 0.15f + 0.85f * (0.5f + 0.5f * cos(TAU * (x0 - stage.x)))
                val bright = state.palette.argb(x0 * 0.6f + wrap(scroll[layer]) * 0.3f + layer * 0.15f + genes.walk, value = 0.8f, alpha = (0.35f + 0.4f * (1f - far)) * lift * spot)
                val clear = bright and 0x00FFFFFF
                val glowAt = top + (bottom - top) * 0.75f
                val left = x0 * size.width
                val right = x1 * size.width + 1f
                val a = curtainMesh.vertex(left, top, clear)
                val b = curtainMesh.vertex(right, top, clear)
                val c = curtainMesh.vertex(right, glowAt, bright)
                val d = curtainMesh.vertex(left, glowAt, bright)
                curtainMesh.quad(a, b, c, d)
                val e = curtainMesh.vertex(right, bottom, clear)
                val f = curtainMesh.vertex(left, bottom, clear)
                curtainMesh.quad(d, c, e, f)
            }
        }
        drawMesh(curtainMesh, BlendMode.Plus)
        // Vertical rays flickering with the treble.
        rayMesh.clear()
        val count = (RAYS * rayDensity.value / 1.5f).toInt().coerceIn(1, RAYS)
        for (ray in 0 until count) {
            val x = wrap(ray * 0.618f + scroll[0] * 0.5f)
            val flicker = 0.5f + 0.5f * sin(time * (3f + ray % 5) + ray)
            val alpha = (0.05f + 0.3f * state.air) * flicker * lift
            if (alpha <= 0.01f) continue
            val colour = state.palette.argb(0.55f + genes.walk, saturation = 0.4f, value = 1f, alpha = alpha)
            val width = size.width * 0.003f
            val bottom = bottomOf(0, x, state) * size.height
            val left = x * size.width
            val a = rayMesh.vertex(left - width, 0f, colour and 0x00FFFFFF)
            val b = rayMesh.vertex(left + width, 0f, colour and 0x00FFFFFF)
            val c = rayMesh.vertex(left + width, bottom, colour)
            val d = rayMesh.vertex(left - width, bottom, colour)
            rayMesh.quad(a, b, c, d)
        }
        drawMesh(rayMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // The ridge of mountains along the bottom, its shape the spectrum, sliding slowly.
        val bands = state.frame.bandsRel
        ridge.reset()
        ridge.moveTo(0f, size.height)
        for (step in 0..RIDGE) {
            val x = step.toFloat() / RIDGE
            val energy = if (bands.isEmpty()) 0f else bands.foldedAt(wrap(x + ridgeScroll))
            ridge.lineTo(x * size.width, size.height * (1f - ridgeHeight.value * (0.4f + 0.6f * energy)))
        }
        ridge.lineTo(size.width, size.height)
        ridge.close()
        drawPath(ridge, state.palette.low.copy(alpha = 0.9f))
        drawPath(ridge, state.palette.mid.copy(alpha = 0.35f), style = Stroke((size.minDimension * 0.003f).coerceAtLeast(1f)))
    }

    override fun onReset() {
        stage.reset()
        scroll.fill(0f)
        ridgeScroll = 0f
        wave = -1f
        dropHold = 0f
        floorPull.reset()
        time = 0f
        comets.clear()
    }

    private companion object {
        const val COLUMNS = 60
        const val RAYS = 36
        const val RIDGE = 64
        val BARS_PER_SCREEN = floatArrayOf(2f, 4f, 8f)
    }
}
