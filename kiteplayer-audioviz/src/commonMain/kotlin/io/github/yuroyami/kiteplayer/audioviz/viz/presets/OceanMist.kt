package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A clear waveform above a restrained water field. Loudness gives the wave its height; onset
 * density and section mood decide how much the water, history and mist move around it. Scope's
 * separate channel traces are an optional view of this same scene.
 */
internal class OceanMist : Layered(
    name = "Ocean Mist",
    family = VizFamily.BarsAndWaves,
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 203L, groundKind = GroundKind.Water, groundDim = 0.1f, groundParallax = 0f,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 203),
    ),
) {
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Size),
        VizDrive(VizDriver.SlowLevel, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1.2f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1.2f)),
        VizDrive(VizDriver.Onset, VizProperty.Speed, response = VizResponse.envelope(0.7f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.envelope(1.4f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.7f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1.2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1.6f)),
        echoes = true,
    )
    // A fixed viewpoint also excludes the shared camera's snare nudge and drop whip.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off
    override val trail: Float get() = trails.value * (0.8f + 0.2f * activity)

    private val motion = VizParam("Motion", 0f, 2f, 0.65f)
    private val contrast = VizParam("Section contrast", 0.5f, 2.5f, 1.35f)
    private val waveHeight = VizParam("Wave height", 0.1f, 1.5f, 0.8f)
    private val lineWidth = VizParam("Line width", 0.5f, 3f, 1f)
    private val curvature = VizParam("Spine curve", 0f, 0.2f, 0.035f)
    private val stereo = VizParam("Stereo traces", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val historyCount = VizParam("History traces", 0f, 2f, 2f).apply { step = 1f }
    private val historyDelay = VizParam("History spacing (s)", 0.15f, 0.8f, 0.45f)
    private val historyDepth = VizParam("History depth", 0f, 0.15f, 0.035f)
    private val trails = VizParam("Trails", 0f, 0.85f, 0.32f)
    private val spectrum = VizParam("Spectrum veil", 0f, 1f, 0.45f)
    private val swarmSize = VizParam("Swarm dots", 0f, MAX_DOTS.toFloat(), 24f).apply { step = 1f }
    private val mistAmount = VizParam("Mist", 0f, 2f, 0.4f)
    private val sprayAmount = VizParam("Drum spray", 0f, 2f, 0.5f)
    private val rippleAmount = VizParam("Ripples", 0f, 1f, 0.45f)
    private val dropAccent = VizParam("Drop swell", 0f, 1f, 0.65f)
    private val background = VizParam("Water background", 0f, 1f, 0.3f)
    private val brightness = VizParam("Brightness", 0.1f, 1f, 0.85f)
    override val params: List<VizParam> = listOf(
        motion, contrast, waveHeight, lineWidth, curvature, stereo, historyCount, historyDelay,
        historyDepth, trails, spectrum, swarmSize, mistAmount, sprayAmount, rippleAmount,
        dropAccent, background, brightness,
    )

    private val history = History(rows = 256)
    private val traceSampler = WaveformResampler()
    private val leftSampler = WaveformResampler()
    private val rightSampler = WaveformResampler()
    private val squeezed = FloatArray(TRACE)
    private val left = FloatArray(TRACE)
    private val right = FloatArray(TRACE)
    private var activity = 0f
    private var character = 0f
    private var sectionWindow = 0f
    private var swell = 0f
    private var retreat = 0f
    private var wave = 0f
    private var rise = 0f
    private var reach = 0f
    private var visible = 0f
    private var mistCredit = 0f
    private var ringCooldown = 0f
    private val swarm = Swarm(MAX_DOTS, 1_203L)
    private val spray = Sprites(96, 1_203L)
    private val mist = Sprites(64, 2_203L)
    private val rings = Travellers(6)
    private val spine = TriangleMesh(maxVertices = BAND_BATCH * 6)
    private val ringMesh = TriangleMesh(maxVertices = 6 * 40)
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds.coerceIn(0f, 0.25f)
        val frame = state.frame
        val audible = frame.audible
        traceSampler.resample(frame.scope, squeezed)
        history.push(squeezed, state.timeSeconds)
        if (stereo.value > 0.5f) {
            leftSampler.resample(if (frame.scopeLeft.isEmpty()) frame.scope else frame.scopeLeft, left)
            rightSampler.resample(if (frame.scopeRight.isEmpty()) frame.scope else frame.scopeRight, right)
        }

        // Boundaries shorten settling; they never pick a random configuration. Without boundary
        // metadata the continuous measured features still distinguish sparse and dense passages.
        sectionWindow = (sectionWindow - dt).coerceAtLeast(0f)
        swell *= exp(-dt / 1.2f)
        retreat *= exp(-dt / 1.6f)
        if (gestures.section) sectionWindow = 0.7f
        if (gestures.drop) { swell = 1f; retreat = 0f; sectionWindow = 0.7f }
        if (gestures.breakdown) { retreat = 1f; swell = 0f; sectionWindow = 0.7f }
        val busy = (0.55f * frame.density + 0.3f * frame.mood +
            0.15f * (frame.novelty / 4f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val target = (busy.pow(contrast.value) * (1f - 0.6f * retreat) +
            0.18f * swell * dropAccent.value).coerceIn(0f, 1f) * audible
        val settle = if (sectionWindow > 0f) 0.3f else if (target > activity) 0.65f else 1.3f
        activity += (target - activity) * (1f - exp(-dt / settle))
        character += ((frame.trebleRel - frame.bassRel) * audible - character) * (1f - exp(-dt / 1.2f))
        val motionScale = motion.value * state.motionScale
        wave = (wave + dt * audible * motionScale * (0.015f + 0.65f * activity)) % TAU
        rise += ((frame.loudLong - 0.5f) * 0.05f * audible - rise) * (1f - exp(-dt))
        reach = waveHeight.value * (0.12f + 0.22f * state.energy) * frame.waveformGain *
            (1f + 0.2f * swell * dropAccent.value)
        visible = brightness.value * (0.18f + 0.82f * frame.loudShort.coerceIn(0f, 1f))
        ground?.dim = background.value * brightness.value * (0.12f + 0.6f * activity)

        var best = 0
        for (index in squeezed.indices) if (abs(squeezed[index]) > abs(squeezed[best])) best = index
        val loudX = best.toFloat() / (TRACE - 1)
        val loudY = spineY(loudX) - squeezed[best].coerceIn(-1f, 1f) * reach
        val follow = 1f - exp(-dt / 0.75f)
        swarm.targetX += (loudX - swarm.targetX) * follow
        swarm.targetY += (loudY - swarm.targetY) * follow
        // The actor caps one step at 50 ms. Substeps preserve speed in the 15 fps menu preview.
        var remaining = dt
        while (remaining > 0f) {
            val step = minOf(remaining, 0.05f)
            swarm.advance(step, speed = (0.008f + 0.22f * activity) * motionScale * audible)
            remaining = (remaining - step).coerceAtLeast(0f)
        }

        spray.advance(dt, drag = 1.1f, gravity = 0.12f * motionScale)
        mist.advance(dt, drag = 0.3f)
        rings.advance(dt)
        ringCooldown = (ringCooldown - dt).coerceAtLeast(0f)
        val hit = maxOf(gestures.kick, gestures.snare)
        val bands = frame.bandsRel
        if (hit > 0f && audible > 0f && bands.isNotEmpty()) {
            var loudest = 0
            for (index in bands.indices) if (bands[index] > bands[loudest]) loudest = index
            val along = (loudest + 0.5f) / bands.size
            val tip = spineY(along) - bands[loudest].coerceIn(0f, 1f) * veilHeight()
            val count = ((2f + 8f * hit) * sprayAmount.value * (0.2f + 0.8f * activity)).toInt()
            if (count > 0) spray.burst(along, tip, count.coerceAtMost(24),
                (0.05f + 0.17f * activity) * motionScale, 1.2f, 0.004f, along, Sprite.SPARK, UP, 0.8f)
            if (gestures.kick > 0f && activity > 0.25f && ringCooldown <= 0f && rippleAmount.value > 0f) {
                rings.spawn(along, tip, along + random.signed() * 0.08f * motionScale, tip - 0.04f * motionScale,
                    seconds = (gestures.beatSeconds * 1.5f).coerceIn(0.4f, 1.5f),
                    size = 0.018f, tint = along, kind = Sprite.RING)
                ringCooldown = 0.4f
            }
        }
        // Emission is per audible second, not per rendered frame. Quiet sections have almost none.
        mistCredit += dt * audible * mistAmount.value * (0.2f + 12f * activity * activity)
        val count = mistCredit.toInt().coerceAtMost(8)
        mistCredit = (mistCredit - count).coerceIn(0f, 1f)
        if (count > 0) mist.sprinkle(count, 2f, 0.006f, random.next(), Sprite.GLOW, drift = 0.012f * motionScale)
    }

    private fun spineY(along: Float): Float = 0.5f - rise + curvature.value *
        (0.2f + 0.8f * activity) * sin(along * TAU * (1f + 0.2f * character) + wave)

    private fun veilHeight(): Float = waveHeight.value * (0.06f + 0.18f * activity)

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (spectrum.value > 0f && bands.isNotEmpty()) {
            spine.clear()
            val slot = size.width / bands.size
            for (index in bands.indices) {
                if (spine.vertexCount + 6 > spine.maxVertices) {
                    drawMesh(spine)
                    spine.clear()
                }
                val along = (index + 0.5f) / bands.size
                addSpineBar(state, along, slot, bands[index].coerceIn(0f, 1f))
            }
            drawMesh(spine)
        }
        // A sparse passage has one readable live line. History gradually appears as density rises.
        for (depth in historyCount.value.toInt() downTo 1) {
            val alpha = visible * ((activity - 0.12f * depth) / 0.65f).coerceIn(0f, 1f) * 0.35f / depth
            if (alpha < 0.005f) continue
            val row = history.row(depth * historyDelay.value)
            if (row < 0) continue
            tracePath(reach * (1f + 0.15f * depth), -historyDepth.value * depth,
                state.palette.cycled(0.16f * depth + genes.walk).copy(alpha = alpha)) { index ->
                history.sample(row, index.toFloat() / (TRACE - 1))
            }
        }
    }

    private fun DrawScope.addSpineBar(state: VizRenderState, along: Float, slot: Float, value: Float) {
        // Use this pass's height. Echo buffers and the sharp foreground need not share a size.
        val middle = spineY(along) * size.height
        val tall = value * size.height * veilHeight()
        if (tall < 0.5f) return
        val tint = state.palette.ramp(value)
        val full = tint.copy(alpha = visible * spectrum.value * (0.25f + 0.45f * activity)).toArgb()
        val clear = tint.copy(alpha = 0f).toArgb()
        val x = along * size.width
        val a = spine.vertex(x - slot * 0.46f, middle - tall, clear)
        val b = spine.vertex(x + slot * 0.46f, middle - tall, clear)
        val c = spine.vertex(x + slot * 0.46f, middle, full)
        val d = spine.vertex(x - slot * 0.46f, middle, full)
        spine.quad(a, b, c, d)
        val e = spine.vertex(x + slot * 0.46f, middle + tall, clear)
        val f = spine.vertex(x - slot * 0.46f, middle + tall, clear)
        spine.quad(d, c, e, f)
    }

    private inline fun DrawScope.tracePath(height: Float, offsetY: Float, colour: Color, sample: (Int) -> Float) {
        path.reset()
        for (index in 0 until TRACE) {
            val along = index.toFloat() / (TRACE - 1)
            val x = along * size.width
            val y = (spineY(along) + offsetY - sample(index).coerceIn(-1f, 1f) * height) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colour, style = Stroke((size.minDimension * 0.0025f * lineWidth.value).coerceAtLeast(1f), cap = StrokeCap.Round))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(mist) { drawSprites(state.palette, genes.walk, alpha = 0.25f * visible, blendMode = BlendMode.SrcOver) }
        with(spray) { drawSprites(state.palette, genes.walk, alpha = 0.7f * visible, blendMode = BlendMode.SrcOver) }
        drawTravellers(rings, ringMesh, state.palette, genes.walk, alpha = 0.4f * visible * rippleAmount.value, blendMode = BlendMode.SrcOver)
        val dots = swarmSize.value * (0.15f + 0.85f * activity)
        for (index in 0 until swarmSize.value.toInt()) {
            val presence = (dots - index).coerceIn(0f, 1f)
            if (presence <= 0f) continue
            drawCircle(state.palette.cycled(index * 0.017f + genes.walk).copy(alpha = visible * presence * (0.08f + 0.4f * activity)),
                radius = (size.minDimension * 0.0018f * (1f + 0.3f * (index % 3))).coerceAtLeast(0.7f),
                center = Offset(swarm.x[index] * size.width, swarm.y[index] * size.height))
        }
        // Draw the current waveform at full resolution, outside feedback and bloom.
        if (stereo.value > 0.5f) {
            tracePath(reach, -0.014f, state.palette.cap.copy(alpha = visible)) { left[it] }
            tracePath(reach, 0.014f, state.palette.mid.copy(alpha = 0.8f * visible)) { right[it] }
        } else {
            tracePath(reach, 0f, state.palette.cap.copy(alpha = visible)) { squeezed[it] }
        }
    }

    override fun onReset() {
        history.clear()
        squeezed.fill(0f)
        left.fill(0f)
        right.fill(0f)
        activity = 0f
        character = 0f
        sectionWindow = 0f
        swell = 0f
        retreat = 0f
        wave = 0f
        rise = 0f
        reach = 0f
        visible = 0f
        mistCredit = 0f
        ringCooldown = 0f
        swarm.scatter()
        swarm.targetX = 0.5f
        swarm.targetY = 0.5f
        spray.clear()
        mist.clear()
        rings.clear()
    }

    private companion object {
        const val TRACE = 256
        const val MAX_DOTS = 48
        const val BAND_BATCH = 64
    }
}
