package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.strip
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The meter as the front row of a city of its own past. Behind it 64 rows of the spectrum's past
 * recede into fog, one row per fifteenth of a heard second, and the walls and the ceiling reflect
 * them. A rail of the live waveform runs along the floor's front edge, embers rise from each new
 * peak, and a sweep lights the caps once a bar. In a silence the hall is empty: a floor grid and a
 * faint flat rail.
 *
 * The rows are projected through a [Scene3D]: each row is one planar rectangle, and every bar on it
 * is placed between its four projected corners. Height is a fixed artistic mapping of the shared
 * display driver, with named sensitivity, frequency balance and curve controls. No band learns its
 * own automatic gain. Each bar wears the palette by band, walked by the genes; the caps are white.
 * The room light follows the level, with a floor kept only while nothing is heard.
 *
 * The city takes four forms: Hall, Canyon (each half of the spectrum stands on one wall), Lake (the
 * rows stand apart on water and mirror below it) and Tower (the rows are floors stacked upward). A
 * morph bends every row from one form to the next over two to four cycles; on a birth the front row
 * bursts into embers. The camera cranes round the city on the slow cycle, held in a pause and
 * brought home in a silence, and it never cuts, shakes or punches.
 */
internal class Bars : Layered(
    name = "Bars",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 201L, groundKind = GroundKind.Grid, groundDim = 0.85f, detailKind = DetailKind.Dots,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 201)),
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
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.72f, livelyTrail = 0.62f, calmDriftY = 0.3f, livelyDriftY = 0.34f)
    override val post: PostSpec get() = PostSpec.Default.copy(bloom = 0.5f)

    // The drawing projects its own city, so the kit's camera stays still and the front is not moved.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

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
    private val sensitivity = VizParam("Sensitivity (dB)", -12f, 12f, 3f).apply { step = 0.5f }
    private val balance = VizParam("Frequency balance", 0f, 1f, 0.7f)
    private val curve = VizParam("Height curve", 0.5f, 1.5f, 0.8f)
    private val heat = VizParam("Activity heat", 0f, 1f, 0.65f)
    private val emberAmount = VizParam("Embers", 0f, 3f, 1f)
    private val depth = VizParam("Reflection depth", 0f, 1f, 0.7f)
    private val floorStrength = VizParam("Floor and ceiling", 0f, 1f, 1f)
    override val params: List<VizParam> = listOf(
        grid, gridOpacity, opposing, opposingOpacity, opposingGap, segmented, rows, fill, width,
        sensitivity, balance, curve, heat, emberAmount, depth, floorStrength,
    )

    private val history = History(rows = HISTORY_ROWS)
    // The history row each city row shows this frame, found once per frame because a lookup walks the rows.
    private val rowIndex = IntArray(ROW_COUNT) { -1 }

    /** Heard seconds since the drawing started, the clock of [history]. */
    private var heard = 0.0
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
    private var glow = IDLE_LIGHT
    private var railPeak = 0f
    private val embers = Sprites(400, 1_201L)
    private var emberCredit = 0f

    private val scene = Scene3D()
    private val city = TriangleMesh(CITY_VERTICES, CITY_INDICES)
    private val room = TriangleMesh(maxVertices = (MAX_FLOOR_ROWS + 1) * CELLS * 4 + 16)
    // Flush after each batch of columns, including for analysers configured above 128 bands.
    private val meter = TriangleMesh(maxVertices = COLUMN_BATCH * (2 * MAX_ROWS + 4) * 4)

    // Four projected corners of a rectangle, in pixels: foot left, foot right, head right, head left.
    private val corners = FloatArray(8)
    // A row is two halves of the spectrum, each four corners: world x, y, z, then projected x, y.
    private val formFromRow = FloatArray(24)
    private val formToRow = FloatArray(24)
    private val world = FloatArray(24)
    private val rowFrame = FloatArray(16)
    // The front row's corners as last drawn, kept so the embers and the anchors can be placed before a draw.
    private val front = FloatArray(16)
    private var frontReady = false
    private var drawnWidth = 1f
    private var drawnHeight = 1f
    private val railXs = FloatArray(RAIL_POINTS)
    private val railYs = FloatArray(RAIL_POINTS)

    private var formFrom = HALL
    private var formTo = HALL
    private var morph = 1f
    private var morphSeconds = 1f
    private val presence = Envelope(2f, 0.5f)
    private val yaw = Slew(0.25f)
    private val rise = Slew(4f)
    private val roll = Slew(0.25f)

    override val forms: FormReadout
        get() = FormReadout(FORMS[if (morph < 0.5f) formFrom else formTo], evolution.morphs, evolution.births)

    /** How far the current morph has gone, 0 to 1. */
    internal val morphValue: Float get() = morph

    /** The camera's turn round the city, in radians. */
    internal val cameraYaw: Float get() = yaw.value

    /** Starts a morph to [form], as the music's own morph does. */
    internal fun morphTo(form: Int) {
        formFrom = formTo
        formTo = form
        morph = 0f
        morphSeconds = gestures.cycleSeconds * (2f + 2f * random.next())
    }

    /** Stands the city in [form] at once, with no morph. */
    internal fun jumpTo(form: Int) {
        formFrom = form
        formTo = form
        morph = 1f
    }

    /** The world position of the head line of [row] at [band], at full height, into [into] as x, y, z. */
    internal fun barHead(row: Int, band: Int, into: FloatArray) {
        val count = heights.size.takeIf { it > 0 } ?: 48
        blendRow(row, HALF_WIDTH * kit.aspect / (16f / 9f))
        val u = bandCentre(band, count)
        val base = if (u < 0.5f) 0 else 12
        val local = if (u < 0.5f) u * 2f else (u - 0.5f) * 2f
        for (axis in 0..2) into[axis] = world[base + 9 + axis] + (world[base + 6 + axis] - world[base + 9 + axis]) * local
    }

    /** How many embers are alive. */
    internal fun emberCount(): Int = embers.live

    /** Bursts the front row into embers, as the music's own birth does. */
    internal fun birth() {
        if (!frontReady) return
        val kind = emberSprite()
        for (index in heights.indices) {
            val u = bandCentre(index, heights.size)
            embers.burst(frontX(u, peaks[index]), frontY(u, peaks[index]), BIRTH_EMBERS, 0.9f, 1.6f, 0.02f, hot(heights[index]), kind, UP, 0.5f)
        }
    }

    private fun emberSprite(): Int = when (emberKind.value) {
        0 -> Sprite.SPARK
        1 -> Sprite.GLOW
        else -> Sprite.DIAMOND
    }

    /** The height of [row] at [position], 0 to 1 across the spectrum: row 0 is the present. */
    internal fun rowLevel(row: Int, position: Float): Float =
        if (row == 0) heights.sampleAt(position) else history.sample(rowIndex[row], position)

    /** The largest swing of the rail in the last drawn frame, in the waveform's own units. */
    internal fun railAmplitude(): Float = railPeak

    /** The room light, after the flash guard. */
    internal val glowValue: Float get() = glow

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        updateLevels(state)
        val bands = heights
        activity = (0.5f * frame.mood + 0.3f * frame.density +
            0.2f * (frame.novelty / 4f).coerceIn(0f, 1f)) * frame.audible
        glow = state.lightScale.coerceIn(0f, 1f) * max(IDLE_LIGHT * (1f - frame.audible), lightFor(state.energy))
        heard += state.stepSeconds
        history.push(bands, heard)
        scroll += 4f * state.stepSeconds / gestures.beatSeconds
        sweep.advance(gestures)
        if (frame.snare > 0f) snareSweep = 0f
        if (snareSweep >= 0f) {
            snareSweep += state.stepSeconds / (gestures.cycleSeconds * 0.5f)
            if (snareSweep >= 1f) snareSweep = -1f
        }
        if (lastPeak.size != bands.size) lastPeak = FloatArray(bands.size)
        val kind = emberSprite()
        var loudest = 0
        for (index in bands.indices) {
            val peak = peaks[index]
            // An ember leaves each cap as it is pushed up to a new peak, and rises to the top edge.
            if (frontReady && peak > lastPeak[index] + 0.01f && peak > 0.12f && emberAmount.value > 0f) {
                val u = bandCentre(index, bands.size)
                val count = (emberAmount.value + random.next()).toInt()
                embers.burst(frontX(u, peak), frontY(u, peak), count, 0.7f + 0.6f * peak, 1.6f, 0.02f, hot(bands[index]), kind, UP, 0.5f)
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
            if (bands[index] < 0.03f || !frontReady) continue
            val u = bandCentre(index, bands.size)
            embers.burst(frontX(u, peaks[index]), frontY(u, peaks[index]), 1, 0.5f + 0.5f * bands[index], 1.6f, 0.02f, hot(bands[index]), kind, UP, 0.5f)
        }
        embers.advance(dt, drag = 0.2f, gravity = -0.3f)
        if (frontReady) {
            kit.place(0, frontX(sweep.position, 0f), frontY(sweep.position, 0f))
            if (bands.isNotEmpty()) {
                val u = bandCentre(loudest, bands.size)
                kit.place(1, frontX(u, peaks[loudest]), frontY(u, peaks[loudest]))
            }
        }
        for (row in 0 until ROW_COUNT) rowIndex[row] = history.row(row / ROWS_PER_SECOND)

        // A drop or a breakdown is also a morph on the same frame; only a plain morph turns the form.
        if (evolution.bloom) {
            // The drop's impact is drawn by the impacts.
        } else if (evolution.collapse) {
            // The breakdown's impact is drawn by the impacts.
        } else if (evolution.morph) {
            morphTo((formTo + 1) % FORMS.size)
        }
        if (evolution.birth) birth()
        morph = (morph + state.stepSeconds / morphSeconds).coerceAtMost(1f)

        // A pause holds the camera where it is; a silence lets the presence fall and brings it home.
        val live = if (frame.held) 0f else dt
        presence.advance(frame.audible, live)
        val reach = presence.value * state.motionScale
        val phase = gestures.slowCyclePhase * TAU
        val shown = eased()
        val riseReach = RISE_REACH[formFrom] + (RISE_REACH[formTo] - RISE_REACH[formFrom]) * shown
        yaw.advance(YAW_REACH * sin(phase) * reach, live)
        rise.advance(riseReach * (0.5f - 0.5f * cos(phase)) * reach, live)
        roll.advance(ROLL_REACH * sin(phase + 1f) * reach, live)
    }

    private fun eased(): Float = morph * morph * (3f - 2f * morph)

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

    private fun bandCentre(index: Int, count: Int): Float = (index + 0.5f) / count

    // A point on the front row as a share of the canvas, for the sprites and the anchors.
    private fun frontX(u: Float, v: Float): Float = rowX(front, u, v) / drawnWidth
    private fun frontY(u: Float, v: Float): Float = rowY(front, u, v) / drawnHeight

    // The echo layer only fades: the whole city is drawn sharp, through its own projection.
    override fun DrawScope.drawEcho(state: VizRenderState) {}

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (heights.isEmpty()) return
        scene.lens(size, FOV, NEAR, FAR)
        val orbit = EYE_Z - AIM_Z
        scene.camera(sin(yaw.value) * orbit, EYE_Y + rise.value, cos(yaw.value) * orbit + AIM_Z,
            0f, AIM_Y + rise.value * 0.5f, AIM_Z, roll.value)
        val half = HALF_WIDTH * kit.aspect / (16f / 9f)
        blendRow(0, half)
        frontReady = projectRow(1f, 0f, 1f, 0f, front)
        drawnWidth = size.width.coerceAtLeast(1f)
        drawnHeight = size.height.coerceAtLeast(1f)
        drawRoom(state, half)
        drawCity(state, half)
        if (frontReady) {
            if (grid.value >= 0.5f && gridOpacity.value > 0f) drawGrid(state)
            drawMeter(state)
            drawSweeps(state)
        }
        drawRail(state, half)
        if (emberAmount.value > 0f) with(embers) { drawSprites(state.palette, genes.walk, alpha = glow) }
    }

    /** Projects a rectangle standing at depth [z] into [into]; false when a corner cannot be projected. */
    private fun rowCorners(left: Float, right: Float, foot: Float, head: Float, z: Float, into: FloatArray): Boolean {
        if (!scene.project(left, foot, z)) return false
        into[0] = scene.screenX
        into[1] = scene.screenY
        if (!scene.project(right, foot, z)) return false
        into[2] = scene.screenX
        into[3] = scene.screenY
        if (!scene.project(right, head, z)) return false
        into[4] = scene.screenX
        into[5] = scene.screenY
        if (!scene.project(left, head, z)) return false
        into[6] = scene.screenX
        into[7] = scene.screenY
        return true
    }

    // One form's world corners for [row]: two halves, four corners each, x y z per corner.
    private fun formRow(form: Int, row: Int, half: Float, into: FloatArray) {
        when (form) {
            CANYON -> {
                val z = -row * ROW_STEP
                val wall = half * 0.5f
                // Each half stands up its own wall, bands rising by height, bars pointing inward.
                corner(into, 0, -wall, 0f, z)
                corner(into, 3, -wall, CANYON_TALL, z)
                corner(into, 6, -wall + CANYON_REACH, CANYON_TALL, z)
                corner(into, 9, -wall + CANYON_REACH, 0f, z)
                corner(into, 12, wall, 0f, z)
                corner(into, 15, wall, CANYON_TALL, z)
                corner(into, 18, wall - CANYON_REACH, CANYON_TALL, z)
                corner(into, 21, wall - CANYON_REACH, 0f, z)
            }
            TOWER -> {
                val foot = row * FLOOR_STEP
                rect(into, -half, half, foot, foot + BAR_HEIGHT * 0.5f, -row * TOWER_STEP)
            }
            LAKE -> rect(into, -half * LAKE_SPREAD, half * LAKE_SPREAD, 0f, BAR_HEIGHT, -row * LAKE_STEP)
            else -> rect(into, -half, half, 0f, BAR_HEIGHT, -row * ROW_STEP)
        }
    }

    private fun rect(into: FloatArray, left: Float, right: Float, foot: Float, head: Float, z: Float) {
        corner(into, 0, left, foot, z)
        corner(into, 3, 0f, foot, z)
        corner(into, 6, 0f, head, z)
        corner(into, 9, left, head, z)
        corner(into, 12, 0f, foot, z)
        corner(into, 15, right, foot, z)
        corner(into, 18, right, head, z)
        corner(into, 21, 0f, head, z)
    }

    private fun corner(into: FloatArray, at: Int, x: Float, y: Float, z: Float) {
        into[at] = x
        into[at + 1] = y
        into[at + 2] = z
    }

    // The world corners of [row] as shown: the two forms of the morph, blended.
    private fun blendRow(row: Int, half: Float) {
        formRow(formFrom, row, half, formFromRow)
        if (formTo == formFrom) {
            formFromRow.copyInto(world)
            return
        }
        formRow(formTo, row, half, formToRow)
        val shown = eased()
        for (index in world.indices) world[index] = formFromRow[index] + (formToRow[index] - formFromRow[index]) * shown
    }

    /** Projects [world], each point mapped to (sx * x + ox, sy * y + oy, z), into [into]; false when a corner cannot be. */
    private fun projectRow(sx: Float, ox: Float, sy: Float, oy: Float, into: FloatArray): Boolean {
        for (point in 0 until 8) {
            if (!scene.project(sx * world[point * 3] + ox, sy * world[point * 3 + 1] + oy, world[point * 3 + 2])) return false
            into[point * 2] = scene.screenX
            into[point * 2 + 1] = scene.screenY
        }
        return true
    }

    // A point on a projected row: u runs across the whole spectrum and picks the half, v up from the foot.
    private fun rowX(c: FloatArray, u: Float, v: Float): Float =
        if (u < 0.5f) halfX(c, 0, u * 2f, v) else halfX(c, 8, (u - 0.5f) * 2f, v)

    private fun rowY(c: FloatArray, u: Float, v: Float): Float =
        if (u < 0.5f) halfX(c, 1, u * 2f, v) else halfX(c, 9, (u - 0.5f) * 2f, v)

    // Bilinear inside one half: [at] is the half's first float plus 0 for x or 1 for y.
    private fun halfX(c: FloatArray, at: Int, u: Float, v: Float): Float {
        val foot = c[at] + (c[at + 2] - c[at]) * u
        return foot + (c[at + 6] + (c[at + 4] - c[at + 6]) * u - foot) * v
    }

    // A bar's cell on a projected row. Both of its edges lie in the half that holds its centre.
    private fun TriangleMesh.barCell(c: FloatArray, u0: Float, u1: Float, v0: Float, v1: Float, argb: Int) {
        val lower = (u0 + u1) < 1f
        val at = if (lower) 0 else 8
        val shift = if (lower) 0f else 0.5f
        val l0 = (u0 - shift) * 2f
        val l1 = (u1 - shift) * 2f
        val a = vertex(halfX(c, at, l0, v0), halfX(c, at + 1, l0, v0), argb)
        val b = vertex(halfX(c, at, l1, v0), halfX(c, at + 1, l1, v0), argb)
        val d = vertex(halfX(c, at, l1, v1), halfX(c, at + 1, l1, v1), argb)
        val e = vertex(halfX(c, at, l0, v1), halfX(c, at + 1, l0, v1), argb)
        quad(a, b, d, e)
    }

    // Bilinear between a rectangle's corners: u runs across, v up from the foot.
    private fun pointX(c: FloatArray, u: Float, v: Float): Float {
        val foot = c[0] + (c[2] - c[0]) * u
        return foot + (c[6] + (c[4] - c[6]) * u - foot) * v
    }

    private fun pointY(c: FloatArray, u: Float, v: Float): Float {
        val foot = c[1] + (c[3] - c[1]) * u
        return foot + (c[7] + (c[5] - c[7]) * u - foot) * v
    }

    private fun barColour(state: VizRenderState, band: Int, count: Int, level: Float, alpha: Float): Int =
        state.palette.argb(band / max(1f, count - 1f) + genes.walk, saturation = 0.6f + 0.4f * level,
            value = 0.45f + 0.55f * level, alpha = alpha)

    // The rows of the past, far to near: the lake's mirror and the wall and ceiling reflections first.
    private fun DrawScope.drawCity(state: VizRenderState, half: Float) {
        val light = state.lightScale.coerceIn(0f, 1f)
        val shown = eased()
        val lake = (if (formFrom == LAKE) 1f - shown else 0f) + (if (formTo == LAKE) shown else 0f)
        val wall = half + WALL_GAP
        val mirror = BAR_HEIGHT + CEILING_GAP
        city.clear()
        for (row in ROW_COUNT - 1 downTo 1) {
            val index = rowIndex[row]
            if (index < 0) continue
            blendRow(row, half)
            val alpha = scene.fog(EYE_Z - world[2]) * (0.25f * light + 0.75f * glow)
            if (alpha <= 0.003f) continue
            if (lake > 0.01f && projectRow(1f, 0f, -1f, 0f, rowFrame)) addRow(state, index, LAKE_ALPHA * lake * alpha)
            if (depth.value <= 0f || row % 2 != 0) continue
            // Mirrored in x about each wall, so the band order reverses, and in y about the ceiling.
            if (projectRow(-1f, 2f * wall, 1f, 0f, rowFrame)) addRow(state, index, WALL_ALPHA * depth.value * alpha)
            if (projectRow(-1f, -2f * wall, 1f, 0f, rowFrame)) addRow(state, index, WALL_ALPHA * depth.value * alpha)
            if (projectRow(1f, 0f, -1f, 2f * mirror, rowFrame)) addRow(state, index, CEILING_ALPHA * depth.value * alpha)
        }
        for (row in ROW_COUNT - 1 downTo 1) {
            val index = rowIndex[row]
            if (index < 0) continue
            blendRow(row, half)
            val alpha = scene.fog(EYE_Z - world[2]) * (0.25f * light + 0.75f * glow)
            if (alpha <= 0.003f) continue
            if (projectRow(1f, 0f, 1f, 0f, rowFrame)) addRow(state, index, alpha)
        }
        drawMesh(city)
    }

    private fun DrawScope.addRow(state: VizRenderState, index: Int, alpha: Float) {
        val count = heights.size
        if (city.vertexCount + 4 * count > city.maxVertices) {
            drawMesh(city)
            city.clear()
        }
        val wide = width.value
        for (band in 0 until count) {
            val level = history.sample(index, band / max(1f, count - 1f))
            if (level < 0.003f) continue
            val u0 = (band + (1f - wide) * 0.5f) / count
            city.barCell(rowFrame, u0, u0 + wide / count, 0f, level, barColour(state, band, count, level, alpha))
        }
    }

    // The faint cell lattice of the front row, on exactly the cells the meter lights.
    private fun DrawScope.drawGrid(state: VizRenderState) {
        meter.clear()
        val count = heights.size
        val cells = rows.value.toInt()
        val step = 1f / rows.value
        val wide = width.value
        val alpha = gridOpacity.value * glow
        for (band in 0 until count) {
            val u0 = (band + (1f - wide) * 0.5f) / count
            val colour = state.palette.argb(band / max(1f, count - 1f) + genes.walk, value = 0.5f, alpha = alpha)
            for (cell in 0 until cells) meter.barCell(front, u0, u0 + wide / count, cell * step, cell * step + step * fill.value, colour)
            if ((band + 1) % COLUMN_BATCH == 0) {
                drawMesh(meter)
                meter.clear()
            }
        }
        drawMesh(meter)
    }

    // The front row: the meter itself, its complementary bar above and its white caps.
    private fun DrawScope.drawMeter(state: VizRenderState) {
        meter.clear()
        val count = heights.size
        val wide = width.value
        val paired = opposing.value >= 0.5f
        val white = Color.White.copy(alpha = glow.coerceIn(0f, 1f)).toArgb()
        for (band in 0 until count) {
            val u0 = (band + (1f - wide) * 0.5f) / count
            val u1 = u0 + wide / count
            val value = heights[band]
            appendColumn(u0, u1, 0f, value, barColour(state, band, count, hot(value), glow))
            if (paired) {
                // Complementary height, not another copy of the spectrum: a rising lower bar
                // shortens its upper partner. Their cells stay aligned even at the moving gap.
                val alpha = glow * opposingOpacity.value * (0.25f + 0.75f * value)
                appendColumn(u0, u1, min(1f, value + opposingGap.value), 1f, barColour(state, band, count, hot(value) * 0.85f, alpha))
            }
            if (peaks[band] > 0.003f) {
                val cap = min(1f - CAP, peaks[band])
                meter.barCell(front, u0, u1, cap, cap + CAP, white)
            }
            if ((band + 1) % COLUMN_BATCH == 0) {
                drawMesh(meter)
                meter.clear()
            }
        }
        drawMesh(meter)
    }

    private fun appendColumn(u0: Float, u1: Float, from: Float, to: Float, argb: Int) {
        if (to - from <= 0.001f) return
        if (segmented.value < 0.5f) {
            // One uninterrupted quad per bar; cell rows and fill have no effect in solid mode.
            meter.barCell(front, u0, u1, from, to, argb)
            return
        }
        val step = 1f / rows.value
        for (cell in 0 until rows.value.toInt()) {
            val a = max(from, cell * step)
            val b = min(to, cell * step + step * fill.value)
            if (b > a) meter.barCell(front, u0, u1, a, b, argb)
        }
    }

    private fun DrawScope.drawSweeps(state: VizRenderState) {
        val count = heights.size
        val reach = size.minDimension * 0.05f
        for (band in 0 until count) {
            if (heights[band] < 0.03f) continue
            val u = bandCentre(band, count)
            val near = maxOf(closeness(sweep.position, u), if (snareSweep >= 0f) closeness(snareSweep, u) else 0f)
            if (near <= 0.05f) continue
            val at = Offset(rowX(front, u, peaks[band]), rowY(front, u, peaks[band]))
            drawCircle(Brush.radialGradient(0f to state.palette.cap.copy(alpha = 0.7f * near * glow),
                1f to Color.Transparent, center = at, radius = reach), reach, at, blendMode = BlendMode.Plus)
        }
    }

    private fun closeness(sweepAt: Float, position: Float): Float = (1f - abs(sweepAt - position) / 0.06f).coerceIn(0f, 1f)

    // The live waveform along the floor's front edge, just in front of the meter.
    private fun DrawScope.drawRail(state: VizRenderState, half: Float) {
        railPeak = 0f
        val scope = state.frame.scope
        if (scope.isEmpty() || !rowCorners(-half, half, 0f, RAIL_HEIGHT, RAIL_Z, corners)) return
        for (point in 0 until RAIL_POINTS) {
            val u = point / (RAIL_POINTS - 1f)
            val swing = scope.sampleAt(u)
            railPeak = max(railPeak, abs(swing))
            railXs[point] = pointX(corners, u, swing)
            railYs[point] = pointY(corners, u, swing)
        }
        city.clear()
        val colour = Color.White.copy(alpha = ((0.6f + 0.4f * railPeak.coerceAtMost(1f)) * glow).coerceIn(0f, 1f)).toArgb()
        city.strip(railXs, railYs, RAIL_POINTS, max(1.5f, size.height * 0.004f), colour)
        drawMesh(city)
    }

    // A checkered floor under the city and in front of it, lit by the bands, one cell nearer every beat.
    private fun DrawScope.drawRoom(state: VizRenderState, half: Float) {
        room.clear()
        val bands = heights
        val deep = 4 + 2 * floorDensity.value
        val frac = scroll - floor(scroll)
        val scrolled = floor(scroll).toInt()
        val back = FLOOR_FRONT - deep * FLOOR_CELL
        for (column in 0 until CELLS) {
            val value = bands.sampleAt((column + 0.5f) / CELLS)
            val x0 = -half + 2f * half * (column + FLOOR_GAP) / CELLS
            val x1 = -half + 2f * half * (column + 1 - FLOOR_GAP) / CELLS
            for (row in 0..deep) {
                val near = (FLOOR_FRONT - (row - frac) * FLOOR_CELL).coerceIn(back, FLOOR_FRONT)
                val far = (near - FLOOR_CELL).coerceIn(back, FLOOR_FRONT)
                if (near - far <= 0.01f) continue
                // A checkerboard that travels with the cells, so the whole floor runs rather than only its lines.
                val checker = if (((row - scrolled + column) % 2 + 2) % 2 == 0) 1f else 0.2f
                val closeness = 1f - row / deep.toFloat()
                val alpha = (0.25f + 0.5f * value) * (0.3f + 0.7f * closeness) * checker * floorStrength.value * glow
                val colour = state.palette.argb(column / CELLS.toFloat() + genes.walk, value = 0.5f + 0.5f * value, alpha = alpha)
                if (!rowCorners(x0, x1, 0f, 0f, near, corners)) continue
                val a = room.vertex(corners[0], corners[1], colour)
                val b = room.vertex(corners[2], corners[3], colour)
                if (!scene.project(x1, 0f, far)) continue
                val c = room.vertex(scene.screenX, scene.screenY, colour)
                if (!scene.project(x0, 0f, far)) continue
                val d = room.vertex(scene.screenX, scene.screenY, colour)
                room.quad(a, b, c, d)
            }
        }
        drawMesh(room, BlendMode.Plus)
    }

    override fun onReset() {
        history.clear()
        rowIndex.fill(-1)
        heard = 0.0
        lastPeak.fill(0f)
        heights.fill(0f)
        peaks.fill(0f)
        activity = 0f
        snareSweep = -1f
        scroll = 0f
        glow = IDLE_LIGHT
        railPeak = 0f
        embers.clear()
        emberCredit = 0f
        city.clear()
        frontReady = false
        formFrom = HALL
        formTo = HALL
        morph = 1f
        morphSeconds = 1f
        presence.reset()
        yaw.reset()
        rise.reset()
        roll.reset()
    }

    internal companion object {
        /** The share of the room light kept while nothing is heard. */
        const val IDLE_LIGHT = 0.3f

        val FORMS = listOf("Hall", "Canyon", "Lake", "Tower")
        const val HALL = 0
        const val CANYON = 1
        const val LAKE = 2
        const val TOWER = 3

        private const val CANYON_TALL = 14f
        private const val CANYON_REACH = 7f
        private const val LAKE_STEP = 1.6f
        private const val LAKE_SPREAD = 1.25f
        private const val LAKE_ALPHA = 0.4f
        private const val FLOOR_STEP = 0.6f
        private const val TOWER_STEP = 0.25f
        private const val BIRTH_EMBERS = 3
        private const val YAW_REACH = 0.35f
        private const val ROLL_REACH = 0.02f
        private val RISE_REACH = floatArrayOf(3f, 2f, 3f, 12f)

        private const val MAX_ROWS = 48
        private const val COLUMN_BATCH = 64
        private const val HEIGHT_FLOOR = 0.05623413f
        private const val MAX_BALANCE_POWER = 3.9810717055349722
        private const val CELLS = 16
        private const val MAX_FLOOR_ROWS = 9
        private const val FLOOR_FRONT = 8f
        private const val FLOOR_CELL = 8f
        private const val FLOOR_GAP = 0.16f

        private const val ROW_COUNT = 64
        private const val ROWS_PER_SECOND = 15f
        private const val HISTORY_ROWS = 256
        private const val ROW_STEP = 1f
        private const val HALF_WIDTH = 16f
        private const val BAR_HEIGHT = 10f
        private const val CAP = 0.015f
        private const val WALL_GAP = 2f
        private const val CEILING_GAP = 2f
        private const val WALL_ALPHA = 0.5f
        private const val CEILING_ALPHA = 0.36f
        private const val EYE_Y = 20f
        private const val EYE_Z = 22f
        private const val AIM_Y = 1f
        private const val AIM_Z = -20f
        private const val FOV = 55f
        private const val NEAR = 1f
        private const val FAR = 110f
        private const val RAIL_HEIGHT = 0.8f
        private const val RAIL_Z = 0.5f
        private const val RAIL_POINTS = 128
        private const val CITY_VERTICES = 16_384
        private const val CITY_INDICES = 24_576
    }
}
