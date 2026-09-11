package io.github.yuroyami.kiteplayer.audioviz.viz

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

    public var value: Float = default
        set(new) {
            field = new.coerceIn(min, max)
        }

    /** Where [value] sits between [min] and [max], 0 to 1. Handy for a slider. */
    public val fraction: Float get() = (value - min) / (max - min)

    public fun reset() {
        value = default
    }
}
