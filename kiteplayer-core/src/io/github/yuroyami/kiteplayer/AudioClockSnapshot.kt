package io.github.yuroyami.kiteplayer

/**
 * One coherent reading of the selected audio output's presentation clock.
 *
 * [position] is the source media timestamp estimated to be audible at [hostTimeNanos], after
 * device buffering has already been accounted for. Null means that no audio anchor is available,
 * including while seeking, before the first device report and after teardown. Zero and negative
 * positions are valid timestamps. This never substitutes a requested seek target or a video clock.
 *
 * [rate] is media seconds per host second, zero while held. [generation] identifies continuous
 * audio delivery and also changes on an audio track switch that leaves video running. It matches
 * the generation delivered to [AudioTap]. [quality] describes the device timing evidence, not a
 * guarantee about wireless latency or when a display actually presents a picture.
 *
 * Read a fresh snapshot for every display frame. To estimate the media position after a known
 * display delay, add `rate * delay` to [position]. A display callback from another clock domain
 * must first be translated to this clock's domain. Do not subtract audio latency again.
 */
public class AudioClockSnapshot internal constructor(
    public val position: Pts?,
    public val hostTimeNanos: Long,
    public val rate: Double,
    public val generation: Generation,
    public val quality: LatencyQuality,
) {
    /** True when this reading has an audio timestamp; silence can have a valid timestamp. */
    public val isValid: Boolean get() = position != null

    internal fun at(hostTimeNanos: Long): AudioClockSnapshot = AudioClockSnapshot(
        position = position?.let { Pts(it.micros + ((hostTimeNanos - this.hostTimeNanos) / 1_000.0 * rate).toLong()) },
        hostTimeNanos = hostTimeNanos,
        rate = rate,
        generation = generation,
        quality = quality,
    )

    internal companion object {
        fun unavailable(generation: Generation, hostTimeNanos: Long = 0L): AudioClockSnapshot =
            AudioClockSnapshot(null, hostTimeNanos, 0.0, generation, LatencyQuality.Unreliable)
    }
}
