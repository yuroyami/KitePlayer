package io.github.yuroyami.kiteplayer.audioviz.viz

import kotlin.math.round

/**
 * One setting a drawing lets a person change while it runs.
 *
 * A drawing reads [value] every frame, so a change shows at once. Whatever is written to [value]
 * is kept between [min] and [max].
 */
public class VizParam(
    public val name: String,
    public val min: Float,
    public val max: Float,
    public val default: Float,
) {
    init {
        require(min < max) { "min must be below max for $name" }
        require(default in min..max) { "the default for $name must be between $min and $max" }
    }

    /** Built-in controls may use whole steps or an on/off switch without changing the public API. */
    internal var step: Float = 0f
    internal var toggle: Boolean = false
    internal var choices: List<String> = emptyList()
    internal var shownWhen: () -> Boolean = { true }

    public var value: Float = default
        set(new) {
            if (new.isNaN()) return
            val bounded = new.coerceIn(min, max)
            field = if (step > 0f) (min + round((bounded - min) / step) * step).coerceIn(min, max)
                else bounded
        }

    /** Where [value] sits between [min] and [max], 0 to 1. Handy for a slider. */
    public val fraction: Float get() = (value - min) / (max - min)

    public fun reset() {
        value = default
    }
}
