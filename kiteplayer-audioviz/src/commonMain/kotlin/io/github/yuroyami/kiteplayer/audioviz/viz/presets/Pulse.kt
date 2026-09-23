package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sweep
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stacks of discs, one for each part of the spectrum, riding orbits and pushing each other apart. A
 * kick punches the cores, a snare makes the stacks swap places, ripples leave each stack on its own
 * drum, and a drop merges every stack into one for a visual cycle.
 */
internal class Pulse : Layered(
    name = "Pulse",
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 111L, groundKind = GroundKind.Plasma, groundDim = 0.7f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.08f, seed = 111)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.25f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.HighHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.55f, livelyTrail = 0.42f, calmDriftX = 0.02f, livelyDriftX = 0.08f)

    private val stacks = genes.choice("Stacks", 3, start = 1)
    private val discs = genes.choice("Discs", 3, start = 1)
    private val wobble = genes.number("Wobble", 0f, 0.25f, 0.14f)
    private val arrangement = genes.choice("Arrangement", 3)

    private val stage = Stage(start = 2.4f)
    private val orbits = Array(STACKS) {
        Orbiter(radiusX = 0.3f, radiusY = 0.26f, lapsPerBar = 0.35f - 0.05f * it, phase = it / STACKS.toFloat())
    }
    private val stackX = FloatArray(STACKS) { 0.5f }
    private val stackY = FloatArray(STACKS) { 0.5f }
    private var shift = 0
    private var wobblePhase = 0f
    private var mergeHold = 0f
    private val merge = Envelope(attackPerSecond = 4f, releasePerSecond = 1.5f)
    private val core = Spring(stiffness = 260f, damping = 0.42f)
    private val sweep = Sweep()
    private val sparks = Sprites(240, 1_111L)
    private val rippleX = FloatArray(RIPPLES)
    private val rippleY = FloatArray(RIPPLES)
    private val rippleAge = FloatArray(RIPPLES)
    private val rippleTint = FloatArray(RIPPLES)
    private val rippleStrength = FloatArray(RIPPLES)
    private var nextRipple = 0

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        val count = stacks.count(2)
        stage.advance(dt * state.idle)
        if (gestures.snare > 0f) shift = (shift + 1) % STACKS
        if (gestures.drop) mergeHold = gestures.cycleSeconds
        mergeHold -= dt
        merge.advance(if (mergeHold > 0f) 1f else 0f, dt)
        core.kick(gestures.kick * 8f)
        core.advance(dt)
        wobblePhase += dt * 2.4f * state.tempo
        for (index in 0 until STACKS) {
            orbits[index].direction = if (index % 2 == 0) 1f else -1f
            orbits[index].centreX = stage.x
            orbits[index].centreY = stage.y
            orbits[index].advance(state, gestures)
        }
        val ease = (dt * 3f).coerceAtMost(1f)
        val lead = orbits[0]
        for (index in 0 until STACKS) {
            val slot = (index + shift) % STACKS
            val around = lead.angle + slot * TAU / STACKS
            var x = 0f
            var y = 0f
            for (option in 0 until 3) {
                val share = arrangement.weight(option)
                if (share <= 0f) continue
                x += share * when (option) {
                    0 -> orbits[slot].x
                    1 -> 0.16f + 0.68f * slot / (STACKS - 1f)
                    else -> 0.5f + 0.36f * cos(around)
                }
                y += share * when (option) {
                    0 -> orbits[slot].y
                    1 -> 0.5f + 0.26f * sin(orbits[slot].angle)
                    else -> 0.5f + 0.32f * sin(around)
                }
            }
            x += (lead.x - x) * merge.value
            y += (lead.y - y) * merge.value
            stackX[index] += (x - stackX[index]) * ease
            stackY[index] += (y - stackY[index]) * ease
        }
        // Stacks that come too close push each other apart a little, so they drift instead of overlapping.
        val gap = 0.22f * (1f - merge.value)
        for (a in 0 until count) {
            for (b in a + 1 until count) {
                val dx = stackX[b] - stackX[a]
                val dy = stackY[b] - stackY[a]
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                if (distance >= gap) continue
                val push = (gap - distance) * 0.5f * (dt * 6f).coerceAtMost(1f) / distance
                stackX[a] -= dx * push
                stackY[a] -= dy * push
                stackX[b] += dx * push
                stackY[b] += dy * push
            }
        }
        for (index in 0 until STACKS) kit.place(index, stackX[index], stackY[index])
        for (index in 0 until count) {
            val drum = when (index % 3) {
                0 -> gestures.kick
                1 -> gestures.snare
                else -> gestures.hat
            }
            if (drum > 0f) ripple(stackX[index], stackY[index], index * 0.25f, 0.3f + 0.7f * drum)
            if (gestures.kick > 0f) {
                sparks.burst(stackX[index], stackY[index], gestures.kickSpawn(11), 0.5f, 0.6f, 0.01f, index * 0.25f, Sprite.SPARK)
            }
        }
        for (slot in 0 until RIPPLES) {
            if (rippleAge[slot] <= 0f) continue
            rippleAge[slot] += dt / (gestures.beatSeconds * 2f)
            if (rippleAge[slot] >= 1f) rippleAge[slot] = 0f
        }
        sweep.advance(gestures)
        kit.place(STACKS, sweep.position, 0.5f)
        sparks.advance(dt, drag = 1f)
    }

    private fun ripple(x: Float, y: Float, tint: Float, strength: Float) {
        rippleX[nextRipple] = x
        rippleY[nextRipple] = y
        rippleAge[nextRipple] = 0.001f
        rippleTint[nextRipple] = tint
        rippleStrength[nextRipple] = strength
        nextRipple = (nextRipple + 1) % RIPPLES
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val walk = genes.walk
        for (index in 0 until stacks.drawn(2)) {
            val presence = stacks.presence(index, 2)
            if (presence <= 0.01f) continue
            val grow = if (index == 0) 1f + 0.5f * merge.value else 1f - 0.5f * merge.value
            val reach = sceneRadius * SIZES[index] * (0.45f + 0.25f * state.lift) * grow
            drawStack(state, Offset(stackX[index] * size.width, stackY[index] * size.height), reach, index % 3, presence, walk + index * 0.23f)
        }
    }

    // Drawn outermost first, so each smaller disc lands on top of the one behind it.
    private fun DrawScope.drawStack(state: VizRenderState, at: Offset, reach: Float, part: Int, presence: Float, tint: Float) {
        val punch = 0.1f * core.value.coerceIn(0f, 1f)
        for (option in 0 until 3) {
            val share = discs.weight(option)
            if (share <= 0.01f) continue
            val count = 5 + 2 * option
            for (disc in count - 1 downTo 0) {
                val along = disc.toFloat() / count
                val energy = split.sample(part, along)
                val sway = 1f + wobble.value * sin(wobblePhase * (1f + along * 2f) + disc)
                val radius = reach * ((disc + 1f) / count) * (0.25f + 0.35f * state.lift + 0.35f * energy + punch) * sway
                drawCircle(
                    state.palette.cycled(tint + along * 0.6f, value = 0.3f + 0.7f * energy, alpha = (0.3f + 0.55f * state.lift) * presence * share),
                    radius.coerceAtLeast(1f),
                    at,
                )
            }
        }
        drawCircle(
            state.palette.cap.copy(alpha = ((0.2f + 0.6f * core.value) * presence).coerceIn(0f, 1f)),
            size.minDimension * 0.02f * (0.6f + core.value.coerceIn(0f, 1.6f)),
            at,
        )
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val thin = (size.minDimension * 0.004f).coerceAtLeast(1f)
        for (slot in 0 until RIPPLES) {
            val age = rippleAge[slot]
            if (age <= 0f) continue
            drawCircle(
                state.palette.cycled(rippleTint[slot] + genes.walk, value = 1f, alpha = (0.6f * (1f - age) * rippleStrength[slot]).coerceIn(0f, 1f)),
                sceneRadius * 0.9f * age,
                Offset(rippleX[slot] * size.width, rippleY[slot] * size.height),
                style = Stroke(thin * (1f + 2f * (1f - age))),
            )
        }
        val x = sweep.position * size.width
        val band = size.width * 0.07f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to state.palette.cap.copy(alpha = (0.16f + 0.2f * state.energy).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                startX = x - band,
                endX = x + band,
            ),
            topLeft = Offset(x - band, 0f),
            size = Size(band * 2f, size.height),
            blendMode = BlendMode.Plus,
        )
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        orbits.forEach { it.reset() }
        stackX.fill(0.5f)
        stackY.fill(0.5f)
        shift = 0
        wobblePhase = 0f
        mergeHold = 0f
        merge.reset()
        core.reset()
        sparks.clear()
        rippleAge.fill(0f)
        nextRipple = 0
    }

    private companion object {
        const val STACKS = 4
        const val RIPPLES = 16
        val SIZES = floatArrayOf(1f, 0.72f, 0.58f, 0.48f)
    }
}
