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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.TraceGain
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.polygon
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three cores, one for each part of the spectrum, orbiting each other and throwing arcs between them,
 * inside shells of spectrum that reach the corners. The colours come from the key, so a change of chord
 * changes the picture. A kick swells the cores, a snare steps the shells round, and a drop collides the
 * cores into one before they fly apart again.
 */
internal class Reactor : Layered(
    name = "Reactor",
    family = VizFamily.MusicalColors,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 601L, groundKind = GroundKind.Voronoi, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 601)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.7f, livelyTrail = 0.56f, calmSpin = 0.1f, livelySpin = 0.6f)

    private val cores = genes.choice("Cores", 3, start = 2)
    private val shells = genes.choice("Shells", 3, start = 1)
    private val arcDensity = genes.number("Arc density", 0.6f, 1.6f, 1f)
    private val exchange = genes.choice("Arc exchange", 3, start = 1)

    private val stage = Stage(reachX = 0.2f, reachY = 0.15f, start = 0.5f)
    private var orbit = 0f
    private var spin = 0f
    private var step = 0f
    private val swell = Spring(stiffness = 250f, damping = 0.42f)
    private var collide = 0f
    private val coreX = FloatArray(3) { 0.5f }
    private val coreY = FloatArray(3) { 0.5f }
    private val arcs = Travellers(16)
    private val arcMesh = TriangleMesh(maxVertices = 16 * 12 + 8)
    private val electronMesh = TriangleMesh(maxVertices = ELECTRONS * 5 + 8)
    private val sparks = Sprites(260, 1_601L)
    private val comets = Comets()
    private var lastBeat = -1
    private var nextCore = 0

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        orbit += dt * (0.5f + 2.2f * state.drive)
        spin += dt * (0.4f + 2.4f * state.drive)
        if (gestures.drop) collide = 1f
        collide = (collide - dt / gestures.barSeconds).coerceAtLeast(0f)
        swell.kick(gestures.kickHit * 9f)
        swell.advance(dt)
        if (gestures.snareHit > 0f) step += TAU / 24f
        val apart = 0.3f * (1f - sin(collide * PI.toFloat()))
        val count = cores.count(1)
        for (index in 0 until 3) {
            val angle = orbit + index * TAU / count
            coreX[index] = stage.x + apart * cos(angle) / kit.aspect * 1.6f
            coreY[index] = stage.y + apart * sin(angle)
            kit.place(index + 1, coreX[index], coreY[index])
        }
        // Arcs pass from core to core once a bar, once a beat or twice a beat.
        val beats = (gestures.barPhase * 8f).toInt()
        if (beats != lastBeat) {
            lastBeat = beats
            val every = when (exchange.value) {
                0 -> 8
                1 -> 2
                else -> 1
            }
            if (beats % every == 0 && count > 1) {
                val from = nextCore % count
                val to = (from + 1) % count
                nextCore++
                arcs.spawn(coreX[from], coreY[from], coreX[to], coreY[to], gestures.beatSeconds * 0.8f, PathShape.Arc, 0.25f, 0.022f, random.next(), 0f, Sprite.SPARK)
            }
        }
        arcs.advance(dt)
        if (gestures.snareHit > 0f) {
            val a = random.next() * TAU
            sparks.burst(stage.x + cos(a) * 0.3f / kit.aspect, stage.y + sin(a) * 0.3f, 8, 0.4f, 0.6f, 0.012f, random.next(), Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        val key = state.frame.keyHue * state.frame.keyConfidence + genes.walk
        val lift = 0.2f + 0.8f * state.lift
        val middle = Offset(stage.x * size.width, stage.y * size.height)
        val stroke = (size.minDimension * 0.018f).coerceAtLeast(1.4f)
        for (shell in 0 until shells.drawn(2)) {
            val presence = shells.presence(shell, 2)
            if (presence <= 0.01f) continue
            val radius = sceneRadius * (0.35f + 0.28f * shell)
            val way = if (shell % 2 == 0) 1f else -1f
            val turn = (spin * (1f - shell * 0.25f) + step) * way
            val count = ((56 + shell * 28) * arcDensity.value).toInt()
            for (arc in 0 until count) {
                val along = arc.toFloat() / count
                val energy = bands[(along * (bands.size - 1)).toInt()]
                if (energy <= 0.01f) continue
                val angle = TAU * along + turn
                drawLine(
                    color = state.palette.cycled(key + along * 0.3f + shell * 0.2f, value = 0.45f + 0.55f * energy, alpha = (lift * presence).coerceIn(0f, 1f)),
                    start = polar(middle, angle, radius),
                    end = polar(middle, angle, radius + size.minDimension * 0.3f * energy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        val grow = swell.value.coerceIn(-0.3f, 1.6f)
        for (index in 0 until cores.drawn(1)) {
            val presence = cores.presence(index, 1)
            if (presence <= 0.01f) continue
            val at = Offset(coreX[index] * size.width, coreY[index] * size.height)
            val core = size.minDimension * (0.035f + 0.04f * split.level(index) + 0.04f * grow)
            val halo = (core * 3.4f).coerceAtLeast(1f)
            drawCircle(
                Brush.radialGradient(
                    0f to state.palette.cycled(key + index * 0.2f, saturation = 0.5f, alpha = (0.75f * presence * lift).coerceIn(0f, 1f)),
                    0.35f to state.palette.mid.copy(alpha = (0.4f * presence).coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                    center = at,
                    radius = halo,
                ),
                halo,
                at,
            )
            drawCircle(state.palette.cap.copy(alpha = presence), core.coerceAtLeast(1f), at)
        }
        drawTravellers(arcs, arcMesh, state.palette, key)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // Electrons circling the cores: hard points that never stop.
        electronMesh.clear()
        val count = cores.count(1)
        for (index in 0 until ELECTRONS) {
            val core = index % count
            val reach = size.minDimension * (0.08f + 0.02f * (index * 7 % 10))
            val way = if (index % 2 == 0) 1f else -1f
            val angle = orbit * (3.5f + (index % 4) * 0.8f) * way + index * 2.4f
            val colour = state.palette.argb(core * 0.2f + index * 0.01f + genes.walk, saturation = 0.5f, value = 1f, alpha = 0.4f + 0.5f * state.lift)
            electronMesh.polygon(coreX[core] * size.width + cos(angle) * reach, coreY[core] * size.height + sin(angle) * reach * 0.8f, size.minDimension * 0.012f, 4, angle, colour)
        }
        drawMesh(electronMesh, BlendMode.Plus)
        with(sparks) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        orbit = 0f
        spin = 0f
        step = 0f
        swell.reset()
        collide = 0f
        arcs.clear()
        sparks.clear()
        comets.clear()
        lastBeat = -1
        nextCore = 0
    }

    private companion object {
        const val ELECTRONS = 180
    }
}

/**
 * Left against right, turned so that mono stands upright, filling the screen, with the traces from one
 * and two seconds ago behind it like the decay of an old tube. The afterimage streams away through the
 * echo, a kick throws the figure outward, and a drop pulls the three traces apart and back together.
 *
 * The middle of the two channels goes up the screen and their difference goes across: a mono recording
 * draws a vertical line, a wide mix opens into a cloud, and anything out of phase lies down flat.
 */
internal class Stereogram : Layered(
    name = "Stereogram",
    family = VizFamily.MusicalColors,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 602L, groundKind = GroundKind.Spectrogram, groundDim = 0.85f, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.06f, seed = 602)),
) {
    // The lines of an old tube, which is what these traces grew up on.
    override val post: PostSpec get() = PostSpec.Retro.copy(glitch = false)
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.86f, livelyTrail = 0.7f)

    private val traces = genes.choice("Traces", 3, start = 2)
    private val drift = genes.choice("Drift", 3)
    private val boxRule = genes.toggle("Box follows width", start = true)
    private val glintRate = genes.number("Glints", 0.3f, 1.5f, 0.8f)
    private val copies = genes.toggle("Turned copies", start = true)

    private val gain = TraceGain()
    private val jump = Spring(stiffness = 150f, damping = 0.45f)
    private val stage = Stage(reachX = 0.2f, reachY = 0.14f, start = 0.9f)
    private val lefts = History(rows = 160)
    private val rights = History(rows = 160)
    private val left = FloatArray(TRACE)
    private val right = FloatArray(TRACE)
    private var scale = 1f
    private var separate = 0f
    private val glints = Sprites(200, 1_602L)
    private var glintCredit = 0f
    private val path = Path()

    // Down, up, or outward from the middle, and faster when the music drives harder.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        val speed = 0.1f + 0.25f * state.drive
        return EchoFrame(
            zoomX = base.zoomX + drift.weight(2) * (0.004f + 0.012f * state.drive),
            driftY = speed * (drift.weight(0) - drift.weight(1)),
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        val frame = state.frame
        frame.scopeLeft.squeezeInto(left)
        frame.scopeRight.squeezeInto(right)
        lefts.push(left, state.timeSeconds)
        rights.push(right, state.timeSeconds)
        scale = gain.update(frame.scopeLeft, frame.scopeRight, dt)
        jump.kick(gestures.kickHit * 5f)
        jump.advance(dt)
        if (gestures.drop) separate = 1f
        separate = (separate - dt / gestures.barSeconds).coerceAtLeast(0f)
        // The head of the trace is its point furthest out, which leaps round the figure.
        var head = 0
        var furthest = -1f
        for (index in 0 until TRACE) {
            val out = abs(left[index] + right[index]) + abs(left[index] - right[index])
            if (out > furthest) {
                furthest = out
                head = index
            }
        }
        val reach = 0.55f * (1f + 0.25f * jump.value.coerceIn(-0.5f, 1.5f))
        val headX = stage.x + (left[head] - right[head]) * 0.5f * scale * reach / kit.aspect
        val headY = stage.y - (left[head] + right[head]) * 0.5f * scale * reach
        kit.place(0, headX, headY)
        kit.place(1, stage.x, stage.y)
        // Glints where the trace turns back on itself.
        glintCredit += dt * 30f * glintRate.value * (0.3f + 0.7f * state.drive)
        var index = 2
        while (glintCredit >= 1f && index < TRACE) {
            val before = left[index - 1] - right[index - 1] - (left[index - 2] - right[index - 2])
            val after = left[index] - right[index] - (left[index - 1] - right[index - 1])
            if (before * after < 0f) {
                glintCredit -= 1f
                glints.burst(
                    stage.x + (left[index] - right[index]) * 0.5f * scale * reach / kit.aspect,
                    stage.y - (left[index] + right[index]) * 0.5f * scale * reach,
                    1, 0.02f, 0.4f, 0.02f, index.toFloat() / TRACE, Sprite.SPARK,
                )
            }
            index += 3
        }
        glintCredit = glintCredit.coerceAtMost(6f)
        glints.advance(dt, drag = 1f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val key = state.frame.keyHue * state.frame.keyConfidence + genes.walk
        val reach = size.height * 0.55f * (1f + 0.25f * jump.value.coerceIn(-0.5f, 1.5f))
        val middle = Offset(stage.x * size.width, stage.y * size.height)
        // Older traces sit behind: smaller, dimmer, a little further with the camera, and pulled
        // apart on a drop.
        for (depth in traces.drawn(1) - 1 downTo 0) {
            val presence = traces.presence(depth, 1)
            if (presence <= 0.01f) continue
            val leftRow = lefts.row(depth.toFloat())
            val rightRow = rights.row(depth.toFloat())
            if (leftRow < 0 || rightRow < 0) continue
            val shrink = 1f - 0.14f * depth
            val apart = separate * (depth - 1f) * size.width * 0.18f
            val alpha = ((0.15f + 0.85f * state.drive) * presence / (1f + 0.6f * depth)).coerceIn(0f, 1f)
            translate(apart + camera.panX * size.width * 0.2f * depth, camera.panY * size.height * 0.2f * depth) {
                path.reset()
                for (index in 0 until TRACE) {
                    val along = index.toFloat() / (TRACE - 1)
                    val l = lefts.sample(leftRow, along) * scale
                    val r = rights.sample(rightRow, along) * scale
                    val x = middle.x + (l - r) * 0.5f * reach * shrink
                    val y = middle.y - (l + r) * 0.5f * reach * shrink
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val colour = state.palette.cycled(key + 0.15f * depth, alpha = alpha)
                val style = Stroke(((size.minDimension * 0.012f) * (0.4f + state.drive)).coerceAtLeast(1.2f), cap = StrokeCap.Round)
                drawPath(path, colour, style = style)
                // The same trace turned five ways, so even a mono line makes a star that fills the box.
                val turned = copies.weight(1)
                if (turned > 0.01f) {
                    for (angle in TURNS) rotate(angle, middle) { drawPath(path, colour.copy(alpha = colour.alpha * turned * 0.7f), style = style) }
                }
            }
        }
        // The box, whose shape follows how wide the mix is.
        val width = if (boxRule.on) 0.6f + 0.8f * state.frame.width else 1f
        val half = reach * 1.05f
        drawRect(
            color = state.palette.mid.copy(alpha = 0.18f),
            topLeft = Offset(middle.x - half * width, middle.y - half),
            size = Size(half * width * 2f, half * 2f),
            style = Stroke(1.5f),
        )
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(glints) { drawSprites(state.palette, genes.walk, blendMode = BlendMode.Plus) }
    }

    override fun onReset() {
        gain.reset()
        jump.reset()
        stage.reset()
        lefts.clear()
        rights.clear()
        separate = 0f
        glints.clear()
        glintCredit = 0f
    }

    private companion object {
        const val TRACE = 256
        val TURNS = floatArrayOf(90f, 30f, -30f, 60f, -60f)
    }
}
