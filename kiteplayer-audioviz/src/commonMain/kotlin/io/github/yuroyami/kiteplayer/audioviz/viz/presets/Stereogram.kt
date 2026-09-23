package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
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
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.math.abs

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
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 602L, groundKind = GroundKind.Spectrogram, groundDim = 0.85f, detailKind = DetailKind.Hatch, camera = Camera2D(wander = 0.06f, seed = 602)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Width, VizProperty.Shape),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Camera, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Spawn, response = VizResponse.Rate),
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Level, VizProperty.Shape),
    )
    // The lines of an old tube, which is what these traces grew up on.
    override val post: PostSpec get() = PostSpec.Retro.copy(glitch = false)
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.86f, livelyTrail = 0.7f)

    private val traces = genes.choice("Traces", 3, start = 2)
    private val drift = genes.choice("Drift", 3)
    private val boxRule = genes.toggle("Box follows width", start = true)
    private val glintRate = genes.number("Glints", 0.3f, 1.5f, 0.8f)
    private val copies = genes.toggle("Turned copies", start = true)

    private val jump = Spring(stiffness = 150f, damping = 0.45f)
    private val stage = Stage(reachX = 0.2f, reachY = 0.14f, start = 0.9f)
    private val lefts = History(rows = 160)
    private val rights = History(rows = 160)
    private val traceSampler = WaveformResampler()
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
        stage.advance(dt * state.idle)
        val frame = state.frame
        traceSampler.resample(frame.scopeLeft, left)
        traceSampler.resample(frame.scopeRight, right)
        lefts.push(left, state.timeSeconds)
        rights.push(right, state.timeSeconds)
        scale = frame.waveformGain
        jump.kick(gestures.kick * 5f)
        jump.advance(dt)
        if (gestures.drop) separate = 1f
        separate = (separate - dt / gestures.cycleSeconds).coerceAtLeast(0f)
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
