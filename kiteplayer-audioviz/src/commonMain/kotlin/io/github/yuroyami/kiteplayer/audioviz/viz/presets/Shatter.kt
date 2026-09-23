package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import androidx.compose.ui.graphics.BlendMode
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * A waterfall of the waveform between drops, and the picture breaking apart when one comes.
 *
 * Between drops three traces are drawn at one edge every frame and the echo carries them across, so the
 * last seconds of sound stack up into a waterfall that fills the screen. On a drop the picture is cut
 * into shards, each a triangle carrying its own piece of it, spinning out to the edges over a visual cycle. A
 * snare breaks off six smaller shards, a kick jolts the waterfall, and dust flies with the pieces.
 */
internal class Shatter : Layered(
    name = "Shatter",
    bucket = VizEnergy.High,
    kit = Kit(seed = 811L, groundKind = GroundKind.Grid, groundDim = 0.6f, detailKind = DetailKind.Scan, camera = Camera2D(wander = 0.05f, seed = 811)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Camera, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Discrete),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.975f, livelyTrail = 0.965f)

    private val shardCount = genes.choice("Shards", 3, start = 1)
    private val shardSize = genes.number("Shard size", 0.7f, 1.4f, 1f)
    private val upward = genes.toggle("Waterfall rises", start = false)
    private val smallShatter = genes.toggle("Snare shatter", start = true)

    private val jolt = Spring(stiffness = 120f, damping = 0.5f)
    private var previous: ImageBitmap? = null
    private var snapshot: ImageBitmap? = null
    private var pending = 0
    private val homeX = FloatArray(MOST)
    private val homeY = FloatArray(MOST)
    private val shardX = FloatArray(MOST)
    private val shardY = FloatArray(MOST)
    private val speedX = FloatArray(MOST)
    private val speedY = FloatArray(MOST)
    private val turn = FloatArray(MOST)
    private val spin = FloatArray(MOST)
    private val life = FloatArray(MOST)
    private val corners = FloatArray(MOST * 6)
    private val dust = Sprites(240, 1_811L)
    private val comets = Comets()
    private val shard = Path()
    private val trace = Path()

    override fun onPreviousFrame(picture: ImageBitmap?) {
        previous = picture
    }

    // The echo carries the traces away from the edge they are drawn at, faster when a kick jolts it.
    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        // Mostly the music, not mostly a floor: at a floor of 0.35 against 0.4 of drive, this ran
        // at nine tenths of its drum speed under a quiet pad and said nothing about the music.
        val speed = (0.08f + 1.1f * state.drive) * (1f + jolt.value.coerceIn(0f, 2f))
        return EchoFrame(zoomX = base.zoomX, driftY = if (rising()) -speed else speed)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        jolt.kick(gestures.kick * 3f)
        jolt.advance(dt)
        if (gestures.drop) {
            pending = shardCount.count(16, 8)
        } else if (gestures.snare > 0f && smallShatter.on && life.all { it <= 0f }) {
            pending = SMALL
        }
        for (slot in 0 until MOST) {
            if (life[slot] <= 0f) continue
            life[slot] -= dt / gestures.cycleSeconds
            shardX[slot] += speedX[slot] * dt
            shardY[slot] += speedY[slot] * dt
            turn[slot] += spin[slot] * dt
        }
        dust.advance(dt, drag = 0.6f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        kit.place(1, 0.5f, if (rising()) 0.9f else 0.1f)
    }

    // The waterfall turns round at each supported section boundary.
    private fun rising(): Boolean = upward.on != (gestures.sections % 2 == 1)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        if (pending > 0) {
            cut(pending)
            pending = 0
        }
        drawShards(state)
        drawWaterfall(state)
        with(dust) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    /**
     * Copies the picture as it stood and cuts it into triangles flying out from the middle: a grid of
     * them over the whole screen on a drop, or a fan of six round one point on a snare.
     */
    private fun DrawScope.cut(count: Int) {
        val source = previous ?: return
        val copy = snapshot?.takeIf { it.width == source.width && it.height == source.height }
            ?: ImageBitmap(source.width, source.height).also { snapshot = it }
        Canvas(copy).drawImage(source, Offset.Zero, Paint().apply { blendMode = BlendMode.Src })
        life.fill(0f)
        if (count == SMALL) {
            val middleX = 0.2f + 0.6f * random.next()
            val middleY = 0.2f + 0.6f * random.next()
            val reach = 0.12f * shardSize.value
            for (slot in 0 until SMALL) {
                val from = TAU * slot / SMALL
                val to = TAU * (slot + 1) / SMALL
                launch(slot, middleX, middleY, 0f, 0f, cos(from) * reach / kit.aspect, sin(from) * reach, cos(to) * reach / kit.aspect, sin(to) * reach, 0.6f)
            }
            return
        }
        val columns = 4
        val rows = count / (columns * 2)
        var slot = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column.toFloat() / columns
                val right = (column + 1f) / columns
                val top = row.toFloat() / rows
                val bottom = (row + 1f) / rows
                val jitter = 0.04f * shardSize.value
                val x = left + (right - left) * (0.5f + random.signed() * 0.3f)
                val y = top + (bottom - top) * (0.5f + random.signed() * 0.3f)
                // Two triangles a cell, split along a diagonal that wanders.
                launch(slot++, x, y, left - x, top - y, right - x + random.signed() * jitter, top - y, left - x, bottom - y + random.signed() * jitter, 1f)
                launch(slot++, x, y, right - x, top - y, right - x, bottom - y, left - x + random.signed() * jitter, bottom - y, 1f)
            }
        }
    }

    // One shard: its home in shares of the screen and its three corners measured from there.
    private fun launch(slot: Int, x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, speed: Float) {
        if (slot >= MOST) return
        val middleX = x + (ax + bx + cx) / 3f
        val middleY = y + (ay + by + cy) / 3f
        homeX[slot] = x
        homeY[slot] = y
        shardX[slot] = x
        shardY[slot] = y
        val awayX = middleX - 0.5f
        val awayY = middleY - 0.5f
        val length = kotlin.math.sqrt(awayX * awayX + awayY * awayY).coerceAtLeast(0.05f)
        val pace = speed * (0.5f + 0.5f * random.next())
        speedX[slot] = awayX / length * pace
        speedY[slot] = awayY / length * pace
        spin[slot] = random.signed() * 4f
        turn[slot] = 0f
        life[slot] = 1f
        corners[slot * 6] = ax
        corners[slot * 6 + 1] = ay
        corners[slot * 6 + 2] = bx
        corners[slot * 6 + 3] = by
        corners[slot * 6 + 4] = cx
        corners[slot * 6 + 5] = cy
        dust.burst(middleX, middleY, 3, 0.4f, 0.8f, 0.01f, random.next(), Sprite.SPARK)
    }

    // Each shard is the copied picture clipped to its triangle, moved and turned with it.
    private fun DrawScope.drawShards(state: VizRenderState) {
        val picture = snapshot ?: return
        val edge = (size.minDimension * 0.004f).coerceAtLeast(1f)
        for (slot in 0 until MOST) {
            val left = life[slot]
            if (left <= 0f) continue
            val x = homeX[slot] * size.width
            val y = homeY[slot] * size.height
            shard.reset()
            shard.moveTo(x + corners[slot * 6] * size.width, y + corners[slot * 6 + 1] * size.height)
            shard.lineTo(x + corners[slot * 6 + 2] * size.width, y + corners[slot * 6 + 3] * size.height)
            shard.lineTo(x + corners[slot * 6 + 4] * size.width, y + corners[slot * 6 + 5] * size.height)
            shard.close()
            withTransform({
                translate((shardX[slot] - homeX[slot]) * size.width, (shardY[slot] - homeY[slot]) * size.height)
                rotate(turn[slot] * 57.29578f, Offset(x, y))
            }) {
                clipPath(shard) { drawImage(picture, alpha = left.coerceIn(0f, 1f)) }
                drawPath(shard, state.palette.cap.copy(alpha = (0.6f * left).coerceIn(0f, 1f)), style = Stroke(edge))
            }
        }
    }

    // Three traces at the edge the waterfall starts from; the echo does the rest.
    private fun DrawScope.drawWaterfall(state: VizRenderState) {
        val frame = state.frame
        val scale = frame.waveformGain
        val edge = size.height * if (rising()) 0.9f else 0.1f
        val reach = size.height * 0.08f * (0.6f + state.lift) * scale
        for (index in 0 until 3) {
            val samples = when (index) {
                0 -> frame.scopeLeft
                1 -> frame.scopeRight
                else -> frame.scope
            }
            if (samples.size < 2) continue
            trace.reset()
            val step = size.width / (samples.size - 1)
            for (point in samples.indices) {
                val y = edge + (index - 1) * size.height * 0.03f - samples[point].coerceIn(-1f, 1f) * reach
                if (point == 0) trace.moveTo(0f, y) else trace.lineTo(point * step, y)
            }
            drawPath(
                trace,
                state.palette.cycled(genes.walk + index * 0.3f, value = 1f, alpha = (0.12f + 0.85f * state.lift).coerceIn(0f, 1f)),
                style = Stroke((size.minDimension * (0.004f + 0.004f * state.lift)).coerceAtLeast(1.2f), cap = StrokeCap.Round),
            )
        }
    }

    override fun onReset() {
        jolt.reset()
        previous = null
        pending = 0
        life.fill(0f)
        dust.clear()
        comets.clear()
    }

    private companion object {
        const val MOST = 32
        const val SMALL = 6
    }
}
