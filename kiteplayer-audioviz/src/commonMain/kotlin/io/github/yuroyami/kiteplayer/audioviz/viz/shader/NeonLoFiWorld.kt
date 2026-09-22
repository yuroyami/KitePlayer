package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.audioviz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.math.*

/** The only simulation owner. Both render paths consume this same snapshot. */
internal class NeonLoFiWorld {
    val history = NeonLoFiHistory()
    val flight = NeonLoFiFlight()
    val regions = NeonLoFiRegions()
    val lanes = FloatArray(16)
    val slowLanes = FloatArray(16)
    val flex = FloatArray(16)
    val impulses = FloatArray(16)
    val profile = FloatArray(512)
    val accents = Array(8) { Accent() }
    val controls = floatArrayOf(0f, 1f, 1f, 1f, 0.5f, 1f, 1f, 1f, 0.6f, 1f, 0.7f, 1f, 1.1f)
    private val wanted = FloatArray(16)
    var slowLevel = 0f; private set
    var bass = 0f; private set
    var air = 0f; private set
    var body = 0f; private set
    var texture = 0f; private set
    var release = 0f; private set
    var retreat = 0f; private set
    private var projectedRevision = -1L
    var rain = 0f; private set
    var time = 0f; private set
    var eventCount = 0L; private set
    var compoundCount = 0L; private set
    var acceptedStrength = 0f; private set
    var profileRevision = 0L; private set
    var available = false; private set
    private var quiet = 0f
    private var rainWanted = false
    private var lastEvents: AudioEventDelivery? = null
    private val seen = arrayOfNulls<AudioEvent>(96)
    private var seenAt = 0
    private var epoch = 0L
    private var lastTime = Float.NaN
    private var lastFrame: SpectrumFrame? = null
    private var settingsInitialized = false
    private var layoutInitialized = false

    class Accent {
        var age = 10f
        var strength = 0f
        var region = 0
        var timestamp = Long.MIN_VALUE
        val applied = FloatArray(16)
    }

    fun advance(state: VizRenderState, values: FloatArray, layout: Float) {
        if (state.timeSeconds == lastTime && state.frame === lastFrame) return
        val dt = if (state.timeSeconds == lastTime) 0f else state.deltaSeconds.coerceIn(0f, 0.25f)
        lastTime = state.timeSeconds; lastFrame = state.frame
        val f = state.frame
        history.record(f)
        if (epoch != history.epoch) { clearActors(); epoch = history.epoch }
        if (f.held) return
        available = f.availability == AnalysisAvailability.Ready
        val audible = if (available) f.audible else 0f
        for (i in controls.indices) controls[i] = if (!settingsInitialized || i == 0 ||
            ((i == 6 || i == 9) && values[i] == 0f)) values[i]
            else NeonLoFiFlight.approach(controls[i], values[i], dt, 0.35f)
        settingsInitialized = true
        if (available) NeonLoFiHistory.resample(f.bands, wanted)
        // Missing analysis is not silence or a musical boundary. Relax only the live foreground.
        if (!available) for (i in wanted.indices) wanted[i] *= exp(-dt * 1.5f)
        for (i in lanes.indices) {
            val before = lanes[i]
            lanes[i] = NeonLoFiFlight.approach(before, wanted[i], dt, if (wanted[i] > before) 0.035f else 0.18f)
            slowLanes[i] = NeonLoFiFlight.approach(slowLanes[i], wanted[i], dt, 0.35f)
            flex[i] = (flex[i] * exp(-dt * 6f) + max(0f, lanes[i] - before)).coerceAtMost(0.65f)
            impulses[i] *= exp(-dt * 7f)
        }
        for (a in accents) a.age += dt
        release *= exp(-dt * 2.5f)
        retreat *= exp(-dt * 0.25f)
        var structural = false
        val delivery = f.events
        if (available && audible > 0f && delivery != null && delivery !== lastEvents && !delivery.reset) {
            for (i in 0 until delivery.size) {
                val event = delivery[i].event
                if (seen.any { event.sameIdentity(it) }) continue
                seen[seenAt] = event; seenAt = (seenAt + 1) % seen.size
                val d = event.detection
                if (d.isHit) applyHit(d, (f.ptsMicros - d.ptsMicros).coerceAtLeast(0L) / 1_000_000f)
                if (d.confidence >= 0.55f && (d.kind == AudioEventKind.SectionBoundary ||
                    d.kind == AudioEventKind.Drop || d.kind == AudioEventKind.Breakdown)) {
                    structural = true
                    if (d.kind == AudioEventKind.Drop) {
                        release = max(release, d.strength * 0.45f)
                        for (b in 3..6) impulses[b] = (impulses[b] + d.strength * 0.16f).coerceAtMost(0.85f)
                    }
                    if (d.kind == AudioEventKind.Breakdown) {
                        retreat = max(retreat, d.strength * 0.7f)
                        if (layout > 0.2f) rainWanted = true
                    }
                }
            }
        }
        lastEvents = delivery
        texture = NeonLoFiFlight.approach(texture, if (available) f.flatness else texture, dt, 0.35f)
        slowLevel = NeonLoFiFlight.approach(slowLevel, if (available) f.loudLong else 0f, dt, 1f)
        bass = NeonLoFiFlight.approach(bass, if (available) f.bass else 0f, dt, 0.1f)
        air = NeonLoFiFlight.approach(air, if (available) f.treble else 0f, dt, 0.12f)
        body = NeonLoFiFlight.approach(body, if (available) f.mid else 0f, dt, 0.15f)
        quiet = if (audible <= 0f) quiet + dt else 0f
        if (available && audible > 0f) {
            if (projectedRevision != history.revision) { history.project(); projectedRevision = history.revision }
            var changed = false
            for (i in profile.indices) {
                val v = NeonLoFiFlight.approach(profile[i], history.profile[i], dt, 0.35f)
                if (abs(v - profile[i]) > 0.00001f) changed = true
                profile[i] = v
            }
            if (changed) profileRevision++
        }
        // Measured zeros continue entering the ring while the last audible ridge holds still.
        flight.layout = if (layoutInitialized) NeonLoFiFlight.approach(flight.layout, layout, dt, 0.7f) else layout
        layoutInitialized = true
        flight.motion = state.motionScale.coerceIn(0f, 1f)
        flight.advance(dt, audible, if (available) f.mood else 0f, controls[6] * (1f - 0.45f * retreat), f.pulse)
        if (available && audible > 0f) regions.advance(dt, audible, controls[0].toInt() - 1,
            controls[5], layout, f.energy, f.density, f.centroid, structural)
        time += dt * audible * state.motionScale
        if (available && audible > 0f && f.energy < 0.22f && f.density < 0.2f && layout > 0.45f) rainWanted = true
        if (available && f.energy > 0.42f) rainWanted = false
        val targetRain = if (audible > 0f && rainWanted) 0.65f else 0f
        rain = NeonLoFiFlight.approach(rain, targetRain, dt, if (quiet > 0f) 0.45f else if (targetRain > rain) 8f else 14f)
        if (quiet > 3f) { flight.advance(0.25f, 0f, 0f, 0f, 0f); for (a in accents) a.age = 10f }
    }

    private fun applyHit(d: AudioDetection, lateness: Float) {
        eventCount++; acceptedStrength += d.strength
        val region = when (d.kind) { AudioEventKind.LowTransient -> 0; AudioEventKind.BodyTransient -> 1
            AudioEventKind.HighTransient -> 2; else -> 3 }
        val strength = d.strength * exp(-lateness * 5f)
        // Generic and regional descriptions of one attack share a bounded visual accent.
        val compound = accents.firstOrNull { it.age < 2.5f && abs(it.timestamp - d.ptsMicros) <= 25_000L }
        val accent = compound ?: accents.firstOrNull { it.age >= 2.5f }
            ?: accents.minBy { it.strength * exp(-it.age * 2f) }
        if (compound == null) {
            compoundCount++; accent.age = lateness; accent.strength = strength
            accent.region = region; accent.timestamp = d.ptsMicros; accent.applied.fill(0f)
        } else { accent.strength = max(accent.strength, strength); if (region != 3) accent.region = region }
        val first = when (region) { 0 -> 0; 1 -> 4; 2 -> 10; else -> wanted.indices.maxBy { wanted[it] } }
        val end = when (region) { 0 -> 4; 1 -> 10; 2 -> 16; else -> first + 1 }
        for (i in first until end) {
            val contribution = strength * 0.32f
            impulses[i] = (impulses[i] + max(0f, contribution - accent.applied[i])).coerceAtMost(0.85f)
            accent.applied[i] = max(accent.applied[i], contribution)
        }
    }

    fun floorHeight(lane: Int, worldZ: Float, edge: Float = 1f): Float {
        val regional = when { lane < 4 -> bass; lane < 10 -> body; else -> air }
        val local = lanes[lane] * 0.72f + slowLanes[lane] * 0.18f + regional * 0.10f
        val articulation = flex[lane] * (0.5f + 0.5f * cos(worldZ * 0.6f + lane * 0.8f))
        val crest = impulses[lane] * controls[10]
        return ((local.pow(0.8f) * 2.3f + articulation * 1.8f + crest) * controls[1] * edge).coerceIn(0f, 6f)
    }
    private fun clearActors() {
        lanes.fill(0f); slowLanes.fill(0f); flex.fill(0f); impulses.fill(0f); profile.fill(0f)
        accents.forEach { it.age = 10f; it.timestamp = Long.MIN_VALUE }
        seen.fill(null); seenAt = 0; flight.reset(); regions.reset(); quiet = 0f; time = 0f
        eventCount = 0; compoundCount = 0; acceptedStrength = 0f; rain = 0f; rainWanted = false
        slowLevel = 0f; bass = 0f; air = 0f; body = 0f; texture = 0f; retreat = 0f; wanted.fill(0f)
        profileRevision++; projectedRevision = -1L; lastEvents = null; release = 0f; layoutInitialized = false
    }
    fun reset() {
        history.clear(); clearActors(); epoch = history.epoch; lastFrame = null; lastTime = Float.NaN
        slowLevel = 0f; air = 0f; body = 0f; settingsInitialized = false
    }
}
