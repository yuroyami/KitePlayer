package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The meter, set in a room: a checkered floor and ceiling of cells lit by the bands and running toward
 * the viewer, a wall of its own past behind it, a reflection streaming into the floor, embers off each
 * new peak, and a sweep that lights the caps once a bar. Optional overlapping meters drift and
 * tilt independently behind the main one. Solid or segmented geometry applies to every meter.
 *
 * The faint cell grid and the complementary ceiling bars use the front meter's exact lattice.
 * Height is a fixed artistic mapping of the shared display driver, with named sensitivity,
 * frequency balance and curve controls. No band learns its own automatic gain. Activity controls
 * colour and embers separately, so an absent frequency remains absent in a busy song.
 */
internal class Bars : Layered(
    name = "Bars",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 201L, groundKind = GroundKind.Grid, groundDim = 0.85f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.14f, seed = 201)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Spawn),
        VizDrive(VizDriver.Mood, VizProperty.Colour),
        VizDrive(VizDriver.Onset, VizProperty.Colour),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.62f, calmDriftY = 0.3f, livelyDriftY = 0.34f)

    private val floorDensity = genes.choice("Floor density", 3, start = 1)
    private val emberKind = genes.choice("Ember kind", 3)

    private val grid = VizParam("Background grid", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val gridOpacity = VizParam("Grid opacity", 0f, 0.25f, 0.07f)
    private val opposing = VizParam("Opposing bars", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val opposingOpacity = VizParam("Opposing opacity", 0f, 1f, 0.38f)
    private val opposingGap = VizParam("Opposing gap", 0.01f, 0.25f, 0.045f)
    private val segmented = VizParam("Segmented bars", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val rows = VizParam("Cell rows", 12f, MAX_ROWS.toFloat(), 24f).apply { step = 1f }
    private val fill = VizParam("Cell fill", 0.3f, 1f, 0.76f)
    private val width = VizParam("Bar width", 0.4f, 1f, 0.88f)
    private val overlapping = VizParam("Overlapping layers", 0f, 1f, 1f).apply { step = 1f; toggle = true }
    private val layerCount = VizParam("Extra layers", 1f, MAX_LAYERS.toFloat(), 2f).apply { step = 1f }
    private val layerOpacity = VizParam("Layer opacity", 0f, 0.65f, 0.22f)
    private val layerOffset = VizParam("Layer offset", 0f, 0.3f, 0.08f)
    private val layerDrift = VizParam("Layer drift", 0f, 1f, 0.4f)
    private val layerSpeed = VizParam("Layer speed", 0f, 2f, 0.6f)
    private val layerTilt = VizParam("Layer tilt", 0f, 20f, 4f)
    private val layerColour = VizParam("Layer colour shift", 0f, 0.5f, 0.12f)
    private val sensitivity = VizParam("Sensitivity (dB)", -12f, 12f, 3f).apply { step = 0.5f }
    private val balance = VizParam("Frequency balance", 0f, 1f, 0.7f)
    private val curve = VizParam("Height curve", 0.5f, 1.5f, 0.8f)
    private val heat = VizParam("Activity heat", 0f, 1f, 0.65f)
    private val emberAmount = VizParam("Embers", 0f, 3f, 1f)
    private val walls = VizParam("History layers", 0f, 3f, 2f).apply { step = 1f }
    private val depth = VizParam("Reflection depth", 0f, 1f, 0.7f)
    private val floorStrength = VizParam("Floor and ceiling", 0f, 1f, 1f)
    private val sides = VizParam("Side meters", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val stageMotion = VizParam("Stage motion", 0f, 1f, 0.45f)
    override val params: List<VizParam> = listOf(
        grid, gridOpacity, opposing, opposingOpacity, opposingGap, segmented, rows, fill, width,
        overlapping, layerCount, layerOpacity, layerOffset, layerDrift, layerSpeed, layerTilt, layerColour,
        sensitivity, balance, curve, heat, emberAmount, walls, depth, floorStrength, sides, stageMotion,
    )

    private val stage = Stage(reachX = 0.3f, reachY = 0.14f, start = -0.8f)

    // Where the meter stands, which the slow stage moves up and down a little.
    private val baseline: Float get() = BASE + (stage.y - 0.5f) * stageMotion.value
    private val ceiling: Float get() = CEILING + (stage.y - 0.5f) * stageMotion.value
    private val centreX: Float get() = 0.5f + (stage.x - 0.5f) * stageMotion.value
    private val height: Float get() = baseline - ceiling
    private val history = History()
    private var heights = FloatArray(0)
    private var peaks = FloatArray(0)
    private var octaveBalance = FloatArray(0)
    private var lowHz = Double.NaN
    private var highHz = Double.NaN
    private var activity = 0f
    private var lastPeak = FloatArray(0)
    private val sweep = Sweep()
    private var snareSweep = -1f
    private var scroll = 0f
    private var fanHold = 0f
    private val fan = Envelope(attackPerSecond = 5f, releasePerSecond = 1.5f)
    private val layerPhase = FloatArray(MAX_LAYERS) { it * 1.3f }
    private val embers = Sprites(400, 1_201L)
    private var emberCredit = 0f
    private val room = TriangleMesh(maxVertices = 2 * 10 * CELLS * 4 + 16)
    // Flush after each batch of columns, including for analysers configured above 128 bands.
    private val meter = TriangleMesh(maxVertices = COLUMN_BATCH * (MAX_ROWS + 4) * 4)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        updateLevels(state)
        val bands = heights
        activity = (0.5f * frame.mood + 0.3f * frame.density +
            0.2f * (frame.novelty / 4f).coerceIn(0f, 1f)) * frame.audible
        stage.advance(dt * state.idle)
        history.push(bands, state.timeSeconds)
        scroll += 4f * state.stepSeconds / gestures.beatSeconds
        sweep.advance(gestures)
        if (frame.snare > 0f) snareSweep = 0f
        if (snareSweep >= 0f) {
            snareSweep += state.stepSeconds / (gestures.cycleSeconds * 0.5f)
            if (snareSweep >= 1f) snareSweep = -1f
        }
        if (gestures.drop) fanHold = gestures.cycleSeconds
        fanHold -= dt
        fan.advance(if (fanHold > 0f) 1f else 0f, dt)
        for (index in layerPhase.indices) {
            val direction = if (index % 2 == 0) 1f else -1f
            layerPhase[index] = (layerPhase[index] + dt * state.paced(0.5f + 0.13f * index) *
                layerSpeed.value * direction + TAU) % TAU
        }
        if (lastPeak.size != bands.size) lastPeak = FloatArray(bands.size)
        val kind = when (emberKind.value) {
            0 -> Sprite.SPARK
            1 -> Sprite.GLOW
            else -> Sprite.DIAMOND
        }
        val span = span()
        var loudest = 0
        for (index in bands.indices) {
            val peak = peaks[index]
            // An ember leaves each cap as it is pushed up to a new peak, and rises to the top edge.
            if (peak > lastPeak[index] + 0.01f && peak > 0.12f && emberAmount.value > 0f) {
                val x = centreX - span / 2f + span * (index + 0.5f) / bands.size
                val count = (emberAmount.value + random.next()).toInt()
                embers.burst(x, baseline - peak * height, count, 0.7f + 0.6f * peak, 1.6f, 0.02f, hot(bands[index]), kind, UP, 0.5f)
            }
            lastPeak[index] = peak
            if (bands[index] > bands[loudest]) loudest = index
        }
        // A steady trickle as well, so a held chord still sends embers up.
        emberCredit = (emberCredit + dt * (10f + 30f * state.drive + 50f * activity) *
            emberAmount.value * frame.audible).coerceAtMost(6f)
        while (emberCredit >= 1f && bands.isNotEmpty()) {
            emberCredit -= 1f
            val index = (random.next() * bands.size).toInt().coerceIn(0, bands.size - 1)
            if (bands[index] < 0.03f) continue
            val x = centreX - span / 2f + span * (index + 0.5f) / bands.size
            embers.burst(x, baseline - peaks[index] * height, 1, 0.5f + 0.5f * bands[index], 1.6f, 0.02f, hot(bands[index]), kind, UP, 0.5f)
        }
        embers.advance(dt, drag = 0.2f, gravity = -0.3f)
        kit.place(0, sweep.position, baseline)
        if (bands.isNotEmpty()) {
            kit.place(1, centreX - span / 2f + span * (loudest + 0.5f) / bands.size, baseline - peaks[loudest] * height)
        }
    }

    private fun span(): Float = 1f - sides.value / 3f

    /**
     * Fixed bandwidth compensation: pink-noise power in an ERB cell is proportional to ln(high/low).
     * Balance can compensate that slope by up to 6 dB, referenced to the lowest cell. This depends
     * only on the published band edges, never a band's recent maximum or the loudest bar in a frame.
     * Sensitivity is a power gain in dB, applied to the inverse of the shared fourth-root height
     * curve. A zero input stays zero. Caps, history, reflections and ember positions share this map.
     */
    private fun updateLevels(state: VizRenderState) {
        val bands = state.frame.bands
        if (heights.size != bands.size) {
            heights = FloatArray(bands.size)
            peaks = FloatArray(bands.size)
            octaveBalance = FloatArray(bands.size) { 1f }
            lowHz = Double.NaN
            history.clear()
        }
        if (bands.isEmpty()) return
        val power = state.frame.power?.takeIf { it.bandCount == bands.size }
        val low = power?.bandLowHz(0) ?: 0.0
        val high = power?.bandHighHz(bands.lastIndex) ?: 0.0
        if (low != lowHz || high != highHz) {
            lowHz = low
            highHz = high
            val reference = if (power != null && low > 0.0) ln(power.bandHighHz(0) / low) else 0.0
            for (index in bands.indices) {
                val bottom = power?.bandLowHz(index) ?: 0.0
                val top = power?.bandHighHz(index) ?: 0.0
                val octaves = if (bottom > 0.0 && top > bottom) ln(top / bottom) else 0.0
                octaveBalance[index] = if (octaves > 0.0 && reference > 0.0)
                    (reference / octaves).coerceIn(1.0, MAX_BALANCE_POWER).toFloat() else 1f
            }
        }
        val gain = 10f.pow(sensitivity.value / 40f)
        for (index in bands.indices) {
            val scale = gain * octaveBalance[index].pow(balance.value / 4f)
            heights[index] = displayHeight(bands[index], scale)
            peaks[index] = maxOf(heights[index], displayHeight(state.frame.peaks.getOrElse(index) { bands[index] }, scale))
        }
    }

    private fun displayHeight(value: Float, scale: Float): Float {
        if (!value.isFinite() || value <= 0f) return 0f
        val floorLift = HEIGHT_FLOOR * (scale - 1f) / (1f - HEIGHT_FLOOR)
        // Analysis has already clipped its noise floor. Fade in the lifted floor near zero so
        // amplification cannot turn a decaying, almost-zero tail into a visible plateau.
        val fade = if (floorLift > 0f) (value / 0.08f).coerceAtMost(1f) else 1f
        val raised = value.coerceAtMost(1f) * scale + floorLift * fade
        return raised.coerceIn(0f, 1f).pow(curve.value)
    }

    private fun hot(value: Float): Float = (value + (1f - value) * heat.value * activity *
        (value * 3f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        if (heights.isEmpty()) return
        meter.clear()
        val base = baseline * size.height
        val half = size.width * span() / 2f
        val middle = centreX * size.width
        for (wall in walls.value.toInt() downTo 1) {
            val scale = 1f - 0.16f * wall
            val row = history.row(wall * 0.8f)
            addEchoMeter(state, middle - half * scale, middle + half * scale,
                base - size.height * 0.08f * wall, height * size.height * scale, row, 0.4f / wall)
        }
        if (depth.value > 0f) {
            addEchoMeter(state, middle - half, middle + half, base,
                -(size.height - base) * 1.6f * depth.value, -1, 0.35f)
        }
        drawMesh(meter)
    }

    private fun addEchoMeter(state: VizRenderState, fromX: Float, toX: Float, foot: Float,
        tallest: Float, row: Int, alpha: Float) {
        val slot = (toX - fromX) / heights.size
        val low = state.palette.low.copy(alpha = alpha).toArgb()
        for (index in heights.indices) {
            val value = if (row < 0) heights[index] else history.sample(row, index.toFloat() / maxOf(1, heights.lastIndex))
            if (value < 0.003f) continue
            val left = fromX + (index + (1f - width.value) * 0.5f) * slot
            val high = state.palette.ramp(hot(value)).copy(alpha = alpha).toArgb()
            meter.bar(left, left + slot * width.value, foot - value * tallest, foot, high, low)
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (heights.isEmpty()) return
        drawRoom(state)
        val base = baseline * size.height
        val half = size.width * span() / 2f
        val middle = centreX * size.width
        val from = middle - half
        val to = middle + half
        drawOverlappingMeters(state, from, to)
        if (grid.value >= 0.5f && gridOpacity.value > 0f) drawGrid(state, from, to)
        drawMeter(state, from, to, 1f, paired = opposing.value >= 0.5f, caps = true)
        if (sides.value >= 0.5f) {
            drawMeter(state, 0f, size.width / 6f, 0.8f, reverse = true)
            drawMeter(state, size.width * 5f / 6f, size.width, 0.8f, reverse = true)
        }
        val slot = (to - from) / heights.size
        val reach = size.minDimension * 0.05f
        for (index in heights.indices) {
            if (heights[index] < 0.03f) continue
            val position = (index + 0.5f) / heights.size
            val near = maxOf(closeness(sweep.position, position),
                if (snareSweep >= 0f) closeness(snareSweep, position) else 0f)
            if (near <= 0.05f) continue
            val at = Offset(from + (index + 0.5f) * slot, base - peaks[index] * height * size.height)
            drawCircle(Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.7f * near),
                1f to Color.Transparent, center = at, radius = reach), reach, at, blendMode = BlendMode.Plus)
        }
        if (emberAmount.value > 0f) with(embers) { drawSprites(state.palette, genes.walk) }
    }

    private fun DrawScope.drawOverlappingMeters(state: VizRenderState, from: Float, to: Float) {
        if (overlapping.value < 0.5f || layerOpacity.value <= 0f) return
        val count = layerCount.value.toInt()
        // Rainbow Bar's independent, opposing drifts, with bounded transparency and the same
        // calibrated spectrum as the main meter. The closest layer is drawn last.
        for (index in count - 1 downTo 0) {
            val phase = layerPhase[index]
            val direction = if (index % 2 == 0) 1f else -1f
            val depth = (index + 1f) / count
            val offset = layerOffset.value * (0.5f + 0.5f * depth)
            val x = direction * offset + cos(phase) * offset * layerDrift.value +
                direction * fan.value * offset * 1.5f
            val y = direction * offset * 0.65f + sin(phase) * offset * layerDrift.value
            val layerScale = 1f - 0.1f * depth
            val alpha = layerOpacity.value * (1f - 0.25f * depth)
            val colourShift = direction * layerColour.value * (0.6f + 0.4f * sin(phase))
            withTransform({
                translate(x * size.width, y * size.height)
                rotate(sin(phase) * layerTilt.value, center)
                scale(layerScale, layerScale, center)
            }) {
                drawMeter(state, from, to, alpha, colourShift = colourShift)
            }
        }
    }

    // The dim and lit cells use precisely the same lattice, in the same front layer and camera.
    private fun DrawScope.drawGrid(state: VizRenderState, from: Float, to: Float) {
        meter.clear()
        val slot = (to - from) / heights.size
        val base = baseline * size.height
        val step = height * size.height / rows.value
        for (index in heights.indices) {
            val left = from + (index + (1f - width.value) * 0.5f) * slot
            for (row in 0 until rows.value.toInt()) {
                val bottom = base - row * step
                val colour = state.palette.ramp((row + 0.5f) / rows.value)
                    .copy(alpha = gridOpacity.value).toArgb()
                meter.bar(left, left + slot * width.value, bottom - step * fill.value, bottom, colour, colour)
            }
            if ((index + 1) % COLUMN_BATCH == 0) {
                drawMesh(meter)
                meter.clear()
            }
        }
        drawMesh(meter)
    }

    private fun DrawScope.drawMeter(state: VizRenderState, from: Float, to: Float, alpha: Float,
        reverse: Boolean = false, paired: Boolean = false, caps: Boolean = false, colourShift: Float = 0f) {
        meter.clear()
        val slot = (to - from) / heights.size
        val base = baseline * size.height
        val top = ceiling * size.height
        val tall = base - top
        val capHeight = maxOf(1f, size.height * 0.004f)
        for (index in heights.indices) {
            val column = if (reverse) heights.lastIndex - index else index
            val left = from + (column + (1f - width.value) * 0.5f) * slot
            val right = left + slot * width.value
            val value = heights[index]
            val head = base - value * tall
            val tint = hot(value)
            appendColumn(state, left, right, head, base, base, tall, tint + colourShift, colourShift, alpha)
            if (paired) {
                // Complementary height, not another copy of the spectrum: a rising lower bar
                // shortens its upper partner. Their cells stay aligned even at the moving gap.
                val end = maxOf(top, head - opposingGap.value * tall)
                appendColumn(state, left, right, top, end, base, tall, 0f, tint * 0.85f,
                    alpha * opposingOpacity.value * (0.25f + 0.75f * value))
            }
            if (caps && peaks[index] > 0.003f) {
                val cap = maxOf(top, base - peaks[index] * tall - capHeight)
                val colour = state.palette.cap.copy(alpha = alpha).toArgb()
                meter.bar(left, right, cap, cap + capHeight, colour, colour)
            }
            if ((index + 1) % COLUMN_BATCH == 0) {
                drawMesh(meter)
                meter.clear()
            }
        }
        drawMesh(meter)
    }

    private fun appendColumn(state: VizRenderState, left: Float, right: Float, top: Float,
        bottom: Float, gridBase: Float, gridHeight: Float, fromTint: Float, toTint: Float, alpha: Float) {
        if (bottom - top <= 0.01f || alpha <= 0f) return
        fun colour(y: Float): Int = state.palette.ramp(fromTint + (toTint - fromTint) *
            ((y - top) / (bottom - top)).coerceIn(0f, 1f)).copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
        if (segmented.value < 0.5f) {
            // One uninterrupted quad per bar; cell rows and fill have no effect in solid mode.
            meter.bar(left, right, top, bottom, colour(top), colour(bottom))
            return
        }
        val step = gridHeight / rows.value
        for (row in 0 until rows.value.toInt()) {
            val cellBottom = gridBase - row * step
            val a = maxOf(top, cellBottom - step * fill.value)
            val b = minOf(bottom, cellBottom)
            if (b > a) meter.bar(left, right, a, b, colour(a), colour(b))
        }
    }

    private fun closeness(sweepAt: Float, position: Float): Float = (1f - abs(sweepAt - position) / 0.06f).coerceIn(0f, 1f)

    // A floor below the meter and a ceiling above it, in perspective, one cell nearer every beat.
    private fun DrawScope.drawRoom(state: VizRenderState) {
        room.clear()
        val bands = heights
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
                val alpha = (0.25f + 0.5f * value) * (0.3f + 0.7f * near) * checker * floorStrength.value
                val colour = state.palette.argb(column / CELLS.toFloat() + genes.walk, value = 0.5f + 0.5f * value, alpha = alpha)
                cell(column, near, nearer, baseline, 1f, colour)
                cell(column, near, nearer, ceiling, 0f, colour)
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
        heights.fill(0f)
        peaks.fill(0f)
        activity = 0f
        snareSweep = -1f
        scroll = 0f
        fanHold = 0f
        fan.reset()
        for (index in layerPhase.indices) layerPhase[index] = index * 1.3f
        embers.clear()
        emberCredit = 0f
    }

    private companion object {
        const val MAX_ROWS = 48
        const val MAX_LAYERS = 4
        const val COLUMN_BATCH = 64
        const val HEIGHT_FLOOR = 0.05623413f
        const val MAX_BALANCE_POWER = 3.9810717055349722
        const val BASE = 0.74f
        const val CEILING = 0.16f
        const val CELLS = 16
    }
}
