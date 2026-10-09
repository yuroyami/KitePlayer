package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Evolution
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lines, by Silvio Paganini (FLUUUID), ported with credit.
 *
 * - Title: Lines
 * - Author: Silvio Paganini (@silviopaganini), for the FLUUUID collective
 * - URL: https://labs.fluuu.id/lines/
 * - Repository: https://github.com/fluuuid/labs, folder `lines/`
 * - Licence as found: ISC declared in package.json, no license file
 * - Year: 2015
 * - The page names the cover art of Joy Division's Unknown Pleasures as its inspiration.
 *
 * Deviations from the original:
 * - The orbit controls are dropped. The camera keeps the author's default: (0, 45, 240), looking at the origin.
 * - [WebAudioAnalyser] rebuilds the page's analyser from the player's spectrum, with the desktop settings:
 *   an FFT of 2048, smoothing 0.8, -100 to -30 dB. The phone setting of the page (an FFT of 256) is not ported.
 * - The analyser read, the noise re-roll and the easing run 60 times per second of heard music, not once
 *   per browser frame. The lines hold still while the music is paused or silent.
 * - The easing of 0.04 per page frame is scaled by frame time, so a 120 Hz screen moves as fast as a 60 Hz one.
 * - The fog is set once per line, at the floor height of 5, not per pixel. A peak is up to 3 percent of
 *   full white darker than on the page.
 * - Each line is 1 device pixel wide. The page asks for 3, but WebGL draws 1 in the common browsers.
 * - The world width is half the longer side of the canvas in device pixels, and the page measured its window
 *   in CSS pixels. The two are the same on a desktop screen with one device pixel per CSS pixel, so a 1080p
 *   phone frames the lines as a 1080p desktop did, and a 160 dp preview tile still shows the whole stack.
 *   The page measured its window once. This drawing measures the canvas every frame.
 * - A seeded generator gives the noise instead of `Math.random()`, so a render repeats.
 * - The flash guard's light scale multiplies the brightness. It is 1 unless the picture would flash.
 *
 * World: the road bends with the stereo balance and dips with the level. The farthest line moves most,
 * and the nearest line does not move. The camera glides on the slow cycle and comes home in a silence.
 * Far quiet lines take a tint from the palette through the fog, and a line with a peak stays white.
 * A kick lifts the two middle lines, and the lift runs outward through the stack one line per page frame.
 * A snare raises every peak by about a third for a beat.
 * At rest, the Flat picture is the port's picture.
 * The forms are Flat, Tunnel (the lines wrap round the viewer), Mirror (the Flat picture reflected below)
 * and Fan (the lines radiate from a far point, the bass lines nearest).
 * The evolution pacer takes turns through them over four cycles. Weights blend the forms, so a morph never jumps.
 * Lines has no birth, by the spec's decision. The form on screen is published through `forms`.
 *
 * Twenty-two thin white lines stand front to back in black fog, seen from a slightly raised camera.
 * Each line wobbles flat at its ends and rises into mirrored peaks at its centre. The middle lines of
 * the stack read the bass, and the front and back lines read the treble. A frequency bin lifts its line
 * only above byte 100 of 255, so a line is either on its floor or in a peak. Nothing is filled, so a
 * tall ridge in front shows the lines behind it.
 */
internal class Lines : Visualization {

    override val name: String = "Lines"
    override val bucket: VizEnergy = VizEnergy.Calm
    override val post: PostSpec = PostSpec.Off

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // The level is a share of the analyser's byte range: above byte 100 a bin raises a peak.
            VizDrive(VizDriver.Bands, VizProperty.Shape, VizCurve.Threshold(KNEE / 255f), VizResponse.envelope(EASE_SECONDS)),
            // The level dips the road.
            VizDrive(VizDriver.Level, VizProperty.Shape, response = VizResponse.envelope(1.2f)),
            // The mood sets the pace of the camera's glide on the slow cycle.
            VizDrive(VizDriver.Mood, VizProperty.Camera, response = VizResponse.Rate),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 2048` on the desktop, and the Web Audio defaults for the rest. */
    internal val analyser = WebAudioAnalyser(fftSize = 2048, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f)

    private val step = DisplayStep()
    private val pageFrames = PageFrames()
    private val noise = PerlinNoise(NOISE_POINTS)
    private var random = Random(SEED)

    /** The 386 heights of `generatedPoints`: noise, the band from its highest bin down, then all of it mirrored. */
    private val heights = FloatArray(CONTROL_POINTS)

    /** Where each line's 512 vertices are heading and where they are, as heights above the line. */
    private val targets = Array(LINES) { FloatArray(POINTS) }
    private val vertices = Array(LINES) { FloatArray(POINTS) }
    private var ready = false

    // THREE.SplineCurve3.getPoint at t = d / 511: the four control points vertex d reads, and its weight.
    private val point0 = IntArray(POINTS)
    private val point1 = IntArray(POINTS)
    private val point2 = IntArray(POINTS)
    private val point3 = IntArray(POINTS)
    private val weight = FloatArray(POINTS)

    private val pointX = FloatArray(POINTS)
    private var sizeX = Float.NaN

    // Line.js: `mesh.position.y = -20 + y` with `y = -SIZE.y / 2 + i`, and `mesh.position.z = (16 - index) * -20`.
    private val lineY = FloatArray(LINES) { -20f + (-SIZE_Y / 2f + it) }
    private val lineZ = FloatArray(LINES) { (16 - it) * -20f }
    private val visibility = FloatArray(LINES) { fogVisibility(lineY[it] + FLOOR, lineZ[it]) }

    private val scene = Scene3D()
    private val paths = Array(LINES) { Path() }
    private val hairline = Stroke(width = Stroke.HairlineWidth)

    // The shared pieces every world drawing reads. A later task reads the recipe; it is not named `genes`
    // because Visualization already has a public member of that name.
    private val gestures = Gestures()
    private val evolution = Evolution()
    private val recipe = Genes(SEED)

    // The road's bend, from the stereo balance.
    private val bend = Slew(maxPerSecond = 0.4f)
    internal val bendValue: Float get() = bend.value

    // The road's dip, from the level.
    private val dip = Envelope(attackPerSecond = 1.5f, releasePerSecond = 0.8f)
    internal val dipValue: Float get() = dip.value

    // How much of the camera's glide is on. It follows `audible`, so a silence brings the camera home.
    private val present = Envelope(attackPerSecond = 2f, releasePerSecond = 2f)

    // 1 for a line whose band has a bin above the knee this frame, eased so the tint does not flicker.
    private val peaked = Array(LINES) { Envelope() }
    private val peakedTarget = FloatArray(LINES)

    // Where the road moves each line this frame. Line 21 never moves and line 0 moves most.
    private val roadX = FloatArray(LINES)
    private val roadY = FloatArray(LINES)

    // A kick's lift on each line, 0 to 1, which runs outward one line per page frame.
    private var wave = FloatArray(LINES)
    private var waveNext = FloatArray(LINES)
    internal fun waveAt(line: Int): Float = wave[line]

    // The shape of the lift along a line: a bell at the middle.
    private val bell = FloatArray(POINTS)

    // A snare's raise of the peaks, 1 at the hit and gone within a beat.
    private var sharpen = 0f
    internal val sharpenValue: Float get() = sharpen
    internal val targetScale: Float get() = 1f + SHARPEN * sharpen

    // The camera: (0, 45, 240) looking at the origin at rest.
    internal var eyeX = 0f
        private set
    internal var eyeY = EYE_Y
        private set
    internal var eyeZ = EYE_Z
        private set
    private var targetZ = 0f

    // The form the pacer is morphing toward.
    private var formTo = FLAT

    // How far each form has come, 0 to 1. Every one moves toward its target, so a new morph never jumps.
    private val blend = FloatArray(FORMS.size).also { it[FLAT] = 1f }

    // The eased, normalized weights of this frame. They add up to 1.
    private val formWeight = FloatArray(FORMS.size)

    // Picks the next form. It is apart from the noise's generator, so the noise is unchanged.
    private var formRandom = Random(SEED + 1)

    // The eased weight of the form being morphed to, 1 when settled.
    internal val morphValue: Float get() = formWeight[formTo]
    internal val cycleSecondsValue: Float get() = gestures.cycleSeconds

    // The reflection of each line in the Mirror form.
    private val mirrorPaths = Array(LINES) { Path() }

    // How many paths the last frame drew.
    internal var pathsDrawn = 0
        private set

    // Where vertex d sits round the Tunnel's axis, as a cosine and a sine.
    private val tunnelCos = FloatArray(POINTS)
    private val tunnelSin = FloatArray(POINTS)

    // How far along its line vertex d is, 0 to 1.
    private val along = FloatArray(POINTS)

    // The angle of each line in the Fan, as a sine and a cosine.
    private val fanSin = FloatArray(LINES)
    private val fanCos = FloatArray(LINES)

    // The world position that place worked out.
    private var placedX = 0f
    private var placedY = 0f
    private var placedZ = 0f

    // The colour each line was last drawn in.
    private val lineColour = Array(LINES) { Color.White }
    internal fun lineColourOf(index: Int): Color = lineColour[index]

    /** The form on screen. Births are 0, because Lines has no birth by the spec's decision. */
    override val forms: FormReadout get() = FormReadout(FORMS[strongestForm()], evolution.morphs, 0)

    init {
        val last = CONTROL_POINTS - 1
        for (d in 0 until POINTS) {
            val point = last * (d.toDouble() / (POINTS - 1))
            val intPoint = floor(point).toInt()
            weight[d] = (point - intPoint).toFloat()
            point0[d] = if (intPoint == 0) intPoint else intPoint - 1
            point1[d] = intPoint
            point2[d] = if (intPoint > CONTROL_POINTS - 2) CONTROL_POINTS - 1 else intPoint + 1
            point3[d] = if (intPoint > CONTROL_POINTS - 3) CONTROL_POINTS - 1 else intPoint + 2
        }
        for (d in 0 until POINTS) {
            val t = (d - (POINTS - 1) / 2f) / 120f
            bell[d] = exp(-t * t)
        }
        for (d in 0 until POINTS) {
            val theta = d / (POINTS - 1f) * TAU
            tunnelCos[d] = cos(theta)
            tunnelSin[d] = sin(theta)
            along[d] = d / (POINTS - 1f)
        }
        for (i in 0 until LINES) {
            val alpha = (i - 10.5f) / 21f * FAN_SPREAD
            fanSin[i] = sin(alpha)
            fanCos[i] = cos(alpha)
        }
        updateWeights()
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        // The page clears to black, and its fog fades to black.
        drawRect(Color.Black)
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        if (!ready) return
        // SIZE.x: half the longer side of the window, with a device pixel for each CSS pixel.
        placePoints(max(size.width, size.height) / 2f)
        scene.lens(size, FOV, NEAR, FAR)
        scene.camera(eyeX, eyeY, eyeZ, 0f, 0f, targetZ)
        pathsDrawn = 0
        val light = state.lightScale.coerceIn(0f, 1f)
        val tint = state.palette.ramp(TINT_AT)
        // Back to front, so the nearer line wins where two cross, as the depth test made it.
        for (index in 0 until LINES) {
            val path = paths[index]
            path.reset()
            var open = false
            for (d in 0 until POINTS) {
                place(index, d, heightAt(index, d))
                if (!scene.project(placedX, placedY, placedZ)) {
                    open = false
                    continue
                }
                if (open) path.lineTo(scene.screenX, scene.screenY) else path.moveTo(scene.screenX, scene.screenY)
                open = true
            }
            // Far quiet lines take a tint from the palette through the fog. A line with a peak, and the whole
            // stack in a loud passage, stays the page's white.
            val grey = visibility[index] * light
            val tintShare = TINT * (1f - visibility[index]) * (1f - peaked[index].value) * (1f - dip.value)
            val colour = Color(
                (1f - tintShare + tintShare * tint.red) * grey,
                (1f - tintShare + tintShare * tint.green) * grey,
                (1f - tintShare + tintShare * tint.blue) * grey,
            )
            lineColour[index] = colour
            drawPath(path, colour, style = hairline)
            pathsDrawn++
            // The Mirror form draws the Flat picture again, reflected in the water line and faded by its weight.
            val mirror = formWeight[MIRROR]
            if (mirror > 0.01f) {
                val reflection = mirrorPaths[index]
                reflection.reset()
                var reflected = false
                for (d in 0 until POINTS) {
                    val lowered = 2f * MIRROR_Y - (lineY[index] + roadY[index] + heightAt(index, d))
                    if (!scene.project(pointX[d] + roadX[index], lowered, lineZ[index])) {
                        reflected = false
                        continue
                    }
                    if (reflected) reflection.lineTo(scene.screenX, scene.screenY) else reflection.moveTo(scene.screenX, scene.screenY)
                    reflected = true
                }
                drawPath(reflection, colour.copy(alpha = 0.5f * mirror), style = hairline)
                pathsDrawn++
            }
        }
    }

    /** The height of vertex [d] of line [index] above its line: the eased target and the kick's lift. */
    private fun heightAt(index: Int, d: Int): Float = vertices[index][d] + wave[index] * WAVE_HEIGHT * bell[d]

    /** The world position of vertex [d] of line [i]: the forms' positions blended by this frame's weights. */
    private fun place(i: Int, d: Int, h: Float) {
        val x = pointX[d] + roadX[i]
        val yBase = lineY[i] + roadY[i]
        val z = lineZ[i]
        var px = 0f
        var py = 0f
        var pz = 0f
        for (f in formWeight.indices) {
            val w = formWeight[f]
            if (w < 1e-4f) continue
            when (f) {
                TUNNEL -> {
                    val r = TUNNEL_R + h
                    px += r * tunnelCos[d] * w
                    py += r * tunnelSin[d] * w
                    pz += z * w
                }
                FAN -> {
                    val u = along[d]
                    px += fanSin[i] * u * FAN_LENGTH * w
                    py += (FAN_Y + h) * w
                    pz += (FAN_Z + fanCos[i] * u * FAN_LENGTH) * w
                }
                else -> {
                    px += x * w
                    py += (yBase + h) * w
                    pz += z * w
                }
            }
        }
        placedX = px
        placedY = py
        placedZ = pz
    }

    /** Projects one vertex under the last camera and answers whether it is on screen. */
    private fun projectVertex(line: Int, vertex: Int): Boolean {
        place(line, vertex, heightAt(line, vertex))
        return scene.project(placedX, placedY, placedZ)
    }

    /** For tests: the screen x of one vertex under the last camera, or NaN when it is not on screen. */
    internal fun screenXOf(line: Int, vertex: Int): Float = if (projectVertex(line, vertex)) scene.screenX else Float.NaN

    /** For tests: the screen y of one vertex under the last camera, or NaN when it is not on screen. */
    internal fun screenYOf(line: Int, vertex: Int): Float = if (projectVertex(line, vertex)) scene.screenY else Float.NaN

    /** For tests: the depth of one vertex, 0 at the near plane and 1 at the far one, or NaN when it is not on screen. */
    internal fun depthOf(line: Int, vertex: Int): Float = if (projectVertex(line, vertex)) scene.depth else Float.NaN

    /** For tests: puts the drawing straight into [form], with no morph. */
    internal fun jumpTo(form: String) {
        val target = FORMS.indexOf(form)
        require(target >= 0) { "no form named $form" }
        formTo = target
        blend.fill(0f)
        blend[formTo] = 1f
        updateWeights()
    }

    /** Eases each blend into a weight and divides by the sum, so the weights add up to 1. */
    private fun updateWeights() {
        var sum = 0f
        for (f in blend.indices) {
            val b = blend[f]
            formWeight[f] = b * b * (3f - 2f * b)
            sum += formWeight[f]
        }
        if (sum <= 0f) {
            formWeight.fill(0f)
            formWeight[formTo] = 1f
        } else {
            for (f in formWeight.indices) formWeight[f] /= sum
        }
    }

    /** The index of the form with the largest weight. */
    private fun strongestForm(): Int {
        var best = 0
        for (f in formWeight.indices) if (formWeight[f] > formWeight[best]) best = f
        return best
    }

    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        gestures.update(state)
        evolution.update(state, gestures)
        // A pause holds everything. A silence lets the road straighten and the camera come home.
        val live = if (state.frame.held) 0f else dt
        recipe.advance(gestures, live, evolution.morph)
        val frame = state.frame
        bend.advance(balanceOf(frame.scopeLeft, frame.scopeRight) * frame.audible, live)
        dip.advance(frame.levelRel * frame.audible, live)
        present.advance(frame.audible, live)
        // A snare raises the peaks, and the raise is gone within a beat of heard time.
        val heardNow = dt * frame.audible
        sharpen = max(sharpen * exp(-heardNow * 3f / gestures.beatSeconds.coerceAtLeast(0.1f)), gestures.snare)
        // The evolution pacer picks the next form. A morph that lands mid-morph turns the weights from where they are.
        if (evolution.morph) {
            formTo = (formTo + 1 + formRandom.nextInt(2)) % FORMS.size
        }
        val rate = heardNow / (MORPH_CYCLES * gestures.cycleSeconds.coerceAtLeast(0.5f))
        for (f in blend.indices) {
            val target = if (f == formTo) 1f else 0f
            blend[f] += (target - blend[f]).coerceIn(-rate, rate)
        }
        updateWeights()
        for (index in 0 until LINES) {
            peaked[index].advance(peakedTarget[index], live)
            val share = (Z_NEAR - lineZ[index]) / Z_SPAN
            roadX[index] = bend.value * ROAD_REACH * share * share
            roadY[index] = -dip.value * DIP_REACH * share
        }
        // The camera is the forms' cameras blended, plus the sway. Every form looks along the axis, so only
        // the eye's height and distance and the target's distance differ.
        var ey = 0f
        var ez = 0f
        var tz = 0f
        for (f in formWeight.indices) {
            val w = formWeight[f]
            if (w < 1e-4f) continue
            when (f) {
                TUNNEL -> {
                    ez += 240f * w
                    tz += -200f * w
                }
                FAN -> {
                    ey += 60f * w
                    ez += 300f * w
                    tz += -100f * w
                }
                else -> {
                    ey += EYE_Y * w
                    ez += EYE_Z * w
                }
            }
        }
        val sway = present.value * state.motionScale
        eyeX = SWAY_X * sin(gestures.slowCyclePhase * TAU) * sway
        eyeY = ey + SWAY_Y * sin(2f * gestures.slowCyclePhase * TAU) * sway
        eyeZ = ez
        targetZ = tz
        if (!ready) {
            // The Line constructor: the vertices start on the first target.
            analyser.update(state.frame)
            for (index in 0 until LINES) {
                generatePoints(index, analyser.frequencyBytes, targets[index])
                targets[index].copyInto(vertices[index])
            }
            ready = true
            return
        }
        // The page's frame loop runs on heard seconds only, so a pause or a silence holds every line.
        val heard = dt * state.frame.audible
        val frames = pageFrames.advance(heard)
        var easing = heard
        for (pageFrame in 1..frames) {
            // The wave runs one line outward per page frame and fades as it goes; the middle keeps a trace.
            for (i in 0 until LINES) {
                val from = if (i < 10) wave[i + 1] else if (i > 11) wave[i - 1] else wave[i] * 0.3f
                waveNext[i] = from * WAVE_KEEP
            }
            val swap = wave
            wave = waveNext
            waveNext = swap
            // A screen frame carries one analysis, so the analyser smooths once however many page frames it covers.
            if (pageFrame == 1) analyser.update(state.frame)
            for (index in 0 until LINES) generatePoints(index, analyser.frequencyBytes, targets[index])
            if (pageFrame < frames) {
                ease(PAGE_FRAME)
                easing -= PAGE_FRAME
            }
        }
        ease(easing)
        // A kick lifts the two middle lines. It lands after the shift, so the hit frame shows it in full.
        if (gestures.kick > 0f) {
            wave[10] = max(wave[10], gestures.kick)
            wave[11] = max(wave[11], gestures.kick)
        }
    }

    /** Line.js `generatedPoints` for line [index]: 386 heights resampled to 512 by a Catmull-Rom spline. */
    private fun generatePoints(index: Int, bins: IntArray, out: FloatArray) {
        noise.generate(random, heights)
        for (i in 0 until NOISE_POINTS) heights[i] *= 10f
        val start = bandStart(index)
        var at = NOISE_POINTS
        var peak = false
        for (i in RANGE + start downTo start) {
            val height = heightOf(bins[i])
            if (height > FLOOR) peak = true
            heights[at++] = FLOOR + (height - FLOOR) * targetScale
        }
        peakedTarget[index] = if (peak) 1f else 0f
        for (i in 0 until HALF) heights[HALF + i] = heights[HALF - 1 - i]
        for (d in 0 until POINTS) {
            out[d] = catmullRom(heights[point0[d]], heights[point1[d]], heights[point2[d]], heights[point3[d]], weight[d])
        }
    }

    /** Line.js `update`: every vertex moves 4 percent of the way to its target per page frame. */
    private fun ease(seconds: Float) {
        if (seconds <= 0f) return
        val share = 1f - (1f - EASE).pow(seconds * 60f)
        for (index in 0 until LINES) {
            val target = targets[index]
            val vertex = vertices[index]
            for (d in 0 until POINTS) vertex[d] += (target[d] - vertex[d]) * share
        }
    }

    /** The x of every vertex for a line [width] world units wide, which only changes with the canvas. */
    private fun placePoints(width: Float) {
        if (width == sizeX) return
        sizeX = width
        val ratio = width / CONTROL_POINTS
        for (d in 0 until POINTS) {
            pointX[d] = catmullRom(
                -width / 2f + ratio * point0[d], -width / 2f + ratio * point1[d],
                -width / 2f + ratio * point2[d], -width / 2f + ratio * point3[d], weight[d],
            )
        }
    }

    override fun reset() {
        step.reset()
        pageFrames.reset()
        analyser.reset()
        random = Random(SEED)
        ready = false
        gestures.reset()
        evolution.reset()
        recipe.restart()
        bend.reset()
        dip.reset()
        present.reset()
        for (envelope in peaked) envelope.reset()
        wave.fill(0f)
        waveNext.fill(0f)
        sharpen = 0f
        peakedTarget.fill(0f)
        roadX.fill(0f)
        roadY.fill(0f)
        eyeX = 0f
        eyeY = EYE_Y
        eyeZ = EYE_Z
        targetZ = 0f
        formTo = FLAT
        blend.fill(0f)
        blend[FLAT] = 1f
        updateWeights()
        formRandom = Random(SEED + 1)
        pathsDrawn = 0
        lineColour.fill(Color.White)
    }

    /**
     * Counts the page's frames, 60 to a second of the time it is given. It rounds to the nearest frame,
     * so a 60 Hz screen whose frame times jitter still gets exactly one page frame per screen frame.
     */
    internal class PageFrames {
        private var owed = 0f

        fun advance(seconds: Float): Int {
            owed += seconds
            var frames = 0
            while (owed >= PAGE_FRAME / 2f) {
                owed -= PAGE_FRAME
                frames++
            }
            return frames
        }

        fun reset() {
            owed = 0f
        }
    }

    /**
     * The npm package perlin-noise 0.0.1, `generatePerlinNoise(1, height)` with its defaults: four octaves,
     * amplitude 0.1, persistence 0.2. It draws fresh white noise on every call, as the page did.
     */
    private class PerlinNoise(private val height: Int) {
        private val whiteNoise = FloatArray(height)
        private val smoothNoiseList = Array(OCTAVE_COUNT) { FloatArray(height) }

        /** Writes the noise, 0 to 1, into the first [height] entries of [out]. */
        fun generate(random: Random, out: FloatArray) {
            for (i in 0 until height) whiteNoise[i] = random.nextFloat()
            for (octave in 0 until OCTAVE_COUNT) generateSmoothNoise(octave, smoothNoiseList[octave])
            var amplitude = AMPLITUDE
            var totalAmplitude = 0f
            out.fill(0f, 0, height)
            for (i in OCTAVE_COUNT - 1 downTo 0) {
                amplitude *= PERSISTENCE
                totalAmplitude += amplitude
                val smooth = smoothNoiseList[i]
                for (j in 0 until height) out[j] += smooth[j] * amplitude
            }
            for (i in 0 until height) out[i] /= totalAmplitude
        }

        private fun generateSmoothNoise(octave: Int, noise: FloatArray) {
            val samplePeriod = 1 shl octave
            val sampleFrequency = 1f / samplePeriod
            for (y in 0 until height) {
                val sampleY0 = y / samplePeriod * samplePeriod
                val sampleY1 = (sampleY0 + samplePeriod) % height
                val vertBlend = (y - sampleY0) * sampleFrequency
                // One column wide, so both x samples are column 0 and the horizontal blend changes nothing.
                noise[y] = interpolate(whiteNoise[sampleY0], whiteNoise[sampleY1], vertBlend)
            }
        }

        private fun interpolate(x0: Float, x1: Float, alpha: Float): Float = x0 * (1f - alpha) + alpha * x1
    }

    internal companion object {
        const val LINES = 22

        /** `curve.getPoints(511)`. */
        const val POINTS = 512

        /** `perlin.generatePerlinNoise(1, 128)`. */
        const val NOISE_POINTS = 128

        /** `512 / 8`: the step between two lines' bands. Each line reads 65 bins, this step plus one. */
        const val RANGE = 64

        /** The noise and the band, before the mirror doubles them. */
        const val HALF = NOISE_POINTS + RANGE + 1
        const val CONTROL_POINTS = HALF * 2

        /** The byte above which a bin raises its line: `bin / 5 > 20`. */
        const val KNEE = 100

        const val SIZE_Y = 30f
        const val EYE_Y = 45f
        const val EYE_Z = 240f
        const val FOV = 45f
        const val NEAR = 0.01f
        const val FAR = 4000f
        const val FOG_DENSITY = 0.00255f

        /** three.js's own constant in the fog chunk. */
        const val LOG2 = 1.442695f

        /** The height the fog is worked out at: the floor that `max(5, a)` holds every quiet bin on. */
        const val FLOOR = 5f

        /** The share of the way to its target a vertex moves per page frame. */
        const val EASE = 0.04f
        const val PAGE_FRAME = 1f / 60f

        const val OCTAVE_COUNT = 4
        const val AMPLITUDE = 0.1f
        const val PERSISTENCE = 0.2f
        const val SEED = 20_150_617L

        /** The names of the forms, in the order of the indices below. */
        val FORMS = listOf("Flat", "Tunnel", "Mirror", "Fan")

        /** The index of the port's picture. */
        const val FLAT = 0

        /** The index of the form where the lines wrap round the viewer. */
        const val TUNNEL = 1

        /** The index of the form with a reflection below. */
        const val MIRROR = 2

        /** The index of the form where the lines radiate from a far point. */
        const val FAN = 3

        /** A morph takes this many cycles. */
        const val MORPH_CYCLES = 4f

        /** The water line of the Mirror form, just under the lowest line's floor. */
        const val MIRROR_Y = -36f

        /** The Tunnel's radius, in world units. */
        const val TUNNEL_R = 60f

        /** The Fan's whole angle, in radians. */
        const val FAN_SPREAD = TAU / 3f

        /** How long each line of the Fan is, from its far point toward the viewer. */
        const val FAN_LENGTH = 700f

        /** The height of the Fan's lines at rest. */
        const val FAN_Y = -10f

        /** The z of the Fan's far point. */
        const val FAN_Z = -400f

        /** The z of line 21, the nearest line, which the road never moves. */
        const val Z_NEAR = 100f

        /** The distance in z from line 21 to line 0, which stands at -320. */
        const val Z_SPAN = 420f

        /** How far the farthest line moves sideways at a full bend, in world units. */
        const val ROAD_REACH = 120f

        /** How far the farthest line drops at full level, in world units. */
        const val DIP_REACH = 40f

        /** The camera's sideways glide on the slow cycle, in world units. */
        const val SWAY_X = 30f

        /** The camera's vertical glide on the slow cycle, in world units. */
        const val SWAY_Y = 6f

        /** How much of the palette colour the fog lends a far quiet line. */
        const val TINT = 0.35f

        /** Where on the palette ramp the tint is read. */
        const val TINT_AT = 0.35f

        /** How much of the lift survives each step outward. */
        const val WAVE_KEEP = 0.9f

        /** The lift of a full kick at the middle of a line, in world units. */
        const val WAVE_HEIGHT = 25f

        /** How much a snare raises the peaks above the floor, as a share. */
        const val SHARPEN = 0.35f

        /** How strongly the balance is stretched: a channel twice as loud is already a full bend. */
        const val BALANCE_GAIN = 3f

        /** The easing's time constant: a vertex covers 63 percent of a step in about 0.41 seconds. */
        val EASE_SECONDS: Float = (-1.0 / (60.0 * ln(1.0 - EASE))).toFloat()

        /**
         * The stereo balance, -1 for a left heavy trace and 1 for a right heavy one.
         * A channel twice as loud is already full.
         */
        fun balanceOf(left: FloatArray, right: FloatArray): Float {
            val l = rootMeanSquare(left)
            val r = rootMeanSquare(right)
            return ((r - l) / (r + l + 1e-4f) * BALANCE_GAIN).coerceIn(-1f, 1f)
        }

        private fun rootMeanSquare(trace: FloatArray): Float {
            if (trace.isEmpty()) return 0f
            var sum = 0f
            for (value in trace) sum += value * value
            return sqrt(sum / trace.size)
        }

        /** Line.js: `a = bin / 5; a = a > 20 ? a * 1.5 : a / 50; a = max(5, a)`. A height is 5, or 30.3 to 76.5. */
        fun heightOf(byte: Int): Float {
            var a = byte / 5f
            a = if (a > 20f) a * 1.5f else a / 50f
            return max(5f, a)
        }

        /** The lowest of the 65 bins line [index] reads. The middle of the stack reads the bass. */
        fun bandStart(index: Int): Int {
            val order = if (index < 11) 10 - index else index
            return RANGE * (order % 11)
        }

        /** THREE.Curve.Utils.interpolate in three.js r71: one uniform Catmull-Rom step. */
        fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val v0 = 0.5f * (p2 - p0)
            val v1 = 0.5f * (p3 - p1)
            val t2 = t * t
            return (2f * p1 - 2f * p2 + v0 + v1) * (t * t2) + (-3f * p1 + 3f * p2 - 2f * v0 - v1) * t2 + v0 * t + p1
        }

        /**
         * The share of a white line that three.js's FogExp2 leaves at height [y] and depth [z], over black.
         * The fog reads `gl_FragCoord.z / gl_FragCoord.w`: the distance along the view axis less the near
         * plane, times far over (far - near).
         */
        fun fogVisibility(y: Float, z: Float): Float {
            // The camera looks from (0, EYE_Y, EYE_Z) at the origin.
            val length = sqrt(EYE_Y * EYE_Y + EYE_Z * EYE_Z)
            val along = ((y - EYE_Y) * -EYE_Y + (z - EYE_Z) * -EYE_Z) / length
            val depth = FAR / (FAR - NEAR) * (along - NEAR)
            val fogFactor = 1f - 2f.pow(-FOG_DENSITY * FOG_DENSITY * depth * depth * LOG2).coerceIn(0f, 1f)
            return 1f - fogFactor
        }
    }
}
