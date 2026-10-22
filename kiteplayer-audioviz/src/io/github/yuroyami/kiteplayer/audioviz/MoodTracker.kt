package io.github.yuroyami.kiteplayer.audioviz

/**
 * Artistic activity and section cues from already normalised energy, onset density and tempo.
 * There is no extra automatic gain or energy smoothing here: fast and slow drivers come from
 * the analyser's shared reference. Recognition of formal musical sections is a separate concern.
 */
internal class MoodTracker(private val analysesPerSecond: Float) {
    private val onsetWindow = FloatArray((analysesPerSecond * 4f).toInt().coerceAtLeast(16))
    private var onsetIndex = 0
    private var onsetFilled = 0

    /** Shared-gain fast energy height. */
    var energy: Float = 0f
        private set

    /** How busy it is: onsets per second, 0 at none and 1 at eight or more. */
    var density: Float = 0f
        private set

    /** Calm at 0, lively at 1. Moves over bars, never over frames. */
    var mood: Float = 0f
        private set

    /** Shared-gain fast energy height. */
    var loudShort: Float = 0f
        private set

    /** Shared-gain slow energy height. */
    var loudLong: Float = 0f
        private set

    /** Short against long. Above 1 the music is arriving, below 1 it is leaving. */
    var trend: Float = 1f
        private set

    /** True on the single analysis where a drop lands. */
    var drop: Boolean = false
        private set

    /** Jumps to 1 on an energy recovery then falls away over about 0.8 seconds. */
    var dropPulse: Float = 0f
        private set

    /** True while the music has thinned right out. */
    var breakdown: Boolean = false
        private set

    private var quietFor = 0f
    private var sinceQuiet = 99f

    fun feed(
        fastEnergy: Float,
        slowEnergy: Float,
        onsetStrength: Float,
        beatConfidence: Float,
        deltaSeconds: Float,
    ) {
        energy = fastEnergy
        loudShort = fastEnergy
        loudLong = slowEnergy

        onsetWindow[onsetIndex] = if (onsetStrength > 0f) 1f else 0f
        onsetIndex = (onsetIndex + 1) % onsetWindow.size
        if (onsetFilled < onsetWindow.size) onsetFilled++
        var onsets = 0f
        for (index in 0 until onsetFilled) onsets += onsetWindow[index]
        val seconds = onsetFilled / analysesPerSecond
        density = if (seconds <= 0f) 0f else (onsets / seconds / BUSY_ONSETS_PER_SECOND).coerceIn(0f, 1f)

        trend = if (loudLong < 0.02f) 1f else (loudShort / loudLong).coerceIn(0f, 3f)

        val target = (0.5f * energy + 0.3f * density + 0.2f * beatConfidence).coerceIn(0f, 1f)
        val step = MOOD_PER_SECOND * deltaSeconds
        mood += (target - mood).coerceIn(-step, step)

        readSections(deltaSeconds, onsetStrength)
    }

    private fun readSections(deltaSeconds: Float, onsetStrength: Float) {
        drop = false
        dropPulse = (dropPulse - deltaSeconds * 1.2f).coerceAtLeast(0f)

        if (trend < 0.7f) {
            sinceQuiet = 0f
            quietFor += deltaSeconds
        } else {
            sinceQuiet += deltaSeconds
            quietFor = 0f
        }

        // A drop is the music coming back after having gone away. Both halves matter: a loud
        // passage that was always loud is not a drop, it is just loud.
        // A smooth pad also swells through its relative loudness range. A drop needs an
        // actual attack as the music returns, otherwise quiet passages trigger camera cuts.
        if (trend > 1.6f && sinceQuiet < 8f && dropPulse <= 0f && onsetStrength > 0.15f) {
            drop = true
            dropPulse = 1f
            sinceQuiet = 99f
        }
        breakdown = trend < 0.5f && quietFor > 2f
    }

    fun reset() {
        onsetWindow.fill(0f)
        onsetIndex = 0
        onsetFilled = 0
        energy = 0f
        density = 0f
        mood = 0f
        loudShort = 0f
        loudLong = 0f
        trend = 1f
        drop = false
        dropPulse = 0f
        breakdown = false
        quietFor = 0f
        sinceQuiet = 99f
    }

    private companion object {
        const val BUSY_ONSETS_PER_SECOND = 8f
        /** How fast mood may travel. A whole swing takes five seconds. */
        const val MOOD_PER_SECOND = 0.2f
    }
}
