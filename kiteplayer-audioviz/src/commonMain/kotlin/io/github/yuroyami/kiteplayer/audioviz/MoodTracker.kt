package io.github.yuroyami.kiteplayer.audioviz

/**
 * Answers the one question a visualiser actually needs: is this calm or is this lively?
 *
 * The problem it solves is that every other reading here is absolute. A magnitude is placed on a
 * scale from silence to full scale, and a real song only ever uses a slice of that. Where the
 * slice sits is decided by how the track was mastered, not by how it feels, so a gentle song
 * mastered loud and a busy song mastered quietly come out the same.
 *
 * So this watches the range the song has actually used over the last twenty seconds and reports
 * where the present moment sits inside it. A quiet intro reads near zero and the biggest chorus
 * so far reads near one, on every song, whatever level it was mastered at.
 *
 * [mood] mixes that with how often something is happening and with how sure the tempo is, then
 * moves slowly on purpose: it should change over a section, not over a frame.
 */
internal class MoodTracker(private val analysesPerSecond: Float) {

    private val loudnessRange = RunningRange(
        adaptPerSecond = 2f,
        // Eight decibels is about how much a gently swelling pad moves. Wider than that and such
        // a song sits in the middle of its own range forever and never fills a drawing.
        minimumSpan = 8f,
        initialLow = -50f,
        initialHigh = -20f,
    )

    /**
     * Loudness as the ear would judge it, rather than at this exact instant.
     *
     * A drum track is mostly gaps. Measured instant by instant it spends far more time near its
     * floor than near its peaks, so it reads quieter than a steady pad that is genuinely softer.
     * People do not hear it that way: the ear holds a loud moment for a fraction of a second. So
     * the reading rises almost at once and falls back slowly, which is what every loudness meter
     * ever built does.
     */
    private var heldLoudness = -60f

    // The range the bars are placed in. The top follows the loudest bar; the bottom follows the
    // quiet quarter of the spectrum. Following only the loudest bar would drag the bottom up to just
    // under the top, so on busy music every bar except the bass would read as zero and every drawing
    // shaped by the spectrum would show a single lobe.
    private var bandHigh = BAND_HIGH_FLOOR
    private var bandLow = 0f

    private val onsetWindow = FloatArray((analysesPerSecond * 4f).toInt().coerceAtLeast(16))
    private var onsetIndex = 0
    private var onsetFilled = 0

    /** Loudness now, placed in this song's own range. 0 is its quietest, 1 its loudest. */
    var energy: Float = 0f
        private set

    /** How busy it is: onsets per second, 0 at none and 1 at eight or more. */
    var density: Float = 0f
        private set

    /** Calm at 0, lively at 1. Moves over bars, never over frames. */
    var mood: Float = 0f
        private set

    /** Loudness over about a third of a second. */
    var loudShort: Float = 0f
        private set

    /** Loudness over about eight seconds. This is what a section sounds like. */
    var loudLong: Float = 0f
        private set

    /** Short against long. Above 1 the music is arriving, below 1 it is leaving. */
    var trend: Float = 1f
        private set

    /** True on the single analysis where a drop lands. */
    var drop: Boolean = false
        private set

    /** Jumps to 1 on a drop then falls away, so a drawing can react over a bar. */
    var dropPulse: Float = 0f
        private set

    /** True while the music has thinned right out. */
    var breakdown: Boolean = false
        private set

    private var quietFor = 0f
    private var sinceQuiet = 99f

    fun feed(
        loudnessDecibels: Float,
        loudestBand: Float,
        quietBand: Float,
        onsetStrength: Float,
        beatConfidence: Float,
        deltaSeconds: Float,
    ) {
        // Below this nothing is playing, and a range built from near-silence would turn the noise
        // floor into a light show.
        val silent = loudnessDecibels < SILENCE_DECIBELS
        val rate = if (loudnessDecibels > heldLoudness) ATTACK_PER_SECOND else RELEASE_PER_SECOND
        heldLoudness += (loudnessDecibels - heldLoudness) * (rate * deltaSeconds).coerceIn(0f, 1f)
        energy = if (silent) 0f else loudnessRange.place(heldLoudness, deltaSeconds)
        followBands(loudestBand, quietBand, deltaSeconds)

        onsetWindow[onsetIndex] = if (onsetStrength > 0f) 1f else 0f
        onsetIndex = (onsetIndex + 1) % onsetWindow.size
        if (onsetFilled < onsetWindow.size) onsetFilled++
        var onsets = 0f
        for (index in 0 until onsetFilled) onsets += onsetWindow[index]
        val seconds = onsetFilled / analysesPerSecond
        density = if (seconds <= 0f) 0f else (onsets / seconds / BUSY_ONSETS_PER_SECOND).coerceIn(0f, 1f)

        val linear = energy
        loudShort += (linear - loudShort) * follow(deltaSeconds, 0.3f)
        loudLong += (linear - loudLong) * follow(deltaSeconds, 8f)
        trend = if (loudLong < 0.02f) 1f else (loudShort / loudLong).coerceIn(0f, 3f)

        val target = (0.5f * energy + 0.3f * density + 0.2f * beatConfidence).coerceIn(0f, 1f)
        val step = MOOD_PER_SECOND * deltaSeconds
        mood += (target - mood).coerceIn(-step, step)

        readSections(deltaSeconds, onsetStrength)
    }

    /** Where each band sits in the range this song's bands have used. */
    fun placeBand(value: Float): Float = ((value - bandLow) / (bandHigh - bandLow)).coerceIn(0f, 1f)

    /**
     * Moves the bars' range on by one analysis.
     *
     * The top jumps to any bar louder than it and eases back over a few seconds. The bottom glides
     * towards the quiet quarter of the spectrum. Both are held a minimum distance apart, so a
     * steady passage cannot collapse the range and turn small wobbles into big swings, and the top
     * never falls below a floor, so silence reads as silence rather than as a screen of full bars.
     */
    private fun followBands(loudest: Float, quiet: Float, deltaSeconds: Float) {
        bandHigh = if (loudest > bandHigh) loudest else bandHigh - BAND_RELEASE_PER_SECOND * deltaSeconds
        bandHigh = bandHigh.coerceAtLeast(BAND_HIGH_FLOOR)
        bandLow += (quiet - bandLow) * (deltaSeconds / BAND_FLOOR_SECONDS).coerceIn(0f, 1f)
        if (bandHigh - bandLow < BAND_MINIMUM_SPAN) bandLow = bandHigh - BAND_MINIMUM_SPAN
        bandLow = bandLow.coerceAtLeast(0f)
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

    /** The share of the gap to close this analysis so the value settles in [seconds]. */
    private fun follow(deltaSeconds: Float, seconds: Float): Float =
        (deltaSeconds / seconds).coerceIn(0f, 1f)

    fun reset() {
        heldLoudness = -60f
        loudnessRange.reset()
        bandHigh = BAND_HIGH_FLOOR
        bandLow = 0f
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
        const val SILENCE_DECIBELS = -60f

        /** The top of the bars' range never drops below this, so silence stays empty. */
        const val BAND_HIGH_FLOOR = 0.25f

        /** How fast the top of the bars' range eases back after a loud bar. */
        const val BAND_RELEASE_PER_SECOND = 0.35f

        /** Seconds for the bottom of the range to settle on a new quiet level. */
        const val BAND_FLOOR_SECONDS = 2f

        /** The least room the bars' range is allowed. */
        const val BAND_MINIMUM_SPAN = 0.22f

        /** Snaps up to a loud moment in about fifty milliseconds. */
        const val ATTACK_PER_SECOND = 20f

        /** And lets go of it over about half a second, which is roughly how long the ear holds one. */
        const val RELEASE_PER_SECOND = 2.2f
        const val BUSY_ONSETS_PER_SECOND = 8f
        /** How fast mood may travel. A whole swing takes five seconds, which is about two bars. */
        const val MOOD_PER_SECOND = 0.2f
    }
}
