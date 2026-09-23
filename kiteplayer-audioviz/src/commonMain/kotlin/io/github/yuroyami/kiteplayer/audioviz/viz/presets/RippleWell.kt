package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The waveform bent into a ring round a well that rides an orbit, wide enough to pass the corners.
 * A second well can open on the far side, and where the two sets of ripples cross they interfere.
 * Ripples run out on every supported beat, a kick sends a ring through the picture, droplets fall into
 * the wells,
 * and a drop pulls both wells to the middle.
 */
internal class RippleWell : Layered(
    name = "Ripple Well",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 902L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 902)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(2.5f)),
        VizDrive(VizDriver.Section, VizProperty.Spawn, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.86f, calmZoom = 1f, livelyZoom = 1.004f)
    override val warp: WarpSpec = WarpSpec(WarpFields.WELLS, calmAmount = 0.015f, livelyAmount = 0.05f)

    private val wells = genes.toggle("Second well", start = true)
    private val ringReach = genes.number("Ring reach", 0.26f, 0.4f, 0.34f)
    private val rippleCount = genes.number("Ripple count", 0.7f, 1.5f, 1f)
    private val spinGene = genes.number("Drift", -0.4f, 0.4f, 0.15f)

    private val orbit = Orbiter(radiusX = 0.26f, radiusY = 0.2f, lapsPerBar = 0.3f)
    private var wellX = 0.5f
    private var wellY = 0.5f
    private var otherX = 0.5f
    private var otherY = 0.5f
    private var gather = 0f
    private val rings = Shocks(10)
    private val droplets = Travellers(12)
    private val dropletMesh = TriangleMesh(maxVertices = 12 * 12 + 8)
    private val comets = Comets()
    private val path = Path()

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val well = anchors.getOrNull(1)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = spinGene.value * (0.4f + 0.6f * state.frame.mood),
            centreX = well?.x ?: 0.5f,
            centreY = well?.y ?: 0.5f,
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        orbit.advance(state, gestures)
        if (gestures.drop) gather = 1f
        gather = (gather - dt / (gestures.cycleSeconds * 2f)).coerceAtLeast(0f)
        // Pulled to the middle on a drop, and let go again over two visual cycles.
        val pull = sin(gather * PI.toFloat() * 0.5f)
        wellX = orbit.x + (0.5f - orbit.x) * pull
        wellY = orbit.y + (0.5f - orbit.y) * pull
        otherX = 1f - orbit.x + (orbit.x - 0.5f) * pull
        otherY = 1f - orbit.y + (orbit.y - 0.5f) * pull
        kit.place(1, wellX, wellY)
        kit.place(2, otherX, otherY)
        // The warp's second well, in its own units: half the screen height, measured from the first.
        val first = anchors[1]
        val second = anchors[2]
        warp.params[0] = (second.x - first.x) * 2f * kit.aspect
        warp.params[1] = (second.y - first.y) * 2f
        warp.params[2] = wells.weight(1)
        warp.params[3] = rippleCount.value
        if (gestures.kick > 0f) {
            rings.fire(wellX, wellY, random.next(), gestures.kick)
            if (wells.on) rings.fire(otherX, otherY, random.next(), gestures.kick * 0.7f)
        }
        rings.advance(dt, gestures.beatSeconds * 2.5f)
        if (gestures.hat > 0f || gestures.section) {
            val toSecond = wells.on && random.next() < 0.5f
            val x = if (toSecond) otherX else wellX
            val y = if (toSecond) otherY else wellY
            droplets.spawn(x + random.signed() * 0.3f, -0.05f, x, y, gestures.beatSeconds * 1.5f, PathShape.Arc, 0.1f * random.signed(), 0.018f, random.next(), 0f, Sprite.GLOW)
        }
        droplets.advance(dt)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        kit.follow(3, droplets)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val scope = state.frame.scope
        val lift = 0.3f + 0.7f * state.lift
        val walk = genes.walk
        val diagonal = hypot(size.width, size.height)
        val scale = state.frame.waveformGain
        traceRing(state, scope, wellX, wellY, diagonal * ringReach.value, scale, 1f, walk)
        val second = wells.weight(1)
        if (second > 0.01f) traceRing(state, scope, otherX, otherY, diagonal * ringReach.value * 0.6f, scale, second, walk + 0.5f)
        with(rings) { drawShocks(state.palette, walk, diagonal * 0.6f, size.minDimension * 0.012f, lift) }
        drawTravellers(droplets, dropletMesh, state.palette, walk, alpha = lift)
        with(comets) { drawComets(state.palette, walk, alpha = lift) }
    }

    private fun DrawScope.traceRing(state: VizRenderState, scope: FloatArray, x: Float, y: Float, radius: Float, scale: Float, presence: Float, tint: Float) {
        if (scope.size < 2) return
        val at = Offset(x * size.width, y * size.height)
        val swing = sceneRadius * 0.22f * (0.4f + state.frame.levelRel) * scale
        path.reset()
        for (point in scope.indices) {
            val along = point.toFloat() / (scope.size - 1)
            // Tapered to nothing at both ends, so where the trace closes the last point meets the first.
            val taper = sin(along * PI.toFloat())
            val p = polar(at, TAU * along, radius + scope[point].coerceIn(-1f, 1f) * swing * taper)
            if (point == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(
            path,
            state.palette.cycled(tint, value = 1f, alpha = ((0.15f + 0.45f * state.lift) * presence).coerceIn(0f, 1f)),
            style = Stroke((size.minDimension * 0.005f).coerceAtLeast(1.2f), cap = StrokeCap.Round),
            blendMode = BlendMode.Plus,
        )
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val glow = size.minDimension * 0.05f
        for (well in 0 until 2) {
            val share = if (well == 0) 1f else wells.weight(1)
            if (share <= 0.01f) continue
            val at = Offset((if (well == 0) wellX else otherX) * size.width, (if (well == 0) wellY else otherY) * size.height)
            drawCircle(
                Brush.radialGradient(0f to state.palette.cap.copy(alpha = ((0.2f + 0.4f * state.lift) * share).coerceIn(0f, 1f)), 1f to Color.Transparent, center = at, radius = glow),
                glow,
                at,
                blendMode = BlendMode.Plus,
            )
        }
    }

    override fun onReset() {
        orbit.reset()
        gather = 0f
        rings.clear()
        droplets.clear()
        comets.clear()
        warp.params.fill(0f)
    }
}
