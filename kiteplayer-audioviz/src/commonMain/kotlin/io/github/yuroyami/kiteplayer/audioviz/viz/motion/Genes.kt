package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import kotlin.math.abs

/** One part of a drawing's recipe the song can change: a count, a layout, a ground. */
public sealed class Gene(public val name: String) {
    internal abstract fun mutate(random: Rng)
    internal abstract fun toMost()
    internal abstract fun toLeast()
    internal abstract fun advance(deltaSeconds: Float, barSeconds: Float)
    internal abstract fun restart()
}

/** A number in the recipe. A change glides to the new value over one bar. */
public class NumberGene internal constructor(
    name: String,
    public val min: Float,
    public val max: Float,
    private val start: Float,
    private val mostAtMax: Boolean,
) : Gene(name) {
    public var value: Float = start
        private set

    /** Where it is heading. Setting it by hand glides there too. */
    public var target: Float = start
        set(wanted) {
            field = wanted.coerceIn(min, max)
            glide = abs(field - value)
        }

    private var glide = 0f

    /** [value] as a share of its range, 0 to 1. */
    public val fraction: Float get() = if (max > min) (value - min) / (max - min) else 0f

    override fun mutate(random: Rng) {
        val span = max - min
        var next = min + random.next() * span
        if (abs(next - value) < span * 0.3f) {
            next = if (value - min > span * 0.5f) min + random.next() * span * 0.35f else max - random.next() * span * 0.35f
        }
        target = next
    }

    override fun toMost() {
        target = if (mostAtMax) max else min
    }

    override fun toLeast() {
        target = if (mostAtMax) min else max
    }

    override fun advance(deltaSeconds: Float, barSeconds: Float) {
        val step = glide * deltaSeconds / barSeconds.coerceAtLeast(0.2f)
        value += (target - value).coerceIn(-step, step)
    }

    override fun restart() {
        value = start
        target = start
        glide = 0f
    }
}

/** A choice between [options] ways of drawing. A change fades from the old option over one bar. */
public class ChoiceGene internal constructor(
    name: String,
    public val options: Int,
    private val start: Int,
    private val most: Int,
    private val least: Int,
) : Gene(name) {
    public var value: Int = start
        private set
    public var previous: Int = start
        private set

    /** How far the change from [previous] to [value] has come, 0 to 1. */
    public var mix: Float = 1f
        private set

    /** How much of [option] to draw right now: 1 when settled on it, fading in or out during a change. */
    public fun weight(option: Int): Float = when (option) {
        value -> if (previous == value) 1f else mix
        previous -> 1f - mix
        else -> 0f
    }

    /** True for a two-way choice that is on. */
    public val on: Boolean get() = value == 1

    public fun choose(option: Int) {
        val wanted = option.coerceIn(0, options - 1)
        if (wanted == value) return
        previous = value
        value = wanted
        mix = 0f
    }

    override fun mutate(random: Rng) {
        if (options < 2) return
        var next = (random.next() * (options - 1)).toInt().coerceIn(0, options - 2)
        if (next >= value) next++
        choose(next)
    }

    override fun toMost() = choose(most)

    override fun toLeast() = choose(least)

    override fun advance(deltaSeconds: Float, barSeconds: Float) {
        if (mix < 1f) mix = (mix + deltaSeconds / barSeconds.coerceAtLeast(0.2f)).coerceAtMost(1f)
        if (mix >= 1f) previous = value
    }

    override fun restart() {
        value = start
        previous = start
        mix = 1f
    }
}

/**
 * A drawing's recipe, and the rules that change it.
 *
 * One or two genes change at the top of every phrase, three or more on every fourth phrase, all go to
 * their busiest end on a drop and their sparest on a breakdown. Seeded, so a run replays exactly.
 */
public class Genes(seed: Long) {
    public val all: List<Gene>
        field = ArrayList<Gene>()

    private val random = Rng(seed)
    private var phrasesSeen = 0

    /** How many single changes have happened, for tests and readouts. */
    public var changes: Int = 0
        private set

    /** A colour offset that walks one full turn every eight phrases. */
    public var walk: Float = 0f
        private set

    public fun number(name: String, min: Float, max: Float, start: Float, mostAtMax: Boolean = true): NumberGene =
        NumberGene(name, min, max, start.coerceIn(min, max), mostAtMax).also { all.add(it) }

    public fun choice(name: String, options: Int, start: Int = 0, most: Int = options - 1, least: Int = 0): ChoiceGene =
        ChoiceGene(name, options.coerceAtLeast(1), start, most, least).also { all.add(it) }

    public fun toggle(name: String, start: Boolean, most: Boolean = true): ChoiceGene =
        ChoiceGene(name, 2, if (start) 1 else 0, if (most) 1 else 0, if (most) 0 else 1).also { all.add(it) }

    /** Moves the recipe on by one frame, after [gestures] has read the same frame. */
    public fun advance(gestures: Gestures, deltaSeconds: Float) {
        when {
            gestures.drop -> {
                all.forEach { it.toMost() }
                changes += all.size
            }
            gestures.breakdown -> {
                all.forEach { it.toLeast() }
                changes += all.size
            }
            gestures.phrase -> {
                phrasesSeen++
                val big = phrasesSeen % 4 == 0
                mutate(if (big) maxOf(3, (all.size + 1) / 2) else 1 + (random.next() * 2f).toInt())
            }
        }
        for (gene in all) gene.advance(deltaSeconds, gestures.barSeconds)
        walk = (gestures.phrases + gestures.phrasePhase) / 8f
    }

    /** Changes [count] genes at once, for a button that says "change it now". */
    public fun mutateNow(count: Int = 2) {
        mutate(count)
    }

    private fun mutate(count: Int) {
        if (all.isEmpty()) return
        val wanted = count.coerceIn(1, all.size)
        var taken = 0L
        var done = 0
        var guard = 0
        while (done < wanted && guard < 64) {
            guard++
            val at = (random.next() * all.size).toInt().coerceIn(0, all.size - 1)
            if (taken and (1L shl at) != 0L) continue
            taken = taken or (1L shl at)
            all[at].mutate(random)
            done++
        }
        changes += done
    }

    public fun restart() {
        random.reset()
        phrasesSeen = 0
        changes = 0
        walk = 0f
        for (gene in all) gene.restart()
    }
}
