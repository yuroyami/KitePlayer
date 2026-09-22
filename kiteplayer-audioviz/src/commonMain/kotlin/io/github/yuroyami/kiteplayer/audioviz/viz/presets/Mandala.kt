package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Journey
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** One set of musical filaments unfolds from a radial flower into a solid, web and wave sheet. */
internal class Mandala : Layered("Mandala", VizFamily.Immersion, VizEnergy.Mid,
    Kit(801L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false))) {
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Timbre, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(6f)),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
    )
    private val automatic = VizParam("Journey", 0f, 1f, 1f).apply { toggle = true; step = 1f }
    private val form = VizParam("Form", 0f, 4f, 0f).apply {
        step = 1f; choices = listOf("Mandala", "Wireframe", "Strobe Web", "Strands", "Wave")
        shownWhen = { automatic.value < 0.5f }
    }
    private val pace = VizParam("Transformation pace", 0.35f, 2f, 1f)
    private val response = VizParam("Musical movement", 0f, 2f, 1f)
    private val depth = VizParam("Depth", 0f, 1.5f, 1f)
    private val orbit = VizParam("Camera orbit", 0f, 1f, 0.65f)
    private val thickness = VizParam("Line width", 0.4f, 3f, 1f)
    private val glow = VizParam("Filament glow", 0f, 1f, 0.3f)
    override val params = listOf(automatic, form, pace, response, depth, orbit, thickness, glow)
    internal val journey = Journey(5, 8_017L)
    internal val geometry = StrandGeometry()
    private val affinity = FloatArray(5)
    private val bands = FloatArray(StrandGeometry.STRANDS)
    private val scope = FloatArray(StrandGeometry.POINTS)
    private val path = Path()
    private var phase = 0.0
    private var activity = 0f
    private var pulse = 0f
    private var rotation = 0f
    private var tilt = 0f

    override fun advance(state: VizRenderState) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f)
        val f = state.frame
        activity += ((0.5f * f.density + 0.3f * state.drive + 0.1f * f.novelty.coerceIn(0f, 2f)) - activity) * (1f - exp(-dt * 3f))
        affinity[0] = 0.4f + f.bass * 0.6f
        affinity[1] = 0.4f + f.mid * 0.7f
        affinity[2] = 0.1f + activity * 1.2f + f.flatness * 0.6f
        affinity[3] = 0.4f + f.width * 0.7f + f.treble * 0.3f
        affinity[4] = 0.2f + (1f - activity) * 0.7f
        journey.advance(state, gestures, affinity, automatic.value >= 0.5f, form.value.toInt(), pace.value)
        phase += dt * f.audible * state.motionScale * (0.16f + activity * 2.2f)
        val ease = 1f - exp(-dt * 10f)
        for (i in bands.indices) bands[i] += (f.bandsRel.sampleAt(i.toFloat() / (bands.size - 1)) - bands[i]) * ease
        for (i in scope.indices) scope[i] += (f.scope.sampleAt(i.toFloat() / (scope.size - 1)) * f.waveformGain - scope[i]) * ease
        pulse = maxOf(pulse * exp(-dt * 4f), gestures.kickAccent * 0.7f, gestures.snareAccent * 0.5f) * state.motionScale
        val p = phase.toFloat()
        val solid = journey.weights[1] + journey.weights[2]
        rotation = sin(p * 0.21f) * solid * orbit.value * 1.8f
        tilt = sin(p * 0.13f) * solid * orbit.value * 0.75f
        geometry.update(journey.weights, p, activity, bands, scope, pulse, kit.aspect,
            response.value * (0.15f + state.motionScale * 0.85f), depth.value)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val scale = size.minDimension * 0.36f
        val points = geometry.positions
        val c = cos(rotation); val s = sin(rotation); val ct = cos(tilt); val st = sin(tilt)
        for (strand in 0 until StrandGeometry.STRANDS) {
            path.reset()
            var bead = Offset.Zero
            var meanDepth = 0f
            val head = ((phase * 0.16 + strand * 0.037) % 1.0 * (StrandGeometry.POINTS - 1)).toInt()
            for (step in 0 until StrandGeometry.POINTS) {
                val p = (strand * StrandGeometry.POINTS + step) * 3
                val x = points[p] * c + points[p + 2] * s
                val z = points[p + 2] * c - points[p] * s
                val y = points[p + 1] * ct - z * st
                val distance = 3.8f + z * ct + points[p + 1] * st
                meanDepth += distance
                val perspective = 3.8f / distance.coerceAtLeast(1.2f)
                val sx = center.x + x * scale * perspective
                val sy = center.y + y * scale * perspective
                if (step == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                if (step == head) bead = Offset(sx, sy)
            }
            val tint = strand.toFloat() / StrandGeometry.STRANDS * 0.78f
            val light = (0.62f + 0.35f * bands[strand] + 0.1f * pulse).coerceIn(0f, 1f)
            val width = (size.minDimension * 0.0036f * thickness.value).coerceAtLeast(0.9f)
            val depthLight = (1.25f - meanDepth / StrandGeometry.POINTS * 0.1f).coerceIn(0.55f, 1f)
            val colour = state.palette.cycled(tint, saturation = 0.72f, value = light,
                alpha = (0.62f + 0.35f * state.lift) * depthLight)
            if (glow.value > 0f) drawPath(path, colour.copy(alpha = glow.value * 0.18f), style = Stroke(width * 3.5f, cap = StrokeCap.Round))
            drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round))
            drawPath(path, state.palette.cycled(tint, saturation = 0.18f, value = 0.95f,
                alpha = 0.18f * depthLight), style = Stroke(width * 0.4f, cap = StrokeCap.Round))
            if (bands[strand] > 0.08f) drawCircle(state.palette.cycled(tint, saturation = 0.25f,
                value = 0.9f, alpha = bands[strand].coerceIn(0f, 1f)), width * 1.2f, bead)
        }
    }

    override fun onReset() {
        journey.reset(); bands.fill(0f); scope.fill(0f)
        phase = 0.0; activity = 0f; pulse = 0f; rotation = 0f; tilt = 0f
        geometry.update(journey.weights, 0f, 0f, bands, scope, 0f, 1f, 1f, 1f)
    }
}
