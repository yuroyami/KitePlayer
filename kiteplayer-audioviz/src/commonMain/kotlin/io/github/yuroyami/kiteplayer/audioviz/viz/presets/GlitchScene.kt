package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.math.*

/** Audio-owned choreography shared by every layer. Geometry never advances this state. */
internal class GlitchScene {
    val bands = FloatArray(64)
    /** Newest first, sampled on a 30 Hz media-time lattice rather than once per display frame. */
    val history = FloatArray(64)
    /** Independent additive strengths: wheel, crystal, eclipse, ribbon, tunnel. */
    val weights = RECIPES[0].copyOf()
    var level = 0f; private set
    var bass = 0f; private set
    var body = 0f; private set
    var air = 0f; private set
    var lowAccent = 0f; private set
    var bodyAccent = 0f; private set
    var highAccent = 0f; private set
    var pressure = 0f; private set
    /** Scene units and radians, integrated from audible time. Hue is a turn in 0..1. */
    var travel = 0f; private set
    var turn = 0f; private set
    var hue = 0.04f; private set
    /** Target dominant layer, using the same indices as [weights]. */
    var composition = 0; private set
    var transitions = 0; private set
    var acceptedHits = 0L; private set
    var historySamples = 0L; private set

    private val gestures = Gestures()
    private val step = DisplayStep()
    private val measured = FloatArray(64)
    private val smoothBands = FloatArray(64)
    private val sequences = LongArray(AudioEventSource.entries.size) { -1L }
    private val rawTimes = LongArray(AudioEventKind.entries.size) { Long.MIN_VALUE }
    private val previousScalar = FloatArray(3)
    private val recentKinds = IntArray(12)
    private val recentTimes = LongArray(12) { Long.MIN_VALUE }
    private val recentApplied = Array(12) { FloatArray(3) }
    private var recentAt = 0
    private var generation: Generation? = null
    private var revision = -1L
    private var lastReset: AudioEventDelivery? = null
    private var lastPts = Long.MIN_VALUE
    private var previousHistoryPts = Long.MIN_VALUE
    private var previousHistory = 0f
    private var nextHistoryTick = Long.MIN_VALUE
    private var initialized = false
    private var mode = 0
    private var baseLevel = 0f
    private var baseBass = 0f
    private var baseBody = 0f
    private var baseAir = 0f
    private var basePressure = 0f
    private var response = 1f
    private var residence = 0f
    private var hitBudget = 0f
    private var referenceLevel = 0f
    private var referenceCentroid = 0f
    private var referenceDensity = 0f

    fun advance(state: VizRenderState, response: Float = 1f, changes: Float = 1f,
        travelSpeed: Float = 1f, rotationSpeed: Float = 1f, compositionMode: Int = 0) {
        val frame = state.frame
        val old = generation
        // A late read from a retired analysis cannot erase the current picture.
        if (old != null && (frame.generation < old ||
                frame.generation == old && frame.analysisRevision < revision)) return
        val resetDelivery = frame.events?.takeIf { it.reset && it !== lastReset }
        val discontinuity = frame.hasTimestamp && lastPts != Long.MIN_VALUE &&
            (frame.ptsMicros < lastPts || frame.ptsMicros - lastPts > 750_000L)
        if (old != frame.generation || revision != frame.analysisRevision || discontinuity || resetDelivery != null) {
            clear()
            generation = frame.generation; revision = frame.analysisRevision
            lastReset = resetDelivery
        }
        configure(compositionMode, response)
        val elapsed = step.of(state)
        if (elapsed == null) { expose(); return }
        val dt = elapsed.coerceIn(0f, 0.25f)
        val ready = frame.availability == AnalysisAvailability.Ready
        if (frame.hasTimestamp) lastPts = frame.ptsMicros
        val first = !initialized && ready
        if (first) {
            initialized = true
            resample(frame.bands)
            measured.copyInto(smoothBands)
            baseLevel = unit(frame.level); baseBass = unit(frame.bass)
            baseBody = unit(frame.mid); baseAir = unit(frame.treble)
            basePressure = pressureOf(frame)
            rememberContrast(frame)
        }
        if (frame.held) {
            if (first) history.fill(historyValue(frame))
            expose()
            return
        }
        gestures.update(state)
        val audible = if (ready) unit(frame.audible) else 0f
        if (ready) resample(frame.bands) else measured.fill(0f)
        for (i in bands.indices) smoothBands[i] = follow(smoothBands[i], measured[i], dt)
        baseLevel = follow(baseLevel, if (ready) unit(frame.level) else 0f, dt)
        baseBass = follow(baseBass, if (ready) unit(frame.bass) else 0f, dt)
        baseBody = follow(baseBody, if (ready) unit(frame.mid) else 0f, dt)
        baseAir = follow(baseAir, if (ready) unit(frame.treble) else 0f, dt)
        basePressure = approach(basePressure, if (ready) pressureOf(frame) else 0f, dt, 0.28f)
        lowAccent *= exp(-dt * 5f)
        bodyAccent *= exp(-dt * 7f)
        highAccent *= exp(-dt * 11f)
        hitBudget *= exp(-dt / 12f)
        if (ready) sampleHistory(frame)
        val motion = finite(state.motionScale, 0f, 1f)
        if (ready && audible > 0f) {
            consumeHits(frame, motion)
            residence += dt * audible
            val changeRate = finite(changes, 0f, 2f)
            if (mode == 0 && changeRate > 0f) {
                val contrast = abs(unit(frame.level) - referenceLevel) * 0.6f +
                    abs(unit(frame.centroid) - referenceCentroid) * 0.65f +
                    abs(unit(frame.density) - referenceDensity) * 0.5f
                val evidence = residence >= 6f / changeRate &&
                    (hitBudget >= 8f / changeRate || contrast > 0.22f)
                // React to the first structural cue immediately, then let the visible dissolve
                // establish itself before another cue can request a different destination.
                val structural = gestures.section && (transitions == 0 || residence >= 1.4f)
                if (structural || evidence) {
                    choose(frame, gestures.drop, gestures.breakdown)
                    rememberContrast(frame)
                    residence = 0f; hitBudget = 0f
                }
            }
            val rate = 0.10f + 0.45f * basePressure + 0.30f * baseLevel + 0.15f * lowAccent
            travel += dt * audible * motion * finite(travelSpeed, 0f, 3f) * rate
            turn += dt * audible * motion * finite(rotationSpeed, 0f, 3f) *
                (0.045f + 0.12f * basePressure + 0.06f * baseAir)
            val colourRate = 0.006f + 0.018f * basePressure + 0.009f * baseAir
            hue = (hue + dt * audible * motion * colourRate) % 1f
        }
        val recipe = RECIPES[composition]
        for (i in weights.indices) weights[i] = approach(weights[i], recipe[i], dt, 0.7f)
        expose()
    }

    /** A deliberate paused selection applies at once without adding time or replaying sound. */
    fun configure(compositionMode: Int, response: Float = 1f) {
        this.response = finite(response, 0f, 2f)
        expose()
        val requested = compositionMode.coerceIn(0, 5)
        if (requested == mode) return
        mode = requested
        if (requested != 0) {
            composition = MODE_TARGET[requested]
            RECIPES[composition].copyInto(weights)
            residence = 0f; hitBudget = 0f
        }
    }

    private fun consumeHits(frame: SpectrumFrame, motion: Float) {
        val delivery = frame.events
        if (delivery != null) {
            if (delivery.generation != frame.generation || delivery.analysisRevision != frame.analysisRevision) return
            for (i in 0 until delivery.size) {
                val item = delivery[i]
                val event = item.event
                if (event.generation != frame.generation || event.analysisRevision != frame.analysisRevision) continue
                val source = event.source.ordinal
                if (event.sequence <= sequences[source]) continue
                sequences[source] = event.sequence
                if (!delivery.reset) accept(event.detection, motion, item.lateByMicros)
            }
        } else if (frame.detections != null) {
            val detections = frame.detections
            for (i in 0 until detections.size) {
                val d = detections[i]
                if (d.ptsMicros <= rawTimes[d.kind.ordinal]) continue
                rawTimes[d.kind.ordinal] = d.ptsMicros
                accept(d, motion, 0L)
            }
        } else {
            // Hand-authored frames have no event identity. Only rising scalar attacks are edges.
            val low = unit(frame.kick); val mid = unit(frame.snare); val high = unit(frame.hat)
            scalarHit(0, low, motion); scalarHit(1, mid, motion); scalarHit(2, high, motion)
        }
    }

    private fun scalarHit(region: Int, value: Float, motion: Float) {
        if (value > previousScalar[region] + 0.01f) {
            apply(region, value * motion)
            acceptedHits++; hitBudget += 0.4f + 0.6f * value
        }
        previousScalar[region] = value
    }

    private fun accept(d: AudioDetection, motion: Float, lateMicros: Long) {
        if (!d.isHit || !d.strength.isFinite() || !d.confidence.isFinite()) return
        val region = when (d.kind) {
            AudioEventKind.LowTransient -> 0
            AudioEventKind.BodyTransient -> 1
            AudioEventKind.HighTransient -> 2
            else -> -1
        }
        val kind = if (region < 0) 8 else 1 shl region
        val strength = unit(d.strength) * exp(-lateMicros.coerceAtLeast(0L) / 1_000_000f * 7f)
        // One generic onset and its regional descriptions are one physical accent. Two records
        // of the same region remain separate even when a low frame rate batches them together.
        var slot = -1
        for (i in recentTimes.indices) if (recentTimes[i] != Long.MIN_VALUE &&
            abs(recentTimes[i] - d.ptsMicros) <= 25_000L && recentKinds[i] and kind == 0) {
            slot = i; break
        }
        if (slot < 0) {
            slot = recentAt; recentAt = (recentAt + 1) % recentTimes.size
            recentTimes[slot] = d.ptsMicros; recentKinds[slot] = 0
            recentApplied[slot].fill(0f)
            acceptedHits++; hitBudget += 0.4f + 0.6f * strength
        }
        recentKinds[slot] = recentKinds[slot] or kind
        // An unclassified onset occupies all three channels gently. A regional refinement only
        // supplies the missing part of that channel, so it cannot stack two full impulses.
        for (channel in 0..2) if (region < 0 || region == channel) {
            val wanted = strength * if (region < 0) 0.28f else 1f
            val added = max(0f, wanted - recentApplied[slot][channel])
            recentApplied[slot][channel] = max(recentApplied[slot][channel], wanted)
            apply(channel, added * motion)
        }
    }

    private fun apply(region: Int, amount: Float) {
        when (region) {
            0 -> lowAccent = (lowAccent + amount).coerceAtMost(1f)
            1 -> bodyAccent = (bodyAccent + amount).coerceAtMost(1f)
            else -> highAccent = (highAccent + amount).coerceAtMost(1f)
        }
    }

    private fun choose(frame: SpectrumFrame, drop: Boolean, breakdown: Boolean) {
        var selected = composition
        if (breakdown) selected = if (composition == 2) 3 else 2
        else if (drop) selected = if (composition == 4) 0 else 4
        else {
            var best = -1f
            for (i in weights.indices) {
                if (i == composition) continue
                val affinity = when (i) {
                    0 -> 0.32f + unit(frame.bass) * 0.35f + unit(frame.density) * 0.18f
                    1 -> 0.24f + unit(frame.mid) * 0.55f + bodyAccent * 0.20f
                    2 -> 0.24f + unit(frame.bass) * 0.40f + (1f - unit(frame.density)) * 0.28f
                    3 -> 0.28f + unit(frame.mid) * 0.45f + unit(frame.width) * 0.30f
                    else -> 0.15f + unit(frame.density) * 0.55f + unit(frame.energy) * 0.30f
                }
                val variety = 0.84f + ((transitions * 7 + i * 3) % 5) * 0.08f
                if (affinity * variety > best) { best = affinity * variety; selected = i }
            }
        }
        if (selected != composition) { composition = selected; transitions++ }
    }

    private fun rememberContrast(frame: SpectrumFrame) {
        referenceLevel = unit(frame.level); referenceCentroid = unit(frame.centroid)
        referenceDensity = unit(frame.density)
    }

    private fun sampleHistory(frame: SpectrumFrame) {
        if (!frame.hasTimestamp) return
        val pts = frame.ptsMicros
        val value = historyValue(frame)
        val tick = floor(pts / 1_000_000.0 * 30).toLong()
        if (previousHistoryPts == Long.MIN_VALUE) {
            history[0] = value; historySamples++
            nextHistoryTick = tick + 1
        } else if (pts > previousHistoryPts) {
            // Capped work after a stall; no unbounded catch-up or display-rate-dependent shifting.
            val first = max(nextHistoryTick, tick - history.size + 1)
            for (at in first..tick) {
                val micros = at * (1_000_000.0 / 30)
                val mix = ((micros - previousHistoryPts) / (pts - previousHistoryPts)).toFloat().coerceIn(0f, 1f)
                for (i in history.lastIndex downTo 1) history[i] = history[i - 1]
                history[0] = previousHistory + (value - previousHistory) * mix
                historySamples++
            }
            nextHistoryTick = tick + 1
        }
        previousHistoryPts = pts; previousHistory = value
    }

    private fun resample(source: FloatArray) {
        if (source.isEmpty()) { measured.fill(0f); return }
        val width = source.size.toFloat() / measured.size
        for (i in measured.indices) {
            val a = i * width; val b = (i + 1) * width
            var sum = 0f
            for (j in floor(a).toInt() until ceil(b).toInt()) {
                val overlap = (min(b, j + 1f) - max(a, j.toFloat())).coerceAtLeast(0f)
                sum += unit(source[j.coerceIn(source.indices)]) * overlap
            }
            measured[i] = (sum / width).coerceIn(0f, 1f)
        }
    }

    private fun expose() {
        for (i in bands.indices) bands[i] = (smoothBands[i] * response).coerceIn(0f, 1f)
        level = (baseLevel * response).coerceIn(0f, 1f)
        bass = (baseBass * response).coerceIn(0f, 1f)
        body = (baseBody * response).coerceIn(0f, 1f)
        air = (baseAir * response).coerceIn(0f, 1f)
        pressure = (basePressure * response).coerceIn(0f, 1f)
    }

    private fun clear() {
        bands.fill(0f); history.fill(0f); measured.fill(0f); smoothBands.fill(0f)
        lowAccent = 0f; bodyAccent = 0f; highAccent = 0f
        baseLevel = 0f; baseBass = 0f; baseBody = 0f; baseAir = 0f; basePressure = 0f
        level = 0f; bass = 0f; body = 0f; air = 0f; pressure = 0f
        travel = 0f; turn = 0f; hue = 0.04f; residence = 0f; hitBudget = 0f
        initialized = false; lastPts = Long.MIN_VALUE; previousHistoryPts = Long.MIN_VALUE
        nextHistoryTick = Long.MIN_VALUE; previousHistory = 0f
        sequences.fill(-1L); rawTimes.fill(Long.MIN_VALUE); previousScalar.fill(0f)
        recentTimes.fill(Long.MIN_VALUE); recentKinds.fill(0)
        recentApplied.forEach { it.fill(0f) }; recentAt = 0
        composition = if (mode == 0) 0 else MODE_TARGET[mode]
        RECIPES[composition].copyInto(weights)
        transitions = 0; acceptedHits = 0L; historySamples = 0L
        gestures.reset(); step.reset()
    }

    fun reset() {
        mode = 0; clear(); generation = null; revision = -1L; lastReset = null; response = 1f
    }

    private companion object {
        val MODE_TARGET = intArrayOf(0, 0, 1, 3, 4, 2)
        val RECIPES = arrayOf(
            floatArrayOf(0.90f, 0f, 0.82f, 0.24f, 0.10f),
            floatArrayOf(0.20f, 0.95f, 0.36f, 0.18f, 0f),
            floatArrayOf(0.15f, 0.10f, 0.95f, 0.28f, 0f),
            floatArrayOf(0.15f, 0.18f, 0.24f, 0.95f, 0.08f),
            floatArrayOf(0.12f, 0.25f, 0.24f, 0.10f, 0.90f),
        )
        fun unit(value: Float): Float = finite(value, 0f, 1f)
        fun finite(value: Float, low: Float, high: Float): Float = if (value.isFinite()) value.coerceIn(low, high) else low
        fun follow(from: Float, to: Float, dt: Float): Float = approach(from, to, dt, if (to > from) 0.035f else 0.16f)
        fun approach(from: Float, to: Float, dt: Float, seconds: Float): Float = from + (to - from) * (1f - exp(-dt / seconds))
        fun historyValue(frame: SpectrumFrame): Float = 0.4f * unit(frame.level) + 0.6f * unit(frame.mid)
        fun pressureOf(frame: SpectrumFrame): Float = (unit(frame.density) * 0.38f +
            unit(frame.flatness) * 0.22f + unit(frame.energy) * 0.24f + unit(frame.novelty / 4f) * 0.16f).coerceIn(0f, 1f)
    }
}
