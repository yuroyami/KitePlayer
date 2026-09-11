package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.ChoiceGene
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers

/** How many of something a choice gene asks for: [base] plus [step] for each option. */
internal fun ChoiceGene.count(base: Int, step: Int = 1): Int = base + step * value

/** The larger of the old and new count, so the items fading out are still drawn. */
internal fun ChoiceGene.drawn(base: Int, step: Int = 1): Int = base + step * maxOf(value, previous)

/**
 * How much of item [index] to draw when a choice gene sets how many there are: all of it while it is
 * in both the old and the new count, fading in or out over the bar a change takes.
 */
internal fun ChoiceGene.presence(index: Int, base: Int, step: Int = 1): Float {
    val now = base + step * value
    val before = base + step * previous
    return when {
        index < minOf(now, before) -> 1f
        index < now -> mix
        index < before -> 1f - mix
        else -> 0f
    }
}

/** Points anchor [index] at the newest live traveller, and leaves it where it was otherwise. */
internal fun Kit.follow(index: Int, travellers: Travellers) {
    val newest = travellers.newest
    if (newest >= 0 && travellers.alive[newest]) {
        place(index, travellers.x[newest], travellers.y[newest])
    } else if (anchors.size <= index) {
        place(index, 0.5f, 0.5f)
    }
}

/** A palette colour packed for a mesh. */
internal fun VizPalette.argb(position: Float, saturation: Float = 0.85f, value: Float = 1f, alpha: Float = 1f): Int =
    cycled(position, saturation, value, alpha.coerceIn(0f, 1f)).toArgb()

/**
 * The last few seconds of a row of values, such as the bands or a trace, one row a frame, so a
 * drawing can show where the music has been.
 */
internal class History(private val rows: Int = 200) {
    private var width = 0
    private var data = FloatArray(0)
    private val times = FloatArray(rows)
    private var newest = -1
    private var count = 0

    /** Stores [values] as the row for [time]. */
    fun push(values: FloatArray, time: Float) {
        if (values.isEmpty()) return
        if (values.size != width) {
            width = values.size
            data = FloatArray(rows * width)
            newest = -1
            count = 0
        }
        newest = (newest + 1) % rows
        values.copyInto(data, newest * width)
        times[newest] = time
        if (count < rows) count++
    }

    /** The row stored about [ago] seconds back, or the oldest one kept. -1 while empty. */
    fun row(ago: Float): Int {
        if (count == 0) return -1
        val target = times[newest] - ago
        var row = newest
        for (step in 1 until count) {
            val older = (row - 1 + rows) % rows
            if (times[older] < target) break
            row = older
        }
        return row
    }

    /** The value at [position], 0 to 1 along [row], blending neighbours. */
    fun sample(row: Int, position: Float): Float {
        if (row < 0 || width == 0) return 0f
        val at = position.coerceIn(0f, 1f) * (width - 1)
        val index = at.toInt().coerceAtMost(width - 1)
        val next = (index + 1).coerceAtMost(width - 1)
        val share = at - index
        return data[row * width + index] * (1f - share) + data[row * width + next] * share
    }

    fun clear() {
        newest = -1
        count = 0
    }
}

/** Shrinks this into [into] by taking evenly spaced samples. */
internal fun FloatArray.squeezeInto(into: FloatArray) {
    if (isEmpty()) {
        into.fill(0f)
        return
    }
    for (index in into.indices) into[index] = this[(index.toLong() * size / into.size).toInt().coerceAtMost(size - 1)]
}

/** The fractional part, so a share of the screen wraps round to the other side. */
internal fun wrap(value: Float): Float = value - kotlin.math.floor(value)

/** Straight up, as an angle for a sprite burst. */
internal const val UP: Float = -1.5707964f

/** A bar from [foot] to [head] in pixels, coloured [low] at its foot and [high] at its head. */
internal fun TriangleMesh.bar(left: Float, right: Float, head: Float, foot: Float, high: Int, low: Int) {
    val a = vertex(left, head, high)
    val b = vertex(right, head, high)
    val c = vertex(right, foot, low)
    val d = vertex(left, foot, low)
    quad(a, b, c, d)
}

/** Onsets this strong are real drums. A pad's own texture fires onsets too, but weaker than this. */
private const val HIT = 0.5f

/** The kick, if it is strong enough to be a real drum, else 0. */
internal val Gestures.kickHit: Float get() = if (kick >= HIT) kick else 0f

/** The snare, if it is strong enough to be a real drum, else 0. */
internal val Gestures.snareHit: Float get() = if (snare >= HIT) snare else 0f

/** The hat, if it is strong enough to be a real drum, else 0. */
internal val Gestures.hatHit: Float get() = if (hat >= HIT) hat else 0f

/** How much light the main parts give: about a third under a calm pad, nearly all of it under drums. */
internal val VizRenderState.lift: Float get() = 0.25f + 0.75f * drive

/** A speed factor that keeps a calm passage moving, at about half the speed of a busy one. */
internal val VizRenderState.tempo: Float get() = 0.4f + 0.8f * frame.motionRate

/**
 * A slow path the whole composition follows, one lap about every [seconds], so the picture eight
 * seconds from now sits somewhere else on the screen.
 */
internal class Stage(private val seconds: Float = 16f, private val reachX: Float = 0.16f, private val reachY: Float = 0.12f, private val start: Float = 0f) {
    var x: Float = 0.5f
        private set
    var y: Float = 0.5f
        private set
    private var phase = start

    fun advance(deltaSeconds: Float) {
        phase += deltaSeconds / seconds * TAU
        x = 0.5f + reachX * kotlin.math.sin(phase)
        y = 0.5f + reachY * kotlin.math.cos(phase)
    }

    fun reset() {
        phase = start
        x = 0.5f
        y = 0.5f
    }
}

/**
 * A bright body that crosses the whole screen on an arc once a bar, under any music. Drawn in the
 * echo layer it leaves a tail. Its anchor gives a drawing a crossing when its own actors keep to one part.
 */
internal class Comets(capacity: Int = 3, private val kind: Int = Sprite.GLOW, private val size: Float = 0.035f) {
    val travellers: Travellers = Travellers(capacity)
    private val mesh = TriangleMesh(maxVertices = capacity * 12 + 8)

    fun advance(state: VizRenderState, gestures: Gestures, random: Rng) {
        // One a bar and never a moment without one in flight, each flying most of the way first.
        val flown = travellers.anyNewest && travellers.progress[travellers.newest] > 0.6f
        if (!travellers.anyNewest || (gestures.bar && flown)) {
            travellers.across(random, gestures.barSeconds * 0.85f, PathShape.Arc, 0.18f * random.signed(), size, random.next(), 3f, kind)
        }
        travellers.advance(state.deltaSeconds)
    }

    fun DrawScope.drawComets(palette: VizPalette, walk: Float, alpha: Float = 1f) {
        drawTravellers(travellers, mesh, palette, walk, alpha)
    }

    fun clear() = travellers.clear()
}

/** Rings that sweep out from where a strong drum landed, thick at first and thinning as they grow. */
internal class Shocks(private val capacity: Int = 8) {
    private val age = FloatArray(capacity)
    private val atX = FloatArray(capacity)
    private val atY = FloatArray(capacity)
    private val tint = FloatArray(capacity)
    private val strength = FloatArray(capacity)
    private var next = 0

    fun fire(x: Float, y: Float, colour: Float, hit: Float = 1f) {
        age[next] = 0.001f
        atX[next] = x
        atY[next] = y
        tint[next] = colour
        strength[next] = hit
        next = (next + 1) % capacity
    }

    /** Moves every ring on; each takes [seconds] to reach its full size. */
    fun advance(deltaSeconds: Float, seconds: Float) {
        for (slot in 0 until capacity) {
            if (age[slot] <= 0f) continue
            age[slot] += deltaSeconds / seconds.coerceAtLeast(0.05f)
            if (age[slot] >= 1f) age[slot] = 0f
        }
    }

    /** Every live ring, growing fast at first and slowing, out to [reach] pixels. */
    fun DrawScope.drawShocks(palette: VizPalette, walk: Float, reach: Float, width: Float, alpha: Float = 1f) {
        for (slot in 0 until capacity) {
            val t = age[slot]
            if (t <= 0f) continue
            val fade = 1f - t
            drawCircle(
                palette.cycled(tint[slot] + walk, value = 1f, alpha = (alpha * strength[slot] * fade).coerceIn(0f, 1f)),
                (reach * (1f - fade * fade)).coerceAtLeast(1f),
                Offset(atX[slot] * size.width, atY[slot] * size.height),
                style = Stroke((width * (0.3f + fade)).coerceAtLeast(1f)),
            )
        }
    }

    fun clear() {
        age.fill(0f)
        next = 0
    }
}
