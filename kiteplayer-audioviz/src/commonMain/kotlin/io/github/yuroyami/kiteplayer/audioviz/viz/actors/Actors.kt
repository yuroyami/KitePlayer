package io.github.yuroyami.kiteplayer.audioviz.viz.actors

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** How a traveller gets from one place to another. */
internal enum class PathShape { Line, Arc, Wave }

/**
 * A pool of things that cross the screen. Each leaves one place and arrives at another over a set
 * time, on a line, an arc or a wave. Positions are shares of the screen.
 */
internal class Travellers(val capacity: Int) {
    val fromX = FloatArray(capacity)
    val fromY = FloatArray(capacity)
    val toX = FloatArray(capacity)
    val toY = FloatArray(capacity)
    /** How far along, 0 to 1. */
    val progress = FloatArray(capacity)
    val seconds = FloatArray(capacity)
    val bend = FloatArray(capacity)
    val shape = IntArray(capacity)
    /** Size as a share of the shorter side of the screen. */
    val size = FloatArray(capacity)
    val tint = FloatArray(capacity)
    val angle = FloatArray(capacity)
    val spin = FloatArray(capacity)
    val kind = IntArray(capacity)
    val alive = BooleanArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)

    /** The slot launched most recently, or -1. */
    var newest: Int = -1
        private set
    private var next = 0

    fun spawn(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        seconds: Float,
        shape: PathShape = PathShape.Line,
        bend: Float = 0f,
        size: Float = 0.02f,
        tint: Float = 0f,
        spin: Float = 0f,
        kind: Int = 0,
    ): Int {
        var slot = -1
        for (offset in 0 until capacity) {
            val candidate = (next + offset) % capacity
            if (!alive[candidate]) {
                slot = candidate
                break
            }
        }
        if (slot < 0) slot = next
        next = (slot + 1) % capacity
        this.fromX[slot] = fromX
        this.fromY[slot] = fromY
        this.toX[slot] = toX
        this.toY[slot] = toY
        this.seconds[slot] = seconds.coerceAtLeast(0.05f)
        this.shape[slot] = shape.ordinal
        this.bend[slot] = bend
        this.size[slot] = size
        this.tint[slot] = tint
        this.spin[slot] = spin
        this.kind[slot] = kind
        angle[slot] = 0f
        progress[slot] = 0f
        alive[slot] = true
        x[slot] = fromX
        y[slot] = fromY
        newest = slot
        return slot
    }

    /** Launches one from just outside a random edge to just outside the opposite one. */
    fun across(
        random: Rng,
        seconds: Float,
        shape: PathShape = PathShape.Line,
        bend: Float = 0f,
        size: Float = 0.02f,
        tint: Float = 0f,
        spin: Float = 0f,
        kind: Int = 0,
    ): Int {
        val side = (random.next() * 4f).toInt().coerceIn(0, 3)
        val along = 0.1f + 0.8f * random.next()
        val landing = 0.1f + 0.8f * random.next()
        return when (side) {
            0 -> spawn(-0.08f, along, 1.08f, landing, seconds, shape, bend, size, tint, spin, kind)
            1 -> spawn(1.08f, along, -0.08f, landing, seconds, shape, bend, size, tint, spin, kind)
            2 -> spawn(along, -0.08f, landing, 1.08f, seconds, shape, bend, size, tint, spin, kind)
            else -> spawn(along, 1.08f, landing, -0.08f, seconds, shape, bend, size, tint, spin, kind)
        }
    }

    /** Sends one outward from [x], [y] to beyond the nearest point on the edge in the direction [angle]. */
    fun outward(x: Float, y: Float, angle: Float, seconds: Float, size: Float, tint: Float, kind: Int = 0, spin: Float = 0f): Int =
        spawn(x, y, x + cos(angle) * 1.3f, y + sin(angle) * 1.3f, seconds, PathShape.Line, 0f, size, tint, spin, kind)

    fun advance(deltaSeconds: Float) {
        for (slot in 0 until capacity) {
            if (!alive[slot]) continue
            progress[slot] += deltaSeconds / seconds[slot]
            angle[slot] += spin[slot] * deltaSeconds
            if (progress[slot] >= 1f) {
                alive[slot] = false
                continue
            }
            place(slot)
        }
    }

    private fun place(slot: Int) {
        val t = progress[slot]
        val dx = toX[slot] - fromX[slot]
        val dy = toY[slot] - fromY[slot]
        var px = fromX[slot] + dx * t
        var py = fromY[slot] + dy * t
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val offset = when (shape[slot]) {
            PathShape.Arc.ordinal -> bend[slot] * sin(PI.toFloat() * t)
            PathShape.Wave.ordinal -> bend[slot] * sin(TAU * 2f * t)
            else -> 0f
        }
        px += -dy / length * offset
        py += dx / length * offset
        x[slot] = px
        y[slot] = py
    }

    /** True while the slot [newest] points at is still travelling. */
    val anyNewest: Boolean get() = newest >= 0 && alive[newest]

    fun clear() {
        alive.fill(false)
        newest = -1
        next = 0
    }
}

/** A band that crosses the screen once per bar, or [perBar] times a bar. [position] runs 0 to 1. */
internal class Sweep(var perBar: Float = 1f, var backwards: Boolean = false) {
    var position: Float = 0f
        private set

    fun advance(gestures: Gestures) {
        val along = gestures.barPhase * perBar
        val wrapped = along - floor(along)
        position = if (backwards) 1f - wrapped else wrapped
    }
}

/** A body on a wide ellipse, [lapsPerBar] laps a bar, faster when the music pushes. */
internal class Orbiter(
    var centreX: Float = 0.5f,
    var centreY: Float = 0.5f,
    var radiusX: Float = 0.35f,
    var radiusY: Float = 0.3f,
    var lapsPerBar: Float = 0.5f,
    phase: Float = 0f,
) {
    private val startPhase = phase
    var angle: Float = phase * TAU
        private set
    var x: Float = centreX
        private set
    var y: Float = centreY
        private set
    var direction: Float = 1f

    fun advance(state: VizRenderState, gestures: Gestures) {
        angle += direction * state.deltaSeconds * lapsPerBar * TAU / gestures.barSeconds * (0.6f + 0.6f * state.drive)
        x = centreX + radiusX * cos(angle)
        y = centreY + radiusY * sin(angle)
    }

    fun reset() {
        angle = startPhase * TAU
        x = centreX
        y = centreY
        direction = 1f
    }
}

/**
 * A flock: each member keeps its distance from the others, matches their speed, and is pulled
 * towards a target that moves. Positions are shares of the screen.
 */
internal class Swarm(val count: Int, seed: Long) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    var targetX: Float = 0.5f
    var targetY: Float = 0.5f
    private val random = Rng(seed)

    init {
        scatter()
    }

    fun scatter() {
        random.reset()
        for (index in 0 until count) {
            x[index] = random.next()
            y[index] = random.next()
            vx[index] = random.signed() * 0.1f
            vy[index] = random.signed() * 0.1f
        }
    }

    /** Moves the flock on. [speed] is the top speed in screens a second. */
    fun advance(deltaSeconds: Float, speed: Float, pull: Float = 1f) {
        val dt = deltaSeconds.coerceIn(0f, 0.05f)
        val near = 0.08f
        for (index in 0 until count) {
            var awayX = 0f
            var awayY = 0f
            var matchX = 0f
            var matchY = 0f
            var neighbours = 0
            for (other in 0 until count) {
                if (other == index) continue
                val dx = x[index] - x[other]
                val dy = y[index] - y[other]
                val distance = dx * dx + dy * dy
                if (distance < near * near) {
                    val weight = 1f / (distance + 1e-4f)
                    awayX += dx * weight * 0.0004f
                    awayY += dy * weight * 0.0004f
                    matchX += vx[other]
                    matchY += vy[other]
                    neighbours++
                }
            }
            var ax = (targetX - x[index]) * 1.2f * pull + awayX
            var ay = (targetY - y[index]) * 1.2f * pull + awayY
            if (neighbours > 0) {
                ax += (matchX / neighbours - vx[index]) * 0.8f
                ay += (matchY / neighbours - vy[index]) * 0.8f
            }
            ax += random.signed() * 0.6f
            ay += random.signed() * 0.6f
            vx[index] += ax * dt
            vy[index] += ay * dt
            val fast = sqrt(vx[index] * vx[index] + vy[index] * vy[index])
            if (fast > speed) {
                vx[index] *= speed / fast
                vy[index] *= speed / fast
            }
            x[index] += vx[index] * dt
            y[index] += vy[index] * dt
        }
    }
}

/** A conveyor: [offset] runs through 0 to 1 [perBar] times a bar, so a repeated shape moves with the tempo. */
internal class Lane(var perBar: Float = 1f, var direction: Float = 1f) {
    var offset: Float = 0f
        private set
    private var travelled = 0f

    fun advance(state: VizRenderState, gestures: Gestures) {
        travelled += direction * state.deltaSeconds * perBar / gestures.barSeconds * (0.5f + 0.7f * state.drive)
        offset = travelled - floor(travelled)
    }

    fun reset() {
        travelled = 0f
        offset = 0f
    }
}
