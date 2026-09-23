package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.drawscope.DrawScope
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two arcs of spokes orbiting each other at opposite ends of one ellipse, each reading its own part
 * of the spectrum, so each crosses the screen twice a lap. A kick throws them apart, the top of a
 * phrase turns them the other way, and a drop smashes them together in the middle.
 */
internal class Gemini : Layered(
    name = "Gemini",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 107L, groundKind = GroundKind.Plasma, groundDim = 0.75f, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.08f, seed = 107)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.spring(0.4f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.74f, livelyTrail = 0.6f, calmSpin = 0.35f, livelySpin = 0.5f)

    private val pairs = genes.choice("Pairs", 2)
    private val shape = genes.number("Orbit shape", 0.4f, 1f, 0.75f)
    private val spokes = genes.choice("Spokes", 3, start = 1)
    private val mirror = genes.choice("Mirror", 2)

    private val stage = Stage(start = 3f)
    private var orbit = 0f
    private var spin = 0f
    private var direction = 1f
    private var swapped = false
    private val apart = Spring(stiffness = 90f, damping = 0.55f)
    private var crash = 0f
    private val comets = Comets()
    private val shards = Sprites(240, 1_107L)
    private val armX = FloatArray(4) { 0.5f }
    private val armY = FloatArray(4) { 0.5f }
    private val mesh = TriangleMesh(maxVertices = 4 * 48 * 4 + 8)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt * state.idle)
        if (gestures.section) {
            direction = -direction
            swapped = !swapped
        }
        orbit += dt * TAU / (gestures.cycleSeconds * 1.3f) * state.tempo * direction
        spin += dt * 1.3f * state.tempo * direction
        apart.kick(gestures.kick * 4f)
        apart.advance(dt)
        if (gestures.drop) {
            crash = 1f
            shards.burst(stage.x, stage.y, 40, 1f, 1f, 0.025f, random.next(), Sprite.SHARD)
        }
        crash = (crash - dt / gestures.beatSeconds).coerceAtLeast(0f)
        val radius = (0.36f + 0.06f * apart.value) * (1f - crash)
        for (index in 0 until 4) {
            val pair = index / 2
            val scale = if (pair == 0) 1f else 0.5f
            val phase = orbit * (if (pair == 0) 1f else -0.7f) + (index % 2) * PI.toFloat() + pair * 1.5707964f
            armX[index] = stage.x + radius * scale * cos(phase)
            armY[index] = stage.y + radius * scale * shape.value * 1.1f * sin(phase)
            kit.place(index, armX[index], armY[index])
        }
        if (gestures.hat > 0f) shards.burst(armX[0], armY[0], gestures.hatSpawn(3), 0.4f, 0.5f, 0.012f, random.next(), Sprite.DIAMOND)
        shards.advance(dt, drag = 0.7f)
        comets.advance(state, gestures, random)
        kit.follow(4, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val count = 24 + 12 * spokes.value
        val walk = genes.walk
        for (index in 0 until 4) {
            val pair = index / 2
            val presence = if (pair == 0) 1f else pairs.weight(1)
            if (presence <= 0.01f) continue
            val side = index % 2
            val part = if (swapped) 2 - side * 2 else side * 2
            val reach = sceneRadius * (if (pair == 0) 0.8f else 0.46f)
            val facing = if (mirror.value == 1 && side == 1) -1f else 1f
            addArc(state, armX[index] * size.width, armY[index] * size.height, reach, spin * facing + side * PI.toFloat(), count, part, presence, walk + side * 0.5f + pair * 0.25f)
        }
        drawMesh(mesh)
        with(comets) { drawComets(state.palette, walk) }
    }

    private fun DrawScope.addArc(state: VizRenderState, x: Float, y: Float, reach: Float, start: Float, count: Int, part: Int, presence: Float, tint: Float) {
        val inner = reach * 0.25f
        val width = PI.toFloat() / count * 0.7f
        for (spoke in 0 until count) {
            val along = spoke.toFloat() / count
            val value = split.sample(part, along)
            val angle = start + PI.toFloat() * along
            val tip = inner + reach * (0.2f * state.presence + 0.8f * value)
            val colour = state.palette.argb(tint + along * 0.5f, value = 0.4f + 0.6f * value, alpha = (0.3f + 0.6f * state.lift) * presence)
            val a = mesh.vertex(x + cos(angle - width) * inner, y + sin(angle - width) * inner, colour)
            val b = mesh.vertex(x + cos(angle - width) * tip, y + sin(angle - width) * tip, colour)
            val c = mesh.vertex(x + cos(angle + width) * tip, y + sin(angle + width) * tip, colour)
            val d = mesh.vertex(x + cos(angle + width) * inner, y + sin(angle + width) * inner, colour)
            mesh.quad(a, b, c, d)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(shards) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        orbit = 0f
        spin = 0f
        direction = 1f
        swapped = false
        apart.reset()
        crash = 0f
        comets.clear()
        shards.clear()
    }
}
