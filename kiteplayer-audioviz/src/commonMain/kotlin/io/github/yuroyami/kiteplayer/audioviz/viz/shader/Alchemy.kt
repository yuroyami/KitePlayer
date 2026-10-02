package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flow
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Impulses
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatSpawn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The classic visualiser loop, drawn sharp: a drawer paints the signal into a memory field every
 * frame, a flow carries the field, and the shader draws the field as banded, lit ink with crisp
 * iso-lines under the live drawer. The flow's parameters move all the time and jump on morphs, a
 * birth changes the flow with a cross fade and seeds a new drawer, so the picture never settles.
 * The palette's cool end is the default; a drop turns the ink gold from the core out over one beat
 * and it cools back from the rim over two cycles. A snare strikes a lightning arc and flips the
 * spin, a hat throws sparks, a kick pushes the whole field outward for a moment.
 *
 * Drawers: Ring (the waveform round a circle), Radar (the waveform along a turning line), Dots (the
 * spectrum as a plane of dots), Polygon (the waveform on an N-gon), Twin (left and right traces as
 * two rings), Edge (the spectrum along the frame edges), Fan (two arms of the waveform). Flows: Swirl,
 * Kaleido, Tunnel, Burst, Blocks, Shimmer, Julia, Drift. The Gas form (Dots plus Drift) also draws
 * thin gas filaments over the ink.
 *
 * Positions are the field's centred units, with y pointing down the screen.
 */
internal class Alchemy : ShaderPreset(
    source = AlchemyShader.SOURCE,
    name = "Alchemy",
    bucket = VizEnergy.High,
    seed = 7.932f,
    // No camera moves: all motion is flow.
    kit = Kit(7_932L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 7_932)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.4f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(0.05f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Cut, VizCurve.Discrete),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.55f)),
        VizDrive(VizDriver.Drop, VizProperty.Colour, VizCurve.Discrete, VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Cut, VizCurve.Discrete),
        VizDrive(VizDriver.Breakdown, VizProperty.Cut, VizCurve.Discrete),
        VizDrive(VizDriver.Section, VizProperty.Cut, VizCurve.Discrete),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        // The flows are paced by the music's motion rate, which mood is part of, but a steady frame
        // draws a picture that stands still, so no speed is declared that a probe could not see.
        silence = VizSilence.Fade,
    )

    // Sharp ink and black space are the look; the shader puts its own glow on the core.
    override val post: PostSpec get() = PostSpec.Off

    private val memory = MemoryField(rows = 108).also { it.halfLife = 1.4f }
    override val field: MemoryField get() = memory

    // The recipe the song changes.
    private val foldCount = genes.number("Fold count", 3f, 9f, 6f)
    private val inkSteps = genes.number("Ink steps", 4f, 9f, 6f)
    private val twoDrawers = genes.toggle("Two drawers", start = false)

    private var drawer = 0
    private var flowKind = 0
    private var previousFlow: Flow = Flows.Swirl(0.6f)
    private var currentFlow: Flow = Flows.Swirl(0.6f)
    private var flowMix = 1f
    private var spin = 0.6f
    private var spinSign = 1f
    private var pull = 0.8f
    private var amount = 0.5f
    private var push = 0f
    private var radarAngle = 0f
    private var morphs = 0
    private var births = 0
    private var gas = 0f

    private val xs = FloatArray(POINTS)
    private val ys = FloatArray(POINTS)
    private val path = Path()
    private val sparks = Sprites(200, 1_701L)

    // The transmutation, in heard seconds since the drop, below zero while none runs.
    private var goldAge = -1f
    internal var goldSpread = 0f
        private set
    internal var goldCool = GOLD_REACH
        private set
    private var rayGold = 0f

    // The discharge: a jagged arc from the ring into the core, in centred units.
    private val arcX = FloatArray(ARC_POINTS)
    private val arcY = FloatArray(ARC_POINTS)
    internal var arcFrames = 0
        private set
    internal var arcsStruck = 0
        private set
    private var sinceArc = 99f
    private val arcPath = Path()

    internal val drawerName: String get() = DRAWERS[drawer]
    internal val flowName: String get() = FLOWS[flowKind]

    override val forms: FormReadout get() = FormReadout("$drawerName + $flowName", morphs, births)

    override fun advance(state: VizRenderState) {
        memory.size(kit.aspect)
        val dt = state.deltaSeconds
        val heard = state.stepSeconds
        val frame = state.frame

        // The recipe moves on the evolution pace: a morph eases the flow, a birth changes it.
        if (evolution.morph) {
            morphs++
            spin = 0.3f + 0.9f * random.next()
            amount = 0.3f + 0.6f * random.next()
            pull = 0.5f + 0.8f * random.next()
            if (random.next() < 0.5f) drawer = (drawer + 1 + (random.next() * (DRAWERS.size - 1)).toInt()) % DRAWERS.size
        }
        if (evolution.birth) {
            births++
            previousFlow = blendedFlow()
            flowKind = (flowKind + 1 + (random.next() * (FLOWS.size - 1)).toInt()) % FLOWS.size
            flowMix = 0f
            drawer = (random.next() * DRAWERS.size).toInt().coerceIn(0, DRAWERS.size - 1)
        }
        if (evolution.collapse) {
            previousFlow = currentFlow
            flowKind = FLOWS.indexOf("Drift")
            drawer = DRAWERS.indexOf("Dots")
            flowMix = 0f
        }
        if (evolution.bloom) {
            previousFlow = currentFlow
            flowKind = FLOWS.indexOf("Burst")
            flowMix = 0f
        }
        if (flowMix < 1f) flowMix = (flowMix + heard / gestures.cycleSeconds.coerceAtLeast(0.4f)).coerceAtMost(1f)
        currentFlow = flowFor(flowKind, state)
        // A snare flips the spin, a kick pushes the whole field outward for a moment.
        if (gestures.snare > 0f) spinSign = -spinSign
        push = max(push - heard * 2.5f, 0f)
        if (gestures.kick > 0f) {
            push = (push + 0.8f * gestures.kick).coerceAtMost(1.2f)
            impulses.add(Impulses.LOW, gestures.kick, 0f, 0f, random.next())
        }
        gas += ((if (flowName == "Drift" && drawerName == "Dots") 1f else 0f) - gas) * (dt / 1.5f).coerceAtMost(1f)
        radarAngle += heard * state.paced(0.9f) * spinSign

        // The field: carry what is there, then write this frame's signal into it.
        val flow = if (push > 0f) Flows.Mixed(blendedFlow(), Flows.Tunnel(3f * push), 0.5f) else blendedFlow()
        memory.advance(flow, heard)
        if (frame.audible > 0f) {
            draw(drawer, state)
            if (twoDrawers.on) draw((drawer + 2) % DRAWERS.size, state)
        }

        advanceGold(state)
        sinceArc += heard
        if (arcFrames > 0) arcFrames--
        if (gestures.snare > 0f && sinceArc >= ARC_GAP && frame.audible > 0f) {
            buildArc()
            arcFrames = ARC_FRAMES
            arcsStruck++
            sinceArc = 0f
        }
        if (gestures.hat > 0f) {
            val a = random.next() * TAU
            sparks.burst(0.5f + cos(a) * 0.3f / kit.aspect, 0.5f + sin(a) * 0.3f,
                gestures.hatSpawn(5), 0.25f, 0.5f, 0.01f, 0.55f, Sprite.SPARK)
            // The hat lands on the matter too: specks of ink thrown off past the ring, which the flow carries.
            repeat(gestures.hatSpawn(3)) {
                val b = a + random.signed() * 0.6f
                val r = 0.72f + 0.18f * random.next()
                memory.disc(cos(b) * r, sin(b) * r, 0.04f + 0.06f * gestures.hat, 0.5f + 0.4f * gestures.hat)
            }
        }
        sparks.advance(dt, drag = 1f)
    }

    /** A 0..1 position folded in half, so a shape drawn round a circle reads the bands with no seam. */
    private fun folded(t: Float): Float = if (t <= 0.5f) t * 2f else (1f - t) * 2f

    private fun blendedFlow(): Flow = if (flowMix >= 1f) currentFlow else Flows.Mixed(previousFlow, currentFlow, flowMix)

    /** The flow of [kind] with this frame's parameters. Speeds are paced, so a ballad swirls slowly. */
    private fun flowFor(kind: Int, state: VizRenderState): Flow {
        val pace = 0.3f + 0.7f * state.frame.motionRate
        return when (FLOWS[kind]) {
            "Swirl" -> Flows.Swirl(spin * spinSign * pace)
            "Kaleido" -> Flows.Mixed(Flows.Kaleido(foldCount.value.toInt().coerceIn(3, 12), pull * pace), Flows.Swirl(0.2f * spinSign * pace), 0.3f)
            "Tunnel" -> Flows.Mixed(Flows.Tunnel(0.35f * pace), Flows.Swirl(0.25f * spinSign * pace), 0.4f)
            "Burst" -> Flows.Mixed(Flows.Burst(0.4f * pace), Flows.Swirl(0.3f * spinSign * pace), 0.4f)
            "Blocks" -> Flows.Blocks(3f + 3f * amount, 0.25f * pace)
            "Shimmer" -> Flows.Mixed(Flows.Shimmer(0.3f * amount * pace, 6f), Flows.Drift(0f, 0.15f * pace), 0.5f)
            "Julia" -> Flows.Julia(JULIA_X[kind % JULIA_X.size], JULIA_Y[kind % JULIA_Y.size], 0.6f * amount * pace)
            else -> Flows.Drift(0.12f * spinSign * pace, 0.08f * pace)
        }
    }

    /** Writes drawer [which] into the field from this frame's waveform and bands. */
    private fun draw(which: Int, state: VizRenderState) {
        val frame = state.frame
        val gain = frame.waveformGain
        val light = 0.55f + 0.45f * frame.energy
        when (DRAWERS[which]) {
            "Ring" -> {
                for (i in 0 until POINTS) {
                    val a = i / (POINTS - 1f) * TAU
                    val t = i / (POINTS - 1f)
                    // The waveform and the spectrum, folded so the ring has no seam where the ends meet.
                    val r = 0.55f + 0.12f * frame.scope.sampleAt(t) * gain + 0.15f * frame.bandsRel.sampleAt(folded(t)) +
                        0.08f * frame.bassRel
                    xs[i] = cos(a) * r; ys[i] = sin(a) * r
                }
                memory.line(xs, ys, POINTS, 1.6f, light)
            }
            "Radar" -> {
                val c = cos(radarAngle); val s = sin(radarAngle)
                for (i in 0 until POINTS) {
                    val along = (i / (POINTS - 1f) - 0.5f) * 2.2f * kit.aspect
                    val across = 0.35f * frame.scope.sampleAt(i / (POINTS - 1f)) * gain
                    xs[i] = along * c - across * s; ys[i] = along * s + across * c
                }
                memory.line(xs, ys, POINTS, 1.6f, light)
            }
            "Dots" -> {
                val bands = frame.bandsRel
                for (b in bands.indices) {
                    val x = (b / (bands.size - 1f) - 0.5f) * 1.8f * kit.aspect
                    // y points down, so a louder band sits higher on the screen.
                    val y = 0.7f - 1.4f * bands[b]
                    memory.disc(x, y, 0.025f + 0.05f * bands[b], light * (0.4f + 0.6f * bands[b]))
                }
            }
            "Polygon" -> {
                val sides = foldCount.value.toInt().coerceIn(3, 9)
                for (i in 0 until POINTS) {
                    val t = i / (POINTS - 1f)
                    val corner = floor(t * sides) / sides * TAU
                    val next = corner + TAU / sides
                    val along = (t * sides) - floor(t * sides)
                    val r = 0.55f + 0.1f * frame.scope.sampleAt(t) * gain + 0.1f * frame.bandsRel.sampleAt(folded(t))
                    xs[i] = (cos(corner) * (1f - along) + cos(next) * along) * r
                    ys[i] = (sin(corner) * (1f - along) + sin(next) * along) * r
                }
                memory.line(xs, ys, POINTS, 1.6f, light)
            }
            "Twin" -> {
                for (side in 0..1) {
                    val trace = if (side == 0) frame.scopeLeft else frame.scopeRight
                    val cx = if (side == 0) -0.45f else 0.45f
                    for (i in 0 until POINTS) {
                        val a = i / (POINTS - 1f) * TAU
                        val r = 0.38f + 0.1f * trace.sampleAt(i / (POINTS - 1f)) * gain
                        xs[i] = cx + cos(a) * r; ys[i] = sin(a) * r
                    }
                    memory.line(xs, ys, POINTS, 1.4f, light)
                }
            }
            "Edge" -> {
                // The bottom edge (y 0.95) rises with the bands, the top edge (y -0.95) falls with them.
                val bands = frame.bandsRel
                val half = POINTS / 2
                for (i in 0 until half) {
                    val t = i / (half - 1f)
                    val level = bands.sampleAt(t)
                    xs[i] = (t - 0.5f) * 2f * kit.aspect; ys[i] = 0.95f - 0.35f * level
                    xs[half + i] = (0.5f - t) * 2f * kit.aspect; ys[half + i] = -0.95f + 0.35f * level
                }
                memory.line(xs, ys, half, 1.6f, light)
                memory.line(xs.copyOfRange(half, POINTS), ys.copyOfRange(half, POINTS), half, 1.6f, light)
            }
            else -> {
                for (arm in 0..1) {
                    val base = radarAngle + arm * 3.1415927f
                    for (i in 0 until POINTS) {
                        val t = i / (POINTS - 1f)
                        val r = 0.1f + 0.75f * t
                        val sway = 0.25f * frame.scope.sampleAt(t) * gain * t
                        xs[i] = cos(base + sway) * r; ys[i] = sin(base + sway) * r
                    }
                    memory.line(xs, ys, POINTS, 1.5f, light)
                }
            }
        }
    }

    /**
     * Lead into gold and back. The gold spreads from the core over one beat, holds for four cycles,
     * then cools back from the rim inward over two cycles. It runs on heard time, so a pause holds it.
     */
    private fun advanceGold(state: VizRenderState) {
        if (gestures.surge) goldAge = 0f
        if (goldAge < 0f) {
            goldSpread = 0f
            goldCool = GOLD_REACH
            rayGold = 0f
            return
        }
        goldAge += state.stepSeconds
        val beat = gestures.beatSeconds.coerceAtLeast(0.1f)
        val cycle = gestures.cycleSeconds.coerceAtLeast(0.4f)
        val spreading = (goldAge / beat).coerceIn(0f, 1f)
        val coolFrom = beat + GOLD_HOLD_CYCLES * cycle
        val cooling = ((goldAge - coolFrom) / (GOLD_COOL_CYCLES * cycle)).coerceIn(0f, 1f)
        goldSpread = spreading * GOLD_REACH
        goldCool = (1f - cooling) * GOLD_REACH
        rayGold = spreading * (1f - cooling)
        if (cooling >= 1f) goldAge = -1f
    }

    /** A jagged arc from the ring into the core, in centred units. */
    private fun buildArc() {
        val from = random.next() * TAU
        val to = from + random.signed() * 0.6f
        val outer = 0.6f
        val inner = 0.08f + 0.1f * random.next()
        arcX[0] = outer * cos(from); arcY[0] = outer * sin(from)
        arcX[ARC_POINTS - 1] = inner * cos(to); arcY[ARC_POINTS - 1] = inner * sin(to)
        // Midpoint displacement: each level halves the segments and the sideways jitter.
        var span = ARC_POINTS - 1
        var jitter = 0.32f
        while (span > 1) {
            val half = span / 2
            var at = 0
            while (at + span < ARC_POINTS) {
                val ax = arcX[at]; val ay = arcY[at]
                val bx = arcX[at + span]; val by = arcY[at + span]
                val dx = bx - ax; val dy = by - ay
                val pushAside = random.signed() * jitter
                arcX[at + half] = (ax + bx) * 0.5f - dy * pushAside
                arcY[at + half] = (ay + by) * 0.5f + dx * pushAside
                at += span
            }
            span = half
            jitter *= 0.62f
        }
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uInkSteps", inkSteps.value)
        program.uniform("uGold", goldSpread, goldCool, rayGold, 0f)
        program.uniform("uPush", push)
        program.uniform("uGas", gas)
        program.uniform("uFold", foldCount.value)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val light = state.lift
        if (light <= 0f) return
        // The live drawer, crisp, over the field: the same points the field was just written with.
        if (state.frame.audible > 0f) drawDrawer(state, light)
        with(sparks) { drawSprites(state.palette, genes.walk, alpha = light) }
        if (arcFrames > 0) drawArc(light)
    }

    /** Strokes the drawer's last polyline at native resolution. Dots and Edge are drawn by the field alone. */
    private fun DrawScope.drawDrawer(state: VizRenderState, light: Float) {
        if (drawerName == "Dots" || drawerName == "Edge") return
        val half = size.height * 0.5f
        path.reset()
        for (i in 0 until POINTS) {
            val x = size.width * 0.5f + half * xs[i]
            val y = half + half * ys[i]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // The cool end of the ramp the shader inks with, lifted towards white, and leaning to the key.
        val lean = state.frame.keyHue * state.frame.keyConfidence * KEY_LEAN
        val at = 0.3f + genes.walk + lean
        val colour = lerp(state.palette.ramp(at - floor(at)), Color.White, 0.4f).copy(alpha = light)
        drawPath(path, colour, style = Stroke(max(1.5f, size.height / 720f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.drawArc(light: Float) {
        val half = size.height * 0.5f
        arcPath.reset()
        for (i in 0 until ARC_POINTS) {
            val x = size.width * 0.5f + half * arcX[i]
            val y = half + half * arcY[i]
            if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
        }
        val width = max(2f, size.height / 540f)
        drawPath(arcPath, ARC_COLOUR.copy(alpha = 0.28f * light), style = Stroke(width * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arcPath, ARC_COLOUR.copy(alpha = light), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** The share of cells holding ink above a tenth, for tests. */
    internal fun fieldInkShare(): Float {
        var lit = 0
        val step = 0.1f
        var count = 0
        var y = -0.95f
        while (y < 1f) {
            var x = -kit.aspect + 0.05f
            while (x < kit.aspect) {
                count++
                if (memory.inkAt(x, y) > 0.1f) lit++
                x += step
            }
            y += step
        }
        return if (count == 0) 0f else lit.toFloat() / count
    }

    internal fun fieldInkAt(x: Float, y: Float): Float = memory.inkAt(x, y)

    /** How far from the middle the ink sits on average, weighted by how much there is, for tests. */
    internal fun fieldMeanRadius(): Float {
        var weighted = 0f
        var total = 0f
        var y = -0.975f
        while (y < 1f) {
            var x = -kit.aspect + 0.025f
            while (x < kit.aspect) {
                val ink = memory.inkAt(x, y)
                weighted += ink * kotlin.math.sqrt(x * x + y * y)
                total += ink
                x += 0.05f
            }
            y += 0.05f
        }
        return if (total <= 0f) 0f else weighted / total
    }

    override fun onReset() {
        memory.clear()
        drawer = 0
        flowKind = 0
        previousFlow = Flows.Swirl(0.6f)
        currentFlow = previousFlow
        flowMix = 1f
        spin = 0.6f
        spinSign = 1f
        pull = 0.8f
        amount = 0.5f
        push = 0f
        radarAngle = 0f
        morphs = 0
        births = 0
        gas = 0f
        sparks.clear()
        goldAge = -1f
        goldSpread = 0f
        goldCool = GOLD_REACH
        rayGold = 0f
        arcFrames = 0
        arcsStruck = 0
        sinceArc = 99f
    }

    internal companion object {
        val DRAWERS = listOf("Ring", "Radar", "Dots", "Polygon", "Twin", "Edge", "Fan")
        val FLOWS = listOf("Swirl", "Kaleido", "Tunnel", "Burst", "Blocks", "Shimmer", "Julia", "Drift")

        /** Points in a drawer's polyline. */
        const val POINTS = 128

        /** Julia constants on the edge of the main cardioid. */
        val JULIA_X = floatArrayOf(-0.75f, -0.4f, 0.28f, -0.12f)
        val JULIA_Y = floatArrayOf(0.11f, 0.6f, 0.53f, 0.74f)

        /** How far a known key moves the palette positions, as a share of the ramp per turn of key hue. */
        const val KEY_LEAN = 0.15f

        /** How far the gold reaches from the core, in centred units. */
        const val GOLD_REACH = 0.9f
        const val GOLD_HOLD_CYCLES = 4f
        const val GOLD_COOL_CYCLES = 2f

        const val ARC_POINTS = 33
        const val ARC_FRAMES = 3
        const val ARC_GAP = 0.5f
        val ARC_COLOUR = Color(0.72f, 0.94f, 1f)
    }
}
