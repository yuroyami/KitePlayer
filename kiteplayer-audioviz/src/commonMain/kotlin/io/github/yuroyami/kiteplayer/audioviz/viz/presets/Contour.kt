package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.vividColour
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A topographic map of an archipelago seen straight down: neon contour lines on black, a white
 * coastline at sea level and a thicker index line every fourth level. Thirty two islands sit on a
 * spiral, bass in the middle and treble at the edges, and a loud band raises its island, so new
 * lines appear at the summit and ripple outward. Louder music lowers the sea. Onsets and kicks send
 * rings through the water and hats make the edge islets flicker. A section raises a new
 * archipelago, a breakdown brings high tide, and a drop brings low tide for a bar. The camera never moves.
 */
internal class Contour : ShaderPreset(
    source = ContourShader.SOURCE,
    name = "Contour",
    bucket = VizEnergy.Calm,
    seed = 4.03f,
    kit = Kit(seed = 403L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false,
        minZoom = 1f, maxZoom = 1f, seed = 403)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(RISE_SECONDS)),
        VizDrive(VizDriver.Onset, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(RING_SECONDS)),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.spring(0.4f)),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(RING_SECONDS)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(FLICKER_SECONDS)),
        VizDrive(VizDriver.Level, VizProperty.Shape, response = VizResponse.envelope(SEA_SECONDS)),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Drop, VizProperty.Colour, VizCurve.Discrete, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Drop, VizProperty.Brightness, VizCurve.Discrete, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        silence = VizSilence.Still,
    )

    // Sharp lines on black are the look, so the only glow is the one the shader puts on the summits.
    override val post: PostSpec get() = PostSpec.Off
    override val hasFallback: Boolean get() = true
    override val frontParallax: Float get() = 0f
    override val fallbackParallax: Float get() = 0f
    override val useRuntimeShader: Boolean get() = !forcePortable

    /** Draws the canvas stand-in even where shaders run, so a test can see what an older phone shows. */
    internal var forcePortable: Boolean = false

    // The genes shape the next archipelago; the one on the map keeps its layout until a section.
    private val turns = genes.number("Spiral turns", 2.4f, 3.2f, 2.8f)
    private val clockwise = genes.toggle("Clockwise spiral", start = true)
    private val spread = genes.number("Island size", 0.85f, 1.2f, 1f)
    private val relief = genes.number("Resting relief", 0.6f, 1.3f, 1f)

    /** Each band's lift, 0 to 1, with a fast rise and a slow fall. Island k stands on bands 2k and 2k + 1. */
    private val lifted = FloatArray(BANDS)
    private val wanted = FloatArray(BANDS)
    /** A slower follower of the lift, so an onset can find the band that just rose. */
    private val settled = FloatArray(BANDS)
    private val flicker = FloatArray(ISLANDS)
    private val jump = Spring(stiffness = 220f, damping = 0.45f)
    private var from = Archipelago()
    private var to = Archipelago()
    private var morph = 1f
    private val rings = Ripples(RINGS)
    private var seaEnergy = 0f
    private var fresh = true
    private var highTide = 0f
    private var highHeld = false
    private var lowClock = -1f
    private var lowBeat = 0.5f
    private var lowBar = 2f
    private val keyLean = Slew(maxPerSecond = KEY_TURN_PER_SECOND)

    private var columns = LONG
    private var rows = SHORT
    private var halfX = LONG / 2f
    private var halfY = SHORT / 2f
    /** The ground in levels, one value per cell, row by row: seabed, shelves and islands. */
    private val terrain = FloatArray(CELLS)
    /** The terrain with the rings added: what is drawn. */
    private val field = FloatArray(CELLS)
    /** How brightly each cell's lines flicker, 0 to 1. */
    private val shimmer = FloatArray(CELLS)
    private val landscape by lazy { PixelImage(LONG, SHORT) }
    private val portrait by lazy { PixelImage(SHORT, LONG) }
    private var built = 0L
    private var uploaded = -1L
    private var uploadedTo: PixelImage? = null
    private var canvasWidth = 1f
    private var canvasHeight = 1f

    private var seaLevel = SEA_QUIET
    private var tallestPeak = 0f
    private var amber = 0f
    private var boost = 0f
    private var light = IDLE_LIGHT
    private val deep = FloatArray(3)
    private val shallow = FloatArray(3)
    private val land = FloatArray(3)

    init {
        layOut(to)
        from.copyFrom(to)
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        // Audible seconds: zero while paused or silent, so the map holds its last heights then.
        val dt = state.stepSeconds
        val bar = gestures.cycleSeconds
        frameFor(kit.aspect)
        readBands(frame.bandsRel, dt)

        if (gestures.turn) {
            highHeld = gestures.breakdown
            if (morph >= 1f) newArchipelago()
        }
        morph = (morph + dt / bar).coerceAtMost(1f)
        highTide = (highTide + (if (highHeld) 1f else -1f) * dt / bar).coerceIn(0f, 1f)
        if (gestures.surge) {
            lowClock = 0f
            lowBeat = gestures.beatSeconds
            lowBar = bar
        } else if (lowClock >= 0f) {
            lowClock += dt
            if (lowClock >= lowBeat + 2f * lowBar) lowClock = -1f
        }

        val shown = if (morph >= 0.5f) to else from
        shape(shown)
        if (gestures.kick > 0f) {
            jump.kick(gestures.kick * JUMP_KICK)
            ring(columns / 2f, rows / 2f, KICK_RING * (0.6f + 0.4f * gestures.kick), KICK_WIDTH, 3f, 1.6f)
        }
        jump.advance(dt)
        val onset = onsetIn(frame)
        if (onset > 0f) {
            val island = firedIsland()
            ring(shown.x[island], shown.y[island], ONSET_RING * (0.6f + 0.4f * onset), ONSET_WIDTH, 5f, 1.2f)
        }
        if (gestures.hat > 0f) {
            val accent = gestures.hatAccent.coerceAtMost(1f)
            for (island in EDGE until ISLANDS) {
                if (random.next() < 0.5f) flicker[island] = max(flicker[island], accent * (0.5f + 0.5f * random.next()))
            }
        }
        val fade = exp(-dt / FLICKER_SECONDS)
        for (island in EDGE until ISLANDS) flicker[island] *= fade
        rings.advance(dt)

        raiseGround()
        tide(state, dt)
        terrain.copyInto(field, 0, 0, columns * rows)
        rings.addTo(field, terrain, columns, rows, seaLevel)
        built++
        paint(state, dt)
        fresh = false
    }

    /** Picks the grid's orientation and how much of it a frame [aspect] wide shows, in cells. */
    private fun frameFor(aspect: Float) {
        val wide = aspect >= 1f
        columns = if (wide) LONG else SHORT
        rows = if (wide) SHORT else LONG
        halfX = min(columns.toFloat(), aspect * rows) / 2f
        halfY = min(columns / aspect, rows.toFloat()) / 2f
    }

    private fun readBands(bands: FloatArray, dt: Float) {
        for (band in 0 until BANDS) {
            val loud = if (bands.isEmpty()) 0f else spanOf(bands, band)
            // A fixed tilt towards the treble, because a mix carries far less energy up there.
            val tilt = 1f + TILT * band / (BANDS - 1f)
            val target = ((loud * tilt - BAND_FLOOR) / (BAND_FULL - BAND_FLOOR)).coerceIn(0f, 1f)
            wanted[band] = target
            if (fresh) {
                lifted[band] = target
                settled[band] = target
                continue
            }
            val rate = if (target > lifted[band]) RISE_PER_SECOND else FALL_PER_SECOND
            lifted[band] += (target - lifted[band]) * (rate * dt).coerceAtMost(1f)
            settled[band] += (target - settled[band]) * (SETTLE_PER_SECOND * dt).coerceAtMost(1f)
        }
    }

    /** The mean height of the analyser's bands under one sixty-fourth of the spectrum. */
    private fun spanOf(bands: FloatArray, band: Int): Float {
        if (bands.size == 1) return bands[0]
        var sum = 0f
        for (tap in 0 until 3) {
            val at = (band + (tap + 0.5f) / 3f) / BANDS * (bands.size - 1)
            val low = at.toInt().coerceIn(0, bands.size - 2)
            sum += bands[low] + (bands[low + 1] - bands[low]) * (at - low)
        }
        return sum / 3f
    }

    /** The old archipelago starts to sink and a new one to rise, over one bar. */
    private fun newArchipelago() {
        val old = from
        from = to
        to = old
        layOut(to)
        morph = 0f
    }

    private fun layOut(into: Archipelago) {
        val start = random.next() * TAU
        val hand = if (clockwise.on) 1f else -1f
        val laps = turns.target
        for (island in 0 until ISLANDS) {
            val along = (island + 0.5f) / ISLANDS
            // Evenly spaced along an Archimedean spiral: out and round both grow with the square root.
            val out = sqrt(along)
            into.reach[island] = (out + 0.035f * random.signed()).coerceIn(0.06f, 1f)
            into.angle[island] = start + hand * TAU * laps * out + 0.1f * random.signed()
            into.size[island] = spread.target * (0.3f - 0.1f * along) * (0.8f + 0.4f * random.next())
            // The island's two bands stand side by side, so its shape leans with their balance.
            into.lean[island] = random.next() * TAU
            val tall = if (random.next() < 0.2f) 1f else 0f
            into.rest[island] = relief.target * (2.8f + 2.2f * random.next() + tall)
        }
        into.seed = (random.next() * 100_000f).toInt()
        into.shapedColumns = 0
    }

    /**
     * Lays [layout] onto the grid for this frame's shape: where its islands sit in cells, and the
     * parts of the ground the music does not move, which are only rebuilt when the layout or the
     * frame changes.
     */
    private fun shape(layout: Archipelago) {
        if (layout.shapedColumns == columns && layout.shapedHalfX == halfX && layout.shapedHalfY == halfY) return
        layout.shapedColumns = columns
        layout.shapedHalfX = halfX
        layout.shapedHalfY = halfY
        val shortSide = min(halfX, halfY)
        for (island in 0 until ISLANDS) {
            val c = cos(layout.angle[island])
            val s = sin(layout.angle[island])
            // Pushed out towards the corners, so the spiral fills a rectangle rather than an ellipse.
            val square = 1f / sqrt(sqrt(c * c * c * c + s * s * s * s))
            layout.x[island] = columns / 2f + layout.reach[island] * c * square * halfX * MARGIN
            layout.y[island] = rows / 2f + layout.reach[island] * s * square * halfY * MARGIN
            layout.radius[island] = layout.size[island] * shortSide
        }
        val cells = columns * rows
        for (cell in 0 until cells) {
            val x = cell % columns + 0.5f - columns / 2f
            val y = cell / columns + 0.5f - rows / 2f
            layout.relief[cell] = SEABED_LOW + SEABED_SWELL * noise(x / SEABED_SCALE, y / SEABED_SCALE, layout.seed)
            layout.grain[cell] = 0.7f + 0.6f * noise(x / GRAIN_SCALE, y / GRAIN_SCALE, layout.seed + 7)
        }
        // Each island stands on a shelf twice its width, so the sea round it shoals in wide steps.
        for (island in 0 until ISLANDS) {
            dome(layout.relief, layout.grain, layout.x[island], layout.y[island],
                layout.radius[island] * SHELF_WIDTH, layout.rest[island], null, 0f)
        }
    }

    /** What the bands add to one side of an island, in levels. */
    private fun heightOf(band: Int): Float {
        val island = band / 2
        var height = BAND_TOP * lifted[band].pow(0.85f)
        if (island < MIDDLE) height += JUMP_LEVELS * jump.value * (1f - island / MIDDLE.toFloat())
        if (island >= EDGE) height += TWITCH_LEVELS * flicker[island]
        return height
    }

    private fun raiseGround() {
        val cells = columns * rows
        terrain.fill(0f, 0, cells)
        shimmer.fill(0f, 0, cells)
        val m = smooth(morph)
        if (m < 1f) raise(from, 1f - m)
        raise(to, m)
        var tallest = 0f
        var at = 0
        for (cell in 0 until cells) {
            val height = softTop(terrain[cell])
            terrain[cell] = height
            if (height > tallest) {
                tallest = height
                at = cell
            }
        }
        tallestPeak = tallest
        kit.place(0, 0.5f + ((at % columns) + 0.5f - columns / 2f) / (2f * halfX),
            0.5f + ((at / columns) + 0.5f - rows / 2f) / (2f * halfY), parallax = 0f)
        kit.place(1, 0.5f, 0.5f, parallax = 0f)
    }

    /** Heights past [SOFT_FROM] bend towards the top of the range rather than flattening into a plateau. */
    private fun softTop(height: Float): Float {
        if (height <= SOFT_FROM) return height
        val room = TOP - SOFT_FROM
        return SOFT_FROM + room * (1f - exp(-(height - SOFT_FROM) / room))
    }

    /** Adds [layout] to the ground, scaled by [weight]: its still relief, then each island's two bands. */
    private fun raise(layout: Archipelago, weight: Float) {
        if (weight <= 0f) return
        shape(layout)
        val cells = columns * rows
        for (cell in 0 until cells) terrain[cell] += weight * layout.relief[cell]
        for (band in 0 until BANDS) {
            val island = band / 2
            val radius = layout.radius[island]
            val side = if (band % 2 == 0) SIDE_OFFSET else -SIDE_OFFSET
            val x = layout.x[island] + cos(layout.lean[island]) * side * radius
            val y = layout.y[island] + sin(layout.lean[island]) * side * radius
            val glint = if (island >= EDGE) flicker[island] * weight else 0f
            dome(terrain, layout.grain, x, y, radius * BAND_WIDTH, heightOf(band) * weight, shimmer, glint)
        }
    }

    /**
     * Adds a smooth dome [height] levels high and [radius] cells wide to [into], its height taken by
     * each cell's [grain] so no two coasts are the same shape. With [glow] above zero it also lights
     * [light] where it stands.
     */
    private fun dome(into: FloatArray, grain: FloatArray, centreX: Float, centreY: Float, radius: Float,
                     height: Float, light: FloatArray?, glow: Float) {
        if (height == 0f && glow <= 0f) return
        val left = floor(centreX - radius).toInt().coerceAtLeast(0)
        val right = floor(centreX + radius).toInt().coerceAtMost(columns - 1)
        val top = floor(centreY - radius).toInt().coerceAtLeast(0)
        val bottom = floor(centreY + radius).toInt().coerceAtMost(rows - 1)
        val reach = radius * radius
        for (y in top..bottom) {
            val dy = y + 0.5f - centreY
            val row = y * columns
            for (x in left..right) {
                val dx = x + 0.5f - centreX
                val distance = dx * dx + dy * dy
                if (distance >= reach) continue
                // Wendland's dome: round at the summit and flat where it meets the ground.
                val q = sqrt(distance) / radius
                val fall = (1f - q) * (1f - q)
                val shape = fall * fall * (4f * q + 1f)
                into[row + x] += height * shape * grain[row + x]
                if (light != null && glow > 0f) light[row + x] = max(light[row + x], glow * shape)
            }
        }
    }

    /** Where the sea stands: louder music lowers it, a breakdown floods it and a drop drains it. */
    private fun tide(state: VizRenderState, dt: Float) {
        seaEnergy = if (fresh) state.energy else seaEnergy + (state.energy - seaEnergy) * (dt / SEA_SECONDS).coerceAtMost(1f)
        val calm = SEA_QUIET + (SEA_LOUD - SEA_QUIET) * seaEnergy
        // High tide leaves only the tallest peaks above the water.
        val high = max(calm, tallestPeak - HIGH_TIDE_BELOW)
        var sea = calm + (high - calm) * smooth(highTide)
        val low = lowTideNow()
        val out = max(low, buildUp(state))
        // Under reduced motion the coastline all but stays put, and low tide is a change of colour and light.
        sea += (SEA_DRY - sea) * out * state.motionScale * state.motionScale
        seaLevel = sea
        amber = out
        boost = LOW_TIDE_LIGHT * low
        light = state.lightScale * (IDLE_LIGHT + (1f - IDLE_LIGHT) * ((lightFor(state.energy) - 0.06f) / 0.94f).coerceIn(0f, 1f))
    }

    /** Low tide after a drop: out in one beat, held for a bar, back over the next bar. */
    private fun lowTideNow(): Float {
        val t = lowClock
        return when {
            t < 0f -> 0f
            t < lowBeat -> smooth(t / lowBeat)
            t < lowBeat + lowBar -> 1f
            else -> 1f - smooth((t - lowBeat - lowBar) / lowBar)
        }
    }

    /** When the analysed audio ahead holds a drop, the sea starts to go out within the last bar. */
    private fun buildUp(state: VizRenderState): Float {
        val next = state.future?.nextEvent(AudioEventKind.Drop) ?: return 0f
        val bar = gestures.cycleSeconds
        if (next.secondsUntil > bar) return 0f
        return BUILD_UP * (1f - next.secondsUntil / bar).coerceIn(0f, 1f)
    }

    /** The strongest onset delivered this frame, 0 when there is none. */
    private fun onsetIn(frame: SpectrumFrame): Float {
        var strongest = 0f
        val delivery = frame.events
        val detections = frame.detections
        when {
            delivery != null -> for (index in 0 until delivery.size) {
                val detection = delivery[index].event.detection
                if (detection.kind == AudioEventKind.Onset && detection.isHit) strongest = max(strongest, detection.strength.coerceIn(0.05f, 1f))
            }
            detections != null -> for (index in 0 until detections.size) {
                val detection = detections[index]
                if (detection.kind == AudioEventKind.Onset && detection.isHit) strongest = max(strongest, detection.strength.coerceIn(0.05f, 1f))
            }
            frame.beat > 0f -> strongest = frame.beat.coerceIn(0.05f, 1f)
        }
        return strongest
    }

    /** The island whose band rose most just now, or the highest one when none rose. */
    private fun firedIsland(): Int {
        var best = 0
        var rise = -1f
        for (band in 0 until BANDS) {
            val step = wanted[band] - settled[band]
            if (step > rise) {
                rise = step
                best = band
            }
        }
        if (rise > 0.02f) return best / 2
        for (band in 0 until BANDS) if (lifted[band] > lifted[best]) best = band
        return best / 2
    }

    /** Sends a ring out from a point, in cells: it lives [beats] beats and travels [reach] short halves of the frame. */
    private fun ring(x: Float, y: Float, strength: Float, width: Float, beats: Float, reach: Float) {
        val life = (beats * gestures.beatSeconds).coerceIn(1.2f, 3.5f)
        rings.spawn(x, y, reach * min(halfX, halfY) / life, strength, life, width)
    }

    /** The line colours: the drawing's own swatches, or the chosen palette read at full strength. */
    private fun paint(state: VizRenderState, dt: Float) {
        val frame = state.frame
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - KEY_REFERENCE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyLean.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, dt)
        val turn = keyLean.value
        val palette = state.palette
        // A palette that spans the whole colour circle, the default one, has no two ends to give a
        // sea and a land, so the map keeps its own swatches under it. Any other palette replaces them.
        val own = ((palette.hueSpan - 300f) / 60f).coerceIn(0f, 1f)
        val deepHue = 262f + turn
        // Electric blue rather than a dark one: a 1.5 px line at a third of the light has to show on black.
        put(deep, colourOf(0.54f, min(0.24f, mostChroma(0.54f, deepHue) - 0.01f), deepHue), palette.vividRamp(0.1f), own)
        put(shallow, vividColour(205f + turn, headroom = 0.01f), palette.vividRamp(0.5f), own)
        put(land, vividColour(70f + turn, lift = 0.02f, headroom = 0.01f), palette.vividRamp(1f), own)
    }

    private fun put(into: FloatArray, swatch: Color, chosen: Color, own: Float) {
        into[0] = chosen.red + (swatch.red - chosen.red) * own
        into[1] = chosen.green + (swatch.green - chosen.green) * own
        into[2] = chosen.blue + (swatch.blue - chosen.blue) * own
    }

    /** The coastline fades as the sea drains below the seabed. */
    private val coast: Float get() = smoothStep(0.1f, 0.8f, seaLevel)

    /** How many levels below the sea count as deep water. */
    private val depth: Float get() = max(seaLevel - SEABED_LOW, 1.5f)

    override fun shaderSize(width: Float, height: Float) {
        canvasWidth = width.coerceAtLeast(1f)
        canvasHeight = height.coerceAtLeast(1f)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val picture = if (columns == LONG) landscape else portrait
        if (uploaded != built || uploadedTo !== picture) {
            pack(picture)
            picture.upload()
            uploaded = built
            uploadedTo = picture
        }
        program.child("uGrid", picture.image)
        val scale = (min(canvasWidth, canvasHeight) / 1080f).coerceIn(1f, 2f)
        program.uniform("uGridSize", columns.toFloat(), rows.toFloat())
        program.uniform("uCell", max(canvasWidth / columns, canvasHeight / rows))
        program.uniform("uTop", TOP)
        program.uniform("uSea", seaLevel)
        program.uniform("uDepth", depth)
        program.uniform("uWhiteFrom", WHITE_FROM)
        program.uniform("uWhiteTo", WHITE_TO)
        program.uniform("uLight", light)
        program.uniform("uBoost", boost)
        program.uniform("uAmber", amber)
        program.uniform("uCoast", coast)
        program.uniform("uFlicker", FLICKER_LIGHT)
        program.uniform("uGlow", GLOW)
        program.uniform("uWidths", PLAIN_WIDTH / 2f * scale, INDEX_WIDTH / 2f * scale, COAST_WIDTH / 2f * scale, GLOW_REACH * scale)
        program.uniform("uCrowd", CROWDED * scale, ROOMY * scale)
        program.uniform("uInterval", intervalFor(max(canvasWidth / columns, canvasHeight / rows)))
        program.uniform("uDeep", deep[0], deep[1], deep[2])
        program.uniform("uShallow", shallow[0], shallow[1], shallow[2])
        program.uniform("uLand", land[0], land[1], land[2])
        program.uniform("uPeak", 1f, 1f, 1f)
    }

    /**
     * Levels from one drawn line to the next. A cell of a large frame is many pixels wide and every
     * level has room; on a small frame or a tile the lines would crowd into a fill, so, as on a small
     * printed map, only every second or third level is drawn.
     */
    private fun intervalFor(cellPixels: Float): Float = when {
        cellPixels >= 9f -> 1f
        cellPixels >= 4.5f -> 2f
        else -> 3f
    }

    /** The field as a picture: sixteen bits of height over red and green, the flicker in blue. */
    private fun pack(picture: PixelImage) {
        for (cell in 0 until columns * rows) {
            val bits = ((field[cell] / TOP).coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
            val glint = (shimmer[cell].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            picture.pixels[cell] = OPAQUE or ((bits shr 8) shl 16) or ((bits and 0xFF) shl 8) or glint
        }
    }

    // The stand-in where shaders cannot run: the same map as polylines from the same grid.
    private val fine by lazy { FloatArray((2 * LONG - 1) * (2 * SHORT - 1)) }
    private val wide by lazy { FloatArray(max((2 * LONG - 1) * SHORT, (2 * SHORT - 1) * LONG)) }
    private val levelPaths by lazy { Array(LEVELS + 1) { Path() } }
    private val coastPath by lazy { Path() }
    private var strokeScale = 0f
    private var plainStroke = Stroke(PLAIN_WIDTH)
    private var indexStroke = Stroke(INDEX_WIDTH)
    private var coastStroke = Stroke(COAST_WIDTH)
    private var glowStroke = Stroke(GLOW_REACH * 2f)

    override fun DrawScope.drawFallback(state: VizRenderState) {
        if (size.width < 1f || size.height < 1f) return
        val scale = (min(size.width, size.height) / 1080f).coerceIn(1f, 2f)
        if (scale != strokeScale) {
            strokeScale = scale
            plainStroke = Stroke(PLAIN_WIDTH * scale, cap = StrokeCap.Round)
            indexStroke = Stroke(INDEX_WIDTH * scale, cap = StrokeCap.Round)
            coastStroke = Stroke(COAST_WIDTH * scale, cap = StrokeCap.Round)
            glowStroke = Stroke(GLOW_REACH * 2f * scale, cap = StrokeCap.Round)
        }
        val interval = intervalFor(max(size.width / columns, size.height / rows)).toInt()
        smoothFine()
        traceFine(size.width, size.height, scale, interval)
        for (level in interval..LEVELS step interval) {
            val hot = smoothStep(WHITE_FROM + 2f, WHITE_TO + 1f, level - seaLevel)
            if (hot > 0.01f) {
                drawPath(levelPaths[level], glowColour(0.35f * hot * GLOW), style = glowStroke, blendMode = BlendMode.Plus)
            }
        }
        for (level in interval..LEVELS step interval) {
            drawPath(levelPaths[level], tintOf(level.toFloat()), style = if ((level / interval) % 4 == 0) indexStroke else plainStroke)
        }
        val shore = coast
        if (shore > 0.01f) {
            val bright = (light * (1f + boost)).coerceIn(0f, 1f)
            drawPath(coastPath, Color(bright, bright, bright, shore), style = coastStroke)
        }
    }

    /** The shader's colour for level [k], lit the same way. */
    private fun tintOf(k: Float): Color {
        val above = k - seaLevel
        val deepShare = (-above / depth).coerceIn(0f, 1f)
        val white = smoothStep(WHITE_FROM, WHITE_TO, above)
        val landShare = smoothStep(-0.5f, 0.5f, above)
        val lit = light * (1f + boost)
        fun channel(index: Int): Float {
            var water = shallow[index] + (deep[index] - shallow[index]) * deepShare
            water += (land[index] - water) * amber
            val ground = land[index] + (1f - land[index]) * white
            return ((water + (ground - water) * landShare) * lit).coerceIn(0f, 1f)
        }
        return Color(channel(0), channel(1), channel(2))
    }

    private fun glowColour(alpha: Float): Color = Color(
        ((land[0] + (1f - land[0]) * 0.6f) * light).coerceIn(0f, 1f),
        ((land[1] + (1f - land[1]) * 0.6f) * light).coerceIn(0f, 1f),
        ((land[2] + (1f - land[2]) * 0.6f) * light).coerceIn(0f, 1f), alpha.coerceIn(0f, 1f),
    )

    /** The field smoothed onto a grid twice as fine, with the shader's own B-spline. */
    private fun smoothFine() {
        val fineColumns = 2 * columns - 1
        val fineRows = 2 * rows - 1
        for (y in 0 until rows) {
            val row = y * columns
            for (u in 0 until fineColumns) {
                wide[y * fineColumns + u] = spline(u) { field[row + it.coerceIn(0, columns - 1)] }
            }
        }
        for (u in 0 until fineColumns) {
            for (v in 0 until fineRows) {
                fine[v * fineColumns + u] = spline(v) { wide[it.coerceIn(0, rows - 1) * fineColumns + u] }
            }
        }
    }

    /** The B-spline at fine step [step]: a texel centre when even, halfway to the next when odd. */
    private inline fun spline(step: Int, value: (Int) -> Float): Float {
        val i = step / 2
        return if (step % 2 == 0) {
            (value(i - 1) + 4f * value(i) + value(i + 1)) / 6f
        } else {
            (value(i - 1) + 23f * value(i) + 23f * value(i + 1) + value(i + 2)) / 48f
        }
    }

    /**
     * Marching squares over the fine grid: every level crossing a cell becomes a piece of its path.
     * Where the levels crowd too close, the plain ones are left out as the shader leaves them out.
     */
    private fun traceFine(width: Float, height: Float, scale: Float, interval: Int) {
        for (path in levelPaths) path.reset()
        coastPath.reset()
        val fineColumns = 2 * columns - 1
        val fineRows = 2 * rows - 1
        val cell = max(width / columns, height / rows)
        val step = cell / 2f
        val originX = width / 2f + (0.5f - columns / 2f) * cell
        val originY = height / 2f + (0.5f - rows / 2f) * cell
        val crowded = (CROWDED + ROOMY) / 2f * scale
        val sea = seaLevel
        val shore = coast > 0.01f
        for (v in 0 until fineRows - 1) {
            for (u in 0 until fineColumns - 1) {
                val a = fine[v * fineColumns + u]
                val b = fine[v * fineColumns + u + 1]
                val c = fine[(v + 1) * fineColumns + u + 1]
                val d = fine[(v + 1) * fineColumns + u]
                val low = min(min(a, b), min(c, d))
                val high = max(max(a, b), max(c, d))
                if (high - low < 1e-6f) continue
                val x = originX + u * step
                val y = originY + v * step
                // Levels per pixel across this cell, from its four corners.
                val across = (b - a + c - d) / (2f * step)
                val down = (d - a + c - b) / (2f * step)
                val room = 1f / max(sqrt(across * across + down * down), 1e-5f)
                val first = max(1, floor(low).toInt() + 1)
                val last = min(LEVELS, floor(high).toInt())
                for (level in first..last) {
                    if (level % interval != 0) continue
                    if (room * interval * (if ((level / interval) % 4 == 0) 4f else 1f) < crowded) continue
                    cross(levelPaths[level], level.toFloat(), a, b, c, d, x, y, step)
                }
                if (shore && sea > low && sea <= high) cross(coastPath, sea, a, b, c, d, x, y, step)
            }
        }
    }

    /**
     * One cell of marching squares: [a] to [d] are its corners clockwise from the top left, and one of
     * sixteen patterns says which edges the line at [level] joins.
     */
    private fun cross(path: Path, level: Float, a: Float, b: Float, c: Float, d: Float, x: Float, y: Float, step: Float) {
        val pattern = (if (a > level) 1 else 0) or (if (b > level) 2 else 0) or
            (if (c > level) 4 else 0) or (if (d > level) 8 else 0)
        if (pattern == 0 || pattern == 15) return
        val topX = x + step * crossing(a, b, level)
        val rightY = y + step * crossing(b, c, level)
        val bottomX = x + step * crossing(d, c, level)
        val leftY = y + step * crossing(a, d, level)
        val right = x + step
        val bottom = y + step
        when (pattern) {
            1, 14 -> segment(path, topX, y, x, leftY)
            2, 13 -> segment(path, topX, y, right, rightY)
            3, 12 -> segment(path, x, leftY, right, rightY)
            4, 11 -> segment(path, right, rightY, bottomX, bottom)
            6, 9 -> segment(path, topX, y, bottomX, bottom)
            7, 8 -> segment(path, x, leftY, bottomX, bottom)
            5 -> {
                segment(path, topX, y, x, leftY)
                segment(path, right, rightY, bottomX, bottom)
            }
            10 -> {
                segment(path, topX, y, right, rightY)
                segment(path, x, leftY, bottomX, bottom)
            }
        }
    }

    private fun crossing(from: Float, to: Float, level: Float): Float {
        val span = to - from
        return if (span == 0f) 0.5f else ((level - from) / span).coerceIn(0f, 1f)
    }

    private fun segment(path: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
    }

    override fun onReset() {
        lifted.fill(0f)
        wanted.fill(0f)
        settled.fill(0f)
        flicker.fill(0f)
        jump.reset()
        layOut(to)
        from.copyFrom(to)
        morph = 1f
        rings.clear()
        seaEnergy = 0f
        fresh = true
        highTide = 0f
        highHeld = false
        lowClock = -1f
        keyLean.reset()
        seaLevel = SEA_QUIET
        amber = 0f
        boost = 0f
        light = IDLE_LIGHT
        built = 0L
        uploaded = -1L
        uploadedTo = null
    }

    // For tests: what the map is doing, in levels and shares.
    internal val sea: Float get() = seaLevel
    internal val tallest: Float get() = tallestPeak
    internal val ringsAlive: Int get() = rings.alive
    internal val lowTide: Float get() = lowTideNow()

    /** How far island [island] is raised by its bands, 0 to 1: the higher of its two. */
    internal fun lift(island: Int): Float = max(lifted[2 * island], lifted[2 * island + 1])

    /** The share of the visible map above the sea. */
    internal fun landShare(): Float {
        var landCells = 0
        var seen = 0
        val left = (columns / 2f - halfX).toInt().coerceAtLeast(0)
        val right = (columns / 2f + halfX).toInt().coerceAtMost(columns)
        val top = (rows / 2f - halfY).toInt().coerceAtLeast(0)
        val bottom = (rows / 2f + halfY).toInt().coerceAtMost(rows)
        for (y in top until bottom) for (x in left until right) {
            seen++
            if (field[y * columns + x] > seaLevel) landCells++
        }
        return if (seen == 0) 0f else landCells.toFloat() / seen
    }

    private companion object {
        const val ISLANDS = 32
        const val BANDS = 2 * ISLANDS
        /** The grid's long and short sides, in cells. It turns with the frame so its cells stay square. */
        const val LONG = 96
        const val SHORT = 54
        const val CELLS = LONG * SHORT
        /** The grid's full range, in levels. */
        const val TOP = 16f
        /** The contour levels drawn: 1 up to the one below the top. */
        const val LEVELS = 15
        /** Where merged summits start to round off below the top of the range. */
        const val SOFT_FROM = 13f

        /** The seabed's lowest point and how far its swell rises, in levels, and the swell's size in cells. */
        const val SEABED_LOW = 0.3f
        const val SEABED_SWELL = 1.4f
        const val SEABED_SCALE = 11f
        /** The size in cells of the grain that shapes the coasts. */
        const val GRAIN_SCALE = 6f
        /** A shelf's width as a multiple of its island's radius. */
        const val SHELF_WIDTH = 2f
        /** How high one band raises its side of an island, how wide that side is, and how far apart the two sides stand. */
        const val BAND_TOP = 7.5f
        const val BAND_WIDTH = 0.85f
        const val SIDE_OFFSET = 0.3f

        /** Sea level with nothing playing, under the loudest music, and at low tide, in levels. */
        const val SEA_QUIET = 7.2f
        const val SEA_LOUD = 4.6f
        const val SEA_DRY = -1.5f
        /** How far below the tallest summit the water stands at high tide. */
        const val HIGH_TIDE_BELOW = 1.5f
        /** How much of low tide the future window may bring early, over the bar before a drop. */
        const val BUILD_UP = 0.3f
        const val SEA_SECONDS = 0.6f

        /** Levels above the sea where land lines start and finish turning white. */
        const val WHITE_FROM = 3.5f
        const val WHITE_TO = 8f
        /** The light of a still map, and the extra light the seabed gets at low tide. */
        const val IDLE_LIGHT = 0.35f
        const val LOW_TIDE_LIGHT = 0.5f
        const val GLOW = 0.6f
        const val FLICKER_LIGHT = 1.4f
        const val FLICKER_SECONDS = 0.1f

        /** Line widths in pixels on a frame whose short side is 1080 or less, and the glow's reach. */
        const val PLAIN_WIDTH = 1.5f
        const val INDEX_WIDTH = 3f
        const val COAST_WIDTH = 2.5f
        const val GLOW_REACH = 4f
        /** Gaps in pixels between neighbouring levels at which a plain line is gone, and fully drawn. */
        const val CROWDED = 2f
        const val ROOMY = 3.5f

        /** How far the spiral reaches towards the frame's edges. */
        const val MARGIN = 0.97f
        /** The middle islands that jump on a kick, and the first of the edge islets that flicker. */
        const val MIDDLE = 6
        const val EDGE = 22
        const val JUMP_KICK = 7f
        const val JUMP_LEVELS = 5f
        const val TWITCH_LEVELS = 0.6f

        /** Band heights that raise an island nothing and all the way, and the tilt towards the treble. */
        const val BAND_FLOOR = 0.05f
        const val BAND_FULL = 0.55f
        const val TILT = 0.5f
        const val RISE_PER_SECOND = 20f
        const val FALL_PER_SECOND = 1.1f
        const val SETTLE_PER_SECOND = 4f
        const val RISE_SECONDS = 0.05f

        const val RINGS = 12
        const val RING_SECONDS = 2f
        const val ONSET_RING = 2.6f
        const val ONSET_WIDTH = 1.5f
        const val KICK_RING = 3.2f
        const val KICK_WIDTH = 2f

        /** The hue the key leans the swatches from, and how far and how fast it may lean them. */
        const val KEY_REFERENCE = 240f
        const val KEY_TURN = 20f
        const val KEY_TURN_PER_SECOND = 12f

        const val OPAQUE = -0x1000000
    }
}

/** Where the islands of one archipelago sit, how big they are and how high their shelves stand. */
private class Archipelago {
    /** Round the spiral, in radians. */
    val angle = FloatArray(32)
    /** Out from the middle, 0 to 1 of the way to the frame's edge. */
    val reach = FloatArray(32)
    /** The island's radius, as a share of the frame's shorter half. */
    val size = FloatArray(32)
    /** Which way the island's two bands stand apart, in radians. */
    val lean = FloatArray(32)
    /** How high the island's shelf stands at rest, in levels. */
    val rest = FloatArray(32)
    var seed = 0

    // The layout on the grid as last shaped: island centres and radii in cells, and the ground the
    // music does not move. Rebuilt when the layout or the frame's shape changes.
    val x = FloatArray(32)
    val y = FloatArray(32)
    val radius = FloatArray(32)
    val relief = FloatArray(96 * 54)
    val grain = FloatArray(96 * 54)
    var shapedColumns = 0
    var shapedHalfX = 0f
    var shapedHalfY = 0f

    fun copyFrom(other: Archipelago) {
        other.angle.copyInto(angle)
        other.reach.copyInto(reach)
        other.size.copyInto(size)
        other.lean.copyInto(lean)
        other.rest.copyInto(rest)
        seed = other.seed
        shapedColumns = 0
    }
}

/** Rings that travel outward through the water as a moving bump in the height field. */
private class Ripples(private val capacity: Int) {
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val age = FloatArray(capacity) { -1f }
    private val speed = FloatArray(capacity)
    private val strength = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val width = FloatArray(capacity)

    val alive: Int
        get() {
            var count = 0
            for (slot in 0 until capacity) if (age[slot] >= 0f) count++
            return count
        }

    /** Starts a ring, in the first free slot or in place of the one furthest through its life. */
    fun spawn(atX: Float, atY: Float, cellsPerSecond: Float, height: Float, seconds: Float, thickness: Float) {
        var slot = 0
        var oldest = -1f
        for (index in 0 until capacity) {
            if (age[index] < 0f) {
                slot = index
                break
            }
            val through = age[index] / life[index]
            if (through > oldest) {
                oldest = through
                slot = index
            }
        }
        x[slot] = atX
        y[slot] = atY
        age[slot] = 0f
        speed[slot] = cellsPerSecond
        strength[slot] = height
        life[slot] = seconds
        width[slot] = thickness
    }

    fun advance(deltaSeconds: Float) {
        for (slot in 0 until capacity) {
            if (age[slot] < 0f) continue
            age[slot] += deltaSeconds
            if (age[slot] >= life[slot]) age[slot] = -1f
        }
    }

    /**
     * Adds every ring to [field] as a raised band round its centre. Only the water carries it: the
     * bump fades out towards the coast, so a ring passes under an island rather than over it.
     */
    fun addTo(field: FloatArray, terrain: FloatArray, columns: Int, rows: Int, sea: Float) {
        for (slot in 0 until capacity) {
            val t = age[slot]
            if (t < 0f) continue
            val height = strength[slot] * (1f - t / life[slot]) * min(1f, t / FADE_IN)
            if (height < 0.02f) continue
            val radius = speed[slot] * t
            val half = width[slot]
            val outer = radius + half
            val inner = max(0f, radius - half)
            val outer2 = outer * outer
            val inner2 = inner * inner
            val x0 = floor(x[slot] - outer).toInt().coerceAtLeast(0)
            val x1 = floor(x[slot] + outer).toInt().coerceAtMost(columns - 1)
            val y0 = floor(y[slot] - outer).toInt().coerceAtLeast(0)
            val y1 = floor(y[slot] + outer).toInt().coerceAtMost(rows - 1)
            for (row in y0..y1) {
                val dy = row + 0.5f - y[slot]
                for (column in x0..x1) {
                    val dx = column + 0.5f - x[slot]
                    val distance = dx * dx + dy * dy
                    if (distance > outer2 || distance < inner2) continue
                    val u = (sqrt(distance) - radius) / half
                    val bump = (1f - u * u) * (1f - u * u)
                    val cell = row * columns + column
                    val room = sea - UNDER_MARGIN - terrain[cell]
                    if (room <= 0f) continue
                    // Eased in below the surface, so a ring swells the water but never breaks it.
                    val lift = height * bump * (room / UNDER_RAMP).coerceAtMost(1f)
                    field[cell] += room * (1f - exp(-lift / room))
                }
            }
        }
    }

    fun clear() {
        age.fill(-1f)
    }

    private companion object {
        const val FADE_IN = 0.06f
        /** Levels below the sea where a ring starts to fade, and over how many it fades out. */
        const val UNDER_MARGIN = 0.4f
        const val UNDER_RAMP = 1.2f
    }
}

/** Smooth value noise between 0 and 1, two octaves, the same for the same [seed]. */
private fun noise(x: Float, y: Float, seed: Int): Float =
    0.65f * lattice(x, y, seed) + 0.35f * lattice(x * 2.1f + 17.3f, y * 2.1f - 9.1f, seed + 1)

private fun lattice(x: Float, y: Float, seed: Int): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val sx = smooth(x - ix)
    val sy = smooth(y - iy)
    val top = hash(ix, iy, seed) + (hash(ix + 1, iy, seed) - hash(ix, iy, seed)) * sx
    val bottom = hash(ix, iy + 1, seed) + (hash(ix + 1, iy + 1, seed) - hash(ix, iy + 1, seed)) * sx
    return top + (bottom - top) * sy
}

private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374_761_393 + y * 668_265_263 + seed * 1_274_126_177
    h = (h xor (h ushr 13)) * 1_274_126_177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65_535f
}

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun smoothStep(from: Float, to: Float, value: Float): Float = smooth((value - from) / (to - from))
