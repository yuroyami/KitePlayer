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
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
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
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flow
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.LaceReaction
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.vividColour
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A living map seen straight down: neon contour lines on black, a white coastline at sea level and
 * a thicker index line every fourth level. Thirty two islands stand on the bands, bass first and
 * treble last, and a loud band raises its island, so new lines appear at the summit and ripple
 * outward. Each island's coastline is the live waveform wrapped round it. Louder music lowers the sea.
 *
 * The north of the map is the last seconds of the spectrum: new land rises at the top edge and
 * drifts south as the music plays, at the music's pace, and stands still in a pause or a silence.
 * The sea is a memory field: kicks drop ink round the loudest bass island and Marble's point
 * vortices comb it into currents, drawn as contour lines of their own, in the cool sea colours, only
 * where the ground lies under the water. A faint wash of the ground's colour keeps the map lit.
 *
 * Forms, at the pace of the evolution pacer: Archipelago (a spiral), Ridge (one chain across the
 * map), Crater (the bass in the middle, the rest on a rim) and Delta (small islands in strong
 * currents). A morph glides the islands to the next form over one to four cycles and turns the
 * currents the other way; a birth raises a new island group where the loudest bands sit. A section
 * raises a new archipelago. Onsets and kicks send rings through the water, a snare cracks an island,
 * hats make the edge islets flicker. A breakdown brings high tide, stills the sea and grows lace foam
 * in it; a drop brings low tide for a cycle, over which the currents run on in gold. The camera never
 * moves.
 *
 * Where shaders cannot run, the stand-in draws the land's lines only: no sea and no wash.
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
        // Each island's coastline is the live waveform, wrapped round it.
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Onset, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(RING_SECONDS)),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.spring(0.4f)),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(RING_SECONDS)),
        // A snare cracks an island for a moment.
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(CRACK_SECONDS)),
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
    private val heights = FloatArray(CELLS)
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

    // The sea's memory: ink the kicks drop and the currents comb, drawn as lines under the water.
    // `ShaderPreset` names it `field`; inside an accessor `field` means a backing field, so the code uses `currents`.
    private val currents = MemoryField(rows = FIELD_ROWS, withExtra = true).also { it.halfLife = INK_HALF_LIFE }
    override val field: MemoryField get() = currents

    // Marble's point vortices, in the field's centred units, and their signed speeds.
    private var vortexCount = 2
    private val vortexX = FloatArray(MOST_VORTICES)
    private val vortexY = FloatArray(MOST_VORTICES)
    private val vortexSpeed = FloatArray(MOST_VORTICES)
    private val vortexLevel = FloatArray(MOST_VORTICES)
    private val stir: Flow = Flows.Vortices({ vortexCount }, vortexX, vortexY, vortexSpeed)

    /** The vortices' direction: every morph flips it, so the sea never turns only one way. */
    private var spin = 1f

    /** How still the sea is: 1 in a breakdown, 0 otherwise, eased. */
    private var stillness = 0f

    /** After a drop the vortices line up across the middle for a cycle and comb the ink into feathers. */
    private var combing = 0f
    private var sinceDrum = LONG_AGO
    private var sinceOnsetInk = LONG_AGO

    /** How hard the currents stir for the form on the map. */
    private var currentStrength = 1f

    // What the sea shader reads: the level the ink lives under, the wash, the gold current and the foam.
    private var inkSea = SEA_QUIET
    private var wash = 0f
    private var goldCurrent = 0f
    private var foam = 0f
    private val seaNear = FloatArray(3)
    private val seaFar = FloatArray(3)
    private val goldInk = FloatArray(3)

    // The north of the map: the spectrum by map time, which runs at the music's pace and stops in a
    // pause or a silence. Grid rows at the top show it, newest at the top edge.
    private val past = History(rows = PAST_ROWS)
    private var mapTime = 0.0

    // This frame's waveform, read round each island's coast.
    private var scope = FloatArray(0)
    private var scopeGain = 1f

    // The form on the map, and a morph's glide from one layout to the next over one to four cycles.
    private var form = ARCHIPELAGO
    private val glideFromU = FloatArray(ISLANDS)
    private val glideFromV = FloatArray(ISLANDS)
    private val glideFromSize = FloatArray(ISLANDS)
    private val glideToU = FloatArray(ISLANDS)
    private val glideToV = FloatArray(ISLANDS)
    private val glideToSize = FloatArray(ISLANDS)
    private var glide = 1f
    private var glideCycles = 1f
    private var raised = 0

    // Snare cracks: a narrow trench across one island, in cells, that heals over [CRACK_SECONDS].
    private val crackX = FloatArray(CRACKS)
    private val crackY = FloatArray(CRACKS)
    private val crackAngle = FloatArray(CRACKS)
    private val crackLength = FloatArray(CRACKS)
    private val crackDepth = FloatArray(CRACKS)
    private val crackAge = FloatArray(CRACKS) { -1f }
    private var nextCrack = 0

    // The breakdown's foam: lace grown in the still sea, in the field's extra channel.
    private val lace = LaceReaction()
    private var foamMask = FloatArray(0)
    private var foamOwed = 0f
    private var seeded = false

    init {
        layOut(to, ARCHIPELAGO)
        from.copyFrom(to)
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        // Audible seconds: zero while paused or silent, so the map holds its last heights then.
        val dt = state.stepSeconds
        val bar = gestures.cycleSeconds
        frameFor(kit.aspect)
        currents.size(kit.aspect)
        readBands(frame.bandsRel, dt)
        // The map scrolls at the music's pace; its north strip is the spectrum by map time.
        mapTime += (dt * state.paced(SCROLL_PACE)).toDouble()
        past.push(frame.bandsRel, mapTime)
        scope = frame.scope
        scopeGain = frame.waveformGain

        // A section raises a new archipelago; a morph of the pacer glides to the next form; a birth
        // raises a new island group. A breakdown holds high tide until the next turn.
        if (gestures.turn) highHeld = gestures.breakdown
        // A section that lands while an archipelago is still rising glides to the next form instead,
        // so every morph the pacer counts changes the picture.
        if (gestures.section && morph >= 1f) {
            newArchipelago()
        } else if (evolution.morph) {
            morphTo((form + 1) % FORMS.size)
        }
        if (evolution.birth) raiseGroup()
        morph = (morph + dt / bar).coerceAtMost(1f)
        glideIslands(dt, bar)
        for (island in 0 until ISLANDS) {
            if (to.rise[island] < 1f) to.rise[island] = (to.rise[island] + dt / bar).coerceAtMost(1f)
        }
        highTide = (highTide + (if (highHeld) 1f else -1f) * dt / bar).coerceIn(0f, 1f)
        if (gestures.surge) {
            lowClock = 0f
            lowBeat = gestures.beatSeconds
            lowBar = bar
            combing = 1f
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
        if (gestures.snare > 0f) crack(shown, gestures.snare)
        ageCracks(dt)
        if (gestures.hat > 0f) {
            val accent = gestures.hatAccent.coerceAtMost(1f)
            for (island in EDGE until ISLANDS) {
                if (random.next() < 0.5f) flicker[island] = max(flicker[island], accent * (0.5f + 0.5f * random.next()))
            }
        }
        val fade = exp(-dt / FLICKER_SECONDS)
        for (island in EDGE until ISLANDS) flicker[island] *= fade
        rings.advance(dt)

        // The sea: place the vortices, drop this frame's ink, and let the currents carry it.
        stirTheSea(state, dt)
        dropInk(dt, onset, shown)
        currents.advance(stir, dt)

        raiseGround()
        tide(state, dt)
        growFoam(state, dt)
        terrain.copyInto(heights, 0, 0, columns * rows)
        rings.addTo(heights, terrain, columns, rows, seaLevel)
        built++
        paint(state, dt)
        fresh = false
    }

    /** Places the vortices along the spectrum's shape and sets how hard each stirs, as Marble did. */
    private fun stirTheSea(state: VizRenderState, dt: Float) {
        val frame = state.frame
        val bands = frame.bandsRel
        stillness += ((if (highHeld) 1f else 0f) - stillness) * (1f - exp(-dt / STILL_SECONDS))
        combing = (combing - dt / gestures.cycleSeconds.coerceAtLeast(0.5f)).coerceAtLeast(0f)
        vortexCount = (2f + 3f * frame.mood).roundToInt().coerceIn(2, MOST_VORTICES)
        val ease = 1f - exp(-dt / VORTEX_EASE_SECONDS)
        val strength = (BASE_STIR + BASS_STIR * frame.bass) * (1f - stillness) *
            (0.3f + 0.7f * state.motionScale) * (1f + 1.2f * combing) * currentStrength
        val reach = currents.aspect
        for (vortex in 0 until vortexCount) {
            var level = 0f
            if (bands.isNotEmpty()) {
                val first = vortex * bands.size / vortexCount
                val last = ((vortex + 1) * bands.size / vortexCount).coerceAtLeast(first + 1)
                for (band in first until last) level += bands[band]
                level /= (last - first)
            }
            vortexLevel[vortex] += (level - vortexLevel[vortex]) * ease
            // Low bands on the left, high on the right, each as high up the sea as its band is loud.
            // While a drop is being combed they line up across the middle, which makes feathers.
            val wantX = -reach + 2f * reach * (vortex + 0.5f) / vortexCount
            val wantY = (0.44f - 0.88f * vortexLevel[vortex].coerceIn(0f, 1f)) * (1f - combing)
            vortexX[vortex] += (wantX - vortexX[vortex]) * ease
            vortexY[vortex] += (wantY - vortexY[vortex]) * ease
            val sign = (if (vortex % 2 == 0) 1f else -1f) * spin
            vortexSpeed[vortex] = sign * strength * (0.35f + 0.65f * vortexLevel[vortex].coerceIn(0f, 1f) * 2f).coerceAtMost(1.3f)
        }
        for (vortex in vortexCount until MOST_VORTICES) vortexSpeed[vortex] = 0f
    }

    /**
     * A kick drops ink round the island of the loudest bass band, wider than the island, so the ink
     * spreads into the water round it and pushes the older ink out in rings. Music without drums drops
     * smaller ink on its onsets instead, at most once a beat.
     */
    private fun dropInk(dt: Float, onset: Float, shown: Archipelago) {
        sinceDrum += dt
        sinceOnsetInk += dt
        if (gestures.kick > 0f) {
            dropAt(shown, loudestLowIsland(), INK_REACH + INK_KICK_REACH * gestures.kick.coerceIn(0f, 1f), 1f)
            sinceDrum = 0f
        } else if (onset > 0f && sinceDrum > gestures.cycleSeconds && sinceOnsetInk > gestures.beatSeconds * 0.9f) {
            dropAt(shown, firedIsland(), INK_REACH * (0.6f + 0.4f * onset), ONSET_INK)
            sinceOnsetInk = 0f
        }
    }

    /** One drop of [ink] round island [island] of [layout], [reach] island radii wide. */
    private fun dropAt(layout: Archipelago, island: Int, reach: Float, ink: Float) {
        // From grid cells to the field's centred units: one unit is half the visible height, y down.
        val x = (layout.x[island] - columns / 2f) / halfY
        val y = (layout.y[island] - rows / 2f) / halfY
        currents.drop(x, y, layout.radius[island] / halfY * reach, ink)
        drops++
        lastDropX = x
        lastDropY = y
    }

    /** The bass island standing highest on its bands. */
    private fun loudestLowIsland(): Int {
        var best = 0
        for (island in 1 until LOW_ISLANDS) if (lift(island) > lift(best)) best = island
        return best
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

    /** The old archipelago starts to sink and a new one, of the next form, to rise, over one cycle. */
    private fun newArchipelago() {
        form = (form + 1) % FORMS.size
        spin = -spin
        currentStrength = if (form == DELTA) DELTA_STIR else 1f
        val old = from
        from = to
        to = old
        layOut(to, form)
        morph = 0f
        glide = 1f
    }

    /** The islands glide to the next form's layout over one to four cycles; the currents turn the other way. */
    private fun morphTo(next: Int) {
        form = next
        spin = -spin
        currentStrength = if (form == DELTA) DELTA_STIR else 1f
        val start = random.next() * TAU
        val hand = if (clockwise.on) 1f else -1f
        for (island in 0 until ISLANDS) {
            glideFromU[island] = to.u[island]
            glideFromV[island] = to.v[island]
            glideFromSize[island] = to.size[island]
            place(form, island, (island + 0.5f) / ISLANDS, start, hand, turns.target, glideToU, glideToV, glideToSize)
        }
        glideCycles = 1f + (random.next() * 4f).toInt().coerceAtMost(3)
        glide = 0f
    }

    private fun glideIslands(dt: Float, bar: Float) {
        if (glide >= 1f) return
        glide = (glide + dt / (glideCycles * bar)).coerceAtMost(1f)
        val eased = smooth(glide)
        for (island in 0 until ISLANDS) {
            to.u[island] = glideFromU[island] + (glideToU[island] - glideFromU[island]) * eased
            to.v[island] = glideFromV[island] + (glideToV[island] - glideFromV[island]) * eased
            to.size[island] = glideFromSize[island] + (glideToSize[island] - glideFromSize[island]) * eased
        }
        to.placed = false
    }

    /** A new archipelago of [form] in [into]: where its islands sit, how big and how high. */
    private fun layOut(into: Archipelago, form: Int) {
        val start = random.next() * TAU
        val hand = if (clockwise.on) 1f else -1f
        for (island in 0 until ISLANDS) {
            place(form, island, (island + 0.5f) / ISLANDS, start, hand, turns.target, into.u, into.v, into.size)
            // The island's two bands stand side by side, so its shape leans with their balance.
            into.lean[island] = random.next() * TAU
            val tall = if (random.next() < 0.2f) 1f else 0f
            into.rest[island] = relief.target * (2.8f + 2.2f * random.next() + tall) * (if (form == DELTA) DELTA_REST else 1f)
            into.rise[island] = 1f
        }
        into.seed = (random.next() * 100_000f).toInt()
        into.placed = false
    }

    /**
     * Where island [island] of [form] sits and how big it is, into [u], [v] and [size]. [along] is its
     * place in band order, 0 for the bass and 1 for the treble.
     */
    private fun place(
        form: Int, island: Int, along: Float, start: Float, hand: Float, laps: Float,
        u: FloatArray, v: FloatArray, size: FloatArray,
    ) {
        when (form) {
            RIDGE -> {
                // One chain across the map, bass on the left, rising and falling like a range.
                u[island] = -1f + 2f * along
                v[island] = 0.35f * sin(along * TAU * 1.5f + start) + 0.08f * random.signed()
                size[island] = spread.target * (0.22f - 0.06f * along) * (0.8f + 0.4f * random.next())
            }
            CRATER -> {
                // The bass in a cluster in the middle, every other band on the rim round it.
                if (island < CRATER_CORE) {
                    val out = 0.22f * sqrt((island + 0.5f) / CRATER_CORE)
                    val angle = start + island * GOLDEN_TURN
                    u[island] = out * cos(angle)
                    v[island] = out * sin(angle)
                } else {
                    val angle = start + hand * TAU * (island - CRATER_CORE) / (ISLANDS - CRATER_CORE) + 0.08f * random.signed()
                    val out = 0.8f + 0.06f * random.signed()
                    u[island] = out * cos(angle)
                    v[island] = out * sin(angle)
                }
                size[island] = spread.target * (0.26f - 0.1f * along) * (0.8f + 0.4f * random.next())
            }
            DELTA -> {
                // Many small islands spread down the map like a river's mouths, the bass upstream.
                u[island] = (0.9f * random.signed() * (0.3f + 0.7f * along)).coerceIn(-1f, 1f)
                v[island] = -0.9f + 1.8f * along + 0.06f * random.signed()
                size[island] = spread.target * DELTA_SIZE * (0.7f + 0.6f * random.next())
            }
            else -> {
                // The spiral: evenly spaced along an Archimedean spiral, pushed out towards the corners.
                val out = sqrt(along)
                val reach = (out + 0.035f * random.signed()).coerceIn(0.06f, 1f)
                val angle = start + hand * TAU * laps * out + 0.1f * random.signed()
                val c = cos(angle)
                val s = sin(angle)
                val square = 1f / sqrt(sqrt(c * c * c * c + s * s * s * s))
                u[island] = reach * c * square
                v[island] = reach * s * square
                size[island] = spread.target * (0.3f - 0.1f * along) * (0.8f + 0.4f * random.next())
            }
        }
    }

    /** A new island group rises out of the sea where the loudest group of bands sits. */
    private fun raiseGroup() {
        var group = 0
        var loudest = -1f
        for (g in 0 until GROUPS) {
            var sum = 0f
            for (band in g * GROUP_BANDS until (g + 1) * GROUP_BANDS) sum += lifted[band]
            if (sum > loudest) {
                loudest = sum
                group = g
            }
        }
        val first = group * GROUP_ISLANDS
        var cu = 0f
        var cv = 0f
        for (island in first until first + GROUP_ISLANDS) {
            cu += to.u[island]
            cv += to.v[island]
        }
        cu /= GROUP_ISLANDS
        cv /= GROUP_ISLANDS
        for (island in first until first + GROUP_ISLANDS) {
            val angle = random.next() * TAU
            val out = GROUP_SPREAD * (0.4f + 0.6f * random.next())
            to.u[island] = (cu + out * cos(angle)).coerceIn(-1f, 1f)
            to.v[island] = (cv + out * sin(angle)).coerceIn(-1f, 1f)
            to.rise[island] = 0f
            // A glide in progress keeps its course; the group's new places are where it heads.
            glideFromU[island] = to.u[island]
            glideToU[island] = to.u[island]
            glideFromV[island] = to.v[island]
            glideToV[island] = to.v[island]
        }
        to.placed = false
        raised++
    }

    /**
     * Lays [layout] onto the grid for this frame's shape: the ground the music does not move, rebuilt
     * when the layout's seed or the frame changes, and where its islands sit in cells, rebuilt
     * whenever an island moved. The islands keep below the north strip, where the map's past runs.
     */
    private fun shape(layout: Archipelago) {
        if (layout.shapedColumns != columns || layout.shapedHalfX != halfX || layout.shapedHalfY != halfY ||
            layout.shapedSeed != layout.seed) {
            layout.shapedColumns = columns
            layout.shapedHalfX = halfX
            layout.shapedHalfY = halfY
            layout.shapedSeed = layout.seed
            for (cell in 0 until columns * rows) {
                val x = cell % columns + 0.5f - columns / 2f
                val y = cell / columns + 0.5f - rows / 2f
                layout.relief[cell] = SEABED_LOW + SEABED_SWELL * noise(x / SEABED_SCALE, y / SEABED_SCALE, layout.seed)
                layout.grain[cell] = 0.7f + 0.6f * noise(x / GRAIN_SCALE, y / GRAIN_SCALE, layout.seed + 7)
            }
            layout.placed = false
        }
        if (layout.placed) return
        layout.placed = true
        val shortSide = min(halfX, halfY)
        val middle = rows / 2f + halfY * NORTH_SHARE
        val reachY = halfY * (1f - NORTH_SHARE)
        for (island in 0 until ISLANDS) {
            layout.x[island] = columns / 2f + layout.u[island] * halfX * MARGIN
            layout.y[island] = middle + layout.v[island] * reachY * MARGIN
            layout.radius[island] = layout.size[island] * shortSide
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
        raisePast()
        cutCracks()
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

    /** Adds [layout] to the ground, scaled by [weight]: its still relief, each island's shelf and two bands, and its coast wave. */
    private fun raise(layout: Archipelago, weight: Float) {
        if (weight <= 0f) return
        shape(layout)
        val cells = columns * rows
        for (cell in 0 until cells) terrain[cell] += weight * layout.relief[cell]
        // Each island stands on a shelf twice its width, so the sea round it shoals in wide steps.
        for (island in 0 until ISLANDS) {
            dome(terrain, layout.grain, layout.x[island], layout.y[island], layout.radius[island] * SHELF_WIDTH,
                layout.rest[island] * layout.rise[island] * weight, null, 0f)
        }
        for (band in 0 until BANDS) {
            val island = band / 2
            val radius = layout.radius[island]
            val side = if (band % 2 == 0) SIDE_OFFSET else -SIDE_OFFSET
            val x = layout.x[island] + cos(layout.lean[island]) * side * radius
            val y = layout.y[island] + sin(layout.lean[island]) * side * radius
            val glint = if (island >= EDGE) flicker[island] * weight else 0f
            dome(terrain, layout.grain, x, y, radius * BAND_WIDTH, heightOf(band) * weight * layout.rise[island], shimmer, glint)
        }
        if (scope.size >= 2) for (island in 0 until ISLANDS) coastWave(layout, island, weight * layout.rise[island])
    }

    // The four history rows of one strip row, found once per row and sampled per column.
    private val pastRows = IntArray(4)

    /**
     * The land north of the islands: grid row r of the top strip is the spectrum from r times
     * [MAP_SECONDS_PER_ROW] of map time ago, bass on the left. Each row keeps the loudest of four
     * moments in its span, so a short burst is not missed. New land rises at the top edge and drifts
     * south towards the islands, where it sinks into the sea. A row the history does not reach yet
     * fades in rather than appearing at once.
     */
    private fun raisePast() {
        val known = past.span()
        val top = (rows / 2f - halfY).toInt().coerceAtLeast(0)
        val strip = (2f * halfY * NORTH_SHARE).toInt().coerceAtLeast(2)
        val left = columns / 2f - halfX
        val across = 2f * halfX
        val grain = to.grain
        for (r in 0 until strip) {
            val y = top + r
            if (y >= rows) break
            val from = r * MAP_SECONDS_PER_ROW
            if (from > known) break
            val reached = ((known - from) / MAP_SECONDS_PER_ROW).toFloat().coerceIn(0f, 1f)
            val fade = (1f - smooth((r - strip * 0.55f) / (strip * 0.45f))) * reached
            // The four history rows of this strip row do not depend on the column, and finding one
            // walks the history, so they are found once here and only sampled per column.
            for (part in 0 until 4) pastRows[part] = past.row((from + part * MAP_SECONDS_PER_ROW / 4.0).toFloat())
            for (x in 0 until columns) {
                val position = ((x + 0.5f - left) / across).coerceIn(0f, 1f)
                var loudest = 0f
                for (part in 0 until 4) {
                    val row = pastRows[part]
                    if (row >= 0) loudest = max(loudest, past.sample(row, position))
                }
                val lift = ((loudest - BAND_FLOOR) / (BAND_FULL - BAND_FLOOR)).coerceIn(0f, 1f)
                terrain[y * columns + x] += PAST_TOP * lift * fade * grain[y * columns + x]
            }
        }
    }

    /**
     * The live waveform wrapped round island [island]: near its rim the ground rises and falls with
     * the trace read round the island, so its coastline is the sound. It is a fraction of a level, so
     * the coast stays one line. Only cells near the sea level are touched, which is where a coast is.
     */
    private fun coastWave(layout: Archipelago, island: Int, weight: Float) {
        val radius = layout.radius[island] * COAST_REACH
        if (radius < 1f || weight <= 0f) return
        val cx = layout.x[island]
        val cy = layout.y[island]
        val left = floor(cx - radius).toInt().coerceAtLeast(0)
        val right = floor(cx + radius).toInt().coerceAtMost(columns - 1)
        val top = floor(cy - radius).toInt().coerceAtLeast(0)
        val bottom = floor(cy + radius).toInt().coerceAtMost(rows - 1)
        for (y in top..bottom) {
            val dy = y + 0.5f - cy
            for (x in left..right) {
                val dx = x + 0.5f - cx
                val q = sqrt(dx * dx + dy * dy) / radius
                if (q < 0.35f || q >= 1f) continue
                val cell = y * columns + x
                if (abs(terrain[cell] - seaLevel) > COAST_BAND) continue
                val spread = (q - 0.7f) / 0.18f
                val ring = exp(-spread * spread)
                val around = atan2(dy, dx) / TAU + 0.5f
                val wave = (scope.sampleAt(around) * scopeGain).coerceIn(-1f, 1f)
                terrain[cell] += COAST_WAVE * wave * ring * weight
            }
        }
    }

    /** A snare: a crack across the island standing highest, as deep as the snare was hard. */
    private fun crack(layout: Archipelago, strength: Float) {
        var island = 0
        for (other in 1 until ISLANDS) if (lift(other) > lift(island)) island = other
        val slot = nextCrack
        nextCrack = (nextCrack + 1) % CRACKS
        crackX[slot] = layout.x[island]
        crackY[slot] = layout.y[island]
        crackAngle[slot] = random.next() * PI_F
        crackLength[slot] = layout.radius[island] * CRACK_REACH
        crackDepth[slot] = CRACK_DEPTH * (0.5f + 0.5f * strength.coerceIn(0f, 1f))
        crackAge[slot] = 0f
    }

    private fun ageCracks(dt: Float) {
        for (slot in 0 until CRACKS) {
            if (crackAge[slot] < 0f) continue
            crackAge[slot] += dt
            if (crackAge[slot] >= CRACK_SECONDS) crackAge[slot] = -1f
        }
    }

    /** Cuts every open crack into the ground: a narrow trench, deepest in its middle, healing as it ages. */
    private fun cutCracks() {
        for (slot in 0 until CRACKS) {
            val age = crackAge[slot]
            if (age < 0f) continue
            val depth = crackDepth[slot] * (1f - age / CRACK_SECONDS)
            val c = cos(crackAngle[slot])
            val s = sin(crackAngle[slot])
            val half = crackLength[slot]
            val cx = crackX[slot]
            val cy = crackY[slot]
            val reach = half + CRACK_WIDTH + 1f
            val left = floor(cx - reach).toInt().coerceAtLeast(0)
            val right = floor(cx + reach).toInt().coerceAtMost(columns - 1)
            val top = floor(cy - reach).toInt().coerceAtLeast(0)
            val bottom = floor(cy + reach).toInt().coerceAtMost(rows - 1)
            for (y in top..bottom) {
                val dy = y + 0.5f - cy
                for (x in left..right) {
                    val dx = x + 0.5f - cx
                    val along = dx * c + dy * s
                    if (abs(along) > half) continue
                    val across = 1f - abs(-dx * s + dy * c) / CRACK_WIDTH
                    if (across <= 0f) continue
                    val taper = 1f - (along / half) * (along / half)
                    terrain[y * columns + x] -= depth * across * across * taper
                }
            }
        }
    }

    /**
     * A breakdown stills the sea and grows lace foam in it: Marble's reaction in the field's extra
     * channel, inside the water, a little faster when the music is louder. The foam fades when the sea
     * moves again, with the ink's half life.
     */
    private fun growFoam(state: VizRenderState, dt: Float) {
        val growing = highHeld && stillness > 0.5f
        foam = (foam + (if (growing) 1f else -1f) * dt / gestures.cycleSeconds.coerceAtLeast(0.4f)).coerceIn(0f, 1f)
        if (!growing) seeded = false
        if (!growing || dt <= 0f) return
        wetMask()
        if (!seeded) {
            lace.sprout(currents, foamMask, FOAM_SEEDS, 1.6f, random)
            seeded = true
        }
        foamOwed += dt * (FOAM_STEPS + FOAM_LOUD_STEPS * state.energy)
        var steps = 0
        while (foamOwed >= 1f && steps < MOST_FOAM_STEPS) {
            foamOwed -= 1f
            lace.step(currents, foamMask)
            steps++
        }
        foamOwed = foamOwed.coerceAtMost(1f)
    }

    /** Marks the field's cells whose ground lies under the water, for the foam. */
    private fun wetMask() {
        val fieldColumns = currents.columns
        val fieldRows = currents.rows
        if (foamMask.size != fieldColumns * fieldRows) foamMask = FloatArray(fieldColumns * fieldRows)
        for (row in 0 until fieldRows) {
            val gy = (currents.yOf(row) * halfY + rows / 2f).toInt().coerceIn(0, rows - 1)
            for (column in 0 until fieldColumns) {
                val gx = (currents.xOf(column) * halfY + columns / 2f).toInt().coerceIn(0, columns - 1)
                val under = seaLevel - terrain[gy * columns + gx]
                foamMask[row * fieldColumns + column] = ((under - 0.3f) / 0.6f).coerceIn(0f, 1f)
            }
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
        // The ink keeps the water's own level, so at low tide its currents run on over the wet sand.
        inkSea = sea
        val low = lowTideNow()
        val out = max(low, buildUp(state))
        // Under reduced motion the coastline all but stays put, and low tide is a change of colour and light.
        sea += (SEA_DRY - sea) * out * state.motionScale * state.motionScale
        seaLevel = sea
        amber = out
        // The drop's gold current runs for as long as the low tide does.
        goldCurrent = out
        boost = LOW_TIDE_LIGHT * low
        light = state.lightScale * (IDLE_LIGHT + (1f - IDLE_LIGHT) * ((lightFor(state.energy) - 0.06f) / 0.94f).coerceIn(0f, 1f))
        // The wash follows the level alone, so a quiet map is darker than a loud one in its fill too.
        wash = WASH * ((lightFor(state.energy) - 0.06f) / 0.94f).coerceIn(0f, 1f)
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
        // The key leans every hue, and the genes' walk swings land and sea a little either way.
        val turn = keyLean.value + WALK_SWING * sin(genes.walk * TAU)
        val palette = state.palette
        // A palette that spans the whole colour circle, the default one, has no two ends to give a
        // sea and a land, so the map keeps its own swatches under it. Any other palette replaces them.
        val own = ((palette.hueSpan - 300f) / 60f).coerceIn(0f, 1f)
        val deepHue = 262f + turn
        // A chosen palette has no hue to turn, so the walk slides along its ramp instead: a tenth of
        // the ramp either way, which keeps sea and land apart and still moves every colour.
        val slide = 0.1f * sin(genes.walk * TAU)
        // Electric blue rather than a dark one: a 1.5 px line at a third of the light has to show on black.
        put(deep, colourOf(0.54f, min(0.24f, mostChroma(0.54f, deepHue) - 0.01f), deepHue), palette.vividRamp((0.1f + slide).coerceIn(0f, 1f)), own)
        put(shallow, vividColour(205f + turn, headroom = 0.01f), palette.vividRamp((0.5f + slide).coerceIn(0f, 1f)), own)
        put(land, vividColour(70f + turn, lift = 0.02f, headroom = 0.01f), palette.vividRamp((0.9f + slide).coerceIn(0f, 1f)), own)
        // The sea's ink lines: two cool hues, from the far blue of thin ink to the near cyan of thick ink.
        put(seaFar, vividColour(SEA_FAR_HUE + turn, headroom = 0.01f), palette.vividRamp((0.2f + slide).coerceIn(0f, 1f)), own)
        put(seaNear, vividColour(SEA_NEAR_HUE + turn, lift = 0.06f, headroom = 0.01f), palette.vividRamp((0.4f + slide).coerceIn(0f, 1f)), own)
        // Gold is the declared accent: the drop's current, the same under any palette.
        val gold = vividColour(GOLD_HUE, headroom = 0.01f)
        goldInk[0] = gold.red
        goldInk[1] = gold.green
        goldInk[2] = gold.blue
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
        program.uniform("uInkSea", inkSea)
        program.uniform("uInkSteps", INK_STEPS)
        program.uniform("uSeaNear", seaNear[0], seaNear[1], seaNear[2])
        program.uniform("uSeaFar", seaFar[0], seaFar[1], seaFar[2])
        program.uniform("uGoldInk", goldInk[0], goldInk[1], goldInk[2])
        program.uniform("uGoldCurrent", goldCurrent)
        program.uniform("uFoam", foam)
        program.uniform("uWash", wash)
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
            val bits = ((heights[cell] / TOP).coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
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
                wide[y * fineColumns + u] = spline(u) { heights[row + it.coerceIn(0, columns - 1)] }
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
        layOut(to, ARCHIPELAGO)
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
        currents.clear()
        vortexX.fill(0f)
        vortexY.fill(0f)
        vortexSpeed.fill(0f)
        vortexLevel.fill(0f)
        vortexCount = 2
        spin = 1f
        stillness = 0f
        combing = 0f
        sinceDrum = LONG_AGO
        sinceOnsetInk = LONG_AGO
        currentStrength = 1f
        inkSea = SEA_QUIET
        wash = 0f
        goldCurrent = 0f
        foam = 0f
        drops = 0
        past.clear()
        mapTime = 0.0
        scope = FloatArray(0)
        scopeGain = 1f
        form = ARCHIPELAGO
        glide = 1f
        raised = 0
        crackAge.fill(-1f)
        nextCrack = 0
        lace.reset()
        foamOwed = 0f
        seeded = false
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
            if (heights[y * columns + x] > seaLevel) landCells++
        }
        return if (seen == 0) 0f else landCells.toFloat() / seen
    }

    /** Drops of ink since the start, and where the last one landed in the field's centred units, for tests. */
    internal var drops = 0
        private set
    internal var lastDropX = 0f
        private set
    internal var lastDropY = 0f
        private set

    /** Ink in the sea at a point in the field's centred units (y down), for tests. */
    internal fun inkAt(x: Float, y: Float): Float = currents.inkAt(x, y)

    /** How fast the vortices stir now: the largest speed, in centred units a second. For tests. */
    internal val stirring: Float
        get() {
            var most = 0f
            for (vortex in 0 until vortexCount) most = max(most, abs(vortexSpeed[vortex]))
            return most
        }

    /** How far a pixel's ground lies under the water, in levels: positive under the sea, negative on land. */
    internal fun depthAt(pixelX: Float, pixelY: Float, width: Float, height: Float): Float {
        val cell = max(width / columns, height / rows)
        val gx = ((pixelX + 0.5f - width / 2f) / cell + columns / 2f).toInt().coerceIn(0, columns - 1)
        val gy = ((pixelY + 0.5f - height / 2f) / cell + rows / 2f).toInt().coerceIn(0, rows - 1)
        return seaLevel - heights[gy * columns + gx]
    }

    /** Pours rings of ink across the whole sea, for the test that ink is drawn only under the water. */
    internal fun pourInkForTest() {
        for (ring in 1..12) currents.ring(0f, 0f, ring * 0.15f, 2f, 1f)
    }

    /** How far the map has scrolled, in map seconds. Still in a pause or a silence. For tests. */
    internal val scroll: Double get() = mapTime

    /** The first grid row of the north strip and how many rows it has, for tests. */
    internal val northTop: Int get() = (rows / 2f - halfY).toInt().coerceAtLeast(0)
    internal val northRows: Int get() = (2f * halfY * NORTH_SHARE).toInt().coerceAtLeast(2)
    internal val gridColumns: Int get() = columns
    internal val gridRows: Int get() = rows

    /** The drawn ground at a grid cell, in levels, for tests. */
    internal fun heightAt(column: Int, row: Int): Float = heights[row * columns + column]

    /** The form on the map: Archipelago, Ridge, Crater or Delta. */
    internal val formName: String get() = FORMS[form]

    override val forms: FormReadout get() = FormReadout(FORMS[form], evolution.morphs, evolution.births)

    internal val groupsRaised: Int get() = raised
    internal val cracksOpen: Int
        get() {
            var open = 0
            for (slot in 0 until CRACKS) if (crackAge[slot] >= 0f) open++
            return open
        }
    internal val goldShare: Float get() = goldCurrent
    internal val foamLevel: Float get() = foam

    /** Cells of the field holding lace, for tests. */
    internal fun foamCells(): Int {
        val values = currents.extra ?: return 0
        var count = 0
        for (value in values) if (value > 0.25f) count++
        return count
    }

    /** Where island [island] of the newest layout sits, in grid cells, for tests. */
    internal fun islandX(island: Int): Float = to.x[island]
    internal fun islandY(island: Int): Float = to.y[island]

    /** Starts a morph to the next form, as the pacer would, for the test of the glide. */
    internal fun morphForTest() = morphTo((form + 1) % FORMS.size)

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

        /** The sea's memory field: 90 rows, so 160 by 90 on a 16 by 9 screen, and its ink's half life. */
        const val FIELD_ROWS = 90
        const val INK_HALF_LIFE = 3f
        /** Ink steps between two sea lines' levels: a line at every seventh of full ink. */
        const val INK_STEPS = 7f

        /** Marble's vortices in centred units: at most five, a stir of 0.11 and 0.36 more with the bass. */
        const val MOST_VORTICES = 5
        const val BASE_STIR = 0.11f
        const val BASS_STIR = 0.36f
        const val VORTEX_EASE_SECONDS = 0.6f
        const val STILL_SECONDS = 0.5f

        /** A kick's ink: round the loudest of the first eight islands, 1.2 to 1.6 island radii wide. */
        const val LOW_ISLANDS = 8
        const val INK_REACH = 1.2f
        const val INK_KICK_REACH = 0.4f
        const val ONSET_INK = 0.7f
        const val LONG_AGO = 99f

        /** The wash under the lines at full level, as a share of the line colours. *Judgement.* */
        const val WASH = 0.22f

        /** How far the genes' walk swings land and sea, and the sea and gold hues, in degrees. */
        const val WALK_SWING = 25f
        const val SEA_FAR_HUE = 238f
        const val SEA_NEAR_HUE = 192f
        const val GOLD_HUE = 85f

        /** The north strip: its share of the visible height, map seconds per grid row, and its highest relief in levels. */
        const val NORTH_SHARE = 0.28f
        const val MAP_SECONDS_PER_ROW = 0.5
        const val PAST_TOP = 6f
        const val PAST_ROWS = 600

        /** How fast map time runs at a motion rate of 1. */
        const val SCROLL_PACE = 1.6f

        /** The coast wave: how far round an island it reaches, its height in levels, and the band round the sea it touches. */
        const val COAST_REACH = 1f
        const val COAST_WAVE = 0.45f
        const val COAST_BAND = 2f

        /** The forms, in the order a morph moves through them. */
        val FORMS = listOf("Archipelago", "Ridge", "Crater", "Delta")
        const val ARCHIPELAGO = 0
        const val RIDGE = 1
        const val CRATER = 2
        const val DELTA = 3
        /** Islands in the crater's middle, and the golden angle that spreads them. */
        const val CRATER_CORE = 6
        const val GOLDEN_TURN = 2.3999631f
        /** The delta's islands are small, low, and in strong currents. */
        const val DELTA_SIZE = 0.12f
        const val DELTA_REST = 0.7f
        const val DELTA_STIR = 2f

        /** A birth: eight groups of eight bands, four islands to a group, clustered this far apart in layout units. */
        const val GROUPS = 8
        const val GROUP_BANDS = 8
        const val GROUP_ISLANDS = 4
        const val GROUP_SPREAD = 0.18f

        /** Snare cracks: at most four open, each this long in island radii, this deep and wide, healing over this long. */
        const val CRACKS = 4
        const val CRACK_REACH = 1.1f
        const val CRACK_DEPTH = 4f
        const val CRACK_WIDTH = 0.9f
        const val CRACK_SECONDS = 0.9f
        const val PI_F = 3.1415927f

        /** The foam: seeds at the start, reaction steps a second (more when loud), and at most this many a frame. */
        const val FOAM_SEEDS = 40
        const val FOAM_STEPS = 60f
        const val FOAM_LOUD_STEPS = 120f
        const val MOST_FOAM_STEPS = 3

        const val OPAQUE = -0x1000000
    }
}

/** Where the islands of one archipelago sit, how big they are and how high their shelves stand. */
private class Archipelago {
    /** Where each island sits in layout units: -1 to 1 across the island area and -1 to 1 down it. */
    val u = FloatArray(32)
    val v = FloatArray(32)
    /** The island's radius, as a share of the frame's shorter half. */
    val size = FloatArray(32)
    /** Which way the island's two bands stand apart, in radians. */
    val lean = FloatArray(32)
    /** How high the island's shelf stands at rest, in levels. */
    val rest = FloatArray(32)
    /** How far the island has risen out of the sea, 0 to 1. A birth starts its island group at 0. */
    val rise = FloatArray(32) { 1f }
    var seed = 0

    // The layout on the grid as last shaped: island centres and radii in cells, and the ground the
    // music does not move. The ground is rebuilt when the seed or the frame's shape changes; the
    // centres whenever an island moves.
    val x = FloatArray(32)
    val y = FloatArray(32)
    val radius = FloatArray(32)
    val relief = FloatArray(96 * 54)
    val grain = FloatArray(96 * 54)
    var shapedColumns = 0
    var shapedHalfX = 0f
    var shapedHalfY = 0f
    var shapedSeed = -1
    var placed = false

    fun copyFrom(other: Archipelago) {
        other.u.copyInto(u)
        other.v.copyInto(v)
        other.size.copyInto(size)
        other.lean.copyInto(lean)
        other.rest.copyInto(rest)
        other.rise.copyInto(rise)
        seed = other.seed
        shapedColumns = 0
        placed = false
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
