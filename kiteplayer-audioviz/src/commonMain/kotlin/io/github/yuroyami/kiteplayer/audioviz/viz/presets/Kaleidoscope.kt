package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wedges of spectrum folded round a centre that wanders off the middle, so the echo's fold mirrors it
 * into new arrangements. Shards thrown in on the drums are folded too, a sweep goes round once a bar,
 * and a drop takes the fold to its most mirrors and spins it a full turn in a bar.
 */
internal class Kaleidoscope : Layered(
    name = "Kaleidoscope",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 504L, groundKind = GroundKind.Plasma, groundDim = 0.8f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.05f, seed = 504)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.8f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.8f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Speed, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.58f, calmSpin = 0.1f, livelySpin = 0.35f)
    override val warp: WarpSpec = WarpSpec(WarpFields.FOLD, calmAmount = 0.03f, livelyAmount = 0.08f)

    private val mirrors = genes.choice("Mirrors", 3, start = 1)
    private val offset = genes.number("Figure offset", 0f, 0.22f, 0.1f)
    private val spriteKind = genes.choice("Sprites", 3)
    private val groundFold = genes.toggle("Cells", start = false)

    private val turn = MusicClock(beatsPerCycle = 8f)
    private var spin = 0f
    private var extra = 0f
    private var fastLeft = 0f
    private val snap = Spring(stiffness = 180f, damping = 0.45f)
    private val wander = Orbiter(radiusX = 1f, radiusY = 1f, lapsPerBar = 0.35f)
    private var centreX = 0.5f
    private var centreY = 0.5f
    private var sweepAngle = 0f
    private val shards = Sprites(300, 1_504L)
    private val comets = Comets()
    private val wedge = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        ground?.kind = if (groundFold.on) GroundKind.Voronoi else GroundKind.Plasma
        spin = turn.advance(dt, state.frame, state.paced(0.2f)) * TAU
        if (gestures.drop) {
            mirrors.choose(2)
            fastLeft = gestures.cycleSeconds
        }
        if (fastLeft > 0f) {
            fastLeft -= dt
            extra += state.stepSeconds * TAU / gestures.cycleSeconds
        }
        snap.kick(gestures.kick * 6f)
        snap.advance(dt)
        wander.advance(state, gestures)
        centreX = 0.5f + offset.value * cos(wander.angle) / kit.aspect * 1.6f
        centreY = 0.5f + offset.value * sin(wander.angle)
        sweepAngle = gestures.cyclePhase * TAU
        kit.place(0, centreX + sin(sweepAngle) * 0.4f / kit.aspect, centreY - cos(sweepAngle) * 0.4f)
        kit.place(1, centreX, centreY)
        if (gestures.kick > 0f || gestures.snare > 0f) {
            val kind = when (spriteKind.value) {
                0 -> Sprite.SHARD
                1 -> Sprite.DIAMOND
                else -> Sprite.CROSS
            }
            shards.burst(centreX, centreY, maxOf(gestures.kickSpawn(28), gestures.snareSpawn(28)),
                0.5f, 0.8f, 0.02f, random.next(), kind)
        }
        shards.advance(dt, drag = 0.8f)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        val at = Offset(centreX * size.width, centreY * size.height)
        val reach = sceneRadius * (0.95f + 0.3f * snap.value.coerceIn(0f, 1.2f))
        val lift = 0.35f + 0.65f * state.lift
        for (option in 0 until 3) {
            val share = mirrors.weight(option)
            if (share > 0.01f) drawWedges(state, at, reach, MIRRORS[option], share * lift, spin + extra)
        }
        drawWedges(state, at, reach * 0.5f, MIRRORS[mirrors.value], 0.6f * lift, -(spin + extra) * 1.5f)
        with(shards) { drawSprites(state.palette, genes.walk) }
        drawLine(state.palette.cap.copy(alpha = 0.5f * lift), at, polar(at, sweepAngle + spin + extra, reach), (size.minDimension * 0.006f).coerceAtLeast(1.5f), blendMode = BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    // Every other copy is flipped, which is what makes the seams line up.
    private fun DrawScope.drawWedges(state: VizRenderState, at: Offset, reach: Float, count: Int, alpha: Float, turn: Float) {
        val bands = state.frame.bandsRel
        val step = TAU / count
        for (mirror in 0 until count) {
            val flip = if (mirror % 2 == 0) 1f else -1f
            val base = step * mirror + turn
            wedge.reset()
            wedge.moveTo(at.x, at.y)
            for (point in 0..POINTS) {
                val along = point.toFloat() / POINTS
                val p = polar(at, base + flip * step * along, reach * (0.12f + 0.88f * bands.sampleAt(along)))
                wedge.lineTo(p.x, p.y)
            }
            wedge.close()
            drawPath(wedge, state.palette.cycled(mirror.toFloat() / count + genes.walk, alpha = (0.55f * alpha).coerceIn(0f, 1f)))
            drawPath(wedge, state.palette.cap.copy(alpha = (0.45f * alpha).coerceIn(0f, 1f)), style = Stroke(2f))
        }
    }

    override fun onReset() {
        turn.reset()
        spin = 0f
        extra = 0f
        fastLeft = 0f
        snap.reset()
        wander.reset()
        shards.clear()
        comets.clear()
    }

    private companion object {
        const val POINTS = 18
        val MIRRORS = intArrayOf(6, 8, 12)
    }
}
