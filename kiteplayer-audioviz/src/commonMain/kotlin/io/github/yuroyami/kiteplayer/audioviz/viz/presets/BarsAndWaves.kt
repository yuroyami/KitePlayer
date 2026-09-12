package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Lane
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

private const val MOST_BANDS = 128

/**
 * The meter, set in a room: a checkered floor and ceiling of cells lit by the bands and running toward
 * the viewer, a wall of its own past behind it, a reflection streaming into the floor, embers off each
 * new peak, and a sweep that lights the caps once a bar.
 *
 * It keeps the absolute readings on purpose. It is a meter, and a meter that stretched itself to fill
 * the screen on a quiet song would be lying about the level.
 */
internal class Bars : Layered(
    name = "Bars",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 201L, groundKind = GroundKind.Grid, groundDim = 0.85f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.14f, seed = 201)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.62f, calmDriftY = 0.3f, livelyDriftY = 0.34f)

    private val floorDensity = genes.choice("Floor density", 3, start = 1)
    private val walls = genes.choice("History walls", 3, start = 1)
    private val depth = genes.number("Reflection depth", 0.3f, 1f, 0.7f)
    private val emberKind = genes.choice("Ember kind", 3)
    private val sides = genes.toggle("Side meters", start = false)

    private val stage = Stage(reachX = 0.3f, reachY = 0.14f, start = -0.8f)

    // Where the meter stands, which the slow stage moves up and down a little.
    private val baseline: Float get() = BASE + stage.y - 0.5f
    private val history = History()
    private var lastPeak = FloatArray(0)
    private val sweep = Sweep()
    private var snareSweep = -1f
    private var scroll = 0f
    private var fanHold = 0f
    private val fan = Envelope(attackPerSecond = 5f, releasePerSecond = 1.5f)
    private val embers = Sprites(400, 1_201L)
    private var emberCredit = 0f
    private val room = TriangleMesh(maxVertices = 2 * 10 * CELLS * 4 + 16)
    private val meter = TriangleMesh(maxVertices = 6 * MOST_BANDS * 4 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val bands = frame.bands
        stage.advance(dt)
        history.push(bands, state.timeSeconds)
        scroll += 4f * dt / gestures.beatSeconds
        sweep.advance(gestures)
        if (frame.snare > 0f) snareSweep = 0f
        if (snareSweep >= 0f) {
            snareSweep += dt / (gestures.barSeconds * 0.5f)
            if (snareSweep >= 1f) snareSweep = -1f
        }
        if (gestures.drop) fanHold = gestures.barSeconds
        fanHold -= dt
        fan.advance(if (fanHold > 0f) 1f else 0f, dt)
        if (lastPeak.size != bands.size) lastPeak = FloatArray(bands.size)
        val kind = when (emberKind.value) {
            0 -> Sprite.SPARK
            1 -> Sprite.GLOW
            else -> Sprite.DIAMOND
        }
        val span = span()
        var loudest = 0
        for (index in bands.indices) {
            val peak = frame.peaks[index]
            // An ember leaves each cap as it is pushed up to a new peak, and rises to the top edge.
            if (peak > lastPeak[index] + 0.01f && peak > 0.12f) {
                val x = stage.x - span / 2f + span * (index + 0.5f) / bands.size
                embers.burst(x, baseline - peak * baseline, 1, 0.7f + 0.6f * peak, 1.6f, 0.02f, index.toFloat() / bands.size, kind, UP, 0.5f)
            }
            lastPeak[index] = peak
            if (bands[index] > bands[loudest]) loudest = index
        }
        // A steady trickle as well, so a held chord still sends embers up.
        emberCredit = (emberCredit + dt * (10f + 30f * state.drive)).coerceAtMost(4f)
        while (emberCredit >= 1f && bands.isNotEmpty()) {
            emberCredit -= 1f
            val index = (random.next() * bands.size).toInt().coerceIn(0, bands.size - 1)
            val x = stage.x - span / 2f + span * (index + 0.5f) / bands.size
            embers.burst(x, baseline - frame.peaks[index] * baseline, 1, 0.5f + 0.5f * bands[index], 1.6f, 0.02f, index.toFloat() / bands.size, kind, UP, 0.5f)
        }
        embers.advance(dt, drag = 0.2f, gravity = -0.3f)
        kit.place(0, sweep.position, baseline)
        if (bands.isNotEmpty()) {
            kit.place(1, stage.x - span / 2f + span * (loudest + 0.5f) / bands.size, baseline - frame.peaks[loudest] * baseline)
        }
    }

    private fun span(): Float = 1f - sides.weight(1) / 3f

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bands
        if (bands.isEmpty()) return
        meter.clear()
        val base = baseline * size.height
        val half = size.width * span() / 2f
        val middle = stage.x * size.width
        // The meter's own past behind it, smaller and dimmer the further back it goes.
        for (wall in walls.drawn(1) downTo 1) {
            val presence = walls.presence(wall - 1, 1)
            if (presence <= 0.01f) continue
            val scale = 1f - 0.16f * wall
            val row = history.row(wall * 0.8f)
            addMeter(state, middle - half * scale, middle + half * scale, base - size.height * 0.08f * wall, base * scale, row, presence * 0.4f / wall, reverse = false, down = false)
        }
        // The reflection, which the echo's downward drift streams into the floor.
        addMeter(state, middle - half, middle + half, base, (size.height - base) * 1.6f * depth.value, -1, 0.35f, reverse = false, down = true)
        // The caps sit in the echo, so a falling cap leaves a short streak behind it.
        val cap = (size.height * 0.006f).coerceAtLeast(2f)
        val slot = half * 2f / bands.size
        val colour = state.palette.cap.toArgb()
        for (index in bands.indices) {
            val peak = state.frame.peaks[index]
            if (peak < 0.004f) continue
            val left = middle - half + index * slot
            val head = base - peak * base - cap
            meter.bar(left + slot * 0.06f, left + slot * 0.94f, head, head + cap, colour, colour)
        }
        drawMesh(meter)
    }

    /** Bars for every band between [fromX] and [toX], standing on [foot], a full bar [tallest] high. */
    private fun addMeter(state: VizRenderState, fromX: Float, toX: Float, foot: Float, tallest: Float, row: Int, alpha: Float, reverse: Boolean, down: Boolean) {
        val bands = state.frame.bands
        val count = bands.size
        val slot = (toX - fromX) / count
        val low = state.palette.low.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
        for (index in 0 until count) {
            val value = if (row < 0) bands[index] else history.sample(row, (index + 0.5f) / count)
            if (value < 0.003f) continue
            val tall = value * tallest
            val at = if (reverse) count - 1 - index else index
            val left = fromX + at * slot + slot * 0.06f
            val head = if (down) foot + tall else foot - tall
            // Coloured by how high it reaches, which is what makes a tall bar read as hot.
            val high = state.palette.ramp(value).copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
            meter.bar(left, left + slot * 0.88f, head, foot, high, low)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val bands = state.frame.bands
        if (bands.isEmpty()) return
        drawRoom(state)
        meter.clear()
        val base = baseline * size.height
        val side = sides.weight(1)
        val half = size.width * span() / 2f
        val middle = stage.x * size.width
        val spread = fan.value * size.width * 0.3f
        if (fan.value > 0.01f) {
            addMeter(state, middle - half - spread, middle + half - spread, base, base, -1, 0.6f * fan.value, reverse = false, down = false)
            addMeter(state, middle - half + spread, middle + half + spread, base, base, -1, 0.6f * fan.value, reverse = false, down = false)
        }
        addMeter(state, middle - half, middle + half, base, base, -1, 1f, reverse = false, down = false)
        if (side > 0.01f) {
            addMeter(state, 0f, size.width / 6f, base, base, -1, 0.8f * side, reverse = true, down = false)
            addMeter(state, size.width * 5f / 6f, size.width, base, base, -1, 0.8f * side, reverse = true, down = false)
        }
        drawMesh(meter)
        // The caps the sweeps are passing light up.
        val slot = half * 2f / bands.size
        val reach = size.minDimension * 0.05f
        for (index in bands.indices) {
            val position = (index + 0.5f) / bands.size
            val near = maxOf(closeness(sweep.position, position), if (snareSweep >= 0f) closeness(snareSweep, position) else 0f)
            if (near <= 0.05f) continue
            val at = Offset(middle - half + (index + 0.5f) * slot, base - state.frame.peaks[index] * base)
            drawCircle(
                Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.7f * near), 1f to Color.Transparent, center = at, radius = reach),
                reach,
                at,
                blendMode = BlendMode.Plus,
            )
        }
        with(embers) { drawSprites(state.palette, genes.walk) }
    }

    private fun closeness(sweepAt: Float, position: Float): Float = (1f - abs(sweepAt - position) / 0.06f).coerceIn(0f, 1f)

    // A floor below the meter and a ceiling above it, in perspective, one cell nearer every beat.
    private fun DrawScope.drawRoom(state: VizRenderState) {
        room.clear()
        val bands = state.frame.bands
        val rows = 4 + 2 * floorDensity.value
        val frac = scroll - floor(scroll)
        val scrolled = floor(scroll).toInt()
        for (column in 0 until CELLS) {
            val value = bands.sampleAt((column + 0.5f) / CELLS)
            for (row in -1 until rows) {
                val near = ((row + frac) / rows).coerceIn(0f, 1f)
                val nearer = ((row + 1 + frac) / rows).coerceIn(0f, 1f)
                if (nearer <= near) continue
                // A checkerboard that travels with the cells, so the whole floor runs rather than only its lines.
                val checker = if (((row - scrolled + column) % 2 + 2) % 2 == 0) 1f else 0.2f
                val alpha = (0.25f + 0.5f * value) * (0.3f + 0.7f * near) * checker
                val colour = state.palette.argb(column / CELLS.toFloat() + genes.walk, value = 0.5f + 0.5f * value, alpha = alpha)
                cell(column, near, nearer, baseline, 1f, colour)
                cell(column, near, nearer, CEILING, 0f, colour)
            }
        }
        drawMesh(room, BlendMode.Plus)
    }

    private fun DrawScope.cell(column: Int, near: Float, nearer: Float, horizon: Float, edge: Float, colour: Int) {
        val gap = 0.16f
        val c0 = column + gap
        val c1 = column + 1 - gap
        val y0 = (horizon + (edge - horizon) * near * near) * size.height
        val y1 = (horizon + (edge - horizon) * nearer * nearer) * size.height
        val a = room.vertex(spread(c0, near) * size.width, y0, colour)
        val b = room.vertex(spread(c1, near) * size.width, y0, colour)
        val c = room.vertex(spread(c1, nearer) * size.width, y1, colour)
        val d = room.vertex(spread(c0, nearer) * size.width, y1, colour)
        room.quad(a, b, c, d)
    }

    private fun spread(column: Float, near: Float): Float {
        val along = column / CELLS
        val far = 0.2f + 0.6f * along
        val close = -0.6f + 2.2f * along
        return far + (close - far) * near
    }

    override fun onReset() {
        stage.reset()
        history.clear()
        lastPeak.fill(0f)
        snareSweep = -1f
        scroll = 0f
        fanHold = 0f
        fan.reset()
        embers.clear()
        emberCredit = 0f
    }

    private companion object {
        const val BASE = 0.74f
        const val CEILING = 0.16f
        const val CELLS = 16
    }
}

/**
 * The block meter on a wall of LEDs that covers the screen, with a half-scale wall of its own past
 * scrolling behind it and a dim copy hanging from the ceiling. Absolute readings, like the front of an
 * old stereo.
 */
internal class Equaliser : Layered(
    name = "Equaliser",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 202L, groundKind = GroundKind.Hatch, groundDim = 0.8f, detailKind = DetailKind.Grid, detailStrength = 0.6f, camera = Camera2D(wander = 0.05f, seed = 202)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.62f, livelyTrail = 0.52f, calmDriftY = 0.05f, livelyDriftY = 0.12f)

    private val aspect = genes.number("Block aspect", 0.45f, 0.85f, 0.72f)
    private val rows = genes.choice("Rows", 2)
    private val backwards = genes.toggle("Scroll backwards", start = false)
    private val rule = genes.choice("Colour rule", 2)

    private val past = Array(PAST) { FloatArray(PAST_ROWS) }
    private val pastLitColours = Array(PAST_ROWS) { Color.Black }
    private val pastDimColours = Array(PAST_ROWS) { Color.Black }
    private var newestPast = 0
    private var lastBeat = -1
    private var beatFraction = 0f
    private val jump = Spring(stiffness = 320f, damping = 0.5f)
    private var sweepAt = -1f
    private var allLit = 0f
    private var lastLit = IntArray(0)
    private val sweep = Sweep()
    private val flares = Sprites(240, 1_202L)
    private val wall = TriangleMesh(maxVertices = 62 * MOST_BANDS * 4 + 16)
    private val back = TriangleMesh(maxVertices = (PAST * PAST_ROWS + 22 * MOST_BANDS + MOST_BANDS) * 4 + 16)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val bands = frame.bands
        if (gestures.phrase) rule.choose(1 - rule.value)
        val beat = (gestures.barPhase * 4f).toInt()
        beatFraction = gestures.barPhase * 4f - beat
        if (beat != lastBeat && bands.isNotEmpty()) {
            lastBeat = beat
            newestPast = (newestPast + 1) % PAST
            val column = past[newestPast]
            for (row in 0 until PAST_ROWS) column[row] = bands.sampleAt((row + 0.5f) / PAST_ROWS)
        }
        jump.kick(frame.kick * 6f)
        jump.advance(dt)
        if (frame.snare > 0f) sweepAt = 0f
        if (sweepAt >= 0f) {
            sweepAt += dt / (gestures.barSeconds * 0.5f)
            if (sweepAt >= 1f) sweepAt = -1f
        }
        if (gestures.drop) allLit = 1f
        allLit = (allLit - dt / gestures.beatSeconds).coerceAtLeast(0f)
        sweep.advance(gestures)
        val count = if (rows.value == 0) 22 else 40
        if (lastLit.size != bands.size) lastLit = IntArray(bands.size)
        for (index in bands.indices) {
            val lit = (bands[index] * count).toInt()
            if (lit > lastLit[index] && lit > 2) {
                flares.burst((index + 0.5f) / bands.size, 1f - lit.toFloat() / count, 1, 0.05f, 0.35f, 0.025f, lit.toFloat() / count, Sprite.GLOW)
            }
            lastLit[index] = lit
        }
        flares.advance(dt, drag = 2f)
        kit.place(0, sweep.position, 0.5f)
        kit.place(1, if (sweepAt >= 0f) sweepAt else 1f - sweep.position, 0.5f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bands
        if (bands.isEmpty()) return
        back.clear()
        val walk = genes.walk
        // The past as a half-scale wall, one column a beat, newest at one edge and sliding to the other.
        val columnWidth = size.width / (PAST - 2)
        val rowHeight = size.height / PAST_ROWS
        val toLeft = !backwards.on
        // Lit where a band was louder than most of this frame's spectrum, whatever the song's level.
        val litLevel = state.percentile(0.45f)
        // Each row has just two hues shared by every history column. Converting them through
        // OKLCH per cell repeated the same expensive gamut mapping forty-eight times.
        for (row in 0 until PAST_ROWS) {
            val position = row.toFloat() / PAST_ROWS + walk
            pastLitColours[row] = state.palette.cycled(position, value = 0.9f)
            pastDimColours[row] = state.palette.cycled(position, value = 0.4f)
        }
        for (age in 0 until PAST) {
            val column = past[(newestPast - age + PAST) % PAST]
            val along = (age + beatFraction) / (PAST - 2)
            val x = if (toLeft) size.width * (1f - along) else size.width * along - columnWidth
            for (row in 0 until PAST_ROWS) {
                val value = column[row]
                val lit = value > litLevel
                val alpha = if (lit) 0.16f + 0.3f * value else 0.03f
                val colour = (if (lit) pastLitColours[row] else pastDimColours[row]).copy(alpha = alpha).toArgb()
                val top = size.height - (row + 1) * rowHeight
                back.bar(x + columnWidth * 0.1f, x + columnWidth * 0.9f, top + rowHeight * 0.15f, top + rowHeight * 0.85f, colour, colour)
            }
        }
        // A dim copy of the meter hanging from the ceiling.
        val slot = size.width / bands.size
        val ceilingRow = size.height / 22f
        for (index in bands.indices) {
            val lit = (bands[index] * 22).toInt()
            for (row in 0 until lit) {
                val colour = state.palette.ramp(row / 22f).copy(alpha = 0.22f).toArgb()
                val top = row * ceilingRow
                back.bar(index * slot + slot * 0.1f, index * slot + slot * 0.9f, top, top + ceilingRow * 0.7f, colour, colour)
            }
        }
        // The caps sit in the echo, so a falling one leaves a column of fading blocks.
        val count = if (rows.value == 0) 22 else 40
        val rowStep = size.height / count
        val cap = state.palette.cap.toArgb()
        for (index in bands.indices) {
            val peak = (state.frame.peaks[index] * count).toInt().coerceIn(0, count - 1)
            val bottom = size.height - peak * rowStep
            back.bar(index * slot + slot * 0.08f, index * slot + slot * 0.92f, bottom - rowStep * aspect.value, bottom, cap, cap)
        }
        drawMesh(back)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val bands = state.frame.bands
        if (bands.isEmpty()) return
        wall.clear()
        for (option in 0 until 2) {
            val share = rows.weight(option)
            if (share > 0.01f) addWall(state, bands, if (option == 0) 22 else 40, share)
        }
        drawMesh(wall)
        val band = size.width * 0.05f
        if (sweepAt >= 0f) lightColumn(state, sweepAt * size.width, band, 0.35f)
        lightColumn(state, sweep.position * size.width, band * 0.5f, 0.12f)
        with(flares) { drawSprites(state.palette, genes.walk) }
    }

    private fun DrawScope.lightColumn(state: VizRenderState, x: Float, band: Float, alpha: Float) {
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to state.palette.cap.copy(alpha = alpha),
                1f to Color.Transparent,
                startX = x - band,
                endX = x + band,
            ),
            topLeft = Offset(x - band, 0f),
            size = androidx.compose.ui.geometry.Size(band * 2f, size.height),
            blendMode = BlendMode.Plus,
        )
    }

    // Every block on the wall: lit ones up to each band's level, the rest at a glimmer.
    private fun DrawScope.addWall(state: VizRenderState, bands: FloatArray, count: Int, share: Float) {
        val columns = bands.size
        val slot = size.width / columns
        val rowHeight = size.height / count
        val block = rowHeight * aspect.value
        val lift = jump.value.coerceIn(-0.5f, 1.5f) * rowHeight
        val walk = genes.walk
        val perColumn = rule.weight(1)
        for (column in 0 until columns) {
            val lit = (bands[column] * count).toInt()
            val left = column * slot + slot * 0.08f
            val right = left + slot * 0.84f
            val columnColour = state.palette.cycled(column.toFloat() / columns + walk)
            for (row in 0 until count) {
                val on = row < lit
                val glow = if (on) 1f else maxOf(0.08f, allLit)
                val rowColour = state.palette.ramp(row.toFloat() / count)
                // Most frames use one rule outright. Avoid converting both colours to another
                // colour space and back just to return one unchanged endpoint.
                val mixed = when {
                    perColumn <= 0f -> rowColour
                    perColumn >= 1f -> columnColour
                    else -> lerp(rowColour, columnColour, perColumn)
                }
                val colour = mixed.copy(alpha = (glow * share).coerceIn(0f, 1f)).toArgb()
                val bottom = size.height - row * rowHeight - if (on) lift else 0f
                wall.bar(left, right, bottom - block, bottom, colour, colour)
            }
        }
    }

    override fun onReset() {
        past.forEach { it.fill(0f) }
        newestPast = 0
        lastBeat = -1
        jump.reset()
        sweepAt = -1f
        allLit = 0f
        lastLit.fill(0)
        flares.clear()
    }

    private companion object {
        const val PAST = 48
        const val PAST_ROWS = 20
    }
}

/**
 * A spine of mirrored bars along a slow wave, with the trace at three depths sliding across it, a
 * swarm of soft spots chasing the loudest point, and spray and rings thrown off on the drums.
 */
internal class OceanMist : Layered(
    name = "Ocean Mist",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 203L, groundKind = GroundKind.Water, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 203)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.62f, livelyTrail = 0.48f)

    private val traces = genes.choice("Traces", 3, start = 2)
    private val curve = genes.number("Spine curve", 0f, 0.2f, 0.1f)
    private val swarmSize = genes.choice("Swarm", 3, start = 2)
    private val fourWay = genes.toggle("Four way", start = false)

    private val gain = TraceGain(releasePerSecond = 8f)
    private val scopes = History(rows = 160)
    private val squeezed = FloatArray(TRACE)
    private val lane = Lane(perBar = 0.5f)
    private var wave = 0f
    private var rise = 0f
    private var tightHold = 0f
    private val tight = Envelope(attackPerSecond = 6f, releasePerSecond = 1.5f)
    private var reach = 0f
    private val swarm = Swarm(80, 1_203L)
    private val spray = Sprites(300, 1_203L)
    private val mist = Sprites(160, 2_203L)
    private val rings = Travellers(12)
    private val spine = TriangleMesh(maxVertices = 2 * MOST_BANDS * 6 + 16)
    private val spots = TriangleMesh(maxVertices = 80 * 8)
    private val ringMesh = TriangleMesh(maxVertices = 12 * 40)
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        frame.scope.squeezeInto(squeezed)
        scopes.push(squeezed, state.timeSeconds)
        lane.advance(state, gestures)
        wave += dt * state.paced(0.5f)
        rise = (frame.loudLong - 0.5f) * 0.16f
        if (gestures.drop) tightHold = gestures.barSeconds
        tightHold -= dt
        tight.advance(if (tightHold > 0f) 1f else 0f, dt)
        // The water swells on a drop and the traces pull tight for the bar.
        ground?.dim = 0.9f + 0.4f * tight.value
        reach = (0.16f + 0.2f * state.energy) * gain.update(frame.scopeLeft, frame.scopeRight, dt) * (1f - 0.6f * tight.value)
        var best = 0
        for (index in squeezed.indices) if (abs(squeezed[index]) > abs(squeezed[best])) best = index
        val loudX = wrap(best.toFloat() / (TRACE - 1) + lane.offset)
        val loudY = spineY(loudX) - squeezed[best].coerceIn(-1f, 1f) * reach
        kit.place(0, loudX, loudY)
        swarm.targetX = loudX
        swarm.targetY = loudY
        swarm.advance(dt, speed = 0.3f + 0.6f * state.drive)
        val bands = frame.bandsRel
        val hit = maxOf(frame.kick, frame.snare)
        if (hit > 0f && bands.isNotEmpty()) {
            var loudest = 0
            for (index in bands.indices) if (bands[index] > bands[loudest]) loudest = index
            val along = (loudest + 0.5f) / bands.size
            val tip = spineY(along) - bands[loudest] * 0.3f
            spray.burst(along, tip, (6 + 10 * hit).toInt(), 0.35f, 0.9f, 0.01f, along, Sprite.GLOW, UP, 1.2f)
            if (frame.kick > 0f) rings.outward(along, tip, random.next() * TAU, gestures.beatSeconds * 3f, 0.05f, along, Sprite.RING)
        }
        spray.advance(dt, drag = 0.8f, gravity = 0.4f)
        mist.sprinkle(1, 2.5f, 0.012f, random.next(), Sprite.GLOW, drift = 0.03f)
        mist.advance(dt, drag = 0.2f)
        rings.advance(dt)
        kit.follow(1, rings)
    }

    private fun spineY(along: Float): Float = 0.5f + curve.value * sin(along * TAU * 1.5f + wave) - rise

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        val walk = genes.walk
        if (bands.isNotEmpty()) {
            spine.clear()
            val four = fourWay.weight(1)
            val slot = size.width / bands.size
            for (index in bands.indices) {
                val along = (index + 0.5f) / bands.size
                addSpineBar(state, along * size.width, slot, bands[index], along, 1f - 0.4f * four)
                if (four > 0.01f) addSpineBar(state, (1f - along) * size.width, slot, bands[index], 1f - along, 0.6f * four)
            }
            drawMesh(spine)
        }
        // Older traces sit behind, larger and dimmer, each a little further along its slide.
        for (depth in traces.drawn(1) - 1 downTo 0) {
            val presence = traces.presence(depth, 1)
            if (presence <= 0.01f) continue
            val row = scopes.row(depth.toFloat())
            val colour = if (depth == 0) state.palette.cap else state.palette.cycled(0.2f * depth + walk)
            drawTrace(row, reach * size.height * (1f + 0.4f * depth), colour.copy(alpha = 0.8f * presence / (1f + depth)), lane.offset + depth * 0.13f)
        }
    }

    private fun addSpineBar(state: VizRenderState, x: Float, slot: Float, value: Float, along: Float, alpha: Float) {
        val middle = spineY(along) * spineHeight
        val tall = value * spineHeight * 0.3f
        if (tall < 1f) return
        val tint = state.palette.ramp(value)
        val full = tint.copy(alpha = alpha).toArgb()
        val clear = tint.copy(alpha = 0f).toArgb()
        val left = x - slot * 0.46f
        val right = x + slot * 0.46f
        val a = spine.vertex(left, middle - tall, clear)
        val b = spine.vertex(right, middle - tall, clear)
        val c = spine.vertex(right, middle, full)
        val d = spine.vertex(left, middle, full)
        spine.quad(a, b, c, d)
        val e = spine.vertex(right, middle + tall, clear)
        val f = spine.vertex(left, middle + tall, clear)
        spine.quad(d, c, e, f)
    }

    private var spineHeight = 1f

    private fun DrawScope.drawTrace(row: Int, height: Float, colour: Color, offset: Float) {
        if (row < 0) return
        spineHeight = size.height
        path.reset()
        var lastX = -1f
        for (index in 0 until TRACE) {
            val along = index.toFloat() / (TRACE - 1)
            val shifted = wrap(along + offset)
            val x = shifted * size.width
            val y = spineY(shifted) * size.height - scopes.sample(row, along).coerceIn(-1f, 1f) * height
            if (index == 0 || x < lastX) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
        }
        drawPath(path, colour, style = Stroke((size.minDimension * 0.004f).coerceAtLeast(1.5f), cap = StrokeCap.Round))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        spineHeight = size.height
        spots.clear()
        val unit = size.minDimension * 0.012f
        for (index in 0 until swarmSize.drawn(40, 20)) {
            val presence = swarmSize.presence(index, 40, 20)
            if (presence <= 0.01f) continue
            val colour = state.palette.argb(index * 0.011f + genes.walk, value = 0.9f, alpha = 0.3f * presence)
            spots.glow(swarm.x[index] * size.width, swarm.y[index] * size.height, unit * (1f + index % 3), colour)
        }
        drawMesh(spots, BlendMode.Plus)
        with(mist) { drawSprites(state.palette, genes.walk, alpha = 0.35f) }
        with(spray) { drawSprites(state.palette, genes.walk) }
        drawTravellers(rings, ringMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        scopes.clear()
        lane.reset()
        wave = 0f
        tightHold = 0f
        tight.reset()
        gain.reset()
        swarm.scatter()
        spray.clear()
        mist.clear()
        rings.clear()
    }

    private companion object {
        const val TRACE = 160
    }
}

/**
 * The waveform as a waterfall: each frame's trace is carried up the screen by the echo, so the last
 * seconds of sound stack up behind the newest one. A dot runs the trace once a beat and sparks jump off
 * where it crosses zero.
 */
internal class Scope : Layered(
    name = "Scope",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 204L, groundKind = GroundKind.Fog, detailKind = DetailKind.Scan, detailStrength = 0.6f, camera = Camera2D(wander = 0.07f, seed = 204)),
) {
    // The lines of an old tube, which is what these traces grew up on.
    override val post: PostSpec get() = PostSpec.Retro
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.98f, livelyTrail = 0.975f)

    private val direction = genes.choice("Waterfall", 3)
    private val traces = genes.choice("Traces", 3, start = 1)
    private val glow = genes.number("Glow", 0.35f, 0.9f, 0.6f)
    private val grid = genes.toggle("Grid", start = false)

    private val gain = TraceGain(releasePerSecond = 8f)
    private val thick = Spring(stiffness = 200f, damping = 0.5f)
    private var fastHold = 0f
    private val fast = Envelope(attackPerSecond = 8f, releasePerSecond = 2f)
    private var reach = 0f
    private var tilt = 0f
    private var dotX = 0f
    private var dotY = 0.5f
    private val sparks = Sprites(240, 1_204L)
    private val path = Path()

    // Up, down, or outward from the middle. A drop doubles the speed for a bar.
    override fun echo(state: VizRenderState): EchoFrame {
        val mood = state.frame.mood
        val speed = (0.15f + 0.45f * state.drive) * (1f + fast.value)
        val outward = direction.weight(2)
        return EchoFrame(
            zoomX = 1f + outward * (0.012f + 0.012f * mood) * (1f + fast.value),
            driftY = speed * (direction.weight(1) - direction.weight(0)),
        )
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        ground?.kind = if (grid.on) GroundKind.Grid else GroundKind.Fog
        thick.kick(frame.kick * 6f)
        thick.advance(dt)
        if (gestures.drop) fastHold = gestures.barSeconds
        fastHold -= dt
        fast.advance(if (fastHold > 0f) 1f else 0f, dt)
        reach = (0.2f + 0.2f * state.energy) * gain.update(frame.scopeLeft, frame.scopeRight, dt)
        tilt = (split.level(1) - 0.5f) * 0.35f
        val scope = frame.scope
        val beat = gestures.barPhase * 4f
        dotX = beat - floor(beat)
        dotY = 0.5f - (if (scope.isNotEmpty()) scope.sampleAt(dotX).coerceIn(-1f, 1f) else 0f) * reach
        kit.place(0, dotX, dotY)
        // Sparks jump up where the trace rises through zero, on the hats and snares.
        if (scope.size > 8 && (frame.hat > 0f || frame.snare > 0f || gestures.bar)) {
            val step = maxOf(1, scope.size / 64)
            var index = step
            var thrown = 0
            while (index < scope.size && thrown < 4) {
                if (scope[index - step] < 0f && scope[index] >= 0f) {
                    val x = index.toFloat() / (scope.size - 1)
                    sparks.burst(x, 0.5f, 3, 0.4f, 0.8f, 0.008f, x, Sprite.SPARK, UP, 0.7f)
                    thrown++
                    index += step * 4
                }
                index += step
            }
        }
        sparks.advance(dt, drag = 0.9f, gravity = 0.3f)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val walk = genes.walk
        val thickness = (size.height * 0.006f * (1f + 0.8f * thick.value.coerceIn(0f, 1.5f))).coerceIn(1.5f, 8f)
        rotate(tilt * 57.29578f, center) {
            for (index in 0 until traces.drawn(1)) {
                val presence = traces.presence(index, 1)
                if (presence <= 0.01f) continue
                val samples = when (index) {
                    0 -> state.frame.scopeLeft
                    1 -> state.frame.scopeRight
                    else -> state.frame.scope
                }
                val colour = if (index == 0) state.palette.cap else state.palette.cycled(0.3f * index + walk)
                drawTrace(samples, reach * size.height * (1f + 0.25f * index), colour.copy(alpha = (0.2f + 0.7f * state.lift) * presence / (1f + 0.3f * index)), thickness)
            }
        }
    }

    private fun DrawScope.drawTrace(samples: FloatArray, height: Float, colour: Color, thickness: Float) {
        if (samples.size < 2) return
        path.reset()
        val step = size.width / (samples.size - 1)
        val middle = size.height / 2f
        for (index in samples.indices) {
            val y = middle - samples[index].coerceIn(-1f, 1f) * height
            if (index == 0) path.moveTo(0f, y) else path.lineTo(index * step, y)
        }
        drawPath(path, colour, style = Stroke(thickness, cap = StrokeCap.Round))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val radius = (sceneRadius * glow.value * (0.8f + 0.3f * state.body)).coerceAtLeast(1f)
        drawCircle(
            Brush.radialGradient(0f to state.palette.mid.copy(alpha = 0.04f + 0.25f * state.energy), 1f to Color.Transparent, center = center, radius = radius),
            radius,
            center,
            blendMode = BlendMode.Plus,
        )
        val at = Offset(dotX * size.width, dotY * size.height)
        val dot = size.minDimension * 0.03f
        drawCircle(Brush.radialGradient(0f to state.palette.cap, 1f to Color.Transparent, center = at, radius = dot), dot, at)
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        gain.reset()
        thick.reset()
        fastHold = 0f
        fast.reset()
        sparks.clear()
    }
}

/**
 * Bars throwing a storm of embers that leave the top of the screen within a bar, blown sideways by a
 * wind that changes side every phrase, with smoke rising behind and fireballs thrown on the kick.
 */
internal class FireStorm : Layered(
    name = "Fire Storm",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.High,
    kit = Kit(seed = 205L, groundKind = GroundKind.Rays, detailKind = DetailKind.Hatch, detailStrength = 0.7f, camera = Camera2D(wander = 0.08f, seed = 205)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.64f, livelyTrail = 0.52f, livelyZoom = 1.008f)

    private val embersParam = VizParam("Embers", 0f, 3f, 1f)
    override val params: List<VizParam> = listOf(embersParam)

    private val windSide = genes.toggle("Wind from the right", start = false)
    private val life = genes.number("Ember life", 0.6f, 1.6f, 1f)
    private val smoke = genes.toggle("Smoke", start = true)
    private val ceiling = genes.toggle("Flames from the ceiling", start = false)

    private val wind = Slew(maxPerSecond = 1.5f)
    private val lean = Spring(stiffness = 60f, damping = 0.5f)
    private var front = 0f
    private val comets = Comets(size = 0.05f)
    private var credit = 0f
    private var ashCredit = 0f
    private var dropHold = 0f
    private val embers = Sprites(1_200, 1_205L)
    private val sparks = Sprites(300, 2_205L)
    private val ash = Sprites(160, 3_205L)
    private val fireballs = Travellers(12)
    private val smokeX = FloatArray(SMOKE)
    private val smokeY = FloatArray(SMOKE)
    private val smokeAge = FloatArray(SMOKE)
    private var nextSmoke = 0
    private var smokeCredit = 0f
    private val flames = TriangleMesh(maxVertices = 2 * MOST_BANDS * 4 + 16)
    private val fireMesh = TriangleMesh(maxVertices = 12 * 12)

    override fun echo(state: VizRenderState): EchoFrame {
        val base = super.echo(state)
        return EchoFrame(zoomX = base.zoomX, driftX = wind.value * (0.05f + 0.07f * state.frame.mood), driftY = -0.04f)
    }

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val bands = frame.bandsRel
        // The wind's side comes from the gene and flips every phrase.
        val side = (1f - 2f * windSide.weight(1)) * if (gestures.phrases % 2 == 0) 1f else -1f
        wind.advance(side, dt)
        front += dt * 2.5f * state.tempo
        lean.kick(frame.snare * 4f * side)
        lean.advance(dt)
        if (gestures.drop) dropHold = gestures.barSeconds
        dropHold -= dt
        val wall = dropHold > 0f
        ground?.dim = if (wall) 1.3f else 0.9f
        embers.advance(dt, drag = 0.25f, gravity = -0.35f)
        sparks.advance(dt, drag = 0.9f, gravity = 0.4f)
        ash.advance(dt, drag = 0.3f, gravity = 0.03f)
        fireballs.advance(dt)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
        advanceSmoke(state)
        if (bands.isEmpty()) return
        credit = (credit + dt * (32f + 320f * state.drive + 260f * frame.kick) * 4f * embersParam.value).coerceAtMost(80f)
        while (credit >= 1f) {
            credit -= 1f
            val index = pickLoudBand(bands)
            if (index < 0) break
            throwEmber(index, bands)
        }
        // A drop is a wall of fire: every bar throws at once.
        if (gestures.drop) for (index in bands.indices) repeat(3) { throwEmber(index, bands) }
        var loudest = 0
        for (index in bands.indices) if (bands[index] > bands[loudest]) loudest = index
        val x = bandX(loudest, bands.size)
        val y = 1f - bands[loudest] * 0.92f
        if (gestures.kickHit > 0f) sparks.burst(x, y, (8 + 12 * gestures.kickHit).toInt(), 0.8f, 0.6f, 0.012f, bands[loudest], Sprite.SPARK, UP, 1.6f)
        if (gestures.kickHit > 0f) {
            fireballs.spawn(x, y, x + wind.value * 0.5f + random.signed() * 0.25f, -0.12f, gestures.beatSeconds * 2.5f, PathShape.Arc, 0.12f * wind.value, 0.045f, bands[loudest], 0f, Sprite.GLOW)
        }
        kit.follow(2, fireballs)
        kit.place(1, x, y)
        ashCredit += dt * 5f
        while (ashCredit >= 1f) {
            ashCredit -= 1f
            ash.sprinkle(1, 3f, 0.004f, 0.02f, Sprite.GLOW, drift = 0.02f)
        }
    }

    private fun advanceSmoke(state: VizRenderState) {
        val dt = state.deltaSeconds
        smokeCredit += dt * 2.5f * smoke.weight(1)
        while (smokeCredit >= 1f) {
            smokeCredit -= 1f
            smokeX[nextSmoke] = random.next()
            smokeY[nextSmoke] = 1.05f
            smokeAge[nextSmoke] = 0.001f
            nextSmoke = (nextSmoke + 1) % SMOKE
        }
        for (slot in 0 until SMOKE) {
            if (smokeAge[slot] <= 0f) continue
            smokeAge[slot] += dt / 5f
            smokeY[slot] -= dt * (0.06f + 0.05f * state.drive)
            smokeX[slot] += dt * wind.value * 0.03f
            if (smokeAge[slot] >= 1f) smokeAge[slot] = 0f
        }
    }

    private fun throwEmber(index: Int, bands: FloatArray) {
        val value = bands[index]
        embers.burst(bandX(index, bands.size), 1f - value * 0.92f, 1, 0.45f + 0.8f * value, life.value * (0.8f + value), 0.022f, value, Sprite.GLOW, UP + wind.value * 0.35f, 0.7f)
    }

    /** Where band [index] stands across the screen now that the fire front has moved on. */
    private fun bandX(index: Int, count: Int): Float = wrap((index + 0.5f + front) / count)

    /** Picks a band to throw an ember from, favouring the loud ones. */
    private fun pickLoudBand(bands: FloatArray): Int {
        var total = 0f
        for (value in bands) total += value * value
        if (total <= 1e-5f) return -1
        var target = random.next() * total
        for (index in bands.indices) {
            target -= bands[index] * bands[index]
            if (target <= 0f) return index
        }
        return bands.size - 1
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val haze = smoke.weight(1)
        if (haze > 0.01f) {
            for (slot in 0 until SMOKE) {
                val age = smokeAge[slot]
                if (age <= 0f) continue
                val at = Offset(smokeX[slot] * size.width, smokeY[slot] * size.height)
                val radius = size.minDimension * (0.08f + 0.18f * age)
                val alpha = (0.4f * sin(PI.toFloat() * age) * haze).coerceIn(0f, 1f)
                drawCircle(Brush.radialGradient(0f to state.palette.low.copy(alpha = alpha), 1f to Color.Transparent, center = at, radius = radius), radius, at)
            }
        }
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        flames.clear()
        val slot = size.width / bands.size
        val skew = lean.value.coerceIn(-1.5f, 1.5f) * slot * 1.5f
        val high = state.palette.high.toArgb()
        val low = state.palette.low.toArgb()
        val hanging = ceiling.weight(1)
        val hangHigh = state.palette.high.copy(alpha = 0.7f * hanging).toArgb()
        val hangLow = state.palette.low.copy(alpha = 0.7f * hanging).toArgb()
        for (index in bands.indices) {
            val value = bands[index]
            if (value < 0.004f) continue
            val tall = value * size.height * 0.92f
            val left = bandX(index, bands.size) * size.width - slot * 0.35f
            val right = left + slot * 0.7f
            // The flames lean with the snare: the head moves, the foot stays put.
            val a = flames.vertex(left + skew * value, size.height - tall, high)
            val b = flames.vertex(right + skew * value, size.height - tall, high)
            val c = flames.vertex(right, size.height, low)
            val d = flames.vertex(left, size.height, low)
            flames.quad(a, b, c, d)
            if (hanging > 0.01f) flames.bar(left, right, tall * 0.5f, 0f, hangHigh, hangLow)
        }
        drawMesh(flames)
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(ash) { drawSprites(state.palette, genes.walk, alpha = 0.4f, saturation = 0.2f) }
        with(embers) { drawSprites(state.palette, genes.walk) }
        with(sparks) { drawSprites(state.palette, genes.walk) }
        drawTravellers(fireballs, fireMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        wind.reset()
        lean.reset()
        credit = 0f
        ashCredit = 0f
        dropHold = 0f
        embers.clear()
        sparks.clear()
        ash.clear()
        fireballs.clear()
        comets.clear()
        front = 0f
        smokeAge.fill(0f)
        nextSmoke = 0
        smokeCredit = 0f
    }

    private companion object {
        const val SMOKE = 14
    }
}
