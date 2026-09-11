package io.github.yuroyami.kiteplayer.audioviz

/**
 * One analysed moment of audio, ready to draw.
 *
 * It is immutable and it is published whole, so the thread that analyses and the thread that
 * draws never share a buffer. Most values are in 0..1. The waveforms ([scope], [scopeLeft] and
 * [scopeRight]) are raw amplitude in -1..1, and [ptsMicros], [bpm], [trend] and [beatInSeconds]
 * carry their own units.
 *
 * The values come in two kinds and the difference matters. The plain ones ([bands], [level],
 * [bass]) are absolute: they say how loud something is between silence and full scale. They are
 * what a meter should show. The relative ones ([bandsRel], [levelRel], [energy]) place the same
 * sound inside the range THIS song has used over the last few seconds, so a quiet track fills the
 * screen as much as a loud one. Anything that should look different for calm and lively music
 * wants the relative kind.
 */
@AudioVizAuthoringApi
public class SpectrumFrame internal constructor(
    /** The media timestamp of the audio this was measured from. Negative when it has none. */
    public val ptsMicros: Long,
    /** One value per bar, low frequency first, already smoothed. Absolute. */
    public val bands: FloatArray,
    /** The falling cap above each bar. */
    public val peaks: FloatArray,
    /** The newest slice of the waveform, for an oscilloscope. */
    public val scope: FloatArray,
    /** Overall loudness of this moment. Absolute. */
    public val level: Float,
    /** Energy below 250 Hz. Kick drums and bass lines live here. Absolute. */
    public val bass: Float,
    /** Energy from 250 Hz to 2 kHz, which is most of what a voice or a guitar occupies. */
    public val mid: Float,
    /** Energy above 2 kHz. Cymbals, consonants and air. */
    public val treble: Float,
    /** How strong an onset is at this exact analysis. Zero most of the time. */
    public val beat: Float,
    /** Jumps to [beat] when one lands, then falls away. This is what a flash or a kick reads. */
    public val pulse: Float,

    // Song-relative. Use these for anything whose look should change with the music.

    /** [bands] placed in the range this song's bars have used. A loud bar for this song reaches 1. */
    public val bandsRel: FloatArray = bands,
    /** [bandsRel] sorted low to high, so a drawing can ask for the top part of the frame cheaply. */
    private val bandsSorted: FloatArray = bandsRel,
    /** [level] placed in this song's own range. */
    public val levelRel: Float = level,
    /** [bass] placed in this song's own range. */
    public val bassRel: Float = bass,
    /** [mid] placed in this song's own range. */
    public val midRel: Float = mid,
    /** [treble] placed in this song's own range. */
    public val trebleRel: Float = treble,

    // What kind of hit it was.

    /** A kick drum landed. 0 to 1. */
    public val kick: Float = 0f,
    /** A snare landed. 0 to 1. */
    public val snare: Float = 0f,
    /** A hat, shaker or cymbal landed. 0 to 1. */
    public val hat: Float = 0f,
    /** How hard the hit actually was, rather than how surprising. A soft hit reads soft. */
    public val onsetStrength: Float = 0f,
    /**
     * How much energy is arriving right now, gate or no gate.
     *
     * Unlike [beat] this has a value on every analysis, so it reads as a continuous sense of
     * something happening rather than as a series of separate events.
     */
    public val novelty: Float = 0f,

    /** Fast attack envelopes, measured in audio time so no display can miss a hit. */
    public val kickPulse: Float = kick,
    public val snarePulse: Float = snare,
    public val hatPulse: Float = hat,

    // Mood.

    /** How loud this moment is for THIS song. 0 is its quietest, 1 its loudest. */
    public val energy: Float = 0f,
    /** How busy it is. 0 is nothing happening, 1 is eight or more onsets a second. */
    public val density: Float = 0f,
    /** Calm at 0, lively at 1. Moves over bars, not frames. */
    public val mood: Float = 0f,
    /** Loudness over a third of a second, in this song's range. */
    public val loudShort: Float = 0f,
    /** Loudness over eight seconds. What the section sounds like. */
    public val loudLong: Float = 0f,
    /** [loudShort] against [loudLong]. Above 1 the music is arriving, below 1 it is leaving. */
    public val trend: Float = 1f,
    /** True on the one analysis where the music comes back after having gone away. */
    public val drop: Boolean = false,
    /** Jumps to 1 on a [drop] and falls over about a second. */
    public val dropPulse: Float = 0f,
    /** True while the music has thinned right out. */
    public val breakdown: Boolean = false,

    // Time.

    /** Beats per minute, or 0 before a tempo has been found. */
    public val bpm: Float = 0f,
    /** How sure the tempo is. Below about 0.4, treat the beat as unknown. */
    public val beatConfidence: Float = 0f,
    /** Where we are between one beat and the next, 0 to 1. */
    public val beatPhase: Float = 0f,
    /** Where we are in a four beat bar, 0 to 1, starting at the downbeat. */
    public val barPhase: Float = 0f,
    /** Where we are in a sixteen beat phrase, 0 to 1. */
    public val phrasePhase: Float = 0f,
    /** Seconds until the next beat is expected, or -1 with no tempo. */
    public val beatInSeconds: Float = -1f,

    // Timbre.

    /** Energy per note of the octave, C first. */
    public val chroma: FloatArray = EMPTY_CHROMA,
    /** The key placed on the circle of fifths, 0 to 1. Related keys sit next to each other. */
    public val keyHue: Float = 0f,
    /** How clearly one key stands out, 0 to 1. */
    public val keyConfidence: Float = 0f,
    /** Where the weight of the spectrum sits. Bright music is high. */
    public val centroid: Float = 0f,
    /** How spread out the spectrum is. A tone is near 0, noise and distortion near 1. */
    public val flatness: Float = 0f,
    /** How wide the stereo image is. 0 is mono, 1 is fully out of phase. */
    public val width: Float = 0f,
    /**
     * The newest samples of the left channel, raw. Plotted against [scopeRight] this is what a
     * vectorscope shows: a line for mono, an open shape for a wide mix. Mono audio gives [scope].
     */
    public val scopeLeft: FloatArray = scope,
    /** The newest samples of the right channel. See [scopeLeft]. */
    public val scopeRight: FloatArray = scope,
) {
    /** Rate shared by clocks and continuous motion; attacks remain distinct from section mood. */
    public val motionRate: Float get() = 0.08f + 0.57f * mood + 0.20f * energy + 0.15f * kickPulse

    /**
     * The bar value that [fraction] of the bars are below, using the relative bars.
     *
     * This is how a drawing says "only the loud ones" without picking a number that is right for
     * one song and wrong for the next. Asking for 0.6 and keeping anything above the answer keeps
     * the loudest 40 percent, whatever is playing.
     */
    public fun bandPercentile(fraction: Float): Float {
        if (bandsSorted.isEmpty()) return 0f
        val at = (fraction.coerceIn(0f, 1f) * (bandsSorted.size - 1)).toInt()
        return bandsSorted[at]
    }

    /**
     * This reading moved [mix] of the way towards [other], for a moment that falls between the two.
     *
     * Everything that changes smoothly is blended. Events are taken from this reading only, so a
     * kick that landed in it is not also half landed in the next one.
     */
    internal fun blend(other: SpectrumFrame, mix: Float): SpectrumFrame {
        val t = mix.coerceIn(0f, 1f)
        fun mixed(from: Float, to: Float): Float = from + (to - from) * t
        fun mixed(from: FloatArray, to: FloatArray): FloatArray =
            if (from.size != to.size) from.copyOf() else FloatArray(from.size) { mixed(from[it], to[it]) }
        // A position that wraps from 1 back to 0 goes the short way round rather than backwards.
        fun turned(from: Float, to: Float): Float {
            var step = to - from
            if (step > 0.5f) step -= 1f
            if (step < -0.5f) step += 1f
            val landed = from + step * t
            return landed - kotlin.math.floor(landed)
        }
        val relative = mixed(bandsRel, other.bandsRel)
        return SpectrumFrame(
            ptsMicros = ptsMicros + ((other.ptsMicros - ptsMicros) * t).toLong(),
            bands = mixed(bands, other.bands),
            peaks = mixed(peaks, other.peaks),
            scope = scope,
            level = mixed(level, other.level),
            bass = mixed(bass, other.bass),
            mid = mixed(mid, other.mid),
            treble = mixed(treble, other.treble),
            beat = beat,
            pulse = mixed(pulse, other.pulse),
            bandsRel = relative,
            bandsSorted = relative.copyOf().also { it.sort() },
            levelRel = mixed(levelRel, other.levelRel),
            bassRel = mixed(bassRel, other.bassRel),
            midRel = mixed(midRel, other.midRel),
            trebleRel = mixed(trebleRel, other.trebleRel),
            kick = kick,
            snare = snare,
            hat = hat,
            kickPulse = mixed(kickPulse, other.kickPulse),
            snarePulse = mixed(snarePulse, other.snarePulse),
            hatPulse = mixed(hatPulse, other.hatPulse),
            onsetStrength = onsetStrength,
            novelty = mixed(novelty, other.novelty),
            energy = mixed(energy, other.energy),
            density = mixed(density, other.density),
            mood = mixed(mood, other.mood),
            loudShort = mixed(loudShort, other.loudShort),
            loudLong = mixed(loudLong, other.loudLong),
            trend = mixed(trend, other.trend),
            drop = drop,
            dropPulse = mixed(dropPulse, other.dropPulse),
            breakdown = breakdown,
            bpm = other.bpm,
            beatConfidence = mixed(beatConfidence, other.beatConfidence),
            beatPhase = turned(beatPhase, other.beatPhase),
            barPhase = turned(barPhase, other.barPhase),
            phrasePhase = turned(phrasePhase, other.phrasePhase),
            beatInSeconds = mixed(beatInSeconds, other.beatInSeconds),
            chroma = mixed(chroma, other.chroma),
            keyHue = keyHue,
            keyConfidence = mixed(keyConfidence, other.keyConfidence),
            centroid = mixed(centroid, other.centroid),
            flatness = mixed(flatness, other.flatness),
            width = mixed(width, other.width),
            scopeLeft = scopeLeft,
            scopeRight = scopeRight,
        )
    }

    /** Replace one-shot events without copying any sample buffers. */
    internal fun withEvents(
        beat: Float = 0f, kick: Float = 0f, snare: Float = 0f, hat: Float = 0f,
        onsetStrength: Float = 0f, drop: Boolean = false,
    ): SpectrumFrame = SpectrumFrame(
        ptsMicros = ptsMicros,
        bands = bands,
        peaks = peaks,
        scope = scope,
        level = level,
        bass = bass,
        mid = mid,
        treble = treble,
        beat = beat,
        pulse = pulse,
        bandsRel = bandsRel,
        bandsSorted = bandsSorted,
        levelRel = levelRel,
        bassRel = bassRel,
        midRel = midRel,
        trebleRel = trebleRel,
        kick = kick,
        snare = snare,
        hat = hat,
        onsetStrength = onsetStrength,
        novelty = novelty,
        kickPulse = kickPulse,
        snarePulse = snarePulse,
        hatPulse = hatPulse,
        energy = energy,
        density = density,
        mood = mood,
        loudShort = loudShort,
        loudLong = loudLong,
        trend = trend,
        drop = drop,
        dropPulse = dropPulse,
        breakdown = breakdown,
        bpm = bpm,
        beatConfidence = beatConfidence,
        beatPhase = beatPhase,
        barPhase = barPhase,
        phrasePhase = phrasePhase,
        beatInSeconds = beatInSeconds,
        chroma = chroma,
        keyHue = keyHue,
        keyConfidence = keyConfidence,
        centroid = centroid,
        flatness = flatness,
        width = width,
        scopeLeft = scopeLeft,
        scopeRight = scopeRight,
    )

    public companion object {
        private val EMPTY_CHROMA = FloatArray(12)

        /** What a visualiser draws before any audio has arrived. */
        public fun silent(bandCount: Int, scopePoints: Int): SpectrumFrame {
            val empty = FloatArray(bandCount)
            return SpectrumFrame(
                ptsMicros = -1L,
                bands = empty,
                peaks = FloatArray(bandCount),
                scope = FloatArray(scopePoints),
                level = 0f,
                bass = 0f,
                mid = 0f,
                treble = 0f,
                beat = 0f,
                pulse = 0f,
                bandsRel = empty,
                bandsSorted = empty,
            )
        }
    }
}
