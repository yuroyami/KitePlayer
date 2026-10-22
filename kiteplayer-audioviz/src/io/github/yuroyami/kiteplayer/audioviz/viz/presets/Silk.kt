package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Silk, a port of "Silk" (codevember, day 21: "3D audio visualizer") by Matt DesLauriers.
 *
 * - Original: http://mattdesl.github.io/codevember/21.html
 * - Repository: https://github.com/mattdesl/codevember (src/21.js, src/shaders/21-line.vert,
 *   src/shaders/21-line.frag, src/gl/gl-line-3d.js)
 * - Licence: MIT, LICENSE.md: "Copyright (c) 2015 Matt DesLauriers"
 * - Year: 2015
 * - Also ported: Ashima simplex noise (MIT, Copyright (C) 2011 Ashima Arts), the background of
 *   gl-vignette-background 1.0.4 (MIT, Copyright (c) 2014 Matt DesLauriers) and glsl-hsl2rgb 1.1.0
 *   (MIT, Copyright (c) 2015 Jam3).
 * - Deviation: 50 ribbons, the author's own Safari setting, instead of 100, for phones at 1080p.
 * - Deviation: mouse orbit and zoom are dropped; the author's default camera is kept.
 * - Deviation: the twist and the noise run on heard seconds at a pace from the energy rather than
 *   the wall clock, so the strand stands still in silence and while paused, turns faster in loud
 *   music, and turns the other way after each change of form; reduced motion slows it too.
 * - Deviation: on a frame narrower than 16:9 the picture is scaled down about its centre, so the
 *   strand keeps the share of the width it has at 16:9 (about 80 percent) instead of being cropped.
 * - Deviation: each ribbon edge is feathered over one pixel, in place of the browser's multisampling.
 * - Deviation: the spectrum is the engine's bands, one per ERB band, read every frame and lifted by a
 *   square root toward the analyser's decibel scale, not a browser analyser.
 * - Deviation: colours are multiplied by the flash guard's light share, and their hues turn slowly
 *   with the music's walk.
 * - Deviation: a faint wash from the cool half of the palette lies over the paper.
 * - Deviation: where runtime shaders cannot run, the background is the same gradient without grain.
 *
 * Translucent cyan, violet and magenta ribbons twist around one horizontal strand over a light
 * grey vignette. The spectrum lies along the strand, bass at the left: a loud frequency fans the
 * ribbons apart and fattens the strand where it sits.
 *
 * The Cloth form weaves the same ribbons into a sheet of 50 rows. The front row is the spectrum
 * now, with the live waveform along its edge; each row behind it is the spectrum a sixteenth of a
 * second earlier, so the sheet reaches about three seconds back. The stage, a 20 degree view over
 * a floor with a soft shadow, comes from Fluctus, which this drawing absorbed.
 *
 * The music moves it between four forms, in turn: Cloth, Tube (the cloth rolled round the camera,
 * which looks down its axis), Veil (the cloth hung from above, time running down) and Strand. The
 * drawing opens on the Cloth, where a tone shows as the column it lifts and the rows wear the palette. A change of
 * form blends every point of the sheet from one placement to the other, and the strand fades into
 * the sheet. A drop lays the cloth out, a breakdown hangs it as a veil, and a birth weaves a second
 * strand in. The camera orbits on the slow cycle and moves closer in a bright mood; in the Tube it
 * rolls round the axis instead. The camera never moves on the beat.
 *
 * The hits move the sheet itself. A kick sends a crest from the front row to the back at sixty rows
 * a heard second, each kick its own crest. A snare pleats the sheet across, as Fluctus folded, and
 * a hat makes the threads shimmer. The bass billows the cloth in a slow wave; a drop lifts the
 * billow to full for a cycle, and a breakdown stills it until the music turns again.
 */
internal class Silk : Layered(
    name = "Silk",
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 2_115L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f,
        shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off
    override val paintsWholeScreen: Boolean get() = true
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Bands, VizProperty.Colour),
        // The dolly. The waveform's edge, the twist's pace, the pleat and the billow are too small
        // to measure at the declaration test's size, so they are not declared.
        VizDrive(VizDriver.Mood, VizProperty.Camera, response = VizResponse.Rate),
        // The crest and the shimmer.
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(1f)),
        VizDrive(VizDriver.HighHit, VizProperty.Texture, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        // The veil eases in, so a breakdown shows a few frames after it lands.
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f, delaySeconds = 0.15f)),
        silence = VizSilence.Still,
    )

    override val forms: FormReadout
        get() = FormReadout(FORMS[if (morph < 0.5f) formFrom else formTo], evolution.morphs, evolution.births)

    internal val strand = SilkStrand()
    /** The strand a birth weaves in between the first one's ribbons. */
    private val second = SilkStrand(lines = 25).also {
        it.phase = SilkStrand.PI_314 / 2f
        it.radiusScale = SECOND_RADIUS
    }
    private val mesh = TriangleMesh(maxVertices = BATCH * STEPS * 4, maxIndices = BATCH * (STEPS - 1) * 18)
    private val vignette: ShaderProgram by lazy { ShaderProgram(VIGNETTE) }
    private val fallback = VignetteGradient()
    private val stage = SilkStage()

    /** The page's `time`: the twist, on heard seconds at the music's pace. */
    private var twist = 0.0
    private var twistSign = 1f

    // The spectrum's past, one row per heard sixtieth, and the bands and waveform of this frame.
    private val history = History()
    private var heard = 0.0
    private var liveBands = FloatArray(0)
    private var liveScope = FloatArray(0)

    // The forms: the one shown before the morph, the one it moves to, and how far it has gone.
    private var formFrom = CLOTH
    private var formTo = CLOTH
    private var morph = 1f
    private var morphSeconds = 1f
    /** True when a new morph cut into one in flight: the start is then the sheet as it stood. */
    private var fromFrozen = false
    private val frozenX = FloatArray(ROWS * ACROSS)
    private val frozenY = FloatArray(ROWS * ACROSS)
    private val frozenZ = FloatArray(ROWS * ACROSS)
    private val frozenEye = FloatArray(3)
    private val frozenAim = FloatArray(3)

    // The birth: a second strand fading in, or thicker ribbons in a sheet form for a cycle.
    internal var strandCount = 1
        private set
    private var secondFade = 0f
    private var thicker = 0f
    private var cycleSeconds = 2f

    // The impacts. The crest's row runs from 0 at the front; -1 means no crest.
    /** The row the last kick's crest has reached, or -1 when there is none. */
    internal var crestAt = -1f
        private set
    private var crestStrength = 0f
    private var pleat = 0f
    private var shimmer = 0f
    private var billow = 0f
    private var billowHold = 0f
    private var inCollapse = false
    /** The heard steps so far, for the shimmer's flicker. */
    private var shimmerTick = 0

    // The camera: how present the music is, the orbit on the slow cycle, the dolly by mood.
    private val presence = Envelope(2f, 0.5f)
    private val orbit = Slew(ORBIT_SPEED)
    private val dolly = Slew(DOLLY_SPEED, 1f)
    private val viewEye = FloatArray(3)
    private val viewAim = FloatArray(3)
    private val shownEye = FloatArray(3)
    private val shownAim = FloatArray(3)
    private val point = FloatArray(3)

    // The cloth's lattice in world units, and each vertex's level from 0 to 1.
    private val worldX = FloatArray(ROWS * ACROSS)
    private val worldY = FloatArray(ROWS * ACROSS)
    private val worldZ = FloatArray(ROWS * ACROSS)
    private val rowLevel = FloatArray(ROWS * ACROSS)

    // One projected row of ribbon, reused for every row and its shadow.
    private val rowX = FloatArray(ACROSS)
    private val rowY = FloatArray(ACROSS)
    private val rowOffsetX = FloatArray(ACROSS)
    private val rowOffsetY = FloatArray(ACROSS)
    private val rowColour = IntArray(ACROSS)
    private val rowAlpha = FloatArray(ACROSS)

    /** The lowest screen y the cloth reached on the last frame, or -1 when it drew nothing. */
    private var clothBottom = -1f

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        if (liveBands.size != frame.bands.size) liveBands = FloatArray(frame.bands.size)
        frame.bands.copyInto(liveBands)
        if (liveScope.size != frame.scope.size) liveScope = FloatArray(frame.scope.size)
        frame.scope.copyInto(liveScope)
        strand.listen(liveBands)
        second.listen(liveBands)
        val step = state.stepSeconds
        val live = if (frame.held) 0f else dt
        heard += step
        history.push(liveBands, heard)
        cycleSeconds = gestures.cycleSeconds.coerceAtLeast(0.5f)

        // One frame can carry several form events, so they are read in one chain.
        if (evolution.bloom) morphTo(CLOTH, cycleSeconds)
        else if (evolution.collapse) morphTo(VEIL, cycleSeconds * 2f)
        else if (evolution.morph) morph()
        if (evolution.birth) birth()
        if (evolution.collapse) inCollapse = true else if (gestures.turn || gestures.surge) inCollapse = false
        if (evolution.bloom) {
            billow = 1f
            billowHold = cycleSeconds
        }
        impacts(step, frame.bass)
        if (morph < 1f) morph = (morph + step / morphSeconds.coerceAtLeast(0.1f)).coerceAtMost(1f)
        // The second strand fades in over half a cycle.
        if (strandCount == 2) secondFade = (secondFade + 2f * step / cycleSeconds).coerceAtMost(1f)
        thicker = (thicker - step / cycleSeconds).coerceAtLeast(0f)

        // time += min(30, dt) / 1000 on the page; here heard seconds at a pace from the energy.
        twist += step * TWIST_RATE * (0.2f + 0.8f * state.energy) * twistSign * state.motionScale.coerceIn(0f, 1f)

        // A pause holds the camera; a silence lets the presence fall and the camera settle home.
        presence.advance(frame.audible, live)
        val reach = presence.value * state.motionScale.coerceIn(0f, 1f)
        orbit.advance(ORBIT_REACH * sin(gestures.slowCyclePhase * TAU) * reach, live)
        dolly.advance(1f + DOLLY_REACH * (frame.mood - 0.5f) * reach, live)
        placeLattice()
    }

    /** Moves the crest, the pleat, the shimmer and the billow on heard time, then takes this frame's hits. */
    private fun impacts(step: Float, bass: Float) {
        if (crestAt >= 0f) {
            crestAt += step * CREST_ROWS_PER_SECOND
            if (crestAt > ROWS + CREST_WIDTH_ROWS) crestAt = -1f
        }
        if (gestures.kick > 0f) {
            crestAt = 0f
            crestStrength = gestures.kick
        }
        pleat *= exp(-step / PLEAT_SECONDS)
        if (gestures.snare > 0f) pleat = gestures.snare
        shimmer *= exp(-step / SHIMMER_SECONDS)
        if (gestures.hat > 0f) shimmer = gestures.hat
        if (step > 0f) shimmerTick++
        billowHold = (billowHold - step).coerceAtLeast(0f)
        val target = when {
            inCollapse -> 0f
            billowHold > 0f -> 1f
            else -> bass.coerceIn(0f, 1f)
        }
        billow += (target - billow) * (1f - exp(-step / BILLOW_SECONDS))
    }

    /** The crest's lift of [row] in world units. */
    internal fun rowLift(row: Int): Float {
        if (crestAt < 0f) return 0f
        val off = row - crestAt
        return crestStrength * CREST_LIFT * exp(-off * off / CREST_SPREAD)
    }

    internal val pleatValue: Float get() = pleat
    internal val shimmerValue: Float get() = shimmer
    internal val billowValue: Float get() = billow

    /** The shimmer's share of a thread's ink at [step] of [row]: 1 with no hat. */
    private fun shimmerAt(step: Int, row: Int): Float =
        1f + shimmer * SHIMMER * sin(step * 0.9f + row * 1.7f + shimmerTick * 0.9f)

    /** Shows [form] at once, with no morph. */
    internal fun jumpTo(form: Int) {
        formFrom = form
        formTo = form
        morph = 1f
        fromFrozen = false
        if (form != STRAND) strandCount = 1
    }

    /** Starts a morph to [form] over [seconds] from what is shown now. */
    internal fun morphTo(form: Int, seconds: Float = cycleSeconds * 2f) {
        if (morph < 1f) {
            // Cut into a morph in flight: freeze the sheet and the camera as they stand.
            worldX.copyInto(frozenX); worldY.copyInto(frozenY); worldZ.copyInto(frozenZ)
            shownEye.copyInto(frozenEye); shownAim.copyInto(frozenAim)
            formFrom = shownForm()
            fromFrozen = true
        } else {
            formFrom = formTo
            fromFrozen = false
        }
        formTo = form
        morph = 0f
        morphSeconds = seconds
        if (form != STRAND) {
            strandCount = 1
            secondFade = 0f
        }
    }

    /** The music's own change of form: the next form in turn, and the twist turns the other way. */
    internal fun morph() {
        morphTo((formTo + 1) % FORMS.size, cycleSeconds * (1f + 2f * random.next()))
        twistSign = -twistSign
    }

    /** A second strand woven in, or in a sheet form, thicker ribbons for a cycle. */
    internal fun birth() {
        if (formTo == STRAND) {
            if (strandCount == 1) secondFade = 0f
            strandCount = 2
        } else {
            thicker = 1f
        }
    }

    internal val morphValue: Float get() = morph
    internal val twistValue: Float get() = twist.toFloat()
    internal val twistSignValue: Float get() = twistSign
    internal val orbitValue: Float get() = orbit.value

    /** The world position of the shown sheet at [row] and [step], into [into]. */
    internal fun vertexWorld(row: Int, step: Int, into: FloatArray) {
        val index = row * ACROSS + step
        into[0] = worldX[index]; into[1] = worldY[index]; into[2] = worldZ[index]
    }

    /** The height of [row] at [across], 0 to 1 from bass to treble, in world units. Row 0 is now. */
    internal fun rowHeight(row: Int, across: Float): Float =
        if (row == 0) liveBands.sampleAt(across) * CLOTH_HEIGHT
        else history.sample(history.row(row * ROW_SECONDS), across) * CLOTH_HEIGHT

    /** The horizon's screen y on the last frame. */
    internal fun horizonY(): Float = stage.horizon

    internal fun clothBottomY(): Float = clothBottom

    /**
     * Places every point of the sheet. In a morph between two sheet forms each point blends from
     * one placement to the other; a morph to or from the strand keeps the sheet's own placement.
     */
    private fun placeLattice() {
        val eased = morph * morph * (3f - 2f * morph)
        val sheetTo = if (formTo == STRAND) formFrom else formTo
        val sheet = if (sheetTo == STRAND) CLOTH else sheetTo
        val blend = morph < 1f && formFrom != STRAND && formTo != STRAND
        for (row in 0 until ROWS) {
            val past = if (row == 0) -1 else history.row(row * ROW_SECONDS)
            val lift = rowLift(row)
            val swell = billow * BILLOW * sin(row / ROWS.toFloat() * PI.toFloat())
            for (step in 0 until ACROSS) {
                val across = step / (ACROSS - 1f)
                val level = if (row == 0) liveBands.sampleAt(across) else history.sample(past, across)
                val index = row * ACROSS + step
                rowLevel[index] = level.coerceIn(0f, 1f)
                // The front row carries the live waveform on its edge.
                val height = level * CLOTH_HEIGHT + if (row == 0) liveScope.sampleAt(across) * WAVE_EDGE else 0f
                val fold = pleat * PLEAT * sin(across * TAU * 6f + row * 0.4f)
                val wave = swell * sin(across * PI.toFloat())
                place(sheet, row, across, height, lift, fold, wave, point)
                if (blend) {
                    val toX = point[0]; val toY = point[1]; val toZ = point[2]
                    if (fromFrozen) {
                        point[0] = frozenX[index]; point[1] = frozenY[index]; point[2] = frozenZ[index]
                    } else {
                        place(formFrom, row, across, height, lift, fold, wave, point)
                    }
                    point[0] += (toX - point[0]) * eased
                    point[1] += (toY - point[1]) * eased
                    point[2] += (toZ - point[2]) * eased
                }
                worldX[index] = point[0]; worldY[index] = point[1]; worldZ[index] = point[2]
            }
        }
    }

    /**
     * Where [row] at [across] sits in [form], with [height] the level and the edge in world units.
     * The crest's [lift] raises every form, the pleat's [fold] moves it across, and the billow's
     * [wave] lifts only the cloth.
     */
    private fun place(form: Int, row: Int, across: Float, height: Float, lift: Float, fold: Float, wave: Float, into: FloatArray) {
        val depth = -row / (ROWS - 1f) * CLOTH_DEPTH + CLOTH_DEPTH * 0.3f
        when (form) {
            // The tube: across wraps round the axis, the level pulls a point toward it.
            TUBE -> {
                val theta = across * TAU
                val radius = TUBE_RADIUS - height * 0.5f - lift
                into[0] = sin(theta) * radius + fold
                into[1] = TUBE_AXIS_Y + cos(theta) * radius
                into[2] = depth
            }
            // The veil: hung from above, time running down, the level pushing it toward the viewer.
            VEIL -> {
                into[0] = (across - 0.5f) * CLOTH_WIDTH + fold
                into[1] = CLOTH_Y + 60f - row / (ROWS - 1f) * CLOTH_DEPTH * 0.6f + lift
                into[2] = -20f + height * 0.4f
            }
            // The cloth: across to x, time back along z, the level up.
            else -> {
                into[0] = (across - 0.5f) * CLOTH_WIDTH + fold
                into[1] = CLOTH_Y + height + lift + wave
                into[2] = depth
            }
        }
    }

    /** The camera of [form] for this frame's size, into [viewEye] and [viewAim]. */
    private fun view(form: Int, width: Float, height: Float) {
        if (form == TUBE) {
            viewEye[0] = 0f; viewEye[1] = TUBE_AXIS_Y; viewEye[2] = CLOTH_DEPTH * 0.3f - 8f
            viewAim[0] = 0f; viewAim[1] = TUBE_AXIS_Y; viewAim[2] = -CLOTH_DEPTH
            return
        }
        val elevation = if (form == VEIL) VEIL_ELEVATION else SilkStage.ELEVATION_DEGREES
        stage.stageView(width, height, dolly.value, orbit.value, elevation)
        stage.eye.copyInto(viewEye)
        stage.aim.copyInto(viewAim)
    }

    /** Sets the stage's camera: the two forms' cameras blended, then the lens. In the Tube the orbit is a roll. */
    private fun placeCamera(width: Float, height: Float) {
        val eased = morph * morph * (3f - 2f * morph)
        val to = if (formTo == STRAND) (if (formFrom == STRAND) CLOTH else formFrom) else formTo
        view(to, width, height)
        viewEye.copyInto(shownEye); viewAim.copyInto(shownAim)
        var tube = if (to == TUBE) 1f else 0f
        if (morph < 1f && formFrom != STRAND && formTo != STRAND) {
            if (fromFrozen) {
                frozenEye.copyInto(viewEye); frozenAim.copyInto(viewAim)
            } else {
                view(formFrom, width, height)
            }
            for (i in 0..2) {
                shownEye[i] = viewEye[i] + (shownEye[i] - viewEye[i]) * eased
                shownAim[i] = viewAim[i] + (shownAim[i] - viewAim[i]) * eased
            }
            tube = (if (formFrom == TUBE) 1f - eased else 0f) + (if (formTo == TUBE) eased else 0f)
        }
        shownEye.copyInto(stage.eye); shownAim.copyInto(stage.aim)
        stage.look(width, height, orbit.value * tube)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val light = state.lightScale.coerceIn(0f, 1f)
        drawPaper(light)
        if (shownForm() != STRAND) {
            placeCamera(size.width, size.height)
            with(stage) { drawHorizon(light, SilkStage.paper(light)) }
        }
        // A faint wash of the palette over the paper, walked by the genes; the paper keeps its light.
        val wash = state.palette.argb(WASH_POSITION + genes.walk * 0.25f, saturation = WASH_SATURATION)
        drawRect(Color(
            red = (wash shr 16 and 0xFF) / 255f * light,
            green = (wash shr 8 and 0xFF) / 255f * light,
            blue = (wash and 0xFF) / 255f * light,
            alpha = WASH,
        ))
    }

    private fun DrawScope.drawPaper(light: Float) {
        if (vignette.available && canDrawRuntimeShaders()) {
            vignette.uniform("uResolution", size.width, size.height)
            vignette.uniform("uLight", light)
            val brush = vignette.brush()
            if (brush != null) {
                drawRect(brush)
                return
            }
        }
        drawRect(fallback.brush(size.width, size.height, light))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (size.width <= 0f || size.height <= 0f) return
        val light = state.lightScale.coerceIn(0f, 1f)
        val eased = morph * morph * (3f - 2f * morph)
        // The strand's share of the ink: all of it in the Strand form, none in a sheet form.
        val strandWeight = when {
            formTo == STRAND -> if (formFrom == STRAND) 1f else eased
            formFrom == STRAND -> 1f - eased
            else -> 0f
        }
        if (strandWeight > 0f) drawStrand(light, strandWeight)
        clothBottom = -1f
        if (strandWeight < 1f) drawCloth(state, light, 1f - strandWeight)
    }

    private fun DrawScope.drawStrand(light: Float, weight: Float) {
        drawStrand(strand, light, weight)
        if (strandCount == 2 && secondFade > 0f) drawStrand(second, light, weight * secondFade)
    }

    private fun DrawScope.drawStrand(one: SilkStrand, light: Float, weight: Float) {
        one.frame(size.width, size.height, twist.toFloat())
        // The walk turns the strand's hues, slowly, over minutes of music.
        one.hueTurn = wrap(genes.walk)
        // Painter's order by line index, with no depth test, as on the page.
        for (first in 0 until one.lines step BATCH) {
            mesh.clear()
            for (line in first until minOf(first + BATCH, one.lines)) {
                one.line(line)
                for (step in 0 until STEPS) rowAlpha[step] = OPACITY * weight * shimmerAt(step, line)
                addRibbon(one.centreX, one.centreY, one.offsetX, one.offsetY, one.colour, rowAlpha, light)
            }
            drawMesh(mesh)
        }
    }

    /** The sheet forms: every row's shadow on the floor first, then the rows from the back to the front. */
    private fun DrawScope.drawCloth(state: VizRenderState, light: Float, weight: Float) {
        placeCamera(size.width, size.height)
        mesh.clear()
        var batched = 0
        rowColour.fill(SHADOW_RGB)
        rowAlpha.fill(SHADOW_INK * weight)
        for (row in ROWS - 1 downTo 0) {
            if (!projectRow(row, shadow = true)) continue
            addRibbon(rowX, rowY, rowOffsetX, rowOffsetY, rowColour, rowAlpha, light)
            if (++batched == BATCH) { drawMesh(mesh); mesh.clear(); batched = 0 }
        }
        if (batched > 0) { drawMesh(mesh); mesh.clear(); batched = 0 }
        for (row in ROWS - 1 downTo 0) {
            if (!projectRow(row, shadow = false)) continue
            val rgb = state.palette.argb(row / (ROWS - 1f) * 0.5f + genes.walk, saturation = 0.55f, value = 0.45f) and 0xFFFFFF
            rowColour.fill(rgb)
            for (step in 0 until ACROSS) rowAlpha[step] = OPACITY * (0.35f + 0.65f * rowLevel[row * ACROSS + step]) * weight * shimmerAt(step, row)
            addRibbon(rowX, rowY, rowOffsetX, rowOffsetY, rowColour, rowAlpha, light)
            for (step in 0 until ACROSS) if (!rowX[step].isNaN()) clothBottom = maxOf(clothBottom, rowY[step])
            if (++batched == BATCH) { drawMesh(mesh); mesh.clear(); batched = 0 }
        }
        if (batched > 0) drawMesh(mesh)
    }

    /**
     * Projects [row], or its floor shadow, into [rowX] and [rowY], with the ribbon's half thickness as
     * an offset across the row. A point outside the lens is NaN, which breaks the ribbon there.
     */
    private fun projectRow(row: Int, shadow: Boolean): Boolean {
        // The ribbon's thickness in pixels, from the middle of the row projected twice.
        val middle = row * ACROSS + ACROSS / 2
        val x = worldX[middle]; val y = worldY[middle]; val z = worldZ[middle]
        val lower = if (shadow) stage.shadow(x, y, z) else stage.project(x, y, z)
        if (!lower) return false
        val lowerY = stage.screenY
        val upper = if (shadow) stage.shadow(x, y, z - RIBBON_WORLD) else stage.project(x, y + RIBBON_WORLD, z)
        if (!upper) return false
        val thickness = maxOf(abs(stage.screenY - lowerY), 0.5f) * (1f + thicker)
        var any = false
        for (step in 0 until ACROSS) {
            val index = row * ACROSS + step
            val seen = if (shadow) stage.shadow(worldX[index], worldY[index], worldZ[index])
                else stage.project(worldX[index], worldY[index], worldZ[index])
            if (seen) {
                rowX[step] = stage.screenX
                rowY[step] = stage.screenY
                any = true
            } else {
                rowX[step] = Float.NaN
                rowY[step] = Float.NaN
            }
        }
        for (step in 0 until ACROSS) {
            val previous = if (step > 0 && !rowX[step - 1].isNaN()) step - 1 else step
            val next = if (step < ACROSS - 1 && !rowX[step + 1].isNaN()) step + 1 else step
            val dx = rowX[next] - rowX[previous]
            val dy = rowY[next] - rowY[previous]
            val length = sqrt(dx * dx + dy * dy)
            if (length > 0f && length.isFinite()) {
                rowOffsetX[step] = -dy / length * thickness * 0.5f
                rowOffsetY[step] = dx / length * thickness * 0.5f
            } else {
                rowOffsetX[step] = 0f
                rowOffsetY[step] = thickness * 0.5f
            }
        }
        return any
    }

    private fun shownForm(): Int = if (morph < 0.5f) formFrom else formTo

    /**
     * One ribbon through the points of [centreX] and [centreY], [offsetX] and [offsetY] from the
     * centre to one edge. Each point gets a core at its [alpha] and a one-pixel fringe on both edges,
     * which keeps the ribbon's coverage when it is under a pixel. A NaN point breaks the ribbon.
     */
    private fun addRibbon(
        centreX: FloatArray, centreY: FloatArray, offsetX: FloatArray, offsetY: FloatArray,
        colour: IntArray, alpha: FloatArray, light: Float,
    ) {
        var before = -1
        for (i in 0 until STEPS) {
            val cx = centreX[i]
            val cy = centreY[i]
            if (cx.isNaN()) {
                before = -1
                continue
            }
            val ox = offsetX[i]
            val oy = offsetY[i]
            val half = sqrt(ox * ox + oy * oy)
            val ux = if (half > 0f) ox / half else 0f
            val uy = if (half > 0f) oy / half else 1f
            val inner = maxOf(half - 0.5f, 0f)
            val outer = half + 0.5f
            val opacity = alpha[i]
            val peak = if (half >= 0.5f) opacity else opacity * 4f * half / (2f * half + 1f)
            val rgb = colour[i]
            val red = channel((rgb shr 16 and 0xFF) / 255f * light)
            val green = channel((rgb shr 8 and 0xFF) / 255f * light)
            val blue = channel((rgb and 0xFF) / 255f * light)
            val solid = (channel(peak) shl 24) or (red shl 16) or (green shl 8) or blue
            val clear = (red shl 16) or (green shl 8) or blue
            val a = mesh.vertex(cx + ux * outer, cy + uy * outer, clear)
            mesh.vertex(cx + ux * inner, cy + uy * inner, solid)
            mesh.vertex(cx - ux * inner, cy - uy * inner, solid)
            mesh.vertex(cx - ux * outer, cy - uy * outer, clear)
            if (before >= 0 && a >= 0) {
                mesh.quad(before + 1, a + 1, a, before)
                mesh.quad(before + 1, a + 1, a + 2, before + 2)
                mesh.quad(before + 2, a + 2, a + 3, before + 3)
            }
            before = a
        }
    }

    override fun onReset() {
        strand.reset()
        second.reset()
        twist = 0.0
        twistSign = 1f
        history.clear()
        heard = 0.0
        liveBands.fill(0f)
        liveScope.fill(0f)
        formFrom = CLOTH
        formTo = CLOTH
        morph = 1f
        morphSeconds = 1f
        fromFrozen = false
        strandCount = 1
        secondFade = 0f
        thicker = 0f
        cycleSeconds = 2f
        presence.reset()
        orbit.reset()
        dolly.reset(1f)
        clothBottom = -1f
        crestAt = -1f
        crestStrength = 0f
        pleat = 0f
        shimmer = 0f
        billow = 0f
        billowHold = 0f
        inCollapse = false
        shimmerTick = 0
    }

    internal companion object {
        /** Ribbons drawn per call, so every corner number fits the triangle call's 16 bits. */
        const val BATCH = 25
        const val STEPS = SilkStrand.STEPS
        const val OPACITY = 0.5f

        // The cloth: 50 rows a sixteenth of a second apart, 200 points across, in world units.
        const val ROWS = 50
        const val ROW_SECONDS = 1f / 16f
        const val ACROSS = SilkStrand.STEPS
        const val CLOTH_WIDTH = 100f
        const val CLOTH_DEPTH = 120f
        const val CLOTH_HEIGHT = 28f
        const val CLOTH_Y = 0f
        /** A row's ribbon thickness. */
        const val RIBBON_WORLD = 1.4f
        /** How far the live waveform lifts the front row's edge. */
        const val WAVE_EDGE = 6f
        /** The shadow's ink, a dark grey. */
        const val SHADOW_INK = 0.12f
        const val SHADOW_RGB = 0x3A3A46
        /** The palette wash over the paper: its opacity and its colour's saturation. */
        const val WASH = 0.15f
        const val WASH_SATURATION = 0.6f
        /** Where on the palette the wash sits: the cool half, as the page's cyan and violet. */
        const val WASH_POSITION = 0.55f

        // The Tube's radius and axis, and the Veil's lower camera.
        const val TUBE_RADIUS = 26f
        const val TUBE_AXIS_Y = CLOTH_Y + 10f
        const val VEIL_ELEVATION = 8f

        // The impacts: the crest in rows and world units, the pleat, the shimmer and the billow.
        const val CREST_ROWS_PER_SECOND = 60f
        const val CREST_LIFT = 12f
        const val CREST_SPREAD = 6f
        const val CREST_WIDTH_ROWS = 8f
        const val PLEAT = 4f
        const val PLEAT_SECONDS = 0.25f
        const val SHIMMER = 0.5f
        const val SHIMMER_SECONDS = 0.4f
        const val BILLOW = 10f
        const val BILLOW_SECONDS = 0.3f

        /** The twist's pace in the page's time units a heard second, at no energy to full. */
        const val TWIST_RATE = 0.6f

        /** How much wider the second strand of a birth wraps than the first: twice, so it reads as a second strand. */
        const val SECOND_RADIUS = 2f

        // The orbit's reach in radians and its fastest turn, and the dolly's reach and speed.
        const val ORBIT_REACH = 0.4f
        const val ORBIT_SPEED = 0.2f
        const val DOLLY_REACH = 0.15f
        const val DOLLY_SPEED = 0.3f

        /** The forms in the order the music moves through them; the drawing opens on the Cloth. */
        val FORMS = listOf("Cloth", "Tube", "Veil", "Strand")
        const val CLOTH = 0
        const val TUBE = 1
        const val VEIL = 2
        const val STRAND = 3

        fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        /** gl-vignette-background 1.0.4 as the page styled it, with glsl-random's hash. */
        const val VIGNETTE = """
uniform float2 uResolution;
uniform float uLight;

float random(float2 co) {
    float a = 12.9898;
    float b = 78.233;
    float c = 43758.5453;
    float dt = dot(co.xy, float2(a, b));
    float sn = mod(dt, 3.14);
    return fract(sin(sn) * c);
}

float3 blendOverlay(float3 base, float3 blend) {
    return float3(
        base.r < 0.5 ? (2.0 * base.r * blend.r) : (1.0 - 2.0 * (1.0 - base.r) * (1.0 - blend.r)),
        base.g < 0.5 ? (2.0 * base.g * blend.g) : (1.0 - 2.0 * (1.0 - base.g) * (1.0 - blend.g)),
        base.b < 0.5 ? (2.0 * base.b * blend.b) : (1.0 - 2.0 * (1.0 - base.b) * (1.0 - blend.b)));
}

half4 main(float2 position) {
    // The page's quad puts texture v = 0 at the top, so vUv is the pixel over the size.
    float2 vUv = position / uResolution;
    float size = min(uResolution.x, uResolution.y) * 1.5;
    float2 scale = float2(size / uResolution.x, size / uResolution.y);
    float2 pos = vUv - 0.5;
    pos /= scale;
    pos -= float2(-0.05, -0.15);
    float dist = smoothstep(-0.5, 1.0, 1.0 - length(pos));
    float3 color = mix(float3(0.30980392), float3(1.0), dist);
    float3 noise = float3(random(vUv * 1.5), random(vUv * 2.5), random(vUv));
    color = mix(color, blendOverlay(color, noise), 0.1);
    return half4(half3(color * uLight), 1.0);
}
"""
    }
}

/**
 * The strand's geometry, one line at a time: the page's vertex shader and its `line3D`, run on
 * the processor for the 200 points of a ribbon.
 */
internal class SilkStrand(val lines: Int = 50) {
    /** Each point's x, `lerp(-1, 1, i / 199)`. */
    val x = FloatArray(STEPS) { (-1.0 * (1.0 - it / (STEPS - 1.0)) + it / (STEPS - 1.0)).toFloat() }

    /** `smoothstep(0, 0.5, 1 - |x|)`, which closes the strand at both ends. */
    private val pinch = FloatArray(STEPS) { smoothstep(0f, 0.5f, 1f - kotlin.math.abs(x[it])) }

    /** The spectrum under each point, sampled at `u = x * 0.5 + 0.5`. */
    val frequencies = FloatArray(STEPS)

    /** Where each point of the current line lands, in pixels. */
    val centreX = FloatArray(STEPS)
    val centreY = FloatArray(STEPS)

    /** The pixel offset from the centre to the ribbon's `direction = +1` side. */
    val offsetX = FloatArray(STEPS)
    val offsetY = FloatArray(STEPS)

    /** Each point's colour as packed RGB, from `hsl(mix(0.5, 0.9, sin(angle) * 0.5 + 0.5), 0.7, 0.6)`. */
    val colour = IntArray(STEPS)

    private var aspect = 16f / 9f
    private var middleX = 0f
    private var middleY = 0f
    private var halfWidth = 0f
    private var halfHeight = 0f
    private var time = 0f

    /** Added to every ribbon's angle, so a second strand weaves between the first one's ribbons. */
    var phase = 0f

    /** Scales every ribbon's distance from the axis, so a second strand wraps round the first. */
    var radiusScale = 1f

    /** Turns every ribbon's hue, as a share of the colour wheel. */
    var hueTurn = 0f

    /**
     * Reads the bands under each point at `u = x * 0.5 + 0.5`, where the page read its analyser's
     * bytes. The square root lifts the bands toward the analyser's decibel scale, where most bins of
     * music sit above half.
     */
    fun listen(bands: FloatArray) {
        for (i in 0 until STEPS) frequencies[i] = sqrt(bands.sampleAt(x[i] * 0.5f + 0.5f).coerceAtLeast(0f))
    }

    /** Sets the canvas and the page's `iGlobalTime` for the lines that follow. */
    fun frame(width: Float, height: Float, time: Float) {
        aspect = width / height
        // The fit for frames narrower than 16:9. It is 1 on wider frames, which the page drew as they are.
        val fit = minOf(1f, aspect / FRAMING_ASPECT)
        middleX = width * 0.5f
        middleY = height * 0.5f
        halfWidth = width * 0.5f * fit
        halfHeight = height * 0.5f * fit
        this.time = time
    }

    /** Works out line [index] of [lines] into [centreX], [centreY], [offsetX], [offsetY] and [colour]. */
    fun line(index: Int) {
        val position = index / (lines - 1f)
        val fx = FOCAL / aspect
        for (i in 0 until STEPS) {
            val px = x[i]
            val freq = frequencies[i]
            val computedThickness = THICKNESS * pinch[i]
            // angle = 2 PI x * twists + index * PI * mix(0.5, 1.5, freq) + time, with PI as 3.14.
            val angleOffset = position * PI_314 * mix(0.5f, 1.5f, freq) + phase
            val angle = 2f * PI_314 * px * 0.5f + angleOffset + time
            var radius = RADIUS
            if (freq != 0f) radius += freq * 0.5f * AshimaNoise.simplex3(px, angle, time * 0.2f)
            radius += 0.1f * AshimaNoise.simplex3(px * 6f, angle, time * 0.25f)
            radius += 0.1f * AshimaNoise.simplex3(px * 2.5f, angle, time * 0.5f)
            radius *= pinch[i]
            radius *= mix(0.65f, 1f, freq) * radiusScale
            val sine = sin(angle)
            val y = cos(angle) * radius
            val z = sine * radius

            // line3D: the camera sits at (0, 0, 0.5) looking at the origin, so clip w is 0.5 - z.
            val clipX = fx * px
            val clipY = FOCAL * y
            val clipW = CAMERA_DISTANCE - z
            // The page adds this point's offset to previous and next as well, so all three share
            // its screen y and w, and the direction (miter off) always runs along x.
            val currentX = clipX / clipW * aspect
            val previousX = fx * x[maxOf(i - 1, 0)] / clipW * aspect
            val nextX = fx * x[minOf(i + 1, STEPS - 1)] / clipW * aspect
            var dirX = if (currentX == previousX) nextX - currentX else currentX - previousX
            var dirY = 0f
            val length = sqrt(dirX * dirX + dirY * dirY)
            if (length > 0f && length.isFinite()) {
                dirX /= length
                dirY /= length
            } else {
                dirX = 1f
                dirY = 0f
            }
            var normalX = -dirY * computedThickness / 2f
            val normalY = dirX * computedThickness / 2f
            normalX /= aspect
            // The page's quirk, kept: line3D returns currentProjected + vec4(offset, 0.0, 1.0).
            val w = clipW + 1f
            centreX[i] = middleX + halfWidth * silkNdc(clipX, clipW)
            centreY[i] = middleY - halfHeight * silkNdc(clipY, clipW)
            offsetX[i] = halfWidth * normalX / w
            offsetY[i] = -halfHeight * normalY / w
            colour[i] = hslRgb(wrap(mix(0.5f, 0.9f, sine * 0.5f + 0.5f) + hueTurn), 0.7f, 0.6f)
        }
    }

    fun reset() {
        frequencies.fill(0f)
    }

    companion object {
        const val STEPS = 200
        const val RADIUS = 0.1f
        const val THICKNESS = 0.01f

        /** The shader's `#define PI 3.14`. */
        const val PI_314 = 3.14f

        /** The page's orbit distance: the camera sits this far from the origin on z. */
        const val CAMERA_DISTANCE = 0.5f

        /** `1 / tan(fov / 2)` for the page's vertical field of view of 50 degrees. */
        val FOCAL: Float = (1.0 / tan(25.0 * PI / 180.0)).toFloat()

        /** The widest frame the fit leaves alone. */
        const val FRAMING_ASPECT = 16f / 9f
    }
}

/**
 * A clip coordinate over the page's `w + 1`. The page's `line3D` adds 1.0 to w, which shrinks the
 * 2-unit strand to about a third of its perspective size and flattens it. It is the page's framing.
 */
internal fun silkNdc(clip: Float, clipW: Float): Float = clip / (clipW + 1f)

private fun mix(from: Float, to: Float, amount: Float): Float = from * (1f - amount) + to * amount

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** glsl-hsl2rgb 1.1.0, packed as RGB. */
internal fun hslRgb(hue: Float, saturation: Float, lightness: Float): Int {
    val red: Float
    val green: Float
    val blue: Float
    if (saturation == 0f) {
        red = lightness
        green = lightness
        blue = lightness
    } else {
        val f2 = if (lightness < 0.5f) lightness * (1f + saturation)
            else lightness + saturation - saturation * lightness
        val f1 = 2f * lightness - f2
        red = hueChannel(f1, f2, hue + 1f / 3f)
        green = hueChannel(f1, f2, hue)
        blue = hueChannel(f1, f2, hue - 1f / 3f)
    }
    fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
}

private fun hueChannel(f1: Float, f2: Float, hueIn: Float): Float {
    var hue = hueIn
    if (hue < 0f) hue += 1f else if (hue > 1f) hue -= 1f
    return when {
        6f * hue < 1f -> f1 + (f2 - f1) * 6f * hue
        2f * hue < 1f -> f2
        3f * hue < 2f -> f1 + (f2 - f1) * ((2f / 3f) - hue) * 6f
        else -> f1
    }
}

/** The vignette as a radial gradient, for canvases where the shader cannot run. Kept between frames. */
private class VignetteGradient {
    private var width = -1f
    private var height = -1f
    private var light = -1f
    private var cached: Brush? = null

    fun brush(width: Float, height: Float, light: Float): Brush {
        val kept = cached
        if (kept != null && width == this.width && height == this.height && light == this.light) return kept
        val size = minOf(width, height) * 1.5f
        val stops = Array(STOPS + 1) { k ->
            val fraction = k / STOPS.toFloat()
            // smoothstep(-0.5, 1.0, 1 - dist) with dist = 1.5 * fraction, in units of size.
            val shade = smoothstep(-0.5f, 1f, 1f - 1.5f * fraction)
            val grey = (DARK + (1f - DARK) * shade) * light
            fraction to Color(grey, grey, grey)
        }
        val made = Brush.radialGradient(
            *stops,
            center = Offset(width * 0.5f - 0.05f * size, height * 0.5f - 0.15f * size),
            radius = 1.5f * size,
        )
        this.width = width
        this.height = height
        this.light = light
        cached = made
        return made
    }

    private companion object {
        const val STOPS = 16
        const val DARK = 79f / 255f
    }
}
