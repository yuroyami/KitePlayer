package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The broken broadcast of [Glitch] as numbers: the spectrogram's blocks and how much light each gives,
 * the trace, and which faults break the picture at this instant.
 *
 * The blocks are a grid of [columns] by [rows]. Across is time, scrolling one column per
 * [SPAN_SECONDS] / [columns] heard seconds, the newest column on the right. Up is band, bass in row 0
 * at the bottom.
 *
 * Nothing here draws, so the rules of the picture can be checked without a canvas. [GlitchGeometry]
 * turns it into shapes and [GlitchFields] into the frozen frame of a datamosh and the static.
 */
internal class GlitchScene {

    /** How many columns and rows of blocks the screen holds, from the last [layout]. */
    var columns: Int = COLUMNS_MOST
        private set
    var rows: Int = ROWS_MOST
        private set

    /** Each block's level, 0 to 1, by column and row. A column keeps the bands of its moment. */
    private val level = FloatArray(COLUMNS_MOST * ROWS_MOST)

    /** How much light each block gives, in linear light: [IDLE_LIGHT] in silence, 1 at the loudest. */
    val light = FloatArray(COLUMNS_MOST * ROWS_MOST) { IDLE_LIGHT }

    /** How far each block has gone to white, 0 to 1. Only a loud band of loud music gets there. */
    val white = FloatArray(COLUMNS_MOST * ROWS_MOST)

    /** The light of the block in [column] and [row], after [present]. */
    fun blockLight(column: Int, row: Int): Float = light[column * ROWS_MOST + row]

    /** The level the block in [column] and [row] shows, 0 to 1, after coarse blocks are merged. */
    fun blockLevel(column: Int, row: Int): Float = shown[column * ROWS_MOST + row]
    private val shown = FloatArray(COLUMNS_MOST * ROWS_MOST)

    /** The mean level of [column], for the speed of a datamosh column. */
    fun columnLevel(column: Int): Float {
        var sum = 0f
        for (row in 0 until rows) sum += level[column * ROWS_MOST + row]
        return sum / rows
    }

    /** How far the whole picture is rolled down, as a share of the height. It wraps round. */
    var trackingY: Float = 0f
        private set

    /** The waveform across the width, 1 being full scale. */
    val trace = FloatArray(TRACE_POINTS)

    /** How much light the trace gives, in linear light. */
    var traceExposure: Float = TRACE_IDLE
        private set

    /** How many channel changes there have been. It picks the colour set. */
    var channel: Int = 0
        private set

    /** The middle of the scan bar, as a share of the height. */
    var scanAt: Float = -SCAN_HALF
        private set

    /** How much the scan bar lifts the bars, 0 in silence. */
    var scanStrength: Float = 0f
        private set

    /** How far the colour layers stand apart, as a share of the width. */
    var split: Float = 0f
        private set

    /** How many slices are torn sideways now, and where: tops and heights in shares of the height. */
    var slices: Int = 0
        private set
    val sliceTop = FloatArray(MOST_SLICES)
    val sliceHeight = FloatArray(MOST_SLICES)

    /** How far each slice is torn, as a share of the width. */
    val sliceShift = FloatArray(MOST_SLICES)

    /** How far each bar's melt has run, 0 to 1, or -1 for a bar that is not melting. */
    val melt = FloatArray(BARS) { -1f }

    /** A number per melting bar, so its streaks keep their places while it melts. */
    val meltSeed = IntArray(BARS)

    /** How far a melt's streaks may fall: 1 normally, small under reduced motion. */
    var fall: Float = 1f
        private set

    /** Whether the fine static of a channel change covers the picture. */
    val staticOn: Boolean get() = staticLeft > EPSILON

    /** Where the static's noise sits this frame, as shares of its tile. */
    var staticX: Float = 0f
        private set
    var staticY: Float = 0f
        private set

    /** How far the picture has collapsed into the line of a breakdown, 0 to 1. */
    var collapse: Float = 0f
        private set

    /** True from a breakdown until the next section. */
    var breakdown: Boolean = false
        private set

    /** True while a datamosh holds the picture. */
    var moshing: Boolean = false
        private set

    /** How far a datamosh block may move: 1 normally, near 0 under reduced motion. */
    var moshMotion: Float = 1f
        private set

    /** Seconds of one visual cycle, a bar when the pulse is known. */
    var cycleSeconds: Float = 2f
        private set

    /** How loud each band has been lately. The datamosh reads it for its column speeds. */
    val glow = FloatArray(BARS)

    private val scope = FloatArray(TRACE_POINTS)
    private val resampler = WaveformResampler()
    private var splitFrom = 0f
    private var splitAge = SPLIT_SECONDS
    private var sliceAge = TEAR_GAP
    private var sliceSeconds = 0f
    private var staticLeft = 0f
    private var collapseAge = 0f
    private var scanPhase = 0f
    private var wrapped = false
    private var moshAge = 0f
    private var moshPending = 0f
    private var moshStarting = false
    private var refreshCredit = 1f
    private var columnClock = 0f
    private var owedSeconds = 0f
    private val presence = Envelope(PRESENCE_RISE, PRESENCE_FALL)

    /**
     * Fits the grid to a screen of [width] by [height] pixels: as many blocks as fit at [BLOCK_PX] or more on
     * each side, up to [COLUMNS_MOST] by [ROWS_MOST], with a clear band of [TRACE_BAND] of the height for the
     * ribbon. The rows are even, so the band sits at the middle with as many rows above it as below. A new
     * size starts the grid again from the idle light.
     */
    fun layout(width: Float, height: Float) {
        val across = min(COLUMNS_MOST, (width / BLOCK_PX).toInt()).coerceAtLeast(1)
        val up = (min(ROWS_MOST, (height * (1f - TRACE_BAND) / BLOCK_PX).toInt()) / 2 * 2).coerceAtLeast(2)
        if (across == columns && up == rows) return
        columns = across
        rows = up
        level.fill(0f)
        shown.fill(0f)
        light.fill(IDLE_LIGHT)
        white.fill(0f)
    }

    /** Moves the picture on by one frame. [gestures] must already have read this frame. */
    fun advance(state: VizRenderState, gestures: Gestures, random: Rng) {
        val dt = state.deltaSeconds.within(0f, MOST_STEP)
        val step = state.stepSeconds.within(0f, MOST_STEP)
        val motion = state.motionScale.within(0f, 1f)
        cycleSeconds = gestures.cycleSeconds.within(0.5f, 8f)
        val bands = state.frame.bands
        // A paused player repeats its last frame, and the picture holds what it showed.
        val listen = if (state.frame.held) 0f else dt
        for (bar in 0 until BARS) {
            val value = if (bands.isEmpty()) 0f else bands.sampleAt(bar / (BARS - 1f)).within(0f, 1f)
            glow[bar] = follow(glow[bar], value, listen, GLOW_RISE, GLOW_FALL)
        }
        owedSeconds += step
        roll(state, gestures, step, dt)
        track(state, gestures, step)
        breakOnHits(gestures, random, dt, motion)
        changeChannel(state, gestures, random, dt, motion)
        mosh(gestures, step, motion)
        if (refreshed(state, dt)) present(state)
    }

    /** True once when a datamosh has started and its first frame still has to be frozen. */
    fun takeMoshStart(): Boolean {
        val starting = moshStarting
        moshStarting = false
        return starting
    }

    /** The audible seconds the datamosh has moved since this was last asked. */
    fun takeMoshSeconds(): Float {
        val seconds = moshPending
        moshPending = 0f
        return seconds
    }

    fun reset() {
        glow.fill(0f)
        level.fill(0f)
        light.fill(IDLE_LIGHT)
        white.fill(0f)
        trace.fill(0f)
        melt.fill(-1f)
        meltSeed.fill(0)
        columnClock = 0f
        owedSeconds = 0f
        presence.reset()
        shown.fill(0f)
        trackingY = 0f
        traceExposure = TRACE_IDLE
        channel = 0
        scanAt = -SCAN_HALF
        scanStrength = 0f
        split = 0f
        splitFrom = 0f
        splitAge = SPLIT_SECONDS
        slices = 0
        sliceAge = TEAR_GAP
        sliceSeconds = 0f
        fall = 1f
        staticLeft = 0f
        staticX = 0f
        staticY = 0f
        collapse = 0f
        collapseAge = 0f
        breakdown = false
        scanPhase = 0f
        wrapped = false
        moshing = false
        moshMotion = 1f
        moshAge = 0f
        moshPending = 0f
        moshStarting = false
        cycleSeconds = 2f
        refreshCredit = 1f
    }

    /**
     * The scan bar's own clock: it moves by audible time, so a pause or a silence holds it, and it
     * leans into the pulse when one is known. Its wrap is the first beat a datamosh ends on.
     */
    private fun roll(state: VizRenderState, gestures: Gestures, step: Float, dt: Float) {
        val before = scanPhase
        var phase = scanPhase + step / cycleSeconds
        if (gestures.pulseUsable && step > 0f) {
            var error = gestures.cyclePhase - (phase - floor(phase))
            if (error > 0.5f) error -= 1f
            if (error < -0.5f) error += 1f
            phase += error * (1f - exp(-step / SCAN_PULL_SECONDS))
        }
        phase -= floor(phase)
        wrapped = phase < before - 0.5f
        scanPhase = phase
        scanAt = -SCAN_HALF + phase * (1f + 2f * SCAN_HALF)
        // Presence keeps its level while paused, so a paused picture keeps its scan bar.
        scanStrength = follow(scanStrength, state.presence, dt, SCAN_RISE, SCAN_FALL)
    }

    /** The tracking roll: a slow offset of the whole picture on the cycle, there only while the music is heard. */
    private fun track(state: VizRenderState, gestures: Gestures, step: Float) {
        presence.advance(state.frame.audible, step)
        trackingY = TRACK_REACH * sin(gestures.cyclePhase * TAU) * presence.value * state.motionScale.within(0f, 1f)
    }

    /** A kick splits the colour layers, a snare tears slices sideways, a hat melts the brightest bars. */
    private fun breakOnHits(gestures: Gestures, random: Rng, dt: Float, motion: Float) {
        splitAge += dt
        if (gestures.kicks > 0) {
            val wanted = SPLIT_MOST * (0.3f + 0.7f * gestures.kick.within(0f, 1f)) * motion
            if (wanted >= split) {
                splitFrom = wanted
                splitAge = 0f
            }
        }
        val left = 1f - splitAge / SPLIT_SECONDS
        split = if (left > 0f) splitFrom * left * left else 0f

        sliceAge += dt
        if (gestures.snares > 0 && sliceAge >= TEAR_GAP) {
            val hit = gestures.snare.within(0f, 1f)
            slices = (3 + (5f * hit).roundToInt()).coerceIn(3, MOST_SLICES)
            sliceSeconds = (2 + (2f * hit).roundToInt()) / 60f
            sliceAge = 0f
            for (slice in 0 until slices) {
                sliceHeight[slice] = 0.02f + 0.09f * random.next()
                sliceTop[slice] = random.next() * (1f - sliceHeight[slice])
                val reach = (0.03f + 0.12f * hit * (0.4f + 0.6f * random.next())) * motion
                sliceShift[slice] = if (random.next() < 0.5f) -reach else reach
            }
        }
        if (slices > 0 && sliceAge >= sliceSeconds - EPSILON) slices = 0

        fall = motion
        for (bar in 0 until BARS) {
            if (melt[bar] < 0f) continue
            melt[bar] += dt / MELT_SECONDS
            if (melt[bar] >= 1f) melt[bar] = -1f
        }
        if (gestures.hats > 0) {
            // The brightest bars above a fixed level, so a quiet passage melts fewer of them.
            val count = (1.5f + 3f * gestures.hatAccent).roundToInt().coerceIn(1, MOST_MELTS)
            for (pick in 0 until count) {
                var best = -1
                for (bar in 0 until BARS) {
                    if (melt[bar] >= 0f || glow[bar] < MELT_LEVEL) continue
                    if (best < 0 || glow[bar] > glow[best]) best = bar
                }
                if (best < 0) break
                melt[best] = 0f
                meltSeed[best] = (random.next() * 65_535f).toInt()
            }
        }
    }

    /**
     * A breakdown collapses the picture into a line until the next section. Any other section is a
     * channel change: three sixtieths of a second of static, and the next colour set. A section that
     * lands with a drop changes the colours without the static, because the datamosh is the moment.
     */
    private fun changeChannel(state: VizRenderState, gestures: Gestures, random: Rng, dt: Float, motion: Float) {
        staticLeft -= dt
        if (gestures.breakdown) {
            breakdown = true
            collapseAge = 0f
        } else if (gestures.turn && state.frame.audible > 0f) {
            breakdown = false
            channel++
            // Under reduced motion the colours change without the static, which is a rapid cut.
            if (!gestures.surge && !moshing && motion >= 1f) staticLeft = STATIC_SECONDS
        }
        if (staticOn) {
            staticX = random.next()
            staticY = random.next()
        }
        // The frame of the breakdown already counts, and the picture folds fast then settles, like
        // an old set switching off.
        collapseAge += dt
        val left = 1f - (collapseAge / COLLAPSE_SECONDS).within(0f, 1f)
        collapse = if (breakdown) 1f - left * left * left else 0f
    }

    /**
     * A drop freezes the frame and its blocks slide down their columns until the first beat after at
     * least half a cycle. It moves by audible time, so a pause holds it and a silence ends it.
     */
    private fun mosh(gestures: Gestures, step: Float, motion: Float) {
        moshMotion = motion * motion
        if (gestures.surge && !moshing) {
            moshing = true
            moshStarting = true
            moshAge = 0f
            moshPending = 0f
            return
        }
        if (!moshing) return
        moshAge += step
        moshPending += step
        val ended = (wrapped && moshAge >= MOSH_LEAST * cycleSeconds) || moshAge >= MOSH_MOST * cycleSeconds
        if (ended || gestures.silence) {
            moshing = false
            moshStarting = false
            moshPending = 0f
        }
    }

    /**
     * Whether the picture takes this frame's music. It always does, except in the second before a
     * drop that the queue already holds: there it refreshes less and less often, the way a stream
     * stalls, until the drop freezes it.
     */
    private fun refreshed(state: VizRenderState, dt: Float): Boolean {
        val until = state.future?.nextEvent(AudioEventKind.Drop)?.secondsUntil ?: -1f
        if (moshing || until < 0f || until > BUILD_SECONDS) {
            refreshCredit = 1f
            return true
        }
        val near = 1f - until / BUILD_SECONDS
        refreshCredit += dt * (STUTTER_FROM + (STUTTER_TO - STUTTER_FROM) * near)
        if (refreshCredit < 1f) return false
        refreshCredit = (refreshCredit - 1f).coerceAtMost(1f)
        return true
    }

    /** Scrolls the grid, writes the newest column, lights every block, and reads the trace. */
    private fun present(state: VizRenderState) {
        // One column a step of heard time; a stall owes its seconds until the picture takes a frame.
        columnClock += owedSeconds * columns / SPAN_SECONDS
        owedSeconds = 0f
        while (columnClock >= 1f) {
            columnClock -= 1f
            level.copyInto(level, 0, ROWS_MOST, columns * ROWS_MOST)
        }
        // The newest column is the bands now, bass in row 0: each row is the mean of its share of the bars.
        val newest = (columns - 1) * ROWS_MOST
        for (row in 0 until rows) {
            val from = row * BARS / rows
            val to = ((row + 1) * BARS / rows).coerceAtLeast(from + 1)
            var sum = 0f
            for (bar in from until to) sum += glow[bar]
            level[newest + row] = sum / (to - from)
        }
        // Dense music coarsens the blocks: pairs of columns, then fours, from the newest, show their mean.
        val coarse = ((state.frame.density - COARSE_FROM) * COARSE_SCALE).within(0f, 1f)
        val merge = if (coarse > COARSE_FOURS) 4 else if (coarse > COARSE_PAIRS) 2 else 1
        for (row in 0 until rows) {
            var end = columns
            while (end > 0) {
                val start = (end - merge).coerceAtLeast(0)
                var sum = 0f
                for (column in start until end) sum += level[column * ROWS_MOST + row]
                val mean = sum / (end - start)
                for (column in start until end) shown[column * ROWS_MOST + row] = mean
                end = start
            }
        }
        // The guard's scale reaches the idle light too, and the light follows the level of the music.
        val scale = state.lightScale.within(0f, 1f)
        val loud = lightFor(state.frame.energy.within(0f, 1f))
        val lift = state.lift.within(0f, 1f)
        for (column in 0 until columns) for (row in 0 until rows) {
            val at = column * ROWS_MOST + row
            light[at] = scale * (IDLE_LIGHT + (1f - IDLE_LIGHT) * loud * response(shown[at]))
            // White needs a band that is high in any song, not merely the loudest of a quiet one.
            val peak = ((shown[at] - WHITE_FROM) / (WHITE_TO - WHITE_FROM)).within(0f, 1f)
            white[at] = lift * peak * peak * (3f - 2f * peak)
        }
        // Neighbouring blocks never differ in light by more than half, from the bottom left.
        for (column in 0 until columns) for (row in 0 until rows) {
            val at = column * ROWS_MOST + row
            var value = light[at]
            if (column > 0) {
                val left = light[at - ROWS_MOST]
                value = value.coerceIn(left - MOST_STEP_LIGHT, left + MOST_STEP_LIGHT)
            }
            if (row > 0) {
                val below = light[at - 1]
                value = value.coerceIn(below - MOST_STEP_LIGHT, below + MOST_STEP_LIGHT)
            }
            light[at] = value
        }
        traceExposure = scale * (TRACE_IDLE + (1f - TRACE_IDLE) * loud)
        val frame = state.frame
        resampler.resample(frame.scope, scope)
        val gain = frame.waveformGain.within(0f, MOST_GAIN)
        // A soft limit, so a loud wave rounds off instead of showing flat tops.
        for (point in trace.indices) trace[point] = TRACE_CLIP * tanh((scope[point] * gain).within(-8f, 8f) / TRACE_CLIP)
    }

    internal companion object {
        /** The bands are read at this many points across the spectrum, then shared out among the rows. */
        const val BARS = 64

        /** The most columns and rows of blocks, the least size of a block in pixels, and the heard seconds across. */
        const val COLUMNS_MOST = 64
        const val ROWS_MOST = 16
        const val BLOCK_PX = 24f
        const val SPAN_SECONDS = 4f

        /** The clear band for the ribbon across the middle, as a share of the height. */
        const val TRACE_BAND = 0.12f

        /** The most two neighbouring blocks may differ in light. */
        const val MOST_STEP_LIGHT = 0.5f

        /** The tracking roll's reach, as a share of the height, and how fast it comes and goes with the music. */
        const val TRACK_REACH = 0.02f
        const val PRESENCE_RISE = 2f
        const val PRESENCE_FALL = 0.5f

        /** Density above [COARSE_FROM] coarsens the blocks: past [COARSE_PAIRS] in pairs, past [COARSE_FOURS] in fours. */
        const val COARSE_FROM = 0.6f
        const val COARSE_SCALE = 2.5f
        const val COARSE_PAIRS = 0.5f
        const val COARSE_FOURS = 0.9f
        const val TRACE_POINTS = 128
        const val MOST_SLICES = 8

        /**
         * The least time from one tear to the next. A backbeat snare always tears, but a body hit on
         * every eighth note would keep the picture torn, and between faults it has to be clean.
         */
        const val TEAR_GAP = 0.25f

        /** How much light the blocks give in silence and in the first frame, in linear light. */
        const val IDLE_LIGHT = 0.3f

        /** How much light the trace gives in silence. */
        const val TRACE_IDLE = 0.42f

        /** A band at this height or above answers in full. *Judgement.* */
        const val FULL_LEVEL = 0.5f

        /** Light follows the bands this fast up and this slow down, so a kick pumps no flash train. */
        const val GLOW_RISE = 0.05f
        const val GLOW_FALL = 0.4f

        /**
         * A band this high starts to go white and one at [WHITE_TO] is white. Few bands reach these
         * heights under the shared gain, so only the peaks of loud music turn white. *Judgement.*
         */
        const val WHITE_FROM = 0.55f
        const val WHITE_TO = 0.85f

        /**
         * The widest split of the colour layers, as a share of the width, and how long it lasts. It
         * is about a third of a loud bar, so the fringes sit on the edges and the middle keeps its colour.
         */
        const val SPLIT_MOST = 0.007f
        const val SPLIT_SECONDS = 0.12f

        /**
         * How long a melt runs, the level a bar needs to melt, and how many one hit may melt. A melt
         * is shorter than the gap between off-beat hats, so the bars come back clean between them.
         */
        const val MELT_SECONDS = 0.18f
        const val MELT_LEVEL = 0.16f
        const val MOST_MELTS = 6

        /** Three frames at sixty a second. */
        const val STATIC_SECONDS = 3f / 60f

        /** How long the picture takes to collapse into the line of a breakdown. */
        const val COLLAPSE_SECONDS = 0.28f

        /** Half the scan bar's height, as a share of the screen, and how fast it appears and goes. */
        const val SCAN_HALF = 0.07f
        const val SCAN_RISE = 0.3f
        const val SCAN_FALL = 0.6f
        const val SCAN_PULL_SECONDS = 0.5f

        /** A datamosh lasts at least half a cycle and at most two. */
        const val MOSH_LEAST = 0.5f
        const val MOSH_MOST = 2f

        /** How early a queued drop starts the stall, and how often the picture refreshes during it. */
        const val BUILD_SECONDS = 1f
        const val STUTTER_FROM = 20f
        const val STUTTER_TO = 5f

        const val TRACE_CLIP = 1.4f
        const val MOST_GAIN = 16f
        const val MOST_STEP = 0.25f
        const val EPSILON = 0.0005f

        /** How strongly a band at [level] answers, 0 to 1. */
        fun response(level: Float): Float = (level / FULL_LEVEL).within(0f, 1f).pow(0.6f)

        private fun follow(from: Float, to: Float, dt: Float, rise: Float, fall: Float): Float =
            from + (to - from) * (1f - exp(-dt / if (to > from) rise else fall))

        private fun Float.within(low: Float, high: Float): Float = if (isFinite()) coerceIn(low, high) else low
    }
}
