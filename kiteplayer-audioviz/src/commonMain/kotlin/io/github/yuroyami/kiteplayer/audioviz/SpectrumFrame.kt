package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.math.sqrt

/**
 * One analysed moment of audio, ready to draw.
 *
 * It is published whole and never written afterwards: every analysis allocates new arrays, so a
 * frame a drawing holds does not change underneath it. Several views share one frame, and the
 * array properties are plain arrays for compatibility, so treat them as read-only and copy an
 * array before changing it. Most values are in 0..1. The waveforms ([scope], [scopeLeft] and
 * [scopeRight]) are raw amplitude with nominal full scale one, and [ptsMicros], [bpm], [trend] and [beatInSeconds]
 * carry their own units.
 *
 * [power] contains raw unweighted measurements, [programme] contains independently timed
 * momentary K-weighted loudness, and [drivers] contains display heights under one shared gain.
 * Legacy relative level names alias those same heights; no band or drawing has a private gain.
 */
@AudioVizAuthoringApi
public class SpectrumFrame internal constructor(
    /** The media timestamp of the audio this was measured from. Consult [hasTimestamp] for validity. */
    public val ptsMicros: Long,
    /** Shared-gain fast display height per ERB band, low frequency first. */
    public val bands: FloatArray,
    /** The falling cap above each bar. */
    public val peaks: FloatArray,
    /** The newest slice of the waveform, for an oscilloscope. */
    public val scope: FloatArray,
    /** Shared-gain fast overall energy height. Raw power is available separately in [power]. */
    public val level: Float,
    /** Fast height for integrated energy below 250 Hz, including LFE. */
    public val bass: Float,
    /** Energy from 250 Hz to 2 kHz, which is most of what a voice or a guitar occupies. */
    public val mid: Float,
    /** Energy above 2 kHz. Cymbals, consonants and air. */
    public val treble: Float,
    /**
     * Scalar onset energy projection, from hits only. Player views project delivered [events]; a
     * raw analysis reports newly detected hits whose individual times are in [detections], not
     * [ptsMicros]. A detection below [AudioDetection.HIT_CONFIDENCE] stays in [events] and in
     * [detections] with its own confidence, and is not projected here.
     */
    public val beat: Float,
    /** Jumps to [beat] when one lands, then falls away. This is what a flash or a kick reads. */
    public val pulse: Float,

    // Compatibility aliases of the same shared-gain drivers.

    /** Compatibility alias of [bands], with the same gain and smoothing. */
    public val bandsRel: FloatArray = bands,
    /** [bandsRel] sorted low to high, so a drawing can ask for the top part of the frame cheaply. */
    private val bandsSorted: FloatArray = bandsRel,
    /** Compatibility alias of [level]. */
    public val levelRel: Float = level,
    /** Compatibility alias of [bass]. */
    public val bassRel: Float = bass,
    /** Compatibility alias of [mid]. */
    public val midRel: Float = mid,
    /** Compatibility alias of [treble]. */
    public val trebleRel: Float = treble,

    // What kind of hit it was.

    /** Strength of the strongest low-transient hit, 0..1. Does not identify a kick drum. */
    public val kick: Float = 0f,
    /** Strength of the strongest body/crack-transient hit, 0..1. Does not identify a snare. */
    public val snare: Float = 0f,
    /** Strength of the strongest high-transient hit, 0..1. Does not identify a hi-hat or cymbal. */
    public val hat: Float = 0f,
    /** How hard the hit actually was, rather than how surprising. A soft hit reads soft. */
    public val onsetStrength: Float = 0f,
    /**
     * How much energy is arriving right now, gate or no gate.
     *
     * Unlike [beat] this has a value on every analysis, so it reads as a continuous sense of
     * something happening rather than as a series of separate events. It runs 0..4: positive
     * spectral change against the detector's recent mean, not an energy height.
     */
    public val novelty: Float = 0f,

    /** Fast attack envelopes of hits, measured in audio time so no display can miss one. */
    public val kickPulse: Float = kick,
    public val snarePulse: Float = snare,
    public val hatPulse: Float = hat,

    // Mood.

    /** Compatibility alias of the shared-gain fast overall energy height. */
    public val energy: Float = 0f,
    /** How busy it is. 0 is nothing happening, 1 is eight or more onsets a second. */
    public val density: Float = 0f,
    /** Calm at 0, lively at 1. Moves over bars, not frames. */
    public val mood: Float = 0f,
    /** Shared-gain fast overall energy height, used for short-versus-slow section contrast. */
    public val loudShort: Float = 0f,
    /** Shared-gain slow overall energy height (100 ms rise and 2 s fall). */
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
    /** Compatibility projection of usable beat support, or zero. Diagnostic scores are in [rhythm]. */
    public val beatConfidence: Float = 0f,
    /** Where we are between one beat and the next, 0 to 1. */
    public val beatPhase: Float = 0f,
    /** Legacy bar phase. The live pulse tracker does not establish meter and publishes zero. */
    public val barPhase: Float = 0f,
    /** Legacy phrase phase. The live pulse tracker does not establish phrases and publishes zero. */
    public val phrasePhase: Float = 0f,
    /** Seconds until the next beat is expected, or -1 with no tempo. */
    public val beatInSeconds: Float = -1f,

    // Timbre.

    /**
     * Short-term pitch-class profile from the long key window, C first, each pitched analysis
     * peaking at one. It decays to zero during unpitched audio.
     */
    public val chroma: FloatArray = EMPTY_CHROMA,
    /** The known key on the circle of fifths, 0 to 1, with a minor key at its relative major. */
    public val keyHue: Float = 0f,
    /** The key's confidence, 0 to 1, and zero while the key is unknown. */
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
    /** Continuous audio identity, matching the player's audible clock for player-fed analysis. */
    public val generation: Generation = Generation.Initial,
    /** Whether [ptsMicros] is known. Zero and negative timestamps can both be valid. */
    public val hasTimestamp: Boolean = true,
    /** Local analysis continuity within [generation], including queue gaps and format changes. */
    public val analysisRevision: Long = 0L,
    /** Whether the short analysis window is available. Silence is [AnalysisAvailability.Ready]. */
    public val availability: AnalysisAvailability = if (hasTimestamp) AnalysisAvailability.Ready else AnalysisAvailability.Unavailable,
    /** Calibrated short-window power, without display scaling. Null until a complete window exists. */
    public val power: PowerSpectrum? = null,
    /** Independently timed ungated 400 ms programme measurement, including unavailable/warmup. */
    public val programme: ProgrammeLoudness? = null,
    /** Fast/slow/peak energy heights with one shared bounded gain and saturation diagnostics. */
    public val drivers: EnergyDrivers? = null,
    /** True while the playback clock stands still: the levels are kept, but nothing is heard. */
    public val held: Boolean = false,
    /** Exact mono trace interval and trigger, null before a complete input window. */
    public val scopeMetadata: WaveformMetadata? = null,
    /** Exact paired trace interval and trigger, null before a complete input window. */
    public val stereoScopeMetadata: WaveformMetadata? = null,
    /** Original detector publication and completion watermark, before per-view delivery. */
    public val detections: AudioDetections? = null,
    /** Ordered events delivered once to this view, including per-event lateness and discard counts. */
    public val events: AudioEventDelivery? = null,
    /** Pulse rate and phase evidence, separately timed and unavailable until analysis is ready. */
    public val rhythm: RhythmEstimate? = null,
    /** This analysis's structural publication and watermark, separate from the transient [detections]. */
    public val structure: AudioDetections? = null,
    /** The key supported by pitched audio, separately timed, or null when unknown. */
    public val key: KeyEstimate? = null,
) {
    /** Conservative primitive payload charge; shared legacy array aliases are charged once. */
    internal val retainedPayloadBytes: Long
        get() {
            val arrays = arrayOf(bands, peaks, scope, bandsRel, bandsSorted, scopeLeft, scopeRight, chroma)
            var bytes = 512L // Scalars and fixed-size measurement/driver metadata.
            for (index in arrays.indices) {
                var duplicate = false
                for (earlier in 0 until index) if (arrays[earlier] === arrays[index]) { duplicate = true; break }
                if (!duplicate) bytes += arrays[index].size * 4L
            }
            power?.let { bytes += (it.binCount + it.bandCount) * 4L + (it.bandCount + 1) * 8L }
            drivers?.let { bytes += it.bandCount * 12L }
            detections?.let { bytes += it.size * 64L }
            structure?.let { bytes += it.size * 64L }
            events?.let { bytes += it.size * 80L }
            return bytes
        }

    /** Recommended trace amplitude multiplier, from the same gain as every energy driver. */
    public val waveformGain: Float get() = sqrt(drivers?.powerGain ?: 1.0).toFloat()

    /**
     * How much there is to hear, 0 to 1: zero while the clock is [held] or the audio is silent,
     * one from a quiet pad upwards.
     *
     * Everything that travels moves by this. A paused player keeps its levels on screen, so the
     * levels alone cannot tell a drawing to stand still, and a song's silent opening is silent
     * whatever the section mood says.
     */
    public val audible: Float get() = if (held) 0f else ((level - AUDIBLE_FLOOR) / AUDIBLE_RAMP).coerceIn(0f, 1f)

    /**
     * Rate shared by clocks and continuous motion; attacks remain distinct from section mood.
     *
     * The floor is what keeps a quiet passage from freezing. It is small on purpose: the standard
     * asks that a quiet passage produce at most a fifth of the change music produces. It runs only
     * while something is [audible]: a paused player and a silence hold the picture still.
     */
    public val motionRate: Float get() = audible * (0.03f + 0.57f * mood + 0.25f * energy + 0.15f * kickPulse)

    /**
     * The bar value that [fraction] of the bars are below, using the relative bars.
     *
     * A rank inside one frame keeps the same share of the picture whatever plays, so a quiet
     * passage lights as many bars as a loud one. Every bar is already a height under one shared
     * gain, so compare it with a fixed level instead.
     */
    @Deprecated("A rank inside one frame hides how loud the music is. Compare the height with a fixed level.")
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
        require(generation == other.generation && analysisRevision == other.analysisRevision && hasTimestamp && other.hasTimestamp)
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
        val targetMicros = ptsMicros + ((other.ptsMicros - ptsMicros) * t).toLong()
        val pulseEstimate = if (t >= 1f) other.rhythm else rhythm?.at(targetMicros)
        return SpectrumFrame(
            ptsMicros = targetMicros,
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
            bpm = pulseEstimate?.bpm ?: bpm,
            beatConfidence = pulseEstimate?.let { if (it.usable) it.beatSupport else 0f } ?: beatConfidence,
            beatPhase = pulseEstimate?.beatPhase ?: turned(beatPhase, other.beatPhase),
            barPhase = turned(barPhase, other.barPhase),
            phrasePhase = turned(phrasePhase, other.phrasePhase),
            beatInSeconds = pulseEstimate?.beatInSeconds ?: beatInSeconds,
            chroma = mixed(chroma, other.chroma),
            keyHue = keyHue,
            keyConfidence = mixed(keyConfidence, other.keyConfidence),
            centroid = mixed(centroid, other.centroid),
            flatness = mixed(flatness, other.flatness),
            width = mixed(width, other.width),
            scopeLeft = scopeLeft,
            scopeRight = scopeRight,
            generation = generation,
            hasTimestamp = hasTimestamp,
            analysisRevision = analysisRevision,
            availability = availability,
            power = power,
            programme = programme,
            drivers = if (drivers != null && other.drivers != null) drivers.blend(other.drivers, t) else drivers,
            scopeMetadata = scopeMetadata,
            stereoScopeMetadata = stereoScopeMetadata,
            detections = detections,
            rhythm = pulseEstimate,
            structure = structure,
            key = key,
        )
    }

    /**
     * The same moment with its pulse held, for a paused playback clock: no beat progression or
     * countdown, while the rate estimate stays readable as a diagnostic.
     */
    internal fun withPulseHeld(): SpectrumFrame = if (held) this else
        withEvents(beat, kick, snare, hat, onsetStrength, drop, events,
            rhythm = rhythm?.held(), beatConfidence = 0f, beatInSeconds = -1f, held = true)

    /** Replace one-shot events without copying any sample buffers. */
    internal fun withEvents(
        beat: Float = 0f, kick: Float = 0f, snare: Float = 0f, hat: Float = 0f,
        onsetStrength: Float = 0f, drop: Boolean = false,
        events: AudioEventDelivery? = null,
        rhythm: RhythmEstimate? = this.rhythm,
        beatConfidence: Float = this.beatConfidence,
        beatInSeconds: Float = this.beatInSeconds,
        held: Boolean = this.held,
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
        generation = generation,
        hasTimestamp = hasTimestamp,
        analysisRevision = analysisRevision,
        availability = availability,
        power = power,
        programme = programme,
        drivers = drivers,
        held = held,
        scopeMetadata = scopeMetadata,
        stereoScopeMetadata = stereoScopeMetadata,
        detections = detections,
        events = events,
        rhythm = rhythm,
        structure = structure,
        key = key,
    )

    /** Keep every delivered record while providing the old strongest-hit projection for envelopes. */
    internal fun withDeliveredEvents(delivery: AudioEventDelivery): SpectrumFrame {
        // A reset can land between the independent feature and event reads.
        if (generation != delivery.generation || analysisRevision != delivery.analysisRevision) {
            return silent(bands.size, scope.size)
        }
        var beat = 0f
        var kick = 0f
        var snare = 0f
        var hat = 0f
        var drop = false
        for (index in 0 until delivery.size) {
            val hit = delivery[index].event.detection
            when (hit.kind) {
                AudioEventKind.Onset -> if (hit.isHit) beat = maxOf(beat, hit.strength)
                AudioEventKind.LowTransient -> if (hit.isHit) kick = maxOf(kick, hit.strength)
                AudioEventKind.BodyTransient -> if (hit.isHit) snare = maxOf(snare, hit.strength)
                AudioEventKind.HighTransient -> if (hit.isHit) hat = maxOf(hat, hit.strength)
                AudioEventKind.EnergyRise -> drop = true
                AudioEventKind.Drop -> drop = true
                AudioEventKind.SectionBoundary, AudioEventKind.Breakdown -> Unit
            }
        }
        return withEvents(beat, kick, snare, hat, beat, drop, delivery)
    }

    public companion object {
        private val EMPTY_CHROMA = FloatArray(12)

        /** What a visualiser draws before any audio has arrived. */
        /** Below this height nothing is heard; a quiet pad reads about 0.10, well above the ramp. */
        private const val AUDIBLE_FLOOR = 0.015f
        private const val AUDIBLE_RAMP = 0.045f

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
                hasTimestamp = false,
            )
        }
    }
}
