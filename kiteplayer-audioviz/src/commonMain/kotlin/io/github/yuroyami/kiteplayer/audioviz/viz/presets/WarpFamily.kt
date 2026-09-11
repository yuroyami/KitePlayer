package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.TraceGain
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Drawings where the warp is the picture.
 *
 * Each one draws a little: figures, a trace, drops, rain. The warp bends last frame's picture into
 * this frame's and so builds detail out of almost nothing. The seeds move and the warps' centres
 * travel, so the shape the warp grows is somewhere else every few seconds.
 */

/**
 * Three spoked figures, one for each part of the spectrum, riding orbits round a twist whose centre
 * wanders the screen. The echoes stream off to one side, the side changing every phrase. Glyphs
 * dropped in are wound into the spirals, a kick tightens the twist, a light sweeps round once a bar,
 * and a drop adds a turned copy of the echo for a bar.
 */
internal class Twist : Layered(
    name = "Twist",
    family = VizFamily.Warp,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 901L, groundKind = GroundKind.Stars, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 901)),
) {
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.86f, calmZoom = 1.002f, livelyZoom = 1.008f)
    override val warp: WarpSpec = WarpSpec(WarpFields.TWIST, calmAmount = 0.035f, livelyAmount = 0.09f, drift = 0.35f)

    private val strengthParam = VizParam("Strength", 0f, 3f, 1f)
    override val params: List<VizParam> = listOf(strengthParam)

    private val figures = genes.choice("Figures", 3, start = 2)
    private val side = genes.toggle("Stream right", start = true)
    private val twist = genes.number("Twist strength", 0.6f, 1.5f, 1f)
    private val glyphKind = genes.choice("Glyphs", 3)

    private val centre = Orbiter(radiusX = 0.24f, radiusY = 0.2f, lapsPerBar = 0.25f)
    private val orbits = Array(FIGURES) { Orbiter(radiusX = 0.3f, radiusY = 0.26f, lapsPerBar = 0.55f + 0.1f * it, phase = it / FIGURES.toFloat()) }
    private val figureX = FloatArray(FIGURES) { 0.5f }
    private val figureY = FloatArray(FIGURES) { 0.5f }
    private val turns = FloatArray(FIGURES)
    private val tighten = Spring(stiffness = 90f, damping = 0.5f)
    private val stream = Slew(maxPerSecond = 0.6f)
    private var copyHold = 0f
    private var sweepAngle = 0f
    private val glyphs = Sprites(200, 1_901L)
    private val comets = Comets()
    private val mesh = TriangleMesh(maxVertices = FIGURES * SPOKES * 3 + 16)

    // The twist turns round the wandering centre, and the echoes stream off to the current side.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val middle = anchors.getOrNull(1)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            driftX = stream.value * (0.03f + 0.05f * state.frame.mood),
            centreX = middle?.x ?: 0.5f,
            centreY = middle?.y ?: 0.5f,
            copy = if (copyHold > 0f) EchoCopy(angle = PI.toFloat(), share = 0.3f) else null,
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        centre.advance(state, gestures)
        kit.place(1, centre.x, centre.y)
        stream.advance((if (side.on) 1f else -1f) * if (gestures.phrases % 2 == 0) 1f else -1f, dt)
        tighten.kick(gestures.kickHit * 4f)
        tighten.advance(dt)
        warp.strength = strengthParam.value * twist.value
        warp.params[0] = 1.2f * tighten.value.coerceIn(-0.5f, 2f)
        if (gestures.drop) copyHold = gestures.barSeconds
        copyHold -= dt
        sweepAngle = gestures.barPhase * TAU
        for (index in 0 until FIGURES) {
            val orbit = orbits[index]
            orbit.centreX = centre.x
            orbit.centreY = centre.y
            orbit.direction = if (index % 2 == 0) 1f else -1f
            orbit.advance(state, gestures)
            figureX[index] = orbit.x
            figureY[index] = orbit.y
            turns[index] += dt * (1.2f + 0.4f * index) * state.tempo * orbit.direction
        }
        kit.place(0, figureX[0], figureY[0])
        kit.place(2, figureX[1], figureY[1])
        if (gestures.snareHit > 0f || gestures.hatHit > 0f) {
            val index = (random.next() * figures.count(1)).toInt().coerceIn(0, FIGURES - 1)
            glyphs.burst(figureX[index], figureY[index], 3, 0.25f, 0.9f, 0.022f, random.next(), GLYPHS[glyphKind.value])
        }
        glyphs.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(3, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val walk = genes.walk
        val lift = 1.2f * state.lift
        for (index in 0 until figures.drawn(1)) {
            val presence = figures.presence(index, 1)
            if (presence <= 0.01f) continue
            addFigure(state, figureX[index] * size.width, figureY[index] * size.height, index, presence * lift, walk + index * 0.33f)
        }
        drawMesh(mesh, BlendMode.Plus)
        val at = Offset(centre.x * size.width, centre.y * size.height)
        drawLine(
            state.palette.cap.copy(alpha = (0.1f + 0.7f * state.lift).coerceIn(0f, 1f)),
            at,
            polar(at, sweepAngle, sceneRadius * 1.4f),
            (size.minDimension * 0.006f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        with(glyphs) { drawSprites(state.palette, walk, alpha = lift) }
        with(comets) { drawComets(state.palette, walk, alpha = lift) }
    }

    // A fan of thin wedges, one per slice of this figure's part of the spectrum.
    private fun DrawScope.addFigure(state: VizRenderState, x: Float, y: Float, part: Int, alpha: Float, tint: Float) {
        val inner = size.minDimension * 0.03f
        val reach = sceneRadius * 0.45f
        val half = TAU / SPOKES * 0.3f
        for (spoke in 0 until SPOKES) {
            val along = spoke.toFloat() / SPOKES
            val value = split.folded(part, along)
            val angle = turns[part] + TAU * along
            val tip = inner + reach * (0.2f + 0.8f * value)
            val colour = state.palette.argb(tint + along * 0.4f, value = 0.5f + 0.5f * value, alpha = (0.2f + 0.6f * value) * alpha)
            val a = mesh.vertex(x + cos(angle - half) * inner, y + sin(angle - half) * inner, colour)
            val b = mesh.vertex(x + cos(angle) * tip, y + sin(angle) * tip, colour)
            val c = mesh.vertex(x + cos(angle + half) * inner, y + sin(angle + half) * inner, colour)
            mesh.triangle(a, b, c)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        for (index in 0 until figures.drawn(1)) {
            val presence = figures.presence(index, 1)
            if (presence <= 0.01f) continue
            drawCircle(
                state.palette.cap.copy(alpha = ((0.3f + 0.5f * state.lift) * presence).coerceIn(0f, 1f)),
                size.minDimension * 0.012f,
                Offset(figureX[index] * size.width, figureY[index] * size.height),
            )
        }
    }

    override fun onReset() {
        centre.reset()
        orbits.forEach { it.reset() }
        turns.fill(0f)
        tighten.reset()
        stream.reset()
        copyHold = 0f
        glyphs.clear()
        comets.clear()
        warp.params.fill(0f)
    }

    private companion object {
        const val FIGURES = 3
        const val SPOKES = 36
        val GLYPHS = intArrayOf(Sprite.CROSS, Sprite.DIAMOND, Sprite.CHEVRON)
    }
}

/**
 * The waveform bent into a ring round a well that rides an orbit, wide enough to pass the corners.
 * A second well can open on the far side, and where the two sets of ripples cross they interfere.
 * Ripples run out one a beat, a kick sends a ring through the picture, droplets fall into the wells,
 * and a drop pulls both wells to the middle.
 */
internal class RippleWell : Layered(
    name = "Ripple Well",
    family = VizFamily.Warp,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 902L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.06f, seed = 902)),
) {
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.86f, calmZoom = 1f, livelyZoom = 1.004f)
    override val warp: WarpSpec = WarpSpec(WarpFields.WELLS, calmAmount = 0.015f, livelyAmount = 0.05f)

    private val wells = genes.toggle("Second well", start = true)
    private val ringReach = genes.number("Ring reach", 0.26f, 0.4f, 0.34f)
    private val rippleCount = genes.number("Ripple count", 0.7f, 1.5f, 1f)
    private val spinGene = genes.number("Drift", -0.4f, 0.4f, 0.15f)

    private val gain = TraceGain()
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
        gather = (gather - dt / (gestures.barSeconds * 2f)).coerceAtLeast(0f)
        // Pulled to the middle on a drop, and let go again over two bars.
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
        if (gestures.kickHit > 0f) {
            rings.fire(wellX, wellY, random.next(), gestures.kickHit)
            if (wells.on) rings.fire(otherX, otherY, random.next(), gestures.kickHit * 0.7f)
        }
        rings.advance(dt, gestures.beatSeconds * 2.5f)
        if (gestures.hatHit > 0f || gestures.bar) {
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
        val scale = gain.update(scope, scope, state.deltaSeconds)
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
        gain.reset()
        orbit.reset()
        gather = 0f
        rings.clear()
        droplets.clear()
        comets.clear()
        warp.params.fill(0f)
    }
}

/**
 * Drops of colour let fall into moving water.
 *
 * A dropper crosses the tank every two bars, letting drops fall as it goes; more fall all over the
 * tank, each spreading a ring, and onsets drop more in from the edges. A drop is drawn once, on the
 * frame it lands; after that the warp drags it through a current, and the whole tank turns with it,
 * both changing way every phrase, while the feedback slowly lets it fade. The left of the tank
 * takes one colour and the right another, and a drop in the music spills ink from every edge.
 */
internal class Ink : Layered(
    name = "Ink",
    family = VizFamily.Warp,
    bucket = VizEnergy.Calm,
    kit = Kit(
        seed = 903L,
        groundKind = GroundKind.Water,
        groundDim = 0.6f,
        detailKind = DetailKind.Hatch,
        detailStrength = 0.6f,
        camera = Camera2D(wander = 0.04f, cuts = false, seed = 903),
    ),
) {
    override val bloom: Int get() = 2
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.99f, livelyTrail = 0.98f, calmSpin = 0.3f, livelySpin = 0.6f)
    // Enough to carry a drop across the tank in a few seconds, still short of tearing it apart.
    override val warp: WarpSpec = WarpSpec(WarpFields.FLOW, calmAmount = 0.14f, livelyAmount = 0.3f)

    private val route = genes.choice("Dropper path", 3)
    private val currentGene = genes.number("Current", 0.3f, 1.2f, 0.7f)
    private val flowAmount = genes.number("Flow amount", 0.7f, 1.4f, 1f)
    private val colourRule = genes.toggle("Colour by side", start = true)

    private var crossing = 0f
    private var way = 1f
    private var dropperX = 0.06f
    private var dropperY = 0.16f
    private val current = Slew(maxPerSecond = 0.5f)
    private var dropCredit = 0f
    private var rainCredit = 0f
    private var spillLeft = 0f
    private val dropX = FloatArray(QUEUE)
    private val dropY = FloatArray(QUEUE)
    private val dropSize = FloatArray(QUEUE)
    private val dropTint = FloatArray(QUEUE)
    private var queued = 0
    private val splashes = Sprites(160, 1_903L)
    private val ripples = Shocks(32)
    private val comets = Comets(size = 0.02f)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        // The whole tank turns with the current, one way and then the other.
        return EchoFrame(zoomX = base.zoomX, spin = base.spin * current.value, driftX = current.value * currentGene.value * (0.05f + 0.08f * state.frame.mood))
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        queued = 0
        warp.strength = flowAmount.value
        if (gestures.phrase) way = -way
        current.advance(way, dt)
        // Back and forth across the tank, one crossing every two bars.
        crossing += dt / (gestures.barSeconds * 2f)
        val leg = crossing % 2f
        val along = if (leg < 1f) leg else 2f - leg
        dropperX = 0.06f + 0.88f * along
        var y = 0f
        for (option in 0 until 3) {
            val share = route.weight(option)
            if (share <= 0f) continue
            y += share * when (option) {
                0 -> 0.16f
                1 -> 0.22f + 0.1f * sin(along * TAU * 2f)
                else -> 0.15f + 0.7f * along
            }
        }
        dropperY = y
        kit.place(0, dropperX, dropperY)
        dropCredit += dt * (16f + 24f * state.drive)
        while (dropCredit >= 1f) {
            dropCredit -= 1f
            drop(dropperX, dropperY + 0.02f * random.signed(), 0.06f + 0.08f * random.next())
        }
        // Drops fall all over the tank as well, so the whole of it has ink for the current to carry.
        rainCredit += dt * (20f + 40f * state.drive)
        while (rainCredit >= 1f) {
            rainCredit -= 1f
            val rainX = 0.05f + 0.9f * random.next()
            val rainY = 0.05f + 0.9f * random.next()
            drop(rainX, rainY, 0.025f + 0.04f * random.next())
            ripples.fire(rainX, rainY, tint(rainX))
        }
        // Onsets drop ink in from the edges.
        val onset = state.frame.onsetStrength
        if (onset > 0f) {
            val edge = (random.next() * 4f).toInt()
            val t = 0.1f + 0.8f * random.next()
            val x = when (edge) {
                0 -> 0.04f
                1 -> 0.96f
                else -> t
            }
            val ey = when (edge) {
                2 -> 0.04f
                3 -> 0.96f
                else -> t
            }
            drop(x, ey, 0.04f + 0.06f * onset)
        }
        if (gestures.drop) spillLeft = gestures.barSeconds
        if (spillLeft > 0f) {
            spillLeft -= dt
            // A spill: ink pours in from all four edges for a bar.
            for (edge in 0 until 4) {
                val t = random.next()
                val x = when (edge) {
                    0 -> 0.02f
                    1 -> 0.98f
                    else -> t
                }
                val ey = when (edge) {
                    2 -> 0.02f
                    3 -> 0.98f
                    else -> t
                }
                drop(x, ey, 0.03f + 0.03f * random.next())
            }
        }
        if (gestures.kickHit > 0f) splashes.burst(dropperX, dropperY, 8, 0.2f, 0.7f, 0.01f, tint(dropperX), Sprite.GLOW)
        splashes.advance(dt, drag = 1.5f)
        ripples.advance(dt, 0.9f)
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
    }

    private fun drop(x: Float, y: Float, radius: Float) {
        if (queued >= QUEUE) return
        dropX[queued] = x
        dropY[queued] = y
        dropSize[queued] = radius
        dropTint[queued] = tint(x)
        queued++
    }

    /** One colour for the left of the tank and another for the right, or any colour at all. */
    private fun tint(x: Float): Float {
        val bySide = colourRule.weight(1)
        val side = if (x < 0.5f) 0.08f else 0.58f
        return side * bySide + random.next() * (1f - bySide) + 0.1f * random.next()
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.35f + 0.65f * state.lift
        for (index in 0 until queued) {
            val at = Offset(dropX[index] * size.width, dropY[index] * size.height)
            val radius = (size.minDimension * dropSize[index] * (0.7f + 0.6f * state.energy)).coerceAtLeast(1f)
            val colour = state.palette.cycled(dropTint[index] + genes.walk)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colour.copy(alpha = lift),
                    0.6f to colour.copy(alpha = 0.35f * lift),
                    1f to Color.Transparent,
                    center = at,
                    radius = radius,
                ),
                radius = radius,
                center = at,
                blendMode = BlendMode.Plus,
            )
            // A rim of pigment round each drop, which the current pulls out into threads.
            drawCircle(colour.copy(alpha = (0.9f * lift).coerceIn(0f, 1f)), radius * 0.8f, at, style = Stroke((size.minDimension * 0.008f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
        }
        with(splashes) { drawSprites(state.palette, genes.walk, alpha = lift) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.6f * lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        drawCircle(
            state.palette.cap.copy(alpha = (0.5f + 0.4f * state.lift).coerceIn(0f, 1f)),
            size.minDimension * 0.012f,
            Offset(dropperX * size.width, dropperY * size.height),
        )
        // Rings spreading on the water where the scattered drops land.
        with(ripples) { drawShocks(state.palette, genes.walk, size.minDimension * 0.12f, size.minDimension * 0.008f, 0.3f + 0.6f * state.lift) }
    }

    override fun onReset() {
        crossing = 0f
        way = 1f
        current.reset()
        dropCredit = 0f
        rainCredit = 0f
        spillLeft = 0f
        queued = 0
        splashes.clear()
        ripples.clear()
        comets.clear()
    }

    private companion object {
        const val QUEUE = 64
    }
}

/**
 * Rain on up to three depths, near drops longer and faster, blown sideways by a wind that changes
 * side every phrase, falling behind lenses that drift down the glass and bend everything behind them.
 * Drops that reach the bottom leave rings in a puddle, a snare throws lightning across the screen,
 * and a drop brings a downpour for a bar.
 */
internal class LensRain : Layered(
    name = "Lens Rain",
    family = VizFamily.Warp,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 904L, groundKind = GroundKind.Rain, groundDim = 0.8f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.04f, cuts = false, seed = 904)),
) {
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.88f, livelyTrail = 0.82f)
    override val warp: WarpSpec = WarpSpec(WarpFields.FALLING_LENSES, calmAmount = 0.03f, livelyAmount = 0.07f)

    private val windGene = genes.number("Wind", -1f, 1f, 0.35f)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val lenses = genes.choice("Lenses", 4, start = 2)
    private val lightningOn = genes.toggle("Lightning", start = true)

    private val dropX = FloatArray(DROPS)
    private val dropY = FloatArray(DROPS)
    private val dropSpeed = FloatArray(DROPS)
    private val dropDepth = FloatArray(DROPS)
    private val dropAlive = BooleanArray(DROPS)
    private var nextDrop = 0
    private var credit = 0f
    private var downpour = 0f
    private val wind = Slew(maxPerSecond = 0.6f)
    private var fallClock = 0f
    private val lensX = FloatArray(4)
    private val lensY = FloatArray(4)
    private val puddleX = FloatArray(PUDDLES)
    private val puddleAge = FloatArray(PUDDLES)
    private var nextPuddle = 0
    private var bolt = 0f
    private var boltX = 0.5f
    private var boltSeed = 0f
    private val boltWander = Noise1(seed = 9_904)
    private val boltPath = Path()
    private val comets = Comets(size = 0.02f)
    private val mesh = TriangleMesh(maxVertices = DROPS * 4 + 16)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, driftX = wind.value * 0.05f, driftY = 0.02f)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        wind.advance(windGene.value * if (gestures.phrases % 2 == 0) 1f else -1f, dt)
        if (gestures.drop) downpour = gestures.barSeconds
        downpour -= dt
        // The lenses fall on their own clock, the same one the warp's field reads.
        fallClock += dt * (0.18f + 0.2f * state.drive)
        for (index in 0 until 4) {
            val along = index / 4f
            val fall = wrap(fallClock * (1.1f - along * 0.5f) + along * 0.37f)
            lensX[index] = 0.5f + 0.4f * sin(index * 2.4f + fallClock * 0.7f)
            lensY[index] = fall * 1.3f - 0.15f
        }
        warp.params[0] = lenses.count(1).toFloat()
        warp.params[1] = fallClock
        warp.params[2] = 1f
        kit.place(0, lensX[0], lensY[0])
        val layers = depths.count(1)
        credit += dt * (80f + 520f * state.drive) * (if (downpour > 0f) 4f else 1f) + gestures.hatHit * 25f
        while (credit >= 1f) {
            credit -= 1f
            spawnDrop(layers)
        }
        for (slot in 0 until DROPS) {
            if (!dropAlive[slot]) continue
            dropY[slot] += dropSpeed[slot] * dt
            dropX[slot] += wind.value * 0.2f * dropDepth[slot] * dt
            if (dropY[slot] > FLOOR) {
                dropAlive[slot] = false
                if (dropDepth[slot] > 0.9f && random.next() < 0.3f) puddle(dropX[slot])
            }
        }
        for (slot in 0 until PUDDLES) {
            if (puddleAge[slot] <= 0f) continue
            puddleAge[slot] += dt / 0.9f
            if (puddleAge[slot] >= 1f) puddleAge[slot] = 0f
        }
        if (gestures.snareHit > 0f && lightningOn.on) {
            bolt = 1f
            boltX = 0.15f + 0.7f * random.next()
            boltSeed = random.next() * 50f
        }
        if (bolt > 0f) {
            bolt -= dt / 0.3f
            boltX += dt * 0.6f
        }
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
    }

    private fun spawnDrop(layers: Int) {
        val slot = nextDrop
        nextDrop = (nextDrop + 1) % DROPS
        val depth = DEPTH[(random.next() * layers).toInt().coerceIn(0, layers - 1)]
        dropX[slot] = -0.1f + 1.2f * random.next()
        dropY[slot] = -0.05f - 0.1f * random.next()
        dropDepth[slot] = depth
        dropSpeed[slot] = (0.7f + 0.5f * random.next()) * depth * 1.4f
        dropAlive[slot] = true
    }

    private fun puddle(x: Float) {
        puddleX[nextPuddle] = x
        puddleAge[nextPuddle] = 0.001f
        nextPuddle = (nextPuddle + 1) % PUDDLES
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val lift = 0.3f + 0.7f * state.lift
        val walk = genes.walk
        for (slot in 0 until DROPS) {
            if (!dropAlive[slot]) continue
            val depth = dropDepth[slot]
            val x = dropX[slot] * size.width
            val y = dropY[slot] * size.height
            val length = size.height * dropSpeed[slot] * 0.05f
            val colour = state.palette.argb(0.55f + 0.2f * depth + walk, saturation = 0.35f, value = 1f, alpha = (0.2f + 0.5f * depth) * lift)
            mesh.streak(x - wind.value * length * 0.2f, y - length, x, y, (size.minDimension * 0.004f * depth).coerceAtLeast(1f), colour)
        }
        drawMesh(mesh, BlendMode.Plus)
        // The lenses, faintly, so the eye can find what is bending the rain.
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (index in 0 until lenses.drawn(1)) {
            val presence = lenses.presence(index, 1)
            if (presence <= 0.01f) continue
            drawCircle(
                state.palette.cap.copy(alpha = (0.12f * lift * presence).coerceIn(0f, 1f)),
                size.minDimension * 0.16f,
                Offset(lensX[index] * size.width, lensY[index] * size.height),
                style = Stroke(thin),
            )
        }
        if (bolt > 0f) {
            boltPath.reset()
            var x = boltX * size.width
            boltPath.moveTo(x, 0f)
            for (step in 1..BOLT_STEPS) {
                x += boltWander.at(boltSeed + step * 1.7f) * size.width * 0.05f
                boltPath.lineTo(x, size.height * 0.9f * step / BOLT_STEPS)
            }
            drawPath(
                boltPath,
                state.palette.cap.copy(alpha = (bolt * lift).coerceIn(0f, 1f)),
                style = Stroke((size.minDimension * 0.006f).coerceAtLeast(1.5f), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
        with(comets) { drawComets(state.palette, walk, alpha = 0.6f * lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val lift = 0.3f + 0.7f * state.lift
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (slot in 0 until PUDDLES) {
            val age = puddleAge[slot]
            if (age <= 0f) continue
            val wide = size.width * 0.05f * age
            drawOval(
                state.palette.cap.copy(alpha = (0.5f * (1f - age) * lift).coerceIn(0f, 1f)),
                topLeft = Offset(puddleX[slot] * size.width - wide, FLOOR * size.height - wide * 0.15f),
                size = Size(wide * 2f, wide * 0.3f),
                style = Stroke(thin),
            )
        }
        if (bolt > 0f) drawRect(state.palette.cap, alpha = (0.12f * bolt).coerceIn(0f, 1f), blendMode = BlendMode.Plus)
    }

    override fun onReset() {
        dropAlive.fill(false)
        nextDrop = 0
        credit = 0f
        downpour = 0f
        wind.reset()
        fallClock = 0f
        puddleAge.fill(0f)
        nextPuddle = 0
        bolt = 0f
        comets.clear()
        warp.params.fill(0f)
    }

    private companion object {
        const val DROPS = 900
        const val PUDDLES = 24
        const val BOLT_STEPS = 12
        const val FLOOR = 0.96f
        val DEPTH = floatArrayOf(1f, 0.65f, 0.4f)
    }
}

/**
 * Whatever is drawn, folded into the shape of a Julia set whose constant walks the edge of the
 * Mandelbrot set, so the shape keeps changing. Three rings of dots ride orbits and feed it, sprites
 * thrown in are folded too, a smaller copy of the echo fills the corners with the set at another zoom,
 * the centre wanders, and a drop jumps the constant to another bulb.
 */
internal class FractalZoom : Layered(
    name = "Fractal Zoom",
    family = VizFamily.Warp,
    bucket = VizEnergy.High,
    kit = Kit(seed = 905L, groundKind = GroundKind.Cloud, groundDim = 0.8f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.05f, seed = 905)),
) {
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.85f, calmZoom = 1.004f, livelyZoom = 1.03f)
    // The whole rule every frame. Anything less is a different map with none of the set's shape.
    override val warp: WarpSpec = WarpSpec(WarpFields.JULIA_WALK, calmAmount = 1f, livelyAmount = 1f, drift = 0.5f)

    private val lobe = genes.choice("Lobe", 3)
    private val rings = genes.choice("Rings", 3, start = 2)
    private val copyScale = genes.number("Copy scale", 0.35f, 0.65f, 0.5f)
    private val spriteKind = genes.choice("Sprites", 2)

    private val orbits = Array(RINGS) { Orbiter(radiusX = 0.36f, radiusY = 0.3f, lapsPerBar = 1f - 0.2f * it, phase = it / RINGS.toFloat()) }
    private val centre = Orbiter(radiusX = 0.2f, radiusY = 0.16f, lapsPerBar = 0.15f)
    private val throwOut = Spring(stiffness = 180f, damping = 0.45f)
    private var walkAngle = 0f
    private val constantX = Slew(maxPerSecond = 0.6f)
    private val constantY = Slew(maxPerSecond = 0.6f)
    private val thrown = Sprites(200, 1_905L)
    private val comets = Comets()
    private val mesh = TriangleMesh(maxVertices = RINGS * DOTS * 7 + 16)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val middle = anchors.getOrNull(RINGS)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            centreX = middle?.x ?: 0.5f,
            centreY = middle?.y ?: 0.5f,
            copy = EchoCopy(zoom = copyScale.value, share = 0.22f),
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        centre.advance(state, gestures)
        kit.place(RINGS, centre.x, centre.y)
        throwOut.kick(gestures.kickHit * 6f)
        throwOut.advance(dt)
        // The constant walks round the edge of the chosen bulb; a change of bulb glides over a bar.
        walkAngle += dt * (0.2f + 0.6f * state.drive)
        var cx = 0f
        var cy = 0f
        for (option in 0 until 3) {
            val share = lobe.weight(option)
            if (share <= 0f) continue
            cx += share * lobeX(option, walkAngle)
            cy += share * lobeY(option, walkAngle)
        }
        constantX.advance(cx, dt)
        constantY.advance(cy, dt)
        warp.params[0] = constantX.value
        warp.params[1] = constantY.value
        for (index in 0 until RINGS) {
            val orbit = orbits[index]
            orbit.centreX = centre.x
            orbit.centreY = centre.y
            orbit.direction = if (index % 2 == 0) 1f else -1f
            orbit.advance(state, gestures)
            kit.place(index, orbit.x, orbit.y)
        }
        if (gestures.snareHit > 0f || gestures.hatHit > 0f) {
            val index = (random.next() * rings.count(1)).toInt().coerceIn(0, RINGS - 1)
            thrown.burst(orbits[index].x, orbits[index].y, 8, 0.3f, 0.8f, 0.018f, random.next(), if (spriteKind.value == 0) Sprite.CROSS else Sprite.SHARD)
        }
        thrown.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(RINGS + 1, comets.travellers)
    }

    // Points just inside the edge of the main body, the bulb to its left, and the bulb above it.
    private fun lobeX(option: Int, angle: Float): Float = when (option) {
        0 -> 0.99f * (0.5f * cos(angle) - 0.25f * cos(2f * angle))
        1 -> -1f + 0.24f * cos(angle)
        else -> -0.1226f + 0.09f * cos(angle)
    }

    private fun lobeY(option: Int, angle: Float): Float = when (option) {
        0 -> 0.99f * (0.5f * sin(angle) - 0.25f * sin(2f * angle))
        1 -> 0.24f * sin(angle)
        else -> 0.7449f + 0.09f * sin(angle)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        mesh.clear()
        val bands = state.frame.bandsRel
        val lift = 0.3f + 0.7f * state.lift
        val walk = genes.walk + state.frame.keyHue * state.frame.keyConfidence
        val reach = size.minDimension * (0.32f + 0.06f * throwOut.value.coerceIn(0f, 1.5f))
        for (index in 0 until rings.drawn(1)) {
            val presence = rings.presence(index, 1)
            if (presence <= 0.01f) continue
            val x = orbits[index].x * size.width
            val y = orbits[index].y * size.height
            val turn = orbits[index].angle * 2f
            // A hard outline too, so the fold has sharp lines to fold.
            drawCircle(state.palette.cycled(walk + index * 0.3f, value = 1f, alpha = (0.5f * lift * presence).coerceIn(0f, 1f)), reach * 1.15f, Offset(x, y), style = Stroke((size.minDimension * 0.012f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
            drawCircle(state.palette.cycled(walk + index * 0.3f + 0.5f, value = 1f, alpha = (0.4f * lift * presence).coerceIn(0f, 1f)), reach * 0.7f, Offset(x, y), style = Stroke((size.minDimension * 0.009f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
            drawCircle(state.palette.cycled(walk + index * 0.3f + 0.25f, value = 1f, alpha = (0.35f * lift * presence).coerceIn(0f, 1f)), reach * 1.45f, Offset(x, y), style = Stroke((size.minDimension * 0.006f).coerceAtLeast(1f)), blendMode = BlendMode.Plus)
            for (dot in 0 until DOTS) {
                val along = dot.toFloat() / DOTS
                val energy = bands.foldedAt(along)
                val angle = TAU * along + turn
                val radius = reach * (1f + 0.35f * energy)
                val colour = state.palette.argb(walk + along * 0.5f + index * 0.3f, value = 1f, alpha = (0.35f + 0.65f * energy) * lift * presence)
                val dotRadius = (size.minDimension * 0.034f * (0.5f + energy)).coerceAtLeast(1f)
                mesh.polygon(x + cos(angle) * radius, y + sin(angle) * radius, dotRadius, 5, angle, colour)
            }
        }
        drawMesh(mesh, BlendMode.Plus)
        with(thrown) { drawSprites(state.palette, walk, alpha = lift) }
        with(comets) { drawComets(state.palette, walk, alpha = lift) }
    }

    override fun onReset() {
        orbits.forEach { it.reset() }
        centre.reset()
        throwOut.reset()
        walkAngle = 0f
        constantX.reset()
        constantY.reset()
        thrown.clear()
        comets.clear()
        warp.params.fill(0f)
    }

    private companion object {
        const val RINGS = 3
        const val DOTS = 140
    }
}

/**
 * Specks carried along streams that run a screen every two bars, over the spectrogram that shapes
 * them. The landscape the streams follow scrolls and turns a step every phrase, big glows ride the
 * currents, a lamp drifts over it all on a slow circle, a kick sends a pulse down every stream, and a
 * drop doubles the flow for a bar.
 */
internal class FlowField : Layered(
    name = "Flow Field",
    family = VizFamily.Warp,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 906L, groundKind = GroundKind.Spectrogram, groundDim = 0.55f, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.05f, seed = 906)),
) {
    override val bloom: Int get() = 1
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.985f, livelyTrail = 0.97f)
    // Strong enough that the streams visibly run rather than creep.
    override val warp: WarpSpec = WarpSpec(WarpFields.SPECTRO, calmAmount = 0.09f, livelyAmount = 0.24f)

    private val amount = genes.number("Flow amount", 0.7f, 1.5f, 1f)
    private val scrollBack = genes.toggle("Scroll backwards", start = false)
    private val glowCount = genes.choice("Glows", 3, start = 1)
    private val speckRate = genes.number("Speck rate", 0.6f, 1.6f, 1f)

    private var owed = 0f
    private var scroll = 0f
    private var turnGoal = 0f
    private val turn = Slew(maxPerSecond = 0.8f)
    private val pulse = Spring(stiffness = 80f, damping = 0.5f)
    private var doubleHold = 0f
    private val glowX = FloatArray(GLOWS) { wrap(it * 0.618f) }
    private val glowY = FloatArray(GLOWS) { wrap(0.2f + it * 0.382f) }
    private val comets = Comets(size = 0.02f)
    private val stage = Stage(reachX = 0.3f, reachY = 0.25f, start = 0f)
    private val lampMesh = TriangleMesh(maxVertices = 32)
    private val speckMesh = TriangleMesh(maxVertices = SPECKS * 7 + 16)
    private val glowMesh = TriangleMesh(maxVertices = GLOWS * 8 + 8)

    // The whole field slides sideways and rises, so the streams also move across their own direction.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(
            zoomX = base.zoomX,
            spin = base.spin,
            driftX = (0.01f + 0.06f * state.frame.mood) * if (scrollBack.on) -1f else 1f,
            driftY = -(0.02f + 0.12f * state.frame.mood),
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (gestures.drop) doubleHold = gestures.barSeconds
        doubleHold -= dt
        pulse.kick(gestures.kickHit * 3f)
        pulse.advance(dt)
        warp.strength = amount.value * (if (doubleHold > 0f) 2f else 1f) * (1f + 0.5f * pulse.value.coerceIn(-0.5f, 1.5f))
        scroll += dt * (0.1f + 0.2f * state.drive) * if (scrollBack.on) -1f else 1f
        if (gestures.phrase) turnGoal += 0.5f
        turn.advance(turnGoal, dt)
        warp.params[0] = turn.value
        warp.params[1] = scroll
        owed = (owed + dt * (1200f + 3000f * state.drive) * speckRate.value).coerceAtMost(SPECKS.toFloat())
        // The glows follow a flow of their own shaped like the warp's, and the warp draws out their tails.
        val speed = (0.08f + 0.2f * state.drive) * amount.value
        for (index in 0 until GLOWS) {
            val heading = sin(glowX[index] * 4.1f + scroll * 3f + index) * 2f + cos(glowY[index] * 3.7f - scroll * 2f) * 2f
            glowX[index] = wrap(glowX[index] + cos(heading) * speed * dt)
            glowY[index] = wrap(glowY[index] + sin(heading) * speed * dt)
        }
        stage.advance(dt)
        kit.place(1, glowX[0], glowY[0])
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val lift = 0.35f + 0.65f * state.lift
        val walk = genes.walk
        val bands = state.frame.bandsRel
        // Many small specks rather than a few big ones: each only has to leave a thin line.
        speckMesh.clear()
        val radius = (size.minDimension * 0.016f).coerceAtLeast(1f)
        while (owed >= 1f) {
            owed -= 1f
            val across = random.next()
            val energy = bands.sampleAt(across)
            val colour = state.palette.argb(across + walk, value = 1f, alpha = (0.2f + 0.8f * energy) * lift)
            speckMesh.polygon(size.width * across, size.height * (1f - random.next() * random.next()), radius * (0.6f + energy), 4, random.next() * TAU, colour)
        }
        drawMesh(speckMesh, BlendMode.Plus)
        glowMesh.clear()
        for (index in 0 until glowCount.drawn(4, 3)) {
            val presence = glowCount.presence(index, 4, 3)
            if (presence <= 0.01f) continue
            val colour = state.palette.argb(index * 0.17f + walk, saturation = 0.5f, value = 1f, alpha = 0.7f * lift * presence)
            glowMesh.glow(glowX[index] * size.width, glowY[index] * size.height, size.minDimension * 0.08f, colour)
        }
        drawMesh(glowMesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, walk, alpha = lift) }
    }

    // A lamp drifting over the landscape on a slow circle, so the bright part of the picture moves.
    override fun DrawScope.drawTop(state: VizRenderState) {
        lampMesh.clear()
        val colour = state.palette.argb(0.15f + genes.walk, saturation = 0.5f, value = 0.9f, alpha = 0.35f * (0.35f + 0.65f * state.lift))
        lampMesh.glow(stage.x * size.width, stage.y * size.height, size.minDimension * 0.45f, colour, sides = 24)
        drawMesh(lampMesh, BlendMode.Plus)
    }

    override fun onReset() {
        owed = 0f
        stage.reset()
        scroll = 0f
        turnGoal = 0f
        turn.reset()
        pulse.reset()
        doubleHold = 0f
        for (index in 0 until GLOWS) {
            glowX[index] = wrap(index * 0.618f)
            glowY[index] = wrap(0.2f + index * 0.382f)
        }
        comets.clear()
        warp.params.fill(0f)
    }

    private companion object {
        const val GLOWS = 10
        const val SPECKS = 300
    }
}

/**
 * An analogue oscilloscope in X and Y mode, three figures at once: left against right, middle against
 * side, and the first one a moment late, in three colours. The screen drifts, so each figure leaves a
 * waterfall of afterimages; the figures wander across the tube, glints mark where the beam slows, a
 * drop pulls the three apart and back, and with nothing playing the beam traces a slow Lissajous.
 *
 * On a real tube the beam lights the glass more where it moves slowly, because it stays there longer.
 * That is copied: each short piece of the trace is drawn brighter the shorter it is.
 */
internal class Phosphor : Layered(
    name = "Phosphor",
    family = VizFamily.Warp,
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 907L,
        groundKind = GroundKind.Hatch,
        groundDim = 0.6f,
        detailKind = DetailKind.Scan,
        detailStrength = 0.6f,
        camera = Camera2D(wander = 0.05f, seed = 907),
    ),
) {
    // The lines of an old tube, which is what these traces grew up on.
    override val post: PostSpec get() = PostSpec.Retro
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.92f, livelyTrail = 0.88f)

    private val figures = genes.choice("Figures", 3, start = 2)
    private val driftWay = genes.choice("Drift", 3, start = 1)
    private val grid = genes.toggle("Fine grid", start = false)
    private val beamWidth = genes.number("Beam width", 0.7f, 1.6f, 1f)

    private val gain = TraceGain()
    private val levels = Array(LEVELS) { Path() }
    private val flash = Envelope(attackPerSecond = 60f, releasePerSecond = 6f)
    private val wander = Orbiter(radiusX = 0.26f, radiusY = 0.18f, lapsPerBar = 0.2f)
    private var apartHold = 0f
    private val apart = Envelope(attackPerSecond = 3f, releasePerSecond = 1f)
    private val quiet = Envelope(attackPerSecond = 1f, releasePerSecond = 2f)
    private var phase = 0f
    private var scale = 1f
    private val left = FloatArray(POINTS)
    private val right = FloatArray(POINTS)
    private val pastLeft = History(rows = 40)
    private val pastRight = History(rows = 40)
    private val figureX = Array(3) { FloatArray(POINTS) }
    private val figureY = Array(3) { FloatArray(POINTS) }
    private var glintCredit = 0f
    private val glints = Sprites(120, 1_907L)
    private val comets = Comets(size = 0.02f)

    // Up, down, or still: the afterimages stream away as a waterfall.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val way = driftWay.weight(2) - driftWay.weight(1)
        return EchoFrame(zoomX = base.zoomX, driftY = way * (0.3f + 0.3f * state.frame.mood))
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        ground?.kind = if (grid.on) GroundKind.Grid else GroundKind.Hatch
        wander.advance(state, gestures)
        kit.place(1, wander.x, wander.y, parallax = 0.8f)
        flash.hit(gestures.kickHit)
        flash.advance(0f, dt)
        if (gestures.drop) apartHold = gestures.barSeconds
        apartHold -= dt
        apart.advance(if (apartHold > 0f) 1f else 0f, dt)
        quiet.advance(if (gestures.silence) 1f else 0f, dt)
        phase += dt * 0.4f
        frame.scopeLeft.squeezeInto(left)
        frame.scopeRight.squeezeInto(right)
        pastLeft.push(left, state.timeSeconds)
        pastRight.push(right, state.timeSeconds)
        scale = gain.update(frame.scopeLeft, frame.scopeRight, dt)
        val lateLeft = pastLeft.row(0.3f)
        val lateRight = pastRight.row(0.3f)
        val still = quiet.value
        for (index in 0 until POINTS) {
            val along = index.toFloat() / (POINTS - 1)
            val theta = TAU * along
            val l = left[index] * scale
            val r = right[index] * scale
            figureX[0][index] = l + (sin(3f * theta + phase) - l) * still
            figureY[0][index] = r + (sin(2f * theta) - r) * still
            figureX[1][index] = (l + r) * 0.7f * (1f - still) + sin(2f * theta + phase) * still
            figureY[1][index] = (l - r) * 1.4f * (1f - still) + sin(5f * theta) * 0.8f * still
            val dl = pastLeft.sample(lateLeft, along) * scale
            val dr = pastRight.sample(lateRight, along) * scale
            figureX[2][index] = dl + (sin(3f * theta + phase - 0.6f) - dl) * still
            figureY[2][index] = dr + (sin(2f * theta - 0.6f) - dr) * still
        }
        // Glints where the first figure's beam is slowest, which is where the glass glows brightest.
        var slowest = 1
        var shortest = Float.MAX_VALUE
        for (index in 1 until POINTS) {
            val dx = figureX[0][index] - figureX[0][index - 1]
            val dy = figureY[0][index] - figureY[0][index - 1]
            val length = dx * dx + dy * dy
            if (length < shortest) {
                shortest = length
                slowest = index
            }
        }
        val glintX = originX(0) + figureX[0][slowest] * REACH[0] / kit.aspect
        val glintY = originY(0) - figureY[0][slowest] * REACH[0]
        kit.place(2, glintX, glintY)
        glintCredit += dt * (4f + 16f * state.drive)
        while (glintCredit >= 1f) {
            glintCredit -= 1f
            glints.burst(glintX, glintY, 1, 0.06f, 0.5f, 0.012f, random.next(), Sprite.SPARK)
        }
        glints.advance(dt, drag = 1.5f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    private fun originX(figure: Int): Float = wander.x + OFFSET_X[figure] * (1f + 1.5f * apart.value)

    private fun originY(figure: Int): Float = wander.y + OFFSET_Y[figure] * (1f + 1.5f * apart.value)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        // The trace is scaled to the song, so busy music shows as a hotter beam, and a kick flares it.
        val glow = (0.2f + 0.8f * state.drive) * (1f + 0.8f * flash.value)
        val beam = (size.minDimension * 0.018f * beamWidth.value).coerceAtLeast(1.2f)
        val hue = state.frame.keyHue * state.frame.keyConfidence + genes.walk
        for (index in 0 until figures.drawn(1)) {
            val presence = figures.presence(index, 1)
            if (presence <= 0.01f) continue
            drawFigure(state, index, size.height * REACH[index], hue + index * 0.33f, glow * presence, beam)
        }
        with(glints) { drawSprites(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.3f + 0.7f * state.lift) }
    }

    private fun DrawScope.drawFigure(state: VizRenderState, figure: Int, reach: Float, hue: Float, glow: Float, beam: Float) {
        val xs = figureX[figure]
        val ys = figureY[figure]
        val originX = originX(figure) * size.width
        val originY = originY(figure) * size.height
        // A piece of trace this short, in pixels, counts as the beam standing still.
        val still = (size.minDimension * 0.004f).coerceAtLeast(1f)
        for (path in levels) path.reset()
        var fromX = originX + xs[0] * reach
        var fromY = originY - ys[0] * reach
        for (index in 1 until POINTS) {
            val toX = originX + xs[index] * reach
            val toY = originY - ys[index] * reach
            val length = hypot(toX - fromX, toY - fromY)
            val brightness = (still / (length + still * 0.25f)).coerceIn(0f, 1f)
            val path = levels[(brightness * (LEVELS - 1) + 0.5f).toInt()]
            path.moveTo(fromX, fromY)
            path.lineTo(toX, toY)
            fromX = toX
            fromY = toY
        }
        for (level in 0 until LEVELS) {
            val brightness = (level + 0.5f) / LEVELS
            drawPath(
                levels[level],
                color = state.palette.cycled(hue, saturation = 0.7f - 0.5f * brightness, value = 1f, alpha = ((0.08f + 0.8f * brightness) * glow).coerceIn(0f, 1f)),
                style = Stroke(beam, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
    }

    override fun onReset() {
        gain.reset()
        flash.reset()
        wander.reset()
        apartHold = 0f
        apart.reset()
        quiet.reset()
        phase = 0f
        pastLeft.clear()
        pastRight.clear()
        glintCredit = 0f
        glints.clear()
        comets.clear()
    }

    private companion object {
        const val LEVELS = 6
        const val POINTS = 256
        val REACH = floatArrayOf(0.85f, 0.65f, 0.65f)
        val OFFSET_X = floatArrayOf(0f, -0.26f, 0.26f)
        val OFFSET_Y = floatArrayOf(0f, 0.08f, -0.08f)
    }
}
